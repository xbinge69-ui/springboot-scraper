package com.example.scraper.controller;

import com.example.scraper.model.PipelineOutcome;
import com.example.scraper.model.PipelineRequest;
import com.example.scraper.model.ScrapedItem;
import com.example.scraper.model.VideoResult;
import com.example.scraper.service.ScraperService;
import com.example.scraper.service.VideoEnrichmentService;
import com.example.scraper.service.VideoIngestionPipelineService;
import com.example.scraper.service.VideoScraperService;
import com.example.scraper.video.EnrichmentMetadata;
import com.example.scraper.video.EnrichmentSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Controller
public class ScraperController {

    private final ScraperService scraperService;
    private final VideoScraperService videoScraperService;
    private final VideoIngestionPipelineService videoIngestionPipelineService;
    private final VideoEnrichmentService videoEnrichmentService;

    public ScraperController(ScraperService scraperService,
                             VideoScraperService videoScraperService,
                             VideoIngestionPipelineService videoIngestionPipelineService,
                             VideoEnrichmentService videoEnrichmentService) {
        this.scraperService = scraperService;
        this.videoScraperService = videoScraperService;
        this.videoIngestionPipelineService = videoIngestionPipelineService;
        this.videoEnrichmentService = videoEnrichmentService;
    }

    @GetMapping("/")
    public String index() {
        return "index";
    }

    @PostMapping("/scrape")
    public String scrape(@RequestParam("url") String url, Model model) {
        if (url == null || (!url.startsWith("http://") && !url.startsWith("https://"))) {
            model.addAttribute("error", "URL invalide. Veuillez entrer une URL commençant par http:// ou https://");
            return "index";
        }

        try {
            String pageTitle = scraperService.getPageTitle(url);
            List<ScrapedItem> items = scraperService.scrape(url);

            model.addAttribute("url", url);
            model.addAttribute("pageTitle", pageTitle);
            model.addAttribute("items", items);
            model.addAttribute("count", items.size());
        } catch (IOException e) {
            model.addAttribute("error", "Erreur lors du scraping : " + e.getMessage());
            return "index";
        }

        return "result";
    }

    // ------------------------------------------------------------------
    // Video scraping routes
    // ------------------------------------------------------------------

    @GetMapping("/video")
    public String videoForm(Model model) {
        model.addAttribute("supportedSites", videoScraperService.supportedSiteNames());
        return "video-form";
    }

    @GetMapping("/video/pipeline")
    public String videoPipelineForm() {
        return "video-pipeline";
    }

    @GetMapping("/video/upload")
    public String videoUploadForm() {
        return "video-upload";
    }

