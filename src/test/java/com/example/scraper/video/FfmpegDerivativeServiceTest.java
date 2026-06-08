package com.example.scraper.video;

import net.bramp.ffmpeg.FFmpeg;
import net.bramp.ffmpeg.FFprobe;
import net.bramp.ffmpeg.probe.FFmpegProbeResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * JUnit 5 tests for {@link FfmpegDerivativeService}. Plain unit-style
 * test (no {@code @SpringBootTest}); the service is constructed with
 * pre-built bramp objects.
 *
 * <p>Fixture videos are generated with {@code ffmpeg -f lavfi} once per
 * JVM in {@code target/test-fixtures/}. If {@code ffmpeg} is not on
 * PATH, every test is skipped (not failed) so CI on a minimal image
 * still produces a green build.
 */
class FfmpegDerivativeServiceTest {

    private static final double ASPECT_EPSILON = 0.02;  // covers trunc/2*2 rounding

    private static Path fixturesDir;
    private static Path largeLandscape;
    private static Path portraitTiktok;
    private static Path smallLandscape;
    private static Path veryShort;
    private static Path audioless;

    private static FfmpegDerivativeService service;
    private static FFprobe ffprobe;

    @TempDir
    Path outDir;

    @BeforeAll
    static void setUp() throws IOException {
        assumeTrue(ffmpegOnPath(), "ffmpeg not on PATH — skipping derivative tests");

        // Target a known location in target/ so a partial run can resume.
        fixturesDir = Path.of("target", "test-fixtures");
        Files.createDirectories(fixturesDir);

        largeLandscape = generate(
                "large_landscape.mp4",
                List.of(
                        "-f", "lavfi", "-i", "testsrc=size=1920x1080:rate=30:duration=10",
                        "-f", "lavfi", "-i", "sine=frequency=440:duration=10",
                        "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest"));

        portraitTiktok = generate(
                "portrait_tiktok.mp4",
                List.of(
                        "-f", "lavfi", "-i", "testsrc=size=1080x1920:rate=30:duration=12",
                        "-f", "lavfi", "-i", "sine=frequency=440:duration=12",
                        "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest"));

        smallLandscape = generate(
                "small_landscape.mp4",
                List.of(
                        "-f", "lavfi", "-i", "testsrc=size=480x270:rate=30:duration=8",
                        "-c:v", "libx264", "-pix_fmt", "yuv420p"));  // no audio

        veryShort = generate(
                "very_short.mp4",
                List.of(
                        "-f", "lavfi", "-i", "testsrc=size=1280x720:rate=30:duration=3",
                        "-c:v", "libx264", "-pix_fmt", "yuv420p"));

        audioless = generate(
                "audioless.mp4",
                List.of(
                        "-f", "lavfi", "-i", "testsrc=size=1920x1080:rate=30:duration=5",
                        "-c:v", "libx264", "-pix_fmt", "yuv420p"));  // no audio

        FfmpegProperties props = new FfmpegProperties(
                "ffmpeg", "ffprobe",
                5, 0.20, 0.40,
                1280, 720,
                28, "fast",
                23, "medium",
                2,
                "Spankycouples.com",
                18, 24, 0.85,
                "black@0.4", 6, 12,
                "See more SpankyCouples.com", 18,
                25, new double[]{0.05, 0.35, 0.50, 0.65, 0.95},
                true, "nvidia,intel,amd",
                "h264");  // tests intentionally use the legacy H.264 path
        FFmpeg ffmpeg = new FFmpeg(props.getFfmpegPath());
        ffprobe = new FFprobe(props.getFfprobePath());
        service = new FfmpegDerivativeService(props, ffmpeg, ffprobe);
    }

