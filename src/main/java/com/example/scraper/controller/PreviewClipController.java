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
        // Inline display so the <video> tag can play it; for downloads the
        // client should hit the sibling /download endpoint below (which sets
        // Content-Disposition: attachment and is the only thing that makes
        // the browser actually save the file — the `download` attribute on
        // an `<a>` is a hint that browsers ignore when the response itself
        // declares the file "inline").
        headers.set("Content-Disposition", "inline; filename=\"" + filename + "\"");
        return new ResponseEntity<>(body, headers, HttpStatus.OK);
    }

    /**
     * Force-download a preview clip. Same validation/lookup as
     * {@link #serveClip}, but the response is sent as
     * {@code Content-Disposition: attachment} so the browser saves the
     * file instead of trying to play it. Without this, the inline header
     * on the play endpoint wins and the user's "⬇ Download" button shows
     * a "file not available" / "resource not downloadable" message on
     * most browsers.
     */
    @GetMapping("/api/video/preview-clips/{jobId}/{filename}/download")
    @ResponseBody
    public ResponseEntity<Resource> downloadClip(@PathVariable("jobId") String jobId,
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
        // ASCII-safe fallback: when the filename isn't pure ASCII, also
        // emit RFC 5987 filename* so non-ASCII download names render
        // correctly. Our clip names are clip1.mp4..clip5.mp4 so this
        // never trips today, but it's a one-liner that future-proofs
        // the endpoint.
        headers.set("Content-Disposition",
                "attachment; filename=\"" + filename + "\"; filename*=UTF-8''" + filename);
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
        boolean removed = deleteDirIfExists(dir);
        jobService.get(jobId).ifPresent(j -> jobService.cleanup(jobId));
        return removed || jobService.get(jobId).isEmpty()
                ? ResponseEntity.noContent().build()
                : ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }

    /**
     * Delete ONE preview clip inside a job's directory. The other 4
     * sibling clips stay on disk so the user can still download / play
     * them. Previously the per-clip UI button wrongly called the
     * "delete-all-for-this-job" endpoint above, which left the UI cards
     * for the other 4 clips in place but with no underlying file — a
     * follow-up "⬇ Download" would then 404 ("site not available").
     */
    @DeleteMapping("/api/video/preview-clips/{jobId}/{filename}")
    @ResponseBody
    public ResponseEntity<Void> deleteSingleClip(@PathVariable("jobId") String jobId,
                                                 @PathVariable("filename") String filename) {
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
        try {
            boolean removed = Files.deleteIfExists(resolved);
            return removed ? ResponseEntity.noContent().build()
                           : ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        } catch (java.io.IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Wipe every preview-clip directory under {@code java.io.tmpdir},
     * regardless of which job created it. Used by the "Delete all
     * preview clips" button on the pipeline page so the user can clear
     * out clips from jobs that finished hours/days ago and that are no
     * longer in the in-memory registry (the registry forgets jobs after
     * a short TTL, but the temp files on disk survive indefinitely).
     *
     * <p>Returns the number of directories removed so the UI can show a
     * meaningful success message ("cleared 3 orphan jobs").
     */
    @DeleteMapping("/api/video/preview-clips/all")
    @ResponseBody
    public ResponseEntity<java.util.Map<String, Object>> deleteAllClips() {
        Path tmp = Paths.get(System.getProperty("java.io.tmpdir"));
        int removedDirs = 0;
        int removedFiles = 0;
        if (!Files.isDirectory(tmp)) {
            return ResponseEntity.ok(java.util.Map.of(
                    "removedDirs", 0, "removedFiles", 0,
                    "message", "tmpdir not found"));
        }
        try (java.util.stream.Stream<Path> stream = Files.list(tmp)) {
            java.util.List<Path> dirs = stream
                    .filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString()
                            .startsWith(PipelineJobService.PREVIEWS_DIR_PREFIX))
                    .toList();
            for (Path dir : dirs) {
                int[] counts = deleteDirAndCount(dir);
                removedDirs += counts[0] == 0 ? 0 : 1;
                removedFiles += counts[1];
                // Also drop the in-memory job registry entry if any
                String name = dir.getFileName().toString();
                String jobId = name.substring(PipelineJobService.PREVIEWS_DIR_PREFIX.length());
                jobService.get(jobId).ifPresent(j -> jobService.cleanup(jobId));
            }
        } catch (java.io.IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(java.util.Map.of("error", e.getMessage()));
        }
        return ResponseEntity.ok(java.util.Map.of(
                "removedDirs", removedDirs,
                "removedFiles", removedFiles,
                "message", "Cleared " + removedDirs + " job dir(s) / "
                          + removedFiles + " file(s)."));
    }

    /** Walk + delete a directory tree. Returns [dirsDeleted, filesDeleted]. */
    private static int[] deleteDirAndCount(Path dir) {
        int[] counts = new int[]{0, 0};
        if (!Files.exists(dir)) return counts;
        try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
            // Snapshot type BEFORE delete — once we delete a path it no
            // longer exists, so Files.isDirectory() is meaningless.
            // Files.isDirectory(Path) is a no-throw variant (returns
            // false on missing/permission errors), so no try/catch needed.
            walk.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(p -> {
                        boolean wasDir = Files.isDirectory(p);
                        try {
                            if (Files.deleteIfExists(p)) {
                                if (wasDir) counts[0]++; else counts[1]++;
                            }
                        } catch (java.io.IOException ignored) {}
                    });
        } catch (java.io.IOException ignored) {
        }
        return counts;
    }

    /** Backwards-compat helper: returns true if anything was removed. */
    private static boolean deleteDirIfExists(Path dir) {
        int[] counts = deleteDirAndCount(dir);
        return counts[0] > 0 || counts[1] > 0;
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

    /**
     * Filename must look like one of:
     * <ul>
     *   <li>{@code clipN.mp4} — the legacy simple pattern (no baseName)</li>
     *   <li>{@code {baseName}.clipN.mp4} — the current pattern. The
     *       orchestrator passes {@code baseName = "clip"} to
     *       {@code FfmpegDerivativeService.generatePreviewClips}, which
     *       then writes {@code clip.clip1.mp4}..{@code clip.clip5.mp4}.
     *       The {@code baseName} can technically be any slug, so we
     *       match "any stem ending in {@code .clipN} with N = digits".</li>
     * </ul>
     * Previous versions of this validator only accepted the simple
     * {@code clipN.mp4} pattern and silently 400'd the real
     * {@code clip.clipN.mp4} files — making the play AND download
     * endpoints return 400 and the browser say "site not available" on
     * the download button.
     */
    private static boolean isValidClipName(String s) {
        if (s == null) return false;
        if (!s.endsWith(".mp4")) return false;
        if (s.length() > 64) return false;
        String stem = s.substring(0, s.length() - 4);
        // Isolate the ".clipN" (or bare "clipN") tail — anything before
        // the last dot is the baseName and is unrestricted.
        int lastDot = stem.lastIndexOf('.');
        String tail = lastDot >= 0 ? stem.substring(lastDot + 1) : stem;
        if (!tail.startsWith("clip")) return false;
        if (tail.length() <= 4) return false;  // at least one digit after "clip"
        for (int i = 4; i < tail.length(); i++) {
            if (!Character.isDigit(tail.charAt(i))) return false;
        }
        return true;
    }
}
