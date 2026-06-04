package com.example.scraper.video;

/**
 * Probed metadata of a single source video, sufficient to drive the
 * derivative pipeline (compressed 720p re-encode, 5-second preview clip,
 * 20%/40%-of-duration thumbnail).
 *
 * @param width            source video width in pixels (0 if no video stream found)
 * @param height           source video height in pixels
 * @param durationSeconds  source duration in seconds (0 if unknown)
 * @param hasAudio         true if the source contains an audio stream
 */
public record VideoMetadata(int width, int height, double durationSeconds, boolean hasAudio) {
}
