package com.example.scraper.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    /**
     * All actresses / pornstars (male or female) detected on the page.
     * Populated by site-specific extractors (e.g. xhamster) that can
     * distinguish performers from regular tags. The legacy single
     * {@link #actress} field is kept for backward compatibility — it is
     * populated with the first entry of this list when the list is non-empty.
     */
    private List<String> pornstars = new ArrayList<>();
    /**
     * Avatar URL for each pornstar name, scraped from the source page
     * (e.g. xhamster's tag JSON). Keys are entries from {@link #pornstars};
     * values are the avatar/thumbnail URL. Empty for source pages that
     * don't expose performer avatars.
     */
    private Map<String, String> pornstarAvatars = new LinkedHashMap<>();
    /**
     * All channels detected on the page (e.g. xhamster's "LMAO GFs").
     * Channels are the producing studio/brand, not a content tag.
     */
    private List<String> channels = new ArrayList<>();
    /**
     * All content categories detected on the page (e.g. xhamster's
     * "Babe", "Blowjob"). These are usually a coarser taxonomy than tags.
     */
    private List<String> categories = new ArrayList<>();
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

    public List<String> getPornstars() { return pornstars; }
    public void setPornstars(List<String> pornstars) { this.pornstars = pornstars == null ? new ArrayList<>() : pornstars; }

    public Map<String, String> getPornstarAvatars() { return pornstarAvatars; }
    public void setPornstarAvatars(Map<String, String> pornstarAvatars) {
        this.pornstarAvatars = pornstarAvatars == null ? new LinkedHashMap<>() : pornstarAvatars;
    }

    public List<String> getChannels() { return channels; }
    public void setChannels(List<String> channels) { this.channels = channels == null ? new ArrayList<>() : channels; }

    public List<String> getCategories() { return categories; }
    public void setCategories(List<String> categories) { this.categories = categories == null ? new ArrayList<>() : categories; }

    public String getSite() { return site; }
    public void setSite(String site) { this.site = site == null ? "" : site; }

    public List<String> getVideoUrls() { return videoUrls; }
    public void setVideoUrls(List<String> videoUrls) { this.videoUrls = videoUrls == null ? new ArrayList<>() : videoUrls; }

    public List<String> getWarnings() { return warnings; }
    public void setWarnings(List<String> warnings) { this.warnings = warnings == null ? new ArrayList<>() : warnings; }
}
