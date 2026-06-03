package com.example.scraper.model;

public class DirectVideoIngestRequest {

    private String title;
    private String description;
    private String category;
    private String tags;
    private String unknownActressName;
    private String actressId;

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String getTags() { return tags; }
    public void setTags(String tags) { this.tags = tags; }

    public String getUnknownActressName() { return unknownActressName; }
    public void setUnknownActressName(String unknownActressName) { this.unknownActressName = unknownActressName; }

    public String getActressId() { return actressId; }
    public void setActressId(String actressId) { this.actressId = actressId; }
}
