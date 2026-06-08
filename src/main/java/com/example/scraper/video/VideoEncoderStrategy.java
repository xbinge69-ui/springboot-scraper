package com.example.scraper.video;

import net.bramp.ffmpeg.builder.FFmpegOutputBuilder;

/**
 * Encapsulates the per-encoder ffmpeg flags so the derivative jobs
 * can stay clean. One implementation per backend:
 * <ul>
 *   <li>{@link LibX264Encoder} — CPU, default fallback</li>
 *   <li>{@link H264NvencEncoder} — NVIDIA GPU</li>
 *   <li>{@link H264QsvEncoder} — Intel GPU</li>
 *   <li>{@link H264AmfEncoder} — AMD GPU (Windows)</li>
 * </ul>
 *
 * <p>Each implementation knows how to translate the project's
 * "compressed CRF" / "preview CRF" into the encoder's preferred
 * quality knob (CRF, CQP, global_quality, etc.) and what preset
 * string to pass.
 */
public interface VideoEncoderStrategy {

    /** Human-readable name, e.g. "libx264", "h264_nvenc", "libx265", "hevc_nvenc". */
    String name();

    /** True when GPU hardware acceleration is in use. */
    boolean isHardwareAccelerated();

    /** Which codec this strategy implements. */
    Codec codec();

    /**
     * Apply the encoder's video-side flags to the FFmpeg output builder.
     * The caller has already set the video filter and any common flags
     * (movflags, color metadata, etc.) — this method only handles the
     * codec + quality + preset triple.
     */
    void applyVideoFlags(FFmpegOutputBuilder o, QualitySettings qs);

    /** Quality knobs. */
    record QualitySettings(int crf, String preset, boolean twoPass) {}
}
