package com.example.scraper.service;

import com.example.scraper.util.HttpPageFetcher;
import jakarta.annotation.PreDestroy;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Fetches an aggregator search-results page (ixxx.com — but the actual
 * category and the embedded source site are user-controlled via the
 * URL's query params) and pulls every external video page link it
 * can find. Used by the batch-pipeline UI to bulk-import URLs without
 * copy/pasting them one by one.
 *
 * <p><b>Redirect resolution:</b> ixxx.com doesn't put the real source
 * URL in the anchor. The href is an aggregator-side redirect
 * (e.g. {@code /out/?l=...&c=...&v=...}) that lands on the actual
 * source page after one or more server-side 3xx hops (and occasionally
 * a {@code <meta http-equiv="refresh">} click-through). We fetch each
 * redirect in parallel through {@link HttpPageFetcher}, which drives a
 * real headless Chrome via Selenium — necessary because ixxx.com now
 * serves a Cloudflare JS proof-of-work challenge to non-browser
 * clients, which {@code java.net.http.HttpClient} cannot pass.
 *
 * <p><b>Source site handling:</b> we don't assume the source is
 * xhamster. We look at every resolved link, group by host, and pick
 * whichever external host has the most links. The caller can also pin
 * a specific source via {@code sourceHost}.
 *
 * <p><b>SSRF guard:</b> refuses localhost, *.local, and any IP that
 * resolves to a loopback / private / link-local / multicast range.
 */
@Service
public class IxxxDiscoveryService {

    private static final int MAX_REDIRECTS_PER_PAGE = 60;
    private static final int REDIRECT_POOL_SIZE = 10;
    private static final int REDIRECT_RESOLVE_TIMEOUT_SECONDS = 60;

    /** Path prefixes that are NOT video pages on any supported source site. */
    private static final Pattern NON_VIDEO_PATH = Pattern.compile(
            "^/(?:channels|categories|users?|user-profile|amateurs?|pornstars?"
                    + "|models?|search|live|login|signup|sign-in|register|about"
                    + "|terms|privacy|dmca|contact|faq|help|press|partners|api"
                    + "|business|stats|tags?|genres|external_links?|premium|my"
                    + "|gay|shemale|hentai)\\b"
                    + "|^/(?:trending|popular|best|top-rated|most-viewed|longest|new|latest|hd)\\b",
            Pattern.CASE_INSENSITIVE);

    /** Aggregator redirect paths we resolve. ixxx.com uses {@code /out/}. */
    private static final Pattern AGGREGATOR_REDIRECT_PATH = Pattern.compile(
            "^/(?:out|redirect|goto|link)/?$", Pattern.CASE_INSENSITIVE);

    private final HttpPageFetcher pageFetcher;

    /**
     * Orchestrates parallel resolution calls. Actual driver use is
     * capped at {@code app.fetcher.pool-size} (default 4) inside
     * {@link HttpPageFetcher}; this pool can be larger — extra threads
     * just queue waiting for a driver.
     */
    private final ExecutorService redirectExecutor = Executors.newFixedThreadPool(
            REDIRECT_POOL_SIZE,
            r -> {
                Thread t = new Thread(r, "ixxx-redirect-resolver");
                t.setDaemon(true);
                return t;
            });

    public IxxxDiscoveryService(HttpPageFetcher pageFetcher) {
        this.pageFetcher = pageFetcher;
    }

    public DiscoveryResult discover(String searchUrl, String sourceHost, boolean videosOnly)
            throws IOException {
        if (searchUrl == null || searchUrl.isBlank()) {
            throw new IllegalArgumentException("url is required");
        }
        String url = searchUrl.trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://" + url;
        }

        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid url: " + e.getMessage(), e);
        }
        if (!"http".equalsIgnoreCase(uri.getScheme())
                && !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("url must be http(s)");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("url must include a host");
        }
        if (isPrivateHost(host)) {
            throw new IllegalArgumentException("url host is not allowed: " + host);
        }
        String aggregatorHost = host.toLowerCase(Locale.ROOT);

        // Fetch the search-results HTML via headless Chrome so the
        // Cloudflare JS challenge is solved before Jsoup parses.
        String html = pageFetcher.fetch(url, "https://www.google.com/");
        Document doc = Jsoup.parse(html, url);

        // Step 1: split anchors into "needs redirect resolution" and
        // "already points at an external site".
        List<String> redirectUrls = new ArrayList<>();
        List<String> directExternal = new ArrayList<>();
        for (Element a : doc.select("a[href]")) {
            String href = a.absUrl("href");
            if (href.isBlank()) continue;
            // absUrl doesn't always decode &amp; → &. Do it here.
            href = href.replace("&amp;", "&");
            String h;
            try {
                h = URI.create(href).getHost();
            } catch (Exception e) {
                continue;
            }
            if (h == null) continue;
            h = h.toLowerCase(Locale.ROOT);

            boolean isAggregator = h.equals(aggregatorHost) || h.endsWith("." + aggregatorHost);
            if (isAggregator) {
                String path;
                try {
                    path = URI.create(href).getPath();
                } catch (Exception e) {
                    continue;
                }
                if (path != null && AGGREGATOR_REDIRECT_PATH.matcher(path).matches()) {
                    redirectUrls.add(href);
                }
            } else {
                directExternal.add(href);
            }
        }

        // Dedupe redirects and cap to avoid hammering aggregators.
        List<String> uniqueRedirects = new ArrayList<>(new LinkedHashSet<>(redirectUrls));
        int redirectsFound = uniqueRedirects.size();
        if (uniqueRedirects.size() > MAX_REDIRECTS_PER_PAGE) {
            uniqueRedirects = uniqueRedirects.subList(0, MAX_REDIRECTS_PER_PAGE);
        }

        // Step 2: resolve every redirect in parallel. Each call returns
        // the final URL after Chrome follows any 3xx hops.
        List<String> resolved = resolveRedirectsParallel(uniqueRedirects);
        int redirectsResolved = resolved.size();

        // Step 3: combine resolved URLs with any direct external URLs
        // and bucket by host. Apply the video-page chrome filter here,
        // on the final URL, not on the aggregator's redirect URL.
        Map<String, Set<String>> perHost = new LinkedHashMap<>();
        for (String finalUrl : resolved) {
            bucket(perHost, finalUrl, videosOnly);
        }
        for (String finalUrl : directExternal) {
            bucket(perHost, finalUrl, videosOnly);
        }

        // Rank hosts by count, tie-broken alphabetically.
        List<Map.Entry<String, Integer>> ranked = new ArrayList<>();
        for (Map.Entry<String, Set<String>> e : perHost.entrySet()) {
            ranked.add(Map.entry(e.getKey(), e.getValue().size()));
        }
        ranked.sort(Comparator
                .<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue).reversed()
                .thenComparing(Map.Entry::getKey));

        // Step 4: pick the chosen host. Caller may pin one explicitly.
        String chosen = null;
        Set<String> chosenUrls = null;
        if (sourceHost != null && !sourceHost.isBlank()) {
            String want = sourceHost.toLowerCase(Locale.ROOT);
            for (Map.Entry<String, Set<String>> e : perHost.entrySet()) {
                if (e.getKey().equals(want) || e.getKey().endsWith("." + want)) {
                    chosen = e.getKey();
                    chosenUrls = e.getValue();
                    break;
                }
            }
            if (chosen == null) {
                throw new IllegalArgumentException(
                        "no " + sourceHost + " links found on that page");
            }
        } else if (!ranked.isEmpty()) {
            chosen = ranked.get(0).getKey();
            chosenUrls = perHost.get(chosen);
        }

        List<HostCount> sourcesJson = new ArrayList<>();
        for (Map.Entry<String, Integer> e : ranked) {
            sourcesJson.add(new HostCount(e.getKey(), e.getValue()));
        }

        return new DiscoveryResult(
                url,
                chosen,
                sourcesJson,
                chosenUrls == null ? List.of() : new ArrayList<>(chosenUrls),
                redirectsFound,
                redirectsResolved);
    }

    private void bucket(Map<String, Set<String>> perHost, String finalUrl, boolean videosOnly) {
        try {
            URI u = URI.create(finalUrl);
            String h = u.getHost();
            if (h == null) return;
            h = h.toLowerCase(Locale.ROOT);
            if (videosOnly && !isVideoPage(finalUrl)) return;
            String normalized = normalize(finalUrl);
            if (normalized.isBlank()) return;
            perHost.computeIfAbsent(h, k -> new LinkedHashSet<>()).add(normalized);
        } catch (Exception e) {
            // skip
        }
    }

    private List<String> resolveRedirectsParallel(List<String> ixxxUrls) {
        if (ixxxUrls.isEmpty()) return List.of();
        List<CompletableFuture<String>> futures = new ArrayList<>(ixxxUrls.size());
        for (String u : ixxxUrls) {
            futures.add(CompletableFuture.supplyAsync(
                    () -> {
                        try {
                            return pageFetcher.resolveFinalUrl(u);
                        } catch (Exception e) {
                            return null;
                        }
                    },
                    redirectExecutor));
        }
        List<String> resolved = new ArrayList<>(ixxxUrls.size());
        for (CompletableFuture<String> f : futures) {
            try {
                String r = f.get(REDIRECT_RESOLVE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (r != null && !r.isBlank()) resolved.add(r);
            } catch (Exception e) {
                // skip on timeout/error — the URL just won't appear
                // in the discovery result
            }
        }
        return resolved;
    }

    private boolean isVideoPage(String href) {
        try {
            URI u = URI.create(href);
            String path = u.getPath();
            if (path == null || path.length() <= 1) return false; // reject root
            return !NON_VIDEO_PATH.matcher(path.toLowerCase(Locale.ROOT)).find();
        } catch (Exception e) {
            return false;
        }
    }

    private String normalize(String href) {
        // Strip fragment, then a single trailing slash (but keep "https://" intact).
        int hash = href.indexOf('#');
        String s = hash >= 0 ? href.substring(0, hash) : href;
        if (s.endsWith("/")) {
            int schemeEnd = s.indexOf("://");
            if (schemeEnd >= 0 && s.length() > schemeEnd + 4) {
                s = s.substring(0, s.length() - 1);
            }
        }
        return s;
    }

    private boolean isPrivateHost(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        if ("localhost".equals(h) || h.endsWith(".local") || h.endsWith(".internal")) {
            return true;
        }
        try {
            InetAddress[] addrs = InetAddress.getAllByName(h);
            for (InetAddress a : addrs) {
                if (a.isAnyLocalAddress() || a.isLoopbackAddress()
                        || a.isSiteLocalAddress() || a.isLinkLocalAddress()
                        || a.isMulticastAddress()) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return true; // unknown host → block
        }
    }

    @PreDestroy
    public void shutdown() {
        redirectExecutor.shutdown();
        try {
            if (!redirectExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                redirectExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            redirectExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public record HostCount(String host, int count) {}

    public record DiscoveryResult(
            String sourceUrl,
            String detectedSource,
            List<HostCount> sources,
            List<String> urls,
            int redirectsFound,
            int redirectsResolved) {}
}
