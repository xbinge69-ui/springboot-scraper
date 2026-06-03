package com.example.scraper.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Service
public class VideoEnricherService {

    private final OllamaService ollamaService;

    @Value("${app.ollama.enabled:true}")
    private boolean ollamaEnabled;

    public VideoEnricherService(OllamaService ollamaService) {
        this.ollamaService = ollamaService;
    }

    /**
     * Generate video description using Ollama LLM based on title and optional existing description.
     */
    public String generateDescription(String title, String existingDescription) {
        if (!ollamaEnabled) {
            return existingDescription != null ? existingDescription : generatePlaceholder(title);
        }

        try {
            String prompt = buildDescriptionPrompt(title, existingDescription);
            String result = ollamaService.generate(prompt);
            return cleanResponse(result);
        } catch (Exception e) {
            return existingDescription != null
                    ? existingDescription
                    : generatePlaceholder(title);
        }
    }

    /**
     * Generate relevant tags using Ollama LLM based on title and description.
     */
    public List<String> generateTags(String title, String description) {
        if (!ollamaEnabled) {
            return Arrays.asList("video", "content");
        }

        try {
            String prompt = buildTagsPrompt(title, description);
            String result = ollamaService.generate(prompt);
            return parseTagsFromResponse(result);
        } catch (Exception e) {
            return Arrays.asList("video", "content");
        }
    }

    /**
     * Generate a clean, SEO-friendly slug from title and description.
     */
    public String generateEnhancedSlug(String title, String description) {
        if (!ollamaEnabled) {
            return slugifyBasic(title);
        }

        try {
            String prompt = buildSlugPrompt(title, description);
            String result = ollamaService.generate(prompt);
            return cleanSlug(result);
        } catch (Exception e) {
            return slugifyBasic(title);
        }
    }

    // ---------------------------------------------------------------
    // Prompt builders
    // ---------------------------------------------------------------

    private String buildDescriptionPrompt(String title, String existing) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are a professional content writer. ");
        prompt.append("Generate a natural, engaging description for video content.\n\n");
        prompt.append("Title: ").append(title).append("\n");
        if (existing != null && !existing.isBlank()) {
            prompt.append("Existing description to expand on: ").append(existing).append("\n");
        }
        prompt.append("\nProvide a 2-3 sentence description that is engaging and informative. ");
        prompt.append("Do NOT include hashtags or tags.");
        return prompt.toString();
    }

    private String buildTagsPrompt(String title, String description) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Generate 5-8 relevant tags for this video content.\n\n");
        prompt.append("Title: ").append(title).append("\n");
        if (description != null && !description.isBlank()) {
            prompt.append("Description: ").append(description).append("\n");
        }
        prompt.append("\nReturn only comma-separated tags (e.g., tag1, tag2, tag3).");
        prompt.append("\nNo brackets, no numbering, just clean tags.");
        return prompt.toString();
    }

    private String buildSlugPrompt(String title, String description) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Create a URL-friendly slug (max 50 chars) for this video.\n\n");
        prompt.append("Title: ").append(title).append("\n");
        if (description != null && !description.isBlank()) {
            prompt.append("Description: ").append(description).append("\n");
        }
        prompt.append("\nReturn ONLY the slug itself (lowercase, hyphens, no spaces).");
        return prompt.toString();
    }

    // ---------------------------------------------------------------
    // Response parsers
    // ---------------------------------------------------------------

    private List<String> parseTagsFromResponse(String response) {
        List<String> tags = new ArrayList<>();
        if (response == null || response.isBlank()) {
            return tags;
        }

        String clean = response.replaceAll("[\\[\\]\\*]", "").trim();
        String[] parts = clean.split("[,;]");

        for (String tag : parts) {
            String trimmed = tag.replaceAll("^\\d+\\.", "").trim().toLowerCase();
            if (!trimmed.isBlank() && trimmed.length() > 1 && trimmed.length() < 50) {
                tags.add(trimmed);
            }
        }

        return tags.isEmpty() ? Arrays.asList("video", "content") : tags;
    }

    private String cleanResponse(String response) {
        if (response == null) {
            return "";
        }
        return response.replaceAll("\\*\\*|__", "").trim();
    }

    private String cleanSlug(String response) {
        if (response == null || response.isBlank()) {
            return "video";
        }

        String slug = response.toLowerCase()
                .replaceAll("[^a-z0-9-]", "")
                .replaceAll("-{2,}", "-")
                .replaceAll("^-+|-+$", "");

        return slug.isBlank() ? "video" : (slug.length() > 50 ? slug.substring(0, 50) : slug);
    }

    private String slugifyBasic(String input) {
        return input.toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
    }

    private String generatePlaceholder(String title) {
        return "Watch this " + title.toLowerCase() + " content.";
    }
}
