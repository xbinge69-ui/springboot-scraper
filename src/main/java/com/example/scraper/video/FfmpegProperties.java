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
            @Value("${app.ffmpeg.thumbnail-quality:2}") int thumbnailQuality) {
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
}
