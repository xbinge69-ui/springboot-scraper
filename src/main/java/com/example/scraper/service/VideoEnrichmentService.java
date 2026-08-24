package com.example.scraper.service;

import com.example.scraper.model.PipelineOutcome;
import com.example.scraper.model.PipelineStats;
import com.example.scraper.model.VideoCatalogEntry;
import com.example.scraper.scraper.XhamsterTagExtractor;
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
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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
        return enrich(source, metadata, useMinimax, false, null, null, null);
    }

    /**
     * Backwards-compatible overload that adds a {@code skipLlm} flag.
     * When {@code skipLlm} is true, the Topical Authority call is
     * skipped entirely and the entry is persisted with the user's
     * metadata as-is. No LLM call, no fallback warning, no Ollama
     * round-trip — saves 2-10s per video on bulk runs.
     */
    public PipelineOutcome enrich(EnrichmentSource source, EnrichmentMetadata metadata,
                                  boolean useMinimax, boolean skipLlm) throws IOException {
        return enrich(source, metadata, useMinimax, skipLlm, null, null, null);
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
        return enrich(source, metadata, useMinimax, false, job, previewsDir, null);
    }

    /**
     * Full-fat overload. When {@code skipLlm} is true, the LLM
     * Topical Authority step is skipped and the entry is persisted
     * with the user's metadata as-is. Skipping saves the 2-10s LLM
     * round-trip per video (and avoids the "LLM unavailable" warning
     * when the local model isn't pulled).
     *
     * @param bunnyZones which Bunny.net pull zones to mirror the
     *                   upload to. {@code null} → upload to every
     *                   configured zone (default batch behaviour:
     *                   primary + all mirrors). Pass an explicit
     *                   subset to restrict — see
     *                   {@link com.example.scraper.service.BunnyAssetService#uploadBytesMulti}.
     */
    public PipelineOutcome enrich(EnrichmentSource source, EnrichmentMetadata metadata,
                                  boolean useMinimax, boolean skipLlm,
                                  com.example.scraper.model.PipelineJob job,
                                  Path previewsDir,
                                  java.util.Collection<String> bunnyZones) throws IOException {
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

        // ----- Step 2: probe the source for duration + audio flag -----
        VideoMetadata probe;
        try {
            probe = runWithTimeout("probe", () -> ffmpeg.probe(inputPath), ffmpegTimeoutSeconds);
        } catch (Exception e) {
            deleteRecursively(tempDir);
            throw new IOException("ffmpeg probe failed: " + e.getMessage(), e);
        }

        // ----- Step 3: generate the 5 watermark-baked mini clips FIRST -----
        // Reasoning: the mini clips are fast (5 × 25s of already-decoded
        // source segments, not a full re-encode) and the user sees
        // them on the result panel right after the pipeline finishes.
        // Doing them first means a) the heavy 720p re-encode isn't
        // blocking the "first impression" deliverable, b) if the
        // re-encode fails for some reason (out of disk, GPU hang) the
        // clips are already on disk, c) clips are encoded from the
        // original source — no quality loss from compressing twice.
        if (job != null) job.updateProgress(15, "Generating 5 preview clips");
        java.util.List<Path> previewClipPaths = new java.util.ArrayList<>();
        if (previewsDir != null) {
            try {
                previewClipPaths.addAll(ffmpeg.generatePreviewClips(
                        inputPath, previewsDir, "clip", probe, probe.hasAudio()));
            } catch (Exception e) {
                warnings.add("Preview clip generation failed: " + e.getMessage());
            }
        }

        // ----- Step 4: heavy 720p re-encode + 5s preview + thumbnail -----
        // Skip the source's intro/bumper before encoding the COMPRESSED
        // output only — xhamster (and its country variants) prepend a
        // 6-second logo bumper to every video. The 5s preview, the
        // thumbnail, and the 5 mini clips still come from the
        // un-trimmed source so we keep the intro visible in previews
        // and don't lose any content.
        double skipHeadSeconds = computeSkipHeadSeconds(source);
        Derivatives derivatives;
        try {
            if (job != null) job.updateProgress(40, "Compressing video (watermark)"
                    + (skipHeadSeconds > 0 ? " (skipping first " + (int) skipHeadSeconds + "s)" : ""));
            Path derivedDir = tempDir.resolve("derivatives");
            derivatives = runWithTimeout("process",
                    () -> ffmpeg.process(inputPath, derivedDir, "enrich", probe, skipHeadSeconds),
                    ffmpegTimeoutSeconds * 3);  // process is 3 sub-encodes
        } catch (Exception e) {
            deleteRecursively(tempDir);
            throw new IOException("ffmpeg process failed: " + e.getMessage(), e);
        }
        if (job != null) job.updateProgress(70, "Uploading derivatives to CDN");

        // ----- Step 3c: snapshot file sizes for the end-of-run summary -----
        // We read the sizes here (before upload) because the local files
        // are deleted right after the Bunny uploads, and the local file
        // size is what the user actually wants to see — not the
        // re-compressed-on-CDN byte count. The previewsDir files live
        // longer (they're for the UI), so their totals are computed
        // there as well.
        PipelineStats stats = new PipelineStats();
        stats.setInputDurationSeconds(probe.durationSeconds());
        stats.setInputBytes(safeSize(inputPath));
        stats.setCompressedBytes(safeSize(derivatives.compressedPath()));
        stats.setPreviewBytes(safeSize(derivatives.previewPath()));
        stats.setThumbnailBytes(safeSize(derivatives.thumbnailPath()));
        stats.setVideoEncoder(ffmpeg.currentEncoderName());
        stats.setHardwareAccelerated(ffmpeg.isHardwareAccelerated());
        stats.setPreviewClipCount(previewClipPaths.size());
        if (!previewClipPaths.isEmpty()) {
            long total = 0L;
            int counted = 0;
            for (Path p : previewClipPaths) {
                long s = safeSize(p);
                if (s > 0) { total += s; counted++; }
            }
            stats.setPreviewClipsTotalBytes(counted > 0 ? total : -1L);
        }

        // ----- Steps 4-5: upload all 3 derivatives to every selected Bunny zone -----
        String slugHint = Slugify.slugify(metadata.titleOrFallback());
        String uuid = BunnyAssetService.newRequestUuid();
        String folder = bunny.getFolder();
        // `bunnyZones` may be null → upload to all configured zones.
        // We snapshot it now so the portrait fetch below uses the
        // same selection (avoids the user un-checking a box mid-run).
        java.util.Collection<String> selectedZones = bunnyZones;
        // Map zone-key → URL for each asset type. LinkedHashMap so the
        // ordering (primary first, mirrors next) survives into JSON.
        java.util.LinkedHashMap<String, String> thumbnailUrls = new java.util.LinkedHashMap<>();
        java.util.LinkedHashMap<String, String> previewUrls    = new java.util.LinkedHashMap<>();
        java.util.LinkedHashMap<String, String> compressedUrls = new java.util.LinkedHashMap<>();
        try (EnrichmentTempFiles ignored = new EnrichmentTempFiles(
                inputPath, derivatives.thumbnailPath(), derivatives.previewPath(), derivatives.compressedPath())) {

            thumbnailUrls.putAll(bunny.uploadBytesMulti(derivatives.thumbnailPath(),
                    folder + "/" + slugHint + "-" + uuid + ".thumbnail.jpg",  "image/jpeg", selectedZones));
            previewUrls.putAll(bunny.uploadBytesMulti(derivatives.previewPath(),
                    folder + "/" + slugHint + "-" + uuid + ".preview.mp4",    "video/mp4", selectedZones));
            compressedUrls.putAll(bunny.uploadBytesMulti(derivatives.compressedPath(),
                    folder + "/" + slugHint + "-" + uuid + ".compressed.mp4", "video/mp4", selectedZones));
        } catch (Exception e) {
            deleteRecursively(tempDir);
            throw new IOException("bunny upload failed: " + e.getMessage(), e);
        }

        // Fail-soft reporting: per-zone upload failures are already
        // logged by BunnyAssetService at WARN level (so ops sees them
        // in the run console). Surface them to the user too — the
        // returned map only contains successful zones, so any zone
        // that was selected but is missing here failed (auth / 5xx /
        // network). Compute the diff against the configured set so we
        // can attach a per-zone warning. Bail out hard only when
        // EVERY selected zone failed — a fully-empty result would mean
        // the entry has no CDN copy at all.
        java.util.Set<String> missingCompressedZones = new java.util.LinkedHashSet<>();
        if (selectedZones != null) {
            missingCompressedZones.addAll(selectedZones);
        } else if (bunny.getZoneKeys() != null) {
            missingCompressedZones.addAll(bunny.getZoneKeys());
        }
        missingCompressedZones.removeAll(compressedUrls.keySet());
        for (String missing : missingCompressedZones) {
            warnings.add("Bunny upload to zone '" + missing + "' failed (other zones were used — see server log for details)");
        }
        if (compressedUrls.isEmpty()) {
            deleteRecursively(tempDir);
            throw new IOException("bunny upload failed: no configured Bunny zone accepted the upload "
                    + "(see server log for per-zone HTTP errors)");
        }

        // Canonical URLs: primary zone for embed/preview/thumbnail,
        // second zone for the backup. When only one zone was uploaded
        // (or only one is configured), backupEmbedUrl mirrors embedUrl
        // — keeps the existing single-CDN contract intact.
        String thumbnailUrl  = BunnyAssetService.firstUrl(thumbnailUrls);
        String previewUrl    = BunnyAssetService.firstUrl(previewUrls);
        String compressedUrl = BunnyAssetService.firstUrl(compressedUrls);
        String backupEmbedUrl = compressedUrls.size() >= 2
                ? new ArrayList<>(compressedUrls.values()).get(1)
                : compressedUrl;

        // ----- Step 6 (optional): fetch the actress portrait from the
        // source page and re-host it on Bunny. We do this AFTER the
        // heavy 3 uploads (thumbnail + compressed + preview) so a
        // network blip on the avatar URL doesn't fail the run — the
        // warning list captures the failure and the entry is still
        // persisted without a portrait. The portrait is downloaded
        // from the scraped URL (xhamster's tag JSON) and never
        // re-resolved, so the entry is no longer tied to the source
        // site.
        String portraitUrl = null;
        if (metadata.actressAvatarUrl() != null && !metadata.actressAvatarUrl().isBlank()) {
            try {
                portraitUrl = fetchAndUploadActressPortrait(
                        metadata.actressAvatarUrl(), tempDir, slugHint, uuid, folder, selectedZones);
            } catch (Exception e) {
                warnings.add("Actress portrait fetch/upload failed: " + e.getMessage());
            }
        }

        if (job != null) job.updateProgress(80, "Running LLM enrichment");

        // ----- Step 7: LLM Topical Authority pass (skipped when skipLlm=true) -----
        TopicalAuthorityResult llm;
        if (skipLlm) {
            // Use the input metadata verbatim. No LLM call, no warning.
            // Slug is derived from the title by the fallback helper.
            llm = TopicalAuthorityPrompt.parse(null, metadata);
            log.info("LLM enrichment skipped (skipLlm=true) — using user metadata as-is");
        } else {
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
        entry.setActressPortraitKey(portraitUrl);
        entry.setViews(llm.views());
        entry.setStats(stats);
        // List of Bunny zone keys this entry was mirrored to — primary
        // first, then any configured mirrors. Downstream consumers
        // (project-b / the public site) read this to decide which CDN
        // to play from. The list matches the actual uploads that ran
        // (i.e. honours the bunnyZones override when one was supplied).
        entry.setCdnZoneKeys(new ArrayList<>(compressedUrls.keySet()));

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
        PipelineOutcome outcome = new PipelineOutcome(saved, warnings);
        outcome.setStats(stats);
        return outcome;
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

    /**
     * Best-effort file size lookup. Returns -1 when the file is missing
     * or unreadable so the caller can distinguish "not measured" from
     * "zero bytes" (both are possible in the wild).
     */
    /**
     * Compute the number of seconds to skip from the start of the
     * source before encoding the COMPRESSED output. Returns 0 for
     * sources that don't need a head trim.
     *
     * <p>Today only xhamster is handled — the platform appends a
     * 6-second logo bumper to every video that's not actual content.
     * We drop those 6 seconds from the compressed re-encode so the
     * viewer never sees the ad/intro. The 5s preview, thumbnail, and
     * 5 mini clips are still sampled from the un-trimmed source so
     * the user can still scrub the intro on the pipeline page.
     *
     * <p>The page URL (not the video file URL) is the one to inspect:
     * video files live on xhamster's CDN under a generic hostname and
     * don't carry the "xhamster" string, but the page URL is always
     * {@code xhamster.com} or one of its country variants
     * ({@code xhamster.desi}, {@code xhamster2.com}, …).
     */
    static double computeSkipHeadSeconds(EnrichmentSource source) {
        if (!(source instanceof EnrichmentSource.RemoteUrl ru)) return 0.0;
        String pageUrl = ru.referer();
        if (pageUrl == null || pageUrl.isBlank()) return 0.0;
        if (XhamsterTagExtractor.isXhamster(pageUrl)) {
            return 6.0;
        }
        return 0.0;
    }

    private static long safeSize(Path p) {
        if (p == null) return -1L;
        try {
            return Files.size(p);
        } catch (IOException e) {
            return -1L;
        }
    }

    private static void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            paths.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
        } catch (IOException ignored) {
            // Best-effort cleanup.
        }
    }

    // ---- Actress portrait fetch (xhamster avatar -> Bunny) ----

    /** Connect timeout for the avatar HTTP fetch. */
    private static final int PORTRAIT_CONNECT_TIMEOUT_MS = 10_000;
    /** Read timeout for the avatar HTTP fetch. */
    private static final int PORTRAIT_READ_TIMEOUT_MS = 15_000;
    /** Hard cap on the avatar download size. 12 MB is well above any real
     * performer photo; anything bigger is a corrupt/misnamed resource. */
    private static final long PORTRAIT_MAX_BYTES = 12L * 1024 * 1024;
    /** Stream copy buffer. */
    private static final int PORTRAIT_BUFFER_BYTES = 32 * 1024;

    /**
     * Download the actress avatar from the source page (e.g. xhamster's
     * tag JSON) into a temp file under {@code tempDir}, upload it to
     * Bunny under {@code <slug>-<uuid>.portrait.<ext>}, then delete the
     * temp file. Returns the public CDN URL of the uploaded portrait.
     *
     * <p>The download is best-effort: any failure throws an
     * {@link IOException} which the caller converts into a warning
     * (the entry is still persisted, just without a portrait). The
     * size cap protects us from a runaway download; the connect/read
     * timeouts match the existing {@link HttpVideoDownloader} style.
     *
     * <p>File extension is inferred from the response's
     * {@code Content-Type} header (jpg/png/webp). When unknown, the
     * extension is omitted from the Bunny key — Bunny stores the bytes
     * either way and serves them with the content-type we set on PUT.
     */
    private String fetchAndUploadActressPortrait(String remoteUrl,
                                                 Path tempDir,
                                                 String slugHint,
                                                 String uuid,
                                                 String folder,
                                                 java.util.Collection<String> bunnyZones) throws IOException {
        URI uri;
        try {
            uri = URI.create(remoteUrl);
        } catch (IllegalArgumentException e) {
            throw new IOException("invalid avatar URL: " + remoteUrl, e);
        }
        String scheme = uri.getScheme();
        if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
            throw new IOException("avatar URL must be http(s): " + remoteUrl);
        }

        HttpURLConnection conn = (HttpURLConnection) new URL(remoteUrl).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(PORTRAIT_CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(PORTRAIT_READ_TIMEOUT_MS);
        // Send a browser UA + Referer so xhamster doesn't 403 the image.
        conn.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                        + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36");
        conn.setRequestProperty("Accept", "image/avif,image/webp,image/png,image/jpeg,image/*;q=0.8,*/*;q=0.5");
        conn.setInstanceFollowRedirects(true);
        conn.connect();

        int status = conn.getResponseCode();
        if (status < 200 || status >= 300) {
            conn.disconnect();
            throw new IOException("avatar fetch HTTP " + status + " for " + remoteUrl);
        }

        String contentType = conn.getContentType();
        String ext = extensionForContentType(contentType);
        String objectKey = folder + "/" + slugHint + "-" + uuid
                + (ext == null ? ".portrait" : ".portrait" + ext);
        String bunnyContentType = (contentType != null && !contentType.isBlank())
                ? contentType
                : "image/jpeg";

        Path tempFile = Files.createTempFile(tempDir, "portrait-", ext == null ? ".img" : ext);
        try (InputStream in = conn.getInputStream()) {
            long copied = copyBounded(in, tempFile, PORTRAIT_MAX_BYTES);
            if (copied == 0) {
                throw new IOException("avatar response was empty: " + remoteUrl);
            }
        } finally {
            conn.disconnect();
        }
        try {
            // Replicate the portrait to every selected Bunny zone so
            // the thumbnail/preview/compressed URLs and the portrait
            // URL are always co-located. Falls back to the primary
            // zone's URL when bunnyZones is null.
            Map<String, String> urls = bunny.uploadBytesMulti(
                    tempFile, objectKey, bunnyContentType, bunnyZones);
            return BunnyAssetService.firstUrl(urls);
        } finally {
            try { Files.deleteIfExists(tempFile); } catch (IOException ignored) {}
        }
    }

    /**
     * Copy up to {@code maxBytes} from {@code in} into {@code target}.
     * Returns the number of bytes copied. Throws {@link IOException} if
     * the source has more than {@code maxBytes} to give.
     */
    private static long copyBounded(InputStream in, Path target, long maxBytes) throws IOException {
        long total = 0;
        byte[] buf = new byte[PORTRAIT_BUFFER_BYTES];
        try (var out = Files.newOutputStream(target, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE, StandardOpenOption.CREATE)) {
            int n;
            while ((n = in.read(buf)) != -1) {
                total += n;
                if (total > maxBytes) {
                    throw new IOException("avatar exceeds " + maxBytes + " bytes");
                }
                out.write(buf, 0, n);
            }
        }
        return total;
    }

    /**
     * Map an HTTP {@code Content-Type} to a Bunny object-key extension.
     * Returns null for unknown types so the caller can omit the
     * extension from the key.
     */
    private static String extensionForContentType(String contentType) {
        if (contentType == null) return null;
        String ct = contentType.toLowerCase(java.util.Locale.ROOT);
        // Strip parameters, e.g. "image/jpeg; charset=utf-8"
        int semi = ct.indexOf(';');
        if (semi >= 0) ct = ct.substring(0, semi).trim();
        return switch (ct) {
            case "image/jpeg", "image/jpg" -> ".jpg";
            case "image/png"              -> ".png";
            case "image/webp"             -> ".webp";
            case "image/gif"              -> ".gif";
            case "image/avif"             -> ".avif";
            default                       -> null;
        };
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
