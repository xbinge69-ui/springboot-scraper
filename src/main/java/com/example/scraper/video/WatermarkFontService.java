package com.example.scraper.video;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Resolves a TTF font usable by ffmpeg's {@code drawtext} filter.
 *
 * <p>Resolution order:
 * <ol>
 *   <li>The path set by {@code app.ffmpeg.watermark.font-path} (if non-empty).</li>
 *   <li>Common system font locations (Windows: Arial, Linux: DejaVu / Liberation,
 *       macOS: Arial).</li>
 *   <li>Download {@code DejaVuSans-Bold.ttf} from the GitHub mirror listed in
 *       {@code app.ffmpeg.watermark.download-url} into
 *       {@code ${user.home}/.scraper/fonts/} and use it from then on.</li>
 * </ol>
 *
 * <p>Resolution is lazy (first call) and the result is cached in-process.
 * Network or IO failures during the download silently fall through to
 * "no font available" — the watermark is then skipped (the pipeline
 * still succeeds, the {@link com.example.scraper.model.PipelineOutcome}
 * gets a warning).
 */
@Service
public class WatermarkFontService {

    private static final Logger log = LoggerFactory.getLogger(WatermarkFontService.class);

    private final String configuredFontPath;
    private final String downloadUrl;
    private final AtomicReference<Path> cached = new AtomicReference<>();

    public WatermarkFontService(
            @Value("${app.ffmpeg.watermark.font-path:}") String configuredFontPath,
            @Value("${app.ffmpeg.watermark.download-url:https://github.com/dejavu-fonts/dejavu-fonts/raw/master/ttf/DejaVuSans-Bold.ttf}") String downloadUrl) {
        this.configuredFontPath = configuredFontPath == null ? "" : configuredFontPath.trim();
        this.downloadUrl = downloadUrl;
    }

    /** @return resolved font path, or empty if no font could be obtained. */
    public Optional<Path> resolveFontPath() {
        Path existing = cached.get();
        if (existing != null) {
            return Optional.of(existing);
        }
        Path resolved = doResolve();
        if (resolved != null) {
            cached.set(resolved);
            return Optional.of(resolved);
        }
        return Optional.empty();
    }

    /** Convenience for call sites that want a boolean. */
    public boolean isAvailable() {
        return resolveFontPath().isPresent();
    }

    private Path doResolve() {
        // 1) Configured path wins.
        if (!configuredFontPath.isEmpty()) {
            Path p = Paths.get(configuredFontPath);
            if (Files.isRegularFile(p)) {
                log.info("Watermark font: using configured path {}", p);
                return p;
            } else {
                log.warn("Configured watermark font not found at {} — falling back to system probe", p);
            }
        }
        // 2) Common system locations.
        String os = System.getProperty("os.name", "").toLowerCase();
        for (String candidate : systemFontCandidates(os)) {
            Path p = Paths.get(candidate);
            if (Files.isRegularFile(p)) {
                log.info("Watermark font: using system font {}", p);
                return p;
            }
        }
        // 3) Download to ~/.scraper/fonts/.
        try {
            Path downloaded = downloadDefaultFont();
            if (downloaded != null) {
                log.info("Watermark font: downloaded default to {}", downloaded);
                return downloaded;
            }
        } catch (Exception e) {
            log.warn("Watermark font auto-download failed: {}", e.getMessage());
        }
        log.warn("Watermark font unavailable — watermarks will be skipped.");
        return null;
    }

    private static String[] systemFontCandidates(String osLower) {
        if (osLower.contains("win")) {
            return new String[]{
                    "C:\\Windows\\Fonts\\arialbd.ttf",
                    "C:\\Windows\\Fonts\\arial.ttf",
                    "C:\\Windows\\Fonts\\segoeui.ttf"
            };
        }
        if (osLower.contains("mac")) {
            return new String[]{
                    "/Library/Fonts/Arial.ttf",
                    "/System/Library/Fonts/Supplemental/Arial.ttf",
                    "/System/Library/Fonts/Helvetica.ttc"
            };
        }
        // Linux / other unix
        return new String[]{
                "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
                "/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf",
                "/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf",
                "/usr/share/fonts/dejavu/DejaVuSans-Bold.ttf"
        };
    }

    private Path downloadDefaultFont() throws IOException {
        if (downloadUrl == null || downloadUrl.isBlank()) return null;
        Path cacheDir = Paths.get(System.getProperty("user.home"), ".scraper", "fonts");
        Files.createDirectories(cacheDir);
        Path dest = cacheDir.resolve("DejaVuSans-Bold.ttf");
        if (Files.isRegularFile(dest) && Files.size(dest) > 1024) {
            // Cache hit — don't re-download.
            return dest;
        }

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(30).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(60).toMillis());
        RestTemplate rt = new RestTemplate(factory);

        log.info("Downloading default watermark font from {}", downloadUrl);
        try (InputStream in = rt.execute(URI.create(downloadUrl),
                org.springframework.http.HttpMethod.GET, null,
                (org.springframework.http.client.ClientHttpResponse resp) -> {
                    if (!resp.getStatusCode().is2xxSuccessful()) {
                        throw new IOException("HTTP " + resp.getStatusCode() + " downloading font");
                    }
                    return resp.getBody();
                })) {
            if (in == null) return null;
            Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
        }
        if (!Files.isRegularFile(dest) || Files.size(dest) < 1024) {
            Files.deleteIfExists(dest);
            return null;
        }
        return dest;
    }
}
