package com.example.scraper.video;

import net.bramp.ffmpeg.FFmpeg;
import net.bramp.ffmpeg.FFprobe;
import net.bramp.ffmpeg.builder.FFmpegBuilder;
import net.bramp.ffmpeg.builder.FFmpegOutputBuilder;
import net.bramp.ffmpeg.probe.FFmpegProbeResult;
import net.bramp.ffmpeg.probe.FFmpegStream;
import net.bramp.ffmpeg.shared.CodecType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Produces three derivative files from a single source video using
 * {@code ffmpeg} / {@code ffprobe} (resolved through the bramp wrapper):
 *
 * <ul>
 *   <li>a full-length 720p-capped re-encode ({@code baseName.compressed.mp4}),</li>
 *   <li>a 5-second preview clip sampled at 40% of duration
 *       ({@code baseName.preview.mp4}),</li>
 *   <li>a single-frame JPEG thumbnail sampled at 20% of duration
 *       ({@code baseName.thumbnail.jpg}) — written at the source's native
 *       resolution so small inputs are never stretched.</li>
 * </ul>
 *
 * <p><b>Aspect-ratio contract.</b> The 720p video and preview use
 * {@code scale=…:force_original_aspect_ratio=decrease}, so the output is
 * capped at 1280x720 and <i>never</i> upscaled. A 1080x1920 tiktok input
 * yields a 404x720 output, preserving the 9:16 ratio. A 480x270 input
 * stays at 480x270. The thumbnail has no scale filter at all, so it
 * always matches the source resolution byte-for-byte.
 *
 * <p>The service runs three separate {@code ffmpeg} invocations (one per
 * output) so stderr is isolated per-output and failure attribution is
 * trivial in tests.
 */
@Service
public class FfmpegDerivativeService {

    private static final Logger log = LoggerFactory.getLogger(FfmpegDerivativeService.class);

    private final FFmpeg ffmpeg;
    private final FFprobe ffprobe;
    private final FfmpegProperties props;
    private final WatermarkFontService watermarkFont;
    private final GpuEncoderProbe gpuProbe;
    /**
     * Cached encoder name from the last {@link #encoder()} call. Caching
     * the name (not the strategy object) is enough for log lines, and
     * the strategy object is cheap to allocate per-encode so we don't
     * bother memoizing it.
     */
    private volatile String lastEncoderName = "libx264";

    /** Spring constructor — resolves ffmpeg/ffprobe via the configured paths. */
    @Autowired
    public FfmpegDerivativeService(FfmpegProperties props,
                                   WatermarkFontService watermarkFont,
                                   GpuEncoderProbe gpuProbe) throws IOException {
        this(props, watermarkFont, gpuProbe,
             new FFmpeg(props.getFfmpegPath()), new FFprobe(props.getFfprobePath()));
    }

    /** Test / advanced constructor — caller supplies all dependencies. */
    public FfmpegDerivativeService(FfmpegProperties props, WatermarkFontService watermarkFont,
                                   GpuEncoderProbe gpuProbe,
                                   FFmpeg ffmpeg, FFprobe ffprobe) {
        this.props = props;
        this.watermarkFont = watermarkFont;
        this.gpuProbe = gpuProbe;
        this.ffmpeg = ffmpeg;
        this.ffprobe = ffprobe;
        // Note: we DO NOT pick the encoder here. The GpuEncoderProbe
        // populates its `selected` field inside its @PostConstruct,
        // which Spring runs AFTER this constructor. Picking the encoder
        // here would always yield libx264 (gpuProbe.isGpuActive() is
        // false at construction time). The encoder is resolved lazily
        // on every job build via {@link #encoder()}.
        log.info("FfmpegDerivativeService ready; encoder will be resolved per-job "
                + "(GPU probe will be consulted at first use).");
    }

