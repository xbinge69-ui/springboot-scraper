package com.example.scraper.scraper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Extracts the full tag list (pornstars, channels, categories, regular tags,
 * brands) from an xhamster video page.
 *
 * <p>xhamster renders only ~18 tags in the visible DOM (the rest are loaded
 * via a "more" toggle). All tags are, however, embedded in the page source
 * as a JSON array under the key {@code "tags"} — e.g.
 * <pre>
 *   "tags":[{"id":40527,"name":"LMAO GFs","isChannel":true,...},
 *           {"name":"Faye Reagan","isPornstar":true,...},
 *           {"name":"Babe","isCategory":true,...},
 *           {"name":"Hottest","isTag":true,...}, ...]
 * </pre>
 *
 * <p>This extractor finds that array, parses it with Jackson, and groups
 * entries by type:
 * <ul>
 *   <li>{@link XhamsterTags#all} — every visible tag's name (preserves order,
 *       deduplicated, {@code null}-safe)</li>
 *   <li>{@link XhamsterTags#pornstars} — entries with {@code isPornstar=true};
 *       these are the "tags with avatars" (the actresses / male performers)</li>
 *   <li>{@link XhamsterTags#categories} — {@code isCategory=true}</li>
 *   <li>{@link XhamsterTags#channels} — {@code isChannel=true}</li>
 *   <li>{@link XhamsterTags#brands} — {@code isBrand=true}</li>
 * </ul>
 *
 * <p>Brand and channel entries are intentionally excluded from {@code all}
 * because they are not topical tags — they are the producing channel/brand
 * and they tend to be low-signal as content descriptors.
 */
public final class XhamsterTagExtractor {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private XhamsterTagExtractor() {}

    /** Empty-result singleton. Avoids allocating an empty record per failed call. */
    public static final XhamsterTags EMPTY = new XhamsterTags(
            List.of(), List.of(), List.of(), List.of(), List.of());

    /**
     * @return true when {@code url} looks like an xhamster video page
     *         ({@code xhamster.com} or any of its country variants, e.g.
     *         {@code xhamster.desi}, {@code xhamster2.com}, etc.).
     */
    public static boolean isXhamster(String url) {
        if (url == null) return false;
        String s = url.toLowerCase(Locale.ROOT);
        return s.contains("xhamster");
    }

    /**
     * Run the extractor against an already-fetched {@link Document}.
     * Returns {@link #EMPTY} on any failure — callers should treat this
     * as a graceful fallback (the rest of the page-info pipeline can still
     * try the generic selector-based strategies).
     */
    public static XhamsterTags extract(Document doc) {
        if (doc == null) return EMPTY;
        JsonNode arr = findTagsArray(doc);
        if (arr == null || !arr.isArray()) return EMPTY;
        return parseArray(arr);
    }

    // ------------------------------------------------------------------
    // internals
    // ------------------------------------------------------------------

    /**
     * Locate the "tags":[...] JSON node in any {@code <script>} element.
     * We try the structured-DOM {@code script#data} first (Jsoup parses
     * JSON inside scripts as raw text), then a last-ditch regex over the
     * whole document HTML for resilience against script-attribute changes.
     */
    private static JsonNode findTagsArray(Document doc) {
        for (Element script : doc.select("script")) {
            String body = script.data();
            if (body == null || body.isEmpty()) continue;
            JsonNode node = tryFindTagsArray(body);
            if (node != null) return node;
        }
        // Fallback: scan the whole document HTML.
        return tryFindTagsArray(doc.outerHtml());
    }

    private static JsonNode tryFindTagsArray(String text) {
        // We want the top-level "tags":[ ... ] array. xhamster embeds it
        // inside its huge page-state object; nested objects/arrays may
        // also carry a "tags" key we want to ignore. Heuristic: gather
        // every "tags":[ ... ] candidate, parse each, and keep the one
        // with the most entries — the real top-level list always has
        // far more entries than any nested "tags" array.
        JsonNode best = null;
        int bestSize = -1;
        int from = 0;
        while (true) {
            int keyIdx = indexOfKey(text, "\"tags\"", from);
            if (keyIdx < 0) break;
            int colon = text.indexOf(':', keyIdx);
            if (colon < 0) { from = keyIdx + 1; continue; }
            int i = colon + 1;
            while (i < text.length() && Character.isWhitespace(text.charAt(i))) i++;
            if (i >= text.length() || text.charAt(i) != '[') { from = keyIdx + 1; continue; }
            int end = matchBalanced(text, i);
            if (end < 0) { from = keyIdx + 1; continue; }
            String json = text.substring(i, end + 1);
            try {
                JsonNode node = MAPPER.readTree(json);
                if (node != null && node.isArray()) {
                    int size = node.size();
                    if (size > bestSize) {
                        best = node;
                        bestSize = size;
                    }
                }
            } catch (IOException ignored) {
                // Try next candidate.
            }
            from = keyIdx + 1;
        }
        return best;
    }

    /** Returns the index of {@code "tags"} as a JSON key (preceded by , { or [, with optional whitespace, and followed by a colon). */
    private static int indexOfKey(String text, String key, int from) {
        while (true) {
            int idx = text.indexOf(key, from);
            if (idx < 0) return -1;
            // Walk back over whitespace to find the structural char.
            int k = idx - 1;
            while (k >= 0 && Character.isWhitespace(text.charAt(k))) k--;
            if (k >= 0) {
                char before = text.charAt(k);
                if (before == ',' || before == '{' || before == '[') {
                    // confirm colon shortly after (skipping whitespace)
                    int j = idx + key.length();
                    while (j < text.length() && Character.isWhitespace(text.charAt(j))) j++;
                    if (j < text.length() && text.charAt(j) == ':') {
                        return idx;
                    }
                }
            }
            from = idx + 1;
        }
    }

    /**
     * Given an index pointing at {@code [}, returns the index of the matching
     * {@code ]} (skipping over strings and escaped chars). Returns -1 if the
     * brackets don't balance by EOF.
     */
    private static int matchBalanced(String s, int openIdx) {
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = openIdx; i < s.length(); i++) {
            char c = s.charAt(i);
            if (escape) { escape = false; continue; }
            if (inString) {
                if (c == '\\') { escape = true; continue; }
                if (c == '"') inString = false;
                continue;
            }
            if (c == '"') { inString = true; continue; }
            if (c == '[') depth++;
            else if (c == ']') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    private static XhamsterTags parseArray(JsonNode arr) {
        Set<String> all = new LinkedHashSet<>();
        List<String> pornstars = new ArrayList<>();
        List<String> categories = new ArrayList<>();
        List<String> channels = new ArrayList<>();
        List<String> brands = new ArrayList<>();

        for (JsonNode item : arr) {
            if (item == null || !item.isObject()) continue;
            String name = pickName(item);
            if (name == null || name.isBlank()) continue;

            boolean isPornstar = boolField(item, "isPornstar");
            boolean isChannel  = boolField(item, "isChannel");
            boolean isCategory = boolField(item, "isCategory");
            boolean isTag      = boolField(item, "isTag");
            boolean isBrand    = boolField(item, "isBrand");
            boolean isCreator  = boolField(item, "isCreator");
            boolean isCeleb    = boolField(item, "isCeleb");

            if (isPornstar) {
                pornstars.add(name);
                all.add(name);  // actresses also surface as tags
            } else if (isCategory) {
                categories.add(name);
                all.add(name);
            } else if (isChannel) {
                channels.add(name);
                // Channels are NOT added to `all` — they're the producing
                // channel, not a content tag. The LLM should not echo them.
            } else if (isBrand) {
                brands.add(name);
                // Same rationale as channels.
            } else if (isTag || isCreator || isCeleb) {
                all.add(name);
            } else {
                // Unknown type — include defensively, the LLM can decide.
                all.add(name);
            }
        }
        return new XhamsterTags(
                new ArrayList<>(all),
                Collections.unmodifiableList(pornstars),
                Collections.unmodifiableList(categories),
                Collections.unmodifiableList(channels),
                Collections.unmodifiableList(brands));
    }

    /**
     * xhamster uses {@code name} for entities that have a native name and
     * {@code nameEn} as the English fallback (channels/studios use both).
     * Prefer {@code name} so localized display names survive.
     */
    private static String pickName(JsonNode item) {
        JsonNode n = item.get("name");
        if (n == null || n.isNull()) {
            n = item.get("nameEn");
        }
        if (n == null || n.isNull()) return null;
        String s = n.asText();
        return (s == null) ? null : s.trim();
    }

    private static boolean boolField(JsonNode item, String field) {
        JsonNode v = item.get(field);
        return v != null && v.isBoolean() && v.asBoolean();
    }

    // ------------------------------------------------------------------

    /**
     * Result of a single xhamster extraction. Lists are unmodifiable and
     * preserve the order in which entries appeared in the source array.
     */
    public record XhamsterTags(
            List<String> all,
            List<String> pornstars,
            List<String> categories,
            List<String> channels,
            List<String> brands
    ) {
        public XhamsterTags {
            all       = all       == null ? List.of() : List.copyOf(all);
            pornstars = pornstars == null ? List.of() : List.copyOf(pornstars);
            categories= categories== null ? List.of() : List.copyOf(categories);
            channels  = channels  == null ? List.of() : List.copyOf(channels);
            brands    = brands    == null ? List.of() : List.copyOf(brands);
        }
    }
}
