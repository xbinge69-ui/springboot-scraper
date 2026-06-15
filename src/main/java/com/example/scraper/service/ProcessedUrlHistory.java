package com.example.scraper.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Persistent set of source page URLs that have already been processed
 * by the video pipeline. Loaded into a HashSet on startup for O(1)
 * lookup, then rewritten to disk whenever new URLs are added.
 *
 * <p>File format (a small JSON document next to the catalog file):
 * <pre>
 * {
 *   "version": 1,
 *   "urls": ["https://...", "https://...", ...]
 * }
 * </pre>
 *
 * <p>URLs are normalized before being stored or compared: trailing
 * slashes and tracking query params (everything after {@code ?}) are
 * dropped. So {@code https://xhamster.com/videos/abc?pw=xyz} and
 * {@code https://xhamster.com/videos/abc?pw=123} are treated as the
 * same video, which is what the user wants when the only difference
 * is a session/tracking parameter.
 */
@Service
public class ProcessedUrlHistory {

    private static final Logger log = LoggerFactory.getLogger(ProcessedUrlHistory.class);

    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);
    // Synchronized so concurrent batch jobs don't trample the set.
    private final Set<String> urls = Collections.synchronizedSet(new HashSet<>());

    public ProcessedUrlHistory(
            @Value("${app.history.processed-urls-file:../src/data/processed-urls.json}") String filePath
    ) {
        this.file = Paths.get(filePath);
    }

    @PostConstruct
    public void load() {
        if (!Files.exists(file)) {
            log.info("ProcessedUrlHistory: no file at {} — starting empty", file.toAbsolutePath());
            return;
        }
        try {
            byte[] data = Files.readAllBytes(file);
            JsonNode root = mapper.readTree(data);
            JsonNode arr = root != null ? root.get("urls") : null;
            int loaded = 0;
            if (arr != null && arr.isArray()) {
                for (JsonNode n : arr) {
                    if (n != null && n.isTextual()) {
                        urls.add(n.asText());
                        loaded++;
                    }
                }
            }
            log.info("ProcessedUrlHistory: loaded {} URLs from {}", loaded, file.toAbsolutePath());
        } catch (Exception e) {
            log.warn("ProcessedUrlHistory: failed to load {} — starting empty: {}",
                    file.toAbsolutePath(), e.getMessage());
        }
    }

    @PreDestroy
    public void save() {
        flush();
    }

    /** O(1) lookup. URL is normalized (trailing slash + query stripped) before comparison. */
    public boolean contains(String url) {
        if (url == null) return false;
        return urls.contains(normalize(url));
    }

    public int size() {
        return urls.size();
    }

    /** Snapshot of the in-memory set. Safe to iterate without holding the lock. */
    public Set<String> getAll() {
        synchronized (urls) {
            return new HashSet<>(urls);
        }
    }

    /** Add a batch of URLs to the set and persist. */
    public synchronized void addAll(List<String> newUrls) {
        if (newUrls == null || newUrls.isEmpty()) return;
        boolean changed = false;
        for (String u : newUrls) {
            String n = normalize(u);
            if (n != null && !n.isBlank() && urls.add(n)) changed = true;
        }
        if (changed) flush();
    }

    private void flush() {
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            List<String> sorted;
            synchronized (urls) {
                sorted = new ArrayList<>(urls);
            }
            Collections.sort(sorted);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("version", 1);
            data.put("urls", sorted);
            // Atomic-ish write: write to temp, then move into place.
            Path tmp = Files.createTempFile(file.getParent(), "processed-urls-", ".json.tmp");
            try {
                mapper.writeValue(tmp.toFile(), data);
                Files.move(tmp, file,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception e) {
                Files.deleteIfExists(tmp);
                throw e;
            }
        } catch (IOException e) {
            log.error("ProcessedUrlHistory: failed to save {}: {}", file.toAbsolutePath(), e.getMessage());
        }
    }

    private static String normalize(String url) {
        if (url == null) return null;
        int q = url.indexOf('?');
        int h = url.indexOf('#');
        int end = url.length();
        if (q >= 0) end = Math.min(end, q);
        if (h >= 0) end = Math.min(end, h);
        String s = url.substring(0, end);
        // Strip a single trailing slash (but keep "https://" intact).
        if (s.endsWith("/") && s.indexOf("://") + 3 < s.length() - 1) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }
}
