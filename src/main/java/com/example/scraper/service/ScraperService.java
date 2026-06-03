package com.example.scraper.service;

import com.example.scraper.model.ScrapedItem;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Service
public class ScraperService {

    private static final int TIMEOUT_MS = 10_000;

    /**
     * Scrapes the given URL and extracts all anchor links with their text.
     *
     * @param targetUrl the URL to scrape
     * @return a list of ScrapedItem records
     * @throws IOException if the connection fails or the URL is malformed
     */
    public List<ScrapedItem> scrape(String targetUrl) throws IOException {
        Document doc = Jsoup.connect(targetUrl)
                .userAgent("Mozilla/5.0 (compatible; ScraperBot/1.0)")
                .timeout(TIMEOUT_MS)
                .get();

        List<ScrapedItem> items = new ArrayList<>();

        // Extract all <a> tags that have a non-empty href
        Elements links = doc.select("a[href]");
        for (Element link : links) {
            String title = link.text().isBlank() ? "(no text)" : link.text();
            String href  = link.absUrl("href");
            String desc  = link.attr("title");
            if (!href.isBlank()) {
                items.add(new ScrapedItem(title, href, desc));
            }
        }

        return items;
    }

    /**
     * Returns the raw <title> of a page.
     */
    public String getPageTitle(String targetUrl) throws IOException {
        Document doc = Jsoup.connect(targetUrl)
                .userAgent("Mozilla/5.0 (compatible; ScraperBot/1.0)")
                .timeout(TIMEOUT_MS)
                .get();
        return doc.title();
    }
}
