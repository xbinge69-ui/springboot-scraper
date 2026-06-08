package com.example.scraper.model;

import java.util.ArrayList;
import java.util.List;

public class PipelineOutcome {

    private VideoCatalogEntry entry;
    private List<String> warnings = new ArrayList<>();
    /**
     * Input/output byte counts + the encoder that was actually used.
     * Populated by the orchestrator; surfaced to the UI so the user
     * sees "X MB → Y MB (Z%) via h264_nvenc" at the end of a run.
     */
    private PipelineStats stats = new PipelineStats();

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

    public PipelineStats getStats() { return stats; }
    public void setStats(PipelineStats stats) { this.stats = stats == null ? new PipelineStats() : stats; }
}
