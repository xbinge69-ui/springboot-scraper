package com.example.scraper.video;

import java.nio.file.Path;

/**
 * Paths to the three derivative files produced by
 * {@link FfmpegDerivativeService#process}. Returned together so a caller
 * can fan them out (e.g. upload all three) atomically.
 */
public record Derivatives(Path compressedPath, Path previewPath, Path thumbnailPath) {
}
