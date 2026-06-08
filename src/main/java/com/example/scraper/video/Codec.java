package com.example.scraper.video;

/**
 * Video codec to use for the compressed output + preview + preview-clip
 * derivatives. H.264 (libx264 / NVENC / QSV / AMF) is the universally-
 * compatible default. HEVC (libx265 / hevc_nvenc / hevc_qsv / hevc_amf)
 * is ~30-40% more efficient at the same visual quality — the same CRF
 * ladder delivers noticeably smaller files — but has wider compatibility
 * gaps (Safari and some smart TVs refuse to play it without licensing).
 *
 * <p>Configurable via {@code app.ffmpeg.codec} in
 * {@code application.properties}. Defaults to H.264 for maximum
 * compatibility.
 */
public enum Codec {

    /** H.264 / AVC — universal compatibility. */
    H264("h264", "libx264", "h264_nvenc", "h264_qsv", "h264_amf"),

    /** H.265 / HEVC — ~30-40% smaller at the same quality, narrower compatibility. */
    HEVC("hevc", "libx265", "hevc_nvenc", "hevc_qsv", "hevc_amf");

    /** Lowercase label used in config / logs (matches the {@code ffmpeg -codecs} name). */
    public final String label;
    /** CPU encoder name (always available if ffmpeg was built with it). */
    public final String softwareEncoder;
    /** NVIDIA GPU encoder name ({@code null} if no NVIDIA build available). */
    public final String nvencEncoder;
    /** Intel GPU encoder name. */
    public final String qsvEncoder;
    /** AMD GPU encoder name (Windows). */
    public final String amfEncoder;

    Codec(String label, String sw, String nv, String qsv, String amf) {
        this.label = label;
        this.softwareEncoder = sw;
        this.nvencEncoder = nv;
        this.qsvEncoder = qsv;
        this.amfEncoder = amf;
    }

    /**
     * Parse the {@code app.ffmpeg.codec} value. Tolerant: case-insensitive,
     * accepts {@code "h264"}, {@code "H.264"}, {@code "avc"}, {@code "hevc"},
     * {@code "h265"}, {@code "H.265"}. Falls back to H.264 on anything
     * unrecognized (rather than throwing — a typo should never brick the
     * whole pipeline).
     */
    public static Codec fromConfig(String raw) {
        if (raw == null) return H264;
        String s = raw.trim().toLowerCase(java.util.Locale.ROOT).replace(".", "").replace("-", "");
        if (s.isEmpty()) return H264;
        return switch (s) {
            case "h264", "avc"   -> H264;
            case "hevc", "h265"  -> HEVC;
            default              -> H264;
        };
    }
}
