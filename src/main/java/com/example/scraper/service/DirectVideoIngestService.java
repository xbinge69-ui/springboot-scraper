package com.example.scraper.service;

import com.example.scraper.model.DirectVideoIngestRequest;
import com.example.scraper.model.PipelineOutcome;
import com.example.scraper.model.VideoCatalogEntry;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

@Service
public class DirectVideoIngestService {

    private final VideoHostUploadService videoHostUploadService;
    private final BunnyAssetService bunnyAssetService;
    private final MediaDerivativeService mediaDerivativeService;
    private final VideoCatalogService videoCatalogService;
    private final VideoEnricherService videoEnricherService;

    public DirectVideoIngestService(VideoHostUploadService videoHostUploadService,
                                   BunnyAssetService bunnyAssetService,
                                   MediaDerivativeService mediaDerivativeService,
                                   VideoCatalogService videoCatalogService,
                                   VideoEnricherService videoEnricherService) {
        this.videoHostUploadService = videoHostUploadService;
        this.bunnyAssetService = bunnyAssetService;
        this.mediaDerivativeService = mediaDerivativeService;
        this.videoCatalogService = videoCatalogService;
        this.videoEnricherService = videoEnricherService;
    }

    public PipelineOutcome ingestDirectVideoFile(MultipartFile videoFile,
                                                  DirectVideoIngestRequest metadata) throws IOException {
        // Validate
        if (videoFile == null || videoFile.isEmpty()) {
            throw new IllegalArgumentException("videoFile is required.");
        }
        if (metadata == null || isBlank(metadata.getTitle())) {
            throw new IllegalArgumentException("title is required.");
        }

        String title = metadata.getTitle().trim();
        String slug = slugify(title);
        List<String> warnings = new ArrayList<>();

        // Read video bytes
        byte[] videoBytes = videoFile.getBytes();

        // Upload to embed hosts (mock)
        String embedUrl = videoHostUploadService.uploadToDoodstream(
                "file://" + videoFile.getOriginalFilename(),
                slug);
        String backupEmbedUrl = videoHostUploadService.uploadToVidara(
                "file://" + videoFile.getOriginalFilename(),
                slug);

        // Generate and upload assets
        String thumbnailUrl;
        String previewUrl;
        try {
            byte[] jpgCandidate = Arrays.copyOf(videoBytes, Math.min(180_000, videoBytes.length));
            byte[] mp4Candidate = Arrays.copyOf(videoBytes, Math.min(1_600_000, videoBytes.length));
            thumbnailUrl = bunnyAssetService.uploadThumbnail(jpgCandidate, slug);
            previewUrl = bunnyAssetService.uploadPreview(mp4Candidate, slug);
            warnings.add("Derivatives generated in lightweight mode. Replace with ffmpeg for real thumbnail/preview quality.");
        } catch (Exception e) {
            thumbnailUrl = embedUrl;
            previewUrl = embedUrl;
            warnings.add("Asset generation failed; using embed URL as fallback: " + e.getMessage());
        }

        // Build catalog entry
        VideoCatalogEntry entry = new VideoCatalogEntry();
        entry.setSlug(slug);
        entry.setTitle(title);

        // Enrich description and tags using Ollama LLM
        String enrichedDescription = videoEnricherService.generateDescription(
                title,
                isBlank(metadata.getDescription()) ? null : metadata.getDescription().trim());
        entry.setDescription(enrichedDescription);

        List<String> enrichedTags = videoEnricherService.generateTags(title, enrichedDescription);
        entry.setTags(enrichedTags);

        entry.setDurationSeconds(0);
        entry.setThumbnailKey(thumbnailUrl);
        entry.setPreviewUrl(previewUrl);
        entry.setEmbedUrl(embedUrl);
        entry.setBackupEmbedUrl(backupEmbedUrl);
        entry.setCategory(isBlank(metadata.getCategory()) ? "Uncategorized" : metadata.getCategory().trim());
        entry.setPublishedAt(Instant.now().toString());
        entry.setActressId(isBlank(metadata.getActressId()) ? null : metadata.getActressId().trim());
        entry.setUnknownActressName(isBlank(metadata.getUnknownActressName()) ? "Unknown" : metadata.getUnknownActressName().trim());
        entry.setViews(0);

        VideoCatalogEntry saved = videoCatalogService.append(entry);
        return new PipelineOutcome(saved, warnings);
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
