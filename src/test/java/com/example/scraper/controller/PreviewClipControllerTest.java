package com.example.scraper.controller;

import com.example.scraper.service.PipelineJobService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link PreviewClipController} covering the per-clip
 * DELETE, the cross-job cleanup, and the still-supported
 * delete-all-for-this-job endpoint. Uses a real temp directory and a
 * minimal stub for {@link PipelineJobService} — the controller is
 * filesystem-driven and the registry calls are best-effort fallbacks.
 */
class PreviewClipControllerTest {

    @TempDir
    Path tmpRoot;

    private PreviewClipController controller;
    private PipelineJobService jobService;

    @BeforeEach
    void setUp() throws Exception {
        // Point java.io.tmpdir at our @TempDir so the controller finds
        // (and removes) only files we create. Setting the system
        // property is the only way to redirect the controller's
        // previewsDir() resolution without refactoring it for DI.
        System.setProperty("java.io.tmpdir", tmpRoot.toString());

        // Minimal stub — only `get()` and `cleanup()` are called by the
        // endpoints we exercise. Anonymous subclass avoids pulling in
        // Mockito for a 2-method stub.
        jobService = new PipelineJobService() {
            @Override public java.util.Optional<com.example.scraper.model.PipelineJob> get(String jobId) {
                return java.util.Optional.empty();
            }
            @Override public boolean cleanup(String jobId) { return false; /* no-op */ }
        };
        controller = new PreviewClipController(jobService);

        // Sanity: the constant must match what the test jobs use
        assertThat(PipelineJobService.PREVIEWS_DIR_PREFIX).isEqualTo("scraper-previews-");
    }

    @Test
    void perClipDelete_removesOnlyThatFile_leavesSiblingsIntact() throws Exception {
        String jobId = UUID.randomUUID().toString();
        Path dir = createJobDirWithClips(jobId, 5);

        // The orchestrator (VideoEnrichmentService) passes baseName="clip"
        // to FfmpegDerivativeService.generatePreviewClips, which writes
        // files named "clip.clipN.mp4" — NOT the bare "clipN.mp4" that
        // earlier controller code expected. The validator must accept
        // the real pattern.
        ResponseEntity<Void> res = controller.deleteSingleClip(jobId, "clip.clip3.mp4");
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(Files.exists(dir.resolve("clip.clip1.mp4"))).isTrue();
        assertThat(Files.exists(dir.resolve("clip.clip2.mp4"))).isTrue();
        assertThat(Files.exists(dir.resolve("clip.clip3.mp4"))).isFalse();
        assertThat(Files.exists(dir.resolve("clip.clip4.mp4"))).isTrue();
        assertThat(Files.exists(dir.resolve("clip.clip5.mp4"))).isTrue();
    }

    @Test
    void perClipDelete_acceptsLegacyBareClipNPattern() throws Exception {
        // The validator should still accept the legacy "clipN.mp4" form
        // in case the caller or older code paths produce it.
        String jobId = UUID.randomUUID().toString();
        Path dir = Files.createDirectory(
                tmpRoot.resolve(PipelineJobService.PREVIEWS_DIR_PREFIX + jobId));
        Files.writeString(dir.resolve("clip2.mp4"), "legacy");

        ResponseEntity<Void> res = controller.deleteSingleClip(jobId, "clip2.mp4");
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(Files.exists(dir.resolve("clip2.mp4"))).isFalse();
    }

    @Test
    void perClipDelete_returnsNotFoundForMissingFile() throws Exception {
        String jobId = UUID.randomUUID().toString();
        createJobDirWithClips(jobId, 5);

        ResponseEntity<Void> res = controller.deleteSingleClip(jobId, "clip.clip3.mp4");
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // Second call: file is already gone → 404
        ResponseEntity<Void> res2 = controller.deleteSingleClip(jobId, "clip.clip3.mp4");
        assertThat(res2.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void perClipDelete_rejectsTraversalAttempt() throws Exception {
        String jobId = UUID.randomUUID().toString();
        createJobDirWithClips(jobId, 3);

        // ../../etc/passwd — the isValidClipName guard catches this
        ResponseEntity<Void> res = controller.deleteSingleClip(jobId, "../etc/passwd");
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void perClipDelete_rejectsNonMp4Extension() throws Exception {
        String jobId = UUID.randomUUID().toString();
        Path dir = createJobDirWithClips(jobId, 3);
        // A file with a .mp4-extension-looking tail but a different ext
        // should still be rejected.
        Files.writeString(dir.resolve("clip.clip1.txt"), "trojan");

        ResponseEntity<Void> res = controller.deleteSingleClip(jobId, "clip.clip1.txt");
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void deleteAllAcrossJobs_wipesEveryJobDir_regardlessOfRegistry() throws Exception {
        // Three job dirs from three different "old" jobs that the user
        // forgot to clean up.
        String job1 = UUID.randomUUID().toString();
        String job2 = UUID.randomUUID().toString();
        String job3 = UUID.randomUUID().toString();
        createJobDirWithClips(job1, 5);
        createJobDirWithClips(job2, 5);
        createJobDirWithClips(job3, 5);
        // Plus an unrelated dir that should NOT be touched.
        Path unrelated = Files.createDirectory(tmpRoot.resolve("not-a-previews-dir"));
        Files.writeString(unrelated.resolve("keepme.txt"), "untouched");

        ResponseEntity<Map<String, Object>> res = controller.deleteAllClips();
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = res.getBody();
        assertThat(body).isNotNull();
        assertThat(((Number) body.get("removedDirs")).intValue()).isEqualTo(3);
        // 3 jobs × 5 clips = 15 files
        assertThat(((Number) body.get("removedFiles")).intValue()).isEqualTo(15);

        // The unrelated dir + its file are still there.
        assertThat(Files.exists(unrelated.resolve("keepme.txt"))).isTrue();
    }

    @Test
    void deleteAllAcrossJobs_handlesEmptyTmpDir() {
        // No previews dirs at all — should still 200 with 0 counts.
        ResponseEntity<Map<String, Object>> res = controller.deleteAllClips();
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) res.getBody().get("removedDirs")).intValue()).isZero();
        assertThat(((Number) res.getBody().get("removedFiles")).intValue()).isZero();
    }

    @Test
    void deleteClipsForJob_removesOnlyThatJob() throws Exception {
        String jobA = UUID.randomUUID().toString();
        String jobB = UUID.randomUUID().toString();
        Path dirA = createJobDirWithClips(jobA, 5);
        Path dirB = createJobDirWithClips(jobB, 5);

        ResponseEntity<Void> res = controller.deleteClips(jobA);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(Files.exists(dirA)).isFalse();
        assertThat(Files.exists(dirB)).isTrue();
        assertThat(Files.exists(dirB.resolve("clip.clip1.mp4"))).isTrue();
    }

    // ---- helpers ----

    /** Create scraper-previews-{jobId}/ with N clip.clip1.mp4..clip.clipN.mp4 files
     *  (matches the real pattern produced by FfmpegDerivativeService
     *  when called with baseName="clip"). */
    private Path createJobDirWithClips(String jobId, int n) throws IOException {
        Path dir = Files.createDirectory(
                tmpRoot.resolve(PipelineJobService.PREVIEWS_DIR_PREFIX + jobId));
        for (int i = 1; i <= n; i++) {
            Files.writeString(dir.resolve("clip.clip" + i + ".mp4"), "fake-bytes-" + i);
        }
        return dir;
    }
}
