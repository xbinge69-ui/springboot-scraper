package com.example.scraper.video;

import net.bramp.ffmpeg.builder.FFmpegOutputBuilder;

/**
 * Concrete {@link VideoEncoderStrategy} implementations, one per backend.
 * Kept in a single file because they're tiny and tightly related.
 */
final class VideoEncoderStrategies {

    private VideoEncoderStrategies() {}

    // ------------------------------------------------------------------
    // libx264 (CPU, H.264 default)
    // ------------------------------------------------------------------
    static final class LibX264 implements VideoEncoderStrategy {
        @Override public String name() { return "libx264"; }
        @Override public boolean isHardwareAccelerated() { return false; }
        @Override public Codec codec() { return Codec.H264; }
        @Override public void applyVideoFlags(FFmpegOutputBuilder o, QualitySettings qs) {
            // Use addExtraArgs for -preset, not bramp's setVideoPreset —
            // setVideoPreset emits the legacy "-vpre" flag which modern
            // ffmpeg removed years ago.
            o.setVideoCodec("libx264")
             .setConstantRateFactor(qs.crf())
             .addExtraArgs("-preset", qs.preset() == null ? "medium" : qs.preset());
        }
    }

    // ------------------------------------------------------------------
    // h264_nvenc (NVIDIA, H.264)
    //
    // -preset p1..p7   (p1 = fastest/lowest quality, p7 = slowest/best)
    // -rc vbr          (constant-quality mode)
    // -cq N            (constant-quality target, mapped from CRF)
    // -b:v 0           (required with -rc vbr to let -cq control quality)
    //
    // AQ settings: temporal-aq=1, no spatial-aq. The matching HEVC
    // strategy had to disable spatial-aq to avoid all-black output
    // (the same aq-strength 8 was crushing dark macroblocks); the
    // H.264 encoder is more forgiving but we keep the conservative
    // settings here too for consistency.
    // ------------------------------------------------------------------
    static final class H264Nvenc implements VideoEncoderStrategy {
        @Override public String name() { return "h264_nvenc"; }
        @Override public boolean isHardwareAccelerated() { return true; }
        @Override public Codec codec() { return Codec.H264; }
        @Override public void applyVideoFlags(FFmpegOutputBuilder o, QualitySettings qs) {
            int cq = Math.max(0, qs.crf() - 2);  // NVENC is ~2 points "sharper" than x264 at same CRF
            String preset = mapNvencPreset(qs.preset());
            o.setVideoCodec("h264_nvenc")
             .addExtraArgs("-preset", preset,
                           "-rc", "vbr",
                           "-cq", Integer.toString(cq),
                           "-b:v", "0",
                           "-temporal-aq", "1");
        }
        private static String mapNvencPreset(String p) {
            if (p == null) return "p4";
            return switch (p.toLowerCase()) {
                case "ultrafast", "veryfast", "fast" -> "p1";
                case "medium"                          -> "p4";
                case "slow"                            -> "p6";
                case "slower", "veryslow"              -> "p7";
                default -> p.startsWith("p") ? p : "p4";
            };
        }
    }

    // ------------------------------------------------------------------
    // h264_qsv (Intel, H.264)
    //
    // -preset veryfast|fast|medium|slow|veryslow
    // -global_quality N  (ICQ mode, 1=best 51=worst — invert the CRF)
    // ------------------------------------------------------------------
    static final class H264Qsv implements VideoEncoderStrategy {
        @Override public String name() { return "h264_qsv"; }
        @Override public boolean isHardwareAccelerated() { return true; }
        @Override public Codec codec() { return Codec.H264; }
        @Override public void applyVideoFlags(FFmpegOutputBuilder o, QualitySettings qs) {
            int gq = Math.max(1, Math.min(51, 52 - qs.crf()));  // invert so higher CRF = lower quality
            String preset = mapQsvPreset(qs.preset());
            o.setVideoCodec("h264_qsv")
             .addExtraArgs("-preset", preset,
                           "-global_quality", Integer.toString(gq),
                           "-look_ahead", "1");
        }
        private static String mapQsvPreset(String p) {
            if (p == null) return "medium";
            return switch (p.toLowerCase()) {
                case "ultrafast" -> "veryfast";
                case "veryfast"  -> "veryfast";
                case "fast"      -> "fast";
                case "medium"    -> "medium";
                case "slow"      -> "slow";
                case "slower", "veryslow" -> "veryslow";
                default -> p;
            };
        }
    }

