package com.example.scraper.service;

import com.example.scraper.model.VideoCatalogEntry;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class VideoCatalogService {

    private static final Pattern ID_PATTERN = Pattern.compile("^v(\\d+)$");

    private final ObjectMapper objectMapper;

    @Value("${app.catalog.videos-file:../src/data/videos.json}")
    private String videosFilePath;

    public VideoCatalogService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public synchronized VideoCatalogEntry append(VideoCatalogEntry entry) throws IOException {
        List<VideoCatalogEntry> all = readAll();
        entry.setId(nextId(all));
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

    private synchronized void writeAll(List<VideoCatalogEntry> all) throws IOException {
        Path path = resolvePath();
        Files.createDirectories(path.getParent());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), all);
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

    private Path resolvePath() {
        return Paths.get(videosFilePath).toAbsolutePath().normalize();
    }
}
