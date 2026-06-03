package com.example.scraper.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

@Service
public class BunnyAssetService {

    @Value("${app.bunny.enabled:false}")
    private boolean bunnyEnabled;

    @Value("${app.bunny.storage-zone:}")
    private String storageZone;

    @Value("${app.bunny.api-key:}")
    private String apiKey;

    @Value("${app.bunny.storage-region:}")
    private String storageRegion;

    @Value("${app.bunny.pull-base-url:https://example.b-cdn.net}")
    private String pullBaseUrl;

    @Value("${app.bunny.folder:uploads}")
    private String folder;

    public String uploadThumbnail(byte[] content, String slug) throws IOException {
        String objectPath = folder + "/" + slug + "-" + UUID.randomUUID().toString().substring(0, 8) + ".jpg";
        return uploadBytes(content, objectPath, "image/jpeg");
    }

    public String uploadPreview(byte[] content, String slug) throws IOException {
        String objectPath = folder + "/" + slug + "-" + UUID.randomUUID().toString().substring(0, 8) + ".mp4";
        return uploadBytes(content, objectPath, "video/mp4");
    }

    public String uploadBytes(byte[] content, String objectPath, String contentType) throws IOException {
        String normalizedObjectPath = objectPath.startsWith("/") ? objectPath.substring(1) : objectPath;
        String publicUrl = stripTrailingSlash(pullBaseUrl) + "/" + normalizedObjectPath;

        if (!bunnyEnabled) {
            return publicUrl;
        }

        if (storageZone.isBlank() || apiKey.isBlank()) {
            throw new IllegalStateException("Bunny enabled but storage-zone/api-key are not configured.");
        }

        String host = storageRegion == null || storageRegion.isBlank()
                ? "storage.bunnycdn.com"
                : storageRegion + ".storage.bunnycdn.com";

        URL url = new URL("https://" + host + "/" + storageZone + "/" + normalizedObjectPath);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("PUT");
        conn.setDoOutput(true);
        conn.setRequestProperty("AccessKey", apiKey);
        conn.setRequestProperty("Content-Type", contentType);
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(25_000);

        try (OutputStream out = conn.getOutputStream()) {
            out.write(content);
        }

        int status = conn.getResponseCode();
        if (status < 200 || status >= 300) {
            throw new IOException("Bunny upload failed with status " + status + " for " + normalizedObjectPath);
        }

        return publicUrl;
    }

    private String stripTrailingSlash(String s) {
        if (s == null || s.isBlank()) {
            return "";
        }
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