    // ------------------------------------------------------------------
    // h264_amf (AMD, H.264, Windows)
    //
    // -quality quality|balanced|speed
    // -rc cqp        (constant-QP mode)
    // -qp N          (QP target; AMF CQP roughly maps to x264 CRF + 2)
    // ------------------------------------------------------------------
    static final class H264Amf implements VideoEncoderStrategy {
        @Override public String name() { return "h264_amf"; }
        @Override public boolean isHardwareAccelerated() { return true; }
        @Override public Codec codec() { return Codec.H264; }
        @Override public void applyVideoFlags(FFmpegOutputBuilder o, QualitySettings qs) {
            int qp = qs.crf() + 2;  // AMF CQP is on a slightly different curve
            String quality = mapAmfQuality(qs.preset());
            o.setVideoCodec("h264_amf")
             .addExtraArgs("-quality", quality,
                           "-rc", "cqp",
                           "-qp", Integer.toString(qp));
        }
        private static String mapAmfQuality(String p) {
            if (p == null) return "balanced";
            return switch (p.toLowerCase()) {
                case "ultrafast", "veryfast", "fast" -> "speed";
                case "medium"    -> "balanced";
                case "slow", "slower", "veryslow" -> "quality";
                default -> p;
            };
        }
    }

    // ==================================================================
    // HEVC / H.265 strategies
    //
    // Quality knobs differ from H.264:
    //   libx265 uses the same CRF concept but on a different scale.
    //   Empirically, x265_crf ≈ x264_crf + 5 for the same visual quality.
    //   So if you've been using x264 CRF 28, set x265 CRF 33.
    //   The orchestrator just passes the configured CRF through; the
    //   user is responsible for picking a codec-appropriate value.
    // ==================================================================

    // ------------------------------------------------------------------
    // libx265 (CPU, HEVC)
    // ------------------------------------------------------------------
    static final class LibX265 implements VideoEncoderStrategy {
        @Override public String name() { return "libx265"; }
        @Override public boolean isHardwareAccelerated() { return false; }
        @Override public Codec codec() { return Codec.HEVC; }
        @Override public void applyVideoFlags(FFmpegOutputBuilder o, QualitySettings qs) {
            String preset = qs.preset() == null ? "medium" : qs.preset();
            // libx265 doesn't accept every x264 preset name — map the
            // common ones across. Unknown presets are passed through
            // (libx265 will error out at ffmpeg runtime if invalid).
            String mapped = switch (preset.toLowerCase()) {
                case "ultrafast" -> "ultrafast";
                case "veryfast"  -> "veryfast";
                case "fast"      -> "fast";
                case "medium"    -> "medium";
                case "slow"      -> "slow";
                case "slower"    -> "slower";
                case "veryslow"  -> "veryslow";
                default          -> preset;
            };
            o.setVideoCodec("libx265")
             .setConstantRateFactor(qs.crf())
             .addExtraArgs("-preset", mapped,
                           // x265 has its own "tune" vocabulary. None
                           // of the x264 tune values carry over, so we
                           // just don't set -tune. Default is "psnr"-
                           // oriented; -tune ssim is the closest to
                           // "perceptual" but adds encode time. Skip
                           // unless the user wants it.
                           "-tag:v", "hvc1");  // hvc1 = Apple/Safari-friendly tag
        }
    }

    // ------------------------------------------------------------------
    // hevc_nvenc (NVIDIA, HEVC)
    //
    // ⚠ AQ settings matter. The matching H.264 strategy uses
    //   "-spatial-aq 1 -aq-strength 8" with no issue, but on HEVC NVENC
    //   the same values crush dark macroblocks to zero — output plays
    //   audio but every frame is solid black. We disable spatial-aq
    //   entirely and keep temporal-aq at the NVENC default of 1. The
    //   result is slightly larger files than the H.264 strategy, but
    //   the video is actually visible.
    // ------------------------------------------------------------------
    static final class HevcNvenc implements VideoEncoderStrategy {
        @Override public String name() { return "hevc_nvenc"; }
        @Override public boolean isHardwareAccelerated() { return true; }
        @Override public Codec codec() { return Codec.HEVC; }
        @Override public void applyVideoFlags(FFmpegOutputBuilder o, QualitySettings qs) {
            int cq = Math.max(0, qs.crf() - 2);
            String preset = mapNvencPreset(qs.preset());
            o.setVideoCodec("hevc_nvenc")
             .addExtraArgs("-preset", preset,
                           "-rc", "vbr",
                           "-cq", Integer.toString(cq),
                           "-b:v", "0",
                           "-tag:v", "hvc1",
                           // No -spatial-aq / -aq-strength here — see
                           // the class-level comment. The defaults
                           // (temporal-aq=1, no spatial-aq) produce
                           // correctly-decodable output.
                           "-temporal-aq", "1");
        }
        private static String mapNvencPreset(String p) {
            if (p == null) return "p4";
            return switch (p.toLowerCase()) {
                case "ultrafast", "veryfast", "fast" -> "p1";
                case "medium"                          -> "p4";
                case "slow"                            -> "p6";
                case "slower", "veryslow"              -> "p7";
                default -> p.startsWith("p") ? p : "p4";
            };
        }
    }

