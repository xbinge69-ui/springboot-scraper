package com.example.scraper.service;

import com.example.scraper.model.PropagationJob;
import com.example.scraper.model.VideoCatalogEntry;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Async orchestrator that, after a batch pipeline run completes in
 * springboot-scraper, takes the successful entries and pushes them
 * into project-b's {@code src/data/videos.json} by invoking the
 * {@code add-video-entry} Claude skill via the Anthropic API.
 *
 * <h3>Flow per batch</h3>
 * <ol>
 *   <li>Controller calls {@link #propagate(List, String)}; we create a
 *       {@link PropagationJob}, store it, and submit a runnable to the
 *       dedicated executor. Returns the jobId immediately.</li>
 *   <li>For each chunk of {@code chunk-size} entries, build a JSON
 *       payload (dropping {@code stats} — pipeline metadata — and
 *       keeping {@code unknownActressName} as a signal), POST to
 *       Anthropic with the cached {@code SKILL.md} as the system
 *       prompt, parse the response (stripping markdown fences if
 *       present) and accumulate the normalized entries.</li>
 *   <li>After all chunks are processed, acquire the class-level
 *       {@link #WRITE_LOCK}, read-merge-write project-b's
 *       {@code videos.json} atomically (mirror of
 *       {@link VideoCatalogService#writeAll}), and mark the job
 *       DONE / PARTIAL / FAILED.</li>
 * </ol>
 *
 * <h3>Concurrency</h3>
 * The executor is a fixed-size 2-thread pool with daemon threads named
 * {@code add-video-entry-N}, mirroring {@link PipelineJobService}. The
 * read-merge-write of project-b's {@code videos.json} is guarded by a
 * class-level {@code WRITE_LOCK} so two concurrent batches don't
 * trample each other's entries.
 *
 * <h3>Failure handling</h3>
 * Per-chunk failures are recorded on the job but do not abort sibling
 * chunks — partial success is the rule. If the final atomic write
 * fails, the job is marked FAILED and the partial chunks' entries are
 * dropped (not partially committed) so re-running the batch is safe.
 */
@Service
public class AddVideoEntryPropagationService {

    private static final Logger log = LoggerFactory.getLogger(AddVideoEntryPropagationService.class);

    /** Class-level lock guarding the read-merge-write of project-b's videos.json. */
    private static final Object WRITE_LOCK = new Object();

    /** Markdown fence extractor — handles ```json ... ``` and bare ``` ... ```. */
    private static final Pattern JSON_FENCE = Pattern.compile(
            "```(?:json|JSON)?\\s*\\n?(.*?)\\n?```", Pattern.DOTALL);
    /** JSON-array extractor — finds the first [...] block in the text. */
    private static final Pattern JSON_ARRAY = Pattern.compile("(?s)\\[.*\\]");

    private final AnthropicMessagesClient anthropic;
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);
    private final ObjectMapper parser = new ObjectMapper();

    private final boolean enabled;
    private final String skillPath;
    private final String videosJsonPath;
    private final int chunkSize;
    private final Path videosJson;

    /** Cached system prompt — SKILL.md content + a small override paragraph. */
    private volatile String systemPrompt;

    private final Map<String, PropagationJob> jobs = new ConcurrentHashMap<>();
    private final ExecutorService executor =
            Executors.newFixedThreadPool(2, r -> {
                Thread t = new Thread(r, "add-video-entry");
                t.setDaemon(true);
                return t;
            });

    public AddVideoEntryPropagationService(
            AnthropicMessagesClient anthropic,
            @Value("${app.add-video-entry.enabled:false}") boolean enabled,
            @Value("${app.add-video-entry.skill-path:C:/Git/project-b/.claude/skills/add-video-entry/SKILL.md}") String skillPath,
            @Value("${app.add-video-entry.videos-json:C:/Git/project-b/src/data/videos.json}") String videosJsonPath,
            @Value("${app.add-video-entry.chunk-size:20}") int chunkSize) {
        this.anthropic = anthropic;
        this.enabled = enabled;
        this.skillPath = skillPath;
        this.videosJsonPath = videosJsonPath;
        this.chunkSize = Math.max(1, chunkSize);
        this.videosJson = Paths.get(videosJsonPath).toAbsolutePath().normalize();
    }

    @PostConstruct
    void init() {
        if (!enabled) {
            log.info("AddVideoEntryPropagationService: disabled (app.add-video-entry.enabled=false)");
            return;
        }
        if (!anthropic.isEnabled()) {
            log.warn("AddVideoEntryPropagationService: app.add-video-entry.enabled=true but "
                    + "AnthropicMessagesClient is not configured (no ANTHROPIC_API_KEY). "
                    + "Propagation jobs will fail at runtime until the key is set.");
        }
        // Load the skill markdown at startup; cached for the lifetime of the JVM.
        try {
            Path p = Paths.get(skillPath);
            if (!Files.exists(p)) {
                log.error("AddVideoEntryPropagationService: skill file not found at {} — "
                        + "propagation will be disabled", p.toAbsolutePath());
                return;
            }
            String skill = Files.readString(p, StandardCharsets.UTF_8);
            // Strip YAML frontmatter (the leading --- block at the top of
            // SKILL.md). Anthropic's system prompt doesn't need it.
            skill = stripFrontmatter(skill);
            // Append a short override paragraph reflecting the newer
            // add-batch.mjs rule ("name becomes a tag") which the SKILL.md
            // hasn't been updated to reflect. Keeps us consistent with
            // the mechanical batch script.
            skill = skill + "\n\n"
                    + "ADDITIONAL OVERRIDE (newer rule, supersedes §3 / §4 of the skill above):\n"
                    + "When the performer's name in `unknownActressName` is not already in "
                    + "actresses.json, DO NOT create a new placeholder actress record. Instead, "
                    + "add the lowercase performer name as the first tag in the entry's `tags` "
                    + "array, omit `actressId` from the output, and drop `unknownActressName`. "
                    + "This matches the project's latest add-batch.mjs pipeline.\n"
                    + "\n"
                    + "Your reply MUST be a single JSON array (the normalized entries), with no "
                    + "surrounding prose, no markdown fences, no commentary. The array length MUST "
                    + "equal the number of input entries, in the same order. Each entry MUST have "
                    + "exactly the canonical 15-field schema (or 14 if anonymous — no actressId, no "
                    + "unknownActressName). Strip `stats` from the input. Do not invent data.\n";
            this.systemPrompt = skill;
            log.info("AddVideoEntryPropagationService: enabled, model={}, skillPath={}, "
                    + "videosJson={}, chunkSize={}, systemPromptChars={}",
                    anthropic.getModel(), skillPath, videosJson, chunkSize, skill.length());
        } catch (IOException e) {
            log.error("AddVideoEntryPropagationService: failed to load skill from {}: {}",
                    skillPath, e.getMessage());
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    public boolean isEnabled() {
        return enabled && systemPrompt != null;
    }

    public Optional<PropagationJob> get(String jobId) {
        return Optional.ofNullable(jobs.get(jobId));
    }

    /**
     * Submit a propagation job. Returns the jobId immediately; the work
     * runs on the dedicated executor. Returns null if the service is
     * not enabled, the system prompt didn't load, the input is empty,
     * or no successful entries are present.
     */
    public String propagate(List<VideoCatalogEntry> successful, String batchId) {
        if (!isEnabled()) {
            return null;
        }
        if (successful == null || successful.isEmpty()) {
            return null;
        }
        // Plan the chunking up front so the job knows its totalChunks
        // before the first chunk returns.
        int totalChunks = (int) Math.ceil(successful.size() / (double) chunkSize);
        String jobId = UUID.randomUUID().toString();
        PropagationJob job = new PropagationJob(jobId, batchId, totalChunks);
        jobs.put(jobId, job);
        executor.submit(() -> runJob(job, successful));
        return jobId;
    }

    /** Worker run by the executor. */
    private void runJob(PropagationJob job, List<VideoCatalogEntry> successful) {
        job.markRunning("Propagating " + successful.size() + " entries in "
                + job.getTotalChunks() + " chunk(s)");

        List<Map<String, Object>> allNormalized = Collections.synchronizedList(new ArrayList<>());

        // Run chunks sequentially. Anthropic rate-limits are tight, and
        // parallel calls wouldn't help much when each call already uses
        // a per-chunk JSON payload.
        for (int i = 0; i < job.getTotalChunks(); i++) {
            int from = i * chunkSize;
            int to = Math.min(successful.size(), from + chunkSize);
            List<VideoCatalogEntry> slice = successful.subList(from, to);
            job.markRunning("Processing chunk " + (i + 1) + "/" + job.getTotalChunks()
                    + " (" + slice.size() + " entries)");
            try {
                List<Map<String, Object>> normalized = callAnthropicForChunk(slice);
                allNormalized.addAll(normalized);
                List<String> ids = new ArrayList<>();
                for (Map<String, Object> n : normalized) {
                    Object id = n.get("id");
                    if (id != null) ids.add(id.toString());
                }
                job.markChunkDone(normalized.size(), ids);
            } catch (Exception e) {
                String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                log.error("Propagation chunk {}/{} failed: {}", i + 1, job.getTotalChunks(), msg);
                job.markChunkFailed(msg);
            }
        }

        if (allNormalized.isEmpty()) {
            job.markFailed("No chunks produced usable entries");
            return;
        }

        // Atomic append. If this fails, we want the job to be FAILED and
        // no partial data on disk — re-running the batch should be safe.
        try {
            int appended = appendToProjectBVideosJson(allNormalized);
            log.info("Propagation job {} complete: appended={} entries to {}",
                    job.getJobId(), appended, videosJson);
            job.markTerminal();
        } catch (IOException e) {
            log.error("Propagation job {} failed at append step: {}", job.getJobId(), e.getMessage());
            job.markFailed("Append to " + videosJson + " failed: " + e.getMessage());
        }
    }

    /** Call Anthropic for one chunk, parse the JSON-array response. */
    private List<Map<String, Object>> callAnthropicForChunk(List<VideoCatalogEntry> slice) throws Exception {
        // Serialize entries for Anthropic: drop `stats` (pipeline noise);
        // keep `unknownActressName` (the model uses it as a signal for
        // actressId resolution, then drops it from the output).
        List<Map<String, Object>> payload = new ArrayList<>(slice.size());
        for (VideoCatalogEntry e : slice) {
            Map<String, Object> m = mapper.convertValue(e, new TypeReference<Map<String, Object>>() {});
            m.remove("stats");
            payload.add(m);
        }
        String userJson = parser.writeValueAsString(payload);
        String response = anthropic.sendMessage(systemPrompt, userJson);
        String jsonArray = extractJsonArray(response);
        JsonNode parsed = parser.readTree(jsonArray);
        if (!parsed.isArray()) {
            throw new IllegalStateException("Anthropic reply was not a JSON array. Got: "
                    + jsonArray.substring(0, Math.min(200, jsonArray.length())));
        }
        List<Map<String, Object>> result = new ArrayList<>(parsed.size());
        for (JsonNode node : parsed) {
            if (node.isObject()) {
                result.add(parser.convertValue(node, new TypeReference<Map<String, Object>>() {}));
            }
        }
        if (result.size() != slice.size()) {
            log.warn("Anthropic returned {} entries for a chunk of {} — possible truncation or model drift",
                    result.size(), slice.size());
        }
        return result;
    }

    /**
     * Append the normalized entries to project-b's videos.json under
     * the class-level WRITE_LOCK. Atomic write: temp file + move.
     */
    private int appendToProjectBVideosJson(List<Map<String, Object>> normalized) throws IOException {
        synchronized (WRITE_LOCK) {
            List<Map<String, Object>> existing = readProjectBVideosJson();
            int startIndex = existing.size();
            existing.addAll(normalized);
            writeProjectBVideosJson(existing);
            return existing.size() - startIndex;
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> readProjectBVideosJson() throws IOException {
        if (!Files.exists(videosJson)) {
            log.warn("Project-b videos.json not found at {} — starting with empty array", videosJson);
            return new ArrayList<>();
        }
        String raw = Files.readString(videosJson, StandardCharsets.UTF_8);
        if (raw.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return parser.readValue(raw, new TypeReference<List<Map<String, Object>>>() {});
        } catch (IOException e) {
            throw new IOException("Failed to parse " + videosJson + " as a JSON array: " + e.getMessage(), e);
        }
    }

    /**
     * Atomic write: serialize to a temp file in the same directory,
     * then {@code Files.move(..., ATOMIC_MOVE, REPLACE_EXISTING)} with a
     * non-atomic fallback. Mirrors {@code VideoCatalogService.writeAll}.
     */
    private void writeProjectBVideosJson(List<Map<String, Object>> all) throws IOException {
        if (videosJson.getParent() != null) {
            Files.createDirectories(videosJson.getParent());
        }
        Path tmp = videosJson.resolveSibling(videosJson.getFileName().toString() + ".tmp");
        // 2-space indent + trailing newline to match the on-disk format.
        parser.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), all);
        try {
            Files.move(tmp, videosJson, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, videosJson, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Best-effort JSON array extraction. Handles responses like:
     * <pre>
     *   [{"id":"v203",...}]
     *   ```json
     *   [{"id":"v203",...}]
     *   ```
     *   Sure! Here is the array:\n[{...}]
     * </pre>
     */
    static String extractJsonArray(String text) {
        if (text == null) {
            throw new IllegalStateException("Anthropic returned null text");
        }
        String trimmed = text.trim();
        if (trimmed.startsWith("[")) {
            return trimmed;
        }
        Matcher fence = JSON_FENCE.matcher(trimmed);
        if (fence.find()) {
            String inside = fence.group(1).trim();
            if (inside.startsWith("[")) {
                return inside;
            }
        }
        Matcher arr = JSON_ARRAY.matcher(trimmed);
        if (arr.find()) {
            return arr.group();
        }
        throw new IllegalStateException("Anthropic reply contained no JSON array. First 200 chars: "
                + trimmed.substring(0, Math.min(200, trimmed.length())));
    }

    /** Strip the leading YAML frontmatter (--- ... ---) from a markdown doc. */
    static String stripFrontmatter(String md) {
        if (md == null) return null;
        String trimmed = md.trim();
        if (!trimmed.startsWith("---")) return md;
        int second = trimmed.indexOf("\n---", 3);
        if (second < 0) return md;
        int after = trimmed.indexOf('\n', second + 4);
        if (after < 0) return md;
        return trimmed.substring(after + 1);
    }

    /** Build a UI-friendly payload for the status endpoint. */
    public Map<String, Object> toStatusPayload(PropagationJob job) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("jobId", job.getJobId());
        out.put("batchId", job.getBatchId());
        out.put("status", job.getStatus().name());
        out.put("totalChunks", job.getTotalChunks());
        out.put("completedChunks", job.getCompletedChunks());
        out.put("failedChunks", job.getFailedChunks());
        out.put("appendedEntries", job.getAppendedEntries());
        out.put("currentChunk", job.getCurrentChunk());
        out.put("currentStep", job.getCurrentStep());
        out.put("appendedIds", job.getAppendedIds());
        if (job.getStatus() == PropagationJob.Status.PARTIAL
                || job.getStatus() == PropagationJob.Status.FAILED) {
            out.put("errors", job.getFailedChunkErrors());
        }
        if (job.getError() != null) {
            out.put("error", job.getError());
        }
        out.put("videosJson", videosJson.toString());
        return out;
    }
}
