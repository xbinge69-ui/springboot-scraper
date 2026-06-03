package com.example.scraper.model;

import java.util.ArrayList;
import java.util.List;

public class PipelineOutcome {

    private VideoCatalogEntry entry;
    private List<String> warnings = new ArrayList<>();

    public PipelineOutcome() {
    }

    public PipelineOutcome(VideoCatalogEntry entry, List<String> warnings) {
        this.entry = entry;
        this.warnings = warnings;
    }

    public VideoCatalogEntry getEntry() {
        return entry;
    }

    public void setEntry(VideoCatalogEntry entry) {
        this.entry = entry;
    }

    public List<String> getWarnings() {
        return warnings;
    }

    public void setWarnings(List<String> warnings) {
        this.warnings = warnings;
    }
}
