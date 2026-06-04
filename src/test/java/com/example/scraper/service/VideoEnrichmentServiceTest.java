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
                        28, "fast", 23, "medium", 2));

        OllamaService ollama = mock(OllamaService.class);
        when(ollama.generate(anyString(), any())).thenReturn("""
                {"title":"Refined Title","description":"Refined desc.",
                 "category":"Amateur","tags":["alpha","beta","gamma"],
                 "slug":"refined-title","views":123456}
                """);

        BunnyAssetService bunny = mock(BunnyAssetService.class);
        when(bunny.getFolder()).thenReturn("videos");
        AtomicInteger uploadCount = new AtomicInteger();
        when(bunny.uploadBytes(any(Path.class), anyString(), anyString())).thenAnswer(inv -> {
            uploadCount.incrementAndGet();
            return "https://cdn.example/" + inv.getArgument(1);
        });

        VideoEnrichmentService service = new VideoEnrichmentService(
                ffmpeg, bunny, ollama, catalog,
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
                null, null);

        Path input = fixtureAsInput();
        long inputSize = Files.size(input);
        PipelineOutcome outcome = service.enrich(
                new EnrichmentSource.LocalFile(input, inputSize),
                meta);

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
                .as("backupEmbedUrl mirrors embedUrl since there's no second CDN")
                .isEqualTo(entry.getEmbedUrl());
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
                        28, "fast", 23, "medium", 2));

        OllamaService ollama = mock(OllamaService.class);
        when(ollama.generate(anyString(), any())).thenThrow(new RuntimeException("Ollama offline"));

        BunnyAssetService bunny = mock(BunnyAssetService.class);
        when(bunny.getFolder()).thenReturn("videos");
        when(bunny.uploadBytes(any(Path.class), anyString(), anyString())).thenAnswer(inv ->
                "https://cdn.example/" + inv.getArgument(1));

        VideoEnrichmentService service = new VideoEnrichmentService(
                ffmpeg, bunny, ollama, catalog,
                120, 50_000_000L, 5, 30, 60, "json", 0.2);

        EnrichmentMetadata meta = new EnrichmentMetadata(
                "Fallback Title", "Some description.",
                "Amateur", List.of("amateur", "couple"),
                "Jane Doe", null);

        Path input = fixtureAsInput();
        PipelineOutcome outcome = service.enrich(
                new EnrichmentSource.LocalFile(input, Files.size(input)),
                meta);

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

    @Test
    void enrich_bunnyFailure_cleansUpLocalFiles() throws Exception {
        Path catalogFile = tempDir.resolve("videos-bunny-fail.json");
        VideoCatalogService catalog = new VideoCatalogService(new ObjectMapper());
        setField(catalog, "videosFilePath", catalogFile.toString());

        FfmpegDerivativeService ffmpeg = new FfmpegDerivativeService(
                new FfmpegProperties("ffmpeg", "ffprobe", 5, 0.20, 0.40, 1280, 720,
                        28, "fast", 23, "medium", 2));

        OllamaService ollama = mock(OllamaService.class);
        when(ollama.generate(anyString(), any())).thenReturn("{}");

        BunnyAssetService bunny = mock(BunnyAssetService.class);
        when(bunny.getFolder()).thenReturn("videos");
        when(bunny.uploadBytes(any(Path.class), anyString(), anyString()))
                .thenThrow(new IOException("Bunny unreachable"));

        VideoEnrichmentService service = new VideoEnrichmentService(
                ffmpeg, bunny, ollama, catalog,
                120, 50_000_000L, 5, 30, 60, "json", 0.2);

        EnrichmentMetadata meta = new EnrichmentMetadata("Test", null, null, null, null, null);
        Path input = fixtureAsInput();

        assertThatThrownBy(() -> service.enrich(
                new EnrichmentSource.LocalFile(input, Files.size(input)), meta))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("bunny upload failed");

        assertThat(catalogFile).doesNotExist();
        try (var ds = Files.newDirectoryStream(tempDir, "scraper-enrich-*")) {
            int leaked = 0;
            for (var p : ds) leaked++;
            assertThat(leaked).as("orchestrator should delete its per-request temp dir").isEqualTo(0);
        }
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
}
