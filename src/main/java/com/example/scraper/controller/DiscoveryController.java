package com.example.scraper.controller;

import com.example.scraper.service.IxxxDiscoveryService;
import com.example.scraper.service.IxxxDiscoveryService.DiscoveryResult;
import com.example.scraper.service.IxxxDiscoveryService.PageOutcome;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Endpoints that look OUTSIDE this app to discover URLs to import.
 * The batch-pipeline UI calls /api/discovery/ixxx to import every
 * external video-page link from a search-results page on ixxx.com.
 *
 * <p>Source site is auto-detected from the page — see
 * {@link IxxxDiscoveryService} for the bucketing logic. Callers may
 * pin a specific source with the {@code sourceHost} field, and may
 * extend the scrape across pagination with the {@code pages} field
 * (e.g. {@code "2,3,5"}, {@code "2-5"}, {@code "1-3,5,7-9"}).
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
        String pages = null;
        Object p = body.get("pages");
        if (p instanceof String && !((String) p).isBlank()) {
            pages = ((String) p).trim();
        }

        try {
            DiscoveryResult result = ixxxService.discover(url, sourceHost, videosOnly, pages);
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
            if (pages != null) {
                // Echo what the service parsed so the UI can confirm its
                // input was understood. requestedPages is always present
                // (single-element list for the legacy single-page path);
                // pagesFetched is the subset that didn't fail.
                resp.put("requestedPages", result.requestedPages());
                resp.put("pagesFetched", result.pagesFetched());
                resp.put("perPage", serializePerPage(result.perPage()));
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

    /**
     * Flattens {@link PageOutcome} into a JSON-safe shape for the UI.
     * The buckets map is omitted — the aggregate is already exposed via
     * {@code result.sources()} / {@code result.urls()} — and we keep
     * only the counts and any error per page.
     */
    private static List<Map<String, Object>> serializePerPage(List<PageOutcome> perPage) {
        if (perPage == null || perPage.isEmpty()) return List.of();
        List<Map<String, Object>> out = new ArrayList<>(perPage.size());
        for (PageOutcome p : perPage) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("page", p.page());
            m.put("requestedUrl", p.requestedUrl());
            m.put("redirectsFound", p.redirectsFound());
            m.put("redirectsResolved", p.redirectsResolved());
            m.put("totalUrls", p.totalUrls());
            if (p.error() != null) m.put("error", p.error());
            out.add(m);
        }
        return out;
    }

    private static Map<String, Object> err(String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", message);
        return m;
    }
}
