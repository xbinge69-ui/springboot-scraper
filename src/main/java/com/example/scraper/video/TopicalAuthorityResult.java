package com.example.scraper.video;

import java.util.List;

/**
 * Result of parsing Ollama's response to the Topical Authority prompt.
 * Fields are non-null where sensible; empty strings / empty lists fall
 * back to the input metadata.
 */
public record TopicalAuthorityResult(
        String title,
        String description,
        String category,
        List<String> tags,
        String slug,
        long views
) {
}
