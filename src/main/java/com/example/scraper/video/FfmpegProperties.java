package com.example.scraper.video;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Holds the 12 {@code app.ffmpeg.*} configuration values used by
 * {@link FfmpegDerivativeService}. Wired via {@code @Value} to match the
 * convention used by every other service in the project
 * (see {@code BunnyAssetService}, {@code OllamaService}, etc.); the
 * codebase does not use {@code @ConfigurationProperties} anywhere.
 *
 * <p>Tests can call the same constructor directly with literal values —
 * the {@code @Value} annotations are only consulted by Spring at
 * injection time, not at compile or runtime.
 */
@Component
public class FfmpegProperties {

    private final String ffmpegPath;
    private final String ffprobePath;
    private final int previewDurationSeconds;
    private final double thumbnailPosition;
    private final double previewPosition;
    private final int targetMaxWidth;
    private final int targetMaxHeight;
    private final int previewCrf;
    private final String previewPreset;
    private final int compressedCrf;
    private final String compressedPreset;
    private final int thumbnailQuality;
    // Watermark
    private final String watermarkText;
    private final int watermarkFontSizeMain;
    private final int watermarkFontSizeClip;
    private final double watermarkOpacity;
    private final String watermarkBoxColor;
    private final int watermarkBoxBorder;
    private final int watermarkMargin;
    // Bottom-center overlay baked into the 5 preview clips only (the
    // shorter 5s preview + the full compressed video do NOT get this
    // text — the request was specifically about the 5 mini clips).
    private final String clipOverlayText;
    private final int clipOverlayFontSize;
    // Preview clips (5×25s)
    private final int previewClipSeconds;
    private final double[] previewClipPositions;
    // GPU acceleration
    private final boolean gpuEnabled;
    private final String gpuPreference;
    // Codec (H.264 or HEVC)
    private final Codec codec;

