package com.example.scraper.video;

import java.util.List;

/**
 * The user-supplied metadata that accompanies a video upload or URL. The
 * enrichment service refines these values via the Ollama "Topical
 * Authority" prompt; this record holds the raw input.
 */
public record EnrichmentMetadata(
        String title,
        String description,
        String category,
        List<String> tags,
        String unknownActressName,
        String actressId
) {
    public static EnrichmentMetadata empty() {
        return new EnrichmentMetadata(null, null, null, List.of(), null, null);
    }

    public String titleOrFallback() {
        return (title == null || title.isBlank()) ? "Untitled" : title;
    }

    public String categoryOrFallback() {
        return (category == null || category.isBlank()) ? "Uncategorized" : category;
    }

    public String unknownActressNameOrFallback() {
        return (unknownActressName == null || unknownActressName.isBlank())
                ? "Anonymous Couple" : unknownActressName;
    }
}
