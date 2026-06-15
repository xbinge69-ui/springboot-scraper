package com.example.scraper.controller;

import com.example.scraper.service.IxxxDiscoveryService;
import com.example.scraper.service.IxxxDiscoveryService.DiscoveryResult;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Endpoints that look OUTSIDE this app to discover URLs to import.
 * The batch-pipeline UI calls /api/discovery/ixxx to import every
 * external video-page link from a search-results page on ixxx.com.
 *
 * <p>Source site is auto-detected from the page — see
 * {@link IxxxDiscoveryService} for the bucketing logic. Callers may
 * pin a specific source with the {@code sourceHost} field.
 */
@Controller
@RequestMapping("/api/discovery")
public class DiscoveryController {

    private final IxxxDiscoveryService ixxxService;

    public DiscoveryController(IxxxDiscoveryService ixxxService) {
        this.ixxxService = ixxxService;
    }

    @PostMapping(value = "/ixxx", produces = "application/json")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> discover(@RequestBody Map<String, Object> body) {
        Object urlRaw = body == null ? null : body.get("url");
        if (!(urlRaw instanceof String) || ((String) urlRaw).isBlank()) {
            return ResponseEntity.badRequest().body(err("url is required"));
        }
        String url = (String) urlRaw;
        boolean videosOnly = !Boolean.FALSE.equals(body.get("videosOnly"));
        String sourceHost = null;
        Object sh = body.get("sourceHost");
        if (sh instanceof String && !((String) sh).isBlank()) {
            sourceHost = ((String) sh).trim();
        }

        try {
            DiscoveryResult result = ixxxService.discover(url, sourceHost, videosOnly);
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("sourceUrl", result.sourceUrl());
            resp.put("detectedSource", result.detectedSource());
            resp.put("sources", result.sources());
            resp.put("count", result.urls().size());
            resp.put("urls", result.urls());
            resp.put("videosOnly", videosOnly);
            resp.put("redirectsFound", result.redirectsFound());
            resp.put("redirectsResolved", result.redirectsResolved());
            if (sourceHost != null) {
                resp.put("requestedSource", sourceHost);
            }
            return ResponseEntity.ok(resp);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(err(e.getMessage()));
        } catch (Exception e) {
            Map<String, Object> body2 = new LinkedHashMap<>();
            body2.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            return ResponseEntity.status(502).body(body2);
        }
    }

    private static Map<String, Object> err(String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", message);
        return m;
    }
}
