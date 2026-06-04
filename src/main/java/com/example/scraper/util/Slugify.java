package com.example.scraper.util;

import java.util.List;

/**
 * Static URL-slug helpers shared by the enrichment pipeline and any
 * legacy callers. A slug is lowercase, hyphen-separated, ASCII-only, max
 * 50 characters.
 */
public final class Slugify {

    private Slugify() {}

    /** Produce a URL-friendly slug from arbitrary input. Never returns null. */
    public static String slugify(String input) {
        if (input == null || input.isBlank()) {
            return "video";
        }
        String lower = input.toLowerCase();
        // Replace any non-alphanumeric with '-'
        StringBuilder out = new StringBuilder(lower.length());
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                out.append(c);
            } else {
                out.append('-');
            }
        }
        // Collapse runs of '-'
        String collapsed = out.toString().replaceAll("-+", "-");
        // Trim leading/trailing '-'
        String trimmed = collapsed.replaceAll("^-|-$", "");
        if (trimmed.isEmpty()) {
            return "video";
        }
        return trimmed.length() > 50 ? trimmed.substring(0, 50).replaceAll("-$", "") : trimmed;
    }

    /**
     * Return {@code base} if no existing slug equals it (case-insensitive);
     * otherwise suffix {@code -2}, {@code -3}, ... until unique.
     */
    public static String uniqueSlug(String base, List<String> existing) {
        if (base == null || base.isBlank()) {
            base = "video";
        }
        if (existing == null || existing.isEmpty()) {
            return base;
        }
        java.util.Set<String> taken = new java.util.HashSet<>();
        for (String s : existing) {
            if (s != null) taken.add(s.toLowerCase());
        }
        if (!taken.contains(base.toLowerCase())) {
            return base;
        }
        for (int i = 2; i < 10_000; i++) {
            String candidate = base + "-" + i;
            if (!taken.contains(candidate.toLowerCase())) {
                return candidate;
            }
        }
        return base + "-" + java.util.UUID.randomUUID().toString().substring(0, 6);
    }
}