    /**
     * Resolve the current encoder strategy. Called once per encode
     * build so the GPU probe's {@code @PostConstruct} has a chance to
     * run between the derivative service's construction and the first
     * encode. The probe's per-codec selection map is volatile, so this
     * is thread-safe.
     *
     * <p>Strategy:
     * <ol>
     *   <li>Read the configured codec (H.264 or HEVC) from
     *       {@link FfmpegProperties#getCodec()}.</li>
     *   <li>Ask the GPU probe for the best encoder for that codec.
     *       If one is available, use the hardware strategy.</li>
     *   <li>Otherwise fall back to the codec's CPU encoder
     *       (libx264 / libx265).</li>
     * </ol>
     */
    VideoEncoderStrategy encoder() {
        Codec codec = props.getCodec();
        GpuEncoderProbe.Detection d = (gpuProbe != null) ? gpuProbe.pick(codec) : null;
        VideoEncoderStrategy e = (d != null)
                ? VideoEncoderStrategies.forGpu(d)
                : VideoEncoderStrategies.forCodec(codec);
        lastEncoderName = e.name();
        return e;
    }

    /**
     * The encoder the last {@link #encoder()} call resolved to. Cheap
     * log/metric accessor — does NOT trigger a re-evaluation, so it's
     * safe to call from outside the encode path.
     */
    public String currentEncoderName() {
        return lastEncoderName;
    }

    /**
     * True when the resolved encoder is a hardware backend
     * (h264_nvenc / h264_qsv / h264_amf). Cheap, side-effect-free.
     */
    public boolean isHardwareAccelerated() {
        // We don't cache the previous boolean — the encoder is cheap
        // to resolve and the probe's selected field is volatile, so
        // a fresh read is the safest option. Throws away the strategy
        // object immediately.
        return encoder().isHardwareAccelerated();
    }

    /**
     * Backwards-compatible test constructor — no font service and no
     * GPU probe. Falls back to libx264. Tests that don't care about
     * watermarks or GPU can keep using this signature.
     */
    public FfmpegDerivativeService(FfmpegProperties props, FFmpeg ffmpeg, FFprobe ffprobe) {
        this(props, new NoOpWatermarkFontService(), null, ffmpeg, ffprobe);
    }

    /**
     * Backwards-compatible test constructor — only properties. The
     * FFmpeg/FFprobe wrappers are constructed from the configured paths
     * (which will throw at construction time on machines without ffmpeg,
     * matching the legacy behavior).
     */
    public FfmpegDerivativeService(FfmpegProperties props) throws IOException {
        this(props, new NoOpWatermarkFontService(), null,
             new FFmpeg(props.getFfmpegPath()), new FFprobe(props.getFfprobePath()));
    }

    /** No-op font service for tests that want plain scale-filter output. */
    private static final class NoOpWatermarkFontService extends WatermarkFontService {
        NoOpWatermarkFontService() { super("", ""); }
        @Override public Optional<Path> resolveFontPath() { return Optional.empty(); }
    }

    /**
     * Probe the source and return enough metadata to drive the
     * derivative pipeline.
     */
    public VideoMetadata probe(Path input) throws IOException {
        FFmpegProbeResult result = ffprobe.probe(input.toString());

        int width = 0;
        int height = 0;
        double duration = (result.getFormat() != null) ? result.getFormat().duration : 0d;
        boolean hasAudio = false;

        for (FFmpegStream s : result.getStreams()) {
            if (s.codec_type == CodecType.VIDEO && width == 0 && height == 0) {
                width = s.width;
                height = s.height;
                if (duration <= 0d && s.duration > 0d) {
                    duration = s.duration;
                }
            }
            if (s.codec_type == CodecType.AUDIO) {
                hasAudio = true;
            }
        }
        return new VideoMetadata(width, height, duration, hasAudio);
    }

    /** Convenience: probe then process in one call. */
    public Derivatives process(Path input, Path outputDir, String baseName) throws IOException {
        return process(input, outputDir, baseName, probe(input), 0.0);
    }

    public Derivatives process(Path input, Path outputDir, String baseName, VideoMetadata precomputed)
            throws IOException {
        return process(input, outputDir, baseName, precomputed, 0.0);
    }

