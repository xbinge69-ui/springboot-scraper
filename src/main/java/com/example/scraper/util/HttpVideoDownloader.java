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
 */
public final class HttpVideoDownloader {

    private static final int BUFFER_BYTES = 64 * 1024;

    /**
     * Test-only entry point: downloads {@code urlStr} to {@code dest}
     * <i>without</i> the SSRF guard. The caller is responsible for any
     * safety check (in production, always go through
     * {@link #download(String, Path, long, int, int)}).
     */
    public static long downloadForTest(String urlStr, Path dest, long maxBytes,
                                       int connectMs, int readMs) throws IOException {
        return download0(urlStr, dest, maxBytes, connectMs, readMs, false);
    }

    private HttpVideoDownloader() {}

    /**
     * @param urlStr    the remote video URL (must be http/https)
     * @param dest      local file to write to (will be created or truncated)
     * @param maxBytes  hard cap on bytes downloaded
     * @param connectMs connect timeout in ms
     * @param readMs    read timeout in ms
     * @return number of bytes written
     * @throws IOException on any failure (SSRF block, size cap, network, disk)
     */
    public static long download(String urlStr, Path dest, long maxBytes,
                                int connectMs, int readMs) throws IOException {
        return download0(urlStr, dest, maxBytes, connectMs, readMs, true);
    }

    private static long download0(String urlStr, Path dest, long maxBytes,
                                  int connectMs, int readMs, boolean applySsrfGuard) throws IOException {
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

        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(connectMs);
        conn.setReadTimeout(readMs);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; scraper-enrich/1.0)");
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