    // ------------------------------------------------------------------
    // hevc_qsv (Intel, HEVC)
    // ------------------------------------------------------------------
    static final class HevcQsv implements VideoEncoderStrategy {
        @Override public String name() { return "hevc_qsv"; }
        @Override public boolean isHardwareAccelerated() { return true; }
        @Override public Codec codec() { return Codec.HEVC; }
        @Override public void applyVideoFlags(FFmpegOutputBuilder o, QualitySettings qs) {
            int gq = Math.max(1, Math.min(51, 52 - qs.crf()));
            String preset = mapQsvPreset(qs.preset());
            o.setVideoCodec("hevc_qsv")
             .addExtraArgs("-preset", preset,
                           "-global_quality", Integer.toString(gq),
                           "-look_ahead", "1",
                           "-tag:v", "hvc1");
        }
        private static String mapQsvPreset(String p) {
            if (p == null) return "medium";
            return switch (p.toLowerCase()) {
                case "ultrafast" -> "veryfast";
                case "veryfast"  -> "veryfast";
                case "fast"      -> "fast";
                case "medium"    -> "medium";
                case "slow"      -> "slow";
                case "slower", "veryslow" -> "veryslow";
                default -> p;
            };
        }
    }

    // ------------------------------------------------------------------
    // hevc_amf (AMD, HEVC, Windows)
    // ------------------------------------------------------------------
    static final class HevcAmf implements VideoEncoderStrategy {
        @Override public String name() { return "hevc_amf"; }
        @Override public boolean isHardwareAccelerated() { return true; }
        @Override public Codec codec() { return Codec.HEVC; }
        @Override public void applyVideoFlags(FFmpegOutputBuilder o, QualitySettings qs) {
            int qp = qs.crf() + 2;
            String quality = mapAmfQuality(qs.preset());
            o.setVideoCodec("hevc_amf")
             .addExtraArgs("-quality", quality,
                           "-rc", "cqp",
                           "-qp", Integer.toString(qp),
                           "-tag:v", "hvc1");
        }
        private static String mapAmfQuality(String p) {
            if (p == null) return "balanced";
            return switch (p.toLowerCase()) {
                case "ultrafast", "veryfast", "fast" -> "speed";
                case "medium"    -> "balanced";
                case "slow", "slower", "veryslow" -> "quality";
                default -> p;
            };
        }
    }

    // ------------------------------------------------------------------
    // Factory
    // ------------------------------------------------------------------

    /** CPU fallback for the given codec. */
    static VideoEncoderStrategy forCodec(Codec codec) {
        return switch (codec) {
            case H264 -> new LibX264();
            case HEVC -> new LibX265();
        };
    }

    /**
     * GPU strategy for the given codec. The {@link GpuEncoderProbe.Detection}
     * already knows the encoder name, so this just dispatches to the
     * matching strategy class.
     */
    static VideoEncoderStrategy forGpu(GpuEncoderProbe.Detection d) {
        if (d == null) return forCodec(Codec.H264);
        return switch (d.codec) {
            case H264 -> switch (d.vendor) {
                case NVIDIA -> new H264Nvenc();
                case INTEL  -> new H264Qsv();
                case AMD    -> new H264Amf();
            };
            case HEVC -> switch (d.vendor) {
                case NVIDIA -> new HevcNvenc();
                case INTEL  -> new HevcQsv();
                case AMD    -> new HevcAmf();
            };
        };
    }
}