    /**
     * Process a source video and write the three derivatives into
     * {@code outputDir} using {@code baseName} as the file prefix.
     *
     * <p>{@code precomputed} lets the CLI runner print the probed
     * metadata before kicking off the encode without re-probing.
     *
     * <p>{@code skipHeadSeconds} trims the first N seconds of the source
     * before encoding the COMPRESSED output only. The 5s preview, the
     * thumbnail, and (when generated separately) the 5 watermark-baked
     * mini clips are still taken from the original source — only the
     * full compressed re-encode is trimmed. Use this to drop a site's
     * intro/ad segment (e.g. xhamster's first 6 seconds are a logo
     * bumper) without losing any of the actual content from the
     * derived previews.
     */
    public Derivatives process(Path input, Path outputDir, String baseName, VideoMetadata precomputed,
                               double skipHeadSeconds)
            throws IOException {
        Files.createDirectories(outputDir);

        Path compressedPath = outputDir.resolve(baseName + ".compressed.mp4");
        Path previewPath    = outputDir.resolve(baseName + ".preview.mp4");
        Path thumbnailPath  = outputDir.resolve(baseName + ".thumbnail.jpg");

        // Clamp the skip to the source duration minus a 0.5s safety
        // margin so we never ask ffmpeg to skip past the end (which
        // would emit a zero-byte file with a non-zero exit code).
        double effectiveSkip = Math.max(0.0,
                Math.min(skipHeadSeconds, Math.max(0.0, precomputed.durationSeconds() - 0.5)));

        double thumbSec   = clampToDuration(precomputed.durationSeconds() * props.getThumbnailPosition(),
                                            precomputed.durationSeconds());
        double previewSec = clampToDuration(precomputed.durationSeconds() * props.getPreviewPosition(),
                                            precomputed.durationSeconds());
        double previewDur = Math.min(
                props.getPreviewDurationSeconds(),
                Math.max(0.5d, precomputed.durationSeconds() - previewSec)
        );

        // Resolve the encoder once for this encode (avoids 3 separate
        // gpuProbe.isGpuActive() lookups + makes the encoder name in the
        // log lines below unambiguous).
        VideoEncoderStrategy enc = encoder();
        log.info("ffmpeg derivative start: input={}, baseName={}, encoder={} (hwAccel={}), "
                        + "duration={}s, hasAudio={}, thumbSec={}, previewSec={}, previewDur={}, skipHead={}s",
                input, baseName, enc.name(), enc.isHardwareAccelerated(),
                String.format("%.2f", precomputed.durationSeconds()),
                precomputed.hasAudio(),
                String.format("%.2f", thumbSec),
                String.format("%.2f", previewSec),
                String.format("%.2f", previewDur),
                String.format("%.2f", effectiveSkip));

        runCompressed(input, compressedPath, precomputed.hasAudio(), effectiveSkip);
        runPreview(input, previewPath, previewSec, previewDur, precomputed.hasAudio());
        runThumbnail(input, thumbnailPath, thumbSec);

        log.info("ffmpeg derivative done: input={}, baseName={}, encoder={}",
                input, baseName, lastEncoderName);
        return new Derivatives(compressedPath, previewPath, thumbnailPath);
    }

    // -------- package-private builders (reusable from tests) --------

    /**
     * Filter string for compressed + preview. Caps to 1280x720, never upscales.
     *
     * <p><b>Color policy:</b> the goal is pixel-perfect preservation of the
     * source. We do not run a color matrix conversion, we do not run a
     * range conversion. The only thing we do is set the OUTPUT
     * colorimetry to BT.709 / pc via {@code out_color_matrix=bt709}
     * and {@code out_range=pc} on the scale filter, which tells ffmpeg
     * "the bytes you emit are BT.709 full-range" without forcing it to
     * reinterpret the source. This means the input is auto-detected
     * (or passed through untouched if the source is already BT.709).
     *
     * <p>Earlier iterations wrapped a {@code colorspace=iall=bt709:all=bt709}
     * filter in the chain, but that filter is itself known to introduce
     * subtle saturation in some sources. Dropping it and letting
     * {@code out_color_matrix} do the work is the most minimal
     * intervention that still tags the output correctly.
     */
    String scaleFilter() {
        return "scale=w='min(iw," + props.getTargetMaxWidth() + ")':h='min(ih,"
                + props.getTargetMaxHeight() + ")':force_original_aspect_ratio=decrease"
                + ":flags=lanczos:out_color_matrix=bt709:out_range=pc,"
                + "scale=trunc(iw/2)*2:trunc(ih/2)*2"
                + ":flags=lanczos:out_color_matrix=bt709:out_range=pc,"
                + "format=yuv420p";
    }

