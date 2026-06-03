package com.example.scraper.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

@Service
public class VideoHostUploadService {

    @Value("${app.pipeline.mock-uploads:true}")
    private boolean mockUploads;

    @Value("${app.upload.doodstream.prefix:https://doodstream.com/d}")
    private String doodPrefix;

    @Value("${app.upload.vidara.prefix:https://vidara.to/v}")
    private String vidaraPrefix;

    public String uploadToDoodstream(String sourceVideoUrl, String slug) {
        if (mockUploads) {
            return normalizePrefix(doodPrefix) + "/" + token(slug + ":dood:" + sourceVideoUrl);
        }
        throw new IllegalStateException("Real Doodstream API upload not configured yet. Set app.pipeline.mock-uploads=true or implement API client.");
    }

    public String uploadToVidara(String sourceVideoUrl, String slug) {
        if (mockUploads) {
            return normalizePrefix(vidaraPrefix) + "/" + token(slug + ":vidara:" + sourceVideoUrl);
        }
        throw new IllegalStateException("Real Vidara API upload not configured yet. Set app.pipeline.mock-uploads=true or implement API client.");
    }

    private String normalizePrefix(String prefix) {
        return prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;
    }

    private String token(String raw) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8))
                .replace("-", "")
                .replace("_", "")
                .substring(0, 12);
    }
}
