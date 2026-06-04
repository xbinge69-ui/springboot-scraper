package com.example.scraper.video;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Spring {@link ApplicationRunner} that lets the operator exercise
 * {@link FfmpegDerivativeService} from the command line, without taking
 * the project out of "web app" mode.
 *
 * <p>Invocation:
 * <pre>
 *   ./mvnw spring-boot:run -Dspring-boot.run.arguments="--cli.input=... --cli.out=... --cli.name=demo"
 * </pre>
 *
 * <p>Without {@code --cli.input}, this runner is a no-op — so
 * {@code ./mvnw spring-boot:run} still boots the web app on port 8080
 * unchanged.
 */
@Component
public class VideoDerivativeCliRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(VideoDerivativeCliRunner.class);

    private final FfmpegDerivativeService service;

    public VideoDerivativeCliRunner(FfmpegDerivativeService service) {
        this.service = service;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!args.containsOption("cli.input")
                || args.getOptionValues("cli.input").isEmpty()) {
            log.debug("ffmpeg-cli: no --cli.input, skipping");
            return;
        }

        String inputArg  = args.getOptionValues("cli.input").get(0);
        Path input       = Paths.get(inputArg);

        if (!Files.isRegularFile(input)) {
            log.error("ffmpeg-cli: --cli.input is not a readable file: {}", input);
            return;
        }

        Path outDir = args.containsOption("cli.out") && !args.getOptionValues("cli.out").isEmpty()
                ? Paths.get(args.getOptionValues("cli.out").get(0))
                : input.toAbsolutePath().getParent();

        String baseName = args.containsOption("cli.name") && !args.getOptionValues("cli.name").isEmpty()
                ? args.getOptionValues("cli.name").get(0)
                : stripExtension(input.getFileName().toString());

        log.info("[ffmpeg-cli] input  = {}", input.toAbsolutePath());
        log.info("[ffmpeg-cli] outDir = {}", outDir.toAbsolutePath());
        log.info("[ffmpeg-cli] name   = {}", baseName);

        VideoMetadata metadata;
        try {
            metadata = service.probe(input);
        } catch (IOException e) {
            log.error("[ffmpeg-cli] probe failed for {}: {}", input, e.getMessage());
            return;
        }
        log.info("[ffmpeg-cli] probed = {}x{}, {}s, audio={}",
                metadata.width(), metadata.height(),
                String.format("%.2f", metadata.durationSeconds()),
                metadata.hasAudio());

        Derivatives derivatives;
        try {
            derivatives = service.process(input, outDir, baseName, metadata);
        } catch (IOException e) {
            log.error("[ffmpeg-cli] process failed: {}", e.getMessage());
            return;
        }

        log.info("[ffmpeg-cli] compressed -> {} ({} kB)",
                derivatives.compressedPath().toAbsolutePath(),
                String.format("%.1f", sizeKb(derivatives.compressedPath())));
        log.info("[ffmpeg-cli] preview    -> {} ({} kB)",
                derivatives.previewPath().toAbsolutePath(),
                String.format("%.1f", sizeKb(derivatives.previewPath())));
        log.info("[ffmpeg-cli] thumbnail  -> {} ({} kB)",
                derivatives.thumbnailPath().toAbsolutePath(),
                String.format("%.1f", sizeKb(derivatives.thumbnailPath())));
    }

    private static String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return (dot <= 0) ? filename : filename.substring(0, dot);
    }

    private static long sizeKb(Path p) {
        try {
            return Files.size(p) / 1024L;
        } catch (IOException e) {
            return -1L;
        }
    }

    /** Exposed for testing. */
    public static String stripExtensionForTest(String filename) {
        return stripExtension(filename);
    }

    /** Exposed for testing. */
    public static long sizeKbForTest(Path p) {
        return sizeKb(p);
    }

    /** Exposed for testing. */
    public FfmpegDerivativeService getService() {
        return service;
    }
}
