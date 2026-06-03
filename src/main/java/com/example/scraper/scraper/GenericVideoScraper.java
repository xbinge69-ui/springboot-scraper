package com.example.scraper.scraper;

import com.example.scraper.model.VideoResult;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Generic fallback scraper for sites without dedicated implementation.
 *
 * This scraper uses common selectors and script URL scanning.
 * It will not bypass site protections by itself, but it enables
 * multi-source extraction out of the box.
 */
@Component
@Order(1000)
public class GenericVideoScraper implements SiteScraper {

    private static final int TIMEOUT_MS = 12_000;

    private static final Pattern VIDEO_URL_PATTERN =
            Pattern.compile("https?://[^\"'\\s]+(?:\\.mp4|\\.m3u8|\\.webm|\\.mov)[^\"'\\s]*", Pattern.CASE_INSENSITIVE);

    @Override
    public boolean supports(String url) {
        return url != null && (url.startsWith("http://") || url.startsWith("https://"));
    }

    @Override
    public VideoResult extractVideos(String url) throws IOException {
        Document doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .timeout(TIMEOUT_MS)
                .followRedirects(true)
                .get();

        LinkedHashSet<String> found = new LinkedHashSet<>();

        for (Element el : doc.select("video[src]")) {
            addIfVideo(el.absUrl("src"), found);
        }
        for (Element el : doc.select("video source[src]")) {
            addIfVideo(el.absUrl("src"), found);
        }
        for (Element el : doc.select("meta[property=og:video][content],meta[name=twitter:player:stream][content],meta[itemprop=contentUrl][content]")) {
            addIfVideo(el.absUrl("content"), found);
        }
        for (Element el : doc.select("[data-src],[data-video],[data-url]")) {
            addIfVideo(el.absUrl("data-src"), found);
            addIfVideo(el.absUrl("data-video"), found);
            addIfVideo(el.absUrl("data-url"), found);
        }

        if (found.isEmpty()) {
            for (Element script : doc.select("script")) {
                Matcher m = VIDEO_URL_PATTERN.matcher(script.data());
                while (m.find()) {
                    addIfVideo(m.group(), found);
                }
            }
        }

        return new VideoResult(url, doc.title(), siteName(), new ArrayList<>(found));
    }

    @Override
    public String siteName() {
        return "Generic";
    }

    private void addIfVideo(String value, LinkedHashSet<String> bucket) {
        if (value == null || value.isBlank()) {
            return;
        }
        String lower = value.toLowerCase();
        if (lower.contains(".mp4") || lower.contains(".m3u8") || lower.contains(".webm") || lower.contains(".mov")) {
            bucket.add(value);
        }
    }
}