    /** Map controller-level argument errors to 400 with a JSON body. */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseBody
    public ResponseEntity<java.util.Map<String, String>> handleIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(java.util.Map.of("error", e.getMessage()));
    }

    @PostMapping("/video/scrape")
    public String videoScrape(@RequestParam("url") String url, Model model) {
        if (url == null || (!url.startsWith("http://") && !url.startsWith("https://"))) {
            model.addAttribute("error", "URL invalide. Veuillez entrer une URL commençant par http:// ou https://");
            model.addAttribute("supportedSites", videoScraperService.supportedSiteNames());
            return "video-form";
        }

        try {
            VideoResult result = videoScraperService.extractVideos(url);
            model.addAttribute("result", result);
        } catch (IllegalArgumentException e) {
            model.addAttribute("error", e.getMessage());
            model.addAttribute("supportedSites", videoScraperService.supportedSiteNames());
            return "video-form";
        } catch (IOException e) {
            model.addAttribute("error", "Erreur réseau : " + e.getMessage());
            model.addAttribute("supportedSites", videoScraperService.supportedSiteNames());
            return "video-form";
        }

        return "video-result";
    }

    // ------------------------------------------------------------------
    // API endpoints
    // ------------------------------------------------------------------

    /**
     * Page-URL → first-video-URL → enrich. Kept for backwards compat with
     * the existing JSON-shape callers; internally it just delegates to
     * the new pipeline.
     */
    @PostMapping("/api/video/pipeline")
    @ResponseBody
    public PipelineOutcome ingestFirstVideo(@RequestBody PipelineRequest request) throws IOException {
        return videoIngestionPipelineService.ingestFromPageUrl(request);
    }

    /**
     * Multipart upload → enrich. The uploaded file is streamed to a temp
     * path via {@code transferTo} (no {@code getBytes()}) so 2 GB
     * uploads don't OOM.
     */
    @PostMapping(value = "/api/video/ingest-file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseBody
    public PipelineOutcome ingestDirectVideoFile(
            @RequestPart("videoFile") MultipartFile videoFile,
            @RequestParam("title") String title,
            @RequestParam(value = "description", required = false) String description,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "tags", required = false) String tags,
            @RequestParam(value = "unknownActressName", required = false) String unknownActressName,
            @RequestParam(value = "actressId", required = false) String actressId) throws IOException {

        Path tmp = Files.createTempFile("scraper-enrich-upload-", ".bin");
        try {
            videoFile.transferTo(tmp);
            EnrichmentMetadata meta = new EnrichmentMetadata(
                    title, description, category,
                    VideoEnrichmentService.parseTagsCsv(tags),
                    unknownActressName, actressId);
            return videoEnrichmentService.enrich(new EnrichmentSource.LocalFile(tmp, videoFile.getSize()), meta);
        } catch (IOException | RuntimeException e) {
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
            throw e;
        }
        // The orchestrator's EnrichmentTempFiles handles cleanup of `tmp` on the
        // happy path; this catch covers the case where enrich() throws before
        // the orchestrator gets to register `tmp` (e.g. invalid source arg).
    }

    /**
     * New endpoint. Multipart form. Accepts either a file upload or a
     * remote URL (exactly one of the two is required). Streams the
     * uploaded file to a temp path, downloads the URL to a temp path,
     * and runs the full enrich pipeline.
     */
    @PostMapping(value = "/api/video/enrich", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseBody
    public PipelineOutcome enrich(
            @RequestParam("title") String title,
            @RequestParam(value = "description", required = false) String description,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "tags", required = false) String tags,
            @RequestParam(value = "unknownActressName", required = false) String unknownActressName,
            @RequestParam(value = "actressId", required = false) String actressId,
            @RequestPart(value = "videoFile", required = false) MultipartFile videoFile,
            @RequestParam(value = "videoUrl", required = false) String videoUrl) throws IOException {

        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title is required");
        }
        boolean hasFile = videoFile != null && !videoFile.isEmpty();
        boolean hasUrl  = videoUrl != null && !videoUrl.isBlank();
        if (hasFile == hasUrl) {
            throw new IllegalArgumentException(
                    hasFile ? "Pass either videoFile OR videoUrl, not both"
                            : "One of videoFile or videoUrl is required");
        }

        EnrichmentSource source;
        Path callerTmp = null;
        try {
            if (hasFile) {
                callerTmp = Files.createTempFile("scraper-enrich-upload-", ".bin");
                videoFile.transferTo(callerTmp);
                source = new EnrichmentSource.LocalFile(callerTmp, videoFile.getSize());
            } else {
                source = new EnrichmentSource.RemoteUrl(videoUrl);
            }
            EnrichmentMetadata meta = new EnrichmentMetadata(
                    title, description, category,
                    VideoEnrichmentService.parseTagsCsv(tags),
                    unknownActressName, actressId);
            return videoEnrichmentService.enrich(source, meta);
        } catch (IOException | RuntimeException e) {
            // If we managed to create a caller-side temp file (the upload case) and
            // the orchestrator never got to clean it up, remove it now.
            if (callerTmp != null) {
                try { Files.deleteIfExists(callerTmp); } catch (IOException ignored) {}
            }
            throw e;
        }
    }
}
