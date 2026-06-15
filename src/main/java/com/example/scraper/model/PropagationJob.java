package com.example.scraper.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Tracks a single batch-to-project-b propagation run. The controller
 * returns a jobId immediately and the UI polls
 * {@code /api/propagation/status/{jobId}} to render the progress bar.
 *
 * <p>Mirrors the shape of {@link PipelineJob} (volatile fields +
 * synchronized markers) so the polling thread sees a consistent snapshot
 * of the worker's progress.
 *
 * <p>State machine:
 * <pre>
 *   PENDING → RUNNING → DONE                (all chunks succeeded)
 *                       → PARTIAL           (at least one chunk failed, at least one succeeded)
 *                       → FAILED            (no chunks produced usable output, or append step failed)
 * </pre>
 *
 * <p>The list of appended ids is exposed for the UI / debug endpoints —
 * the canonical source of truth lives in
 * {@code project-b/src/data/videos.json} on disk.
 */
public class PropagationJob {

    public enum Status { PENDING, RUNNING, DONE, PARTIAL, FAILED }

    private final String jobId;
    private final String batchId;            // caller-supplied correlation id (optional)
    private final long createdAt;
    private volatile Status status;
    private volatile int totalChunks;
    private volatile int completedChunks;
    private volatile int failedChunks;
    private volatile int appendedEntries;
    private volatile String currentChunk;    // human-readable, e.g. "2/3"
    private volatile String currentStep;     // human-readable status line
    private volatile String error;           // last error, populated on FAILED or PARTIAL
    private final List<String> appendedIds =
            Collections.synchronizedList(new ArrayList<>());
    private final List<String> failedChunkErrors =
            Collections.synchronizedList(new ArrayList<>());

    public PropagationJob(String jobId, String batchId, int totalChunks) {
        this.jobId = jobId;
        this.batchId = batchId == null ? "" : batchId;
        this.totalChunks = Math.max(0, totalChunks);
        this.status = Status.PENDING;
        this.currentChunk = "0/" + this.totalChunks;
        this.currentStep = "Pending";
        this.createdAt = System.currentTimeMillis();
    }

    /** Flip to RUNNING and seed the step line. Idempotent. */
    public synchronized void markRunning(String step) {
        if (this.status == Status.PENDING) {
            this.status = Status.RUNNING;
        }
        this.currentStep = step == null ? "" : step;
    }

    /** A chunk finished. Bumps the counter, refreshes the "N/M" label. */
    public synchronized void markChunkDone(int appendedInThisChunk, List<String> idsInThisChunk) {
        this.completedChunks++;
        this.appendedEntries += Math.max(0, appendedInThisChunk);
        if (idsInThisChunk != null) {
            for (String id : idsInThisChunk) {
                if (id != null && !id.isBlank()) {
                    this.appendedIds.add(id);
                }
            }
        }
        this.currentChunk = this.completedChunks + "/" + this.totalChunks;
        this.currentStep = "Propagated chunk " + this.currentChunk;
    }

    /** A chunk failed. Records the error but keeps the job running. */
    public synchronized void markChunkFailed(String chunkError) {
        this.failedChunks++;
        String trimmed = chunkError == null ? "unknown error" : chunkError;
        this.failedChunkErrors.add("chunk " + this.completedChunks + "/" + this.totalChunks + ": " + trimmed);
        this.currentChunk = (this.completedChunks + this.failedChunks) + "/" + this.totalChunks;
        this.currentStep = "Chunk failed (" + this.failedChunks + " so far)";
        this.error = trimmed;
    }

    /**
     * Terminal state. Picks DONE / PARTIAL / FAILED based on counters.
     * Idempotent: subsequent calls don't downgrade an already-terminal job.
     */
    public synchronized void markTerminal() {
        if (this.status == Status.DONE || this.status == Status.PARTIAL || this.status == Status.FAILED) {
            return;
        }
        if (this.completedChunks == 0) {
            this.status = Status.FAILED;
            this.currentStep = "Failed: no chunks produced entries";
        } else if (this.failedChunks == 0) {
            this.status = Status.DONE;
            this.currentStep = "Done. " + this.appendedEntries + " entries appended.";
        } else {
            this.status = Status.PARTIAL;
            this.currentStep = "Partial: " + this.appendedEntries + " entries from "
                    + this.completedChunks + "/" + this.totalChunks + " chunks.";
        }
    }

    /** Override the terminal state with a hard failure (e.g. atomic-write step failed). */
    public synchronized void markFailed(String error) {
        this.status = Status.FAILED;
        this.error = error == null ? "Unknown error" : error;
        this.currentStep = "Failed: " + this.error;
    }

    public String getJobId() { return jobId; }
    public String getBatchId() { return batchId; }
    public Status getStatus() { return status; }
    public int getTotalChunks() { return totalChunks; }
    public int getCompletedChunks() { return completedChunks; }
    public int getFailedChunks() { return failedChunks; }
    public int getAppendedEntries() { return appendedEntries; }
    public String getCurrentChunk() { return currentChunk; }
    public String getCurrentStep() { return currentStep; }
    public String getError() { return error; }
    public List<String> getAppendedIds() {
        synchronized (appendedIds) {
            return List.copyOf(appendedIds);
        }
    }
    public List<String> getFailedChunkErrors() {
        synchronized (failedChunkErrors) {
            return List.copyOf(failedChunkErrors);
        }
    }
    public long getCreatedAt() { return createdAt; }
}
