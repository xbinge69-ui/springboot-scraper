package com.example.scraper.model;

public class PipelineRequest {

    private String sourcePageUrl;
    private String title;
    private String description;
    private String tags;
    private String category;
    private String unknownActressName;
    private String actressId;
    /**
     * Optional direct URL of the performer's avatar, scraped from the
     * source page (e.g. xhamster's tag JSON). The enrichment service
     * downloads this and re-hosts it on Bunny CDN so the entry no longer
     * depends on the source site.
     */
    private String actressAvatarUrl;
    private Long views;
    /** When true, the LLM enrichment step uses MiniMax instead of local Ollama. */
    private Boolean useMinimax;
    /**
     * When true, the LLM enrichment step is skipped entirely. The entry
     * is persisted with the user-supplied metadata as-is (title,
     * description, category, tags, slug from the title). Useful for
     * fast bulk runs where the user has already polished the metadata
     * and doesn't want a 2-10s LLM call per video.
     */
    private Boolean skipLlm;

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

    public String getActressAvatarUrl() { return actressAvatarUrl; }
    public void setActressAvatarUrl(String actressAvatarUrl) { this.actressAvatarUrl = actressAvatarUrl; }

    public Long getViews() { return views; }
    public void setViews(Long views) { this.views = views; }

    public Boolean getUseMinimax() { return useMinimax; }
    public void setUseMinimax(Boolean useMinimax) { this.useMinimax = useMinimax; }

    public Boolean getSkipLlm() { return skipLlm; }
    public void setSkipLlm(Boolean skipLlm) { this.skipLlm = skipLlm; }
}

