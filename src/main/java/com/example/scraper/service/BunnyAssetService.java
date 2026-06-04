package com.example.scraper.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

@Service
public class BunnyAssetService {

    /** Read timeout for streaming uploads. Set to 5 min so a 100 MB file at 1 MB/s has headroom. */
    private static final int UPLOAD_READ_TIMEOUT_MS = 5 * 60 * 1000;
    private static final int UPLOAD_CONNECT_TIMEOUT_MS = 15_000;
    private static final int STREAM_BUFFER_BYTES = 64 * 1024;
    /** 12 hex chars = 48 bits → birthday collision around 16M objects. */
    private static final int UUID_HEX_CHARS = 12;

    @Value("${app.bunny.enabled:false}")
    private boolean bunnyEnabled;

    @Value("${app.bunny.storage.zone-name:}")
    private String storageZoneName;

    /**
     * Bunny storage API key. Sourced from the {@code BUNNY_STORAGE_API_KEY}
     * environment variable (resolved via
     * {@code @Value("${BUNNY_STORAGE_API_KEY:}")}). The default value is
     * empty, so the key is never in source. The {@code @Value} is
     * intentionally indirect (referencing the env var) so Spring's
     * property binding can find it without the dev having to set
     * {@code -DBUNNY_STORAGE_API_KEY=…} on the command line.
     */
    @Value("${app.bunny.storage.api-key:}")
    private String storageApiKey;

    @Value("${app.bunny.storage.endpoint:https://storage.bunnycdn.com}")
    private String storageEndpoint;

    @Value("${app.bunny.storage-region:}")
    private String storageRegion;

    @Value("${app.bunny.cdn.hostname:}")
    private String cdnHostname;

    @Value("${app.bunny.folder:uploads}")
    private String folder;

    /**
     * Generate a 12-hex-char identifier for object keys. Callers can use the
     * same UUID across the 3 derivatives of one video so a single
     * {@code ls <slug>-<uuid>*} reveals the whole set.
     */
    public static String newRequestUuid() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, UUID_HEX_CHARS);
    }

    public String uploadBytes(byte[] content, String objectPath, String contentType) throws IOException {
        if (!bunnyEnabled) {
            return publicUrlFor(objectPath);
        }
        ensureConfigured();
        HttpURLConnection conn = openConnection(objectPath, contentType);
        try (OutputStream out = conn.getOutputStream()) {
            out.write(content);
        }
        return checkStatusAndReturn(objectPath, conn);
    }

    /**
     * Streaming upload from a {@link Path}. Avoids loading the whole file into
     * a {@code byte[]}, so a 2 GB upload doesn't OOM the JVM. Returns the
     * public CDN URL.
     */
    public String uploadBytes(Path source, String objectPath, String contentType) throws IOException {
        if (!bunnyEnabled) {
            return publicUrlFor(objectPath);
        }
        ensureConfigured();
        HttpURLConnection conn = openConnection(objectPath, contentType);
        try (OutputStream out = conn.getOutputStream()) {
            byte[] buf = new byte[STREAM_BUFFER_BYTES];
            int read;
            try (var in = Files.newInputStream(source)) {
                while ((read = in.read(buf)) != -1) {
                    out.write(buf, 0, read);
                }
            }
        }
        return checkStatusAndReturn(objectPath, conn);
    }

    private void ensureConfigured() {
        if (storageZoneName.isBlank() || storageApiKey.isBlank()) {
            throw new IllegalStateException("Bunny enabled but storage zone-name/api-key are not configured.");
        }
    }

    private HttpURLConnection openConnection(String objectPath, String contentType) throws IOException {
        // PUT URL: {storage-endpoint}/{storage-zone-name}/{object-path}.
        // Default endpoint is https://storage.bunnycdn.com; a regional edge
        // (e.g. "ny") is prepended to .storage.bunnycdn.com.
        String host = storageEndpoint;
        if (host == null || host.isBlank()) {
            host = (storageRegion == null || storageRegion.isBlank())
                    ? "storage.bunnycdn.com"
                    : storageRegion + ".storage.bunnycdn.com";
        }
        URL url = new URL(host + "/" + storageZoneName + "/" + normalizeObjectPath(objectPath));
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("PUT");
        conn.setDoOutput(true);
        conn.setRequestProperty("AccessKey", storageApiKey);
        conn.setRequestProperty("Content-Type", contentType);
        conn.setConnectTimeout(UPLOAD_CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(UPLOAD_READ_TIMEOUT_MS);
        return conn;
    }

    private String checkStatusAndReturn(String objectPath, HttpURLConnection conn) throws IOException {
        int status = conn.getResponseCode();
        if (status < 200 || status >= 300) {
            throw new IOException("Bunny upload failed with status " + status + " for " + objectPath);
        }
        return publicUrlFor(objectPath);
    }

    private String publicUrlFor(String objectPath) {
        // Public read URL: https://{cdn-hostname}/{object-path}
        return "https://" + stripTrailingSlash(cdnHostname) + "/" + normalizeObjectPath(objectPath);
    }

    private String normalizeObjectPath(String objectPath) {
        return objectPath.startsWith("/") ? objectPath.substring(1) : objectPath;
    }

    private String stripTrailingSlash(String s) {
        if (s == null || s.isBlank()) {
            return "";
        }
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    public String getFolder() {
        return folder;
    }
}
