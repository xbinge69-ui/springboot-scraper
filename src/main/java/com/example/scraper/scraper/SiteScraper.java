package com.example.scraper.scraper;

import com.example.scraper.model.VideoResult;

import java.io.IOException;

/**
 * Strategy interface: one implementation per supported website.
 * Keeps each site's scraping logic isolated and makes it easy
 * to add new sites later without touching the service layer.
 */
public interface SiteScraper {

    /**
     * Returns true if this scraper can handle the given URL.
     *
     * @param url the page URL to test
     * @return true when this implementation should be used
     */
    boolean supports(String url);

    /**
     * Scrapes the page at {@code url} and extracts video information.
     *
     * @param url the page URL
     * @return a {@link VideoResult} containing all found video URLs
     * @throws IOException on network or parse failure
     */
    VideoResult extractVideos(String url) throws IOException;

    /**
     * Human-readable name of the site handled by this scraper.
     */
    String siteName();
}
