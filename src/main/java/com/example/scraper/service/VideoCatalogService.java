package com.example.scraper.service;

import com.example.scraper.model.VideoCatalogEntry;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class VideoCatalogService {

    private static final Pattern ID_PATTERN = Pattern.compile("^v(\\d+)$");

    private final ObjectMapper objectMapper;

    @Value("${app.catalog.videos-file:C:/Git/projectExtraction/src/data/videos.json}")
    private String videosFilePath;

    public VideoCatalogService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public synchronized VideoCatalogEntry append(VideoCatalogEntry entry) throws IOException {
        List<VideoCatalogEntry> all = readAll();
        entry.setId(nextId(all));
        entry.setSlug(makeUniqueSlug(entry.getSlug(), all));
        all.add(entry);
        writeAll(all);
        return entry;
    }

    public synchronized List<VideoCatalogEntry> readAll() throws IOException {
        Path path = resolvePath();
        if (!Files.exists(path)) {
            return new ArrayList<>();
        }

        String raw = Files.readString(path);
        if (raw.isBlank()) {
            return new ArrayList<>();
        }

        return objectMapper.readValue(raw, new TypeReference<List<VideoCatalogEntry>>() {});
    }

    /**
     * Write the catalog atomically: write to {@code videos.json.tmp} first,
     * then {@code Files.move(... ATOMIC_MOVE, REPLACE_EXISTING)} so a
     * mid-write crash doesn't truncate the existing file.
     */
    private synchronized void writeAll(List<VideoCatalogEntry> all) throws IOException {
        Path path = resolvePath();
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        Path tmp = path.resolveSibling(path.getFileName().toString() + ".tmp");
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), all);
        try {
            Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            // Fallback for filesystems that don't support atomic move (e.g. some FAT mounts).
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private String nextId(List<VideoCatalogEntry> all) {
        int max = 0;
        for (VideoCatalogEntry entry : all) {
            if (entry.getId() == null) {
                continue;
            }
            Matcher matcher = ID_PATTERN.matcher(entry.getId());
            if (matcher.matches()) {
                max = Math.max(max, Integer.parseInt(matcher.group(1)));
            }
        }
        return "v" + (max + 1);
    }

    private String makeUniqueSlug(String desired, List<VideoCatalogEntry> all) {
        if (desired == null || desired.isBlank()) {
            desired = "video";
        }
        Set<String> taken = all.stream()
                .map(VideoCatalogEntry::getSlug)
                .filter(s -> s != null)
                .collect(Collectors.toSet());
        if (!taken.contains(desired)) {
            return desired;
        }
        for (int i = 2; i < 10_000; i++) {
            String candidate = desired + "-" + i;
            if (!taken.contains(candidate)) {
                return candidate;
            }
        }
        // Astronomically unlikely; fall back to UUID suffix.
        return desired + "-" + java.util.UUID.randomUUID().toString().substring(0, 6);
    }

    /**
     * Build a compact, prompt-friendly summary of the catalog. Designed to
     * stay well under ~2 KB so the Ollama context window is never blown
     * out, even as the catalog grows.
     *
     * <p>Shape:
     * <pre>
     * {
     *   "size": 47,
     *   "categories": { "Amateur": 12, "Lesbian": 8, ... },
     *   "topTags":    [ { "tag": "couple", "count": 14 }, ...30 ],
     *   "recentTitles": [ "Amateur couple ...", ...30 ]
     * }
     * </pre>
     */
    public synchronized String summarize() throws IOException {
        List<VideoCatalogEntry> all = readAll();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("size", all.size());

        // Categories with counts.
        Map<String, Long> categoryCounts = all.stream()
                .map(VideoCatalogEntry::getCategory)
                .filter(c -> c != null && !c.isBlank())
                .collect(Collectors.groupingBy(c -> c, Collectors.counting()));
        Map<String, Long> categoriesSorted = new LinkedHashMap<>();
        categoryCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .forEach(e -> categoriesSorted.put(e.getKey(), e.getValue()));
        summary.put("categories", categoriesSorted);

        // Top 30 tags by frequency.
        Map<String, Long> tagCounts = new HashMap<>();
        for (VideoCatalogEntry e : all) {
            if (e.getTags() == null) continue;
            for (String tag : e.getTags()) {
                if (tag == null || tag.isBlank()) continue;
                tagCounts.merge(tag.toLowerCase(), 1L, (a, b) -> a + b);
            }
        }
        List<Map<String, Object>> topTags = tagCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(30)
                .map(e -> Map.<String, Object>of("tag", e.getKey(), "count", e.getValue()))
                .collect(Collectors.toList());
        summary.put("topTags", topTags);

        // 30 most recent titles (by publishedAt desc, fallback to insertion order).
        List<String> recentTitles = all.stream()
                .sorted(Comparator.<VideoCatalogEntry, String>comparing(
                        e -> e.getPublishedAt() == null ? "" : e.getPublishedAt()
                ).reversed())
                .limit(30)
                .map(VideoCatalogEntry::getTitle)
                .filter(t -> t != null && !t.isBlank())
                .collect(Collectors.toList());
        summary.put("recentTitles", recentTitles);

        return objectMapper.writeValueAsString(summary);
    }

    public Path resolvePath() {
        return Paths.get(videosFilePath).toAbsolutePath().normalize();
    }
}
