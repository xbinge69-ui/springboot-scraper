package com.example.scraper.video;

import java.nio.file.Path;

/**
 * Where the source video comes from for an enrichment request. Sealed
 * because the service is a sum type with two valid cases: a local file
 * (already on disk after a multipart {@code transferTo} or a successful
 * URL download) or a remote URL that still needs to be downloaded.
 *
 * <p>{@code LocalFile} is the "ready" case; {@code RemoteUrl} is the
 * "needs download" case. The service routes the URL through
 * {@link com.example.scraper.util.HttpVideoDownloader} and promotes it
 * to a {@code LocalFile} before ffmpeg runs.
 */
public sealed interface EnrichmentSource {

    /**
     * Source is already a local file on disk. {@code sizeBytes} is the
     * known size (used for byte-cap accounting) or {@code -1} if unknown.
     */
    record LocalFile(Path path, long sizeBytes) implements EnrichmentSource {}

    /** Source is a remote URL that still needs to be downloaded. */
    record RemoteUrl(String url) implements EnrichmentSource {}
}
