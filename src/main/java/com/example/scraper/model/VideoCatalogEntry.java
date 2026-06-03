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
    private long views;

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

    public long getViews() { return views; }
    public void setViews(long views) { this.views = views; }
}
