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

    /** Spring constructor — resolves ffmpeg/ffprobe via the configured paths. */
    @Autowired
    public FfmpegDerivativeService(FfmpegProperties props) throws IOException {
        this(props, new FFmpeg(props.getFfmpegPath()), new FFprobe(props.getFfprobePath()));
    }

    /** Test constructor — caller supplies pre-built bramp objects. */
    public FfmpegDerivativeService(FfmpegProperties props, FFmpeg ffmpeg, FFprobe ffprobe) {
        this.props = props;
        this.ffmpeg = ffmpeg;
        this.ffprobe = ffprobe;
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
        return process(input, outputDir, baseName, probe(input));
    }

    /**
     * Process a source video and write the three derivatives into
     * {@code outputDir} using {@code baseName} as the file prefix.
     *
     * <p>{@code precomputed} lets the CLI runner print the probed
     * metadata before kicking off the encode without re-probing.
     */
    public Derivatives process(Path input, Path outputDir, String baseName, VideoMetadata precomputed)
            throws IOException {
        Files.createDirectories(outputDir);

        Path compressedPath = outputDir.resolve(baseName + ".compressed.mp4");
        Path previewPath    = outputDir.resolve(baseName + ".preview.mp4");
        Path thumbnailPath  = outputDir.resolve(baseName + ".thumbnail.jpg");

        double thumbSec   = clampToDuration(precomputed.durationSeconds() * props.getThumbnailPosition(),
                                            precomputed.durationSeconds());
        double previewSec = clampToDuration(precomputed.durationSeconds() * props.getPreviewPosition(),
                                            precomputed.durationSeconds());
        double previewDur = Math.min(
                props.getPreviewDurationSeconds(),
                Math.max(0.5d, precomputed.durationSeconds() - previewSec)
        );

        log.info("ffmpeg derivative start: input={}, baseName={}, duration={}s, hasAudio={}, "
                        + "thumbSec={}, previewSec={}, previewDur={}",
                input, baseName,
                String.format("%.2f", precomputed.durationSeconds()),
                precomputed.hasAudio(),
                String.format("%.2f", thumbSec),
                String.format("%.2f", previewSec),
                String.format("%.2f", previewDur));

        runCompressed(input, compressedPath, precomputed.hasAudio());
        runPreview(input, previewPath, previewSec, previewDur, precomputed.hasAudio());
        runThumbnail(input, thumbnailPath, thumbSec);

        log.info("ffmpeg derivative done: input={}, baseName={}", input, baseName);
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

    FFmpegBuilder buildCompressedJob(Path input, Path output, boolean hasAudio) {
        FFmpegBuilder b = new FFmpegBuilder().overrideOutputFiles(true);
        b.setInput(input.toString());  // input side; no seek
        FFmpegOutputBuilder o = b.addOutput(output.toString())
                .setVideoCodec("libx264")
                .setConstantRateFactor(props.getCompressedCrf())
                .setVideoFilter(scaleFilter())
                .setVideoMovFlags("+faststart")
                .addExtraArgs("-preset", props.getCompressedPreset())
                // Modern ffmpeg (>=4.4) writes no color metadata by default.
                // Players then assume "tv / limited range" (16-235) and clip
                // the bright values → washed-out / over-saturated picture.
                // Force BT.709 / pc (full) range on the output so it matches
                // the common-web default and renders identically to the source.
                .addExtraArgs("-color_range", "pc",
                              "-colorspace", "bt709",
                              "-color_primaries", "bt709",
                              "-color_trc", "bt709");
        if (hasAudio) {
            o.setAudioCodec("aac").setAudioBitRate(128_000L);
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
                .setVideoCodec("libx264")
                .setConstantRateFactor(props.getPreviewCrf())
                .setVideoFilter(scaleFilter())
                .setVideoMovFlags("+faststart")
                .addExtraArgs("-preset", props.getPreviewPreset())
                // See buildCompressedJob for why these matter.
                .addExtraArgs("-color_range", "pc",
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

    // -------- private runners --------

    private void runCompressed(Path input, Path output, boolean hasAudio) throws IOException {
        log.info("ffmpeg compressed start: {}", output);
        long t0 = System.currentTimeMillis();
        try {
            ffmpeg.run(buildCompressedJob(input, output, hasAudio));
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
