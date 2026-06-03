package com.example.scraper.model;

public class ScrapedItem {

    private final String title;
    private final String url;
    private final String description;

    public ScrapedItem(String title, String url, String description) {
        this.title = title;
        this.url = url;
        this.description = description;
    }

    public String title() { return title; }
    public String url() { return url; }
    public String description() { return description; }
}
