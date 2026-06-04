package com.example.scraper.video;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds the "Topical Authority" prompt sent to Ollama and parses the
 * model's JSON reply. The build step is a pure string concatenation
 * (no IO); the parse step is a pure function of the response text.
 * Both are static for easy unit testing.
 *
 * <p>The parser is deliberately defensive: it strips markdown fences,
 * finds the first balanced {@code {...}} block via a stack matcher
 * (not a regex), and falls back per-field to the input metadata when
 * the model returns malformed output. The Ollama request is configured
 * with {@code options.format=json} server-side, so 80% of the brittleness
 * is mitigated before the response even leaves the model.
 */
public final class TopicalAuthorityPrompt {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TopicalAuthorityPrompt() {}

    /**
     * Build the prompt the orchestrator sends to Ollama. {@code catalogSummary}
     * is a compact JSON blob produced by
     * {@code VideoCatalogService.summarize()}.
     */
    public static String buildPrompt(EnrichmentMetadata input, String catalogSummary) {
        StringBuilder sb = new StringBuilder(2048);
        sb.append("You are an SEO and topical-authority expert for an adult video site ")
          .append("called \"SpankyCouples\". Your job is to refine a new video's draft ")
          .append("metadata so the new entry builds topical authority across the existing ")
          .append("catalog.\n\n");

        sb.append("You will be given:\n")
          .append("1. The new video's current metadata (title, description, category, tags).\n")
          .append("2. A compact summary of the existing catalog (categories with counts, the ")
          .append("top 30 tags with counts, and the 30 most recent titles).\n\n");

        sb.append("Your task — LIGHT POLISH ONLY:\n")
          .append("- The input title already comes from the page the video was scraped ")
          .append("from. Your job is to lightly polish it for SEO (fix capitalization, ")
          .append("expand obvious abbreviations, remove debug/test words like \"test\", ")
          .append("\"smoke\", \"debug\", \"sample\"), not invent a new title. Keep the ")
          .append("core subject (people, scene, activity) intact. Aim for 60-80 chars.\n")
          .append("- The input description is also from the page. Polish it: tighten the ")
          .append("wording, fix typos, keep the factual subject matter. Don't fabricate ")
          .append("details not present in the input or in the catalog summary.\n")
          .append("- The input tags may be a small list. Expand to 5-8 by adding tags that ")
          .append("already appear in the existing catalog (this is how we build internal ")
          .append("topical authority). Keep all original input tags. Lowercase, hyphenated.\n")
          .append("- Slug: a tight, URL-friendly version of the polished title (lowercase, ")
          .append("hyphens, no debug words, max 50 chars, ends in a content noun).\n")
          .append("- Category: prefer an existing one from the catalog unless none has 3+ ")
          .append("entries that match the subject.\n")
          .append("- Views: realistic starting view count, integer in 50,000 to 500,000. ")
          .append("Bias toward the higher end for categories that are common.\n\n");

        sb.append("OUTPUT FORMAT:\n")
          .append("Return a single valid JSON object with EXACTLY these fields, no other ")
          .append("text, no markdown fences, no commentary:\n")
          .append("{\n")
          .append("  \"title\":       \"string\",\n")
          .append("  \"description\": \"string\",\n")
          .append("  \"category\":    \"string\",\n")
          .append("  \"tags\":        [\"string\", ...],\n")
          .append("  \"slug\":        \"string\",\n")
          .append("  \"views\":       integer\n")
          .append("}\n\n");

        sb.append("NEW VIDEO METADATA:\n")
          .append("Title: ").append(input.titleOrFallback()).append('\n')
          .append("Description: ").append(nullSafe(input.description())).append('\n')
          .append("Category: ").append(nullSafe(input.category())).append('\n')
          .append("Tags: ").append(input.tags() == null ? "" : String.join(", ", input.tags())).append('\n')
          .append('\n');

        if (catalogSummary == null || catalogSummary.isBlank() || "\"size\":0".equals(extractSize(catalogSummary))) {
            sb.append("EXISTING CATALOG SUMMARY:\n")
              .append("(empty - you are the seed entry. Propose a clean initial category ")
              .append("taxonomy and avoid being too narrow.)\n");
        } else {
            sb.append("EXISTING CATALOG SUMMARY:\n").append(catalogSummary).append('\n');
        }
        return sb.toString();
    }

