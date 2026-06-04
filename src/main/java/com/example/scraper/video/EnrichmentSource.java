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

    /**
     * Source is a remote URL that still needs to be downloaded. The
     * optional {@code referer} is sent as the {@code Referer} header
     * (and {@code Origin} derived from it) to satisfy CDNs that require
     * a same-origin referer (erome, doodstream, etc.). If null, the
     * video URL's own origin is used.
     */
    record RemoteUrl(String url, String referer) implements EnrichmentSource {
        public RemoteUrl(String url) { this(url, null); }
    }
}
