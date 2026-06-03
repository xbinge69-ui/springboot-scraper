package com.example.scraper.controller;

import com.example.scraper.model.ScrapedItem;
import com.example.scraper.model.DirectVideoIngestRequest;
import com.example.scraper.model.PipelineOutcome;
import com.example.scraper.model.PipelineRequest;
import com.example.scraper.model.VideoResult;
import com.example.scraper.service.DirectVideoIngestService;
import com.example.scraper.service.ScraperService;
import com.example.scraper.service.VideoIngestionPipelineService;
import com.example.scraper.service.VideoScraperService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@Controller
public class ScraperController {

    private final ScraperService scraperService;
    private final VideoScraperService videoScraperService;
    private final VideoIngestionPipelineService videoIngestionPipelineService;
    private final DirectVideoIngestService directVideoIngestService;

    public ScraperController(ScraperService scraperService,
                             VideoScraperService videoScraperService,
                             VideoIngestionPipelineService videoIngestionPipelineService,
                             DirectVideoIngestService directVideoIngestService) {
        this.scraperService = scraperService;
        this.videoScraperService = videoScraperService;
        this.videoIngestionPipelineService = videoIngestionPipelineService;
        this.directVideoIngestService = directVideoIngestService;
    }

    @GetMapping("/")
    public String index() {
        return "index";
    }

    @PostMapping("/scrape")
    public String scrape(@RequestParam("url") String url, Model model) {
        // Basic validation: only allow http/https URLs
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

    @PostMapping("/api/video/pipeline")
    @ResponseBody
    public PipelineOutcome ingestFirstVideo(@RequestBody PipelineRequest request) throws IOException {
        return videoIngestionPipelineService.ingestFirstVideo(request);
    }

    @PostMapping("/api/video/ingest-file")
    @ResponseBody
    public PipelineOutcome ingestDirectVideoFile(
            @RequestPart("videoFile") MultipartFile videoFile,
            @RequestPart("title") String title,
            @RequestPart(value = "description", required = false) String description,
            @RequestPart(value = "category", required = false) String category,
            @RequestPart(value = "tags", required = false) String tags,
            @RequestPart(value = "unknownActressName", required = false) String unknownActressName,
            @RequestPart(value = "actressId", required = false) String actressId) throws IOException {
        DirectVideoIngestRequest metadata = new DirectVideoIngestRequest();
        metadata.setTitle(title);
        metadata.setDescription(description);
        metadata.setCategory(category);
        metadata.setTags(tags);
        metadata.setUnknownActressName(unknownActressName);
        metadata.setActressId(actressId);
        return directVideoIngestService.ingestDirectVideoFile(videoFile, metadata);
    }
}