    /**
     * Same as {@link #scaleFilter()} but with a {@code drawtext} filter
     * appended that bakes the watermark into the top-right corner. If
     * no font is available, returns the plain scale chain (so the
     * pipeline degrades gracefully — the watermark just doesn't appear).
     */
    String scaleFilterWithWatermark(int fontSize) {
        String base = scaleFilter();
        Optional<Path> font = watermarkFont.resolveFontPath();
        if (font.isEmpty() || props.getWatermarkText().isBlank()) {
            return base;
        }
        // ffmpeg filter escaping: ":" is an option separator, "'" ends a quoted
        // segment. Our text is alphanumeric + a dot so the only thing to worry
        // about on the fontfile path is the colon (Windows C:\...) and the
        // backslash (Windows path separator).
        String text = escapeForDrawtext(props.getWatermarkText());
        String fontPath = escapeForDrawtext(font.get().toString());
        return base + ",drawtext=fontfile='" + fontPath
                + "':text='" + text + "'"
                + ":fontcolor=white@" + props.getWatermarkOpacity()
                + ":fontsize=" + fontSize
                + ":x=w-tw-" + props.getWatermarkMargin()
                + ":y=" + props.getWatermarkMargin()
                + ":box=1:boxcolor=" + props.getWatermarkBoxColor()
                + ":boxborderw=" + props.getWatermarkBoxBorder();
    }

    /**
     * Filter chain for the 5 mini 25s preview clips: the base scale
     * chain PLUS a single bottom-center drawtext printing the
     * configured "see more" string (default "See more
     * SpankyCouples.com"). The top-right corner watermark is
     * intentionally NOT included here — those clips are short
     * previews shown on the pipeline page, and the small top-right
     * site name is redundant with the bottom-center call-to-action.
     *
     * <p>Positioning: centered horizontally ({@code x=(w-tw)/2}),
     * pinned to the bottom edge with the same margin used for the
     * corner watermark. Font size defaults to 18 (same as the main
     * watermark) — large enough to read on a 280px-wide preview card,
     * small enough to stay subtle.
     *
     * <p>If the font is unavailable, this falls back to the plain
     * scale filter (no drawtext at all) — same graceful-degrade rule
     * as the corner watermark.
     */
    String scaleFilterWithClipOverlay() {
        String base = scaleFilter();
        Optional<Path> font = watermarkFont.resolveFontPath();
        if (font.isEmpty()
                || props.getClipOverlayText().isBlank()) {
            return base;
        }
        String fontPath = escapeForDrawtext(font.get().toString());
        String overlayText = escapeForDrawtext(props.getClipOverlayText());
        int margin = props.getWatermarkMargin();
        String overlay = ",drawtext=fontfile='" + fontPath
                + "':text='" + overlayText + "'"
                + ":fontcolor=white@" + props.getWatermarkOpacity()
                + ":fontsize=" + props.getClipOverlayFontSize()
                + ":x=(w-tw)/2"
                + ":y=h-th-" + margin
                + ":box=1:boxcolor=" + props.getWatermarkBoxColor()
                + ":boxborderw=" + props.getWatermarkBoxBorder();
        return base + overlay;
    }

