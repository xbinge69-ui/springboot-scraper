package com.example.scraper.video;

import jakarta.annotation.PostConstruct;
import net.bramp.ffmpeg.FFmpeg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Detects which (if any) hardware video encoders the local ffmpeg
 * supports. Runs {@code ffmpeg -hide_banner -encoders} once at startup
 * and parses the output. Cached for the JVM lifetime.
 *
 * <p>Supported GPUs (per codec):
 * <ul>
 *   <li><b>NVIDIA</b> — {@code h264_nvenc} / {@code hevc_nvenc} (needs an NVIDIA GPU + ffmpeg built with NVENC)</li>
 *   <li><b>Intel</b>  — {@code h264_qsv}   / {@code hevc_qsv}   (needs an Intel iGPU/dGPU + ffmpeg built with QSV)</li>
 *   <li><b>AMD</b>    — {@code h264_amf}   / {@code hevc_amf}   (Windows; needs an AMD GPU + ffmpeg built with AMF)</li>
 * </ul>
 *
 * <p>The probe prefers encoders in a configurable order
 * ({@code app.ffmpeg.gpu.preference}, default {@code nvidia,intel,amd}).
 * If none are available, the orchestrator falls back to the
 * codec's CPU encoder (libx264 / libx265).
 */
@Service
public class GpuEncoderProbe {

    private static final Logger log = LoggerFactory.getLogger(GpuEncoderProbe.class);

    // H.264 encoder names
    public static final String H264_NVENC = "h264_nvenc";
    public static final String H264_QSV   = "h264_qsv";
    public static final String H264_AMF   = "h264_amf";
    // HEVC encoder names
    public static final String HEVC_NVENC = "hevc_nvenc";
    public static final String HEVC_QSV   = "hevc_qsv";
    public static final String HEVC_AMF   = "hevc_amf";

    public enum Vendor { NVIDIA, INTEL, AMD }

    public static final class Detection {
        public final Vendor vendor;
        public final Codec codec;
        public final String encoderName;
        public Detection(Vendor v, Codec c, String name) {
            this.vendor = v; this.codec = c; this.encoderName = name;
        }
        @Override public String toString() { return vendor + "/" + codec + " (" + encoderName + ")"; }
    }

    private final boolean gpuEnabled;
    private final String preferenceOrder;
    private final FFmpeg ffmpeg;
    private final List<Detection> available = new ArrayList<>();
    /**
     * Best selection per codec. Keyed by the codec so the orchestrator
     * can ask {@code pick(Codec.HEVC)} and get the best HEVC GPU encoder
     * (or null if none), independently of the H.264 pick.
     */
    private final java.util.EnumMap<Codec, Detection> selectedByCodec = new java.util.EnumMap<>(Codec.class);

    public GpuEncoderProbe(
            @Value("${app.ffmpeg.gpu.enabled:true}") boolean gpuEnabled,
            @Value("${app.ffmpeg.gpu.preference:nvidia,intel,amd}") String preferenceOrder,
            FfmpegProperties props) throws IOException {
        this.gpuEnabled = gpuEnabled;
        this.preferenceOrder = preferenceOrder == null ? "" : preferenceOrder;
        this.ffmpeg = new FFmpeg(props.getFfmpegPath());
    }

    @PostConstruct
    void detect() {
        if (!gpuEnabled) {
            log.info("GPU encoding disabled by config — using CPU encoders.");
            return;
        }
        try {
            List<String> encoders = runEncodersList();
            if (encoders.contains(H264_NVENC)) available.add(new Detection(Vendor.NVIDIA, Codec.H264, H264_NVENC));
            if (encoders.contains(H264_QSV))   available.add(new Detection(Vendor.INTEL,  Codec.H264, H264_QSV));
            if (encoders.contains(H264_AMF))   available.add(new Detection(Vendor.AMD,    Codec.H264, H264_AMF));
            if (encoders.contains(HEVC_NVENC)) available.add(new Detection(Vendor.NVIDIA, Codec.HEVC, HEVC_NVENC));
            if (encoders.contains(HEVC_QSV))   available.add(new Detection(Vendor.INTEL,  Codec.HEVC, HEVC_QSV));
            if (encoders.contains(HEVC_AMF))   available.add(new Detection(Vendor.AMD,    Codec.HEVC, HEVC_AMF));
        } catch (Exception e) {
            log.warn("GPU encoder probe failed: {} — falling back to CPU.", e.getMessage());
        }

        if (available.isEmpty()) {
            log.info("No GPU video encoder found in this ffmpeg build — using CPU.");
            return;
        }
        log.info("GPU encoders available: {}", available);

        // Per-codec: pick the highest-priority vendor the user asked for.
        String[] prefs = preferenceOrder.toLowerCase(Locale.ROOT).split("[,;\\s]+");
        for (Codec codec : Codec.values()) {
            Detection best = null;
            for (String p : prefs) {
                if (p.isBlank()) continue;
                for (Detection d : available) {
                    if (d.codec == codec && d.vendor.name().toLowerCase(Locale.ROOT).equals(p)) {
                        best = d;
                        break;
                    }
                }
                if (best != null) break;
            }
            if (best == null) {
                // No preference matched; use the first available for this codec.
                for (Detection d : available) {
                    if (d.codec == codec) { best = d; break; }
                }
            }
            if (best != null) {
                selectedByCodec.put(codec, best);
                log.info("GPU encoder selected for {}: {} (preference='{}')",
                        codec, best, preferenceOrder);
            }
        }
    }

