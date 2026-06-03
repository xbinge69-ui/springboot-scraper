package com.example.scraper.model;

public class PipelineRequest {

    private String sourcePageUrl;
    private String title;
    private String description;
    private String tags;
    private String category;
    private String unknownActressName;
    private String actressId;
    private Long views;

    public String getSourcePageUrl() { return sourcePageUrl; }
    public void setSourcePageUrl(String sourcePageUrl) { this.sourcePageUrl = sourcePageUrl; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getTags() { return tags; }
    public void setTags(String tags) { this.tags = tags; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String getUnknownActressName() { return unknownActressName; }
    public void setUnknownActressName(String unknownActressName) { this.unknownActressName = unknownActressName; }

    public String getActressId() { return actressId; }
    public void setActressId(String actressId) { this.actressId = actressId; }

    public Long getViews() { return views; }
    public void setViews(Long views) { this.views = views; }
}
