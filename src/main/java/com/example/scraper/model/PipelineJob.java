package com.example.scraper.model;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

/**
 * Tracks a single running or completed pipeline invocation. The controller
 * returns a jobId immediately (HTTP 202) and the UI polls
 * {@code /api/video/pipeline/status/{jobId}} to render the progress bar.
 *
 * <p>Fields are {@code volatile} so the polling thread sees the latest
 * values written by the worker thread; mutations are also synchronized
 * via {@link #updateProgress(int, String)} so the (progress, step) pair
 * is always consistent.
 */
public class PipelineJob {

    public enum Status { PENDING, RUNNING, DONE, FAILED }

    private final String jobId;
    private final String kind;          // "single" or "batch" — used for URL routing
    private volatile Status status;
    private volatile int progress;       // 0..100
    private volatile String step;        // human-readable status line
    private volatile PipelineOutcome result;       // populated when DONE
    private volatile String error;                  // populated when FAILED
    private volatile List<Path> previewClipPaths;   // populated when DONE
    private final long createdAt;

    public PipelineJob(String jobId, String kind) {
        this.jobId = jobId;
        this.kind = kind;
        this.status = Status.PENDING;
        this.progress = 0;
        this.step = "Pending";
        this.createdAt = System.currentTimeMillis();
    }

    public synchronized void updateProgress(int progress, String step) {
        this.progress = Math.max(0, Math.min(100, progress));
        this.step = step == null ? "" : step;
    }

    public synchronized void markRunning(String step) {
        this.status = Status.RUNNING;
        this.progress = Math.max(this.progress, 1);
        this.step = step == null ? "" : step;
    }

    public synchronized void markDone(PipelineOutcome result, List<Path> previewClipPaths) {
        this.status = Status.DONE;
        this.progress = 100;
        this.step = "Complete";
        this.result = result;
        this.previewClipPaths = previewClipPaths == null
                ? Collections.emptyList()
                : List.copyOf(previewClipPaths);
    }

    public synchronized void markFailed(String error) {
        this.status = Status.FAILED;
        this.progress = Math.max(this.progress, this.progress);
        this.step = "Failed";
        this.error = error == null ? "Unknown error" : error;
    }

    public String getJobId() { return jobId; }
    public String getKind() { return kind; }
    public Status getStatus() { return status; }
    public int getProgress() { return progress; }
    public String getStep() { return step; }
    public PipelineOutcome getResult() { return result; }
    public String getError() { return error; }
    public List<Path> getPreviewClipPaths() { return previewClipPaths; }
    public long getCreatedAt() { return createdAt; }
}
