package com.example.scraper.service;

import com.example.scraper.model.PipelineJob;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * In-memory registry of in-flight and recently-completed pipeline jobs.
 *
 * <p>Backed by a {@link ConcurrentHashMap} for thread-safe get/put. A
 * small fixed-size thread pool runs the work asynchronously so the
 * controller can return 202 + jobId immediately. Jobs are NOT evicted
 * automatically — the UI is expected to call {@link #cleanup(String)}
 * (which also deletes the preview-clip temp dir) when the user is
 * done with the result.
 */
@Service
public class PipelineJobService {

    private static final Logger log = LoggerFactory.getLogger(PipelineJobService.class);

    /** Temp dir prefix for the per-job preview-clip directory. */
    public static final String PREVIEWS_DIR_PREFIX = "scraper-previews-";

    private final Map<String, PipelineJob> jobs = new ConcurrentHashMap<>();
    private final ExecutorService executor =
            Executors.newFixedThreadPool(2, r -> {
                Thread t = new Thread(r, "pipeline-job");
                t.setDaemon(true);
                return t;
            });

    /** Create a new PENDING job with a fresh UUID and return it. */
    public PipelineJob create(String kind) {
        String id = UUID.randomUUID().toString();
        PipelineJob job = new PipelineJob(id, kind);
        jobs.put(id, job);
        return job;
    }

    /** Look up a job by id. */
    public Optional<PipelineJob> get(String jobId) {
        return Optional.ofNullable(jobs.get(jobId));
    }

    /** Submit a unit of work to run on the executor. */
    public void submit(Runnable work) {
        executor.submit(work);
    }

    /** Returns the temp dir used for this job's preview clips, creating it. */
    public Path previewsDirFor(String jobId) throws IOException {
        Path dir = Paths.get(System.getProperty("java.io.tmpdir"), PREVIEWS_DIR_PREFIX + jobId);
        Files.createDirectories(dir);
        return dir;
    }

    /**
     * Remove the job from the registry and recursively delete its
     * preview-clip temp dir. No-op if the jobId is unknown (idempotent
     * so the UI's delete button can retry).
     *
     * @return true if anything was actually removed
     */
    public boolean cleanup(String jobId) {
        PipelineJob removed = jobs.remove(jobId);
        Path dir = Paths.get(System.getProperty("java.io.tmpdir"), PREVIEWS_DIR_PREFIX + jobId);
        boolean deleted = false;
        if (Files.exists(dir)) {
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder())
                        .forEach(p -> {
                            try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                        });
                deleted = true;
            } catch (IOException e) {
                log.warn("Failed to delete previews dir {}: {}", dir, e.getMessage());
            }
        }
        return removed != null || deleted;
    }

    /** List every job currently tracked. Used by tests / future admin endpoints. */
    public List<PipelineJob> listAll() {
        return List.copyOf(jobs.values());
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }
}