    /**
     * Parse Ollama's response into a {@link TopicalAuthorityResult}. Returns
     * a fallback (per-field from {@code input}) on any parse failure, so
     * the caller never has to handle a null.
     */
    public static TopicalAuthorityResult parse(String response, EnrichmentMetadata input) {
        if (response == null || response.isBlank()) {
            return fallback(input);
        }
        String json = extractFirstJsonObject(response);
        if (json == null) {
            return fallback(input);
        }
        try {
            JsonNode root = MAPPER.readTree(json);
            if (root.isArray()) {
                if (root.isEmpty()) return fallback(input);
                root = root.get(0);
            }
            if (root == null || !root.isObject()) {
                return fallback(input);
            }
            String title = textOrNull(root, "title");
            String description = textOrNull(root, "description");
            String category = textOrNull(root, "category");
            String slug = textOrNull(root, "slug");
            List<String> tags = readTags(root.get("tags"));
            long views = parseViews(root.get("views"));

            return new TopicalAuthorityResult(
                    nonBlank(title) ? title : input.titleOrFallback(),
                    nonBlank(description) ? description : nullSafe(input.description()),
                    nonBlank(category) ? category : input.categoryOrFallback(),
                    tags.isEmpty() ? (input.tags() == null ? List.of() : input.tags()) : tags,
                    nonBlank(slug) ? slug : com.example.scraper.util.Slugify.slugify(input.titleOrFallback()),
                    views
            );
        } catch (Exception e) {
            return fallback(input);
        }
    }

    private static TopicalAuthorityResult fallback(EnrichmentMetadata input) {
        return new TopicalAuthorityResult(
                input.titleOrFallback(),
                nullSafe(input.description()),
                input.categoryOrFallback(),
                input.tags() == null ? List.of() : input.tags(),
                com.example.scraper.util.Slugify.slugify(input.titleOrFallback()),
                0L
        );
    }

    // ---- helpers ----

    private static String textOrNull(JsonNode root, String field) {
        JsonNode n = root.get(field);
        if (n == null || n.isNull() || !n.isValueNode()) return null;
        String s = n.asText();
        return s == null ? null : s;
    }

    private static boolean nonBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    /**
     * Coerce {@code tags} to a list of strings. Handles (a) JSON array
     * of strings, (b) a single CSV string, (c) null, (d) a single value
     * (treated as a one-element list).
     */
    private static List<String> readTags(JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (node.isArray()) {
            List<String> out = new ArrayList<>();
            for (JsonNode item : node) {
                if (item != null && item.isValueNode()) {
                    String s = item.asText();
                    if (s != null && !s.isBlank()) out.add(s.trim().toLowerCase(Locale.ROOT));
                }
            }
            return out;
        }
        if (node.isValueNode()) {
            String csv = node.asText();
            if (csv == null || csv.isBlank()) return List.of();
            return Arrays.stream(csv.split("[,;]"))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(s -> s.toLowerCase(Locale.ROOT))
                    .toList();
        }
        return List.of();
    }

    /**
     * Coerce {@code views} to a long. Handles: number-as-long, number-as-double
     * (truncates), number-as-string with or without thousands separators
     * ("372,893" / "372893"), null, missing field, unparseable. Returns 0 on
     * any failure.
     */
    private static long parseViews(JsonNode node) {
        if (node == null || node.isNull()) return 0L;
        if (node.isNumber()) {
            return (long) node.asDouble();
        }
        if (node.isTextual()) {
            String s = node.asText();
            if (s == null) return 0L;
            String cleaned = s.replace(",", "").replace(" ", "").trim();
            if (cleaned.isEmpty()) return 0L;
            try {
                return Long.parseLong(cleaned);
            } catch (NumberFormatException e) {
                try {
                    return (long) Double.parseDouble(cleaned);
                } catch (NumberFormatException e2) {
                    return 0L;
                }
            }
        }
        return 0L;
    }

    /**
     * Strip markdown fences and surrounding prose, then return the first
     * balanced top-level JSON object as a string. Returns null if no
     * balanced object is found.
     */
    static String extractFirstJsonObject(String text) {
        // 1) Strip ```json ... ``` fences.
        Pattern fenced = Pattern.compile("```(?:json)?\\s*(\\{[\\s\\S]*?\\})\\s*```", Pattern.CASE_INSENSITIVE);
        Matcher m = fenced.matcher(text);
        if (m.find()) {
            return m.group(1);
        }
        // 2) Walk the string and find the first balanced {...} block.
        int depth = 0;
        int start = -1;
        boolean inString = false;
        boolean escape = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escape) { escape = false; continue; }
            if (c == '\\' && inString) { escape = true; continue; }
            if (c == '"') { inString = !inString; continue; }
            if (inString) continue;
            if (c == '{') {
                if (depth == 0) start = i;
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && start >= 0) {
                    return text.substring(start, i + 1);
                }
            }
        }
        return null;
    }

    private static String extractSize(String catalogSummary) {
        try {
            JsonNode n = MAPPER.readTree(catalogSummary);
            JsonNode s = n.get("size");
            if (s == null) return null;
            return "\"size\":" + s.asText();
        } catch (Exception e) {
            return null;
        }
    }
}
