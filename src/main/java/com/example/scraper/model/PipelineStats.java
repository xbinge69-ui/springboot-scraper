package com.example.scraper.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Bytes-in / bytes-out + encoder info for one pipeline run. Returned in
 * {@link PipelineOutcome} so the UI can render the before/after
 * compression numbers and confirm which video encoder was actually used
 * (libx264 CPU vs h264_nvenc/h264_qsv/h264_amf GPU).
 *
 * <p>The fields are also persisted to {@link VideoCatalogEntry} so the
 * catalog JSON keeps a permanent record of how much the encoder saved
 * for each video — handy for tracking whether the GPU is still being
 * used as drivers change.
 *
 * <p>All byte counts are -1 when the corresponding file doesn't exist
 * (e.g. a clip was suppressed because the source had no audio and
 * the audio-free fallback was used).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PipelineStats {

    /** Size of the source video the user uploaded or downloaded. */
    private long inputBytes = -1L;

    /** Size of the watermarked compressed video uploaded to Bunny. */
    private long compressedBytes = -1L;

    /** Size of the 5-second preview clip. */
    private long previewBytes = -1L;

    /** Size of the JPEG thumbnail. */
    private long thumbnailBytes = -1L;

    /** Combined size of all 5 watermark-baked preview clips (if generated). */
    private long previewClipsTotalBytes = -1L;

    /** Number of preview clips written (typically 5, may be 0 when the job didn't request them). */
    private int previewClipCount = 0;

    /** Source video duration in seconds (echo of {@code durationSeconds} for convenience). */
    private double inputDurationSeconds = -1d;

    /**
     * ffmpeg video encoder that produced the compressed/preview files.
     * One of {@code libx264} (CPU), {@code h264_nvenc} (NVIDIA GPU),
     * {@code h264_qsv} (Intel GPU), {@code h264_amf} (AMD GPU).
     */
    private String videoEncoder = "libx264";

    /** True when {@link #videoEncoder} is a hardware-accelerated backend. */
    private boolean hardwareAccelerated;

    public PipelineStats() {}

    public long getInputBytes() { return inputBytes; }
    public void setInputBytes(long inputBytes) { this.inputBytes = inputBytes; }

    public long getCompressedBytes() { return compressedBytes; }
    public void setCompressedBytes(long compressedBytes) { this.compressedBytes = compressedBytes; }

    public long getPreviewBytes() { return previewBytes; }
    public void setPreviewBytes(long previewBytes) { this.previewBytes = previewBytes; }

    public long getThumbnailBytes() { return thumbnailBytes; }
    public void setThumbnailBytes(long thumbnailBytes) { this.thumbnailBytes = thumbnailBytes; }

    public long getPreviewClipsTotalBytes() { return previewClipsTotalBytes; }
    public void setPreviewClipsTotalBytes(long previewClipsTotalBytes) { this.previewClipsTotalBytes = previewClipsTotalBytes; }

    public int getPreviewClipCount() { return previewClipCount; }
    public void setPreviewClipCount(int previewClipCount) { this.previewClipCount = previewClipCount; }

    public double getInputDurationSeconds() { return inputDurationSeconds; }
    public void setInputDurationSeconds(double inputDurationSeconds) { this.inputDurationSeconds = inputDurationSeconds; }

    public String getVideoEncoder() { return videoEncoder; }
    public void setVideoEncoder(String videoEncoder) { this.videoEncoder = videoEncoder == null ? "libx264" : videoEncoder; }

    public boolean isHardwareAccelerated() { return hardwareAccelerated; }
    public void setHardwareAccelerated(boolean hardwareAccelerated) { this.hardwareAccelerated = hardwareAccelerated; }

    /** Compression ratio = inputBytes / compressedBytes, or 0 when either is non-positive. */
    public double compressionRatio() {
        if (inputBytes <= 0 || compressedBytes <= 0) return 0d;
        return (double) inputBytes / (double) compressedBytes;
    }

    /** Percentage of input size the compressed output is, rounded to one decimal. */
    public double compressedPctOfInput() {
        if (inputBytes <= 0 || compressedBytes <= 0) return 0d;
        return Math.round((double) compressedBytes * 1000d / (double) inputBytes) / 10d;
    }
}
