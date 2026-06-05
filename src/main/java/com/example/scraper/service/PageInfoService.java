package com.example.scraper.service;

import com.example.scraper.model.PageInfo;
import com.example.scraper.model.VideoResult;
import com.example.scraper.scraper.SiteScraper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Lightweight scraper that pulls "info" (title, description, tags,
 * category, actress) from a page URL. Used by the pipeline page as
 * a non-obligatory pre-fill step.
 *
 * <p>Strategy: prefer Open Graph / Twitter Card / JSON-LD metadata
 * because they're the most stable across sites. Fall back to
 * heuristics (keyword elements, link rel=tag, breadcrumb text) for
 * sites that don't expose structured metadata.
 */
@Service
public class PageInfoService {

    private static final int TIMEOUT_MS = 15_000;

    private final List<SiteScraper> scrapers;

    public PageInfoService(List<SiteScraper> scrapers) {
        this.scrapers = scrapers;
    }

    /**
     * Fetches {@code url} and returns the best-effort extracted
     * metadata. Never throws on missing fields — the {@link PageInfo}
     * simply has empty strings / lists. Network/IO errors are wrapped
     * in {@link IOException}.
     */
    public PageInfo grab(String url) throws IOException {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("url is required");
        }
        String trimmed = url.trim();
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            throw new IllegalArgumentException("url must start with http:// or https://");
        }

        PageInfo info = new PageInfo(trimmed);
        Document doc = Jsoup.connect(trimmed)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                        + "AppleWebKit/537.36 (KHTML, like Gecko) "
                        + "Chrome/124.0.0.0 Safari/537.36")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .timeout(TIMEOUT_MS)
                .followRedirects(true)
                .get();

        info.setSite(detectSite(doc, trimmed));
        info.setTitle(firstNonBlank(
                metaContent(doc, "property", "og:title"),
                metaContent(doc, "name", "twitter:title"),
                metaContent(doc, "name", "title"),
                doc.title()
        ));
        info.setDescription(firstNonBlank(
                metaContent(doc, "property", "og:description"),
                metaContent(doc, "name", "twitter:description"),
                metaContent(doc, "name", "description"),
                firstParagraph(doc)
        ));

        LinkedHashSet<String> tags = new LinkedHashSet<>();
        collectMetaKeywords(doc, tags);
        collectLinkRelTags(doc, tags);
        collectKeywordElements(doc, tags);
        collectAnchorTags(doc, "a[href*='/tag/'], a[href*='/tags/'], a[href*='?tag=']", tags);
        info.setTags(new ArrayList<>(tags));

        info.setCategory(firstNonBlank(
                metaContent(doc, "property", "article:section"),
                metaContent(doc, "name", "category"),
                breadcrumbLast(doc),
                detectCategoryFromUrl(trimmed)
        ));

        info.setActress(firstNonBlank(
                metaContent(doc, "property", "video:actor"),
                metaContent(doc, "name", "twitter:creator"),
                firstModelName(doc)
        ));

        try {
            VideoResult vr = runScraperChain(trimmed);
            if (vr != null) {
                info.setVideoUrls(vr.videoUrls());
                if (info.getTitle().isBlank()) info.setTitle(vr.pageTitle());
            }
        } catch (Exception e) {
            info.getWarnings().add("Video URL discovery failed: " + e.getMessage());
        }

        if (info.getTitle().isBlank() && info.getDescription().isBlank() && info.getTags().isEmpty()) {
            info.getWarnings().add("No structured metadata found; fields will stay empty.");
        }
        return info;
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private String detectSite(Document doc, String url) {
        String og = metaContent(doc, "property", "og:site_name");
        if (!og.isBlank()) return og;
        try {
            String host = java.net.URI.create(url).getHost();
            return host == null ? "" : host;
        } catch (Exception e) {
            return "";
        }
    }

    private String metaContent(Document doc, String attr, String value) {
        Elements els = doc.select("meta[" + attr + "=" + cssEscape(value) + "]");
        for (Element el : els) {
            String c = el.attr("content");
            if (c != null && !c.isBlank()) return c.trim();
        }
        return "";
    }

    private void collectMetaKeywords(Document doc, LinkedHashSet<String> out) {
        String k = metaContent(doc, "name", "keywords");
        if (k.isBlank()) return;
        for (String token : k.split("[,;]")) {
            String t = token.trim();
            if (!t.isEmpty()) out.add(t);
        }
    }

    private void collectLinkRelTags(Document doc, LinkedHashSet<String> out) {
        for (Element el : doc.select("link[rel=tag]")) {
            String t = el.text();
            if (t.isBlank()) t = el.attr("href");
            if (t != null && !t.isBlank()) out.add(stripAfterLastSlash(t));
        }
    }

    private void collectKeywordElements(Document doc, LinkedHashSet<String> out) {
        for (Element el : doc.select("[class*='tag' i], [class*='keyword' i]")) {
            String t = el.text();
            if (t != null && !t.isBlank() && t.length() < 40) out.add(t.trim());
        }
    }

    private void collectAnchorTags(Document doc, String selector, LinkedHashSet<String> out) {
        for (Element el : doc.select(selector)) {
            String t = el.text();
            if (t != null && !t.isBlank() && t.length() < 60) out.add(t.trim());
        }
    }

    private String breadcrumbLast(Document doc) {
        Elements crumbs = doc.select("[class*='breadcrumb' i] a, [class*='breadcrumb' i] span");
        if (crumbs.isEmpty()) return "";
        return crumbs.last().text().trim();
    }

    private String detectCategoryFromUrl(String url) {
        try {
            String path = java.net.URI.create(url).getPath();
            if (path == null) return "";
            String[] parts = path.split("/");
            for (String p : parts) {
                if (p.isBlank()) continue;
                if (p.equals("a") || p.equals("v") || p.matches("^[a-z0-9]{6,}$")) continue;
                return p.replace('-', ' ').trim();
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private String firstModelName(Document doc) {
        for (Element el : doc.select("[class*='model' i] a, [class*='performer' i] a, [class*='pornstar' i] a, [class*='actress' i] a")) {
            String t = el.text();
            if (t != null && !t.isBlank() && t.length() < 60) return t.trim();
        }
        return "";
    }

    private String firstParagraph(Document doc) {
        Element p = doc.selectFirst("article p, main p, .description, [class*='description' i]");
        return p == null ? "" : p.text();
    }

    private VideoResult runScraperChain(String url) throws IOException {
        for (SiteScraper s : scrapers) {
            if (s.supports(url)) return s.extractVideos(url);
        }
        return null;
    }

    private static String firstNonBlank(String... candidates) {
        for (String c : candidates) {
            if (c != null && !c.isBlank()) return c;
        }
        return "";
    }

    private static String stripAfterLastSlash(String s) {
        int idx = s.lastIndexOf('/');
        return idx < 0 ? s : s.substring(idx + 1);
    }

    /** CSS attribute-selector escape for values containing ':' (the only char we use). */
    private static String cssEscape(String value) {
        return value.replace(":", "\\:");
    }
}
