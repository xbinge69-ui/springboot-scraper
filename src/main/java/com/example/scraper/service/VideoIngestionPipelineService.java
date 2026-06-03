package com.example.scraper.service;

import com.example.scraper.model.PipelineOutcome;
import com.example.scraper.model.PipelineRequest;
import com.example.scraper.model.VideoCatalogEntry;
import com.example.scraper.model.VideoResult;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

@Service
public class VideoIngestionPipelineService {

    private final VideoScraperService videoScraperService;
    private final VideoHostUploadService videoHostUploadService;
    private final BunnyAssetService bunnyAssetService;
    private final MediaDerivativeService mediaDerivativeService;
    private final VideoCatalogService videoCatalogService;

    public VideoIngestionPipelineService(VideoScraperService videoScraperService,
                                         VideoHostUploadService videoHostUploadService,
                                         BunnyAssetService bunnyAssetService,
                                         MediaDerivativeService mediaDerivativeService,
                                         VideoCatalogService videoCatalogService) {
        this.videoScraperService = videoScraperService;
        this.videoHostUploadService = videoHostUploadService;
        this.bunnyAssetService = bunnyAssetService;
        this.mediaDerivativeService = mediaDerivativeService;
        this.videoCatalogService = videoCatalogService;
    }

    public PipelineOutcome ingestFirstVideo(PipelineRequest request) throws IOException {
        validateRequest(request);

        List<String> warnings = new ArrayList<>();
        VideoResult scraped = videoScraperService.extractVideos(request.getSourcePageUrl());
        String sourceVideoUrl = scraped.firstVideoUrl();
        if (sourceVideoUrl == null || sourceVideoUrl.isBlank()) {
            throw new IllegalArgumentException("Aucune video detectee sur la page source.");
        }

        String effectiveTitle = isBlank(request.getTitle()) ? defaultTitle(scraped) : request.getTitle().trim();
        String slug = slugify(effectiveTitle);

        String embedUrl = videoHostUploadService.uploadToDoodstream(sourceVideoUrl, slug);
        String backupEmbedUrl = videoHostUploadService.uploadToVidara(sourceVideoUrl, slug);

        String thumbnailUrl;
        String previewUrl;
        try {
            byte[] jpgCandidate = mediaDerivativeService.downloadFirstBytes(sourceVideoUrl, 180_000);
            byte[] mp4Candidate = mediaDerivativeService.downloadFirstBytes(sourceVideoUrl, 1_600_000);
            thumbnailUrl = bunnyAssetService.uploadThumbnail(jpgCandidate, slug);
            previewUrl = bunnyAssetService.uploadPreview(mp4Candidate, slug);
            warnings.add("Derivatives generated in lightweight mode. Replace with ffmpeg for real thumbnail/preview quality.");
        } catch (Exception e) {
            thumbnailUrl = sourceVideoUrl;
            previewUrl = sourceVideoUrl;
            warnings.add("Thumbnail/preview generation fallback used: " + e.getMessage());
        }

        VideoCatalogEntry entry = new VideoCatalogEntry();
        entry.setSlug(slug);
        entry.setTitle(effectiveTitle);
        entry.setDescription(isBlank(request.getDescription()) ? "" : request.getDescription().trim());
        entry.setDurationSeconds(0);
        entry.setThumbnailKey(thumbnailUrl);
        entry.setPreviewUrl(previewUrl);
        entry.setEmbedUrl(embedUrl);
        entry.setBackupEmbedUrl(backupEmbedUrl);
        entry.setTags(parseTags(request.getTags()));
        entry.setCategory(isBlank(request.getCategory()) ? "Uncategorized" : request.getCategory().trim());
        entry.setPublishedAt(Instant.now().toString());
        entry.setActressId(isBlank(request.getActressId()) ? null : request.getActressId().trim());
        entry.setUnknownActressName(isBlank(request.getUnknownActressName()) ? "Unknown" : request.getUnknownActressName().trim());
        entry.setViews(request.getViews() == null ? 0 : request.getViews());

        VideoCatalogEntry saved = videoCatalogService.append(entry);
        return new PipelineOutcome(saved, warnings);
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

    private String defaultTitle(VideoResult scraped) {
        if (!isBlank(scraped.pageTitle())) {
            return scraped.pageTitle().trim();
        }
        return "Untitled Video";
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

    private String slugify(String input) {
        String normalized = input.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        return normalized.isBlank() ? "video" : normalized;
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