    private static boolean ffmpegOnPath() {
        try {
            return new ProcessBuilder("ffmpeg", "-version")
                    .redirectErrorStream(true)
                    .start()
                    .waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static Path generate(String name, List<String> args) throws IOException {
        Path out = fixturesDir.resolve(name);
        if (Files.exists(out) && Files.size(out) > 0) {
            return out;  // resume: fixture already there
        }
        List<String> cmd = new java.util.ArrayList<>();
        cmd.add("ffmpeg");
        cmd.add("-y");
        cmd.addAll(args);
        cmd.add(out.toString());
        Process p = new ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .start();
        // Drain stdout/stderr to avoid pipe backpressure
        try (var in = p.getInputStream()) {
            in.transferTo(java.io.OutputStream.nullOutputStream());
        }
        int code;
        try {
            code = p.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("fixture generation interrupted: " + name, e);
        }
        if (code != 0) {
            throw new IOException("fixture generation failed: " + name + " (exit " + code + ")");
        }
        return out;
    }

    // -------------------------- tests --------------------------

    @Test
    void process_largeLandscape_capsTo720pAndPreservesAudio() throws IOException {
        Derivatives d = service.process(largeLandscape, outDir, "large");

        assertAllOutputsExistAndNonEmpty(d);

        // Compressed and preview: exactly 1280x720
        var comp = ffprobe.probe(d.compressedPath().toString());
        var prev = ffprobe.probe(d.previewPath().toString());
        assertVideoStream(comp, 1280, 720);
        assertVideoStream(prev, 1280, 720);

        // Preview duration is 5.0s ± 0.25s
        assertThat(prev.format.duration).isBetween(4.75, 5.25);

        // Thumbnail is at source resolution (no scale)
        var thumb = ffprobe.probe(d.thumbnailPath().toString());
        assertVideoStream(thumb, 1920, 1080);

        // Audio is preserved in compressed (and preview)
        assertThat(hasAudioStream(comp)).as("compressed has aac audio").isTrue();
        assertThat(hasAudioStream(prev)).as("preview has aac audio").isTrue();

        // Aspect ratio preserved on compressed + preview
        assertAspectPreserved(largeLandscape, d.compressedPath(), ASPECT_EPSILON);
        assertAspectPreserved(largeLandscape, d.previewPath(),    ASPECT_EPSILON);
    }

    @Test
    void process_portraitTiktok_preservesNineSixteenAspect() throws IOException {
        Derivatives d = service.process(portraitTiktok, outDir, "tiktok");

        assertAllOutputsExistAndNonEmpty(d);

        var comp = ffprobe.probe(d.compressedPath().toString());
        var thumb = ffprobe.probe(d.thumbnailPath().toString());

        // Compressed is 404x720: not landscape 1280x720, not square 1280x1280, not flipped 720x1280.
        // Each dimension asserted individually so failure messages name the wrong assumption.
        assertThat(firstVideoStream(comp).width)
                .as("tiktok compressed width is the 9:16 fit, not 1280 (landscape) or 720 (flipped)")
                .isEqualTo(404);
        assertThat(firstVideoStream(comp).height)
                .as("tiktok compressed height is exactly 720")
                .isEqualTo(720);

        // Thumbnail keeps source resolution
        assertVideoStream(thumb, 1080, 1920);

        // Aspect preserved
        assertAspectPreserved(portraitTiktok, d.compressedPath(), ASPECT_EPSILON);
        assertAspectPreserved(portraitTiktok, d.thumbnailPath(), ASPECT_EPSILON);
    }

    @Test
    void process_smallLandscape_doesNotUpscale() throws IOException {
        Derivatives d = service.process(smallLandscape, outDir, "small");

        assertAllOutputsExistAndNonEmpty(d);

        // 480x270 source must NOT be upscaled. Every output stays at 480x270.
        var comp = ffprobe.probe(d.compressedPath().toString());
        var prev = ffprobe.probe(d.previewPath().toString());
        var thumb = ffprobe.probe(d.thumbnailPath().toString());

        assertVideoStream(comp,  480, 270);
        assertVideoStream(prev,  480, 270);
        assertVideoStream(thumb, 480, 270);

        assertAspectPreserved(smallLandscape, d.compressedPath(), ASPECT_EPSILON);
        assertAspectPreserved(smallLandscape, d.previewPath(),    ASPECT_EPSILON);
        assertAspectPreserved(smallLandscape, d.thumbnailPath(),  ASPECT_EPSILON);
    }

    @Test
    void process_veryShortVideo_clampsPreviewDuration() throws IOException {
        Derivatives d = service.process(veryShort, outDir, "veryshort");

        assertAllOutputsExistAndNonEmpty(d);

        // 3s source, preview starts at 40% = 1.2s, remaining = 1.8s. previewDur = min(5, max(0.5, 1.8)) = 1.8s.
        var prev = ffprobe.probe(d.previewPath().toString());
        assertThat(prev.format.duration)
                .as("preview duration is clamped to remaining source length, not the requested 5s")
                .isBetween(1.5, 2.1);

        // Thumbnail is also at 20% = 0.6s — readable JPEG
        var thumb = ffprobe.probe(d.thumbnailPath().toString());
        assertThat(firstVideoStream(thumb).width).isPositive();
        assertThat(firstVideoStream(thumb).height).isPositive();
    }

    @Test
    void process_audioLessVideo_producesOutputsWithoutAudioStream() throws IOException {
        Derivatives d = service.process(audioless, outDir, "audioless");

        assertAllOutputsExistAndNonEmpty(d);

        VideoMetadata metadata = service.probe(audioless);
        assertThat(metadata.hasAudio()).as("source has no audio").isFalse();

        var comp = ffprobe.probe(d.compressedPath().toString());
        var prev = ffprobe.probe(d.previewPath().toString());
        assertThat(hasAudioStream(comp)).as("compressed has no audio").isFalse();
        assertThat(hasAudioStream(prev)).as("preview has no audio").isFalse();
    }

    @Test
    void probe_returnsExpectedMetadata() throws IOException {
        VideoMetadata m = service.probe(largeLandscape);
        assertThat(m.width()).isEqualTo(1920);
        assertThat(m.height()).isEqualTo(1080);
        assertThat(m.durationSeconds()).isBetween(9.9, 10.1);
        assertThat(m.hasAudio()).isTrue();
    }

    @Test
    void buildCompressedJob_omitsSsFlagWhenSkipIsZero() {
        // No skip → the input has no -ss. The ffmpeg command line
        // would otherwise have a -ss 0.000 argument that confuses
        // downstream parsers and adds nothing.
        var builder = service.buildCompressedJob(Path.of("/tmp/in.mp4"),
                Path.of("/tmp/out.mp4"), false, 0.0);
        var cmd = builder.build();
        assertThat(cmd).noneMatch(arg -> arg.equals("-ss"));
        // -i flag and the input path must be present (separately,
        // because FFmpegBuilder normalizes Windows backslashes — the
        // exact "/tmp/in.mp4" string is only on POSIX runtimes).
        assertThat(cmd).contains("-i");
        assertThat(cmd).anyMatch(a -> a.endsWith("in.mp4"));
    }

    @Test
    void buildCompressedJob_addsSsFlagBeforeInputWhenSkipIsPositive() {
        // xhamster case: skipHead=6 should emit "-ss <sec> -i in.mp4"
        // (input-side fast seek, before the -i flag).
        var builder = service.buildCompressedJob(Path.of("/tmp/in.mp4"),
                Path.of("/tmp/out.mp4"), true, 6.0);
        var cmd = builder.build();
        int ssIdx = -1, iIdx = -1;
        for (int i = 0; i < cmd.size(); i++) {
            if ("-ss".equals(cmd.get(i))) ssIdx = i;
            if ("-i".equals(cmd.get(i))) iIdx = i;
        }
        assertThat(ssIdx).as("-ss flag must be present").isNotNegative();
        assertThat(iIdx).as("-i flag must be present").isNotNegative();
        assertThat(ssIdx).as("-ss must come BEFORE -i (input-side seek)").isLessThan(iIdx);
        // Argument immediately after -ss should be a number containing "6".
        assertThat(cmd.get(ssIdx + 1)).contains("6");
    }

    @Test
    void buildCompressedJob_omitsSsFlagWhenSkipIsNegative() {
        // Negative skips would be a bug elsewhere; the job should treat
        // them as "no skip" defensively (buildCompressedJob's only
        // contract is "if skipHead > 0, emit -ss"). The duration-clamp
        // for safety lives in process(), not here.
        var builder = service.buildCompressedJob(Path.of("/tmp/in.mp4"),
                Path.of("/tmp/out.mp4"), false, -1.0);
        assertThat(builder.build()).noneMatch(arg -> arg.equals("-ss"));
    }

    @Test
    void assertAspectPreserved_helperItselfWorks() throws IOException {
        // Sanity: same file aspect-pass.
        assertAspectPreserved(largeLandscape, largeLandscape, ASPECT_EPSILON);
        // Sanity: matching-aspect files at different sizes pass.
        Path another1920x1080 = generate(
                "another_1920x1080.mp4",
                List.of("-f", "lavfi", "-i", "testsrc=size=1920x1080:rate=30:duration=2",
                        "-c:v", "libx264", "-pix_fmt", "yuv420p"));
        assertAspectPreserved(largeLandscape, another1920x1080, ASPECT_EPSILON);

        // The 480x270 fixture has a 16:9 aspect, so comparing it to a 320x180 should pass
        // (same 16:9 ratio). Use portraitTiktok for the negative case (9:16 vs 16:9 -> fail).
        assertThatThrownBy(() -> assertAspectPreserved(largeLandscape, portraitTiktok, ASPECT_EPSILON))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("aspect ratio");
    }

    // -------------------------- helpers --------------------------

    private void assertAllOutputsExistAndNonEmpty(Derivatives d) throws IOException {
        for (Path p : List.of(d.compressedPath(), d.previewPath(), d.thumbnailPath())) {
            assertThat(p).as("output file exists: " + p).exists();
            assertThat(Files.size(p)).as("output file is non-empty: " + p).isPositive();
        }
    }

    private void assertVideoStream(FFmpegProbeResult probe, int expectedW, int expectedH) {
        var s = firstVideoStream(probe);
        assertThat(s.width)
                .as("video width")
                .isEqualTo(expectedW);
        assertThat(s.height)
                .as("video height")
                .isEqualTo(expectedH);
    }

    private static net.bramp.ffmpeg.probe.FFmpegStream firstVideoStream(FFmpegProbeResult probe) {
        return probe.streams.stream()
                .filter(s -> s.codec_type == net.bramp.ffmpeg.shared.CodecType.VIDEO)
                .min(Comparator.comparingInt(s -> s.index))
                .orElseThrow(() -> new AssertionError("no video stream in " + probe.format.filename));
    }

    private static boolean hasAudioStream(FFmpegProbeResult probe) {
        return probe.streams.stream()
                .anyMatch(s -> s.codec_type == net.bramp.ffmpeg.shared.CodecType.AUDIO);
    }

    private void assertAspectPreserved(Path input, Path output, double epsilon) throws IOException {
        var in  = ffprobe.probe(input.toString());
        var out = ffprobe.probe(output.toString());
        var inS  = firstVideoStream(in);
        var outS = firstVideoStream(out);
        double inAr  = (double) inS.width  / inS.height;
        double outAr = (double) outS.width / outS.height;
        assertThat(Math.abs(inAr - outAr))
                .as("aspect ratio of %s (%dx%d) is preserved in %s (%dx%d)",
                        input, inS.width, inS.height, output, outS.width, outS.height)
                .isLessThan(epsilon);
    }
}
