package com.example.scraper.service;

import com.example.scraper.model.PipelineOutcome;
import com.example.scraper.model.VideoCatalogEntry;
import com.example.scraper.video.EnrichmentMetadata;
import com.example.scraper.video.EnrichmentSource;
import com.example.scraper.video.FfmpegDerivativeService;
import com.example.scraper.video.FfmpegProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Integration test for {@link VideoEnrichmentService}. Real ffmpeg,
 * real catalog (pointed at a temp file), mocked Ollama and Bunny.
 * Skips when ffmpeg is not on PATH.
 */
class VideoEnrichmentServiceTest {

    private static Path fixturesDir;

    @TempDir
    Path tempDir;

    @BeforeAll
    static void setUp() throws Exception {
        assumeTrue(ffmpegOnPath(), "ffmpeg not on PATH - skipping enrichment integration test");
        fixturesDir = Path.of(System.getProperty("user.dir"), "target", "test-fixtures");
        Files.createDirectories(fixturesDir);
    }

    private static boolean ffmpegOnPath() {
        try {
            return new ProcessBuilder("ffmpeg", "-version")
                    .redirectErrorStream(true).start().waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Generate (or reuse) the shared fixture, then copy it into the per-test
     * temp dir. The orchestrator takes ownership of the {@code LocalFile}
     * it receives and deletes it after the pipeline runs, so we hand it
     * the per-test copy rather than the shared fixture.
     */
    private Path fixtureAsInput() throws IOException, InterruptedException {
        Path fixture = fixturesDir.resolve("enrich_small.mp4");
        if (!Files.exists(fixture) || Files.size(fixture) == 0) {
            fixture = generate("enrich_small.mp4",
                    List.of("-f", "lavfi", "-i", "testsrc=size=320x240:rate=15:duration=5",
                            "-c:v", "libx264", "-pix_fmt", "yuv420p"));
        }
        Path copy = tempDir.resolve("input-" + UUID.randomUUID() + ".mp4");
        Files.copy(fixture, copy);
        return copy;
    }

    private static Path generate(String name, List<String> args) throws IOException, InterruptedException {
        Path out = fixturesDir.resolve(name);
        if (Files.exists(out) && Files.size(out) > 0) return out;
        List<String> cmd = new ArrayList<>();
        cmd.add("ffmpeg"); cmd.add("-y"); cmd.addAll(args); cmd.add(out.toString());
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        try (var in = p.getInputStream()) {
            in.transferTo(java.io.OutputStream.nullOutputStream());
        }
        int code = p.waitFor();
        if (code != 0) throw new IOException("fixture generation failed: " + name);
        return out;
    }

    @Test
    void enrich_localFile_runsFullPipelineAndCleansUp() throws Exception {
        Path catalogFile = tempDir.resolve("videos.json");
        VideoCatalogService catalog = new VideoCatalogService(new ObjectMapper());
        setField(catalog, "videosFilePath", catalogFile.toString());

        FfmpegDerivativeService ffmpeg = new FfmpegDerivativeService(
                new FfmpegProperties("ffmpeg", "ffprobe", 5, 0.20, 0.40, 1280, 720,
                        28, "fast", 23, "medium", 2,
                        "Spankycouples.com",
                        18, 24, 0.85, "black@0.4", 6, 12,
                        "See more SpankyCouples.com", 18,
                        25, new double[]{0.05, 0.35, 0.50, 0.65, 0.95},
                        true, "nvidia,intel,amd", "h264"));

        OllamaService ollama = mock(OllamaService.class);
        when(ollama.generate(anyString(), any())).thenReturn("""
                {"title":"Refined Title","description":"Refined desc.",
                 "category":"Amateur","tags":["alpha","beta","gamma"],
                 "slug":"refined-title","views":123456}
                """);

        BunnyAssetService bunny = mock(BunnyAssetService.class);
        when(bunny.getFolder()).thenReturn("videos");
        // Orchestrator consults getZoneKeys() to compute the
        // fail-soft "missing zone" warnings. Stub it so the
        // per-zone comparison works correctly.
        when(bunny.getZoneKeys()).thenReturn(java.util.List.of("spankycouples"));
        AtomicInteger uploadCount = new AtomicInteger();
        // Stub the multi-zone variant used by the orchestrator. Return
        // a single-zone map so the test mirrors the legacy single-CDN
        // behaviour: backupEmbedUrl == embedUrl, cdnZoneKeys has one
        // entry. A separate test exercises the real 2-zone path.
        when(bunny.uploadBytesMulti(any(Path.class), anyString(), anyString(), any())).thenAnswer(inv -> {
            uploadCount.incrementAndGet();
            // selectedKeys is the 4th arg (Collection<String>). null
            // means "upload to every configured zone" — we simulate
            // the result with one zone for this test.
            return java.util.Map.of("spankycouples", "https://cdn.example/" + inv.getArgument(1));
        });
        when(bunny.uploadBytesMulti(any(byte[].class), anyString(), anyString(), any())).thenAnswer(inv ->
                java.util.Map.of("spankycouples", "https://cdn.example/" + inv.getArgument(1)));

        VideoEnrichmentService service = new VideoEnrichmentService(
                ffmpeg, bunny, ollama, /*minimax*/ null, catalog,
                /*ffmpegTimeout*/ 120,
                /*maxDownloadBytes*/ 50_000_000L,
                /*downloadConnectSeconds*/ 5,
                /*downloadReadSeconds*/ 30,
                /*sweeperStaleMinutes*/ 60,
                /*ollamaFormat*/ "json",
                /*ollamaTemperature*/ 0.2);

        EnrichmentMetadata meta = new EnrichmentMetadata(
                "My Draft Title", "An authentic bedroom encounter.",
                "Amateur", List.of("amateur", "couple"),
                null, null, null);

        Path input = fixtureAsInput();
        long inputSize = Files.size(input);
        PipelineOutcome outcome = service.enrich(
                new EnrichmentSource.LocalFile(input, inputSize),
                meta, false);

        // ---- response ----
        VideoCatalogEntry entry = outcome.getEntry();
        assertThat(entry).isNotNull();
        assertThat(entry.getId()).isEqualTo("v1");
        assertThat(entry.getTitle()).isEqualTo("Refined Title");
        assertThat(entry.getDescription()).isEqualTo("Refined desc.");
        assertThat(entry.getCategory()).isEqualTo("Amateur");
        assertThat(entry.getTags()).containsExactly("alpha", "beta", "gamma");
        assertThat(entry.getSlug()).isEqualTo("refined-title");
        assertThat(entry.getViews()).isEqualTo(123_456L);
        assertThat(entry.getDurationSeconds()).isBetween(4, 6);
        assertThat(entry.getThumbnailKey()).startsWith("https://cdn.example/videos/").endsWith(".thumbnail.jpg");
        assertThat(entry.getPreviewUrl()).endsWith(".preview.mp4");
        assertThat(entry.getEmbedUrl()).endsWith(".compressed.mp4");
        assertThat(entry.getBackupEmbedUrl())
                .as("backupEmbedUrl mirrors embedUrl since only one CDN zone was returned")
                .isEqualTo(entry.getEmbedUrl());
        assertThat(entry.getCdnZoneKeys())
                .as("entry records which Bunny zones the upload reached")
                .containsExactly("spankycouples");
        assertThat(entry.getUnknownActressName()).isEqualTo("Anonymous Couple");
        assertThat(entry.getPublishedAt()).isNotBlank();

        // ---- side effects ----
        verify(ollama, atLeastOnce()).generate(anyString(), any());
        assertThat(uploadCount.get())
                .as("3 bunny uploads (thumbnail, preview, compressed)")
                .isEqualTo(3);
        assertThat(catalogFile).exists();
        List<VideoCatalogEntry> saved = catalog.readAll();
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getId()).isEqualTo("v1");
        assertThat(saved.get(0).getCdnZoneKeys())
                .as("persisted entry mirrors the multi-zone list")
                .containsExactly("spankycouples");

        // ---- cleanup: no scraper-enrich-* dirs leaked (the catalog file is
        // intentionally in tempDir and stays) ----
        try (var ds = Files.newDirectoryStream(tempDir, "scraper-enrich-*")) {
            int leaked = 0;
            for (var p : ds) leaked++;
            assertThat(leaked).as("orchestrator should delete its per-request temp dir").isEqualTo(0);
        }
    }

    @Test
    void enrich_ollamaFailure_fallsBackToInputMetadata() throws Exception {
        Path catalogFile = tempDir.resolve("videos-ollama-fail.json");
        VideoCatalogService catalog = new VideoCatalogService(new ObjectMapper());
        setField(catalog, "videosFilePath", catalogFile.toString());

        FfmpegDerivativeService ffmpeg = new FfmpegDerivativeService(
                new FfmpegProperties("ffmpeg", "ffprobe", 5, 0.20, 0.40, 1280, 720,
                        28, "fast", 23, "medium", 2,
                        "Spankycouples.com",
                        18, 24, 0.85, "black@0.4", 6, 12,
                        "See more SpankyCouples.com", 18,
                        25, new double[]{0.05, 0.35, 0.50, 0.65, 0.95},
                        true, "nvidia,intel,amd", "h264"));

        OllamaService ollama = mock(OllamaService.class);
        when(ollama.generate(anyString(), any())).thenThrow(new RuntimeException("Ollama offline"));

        BunnyAssetService bunny = mock(BunnyAssetService.class);
        when(bunny.getFolder()).thenReturn("videos");
        when(bunny.getZoneKeys()).thenReturn(java.util.List.of("spankycouples"));
        when(bunny.uploadBytesMulti(any(Path.class), anyString(), anyString(), any())).thenAnswer(inv ->
                java.util.Map.of("spankycouples", "https://cdn.example/" + inv.getArgument(1)));
        when(bunny.uploadBytesMulti(any(byte[].class), anyString(), anyString(), any())).thenAnswer(inv ->
                java.util.Map.of("spankycouples", "https://cdn.example/" + inv.getArgument(1)));

        VideoEnrichmentService service = new VideoEnrichmentService(
                ffmpeg, bunny, ollama, /*minimax*/ null, catalog,
                120, 50_000_000L, 5, 30, 60, "json", 0.2);

        EnrichmentMetadata meta = new EnrichmentMetadata(
                "Fallback Title", "Some description.",
                "Amateur", List.of("amateur", "couple"),
                "Jane Doe", null, null);

        Path input = fixtureAsInput();
        PipelineOutcome outcome = service.enrich(
                new EnrichmentSource.LocalFile(input, Files.size(input)),
                meta, false);

        VideoCatalogEntry entry = outcome.getEntry();
        assertThat(entry.getTitle()).isEqualTo("Fallback Title");
        assertThat(entry.getDescription()).isEqualTo("Some description.");
        assertThat(entry.getCategory()).isEqualTo("Amateur");
        assertThat(entry.getTags()).containsExactly("amateur", "couple");
        assertThat(entry.getSlug()).isEqualTo("fallback-title");
        assertThat(entry.getViews()).isEqualTo(0L);
        assertThat(entry.getUnknownActressName()).isEqualTo("Jane Doe");

        assertThat(outcome.getWarnings()).anyMatch(w -> w.contains("Ollama"));
    }

    /**
     * Multi-zone path: the orchestrator asks Bunny for two zones
     * (spankycouples + youjav) and gets back a per-zone URL map. The
     * catalog entry must record BOTH URLs:
     * <ul>
     *   <li>{@code embedUrl} → primary (spankycouples)</li>
     *   <li>{@code backupEmbedUrl} → second (youjav)</li>
     *   <li>{@code cdnZoneKeys} → the ordered list of zone keys</li>
     * </ul>
     * Downstream project-b uses these fields to pick which CDN to play
     * from and which to use as failover.
     */
    @Test
    void enrich_multiZone_recordsPerZoneUrlsAndBackupEmbed() throws Exception {
        Path catalogFile = tempDir.resolve("videos-multi-zone.json");
        VideoCatalogService catalog = new VideoCatalogService(new ObjectMapper());
        setField(catalog, "videosFilePath", catalogFile.toString());

        FfmpegDerivativeService ffmpeg = new FfmpegDerivativeService(
                new FfmpegProperties("ffmpeg", "ffprobe", 5, 0.20, 0.40, 1280, 720,
                        28, "fast", 23, "medium", 2,
                        "Spankycouples.com",
                        18, 24, 0.85, "black@0.4", 6, 12,
                        "See more SpankyCouples.com", 18,
                        25, new double[]{0.05, 0.35, 0.50, 0.65, 0.95},
                        true, "nvidia,intel,amd", "h264"));

        OllamaService ollama = mock(OllamaService.class);
        when(ollama.generate(anyString(), any())).thenReturn("{}");

        BunnyAssetService bunny = mock(BunnyAssetService.class);
        when(bunny.getFolder()).thenReturn("videos");
        // Multi-zone stub: return a different URL per zone so we can
        // verify the orchestrator routes the right URL to embedUrl vs
        // backupEmbedUrl. Order is canonical (primary first).
        when(bunny.uploadBytesMulti(any(Path.class), anyString(), anyString(), any())).thenAnswer(inv -> {
            String objectPath = inv.getArgument(1);
            java.util.LinkedHashMap<String, String> urls = new java.util.LinkedHashMap<>();
            urls.put("spankycouples", "https://spankycouples.example/" + objectPath);
            urls.put("youjav",       "https://youjav.example/"       + objectPath);
            return urls;
        });
        when(bunny.uploadBytesMulti(any(byte[].class), anyString(), anyString(), any())).thenAnswer(inv -> {
            String objectPath = inv.getArgument(1);
            java.util.LinkedHashMap<String, String> urls = new java.util.LinkedHashMap<>();
            urls.put("spankycouples", "https://spankycouples.example/" + objectPath);
            urls.put("youjav",       "https://youjav.example/"       + objectPath);
            return urls;
        });

        VideoEnrichmentService service = new VideoEnrichmentService(
                ffmpeg, bunny, ollama, /*minimax*/ null, catalog,
                120, 50_000_000L, 5, 30, 60, "json", 0.2);

        EnrichmentMetadata meta = new EnrichmentMetadata(
                "Multi Zone Test", null, "Amateur", List.of("test"),
                null, null, null);

        Path input = fixtureAsInput();
        PipelineOutcome outcome = service.enrich(
                new EnrichmentSource.LocalFile(input, Files.size(input)),
                meta, false);

        VideoCatalogEntry entry = outcome.getEntry();
        // Primary zone → embedUrl. The thumbnail/preview/portrait all
        // point at the primary too (canonical fields, single URL each).
        assertThat(entry.getEmbedUrl())
                .as("embedUrl is the primary zone's URL")
                .startsWith("https://spankycouples.example/")
                .endsWith(".compressed.mp4");
        assertThat(entry.getThumbnailKey()).startsWith("https://spankycouples.example/");
        assertThat(entry.getPreviewUrl()).startsWith("https://spankycouples.example/");
        // Second zone → backupEmbedUrl (new behaviour: no longer mirrors embedUrl).
        assertThat(entry.getBackupEmbedUrl())
                .as("backupEmbedUrl is the SECOND zone's URL when 2+ zones were uploaded")
                .startsWith("https://youjav.example/")
                .endsWith(".compressed.mp4")
                .isNotEqualTo(entry.getEmbedUrl());
        // The recorded zone-key list is what project-b consumes to know
        // which CDNs hold this video. Order = canonical (primary first).
        assertThat(entry.getCdnZoneKeys())
                .as("cdnZoneKeys is the ordered list of zones that got the upload")
                .containsExactly("spankycouples", "youjav");
    }

    /**
     * Selection override: caller passes a non-null bunnyZones set, and
     * the orchestrator must forward it verbatim to
     * {@link BunnyAssetService#uploadBytesMulti} so only the requested
     * zone is uploaded to. The catalog entry then records only the
     * chosen zone, not the full set.
     */
    @Test
    void enrich_bunnyZonesOverride_isForwardedToUploader() throws Exception {
        Path catalogFile = tempDir.resolve("videos-zone-override.json");
        VideoCatalogService catalog = new VideoCatalogService(new ObjectMapper());
        setField(catalog, "videosFilePath", catalogFile.toString());

        FfmpegDerivativeService ffmpeg = new FfmpegDerivativeService(
                new FfmpegProperties("ffmpeg", "ffprobe", 5, 0.20, 0.40, 1280, 720,
                        28, "fast", 23, "medium", 2,
                        "Spankycouples.com",
                        18, 24, 0.85, "black@0.4", 6, 12,
                        "See more SpankyCouples.com", 18,
                        25, new double[]{0.05, 0.35, 0.50, 0.65, 0.95},
                        true, "nvidia,intel,amd", "h264"));

        OllamaService ollama = mock(OllamaService.class);
        when(ollama.generate(anyString(), any())).thenReturn("{}");

        // Capture the selectedKeys arg passed to uploadBytesMulti.
        // The orchestrator must forward the caller's override unchanged.
        java.util.concurrent.atomic.AtomicReference<java.util.Collection<String>> seenSelection =
                new java.util.concurrent.atomic.AtomicReference<>();
        BunnyAssetService bunny = mock(BunnyAssetService.class);
        when(bunny.getFolder()).thenReturn("videos");
        when(bunny.uploadBytesMulti(any(Path.class), anyString(), anyString(), any())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            java.util.Collection<String> sel = inv.getArgument(3);
            seenSelection.set(sel);
            // Simulate the server: only the requested zone was uploaded.
            return java.util.Map.of("youjav", "https://youjav.example/" + inv.getArgument(1));
        });

        VideoEnrichmentService service = new VideoEnrichmentService(
                ffmpeg, bunny, ollama, /*minimax*/ null, catalog,
                120, 50_000_000L, 5, 30, 60, "json", 0.2);

        EnrichmentMetadata meta = new EnrichmentMetadata(
                "Override Test", null, "Amateur", List.of("test"),
                null, null, null);

        Path input = fixtureAsInput();
        java.util.Set<String> override = java.util.Set.of("youjav");
        service.enrich(
                new EnrichmentSource.LocalFile(input, Files.size(input)),
                meta, false, false, null, null, override);

        // Selection forwarded verbatim.
        assertThat(seenSelection.get())
                .as("orchestrator must forward the bunnyZones override to the uploader")
                .containsExactly("youjav");

        List<VideoCatalogEntry> saved = catalog.readAll();
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getCdnZoneKeys())
                .as("only the overridden zone is persisted on the entry")
                .containsExactly("youjav");
        assertThat(saved.get(0).getEmbedUrl()).startsWith("https://youjav.example/");
        // Single zone selected → backupEmbedUrl mirrors embedUrl (no failover URL).
        assertThat(saved.get(0).getBackupEmbedUrl()).isEqualTo(saved.get(0).getEmbedUrl());
    }

    @Test
    void enrich_bunnyFailure_cleansUpLocalFiles() throws Exception {
        Path catalogFile = tempDir.resolve("videos-bunny-fail.json");
        VideoCatalogService catalog = new VideoCatalogService(new ObjectMapper());
        setField(catalog, "videosFilePath", catalogFile.toString());

        FfmpegDerivativeService ffmpeg = new FfmpegDerivativeService(
                new FfmpegProperties("ffmpeg", "ffprobe", 5, 0.20, 0.40, 1280, 720,
                        28, "fast", 23, "medium", 2,
                        "Spankycouples.com",
                        18, 24, 0.85, "black@0.4", 6, 12,
                        "See more SpankyCouples.com", 18,
                        25, new double[]{0.05, 0.35, 0.50, 0.65, 0.95},
                        true, "nvidia,intel,amd", "h264"));

        OllamaService ollama = mock(OllamaService.class);
        when(ollama.generate(anyString(), any())).thenReturn("{}");

        BunnyAssetService bunny = mock(BunnyAssetService.class);
        when(bunny.getFolder()).thenReturn("videos");
        when(bunny.getZoneKeys()).thenReturn(java.util.List.of("spankycouples"));
        // With fail-soft semantics, the upload itself doesn't throw —
        // it returns an empty map when every zone fails. The
        // orchestrator then sees the empty map and throws an
        // IOException("no configured Bunny zone accepted the upload")
        // so the caller still gets a clear failure.
        when(bunny.uploadBytesMulti(any(Path.class), anyString(), anyString(), any()))
                .thenReturn(java.util.Map.of());
        when(bunny.uploadBytesMulti(any(byte[].class), anyString(), anyString(), any()))
                .thenReturn(java.util.Map.of());

        VideoEnrichmentService service = new VideoEnrichmentService(
                ffmpeg, bunny, ollama, /*minimax*/ null, catalog,
                120, 50_000_000L, 5, 30, 60, "json", 0.2);

        EnrichmentMetadata meta = new EnrichmentMetadata("Test", null, null, null, null, null, null);
        Path input = fixtureAsInput();

        assertThatThrownBy(() -> service.enrich(
                new EnrichmentSource.LocalFile(input, Files.size(input)), meta, false))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("bunny upload failed");

        assertThat(catalogFile).doesNotExist();
        try (var ds = Files.newDirectoryStream(tempDir, "scraper-enrich-*")) {
            int leaked = 0;
            for (var p : ds) leaked++;
            assertThat(leaked).as("orchestrator should delete its per-request temp dir").isEqualTo(0);
        }
    }

    /**
     * Fail-soft path: two zones are configured, the multi-zone upload
     * returns ONLY the primary (youjav rejected the upload — auth or
     * 5xx, doesn't matter). The orchestrator must:
     * <ul>
     *   <li>still persist the entry with {@code cdnZoneKeys=["spankycouples"]}</li>
     *   <li>set {@code embedUrl}/{@code backupEmbedUrl} to the
     *       primary's URL (no second zone available)</li>
     *   <li>attach a warning naming the failed zone so the user sees
     *       which CDN is unreachable</li>
     * </ul>
     * Pre-fail-soft this test would have failed with an
     * IOException("bunny upload failed: status 401 ...") on the
     * first upload attempt — making the whole batch unusable when
     * one mirror zone is misconfigured.
     */
    @Test
    void enrich_oneZoneFails_othersSucceedAndWarningIsAttached() throws Exception {
        Path catalogFile = tempDir.resolve("videos-fail-soft.json");
        VideoCatalogService catalog = new VideoCatalogService(new ObjectMapper());
        setField(catalog, "videosFilePath", catalogFile.toString());

        FfmpegDerivativeService ffmpeg = new FfmpegDerivativeService(
                new FfmpegProperties("ffmpeg", "ffprobe", 5, 0.20, 0.40, 1280, 720,
                        28, "fast", 23, "medium", 2,
                        "Spankycouples.com",
                        18, 24, 0.85, "black@0.4", 6, 12,
                        "See more SpankyCouples.com", 18,
                        25, new double[]{0.05, 0.35, 0.50, 0.65, 0.95},
                        true, "nvidia,intel,amd", "h264"));

        OllamaService ollama = mock(OllamaService.class);
        when(ollama.generate(anyString(), any())).thenReturn("{}");

        BunnyAssetService bunny = mock(BunnyAssetService.class);
        when(bunny.getFolder()).thenReturn("videos");
        // Two zones are configured; only the primary one accepts the
        // upload. This simulates youjav returning HTTP 401.
        when(bunny.getZoneKeys()).thenReturn(java.util.List.of("spankycouples", "youjav"));
        when(bunny.uploadBytesMulti(any(Path.class), anyString(), anyString(), any())).thenAnswer(inv -> {
            String objectPath = inv.getArgument(1);
            // Only spankycouples succeeds; youjav is missing from the
            // returned map (matches the real "BunnyAssetService
            // logged the failure and skipped" behaviour).
            return java.util.Map.of("spankycouples", "https://spankycouples.example/" + objectPath);
        });
        when(bunny.uploadBytesMulti(any(byte[].class), anyString(), anyString(), any())).thenAnswer(inv ->
                java.util.Map.of("spankycouples", "https://spankycouples.example/" + inv.getArgument(1)));

        VideoEnrichmentService service = new VideoEnrichmentService(
                ffmpeg, bunny, ollama, /*minimax*/ null, catalog,
                120, 50_000_000L, 5, 30, 60, "json", 0.2);

        EnrichmentMetadata meta = new EnrichmentMetadata(
                "Fail Soft Test", null, "Amateur", List.of("test"),
                null, null, null);

        Path input = fixtureAsInput();
        PipelineOutcome outcome = service.enrich(
                new EnrichmentSource.LocalFile(input, Files.size(input)),
                meta, false);

        // Entry still got persisted — fail-soft means we don't lose
        // the upload just because one zone was unhappy.
        VideoCatalogEntry entry = outcome.getEntry();
        assertThat(entry).as("entry persists even when one zone fails").isNotNull();
        assertThat(entry.getCdnZoneKeys())
                .as("cdnZoneKeys only contains the zone that actually accepted the upload")
                .containsExactly("spankycouples");
        assertThat(entry.getEmbedUrl())
                .startsWith("https://spankycouples.example/")
                .endsWith(".compressed.mp4");
        // Only one zone in the map → backupEmbedUrl mirrors embedUrl
        // (no second zone to point at).
        assertThat(entry.getBackupEmbedUrl()).isEqualTo(entry.getEmbedUrl());

        // The user must see WHY youjav is missing — surfaced via the
        // warning list that the controller pipes into the response.
        assertThat(outcome.getWarnings())
                .as("warning names the failed zone so the user can investigate")
                .anyMatch(w -> w.contains("youjav") && w.contains("failed"));
    }

    // ---- helpers ----

    private static void setField(Object target, String name, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ----- computeSkipHeadSeconds unit tests -----

    @Test
    void computeSkipHeadSeconds_returns6ForXhamsterComVariant() {
        assertThat(VideoEnrichmentService.computeSkipHeadSeconds(
                new EnrichmentSource.RemoteUrl("https://cdn.xhamster.com/.../v.mp4",
                        "https://xhamster.com/videos/abc-123"))).isEqualTo(6.0);
    }

    @Test
    void computeSkipHeadSeconds_returns6ForXhamsterCountryVariants() {
        assertThat(VideoEnrichmentService.computeSkipHeadSeconds(
                new EnrichmentSource.RemoteUrl("https://xhamster.desi/v/foo",
                        "https://xhamster.desi/videos/foo"))).isEqualTo(6.0);
        assertThat(VideoEnrichmentService.computeSkipHeadSeconds(
                new EnrichmentSource.RemoteUrl("https://xhamster2.com/v/foo",
                        "https://xhamster2.com/videos/foo"))).isEqualTo(6.0);
    }

    @Test
    void computeSkipHeadSeconds_returns0ForErome() {
        // Erome is the most common other source — must NOT trigger the
        // 6s skip, even though its video files share some CDN with xhamster.
        assertThat(VideoEnrichmentService.computeSkipHeadSeconds(
                new EnrichmentSource.RemoteUrl("https://erome.com/a/abc",
                        "https://www.erome.com/a/abc"))).isEqualTo(0.0);
    }

    @Test
    void computeSkipHeadSeconds_returns0ForLocalFileSource() {
        // No page URL on a local file → no skip.
        assertThat(VideoEnrichmentService.computeSkipHeadSeconds(
                new EnrichmentSource.LocalFile(Path.of("/tmp/in.mp4"), 100))).isEqualTo(0.0);
    }

    @Test
    void computeSkipHeadSeconds_returns0ForBlankReferer() {
        assertThat(VideoEnrichmentService.computeSkipHeadSeconds(
                new EnrichmentSource.RemoteUrl("https://example.com/video.mp4", ""))).isEqualTo(0.0);
        assertThat(VideoEnrichmentService.computeSkipHeadSeconds(
                new EnrichmentSource.RemoteUrl("https://example.com/video.mp4", null))).isEqualTo(0.0);
    }
}
