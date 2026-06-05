package com.example.scraper.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Lightweight page-level info scraped from a source URL.
 * Used by the pipeline page to pre-fill the form when the user
 * clicks "Grab Info" (not obligatory).
 */
public class PageInfo {

    private String sourcePageUrl;
    private String title = "";
    private String description = "";
    private List<String> tags = new ArrayList<>();
    private String category = "";
    private String actress = "";
    private String site = "";
    private List<String> videoUrls = new ArrayList<>();
    private List<String> warnings = new ArrayList<>();

    public PageInfo() {
    }

    public PageInfo(String sourcePageUrl) {
        this.sourcePageUrl = sourcePageUrl;
    }

    public String getSourcePageUrl() { return sourcePageUrl; }
    public void setSourcePageUrl(String sourcePageUrl) { this.sourcePageUrl = sourcePageUrl; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title == null ? "" : title; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description == null ? "" : description; }

    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags == null ? new ArrayList<>() : tags; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category == null ? "" : category; }

    public String getActress() { return actress; }
    public void setActress(String actress) { this.actress = actress == null ? "" : actress; }

    public String getSite() { return site; }
    public void setSite(String site) { this.site = site == null ? "" : site; }

    public List<String> getVideoUrls() { return videoUrls; }
    public void setVideoUrls(List<String> videoUrls) { this.videoUrls = videoUrls == null ? new ArrayList<>() : videoUrls; }

    public List<String> getWarnings() { return warnings; }
    public void setWarnings(List<String> warnings) { this.warnings = warnings == null ? new ArrayList<>() : warnings; }
}
