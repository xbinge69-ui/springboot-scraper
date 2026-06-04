package com.example.scraper.video;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Tracks the four local files produced during a single enrichment
 * request (input + 3 derivatives) and deletes them on close. Designed
 * for use with try-with-resources so cleanup is guaranteed regardless of
 * which step in the pipeline throws.
 *
 * <p>This record implements {@link AutoCloseable} so callers can write:
 * <pre>
 * try (var files = new EnrichmentTempFiles(input, thumb, preview, compressed)) {
 *     // do work
 * } // 4 files deleted
 * </pre>
 */
public record EnrichmentTempFiles(Path input, Path thumbnail,
                                  Path preview, Path compressed) implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(EnrichmentTempFiles.class);

    @Override
    public void close() {
        deleteQuietly(thumbnail);
        deleteQuietly(preview);
        deleteQuietly(compressed);
        deleteQuietly(input);
    }

    private static void deleteQuietly(Path p) {
        if (p == null) return;
        try {
            Files.deleteIfExists(p);
        } catch (IOException e) {
            log.warn("Failed to delete temp file {}: {}", p, e.getMessage());
        }
    }
}