    /**
     * @return the best GPU encoder for the given codec, or {@code null}
     *         if none — caller falls back to the codec's CPU encoder.
     */
    public Detection pick(Codec codec) {
        return selectedByCodec.get(codec);
    }

    /**
     * Backwards-compatible accessor: returns the H.264 selection. Kept
     * so existing callers that haven't been refactored to ask by codec
     * continue to work.
     */
    public Detection getSelected() {
        return selectedByCodec.get(Codec.H264);
    }

    /** @return unmodifiable list of every GPU encoder ffmpeg exposes. */
    public List<Detection> getAvailable() {
        return Collections.unmodifiableList(available);
    }

    /**
     * @return true if any GPU encoder is selected and ready to use.
     *         (Per-codec: see {@link #pick(Codec)}.)
     */
    public boolean isGpuActive() {
        return !selectedByCodec.isEmpty();
    }

    /** @return true if the given codec has a GPU encoder available. */
    public boolean isGpuActiveFor(Codec codec) {
        return selectedByCodec.containsKey(codec);
    }

    /**
     * Full diagnostic snapshot of the probe state, for the UI panel
     * and the {@code /api/ffmpeg/test} endpoint. The probe runs once
     * at {@code @PostConstruct} time; this method is the read-only
     * window onto that cached result — no re-probe, no subprocess
     * spawn, cheap to call.
     */
    public Snapshot snapshot() {
        return new Snapshot(
                gpuEnabled,
                preferenceOrder,
                ffmpeg.getPath(),
                isGpuActive(),
                Collections.unmodifiableList(new ArrayList<>(available)),
                Collections.unmodifiableMap(new java.util.LinkedHashMap<>(selectedByCodec)));
    }

    /** Result of {@link #snapshot()}. Pure data, safe to serialize as JSON. */
    public record Snapshot(
            boolean gpuEnabled,
            String preferenceOrder,
            String ffmpegPath,
            boolean gpuActive,
            List<Detection> available,
            java.util.Map<Codec, Detection> selectedByCodec
    ) {}

    // ---- internals ----

    private List<String> runEncodersList() throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpeg.getPath());
        cmd.add("-hide_banner");
        cmd.add("-encoders");
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        Process p = pb.start();
        List<String> found = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            // Real ffmpeg -encoders output (note the LEADING space before the
            // flags column, which trips up a naive indexOf(' ') split):
            //   " V..... = Video"
            //   " V....D h264_nvenc           NVIDIA NVENC H.264 encoder (codec h264)"
            //   " A..... aac                  AAC (Advanced Audio Coding)"
            // The flags column is 6 chars, followed by a space, then the
            // name (variable width, padded with spaces), then the description.
            // We use a regex so the leading whitespace is handled cleanly.
            java.util.regex.Pattern encLine =
                    java.util.regex.Pattern.compile("^\\s+([SVAD.]+)\\s+(\\S+)\\s+.*$");
            while ((line = r.readLine()) != null) {
                java.util.regex.Matcher m = encLine.matcher(line);
                if (!m.matches()) continue;
                String flags = m.group(1);
                String name  = m.group(2);
                // We want video (V) encoders only. The 'D' flag in the
                // position-6 column is "direct rendering" (ffmpeg's
                // -encoders convention), NOT a decoder marker — it just
                // means the encoder accepts a raw device as input. Most
                // hardware encoders (h264_nvenc, h264_amf) have it; the
                // libx264 CPU encoder doesn't. Both are valid for our
                // use case, so we don't filter on D anymore (an earlier
                // version of this probe did and silently rejected every
                // GPU encoder).
                if (flags.isEmpty() || flags.charAt(0) != 'V') continue;
                if (name.equals(H264_NVENC) || name.equals(H264_QSV) || name.equals(H264_AMF)
                        || name.equals(HEVC_NVENC) || name.equals(HEVC_QSV) || name.equals(HEVC_AMF)) {
                    found.add(name);
                }
            }
        }
        boolean finished = p.waitFor(10, TimeUnit.SECONDS);
        if (!finished) {
            p.destroyForcibly();
            throw new IOException("ffmpeg -encoders timed out after 10s");
        }
        if (p.exitValue() != 0) {
            throw new IOException("ffmpeg -encoders exited with code " + p.exitValue());
        }
        return found;
    }
}
