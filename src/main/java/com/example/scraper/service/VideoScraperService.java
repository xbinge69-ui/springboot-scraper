package com.example.scraper.service;

import com.example.scraper.model.VideoResult;
import com.example.scraper.scraper.SiteScraper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Delegates to the correct {@link SiteScraper} implementation based on the URL.
 * Spring injects all @Component beans that implement SiteScraper automatically —
 * adding a new site only requires creating a new @Component class.
 */
@Service
public class VideoScraperService {

    private final List<SiteScraper> scrapers;

    public VideoScraperService(List<SiteScraper> scrapers) {
        this.scrapers = scrapers;
    }

    /**
     * Finds a matching scraper for the URL and extracts video information.
     *
     * @param url the page URL to scrape
     * @return a {@link VideoResult} with found video URLs
     * @throws IOException              on network or parse failure
     * @throws IllegalArgumentException if no scraper supports the given URL
     */
    public VideoResult extractVideos(String url) throws IOException {
        SiteScraper scraper = scrapers.stream()
                .filter(s -> s.supports(url))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Aucun scraper disponible pour : " + url));

        return scraper.extractVideos(url);
    }

    /**
     * Returns true if at least one registered scraper supports the URL.
     */
    public boolean isSupported(String url) {
        return scrapers.stream().anyMatch(s -> s.supports(url));
    }

    /**
     * Returns the list of registered scraper names.
     */
    public List<String> supportedSiteNames() {
        return scrapers.stream()
                .map(SiteScraper::siteName)
                .distinct()
                .collect(Collectors.toList());
    }
}
