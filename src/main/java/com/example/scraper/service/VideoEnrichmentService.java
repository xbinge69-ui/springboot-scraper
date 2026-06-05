package com.example.scraper.service;

import com.example.scraper.model.PipelineOutcome;
import com.example.scraper.model.VideoCatalogEntry;
import com.example.scraper.service.llm.LlmProvider;
import com.example.scraper.util.HttpVideoDownloader;
import com.example.scraper.util.Slugify;
import com.example.scraper.video.Derivatives;
import com.example.scraper.video.EnrichmentMetadata;
import com.example.scraper.video.EnrichmentSource;
import com.example.scraper.video.EnrichmentTempFiles;
import com.example.scraper.video.FfmpegDerivativeService;
import com.example.scraper.video.TopicalAuthorityPrompt;
import com.example.scraper.video.TopicalAuthorityResult;
import com.example.scraper.video.VideoMetadata;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * End-to-end orchestrator for the {@code POST /api/video/enrich}
 * endpoint. Chains:
 *
 * <ol>
 *   <li>resolve the source (local file or remote URL → temp file),</li>
 *   <li>{@link FfmpegDerivativeService#probe probe} for {@code durationSeconds},</li>
 *   <li>{@link FfmpegDerivativeService#process process} for compressed / preview / thumbnail,</li>
 *   <li>{@link BunnyAssetService#uploadBytes(Path, String, String) upload all 3 to bunny} (streaming, no byte[]),</li>
 *   <li>{@link OllamaService} for the Topical Authority prompt + JSON parse,</li>
 *   <li>{@link VideoCatalogService#append persist} to {@code videos.json},</li>
 *   <li>cleanup the 4 local files via try-with-resources.</li>
 * </ol>
 *
 * <p>Failure-mode policy: local file cleanup is unconditional. Bunny
 * orphans on partial upload (R15) are documented and not rolled back.
 * Ollama failure falls back per-field to the input metadata, and the
 * {@link PipelineOutcome#warnings()} list is populated.
 */
@Service
public class VideoEnrichmentService {

    private static final Logger log = LoggerFactory.getLogger(VideoEnrichmentService.class);

    private final FfmpegDerivativeService ffmpeg;
    private final BunnyAssetService bunny;
    private final OllamaService ollama;
    private final com.example.scraper.service.llm.MinimaxChatProvider minimax;
    private final VideoCatalogService catalog;
    private final long ffmpegTimeoutSeconds;
    private final long maxDownloadBytes;
    private final int downloadConnectSeconds;
    private final int downloadReadSeconds;
    private final int sweeperStaleMinutes;
    private final String ollamaFormat;
    private final double ollamaTemperature;
    private final Semaphore ffmpegPermits = new Semaphore(Math.max(1, Runtime.getRuntime().availableProcessors() / 2));

    public VideoEnrichmentService(FfmpegDerivativeService ffmpeg,
                                  BunnyAssetService bunny,
                                  OllamaService ollama,
                                  com.example.scraper.service.llm.MinimaxChatProvider minimax,
                                  VideoCatalogService catalog,
                                  @Value("${app.enrichment.ffmpeg-timeout-seconds:300}") long ffmpegTimeoutSeconds,
                                  @Value("${app.enrichment.max-download-bytes:2147483648}") long maxDownloadBytes,
                                  @Value("${app.enrichment.download-connect-timeout-seconds:30}") int downloadConnectSeconds,
                                  @Value("${app.enrichment.download-read-timeout-seconds:300}") int downloadReadSeconds,
                                  @Value("${app.enrichment.sweeper-stale-minutes:60}") int sweeperStaleMinutes,
                                  @Value("${app.enrichment.ollama-format:json}") String ollamaFormat,
                                  @Value("${app.enrichment.ollama-temperature:0.2}") double ollamaTemperature) {
        this.ffmpeg = ffmpeg;
        this.bunny = bunny;
        this.ollama = ollama;
        this.minimax = minimax;
        this.catalog = catalog;
        this.ffmpegTimeoutSeconds = ffmpegTimeoutSeconds;
        this.maxDownloadBytes = maxDownloadBytes;
        this.downloadConnectSeconds = downloadConnectSeconds;
        this.downloadReadSeconds = downloadReadSeconds;
        this.sweeperStaleMinutes = sweeperStaleMinutes;
        this.ollamaFormat = ollamaFormat;
        this.ollamaTemperature = ollamaTemperature;
    }

    /** Sweep stale temp dirs from prior crashed JVMs. */
    @PostConstruct
    void sweepStaleTempDirs() {
        Path tmp = Path.of(System.getProperty("java.io.tmpdir"));
        long cutoff = System.currentTimeMillis() - sweeperStaleMinutes * 60_000L;
        int removed = 0;
        // Both "scraper-enrich-*" (per-run temp) and "scraper-previews-*"
        // (per-job preview clip dir, which survives past the request).
        String[] globs = {"scraper-enrich-*", "scraper-previews-*"};
        for (String glob : globs) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(tmp, glob)) {
                for (Path p : ds) {
                    try {
                        if (Files.getLastModifiedTime(p).toMillis() < cutoff) {
                            deleteRecursively(p);
                            removed++;
                        }
                    } catch (IOException ignored) {
                        // Best-effort.
                    }
                }
            } catch (IOException e) {
                log.warn("Temp-dir sweeper failed for glob {}: {}", glob, e.getMessage());
            }
        }
        if (removed > 0) {
            log.info("Temp-dir sweeper removed {} stale dirs", removed);
        }
    }

    /**
     * Top-level orchestration. Returns a {@link PipelineOutcome} so the
     * existing JSON response shape (entry + warnings) is preserved.
     *
     * @param source     the input (local file or remote URL)
     * @param metadata   the user-supplied metadata
     * @param useMinimax when true, route the Topical Authority call to
     *                   MiniMax's hosted API instead of local Ollama.
     */
    public PipelineOutcome enrich(EnrichmentSource source, EnrichmentMetadata metadata, boolean useMinimax) throws IOException {
        return enrich(source, metadata, useMinimax, null, null);
    }

    /**
     * Progress-aware variant. When {@code job} is non-null, this method
     * pushes status updates to it as it works. When {@code previewsDir}
     * is non-null, the 5 watermark-baked preview clips are written there
     * (and survive past the return) instead of being deleted with the
     * enrich temp dir. Use {@code previewsDir == null} to keep the
     * legacy "no clips generated" behavior (e.g. for the direct-upload
     * endpoint that doesn't need a UI).
     */
    public PipelineOutcome enrich(EnrichmentSource source, EnrichmentMetadata metadata,
                                  boolean useMinimax,
                                  com.example.scraper.model.PipelineJob job,
                                  Path previewsDir) throws IOException {
        if (job != null) job.markRunning("Resolving source");
        List<String> warnings = new ArrayList<>();
        Path tempDir = Files.createTempDirectory("scraper-enrich-" + UUID.randomUUID());

        // ----- Step 1: resolve source to a local file -----
        Path inputPath;
        try {
            if (source instanceof EnrichmentSource.LocalFile lf) {
                inputPath = lf.path();
            } else if (source instanceof EnrichmentSource.RemoteUrl ru) {
                Path dest = tempDir.resolve("input.bin");
                HttpVideoDownloader.download(ru.url(), dest, maxDownloadBytes,
                        downloadConnectSeconds * 1000, downloadReadSeconds * 1000,
                        ru.referer());
                inputPath = dest;
            } else {
                throw new IllegalArgumentException("Unknown EnrichmentSource: " + source);
            }
        } catch (IOException | RuntimeException e) {
            deleteRecursively(tempDir);
            throw e;
        }
        if (job != null) job.updateProgress(10, "Probing source");

        // ----- Steps 2 + 3: probe + process via ffmpeg, with timeout + semaphore -----
        VideoMetadata probe;
        Derivatives derivatives;
        try {
            probe = runWithTimeout("probe", () -> ffmpeg.probe(inputPath), ffmpegTimeoutSeconds);
            if (job != null) job.updateProgress(15, "Compressing video (watermark)");
            Path derivedDir = tempDir.resolve("derivatives");
            derivatives = runWithTimeout("process",
                    () -> ffmpeg.process(inputPath, derivedDir, "enrich"),
                    ffmpegTimeoutSeconds * 3);  // process is 3 sub-encodes
        } catch (Exception e) {
            deleteRecursively(tempDir);
            throw new IOException("ffmpeg stage failed: " + e.getMessage(), e);
        }
        if (job != null) job.updateProgress(50, "Generating 5 preview clips with watermark");

        // ----- Step 3b: generate 5 watermark-baked preview clips (per-job, if requested) -----
        java.util.List<Path> previewClipPaths = new java.util.ArrayList<>();
        if (previewsDir != null) {
            try {
                previewClipPaths.addAll(ffmpeg.generatePreviewClips(
                        inputPath, previewsDir, "clip", probe, probe.hasAudio()));
            } catch (Exception e) {
                warnings.add("Preview clip generation failed: " + e.getMessage());
            }
        }
        if (job != null) job.updateProgress(70, "Uploading derivatives to CDN");

        // ----- Steps 4-5: upload all 3 derivatives to Bunny (the only CDN) -----
        String slugHint = Slugify.slugify(metadata.titleOrFallback());
        String uuid = BunnyAssetService.newRequestUuid();
        String thumbnailUrl, previewUrl, compressedUrl;
        try (EnrichmentTempFiles ignored = new EnrichmentTempFiles(
                inputPath, derivatives.thumbnailPath(), derivatives.previewPath(), derivatives.compressedPath())) {

            String folder = bunny.getFolder();
            thumbnailUrl  = bunny.uploadBytes(derivatives.thumbnailPath(),
                    folder + "/" + slugHint + "-" + uuid + ".thumbnail.jpg",  "image/jpeg");
            previewUrl    = bunny.uploadBytes(derivatives.previewPath(),
                    folder + "/" + slugHint + "-" + uuid + ".preview.mp4",    "video/mp4");
            compressedUrl = bunny.uploadBytes(derivatives.compressedPath(),
                    folder + "/" + slugHint + "-" + uuid + ".compressed.mp4", "video/mp4");
        } catch (Exception e) {
            deleteRecursively(tempDir);
            throw new IOException("bunny upload failed: " + e.getMessage(), e);
        }
        if (job != null) job.updateProgress(80, "Running LLM enrichment");

        // No second CDN — backupEmbedUrl mirrors embedUrl (the compressed
        // Bunny URL) so the field stays populated for any downstream consumer
        // that reads it. The field is still @JsonInclude(NON_NULL) on the
        // model so it's trivial to remove later.
        String backupEmbedUrl = compressedUrl;

        // ----- Step 7: LLM Topical Authority pass -----
        TopicalAuthorityResult llm;
        LlmProvider provider = (useMinimax && minimax != null && minimax.isAvailable())
                ? minimax
                : ollama;
        try {
            String summary = catalog.summarize();
            String prompt = TopicalAuthorityPrompt.buildPrompt(metadata, summary);
            Map<String, Object> options = Map.of(
                    "format", ollamaFormat,
                    "temperature", ollamaTemperature
            );
            String raw = provider.generate(prompt, options);
            llm = TopicalAuthorityPrompt.parse(raw, metadata);
        } catch (Exception e) {
            warnings.add("LLM (" + provider.name() + ") unavailable; fallback metadata used: " + e.getMessage());
            llm = TopicalAuthorityPrompt.parse(null, metadata);
        }
        if (job != null) job.updateProgress(92, "Saving to catalog");

        // ----- Steps 8-9: build & persist entry -----
        VideoCatalogEntry entry = new VideoCatalogEntry();
        entry.setSlug(Slugify.slugify(llm.slug()));
        entry.setTitle(llm.title());
        entry.setDescription(llm.description());
        entry.setDurationSeconds((int) Math.round(probe.durationSeconds()));
        entry.setThumbnailKey(thumbnailUrl);
        entry.setPreviewUrl(previewUrl);
        entry.setEmbedUrl(compressedUrl);
        entry.setBackupEmbedUrl(backupEmbedUrl);
        entry.setTags(llm.tags());
        entry.setCategory(llm.category());
        entry.setPublishedAt(Instant.now().toString());
        entry.setActressId(metadata.actressId());
        entry.setUnknownActressName(metadata.unknownActressNameOrFallback());
        entry.setViews(llm.views());

        VideoCatalogEntry saved;
        try {
            saved = catalog.append(entry);
        } catch (Exception e) {
            deleteRecursively(tempDir);
            throw new IOException("catalog append failed: " + e.getMessage(), e);
        }

        deleteRecursively(tempDir);
        // Stash the clip paths in the outcome's job so the controller can
        // return them; we keep them on the PipelineJob itself (set by the
        // controller after this method returns) — see ScraperController.
        return new PipelineOutcome(saved, warnings);
    }

    // ---- helpers ----

    /** Run a callable with the ffmpeg concurrency cap and a hard timeout. */
    private <T> T runWithTimeout(String label, FfmpegCall<T> call, long timeoutSeconds)
            throws Exception {
        ffmpegPermits.acquire();
        ExecutorService oneShot = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "enrich-ffmpeg-" + label);
            t.setDaemon(true);
            return t;
        });
        Future<T> future = oneShot.submit(call::call);
        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException te) {
            future.cancel(true);
            throw new IOException("ffmpeg " + label + " timed out after " + timeoutSeconds + "s");
        } finally {
            oneShot.shutdownNow();
            ffmpegPermits.release();
        }
    }

    @FunctionalInterface
    private interface FfmpegCall<T> { T call() throws Exception; }

    private static void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            paths.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
        } catch (IOException ignored) {
            // Best-effort cleanup.
        }
    }

    // ---- Convenience used by the controller ----

    /** Parse a CSV tags string into a list. Helper for the controller layer. */
    public static List<String> parseTagsCsv(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        return Arrays.stream(csv.split("[,;]"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .collect(Collectors.toList());
    }
}
