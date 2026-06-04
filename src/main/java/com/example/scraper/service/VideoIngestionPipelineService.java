package com.example.scraper.service;

import com.example.scraper.model.PipelineOutcome;
import com.example.scraper.model.PipelineRequest;
import com.example.scraper.model.VideoResult;
import com.example.scraper.video.EnrichmentMetadata;
import com.example.scraper.video.EnrichmentSource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Page-URL → first-video-URL → enrich. Slim shim that keeps the
 * {@code POST /api/video/pipeline} JSON-shape contract for backwards
 * compatibility while delegating the actual ffmpeg/bunny/ollama work
 * to {@link VideoEnrichmentService}.
 */
@Service
public class VideoIngestionPipelineService {

    private final VideoScraperService videoScraperService;
    private final VideoEnrichmentService videoEnrichmentService;

    public VideoIngestionPipelineService(VideoScraperService videoScraperService,
                                         VideoEnrichmentService videoEnrichmentService) {
        this.videoScraperService = videoScraperService;
        this.videoEnrichmentService = videoEnrichmentService;
    }

    public PipelineOutcome ingestFromPageUrl(PipelineRequest request) throws IOException {
        validateRequest(request);

        VideoResult scraped = videoScraperService.extractVideos(request.getSourcePageUrl());
        String sourceVideoUrl = scraped.firstVideoUrl();
        if (sourceVideoUrl == null || sourceVideoUrl.isBlank()) {
            throw new IllegalArgumentException("Aucune video detectee sur la page source.");
        }

        String effectiveTitle = isBlank(request.getTitle())
                ? (isBlank(scraped.pageTitle()) ? "Untitled Video" : scraped.pageTitle().trim())
                : request.getTitle().trim();

        EnrichmentMetadata meta = new EnrichmentMetadata(
                effectiveTitle,
                request.getDescription(),
                request.getCategory(),
                parseTags(request.getTags()),
                request.getUnknownActressName(),
                request.getActressId());

        return videoEnrichmentService.enrich(
                new EnrichmentSource.RemoteUrl(sourceVideoUrl, request.getSourcePageUrl()),
                meta);
    }

    private void validateRequest(PipelineRequest request) {
        if (request == null || isBlank(request.getSourcePageUrl())) {
            throw new IllegalArgumentException("sourcePageUrl is required.");
        }
        String url = request.getSourcePageUrl().trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw new IllegalArgumentException("sourcePageUrl must start with http:// or https://");
        }
    }

    private List<String> parseTags(String tags) {
        if (isBlank(tags)) {
            return new ArrayList<>();
        }
        return Arrays.stream(tags.split(","))
                .map(String::trim)
                .filter(t -> !t.isBlank())
                .distinct()
                .collect(Collectors.toList());
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
