package com.example.scraper.controller;

import com.example.scraper.model.PageInfo;
import com.example.scraper.model.PipelineOutcome;
import com.example.scraper.model.PipelineRequest;
import com.example.scraper.model.ScrapedItem;
import com.example.scraper.model.VideoResult;
import com.example.scraper.service.PageInfoService;
import com.example.scraper.service.PipelineJobService;
import com.example.scraper.service.ScraperService;
import com.example.scraper.service.OllamaService;
import com.example.scraper.service.VideoEnrichmentService;
import com.example.scraper.service.VideoIngestionPipelineService;
import com.example.scraper.service.VideoScraperService;
import com.example.scraper.video.Codec;
import com.example.scraper.video.EnrichmentMetadata;
import com.example.scraper.video.EnrichmentSource;
import com.example.scraper.video.GpuEncoderProbe;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Controller
public class ScraperController {

    private final ScraperService scraperService;
    private final VideoScraperService videoScraperService;
    private final VideoIngestionPipelineService videoIngestionPipelineService;
    private final VideoEnrichmentService videoEnrichmentService;
    private final PageInfoService pageInfoService;
    private final OllamaService ollama;
    private final com.example.scraper.service.llm.MinimaxChatProvider minimax;
    private final PipelineJobService pipelineJobService;
    private final GpuEncoderProbe gpuEncoderProbe;
    private final com.example.scraper.video.FfmpegProperties ffmpegProperties;

    public ScraperController(ScraperService scraperService,
                             VideoScraperService videoScraperService,
                             VideoIngestionPipelineService videoIngestionPipelineService,
                             VideoEnrichmentService videoEnrichmentService,
                             PageInfoService pageInfoService,
                             OllamaService ollama,
                             com.example.scraper.service.llm.MinimaxChatProvider minimax,
                             PipelineJobService pipelineJobService,
                             GpuEncoderProbe gpuEncoderProbe,
                             com.example.scraper.video.FfmpegProperties ffmpegProperties) {
        this.scraperService = scraperService;
        this.videoScraperService = videoScraperService;
        this.videoIngestionPipelineService = videoIngestionPipelineService;
        this.videoEnrichmentService = videoEnrichmentService;
        this.pageInfoService = pageInfoService;
        this.ollama = ollama;
        this.minimax = minimax;
        this.pipelineJobService = pipelineJobService;
        this.gpuEncoderProbe = gpuEncoderProbe;
        this.ffmpegProperties = ffmpegProperties;
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

    @GetMapping("/video/pipeline/batch")
    public String videoPipelineBatchForm() {
        return "video-pipeline-batch";
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
     * Async submit. Validates the request, creates a {@link com.example.scraper.model.PipelineJob},
     * hands the work to {@link com.example.scraper.service.PipelineJobService}, and
     * returns the jobId + a status URL the UI can poll.
     *
     * <p>Status code is {@code 202 Accepted} (the work is in progress, not done).
     */
    @PostMapping("/api/video/pipeline")
    @ResponseBody
    public ResponseEntity<java.util.Map<String, String>> ingestFirstVideoAsync(@RequestBody PipelineRequest request) {
        validatePipelineRequest(request);
        com.example.scraper.model.PipelineJob job = pipelineJobService.create("single");
        java.util.Map<String, String> body = new java.util.LinkedHashMap<>();
        body.put("jobId", job.getJobId());
        body.put("statusUrl", "/api/video/pipeline/status/" + job.getJobId());
        body.put("previewClipsUrlPrefix", "/api/video/preview-clips/" + job.getJobId() + "/");
        pipelineJobService.submit(() -> runSingleJob(job, request));
        return ResponseEntity.status(202).body(body);
    }

    /**
     * Polled by the pipeline page every ~600ms. Returns the job snapshot
     * (status / progress / step / error). When {@code status == DONE}
     * the response includes the full {@link PipelineOutcome} under
     * {@code result}.
     */
    @GetMapping("/api/video/pipeline/status/{jobId}")
    @ResponseBody
    public ResponseEntity<java.util.Map<String, Object>> jobStatus(@PathVariable("jobId") String jobId) {
        return pipelineJobService.get(jobId)
                .map(this::toStatusPayload)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(404)
                        .body(java.util.Map.of("error", "Unknown jobId: " + jobId)));
    }

    /** Translate a {@link com.example.scraper.model.PipelineJob} into the JSON the UI consumes. */
    private java.util.Map<String, Object> toStatusPayload(com.example.scraper.model.PipelineJob job) {
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("jobId", job.getJobId());
        out.put("status", job.getStatus().name());
        out.put("progress", job.getProgress());
        out.put("step", job.getStep());
        if (job.getError() != null) {
            out.put("error", job.getError());
        }
        if (job.getStatus() == com.example.scraper.model.PipelineJob.Status.DONE) {
            out.put("result", job.getResult());
            // Also include the local file names for the 5 clips so the UI
            // can build <video> tags without an extra request.
            out.put("previewClipFilenames",
                    job.getPreviewClipPaths().stream().map(p -> p.getFileName().toString()).toList());
        }
        return out;
    }

    /** Worker run by the executor for the single-pipeline endpoint. */
    private void runSingleJob(com.example.scraper.model.PipelineJob job, PipelineRequest request) {
        Path previewsDir = null;
        try {
            previewsDir = pipelineJobService.previewsDirFor(job.getJobId());
            PipelineOutcome outcome = videoIngestionPipelineService.ingestFromPageUrl(request, job, previewsDir);
            // Re-collect the clip paths that were generated, in case the
            // pipeline's reference list is empty after the call returns.
            java.util.List<Path> clipPaths = new java.util.ArrayList<>();
            if (previewsDir != null && Files.isDirectory(previewsDir)) {
                try (java.util.stream.Stream<Path> walk = Files.list(previewsDir)) {
                    walk.filter(p -> p.getFileName().toString().endsWith(".mp4"))
                            .sorted()
                            .forEach(clipPaths::add);
                }
            }
            job.markDone(outcome, clipPaths);
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            job.markFailed(msg);
        }
    }

    private void validatePipelineRequest(PipelineRequest request) {
        if (request == null || request.getSourcePageUrl() == null
                || request.getSourcePageUrl().isBlank()) {
            throw new IllegalArgumentException("sourcePageUrl is required");
        }
        String url = request.getSourcePageUrl().trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw new IllegalArgumentException("sourcePageUrl must start with http:// or https://");
        }
    }

