package com.example.scraper.service;

import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

@Service
public class MediaDerivativeService {

    /**
     * Lightweight default strategy:
     * - thumbnail: keep first downloaded bytes placeholder strategy is avoided
     * - preview: use source video bytes (same asset)
     *
     * This keeps the pipeline functional without requiring ffmpeg on the host.
     * You can later replace this service by an ffmpeg-based implementation.
     */
    public byte[] downloadFirstBytes(String sourceVideoUrl, int maxBytes) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(sourceVideoUrl).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(20_000);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0");
        conn.setRequestProperty("Range", "bytes=0-" + (maxBytes - 1));

        try (InputStream in = conn.getInputStream();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = in.read(buffer)) != -1 && total < maxBytes) {
                int writable = Math.min(read, maxBytes - total);
                out.write(buffer, 0, writable);
                total += writable;
            }
            return out.toByteArray();
        } finally {
            conn.disconnect();
        }
    }
}
