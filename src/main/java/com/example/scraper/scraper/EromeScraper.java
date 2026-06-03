package com.example.scraper.scraper;

import com.example.scraper.model.VideoResult;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scraper for Erome album pages (https://www.erome.com/a/{id}).
 *
 * Erome serves video sources in several ways depending on the browser/CDN:
 *   1. <video src="...">
 *   2. <video><source src="..."></video>
 *   3. <div data-src="...">  (lazy-loaded)
 *   4. Inline JS:  "https://...mp4" embedded in a script tag
 *
 * We try all four selectors and deduplicate results.
 */
@Component
@Order(10)
public class EromeScraper implements SiteScraper {

    private static final String SITE   = "Erome";
    private static final String HOST   = "erome.com";
    private static final int    TIMEOUT = 15_000;

    // MP4 URLs that appear in inline scripts  (non-greedy, stops at quote)
    private static final Pattern JS_MP4_PATTERN =
            Pattern.compile("https?://[^\"'\\s]+\\.mp4[^\"'\\s]*");

    @Override
    public boolean supports(String url) {
        return url != null && (url.contains("erome.com") || url.contains("www.erome.com"));
    }

    @Override
    public String siteName() {
        return SITE;
    }

    @Override
    public VideoResult extractVideos(String url) throws IOException {
        Document doc = buildConnection(url).get();

        // Use a LinkedHashSet to preserve discovery order and avoid duplicates
        LinkedHashSet<String> found = new LinkedHashSet<>();

        // --- Strategy 1: <video src="...">
        for (Element el : doc.select("video[src]")) {
            addIfVideo(el.absUrl("src"), found);
        }

        // --- Strategy 2: <source src="..."> inside a <video>
        for (Element el : doc.select("video source[src]")) {
            addIfVideo(el.absUrl("src"), found);
        }

        // --- Strategy 3: any element with data-src pointing to a video
        for (Element el : doc.select("[data-src]")) {
            addIfVideo(el.absUrl("data-src"), found);
        }

        // --- Strategy 4: scan inline <script> blocks for .mp4 URLs
        if (found.isEmpty()) {
            Elements scripts = doc.select("script");
            for (Element script : scripts) {
                Matcher m = JS_MP4_PATTERN.matcher(script.data());
                while (m.find()) {
                    addIfVideo(m.group(), found);
                }
            }
        }

        return new VideoResult(url, doc.title(), SITE, new ArrayList<>(found));
    }

    // -------------------------------------------------------------------------

    private Connection buildConnection(String url) {
        return Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                        + "AppleWebKit/537.36 (KHTML, like Gecko) "
                        + "Chrome/124.0.0.0 Safari/537.36")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .referrer("https://www.erome.com/")
                .timeout(TIMEOUT)
                .followRedirects(true);
    }

    /** Adds the URL to the set only when it looks like a playable video. */
    private void addIfVideo(String url, LinkedHashSet<String> set) {
        if (url == null || url.isBlank()) return;
        String lower = url.toLowerCase();
        if (lower.contains(".mp4") || lower.contains(".m3u8")
                || lower.contains(".webm") || lower.contains(".mov")) {
            set.add(url);
        }
    }
}
