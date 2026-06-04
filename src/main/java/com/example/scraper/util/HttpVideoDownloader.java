package com.example.scraper.util;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Stream a remote video URL to a local file. Defends against SSRF by
 * rejecting loopback / link-local / site-local / multicast hosts (same
 * rules as {@code VideoProxyController.isAllowedRemoteHost}), refuses
 * downloads whose declared {@code Content-Length} exceeds
 * {@code maxBytes}, and enforces a hard byte cap on the read loop so a
 * server that lies about {@code Content-Length} is still bounded.
 *
 * <p>Many video CDNs (erome, doodstream, etc.) reject requests without
 * proper browser-like headers. By default this downloader spoofs
 * User-Agent, Accept, Accept-Language, and a same-origin Referer + Origin
 * so a hotlink-protected URL behaves the same as it would in a browser.
 * Callers can override the Referer (e.g. to the page that linked to the
 * video) via {@link #download(String, Path, long, int, int, String)}.
 */
public final class HttpVideoDownloader {

    private static final int BUFFER_BYTES = 64 * 1024;
    private static final String BROWSER_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                    + "AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/124.0.0.0 Safari/537.36";
    private static final String ACCEPT_VALUE =
            "video/webm,video/ogg,video/*;q=0.9,application/octet-stream;q=0.8,*/*;q=0.5";
    private static final String ACCEPT_LANGUAGE_VALUE = "en-US,en;q=0.9";

    private HttpVideoDownloader() {}

    /**
     * Convenience: defaults the Referer to the video URL's own origin
     * (which satisfies most CDNs' same-origin check).
     */
    public static long download(String urlStr, Path dest, long maxBytes,
                                int connectMs, int readMs) throws IOException {
        return download(urlStr, dest, maxBytes, connectMs, readMs, null);
    }

    /**
     * @param urlStr    the remote video URL (must be http/https)
     * @param dest      local file to write to (will be created or truncated)
     * @param maxBytes  hard cap on bytes downloaded
     * @param connectMs connect timeout in ms
     * @param readMs    read timeout in ms
     * @param referer   optional {@code Referer} header value; if null, the
     *                  video URL's own origin is used (e.g.
     *                  {@code https://v5.erome.com/}). Pass an explicit
     *                  page URL when the video was discovered by scraping.
     * @return number of bytes written
     * @throws IOException on any failure (SSRF block, size cap, network, disk)
     */
    public static long download(String urlStr, Path dest, long maxBytes,
                                int connectMs, int readMs, String referer) throws IOException {
        return download0(urlStr, dest, maxBytes, connectMs, readMs, referer, true);
    }

    /**
     * Test-only entry point: downloads {@code urlStr} to {@code dest}
     * <i>without</i> the SSRF guard. The caller is responsible for any
     * safety check (in production, always go through
     * {@link #download(String, Path, long, int, int)}).
     */
    public static long downloadForTest(String urlStr, Path dest, long maxBytes,
                                       int connectMs, int readMs) throws IOException {
        return download0(urlStr, dest, maxBytes, connectMs, readMs, null, false);
    }

    private static long download0(String urlStr, Path dest, long maxBytes,
                                  int connectMs, int readMs, String referer,
                                  boolean applySsrfGuard) throws IOException {
        URL url;
        try {
            url = URI.create(urlStr).toURL();
        } catch (Exception e) {
            throw new IOException("Invalid video URL: " + urlStr, e);
        }
        if (!"http".equalsIgnoreCase(url.getProtocol()) && !"https".equalsIgnoreCase(url.getProtocol())) {
            throw new IOException("Unsupported protocol for video URL: " + url.getProtocol());
        }
        if (applySsrfGuard && !isAllowedRemoteHost(url.getHost())) {
            throw new IOException("Refusing to download from disallowed host: " + url.getHost());
        }

        String effectiveReferer = (referer == null || referer.isBlank())
                ? url.getProtocol() + "://" + url.getHost() + "/"
                : referer;
        String origin = originFromUrl(effectiveReferer);

        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(connectMs);
        conn.setReadTimeout(readMs);
        conn.setRequestProperty("User-Agent", BROWSER_USER_AGENT);
        conn.setRequestProperty("Referer", effectiveReferer);
        conn.setRequestProperty("Origin", origin);
        conn.setRequestProperty("Accept", ACCEPT_VALUE);
        conn.setRequestProperty("Accept-Language", ACCEPT_LANGUAGE_VALUE);
        conn.setInstanceFollowRedirects(true);

        int status = conn.getResponseCode();
        if (status != 200) {
            conn.disconnect();
            throw new IOException("Video download failed: HTTP " + status + " for " + urlStr);
        }

        long declared = conn.getContentLengthLong();
        if (declared > maxBytes) {
            conn.disconnect();
            throw new IOException("Video download exceeds max size: declared " + declared + " > " + maxBytes);
        }

        Path parent = dest.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        long written = 0;
        byte[] buf = new byte[BUFFER_BYTES];
        try (InputStream in = conn.getInputStream()) {
            try (var out = Files.newOutputStream(dest,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                int read;
                while ((read = in.read(buf)) != -1) {
                    if (written + read > maxBytes) {
                        throw new IOException("Video download exceeded max size mid-stream (> " + maxBytes + ")");
                    }
                    out.write(buf, 0, read);
                    written += read;
                }
            }
        } finally {
            conn.disconnect();
        }
        return written;
    }

    private static String originFromUrl(String pageUrl) {
        try {
            URI uri = URI.create(pageUrl);
            return uri.getScheme() + "://" + uri.getHost();
        } catch (Exception e) {
            return "https://";
        }
    }

    /**
     * Same SSRF check as {@code VideoProxyController.isAllowedRemoteHost}.
     * Exposed here so the downloader is self-contained and doesn't reach
     * into a controller for security logic.
     */
    static boolean isAllowedRemoteHost(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        String normalized = host.toLowerCase();
        if ("localhost".equals(normalized) || normalized.endsWith(".local")) {
            return false;
        }
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            for (InetAddress address : addresses) {
                if (address.isAnyLocalAddress()
                        || address.isLoopbackAddress()
                        || address.isSiteLocalAddress()
                        || address.isLinkLocalAddress()
                        || address.isMulticastAddress()) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Helper for the controller's tests; not used at runtime. */
    public static boolean isAllowedRemoteHostForTest(String host) {
        return isAllowedRemoteHost(host);
    }
}