    /** Escape characters that have special meaning inside an ffmpeg drawtext value. */
    static String escapeForDrawtext(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case ':' -> sb.append("\\:");
                case '\\' -> sb.append("\\\\");
                case '\'' -> sb.append("\\'");
                case '%' -> sb.append("\\%");
                case '[' -> sb.append("\\[");
                case ']' -> sb.append("\\]");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    FFmpegBuilder buildCompressedJob(Path input, Path output, boolean hasAudio) {
        return buildCompressedJob(input, output, hasAudio, 0.0);
    }

    /**
     * Build the compressed-output job. When {@code skipHeadSeconds > 0},
     * a fast input-side {@code -ss} seek is applied — the encoder jumps
     * to the nearest keyframe at or after that timestamp and re-encodes
     * from there. Fast seek is fine here: the skip target (e.g. 6s for
     * xhamster's intro) is small relative to a typical 5-10 minute
     * source, so any keyframe snap of ±0.5s is well past the ad
     * bumper the user wants to drop.
     */
    FFmpegBuilder buildCompressedJob(Path input, Path output, boolean hasAudio, double skipHeadSeconds) {
        FFmpegBuilder b = new FFmpegBuilder().overrideOutputFiles(true);
        if (skipHeadSeconds > 0.0) {
            // Input-side seek — emit "-ss <sec> -i <input>".
            b.setInput(input.toString())
                    .setStartOffset((long) (skipHeadSeconds * 1000d), TimeUnit.MILLISECONDS);
        } else {
            b.setInput(input.toString());
        }
        FFmpegOutputBuilder o = b.addOutput(output.toString())
                .setVideoFilter(scaleFilterWithWatermark(props.getWatermarkFontSizeMain()))
                .setVideoMovFlags("+faststart");
        encoder().applyVideoFlags(o, new VideoEncoderStrategy.QualitySettings(
                props.getCompressedCrf(), props.getCompressedPreset(), false));
        o.addExtraArgs("-color_range", "pc",
                       "-colorspace", "bt709",
                       "-color_primaries", "bt709",
                       "-color_trc", "bt709");
        if (hasAudio) {
            // 96 kbps AAC is "transparent" for stereo speech/music content
            // indistinguishable from 128 kbps in blind tests, and saves
            // ~0.5 MB per minute of video vs the previous 128 kbps. 64
            // kbps (used for the 5s preview + 25s clips below) is fine
            // for short clips where bitrate matters more than quality.
            o.setAudioCodec("aac").setAudioBitRate(96_000L);
        } else {
            o.disableAudio();
        }
        return b;
    }

    FFmpegBuilder buildPreviewJob(Path input, Path output,
                                  double startSec, double durationSec, boolean hasAudio) {
        FFmpegBuilder b = new FFmpegBuilder().overrideOutputFiles(true);
        b.setInput(input.toString())
                .setStartOffset((long) (startSec * 1000d), TimeUnit.MILLISECONDS);  // input-side seek
        FFmpegOutputBuilder o = b.addOutput(output.toString())
                .setDuration((long) (durationSec * 1000d), TimeUnit.MILLISECONDS)
                .setVideoFilter(scaleFilter())
                .setVideoMovFlags("+faststart");
        encoder().applyVideoFlags(o, new VideoEncoderStrategy.QualitySettings(
                props.getPreviewCrf(), props.getPreviewPreset(), false));
        o.addExtraArgs("-color_range", "pc",
                       "-colorspace", "bt709",
                       "-color_primaries", "bt709",
                       "-color_trc", "bt709");
        if (hasAudio) {
            o.setAudioCodec("aac").setAudioBitRate(64_000L);
        } else {
            o.disableAudio();
        }
        return b;
    }

    FFmpegBuilder buildThumbnailJob(Path input, Path output, double startSec) {
        FFmpegBuilder b = new FFmpegBuilder().overrideOutputFiles(true);
        b.setInput(input.toString())
                .setStartOffset((long) (startSec * 1000d), TimeUnit.MILLISECONDS);
        b.addOutput(output.toString())
                .setFrames(1)
                .setVideoQuality((double) props.getThumbnailQuality());  // -q:v for JPEG
        return b;
    }

    /**
     * Build an FFmpeg job for one of the 5 watermark-baked 25s preview clips.
     * Filter chain is the same as the 5s preview + drawtext watermark +
     * a bottom-center "See more SpankyCouples.com" overlay.
     */
    FFmpegBuilder buildClipJob(Path input, Path output,
                               double startSec, double durationSec, boolean hasAudio) {
        FFmpegBuilder b = new FFmpegBuilder().overrideOutputFiles(true);
        b.setInput(input.toString())
                .setStartOffset((long) (startSec * 1000d), TimeUnit.MILLISECONDS);
        FFmpegOutputBuilder o = b.addOutput(output.toString())
                .setDuration((long) (durationSec * 1000d), TimeUnit.MILLISECONDS)
                .setVideoFilter(scaleFilterWithClipOverlay())
                .setVideoMovFlags("+faststart");
        encoder().applyVideoFlags(o, new VideoEncoderStrategy.QualitySettings(
                props.getPreviewCrf(), props.getPreviewPreset(), false));
        o.addExtraArgs("-color_range", "pc",
                       "-colorspace", "bt709",
                       "-color_primaries", "bt709",
                       "-color_trc", "bt709");
        if (hasAudio) {
            o.setAudioCodec("aac").setAudioBitRate(64_000L);
        } else {
            o.disableAudio();
        }
        return b;
    }

    /**
     * Generate the 5 watermark-baked preview clips and return their paths.
     *
     * <p>Positions default to {@code 0.05, 0.35, 0.50, 0.65, 0.95} of the
     * source duration — 1 from the start, 3 from the middle, 1 from the
     * end. Each clip is exactly {@code app.ffmpeg.preview-clip-seconds}
     * (default 25) long, clamped to fit inside the source.
     */
    public List<Path> generatePreviewClips(Path input, Path outputDir, String baseName,
                                           VideoMetadata meta, boolean hasAudio) throws IOException {
        Files.createDirectories(outputDir);
        double[] positions = props.getPreviewClipPositions();
        int clipDur = props.getPreviewClipSeconds();
        List<Path> out = new ArrayList<>(positions.length);
        for (int i = 0; i < positions.length; i++) {
            double startSec = clampToDuration(meta.durationSeconds() * positions[i],
                                               meta.durationSeconds());
            double actualDur = Math.max(0.5d, Math.min(clipDur,
                    Math.max(0d, meta.durationSeconds() - startSec)));
            Path clipPath = outputDir.resolve(baseName + ".clip" + (i + 1) + ".mp4");
            log.info("ffmpeg clip {} start: input={}, baseName={}, start={}s, dur={}s",
                    i + 1, input, baseName,
                    String.format("%.2f", startSec), String.format("%.2f", actualDur));
            runClip(input, clipPath, startSec, actualDur, hasAudio);
            log.info("ffmpeg clip {} done: {}", i + 1, clipPath);
            out.add(clipPath);
        }
        return out;
    }

    // -------- private runners --------

    private void runCompressed(Path input, Path output, boolean hasAudio) throws IOException {
        runCompressed(input, output, hasAudio, 0.0);
    }

    private void runCompressed(Path input, Path output, boolean hasAudio, double skipHeadSeconds) throws IOException {
        log.info("ffmpeg compressed start: {} (skipHead={}s)", output,
                String.format("%.2f", skipHeadSeconds));
        long t0 = System.currentTimeMillis();
        try {
            ffmpeg.run(buildCompressedJob(input, output, hasAudio, skipHeadSeconds));
        } catch (Exception e) {
            throw new IOException("ffmpeg compressed failed for " + input + " -> " + output, e);
        }
        log.info("ffmpeg compressed done: {} ({} ms)", output, System.currentTimeMillis() - t0);
    }

    private void runPreview(Path input, Path output, double startSec, double durationSec, boolean hasAudio) throws IOException {
        log.info("ffmpeg preview start: {}", output);
        long t0 = System.currentTimeMillis();
        try {
            ffmpeg.run(buildPreviewJob(input, output, startSec, durationSec, hasAudio));
        } catch (Exception e) {
            throw new IOException("ffmpeg preview failed for " + input + " -> " + output, e);
        }
        log.info("ffmpeg preview done: {} ({} ms)", output, System.currentTimeMillis() - t0);
    }

    private void runThumbnail(Path input, Path output, double startSec) throws IOException {
        log.info("ffmpeg thumbnail start: {}", output);
        long t0 = System.currentTimeMillis();
        try {
            ffmpeg.run(buildThumbnailJob(input, output, startSec));
        } catch (Exception e) {
            throw new IOException("ffmpeg thumbnail failed for " + input + " -> " + output, e);
        }
        log.info("ffmpeg thumbnail done: {} ({} ms)", output, System.currentTimeMillis() - t0);
    }

    private void runClip(Path input, Path output, double startSec, double durationSec, boolean hasAudio) throws IOException {
        log.info("ffmpeg clip start: {}", output);
        long t0 = System.currentTimeMillis();
        try {
            ffmpeg.run(buildClipJob(input, output, startSec, durationSec, hasAudio));
        } catch (Exception e) {
            throw new IOException("ffmpeg clip failed for " + input + " -> " + output, e);
        }
        log.info("ffmpeg clip done: {} ({} ms)", output, System.currentTimeMillis() - t0);
    }

    /**
     * Clamp a seek timestamp into {@code [0, duration - 0.1]} so the seek
     * is always inside the source (and ffmpeg has at least a sliver of
     * video to decode). Returns 0 for non-positive durations.
     */
    static double clampToDuration(double t, double duration) {
        if (duration <= 0d) {
            return 0d;
        }
        return Math.max(0d, Math.min(t, Math.max(0d, duration - 0.1d)));
    }
}
