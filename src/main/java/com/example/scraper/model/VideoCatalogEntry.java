package com.example.scraper.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class VideoCatalogEntry {

    private String id;
    private String slug;
    private String title;
    private String description;
    private int durationSeconds;
    private String thumbnailKey;
    private String previewUrl;
    private String embedUrl;
    private String backupEmbedUrl;
    private List<String> tags = new ArrayList<>();
    private String category;
    private String publishedAt;
    private String actressId;
    private String unknownActressName;
    /**
     * CDN URL of the performer's portrait photo. Sourced by scraping
     * the page (e.g. xhamster's tag JSON), downloaded into the JVM,
     * and re-uploaded to Bunny under a {@code .portrait.jpg} key.
     * Distinct from {@link #thumbnailKey}, which is a single frame
     * from the video itself. Null when no avatar was available.
     */
    private String actressPortraitKey;
    private long views;
    /**
     * Optional per-entry pipeline stats: input size, compressed size,
     * encoder used. Populated at the end of the pipeline; persisted
     * to {@code videos.json} so the catalog carries a record of how
     * much each entry was compressed. Null for legacy entries.
     */
    private PipelineStats stats;
    /**
     * Bunny.net pull zones this entry was uploaded to. Order matches
     * server configuration (primary first). Downstream consumers
     * (project-b / the public site) read this to decide which CDN to
     * play from and which to use as failover — the canonical
     * {@link #embedUrl} is always the primary zone's URL, and
     * {@link #backupEmbedUrl} is the secondary zone's URL when more
     * than one zone was selected.
     *
     * <p>Null for legacy entries uploaded before multi-zone support
     * existed (single zone implied).
     */
    private List<String> cdnZoneKeys;

    public VideoCatalogEntry() {
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public int getDurationSeconds() { return durationSeconds; }
    public void setDurationSeconds(int durationSeconds) { this.durationSeconds = durationSeconds; }

    public String getThumbnailKey() { return thumbnailKey; }
    public void setThumbnailKey(String thumbnailKey) { this.thumbnailKey = thumbnailKey; }

    public String getPreviewUrl() { return previewUrl; }
    public void setPreviewUrl(String previewUrl) { this.previewUrl = previewUrl; }

    public String getEmbedUrl() { return embedUrl; }
    public void setEmbedUrl(String embedUrl) { this.embedUrl = embedUrl; }

    public String getBackupEmbedUrl() { return backupEmbedUrl; }
    public void setBackupEmbedUrl(String backupEmbedUrl) { this.backupEmbedUrl = backupEmbedUrl; }

    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String getPublishedAt() { return publishedAt; }
    public void setPublishedAt(String publishedAt) { this.publishedAt = publishedAt; }

    public String getActressId() { return actressId; }
    public void setActressId(String actressId) { this.actressId = actressId; }

    public String getUnknownActressName() { return unknownActressName; }
    public void setUnknownActressName(String unknownActressName) { this.unknownActressName = unknownActressName; }

    public String getActressPortraitKey() { return actressPortraitKey; }
    public void setActressPortraitKey(String actressPortraitKey) { this.actressPortraitKey = actressPortraitKey; }

    public long getViews() { return views; }
    public void setViews(long views) { this.views = views; }

    public PipelineStats getStats() { return stats; }
    public void setStats(PipelineStats stats) { this.stats = stats; }

    public List<String> getCdnZoneKeys() { return cdnZoneKeys; }
    public void setCdnZoneKeys(List<String> cdnZoneKeys) {
        // Defensive copy so callers can mutate their input without
        // aliasing the catalog entry's internal list.
        this.cdnZoneKeys = (cdnZoneKeys == null) ? null : new ArrayList<>(cdnZoneKeys);
    }
}
