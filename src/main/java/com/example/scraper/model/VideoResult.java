package com.example.scraper.model;

import java.util.List;

/**
 * Holds the extracted video(s) from a scraped page.
 */
public class VideoResult {

    private final String albumUrl;
    private final String pageTitle;
    private final String site;
    private final List<String> videoUrls;

    public VideoResult(String albumUrl, String pageTitle, String site, List<String> videoUrls) {
        this.albumUrl = albumUrl;
        this.pageTitle = pageTitle;
        this.site = site;
        this.videoUrls = videoUrls;
    }

    public String albumUrl()   { return albumUrl; }
    public String pageTitle()  { return pageTitle; }
    public String site()       { return site; }
    public List<String> videoUrls() { return videoUrls; }

    /** Convenience: first video URL, or null if none found. */
    public String firstVideoUrl() {
        return videoUrls.isEmpty() ? null : videoUrls.get(0);
    }
}
