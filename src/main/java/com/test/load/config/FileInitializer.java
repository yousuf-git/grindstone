package com.test.load.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ThreadLocalRandom;

@Component
public class FileInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(FileInitializer.class);

    @Value("${loadtest.video-path:./data/video.mp4}")
    private String videoPath;

    @Value("${loadtest.generate-if-missing:false}")
    private boolean generateIfMissing;

    @Value("${loadtest.generate-size-mb:100}")
    private int generateSizeMb;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        File file = Path.of(videoPath).toFile();
        if (file.exists()) {
            log.info("Video file found: {} ({} MB)", file.getAbsolutePath(), file.length() / 1048576);
            return;
        }

        if (!generateIfMissing) {
            log.warn("Video file not found: {}. Set loadtest.generate-if-missing=true to auto-generate.", file.getAbsolutePath());
            return;
        }

        log.info("Generating synthetic video file: {} ({} MB)", file.getAbsolutePath(), generateSizeMb);
        Files.createDirectories(file.toPath().getParent());

        byte[] buffer = new byte[1024 * 1024];
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            for (int i = 0; i < generateSizeMb; i++) {
                ThreadLocalRandom.current().nextBytes(buffer);
                raf.write(buffer);
            }
        }
        log.info("Synthetic file generated: {} MB", file.length() / 1048576);
    }
}
