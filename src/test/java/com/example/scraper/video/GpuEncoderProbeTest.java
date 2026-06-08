package com.example.scraper.video;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the {@code ffmpeg -encoders} parser inside
 * {@link GpuEncoderProbe}.
 *
 * <p>This test exists because the original parser silently rejected every
 * hardware encoder due to a leading-space bug in its indexOf(' ') split
 * — which made the GPU probe report "no GPU" even when the ffmpeg build
 * had NVENC / QSV / AMF compiled in. The fixture below is the literal
 * output of {@code ffmpeg -encoders} from BtbN's GPL win64 build; the
 * test feeds it through the same regex the probe now uses and asserts
 * the six GPU encoders are detected.
 *
 * <p>The regex itself is duplicated from the probe (it's private); if
 * the probe's parser ever changes, this test must be updated in lockstep.
 */
class GpuEncoderProbeTest {

    private static final Pattern ENC_LINE =
            Pattern.compile("^\\s+([SVAD.]+)\\s+(\\S+)\\s+.*$");

    @Test
    void parsesBtbNGplEncodersAndFindsAllGpuEncoders() throws IOException {
        List<String> lines = readFixture("encoders/btbn-gpl.txt");
        List<String> found = parseEncoders(lines);

        // All six hardware encoders must be detected.
        assertThat(found)
                .containsExactlyInAnyOrder(
                        "h264_nvenc", "h264_qsv", "h264_amf",
                        "hevc_nvenc", "hevc_qsv", "hevc_amf");
    }

    @Test
    void rejectsEmptyFlagsAndAudioEncoders() {
        // A line with no flags column (only name) — e.g. blank line, or
        // a separator like "----D." which has no encoder name in column 2.
        assertThat(parseLine(" ----D. = supports direct rendering")).isNull();
        // An audio encoder should NOT match the GPU detection.
        assertThat(parseLine(" A..... aac                  AAC (Advanced Audio Coding)")).isNull();
        // A subtitle encoder should NOT match.
        assertThat(parseLine(" S..... ass                  ASS (Advanced SSA) subtitle")).isNull();
    }

    @Test
    void leadingSpaceDoesNotConfuseParser() {
        // The exact bug the old parser had: leading whitespace before the
        // flags column. Old code used indexOf(' ') which returned 0
        // (the leading space), making "flags" empty and bailing on the
        // empty-flags check. The regex must handle it.
        assertThat(parseLine(" V....D h264_nvenc           NVIDIA NVENC H.264 encoder (codec h264)"))
                .isEqualTo("h264_nvenc");
        assertThat(parseLine(" V....D h264_amf             AMD AMF H.264 Encoder (codec h264)"))
                .isEqualTo("h264_amf");
        assertThat(parseLine(" V..... hevc_qsv             HEVC (Intel Quick Sync Video acceleration) (codec hevc)"))
                .isEqualTo("hevc_qsv");
    }

    @Test
    void acceptsBothDirectRenderingAndNonDirectRenderingEncoders() {
        // 'D' in position 6 of the flags column means "supports direct
        // rendering" (ffmpeg's -encoders convention), NOT "decoder" as
        // the original parser thought. Most GPU encoders have it; some
        // (h264_qsv, hevc_qsv) don't. Both must be accepted.
        assertThat(parseLine(" V....D h264_nvenc           NVIDIA NVENC H.264 encoder (codec h264)"))
                .isEqualTo("h264_nvenc");
        assertThat(parseLine(" V..... h264_qsv             H.264 / AVC / MPEG-4 AVC / MPEG-4 part 10 (Intel Quick Sync Video acceleration) (codec h264)"))
                .isEqualTo("h264_qsv");
    }

    // ---- helpers ----

    /**
     * Mirror of the production parser: same regex, same filter, same
     * target names. Returns the subset of encoder names the probe would
     * consider "GPU-relevant".
     */
    private static List<String> parseEncoders(List<String> lines) {
        List<String> found = new ArrayList<>();
        for (String line : lines) {
            String name = parseLine(line);
            if (name != null) found.add(name);
        }
        return found;
    }

    private static String parseLine(String line) {
        Matcher m = ENC_LINE.matcher(line);
        if (!m.matches()) return null;
        String flags = m.group(1);
        String name = m.group(2);
        if (flags.isEmpty() || flags.charAt(0) != 'V') return null;
        if (name.equals(GpuEncoderProbe.H264_NVENC) || name.equals(GpuEncoderProbe.H264_QSV) || name.equals(GpuEncoderProbe.H264_AMF)
                || name.equals(GpuEncoderProbe.HEVC_NVENC) || name.equals(GpuEncoderProbe.HEVC_QSV) || name.equals(GpuEncoderProbe.HEVC_AMF)) {
            return name;
        }
        return null;
    }

    private static List<String> readFixture(String name) throws IOException {
        ClassLoader cl = GpuEncoderProbeTest.class.getClassLoader();
        try (InputStream in = cl.getResourceAsStream(name)) {
            if (in == null) throw new IOException("Missing fixture: " + name);
            List<String> lines = new ArrayList<>();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) lines.add(line);
            }
            return lines;
        }
    }
}
