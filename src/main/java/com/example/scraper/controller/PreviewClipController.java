package com.example.scraper.controller;

import com.example.scraper.service.PipelineJobService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseBody;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Serves and deletes the 5 watermark-baked preview clips generated
 * by the pipeline. The files live in
 * {@code ${java.io.tmpdir}/scraper-previews-{jobId}/} and are served
 * via {@code /api/video/preview-clips/{jobId}/{filename}}.
 *
 * <p>Path-traversal hardening: the resolved path must be a child of
 * the per-job dir, the filename must end in {@code .mp4} and must
 * match the {@code clip1.mp4}..{@code clip5.mp5} naming pattern.
 */
@Controller
public class PreviewClipController {

    private static final MediaType VIDEO_MP4 = MediaType.parseMediaType("video/mp4");

    private final PipelineJobService jobService;

    public PreviewClipController(PipelineJobService jobService) {
        this.jobService = jobService;
    }

    @GetMapping("/api/video/preview-clips/{jobId}/{filename}")
    @ResponseBody
    public ResponseEntity<Resource> serveClip(@PathVariable("jobId") String jobId,
                                              @PathVariable("filename") String filename) throws IOException {
        if (!isValidJobId(jobId) || !isValidClipName(filename)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
        Path baseDir = previewsDir(jobId);
        if (!Files.isDirectory(baseDir)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        Path resolved = baseDir.resolve(filename).normalize();
        if (!resolved.startsWith(baseDir)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();  // traversal attempt
        }
        if (!Files.isRegularFile(resolved)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        Resource body = new FileSystemResource(resolved);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(VIDEO_MP4);
        headers.setContentLength(Files.size(resolved));
        headers.set("Accept-Ranges", "bytes");
        // Inline display so the <video> tag can play it; users can still
        // right-click → "Save video as" to download.
        headers.set("Content-Disposition", "inline; filename=\"" + filename + "\"");
        return new ResponseEntity<>(body, headers, HttpStatus.OK);
    }

    @DeleteMapping("/api/video/preview-clips/{jobId}")
    @ResponseBody
    public ResponseEntity<Void> deleteClips(@PathVariable("jobId") String jobId) {
        if (!isValidJobId(jobId)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
        // The job may be unknown to the registry (e.g. after a server
        // restart) but the temp dir may still exist — always try to
        // remove the dir regardless of registry state.
        Path dir = previewsDir(jobId);
        boolean removed = false;
        if (Files.exists(dir)) {
            try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
                walk.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                        .forEach(p -> {
                            try { Files.deleteIfExists(p); } catch (java.io.IOException ignored) {}
                        });
                removed = true;
            } catch (java.io.IOException ignored) {
            }
        }
        jobService.get(jobId).ifPresent(j -> jobService.cleanup(jobId));
        return removed || jobService.get(jobId).isEmpty()
                ? ResponseEntity.noContent().build()
                : ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }

    private static Path previewsDir(String jobId) {
        return Paths.get(System.getProperty("java.io.tmpdir"),
                PipelineJobService.PREVIEWS_DIR_PREFIX + jobId);
    }

    /** Job ids are UUIDs — restrict to that alphabet. */
    private static boolean isValidJobId(String s) {
        if (s == null || s.isEmpty() || s.length() > 64) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '-')) return false;
        }
        return true;
    }

    /** Filename must look like clip1.mp4 .. clip99.mp4. */
    private static boolean isValidClipName(String s) {
        if (s == null) return false;
        if (!s.endsWith(".mp4")) return false;
        if (s.length() > 32) return false;
        String stem = s.substring(0, s.length() - 4);
        if (!stem.startsWith("clip")) return false;
        for (int i = 4; i < stem.length(); i++) {
            if (!Character.isDigit(stem.charAt(i))) return false;
        }
        return true;
    }
}