    public FfmpegProperties(
            @Value("${app.ffmpeg.path:ffmpeg}") String ffmpegPath,
            @Value("${app.ffprobe.path:ffprobe}") String ffprobePath,
            @Value("${app.ffmpeg.preview-duration-seconds:5}") int previewDurationSeconds,
            @Value("${app.ffmpeg.thumbnail-position:0.20}") double thumbnailPosition,
            @Value("${app.ffmpeg.preview-position:0.40}") double previewPosition,
            @Value("${app.ffmpeg.target-max-width:1280}") int targetMaxWidth,
            @Value("${app.ffmpeg.target-max-height:720}") int targetMaxHeight,
            @Value("${app.ffmpeg.preview-crf:28}") int previewCrf,
            @Value("${app.ffmpeg.preview-preset:fast}") String previewPreset,
            @Value("${app.ffmpeg.compressed-crf:23}") int compressedCrf,
            @Value("${app.ffmpeg.compressed-preset:medium}") String compressedPreset,
            @Value("${app.ffmpeg.thumbnail-quality:2}") int thumbnailQuality,
            @Value("${app.ffmpeg.watermark.text:Spankycouples.com}") String watermarkText,
            @Value("${app.ffmpeg.watermark.font-size-main:18}") int watermarkFontSizeMain,
            @Value("${app.ffmpeg.watermark.font-size-clip:24}") int watermarkFontSizeClip,
            @Value("${app.ffmpeg.watermark.opacity:0.85}") double watermarkOpacity,
            @Value("${app.ffmpeg.watermark.box-color:black@0.4}") String watermarkBoxColor,
            @Value("${app.ffmpeg.watermark.box-border:6}") int watermarkBoxBorder,
            @Value("${app.ffmpeg.watermark.margin:12}") int watermarkMargin,
            @Value("${app.ffmpeg.clip-overlay.text:See more SpankyCouples.com}") String clipOverlayText,
            @Value("${app.ffmpeg.clip-overlay.font-size:18}") int clipOverlayFontSize,
            @Value("${app.ffmpeg.preview-clip-seconds:25}") int previewClipSeconds,
            @Value("${app.ffmpeg.preview-clip-positions:0.05,0.35,0.50,0.65,0.95}") double[] previewClipPositions,
            @Value("${app.ffmpeg.gpu.enabled:true}") boolean gpuEnabled,
            @Value("${app.ffmpeg.gpu.preference:nvidia,intel,amd}") String gpuPreference,
            @Value("${app.ffmpeg.codec:h264}") String codec) {
        this.ffmpegPath = ffmpegPath;
        this.ffprobePath = ffprobePath;
        this.previewDurationSeconds = previewDurationSeconds;
        this.thumbnailPosition = thumbnailPosition;
        this.previewPosition = previewPosition;
        this.targetMaxWidth = targetMaxWidth;
        this.targetMaxHeight = targetMaxHeight;
        this.previewCrf = previewCrf;
        this.previewPreset = previewPreset;
        this.compressedCrf = compressedCrf;
        this.compressedPreset = compressedPreset;
        this.thumbnailQuality = thumbnailQuality;
        this.watermarkText = watermarkText == null ? "" : watermarkText;
        this.watermarkFontSizeMain = watermarkFontSizeMain;
        this.watermarkFontSizeClip = watermarkFontSizeClip;
        this.watermarkOpacity = watermarkOpacity;
        this.watermarkBoxColor = watermarkBoxColor == null ? "black@0.4" : watermarkBoxColor;
        this.watermarkBoxBorder = watermarkBoxBorder;
        this.watermarkMargin = watermarkMargin;
        this.clipOverlayText = clipOverlayText == null ? "" : clipOverlayText;
        this.clipOverlayFontSize = clipOverlayFontSize;
        this.previewClipSeconds = previewClipSeconds;
        this.previewClipPositions = previewClipPositions == null || previewClipPositions.length == 0
                ? new double[]{0.05, 0.35, 0.50, 0.65, 0.95}
                : previewClipPositions;
        this.gpuEnabled = gpuEnabled;
        this.gpuPreference = gpuPreference == null ? "nvidia,intel,amd" : gpuPreference;
        this.codec = Codec.fromConfig(codec);
    }

    public String getFfmpegPath() { return ffmpegPath; }
    public String getFfprobePath() { return ffprobePath; }
    public int getPreviewDurationSeconds() { return previewDurationSeconds; }
    public double getThumbnailPosition() { return thumbnailPosition; }
    public double getPreviewPosition() { return previewPosition; }
    public int getTargetMaxWidth() { return targetMaxWidth; }
    public int getTargetMaxHeight() { return targetMaxHeight; }
    public int getPreviewCrf() { return previewCrf; }
    public String getPreviewPreset() { return previewPreset; }
    public int getCompressedCrf() { return compressedCrf; }
    public String getCompressedPreset() { return compressedPreset; }
    public int getThumbnailQuality() { return thumbnailQuality; }
    public String getWatermarkText() { return watermarkText; }
    public int getWatermarkFontSizeMain() { return watermarkFontSizeMain; }
    public int getWatermarkFontSizeClip() { return watermarkFontSizeClip; }
    public double getWatermarkOpacity() { return watermarkOpacity; }
    public String getWatermarkBoxColor() { return watermarkBoxColor; }
    public int getWatermarkBoxBorder() { return watermarkBoxBorder; }
    public int getWatermarkMargin() { return watermarkMargin; }
    public String getClipOverlayText() { return clipOverlayText; }
    public int getClipOverlayFontSize() { return clipOverlayFontSize; }
    public int getPreviewClipSeconds() { return previewClipSeconds; }
    public double[] getPreviewClipPositions() { return previewClipPositions; }
    public boolean isGpuEnabled() { return gpuEnabled; }
    public String getGpuPreference() { return gpuPreference; }
    public Codec getCodec() { return codec; }
}