    /**
     * Non-obligatory pre-fill step. Fetches the source URL and returns
     * the best-effort extracted title / description / tags / category /
     * actress. The pipeline page uses this to populate its form when the
     * user clicks "Grab Info".
     */
    @PostMapping("/api/video/page-info")
    @ResponseBody
    public PageInfo grabPageInfo(@RequestBody Map<String, String> body) throws IOException {
        String url = body == null ? null : body.get("sourcePageUrl");
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("sourcePageUrl is required");
        }
        return pageInfoService.grab(url.trim());
    }

    /**
     * Reports which LLM providers are configured / reachable. Used by
     * the pipeline page to disable the "Use MiniMax" checkbox when the
     * server has no API key, and to render a status line.
     */
    @GetMapping("/api/llm/status")
    @ResponseBody
    public java.util.Map<String, Object> llmStatus() {
        boolean ollamaUp = ollamaServiceAvailable();
        boolean minimaxConfigured = minimax != null && minimax.isAvailable();
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("ollama", java.util.Map.of("name", "ollama", "available", ollamaUp));
        out.put("minimax", java.util.Map.of(
                "name", "minimax-chat",
                "available", minimaxConfigured,
                // Surfaced verbatim to the UI so the user knows whether to
                // set the env var, flip `app.minimax.enabled`, or both.
                "reason", minimaxConfigured
                        ? "configured"
                        : (minimax == null
                            ? "not on classpath"
                            : minimax.unavailableReason())));
        out.put("defaultProvider", minimaxConfigured ? "minimax-chat" : "ollama");
        return out;
    }

    /**
     * Active probe of the MiniMax provider. Sends a 1-token ping and
     * returns MiniMax's raw status_code + status_msg from
     * {@code base_resp}, plus a 60-char snippet of the model reply when
     * the call succeeds. Use this when auth fails — it tells you
     * whether the key is bad, the model name is bad, the endpoint is
     * wrong, or MiniMax is down.
     */
    @GetMapping("/api/llm/minimax/test")
    @ResponseBody
    public java.util.Map<String, Object> testMinimax() {
        if (minimax == null) {
            return java.util.Map.of(
                    "ok", false,
                    "message", "MinimaxChatProvider not on the classpath");
        }
        com.example.scraper.service.llm.MinimaxChatProvider.PingResult r = minimax.ping();
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("ok", r.ok());
        out.put("httpStatus", r.httpStatus());
        out.put("baseRespCode", r.baseRespCode());
        out.put("baseRespMsg", r.baseRespMsg());
        out.put("replySnippet", r.replySnippet());
        out.put("latencyMs", r.latencyMs());
        out.put("context", r.context());
        return out;
    }

    /**
     * Active probe of the local Ollama installation. Lists the pulled
     * models, checks whether the configured model is among them, and
     * suggests the {@code ollama pull <name>} command when it isn't.
     * Use this when a pipeline run logs "model 'xxx' not found" — the
     * browser answer is the same info, no shell required.
     */
    @GetMapping("/api/llm/ollama/test")
    @ResponseBody
    public java.util.Map<String, Object> testOllama() {
        com.example.scraper.service.OllamaService.OllamaProbe p = ollama.probe();
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("ok", p.reachable && p.modelPresent);
        out.put("reachable", p.reachable);
        out.put("ollamaUrl", p.ollamaUrl);
        out.put("configuredModel", p.configuredModel);
        out.put("modelPresent", p.modelPresent);
        out.put("matchedAs", p.matchedAs);
        out.put("availableModels", p.availableModels);
        out.put("latencyMs", p.latencyMs);
        out.put("error", p.error);
        out.put("suggestedCommand", p.suggestedCommand);
        if (!p.reachable) {
            out.put("message", "Ollama is not reachable at " + p.ollamaUrl
                    + (p.error == null ? "" : " — " + p.error));
        } else if (!p.modelPresent) {
            out.put("message", "Model '" + p.configuredModel
                    + "' is not pulled. Run: " + p.suggestedCommand);
        } else {
            out.put("message", "Ollama OK — model '" + p.matchedAs + "' is ready");
        }
        return out;
    }

    /**
     * Diagnostic snapshot of the ffmpeg GPU encoder probe. Returns the
     * ffmpeg binary path, whether GPU is enabled, the full list of GPU
     * encoders ffmpeg exposes, and the per-codec selection the probe
     * will use. The probe runs once at {@code @PostConstruct} time
     * and this endpoint just reads the cached result — no subprocess
     * spawn, no re-probe, cheap to call.
     *
     * <p>Used by the pipeline page's "GPU status" panel so the user
     * can see, at a glance, whether their ffmpeg build supports NVENC
     * / QSV / AMF and which one will be used for the next encode.
     */
    @GetMapping("/api/ffmpeg/test")
    @ResponseBody
    public java.util.Map<String, Object> testFfmpeg() {
        GpuEncoderProbe.Snapshot snap = gpuEncoderProbe.snapshot();

        // Render the per-codec selection into a JSON-friendly map.
        java.util.Map<String, Object> selectedMap = new java.util.LinkedHashMap<>();
        for (Codec codec : Codec.values()) {
            GpuEncoderProbe.Detection d = snap.selectedByCodec().get(codec);
            if (d != null) {
                java.util.Map<String, Object> entry = new java.util.LinkedHashMap<>();
                entry.put("vendor", d.vendor.name());
                entry.put("encoder", d.encoderName);
                selectedMap.put(codec.label, entry);
            } else {
                selectedMap.put(codec.label, null);
            }
        }
        // Plain list of (vendor, codec, encoder) for the UI to render.
        java.util.List<java.util.Map<String, Object>> availableList = new java.util.ArrayList<>();
        for (GpuEncoderProbe.Detection d : snap.available()) {
            java.util.Map<String, Object> entry = new java.util.LinkedHashMap<>();
            entry.put("vendor", d.vendor.name());
            entry.put("codec", d.codec.label);
            entry.put("encoder", d.encoderName);
            availableList.add(entry);
        }

        // The actual codec the next encode will use + the encoder name
        // the orchestrator will write to the catalog. Lets the UI say
        // "next encode: hevc_nvenc (GPU)" or "next encode: libx264
        // (CPU) — no GPU encoder found" without ambiguity.
        Codec activeCodec = ffmpegProperties.getCodec();
        GpuEncoderProbe.Detection activePick = snap.selectedByCodec().get(activeCodec);
        String nextEncoder = activePick != null ? activePick.encoderName : activeCodec.softwareEncoder;
        boolean nextIsGpu = activePick != null;

        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("ok", nextIsGpu || !snap.available().isEmpty());
        out.put("ffmpegPath", snap.ffmpegPath());
        out.put("gpuEnabled", snap.gpuEnabled());
        out.put("preferenceOrder", snap.preferenceOrder());
        out.put("activeCodec", activeCodec.label);
        out.put("nextEncoder", nextEncoder);
        out.put("nextIsGpu", nextIsGpu);
        out.put("available", availableList);
        out.put("selected", selectedMap);
        if (!snap.gpuEnabled()) {
            out.put("message", "GPU encoding disabled by config (app.ffmpeg.gpu.enabled=false) — will use "
                    + activeCodec.softwareEncoder);
        } else if (snap.available().isEmpty()) {
            out.put("message", "ffmpeg at '" + snap.ffmpegPath() + "' exposes NO hardware H.264/HEVC "
                    + "encoders — install an ffmpeg build with NVENC/QSV/AMF (e.g. BtbN's gpl build) to enable GPU");
        } else if (activePick == null) {
            out.put("message", "GPU encoders found (" + snap.available().size()
                    + ") but none for the active codec (" + activeCodec.label + "). Try setting "
                    + "app.ffmpeg.codec=h264 to use the H.264 GPU encoders instead.");
        } else {
            out.put("message", "Will encode with " + nextEncoder + " (GPU) for codec=" + activeCodec.label);
        }
        return out;
    }

    private boolean ollamaServiceAvailable() {
        try {
            // OllamaService.isOllamaAvailable() does the actual probe.
            return ollama != null && ollama.isOllamaAvailable();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Batch mode: accepts a list of source page URLs. For each URL we
     * (1) auto-grab info, (2) merge with any per-URL overrides, and
     * (3) run the full pipeline. Returns a JSON array of catalog
     * entries (one per processed URL). Failed entries are returned
     * with a populated {@code error} field instead of the entry.
     *
     * <p>The optional top-level {@code useMinimax} flag (or per-URL
     * override) routes the LLM call to MiniMax for that entry.
     */
    @PostMapping("/api/video/pipeline/batch")
    @ResponseBody
    public List<Map<String, Object>> ingestBatch(@RequestBody BatchPipelineRequest body) {
        if (body == null || body.urls() == null || body.urls().isEmpty()) {
            throw new IllegalArgumentException("urls is required (non-empty list)");
        }
        boolean useMinimax = Boolean.TRUE.equals(body.useMinimax());
        boolean skipLlm = Boolean.TRUE.equals(body.skipLlm());
        List<Map<String, Object>> results = new ArrayList<>();
        for (String url : body.urls()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("sourcePageUrl", url);
            if (url == null || url.isBlank()
                    || (!url.startsWith("http://") && !url.startsWith("https://"))) {
                row.put("error", "Invalid URL");
                results.add(row);
                continue;
            }
            try {
                // Step 1: auto-grab info
                PageInfo info = pageInfoService.grab(url);
                // Step 2: build the pipeline request from the grabbed info
                PipelineRequest req = new PipelineRequest();
                req.setSourcePageUrl(url);
                req.setTitle(info.getTitle());
                req.setDescription(info.getDescription());
                req.setTags(String.join(",", info.getTags()));
                req.setCategory(info.getCategory().isBlank() ? "Amateur" : info.getCategory());
                // Prefer the first performer from the typed list (xhamster
                // provides pornstars with avatars). Fall back to the legacy
                // single-actress field, then "Anonymous".
                String firstPerformer = info.getPornstars().isEmpty()
                        ? info.getActress()
                        : info.getPornstars().get(0);
                req.setUnknownActressName(firstPerformer.isBlank() ? "Anonymous" : firstPerformer);
                req.setActressId(null);
                req.setViews(0L);
                req.setUseMinimax(useMinimax);
                req.setSkipLlm(skipLlm);
                // Step 3: run the pipeline
                PipelineOutcome outcome = videoIngestionPipelineService.ingestFromPageUrl(req);
                row.put("entry", outcome.getEntry());
                row.put("warnings", outcome.getWarnings());
                row.put("stats", outcome.getStats());
            } catch (Exception e) {
                row.put("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
            results.add(row);
        }
        return results;
    }

    /** Request body for {@code POST /api/video/pipeline/batch}. */
    public record BatchPipelineRequest(List<String> urls, Boolean useMinimax, Boolean skipLlm) {}

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
            return videoEnrichmentService.enrich(new EnrichmentSource.LocalFile(tmp, videoFile.getSize()), meta, false);
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
            return videoEnrichmentService.enrich(source, meta, false);
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
