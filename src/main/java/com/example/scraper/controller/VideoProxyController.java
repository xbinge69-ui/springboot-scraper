package com.example.scraper.controller;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Proxies video streams from supported CDNs so the browser can play them
 * without hitting CDN hotlink-protection (403 Forbidden).
 *
 * This endpoint blocks localhost/private IP targets to reduce SSRF risk.
 */
@Controller
public class VideoProxyController {

    private static final int TIMEOUT_MS   = 20_000;
    private static final int BUFFER_SIZE  = 64 * 1024; // 64 KB

    @GetMapping("/video/stream")
    public ResponseEntity<StreamingResponseBody> stream(
            @RequestParam("url") String videoUrl,
            @RequestParam(value = "referer", required = false) String referer
    ) throws IOException {

        // --- Security: validate the URL host is whitelisted ---
        URL parsed;
        try {
            parsed = new URL(videoUrl);
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }

        if (!isAllowedRemoteHost(parsed.getHost())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        String safeReferer = safeReferer(referer, parsed);

        // --- Open connection with spoofed headers ---
        HttpURLConnection conn = (HttpURLConnection) parsed.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        conn.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                + "AppleWebKit/537.36 (KHTML, like Gecko) "
                + "Chrome/124.0.0.0 Safari/537.36");
        conn.setRequestProperty("Referer", safeReferer);
        conn.setRequestProperty("Origin", originFromUrl(safeReferer));
        conn.setRequestProperty("Accept", "video/webm,video/ogg,video/*;q=0.9,*/*;q=0.8");
        conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
        conn.setInstanceFollowRedirects(true);

        int status = conn.getResponseCode();
        if (status != 200) {
            conn.disconnect();
            return ResponseEntity.status(status).build();
        }

        // --- Determine content type ---
        String contentType = conn.getContentType();
        if (contentType == null || contentType.isBlank()) {
            contentType = videoUrl.toLowerCase().endsWith(".m3u8")
                    ? "application/x-mpegURL"
                    : "video/mp4";
        }

        // --- Stream the response body ---
        InputStream in = conn.getInputStream();
        StreamingResponseBody body = outputStream -> {
            try {
                byte[] buf = new byte[BUFFER_SIZE];
                int read;
                while ((read = in.read(buf)) != -1) {
                    outputStream.write(buf, 0, read);
                    outputStream.flush();
                }
            } finally {
                in.close();
                conn.disconnect();
            }
        };

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(contentType));
        // Allow browser to seek (partial content) — forward Accept-Ranges if present
        String acceptRanges = conn.getHeaderField("Accept-Ranges");
        if (acceptRanges != null) {
            headers.set("Accept-Ranges", acceptRanges);
        }
        long contentLength = conn.getContentLengthLong();
        if (contentLength > 0) {
            headers.setContentLength(contentLength);
        }

        return ResponseEntity.ok()
                .headers(headers)
                .body(body);
    }

    private String safeReferer(String referer, URL target) {
        if (referer != null && (referer.startsWith("http://") || referer.startsWith("https://"))) {
            return referer;
        }
        return target.getProtocol() + "://" + target.getHost() + "/";
    }

    private String originFromUrl(String pageUrl) {
        try {
            URI uri = URI.create(pageUrl);
            return uri.getScheme() + "://" + uri.getHost();
        } catch (Exception e) {
            return "https://";
        }
    }

    private boolean isAllowedRemoteHost(String host) {
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
}
