package com.example.scraper.service;

import com.example.scraper.util.HttpPageFetcher;
import jakarta.annotation.PreDestroy;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
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
 * <p><b>Pagination:</b> the search results span multiple pages; one
 * call to {@link #discover(String, String, boolean, String)} can fetch
 * any number of pages. The {@code pagesInput} parameter accepts:
 * <ul>
 *   <li>empty / null → page 1 only (backward-compatible default)</li>
 *   <li>singletons — e.g. {@code "3"} → page 3</li>
 *   <li>comma-separated lists — e.g. {@code "2,3,5"} → pages 2, 3, 5</li>
 *   <li>ranges — e.g. {@code "2-5"} → pages 2, 3, 4, 5</li>
 *   <li>mixed — e.g. {@code "1-3,5,7-9"} → pages 1, 2, 3, 5, 7, 8, 9</li>
 * </ul>
 * Duplicates are removed and the result is sorted ascending. The
 * service caps total pages at {@link #MAX_PAGES_PER_DISCOVERY} so a
 * fat-finger can't queue hours of work. Per-page failures are recorded
 * in {@link DiscoveryResult#perPage()} and don't abort the rest of the
 * request — partial success is more useful here than a hard fail.
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

    private static final Logger log = LoggerFactory.getLogger(IxxxDiscoveryService.class);

    private static final int MAX_REDIRECTS_PER_PAGE = 60;
    private static final int REDIRECT_POOL_SIZE = 10;
    private static final int REDIRECT_RESOLVE_TIMEOUT_SECONDS = 60;
    /** Safety cap on how many pages one discovery call may scrape. */
    static final int MAX_PAGES_PER_DISCOVERY = 50;

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

    /** Single-page shortcut — equivalent to {@code discover(url, sourceHost, videosOnly, "")}. */
    public DiscoveryResult discover(String searchUrl, String sourceHost, boolean videosOnly)
            throws IOException {
        return discover(searchUrl, sourceHost, videosOnly, "");
    }

    /**
     * Fetches the aggregator search results across the requested pages
     * and pulls every external video-page link.
     *
     * @param searchUrl  the search/category URL on the aggregator
     * @param sourceHost optional override pinning the result to a specific
     *                   source host (e.g. {@code "xhamster.com"}); the
     *                   most-popular host is picked if blank
     * @param videosOnly when {@code true}, drop non-video pages via
     *                   {@link #NON_VIDEO_PATH}
     * @param pagesInput page numbers to scrape (see class javadoc); empty
     *                   or null means page 1 only
     */
    public DiscoveryResult discover(String searchUrl, String sourceHost, boolean videosOnly,
                                    String pagesInput) throws IOException {
        // ---- 1. Validate base URL ----
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

        // ---- 2. Parse pages input ----
        List<Integer> pages = parsePagesInput(pagesInput);

        // ---- 3. Extract each page; aggregate results across all pages ----
        Map<String, Set<String>> perHost = new LinkedHashMap<>();
        List<PageOutcome> perPage = new ArrayList<>(pages.size());
        int totalRedirectsFound = 0;
        int totalRedirectsResolved = 0;

        for (int pageNum : pages) {
            String pageUrl = buildPageUrl(url, pageNum);
            PageOutcome outcome;
            try {
                outcome = extractPage(pageNum, pageUrl, aggregatorHost, videosOnly);
            } catch (Exception e) {
                log.warn("ixxx discovery: page {} failed: {}", pageNum, e.getMessage());
                outcome = PageOutcome.failed(pageNum, pageUrl, e.getMessage());
            }
            mergeInto(perHost, outcome.buckets());
            totalRedirectsFound += outcome.redirectsFound();
            totalRedirectsResolved += outcome.redirectsResolved();
            perPage.add(outcome);
        }

        int pagesFetched = (int) perPage.stream().filter(p -> p.error() == null).count();

        // ---- 4. Rank hosts by count, tie-broken alphabetically ----
        List<Map.Entry<String, Integer>> ranked = new ArrayList<>();
        for (Map.Entry<String, Set<String>> e : perHost.entrySet()) {
            ranked.add(Map.entry(e.getKey(), e.getValue().size()));
        }
        ranked.sort(Comparator
                .<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue).reversed()
                .thenComparing(Map.Entry::getKey));

        // ---- 5. Pick chosen host ----
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
                        "no " + sourceHost + " links found on those pages");
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
                totalRedirectsFound,
                totalRedirectsResolved,
                pages,
                pagesFetched,
                perPage);
    }

    /**
     * Fetches one page, splits anchors into "needs redirect resolution"
     * vs "already external", resolves every redirect in parallel, then
     * buckets the final URLs by host.
     */
    private PageOutcome extractPage(int pageNum, String pageUrl, String aggregatorHost,
                                    boolean videosOnly) throws IOException {
        String html = pageFetcher.fetch(pageUrl);
        Document doc = Jsoup.parse(html, pageUrl);

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

        // Resolve every redirect in parallel. Each call returns the
        // final URL after Chrome follows any 3xx hops.
        List<String> resolved = resolveRedirectsParallel(uniqueRedirects);
        int redirectsResolved = resolved.size();

        // Bucket the final URLs by host. Apply the video-page chrome
        // filter on the final URL, not on the aggregator's redirect.
        Map<String, Set<String>> buckets = new LinkedHashMap<>();
        for (String finalUrl : resolved) {
            bucket(buckets, finalUrl, videosOnly);
        }
        for (String finalUrl : directExternal) {
            bucket(buckets, finalUrl, videosOnly);
        }
        buckets.values().removeIf(Set::isEmpty);

        int bucketedCount = buckets.values().stream().mapToInt(Set::size).sum();
        log.info("ixxx discovery: page {} → {} redirect(s), {} resolved, {} URL(s) bucketed",
                pageNum, redirectsFound, redirectsResolved, bucketedCount);

        return new PageOutcome(pageNum, pageUrl, redirectsFound, redirectsResolved, buckets, null);
    }

    /** Union of a per-page bucket map into an aggregate (deduped by normalized URL). */
    private void mergeInto(Map<String, Set<String>> aggregate, Map<String, Set<String>> pageBuckets) {
        for (Map.Entry<String, Set<String>> e : pageBuckets.entrySet()) {
            aggregate.computeIfAbsent(e.getKey(), k -> new LinkedHashSet<>()).addAll(e.getValue());
        }
    }

    /**
     * Parses the pages input string. Accepts:
     * <ul>
     *   <li>empty / null / blank → page 1 only</li>
     *   <li>singletons: {@code "3"} → that page</li>
     *   <li>comma-separated: {@code "2,3,5"} → those pages</li>
     *   <li>ranges: {@code "2-5"} → pages 2, 3, 4, 5</li>
     *   <li>mixed: {@code "1-3,5,7-9"} → union of all of the above</li>
     * </ul>
     * Duplicates are removed, results are sorted ascending. Throws
     * {@link IllegalArgumentException} on any non-integer token, a
     * page number below 1, an inverted range, or a total above
     * {@link #MAX_PAGES_PER_DISCOVERY}.
     */
    public static List<Integer> parsePagesInput(String input) {
        if (input == null || input.isBlank()) return List.of(1);
        LinkedHashSet<Integer> out = new LinkedHashSet<>();
        for (String rawPart : input.trim().split(",")) {
            String chunk = rawPart.trim();
            if (chunk.isEmpty()) continue;
            int dash = chunk.indexOf('-');
            if (dash < 0) {
                int n;
                try {
                    n = Integer.parseInt(chunk);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("pages: not an integer: '" + chunk + "'");
                }
                if (n < 1) {
                    throw new IllegalArgumentException("pages: must be >= 1, got " + n);
                }
                out.add(n);
            } else {
                String from = chunk.substring(0, dash).trim();
                String to = chunk.substring(dash + 1).trim();
                if (from.isEmpty() || to.isEmpty()) {
                    throw new IllegalArgumentException("pages: invalid range '" + chunk + "'");
                }
                int start;
                int end;
                try {
                    start = Integer.parseInt(from);
                    end = Integer.parseInt(to);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(
                            "pages: not an integer in range '" + chunk + "'");
                }
                if (start < 1 || end < 1) {
                    throw new IllegalArgumentException(
                            "pages: range endpoints must be >= 1: '" + chunk + "'");
                }
                if (end < start) {
                    throw new IllegalArgumentException(
                            "pages: inverted range '" + chunk + "'");
                }
                for (int i = start; i <= end; i++) out.add(i);
            }
        }
        if (out.isEmpty()) return List.of(1);
        List<Integer> sorted = new ArrayList<>(out);
        Collections.sort(sorted);
        if (sorted.size() > MAX_PAGES_PER_DISCOVERY) {
            throw new IllegalArgumentException(
                    "pages: too many (" + sorted.size() + ", max "
                            + MAX_PAGES_PER_DISCOVERY + ")");
        }
        return Collections.unmodifiableList(sorted);
    }

    /**
     * Builds the per-page URL by appending {@code page=N}, or replacing
     * any existing {@code page=...} value. Treats the input as a raw
     * URL string — does not decode {@code [bracket]} query keys (which
     * ixxx.com uses for filter parameters).
     */
    static String buildPageUrl(String baseUrl, int pageNum) {
        int q = baseUrl.indexOf('?');
        if (q < 0) {
            return baseUrl + "?page=" + pageNum;
        }
        String pathPart = baseUrl.substring(0, q);
        String existingQs = baseUrl.substring(q + 1);
        StringBuilder rebuilt = new StringBuilder();
        boolean first = true;
        for (String param : existingQs.split("&")) {
            if (param.isEmpty()) continue;
            int eq = param.indexOf('=');
            String key = eq < 0 ? param : param.substring(0, eq);
            if ("page".equals(key)) continue; // drop existing page=N
            if (!first) rebuilt.append('&');
            rebuilt.append(param);
            first = false;
        }
        if (!first) rebuilt.append('&');
        rebuilt.append("page=").append(pageNum);
        return pathPart + "?" + rebuilt;
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

    /**
     * Per-page breakdown of one discovery request. Returned in
     * {@link DiscoveryResult#perPage()} so the UI can render a line
     * per page (e.g. "page 2 → 53 links, page 3 → 0 links") instead
     * of an opaque single count.
     */
    public record PageOutcome(
            int page,
            String requestedUrl,
            int redirectsFound,
            int redirectsResolved,
            Map<String, Set<String>> buckets,
            String error) {

        /** Factory for a failed scrape — used when {@link #extractPage} throws. */
        public static PageOutcome failed(int page, String url, String error) {
            return new PageOutcome(page, url, 0, 0, Map.of(), error);
        }

        /** Total URLs extracted from this page across all source hosts. */
        public int totalUrls() {
            return buckets().values().stream().mapToInt(Set::size).sum();
        }
    }

    public record DiscoveryResult(
            String sourceUrl,
            String detectedSource,
            List<HostCount> sources,
            List<String> urls,
            int redirectsFound,
            int redirectsResolved,
            List<Integer> requestedPages,
            int pagesFetched,
            List<PageOutcome> perPage) {}
}
