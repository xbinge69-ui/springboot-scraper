package com.example.scraper.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Multi-zone Bunny.net uploader. Holds the <em>primary</em> zone
 * (spankycouples — configured via the legacy {@code app.bunny.*} flat
 * keys for backward compatibility) and any <em>mirror</em> zones listed
 * under {@code app.bunny.zones[i].*} in {@link BunnyProperties}.
 *
 * <p>Uploads go to one or more zones in a single call. The result is a
 * {@code LinkedHashMap} from {@code zoneKey → publicUrl} so the caller
 * can pick a canonical URL (e.g. for {@code embedUrl}) and store the
 * others as fallbacks. The order in the map matches the order in
 * {@link #getZoneKeys()} (primary first), which keeps the JSON
 * output stable.
 *
 * <h3>Selection</h3>
 * Callers pass a {@code Collection<String> selectedKeys} to {@link
 * #uploadBytesMulti}. A {@code null} selection means "upload to every
 * configured zone" (the default behaviour the API exposed before
 * multi-zone support was added). A non-null selection uploads only to
 * the named zones that are actually configured; unknown keys raise
 * {@link IllegalArgumentException}.
 */
@Service
public class BunnyAssetService {

    private static final Logger log = LoggerFactory.getLogger(BunnyAssetService.class);

    /** Read timeout for streaming uploads. Set to 5 min so a 100 MB file at 1 MB/s has headroom. */
    private static final int UPLOAD_READ_TIMEOUT_MS = 5 * 60 * 1000;
    private static final int UPLOAD_CONNECT_TIMEOUT_MS = 15_000;
    private static final int STREAM_BUFFER_BYTES = 64 * 1024;
    /** 12 hex chars = 48 bits → birthday collision around 16M objects. */
    private static final int UUID_HEX_CHARS = 12;

    @Value("${app.bunny.enabled:false}")
    private boolean bunnyEnabled;

    /**
     * The primary zone's storage name. Kept on the legacy
     * {@code app.bunny.storage.zone-name} key so existing
     * {@code application.properties} files don't need to be rewritten.
     */
    @Value("${app.bunny.storage.zone-name:}")
    private String storageZoneName;

    /**
     * Bunny storage API key. Sourced from the {@code BUNNY_STORAGE_API_KEY}
     * environment variable (resolved via
     * {@code @Value("${BUNNY_STORAGE_API_KEY:}")}). The default value is
     * empty, so the key is never in source. The {@code @Value} is
     * intentionally indirect (referencing the env var) so Spring's
     * property binding can find it without the dev having to set
     * {@code -DBUNNY_STORAGE_API_KEY=…} on the command line.
     */
    @Value("${app.bunny.storage.api-key:}")
    private String storageApiKey;

    @Value("${app.bunny.storage.endpoint:https://storage.bunnycdn.com}")
    private String storageEndpoint;

    @Value("${app.bunny.storage-region:}")
    private String storageRegion;

    @Value("${app.bunny.cdn.hostname:}")
    private String cdnHostname;

    @Value("${app.bunny.folder:uploads}")
    private String folder;

    private final BunnyProperties properties;
    /**
     * Combined zone list in stable order: primary first (if configured),
     * then every additional zone from {@link BunnyProperties#getZones()}.
     * Built once in {@link #initZones()}.
     */
    private List<ResolvedZone> zones = List.of();
    /** Index by key for fast lookup during upload. */
    private Map<String, ResolvedZone> zonesByKey = Map.of();
    /** Stable ordering by key — what {@link #getZoneKeys()} returns. */
    private List<String> zoneKeys = List.of();

    public BunnyAssetService(BunnyProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    void initZones() {
        List<ResolvedZone> all = new ArrayList<>();
        // Primary zone from legacy flat keys. We treat it as the
        // "primary" regardless of order, so the canonical embed URL
        // still comes from spankycouples unless the user explicitly
        // removes the legacy config.
        if (storageZoneName != null && !storageZoneName.isBlank()) {
            all.add(new ResolvedZone(
                    storageZoneName.trim(),
                    cdnHostname == null ? "" : cdnHostname.trim(),
                    storageApiKey,
                    storageEndpoint,
                    storageRegion,
                    0));
        }
        if (properties != null && properties.getZones() != null) {
            int order = all.size();
            for (BunnyProperties.Zone z : properties.getZones()) {
                if (z == null || z.getKey() == null || z.getKey().isBlank()) continue;
                if (!z.isEnabled()) continue;
                String endpoint = (z.getStorageEndpoint() == null || z.getStorageEndpoint().isBlank())
                        ? "https://storage.bunnycdn.com" : z.getStorageEndpoint();
                all.add(new ResolvedZone(
                        z.getKey().trim(),
                        z.getCdnHostname() == null ? "" : z.getCdnHostname().trim(),
                        z.getStorageApiKey(),
                        endpoint,
                        z.getStorageRegion(),
                        order++));
            }
        }
        // De-duplicate by key (primary wins if a mirror reuses the key)
        // and preserve insertion order so the JSON output is stable.
        LinkedHashMap<String, ResolvedZone> deduped = new LinkedHashMap<>();
        for (ResolvedZone z : all) {
            if (!deduped.containsKey(z.key())) {
                deduped.put(z.key(), z);
            } else {
                log.warn("Bunny zone key '{}' is configured more than once; keeping the first entry", z.key());
            }
        }
        this.zones = List.copyOf(deduped.values());
        this.zonesByKey = Map.copyOf(deduped);
        this.zoneKeys = List.copyOf(deduped.keySet());
        if (this.zones.isEmpty()) {
            log.warn("No Bunny zones configured (app.bunny.storage.zone-name is blank and "
                    + "app.bunny.zones is empty). Uploads will be no-ops.");
        } else {
            log.info("BunnyAssetService initialised with {} zone(s): {}",
                    this.zones.size(), this.zoneKeys);
        }
    }

    /**
     * Generate a 12-hex-char identifier for object keys. Callers can use the
     * same UUID across the 3 derivatives of one video so a single
     * {@code ls <slug>-<uuid>*} reveals the whole set.
     */
    public static String newRequestUuid() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, UUID_HEX_CHARS);
    }

    public String getFolder() {
        return folder;
    }

    /**
     * Ordered list of zone keys configured at startup. The first entry
     * is the primary (legacy) zone; the rest are mirrors.
     */
    public List<String> getZoneKeys() {
        return zoneKeys;
    }

    /**
     * Convenience for single-zone callers (legacy + tests + the
     * actress-portrait fetch path). Uploads to every configured zone
     * and returns the primary zone's URL. When bunny is disabled this
     * returns the synthetic URL for the primary zone without hitting
     * the network.
     */
    public String uploadBytes(byte[] content, String objectPath, String contentType) throws IOException {
        Map<String, String> urls = uploadBytesMulti(content, objectPath, contentType, null);
        return firstUrl(urls);
    }

    /** Path overload of {@link #uploadBytes(byte[], String, String)}. */
    public String uploadBytes(Path source, String objectPath, String contentType) throws IOException {
        Map<String, String> urls = uploadBytesMulti(source, objectPath, contentType, null);
        return firstUrl(urls);
    }

    /**
     * Streaming multi-zone upload from a {@link Path}. Avoids loading
     * the whole file into a {@code byte[]}, so a 2 GB upload doesn't
     * OOM the JVM.
     *
     * <p><b>Fail-soft per zone.</b> If a single zone's PUT fails
     * (HTTP 401, 403, 5xx, network error, etc.), the error is logged
     * at WARN and the upload continues with the remaining zones.
     * The returned map only contains successful zones — the caller
     * computes the "missing from result" set against
     * {@link #getZoneKeys()} to detect which ones failed and surface
     * them to the user.
     *
     * <p>Rationale: with multiple Bunny pull zones, one
     * misconfigured zone shouldn't take down the whole batch — the
     * canonical entry should still land on the working zone(s).
     * Pre-multi-zone behaviour was fail-loud (single zone → fail the
     * upload); that still applies when only one zone is configured,
     * because the "missing from result" set then equals the "all
     * configured" set, and the orchestrator can decide how strict to
     * be.
     *
     * @param source         the local file to upload
     * @param objectPath     Bunny object key (e.g. {@code videos/slug-uuid.compressed.mp4})
     * @param contentType    MIME type set as the PUT {@code Content-Type}
     * @param selectedKeys   which zones to upload to. {@code null} =
     *                       every configured zone (the default batch
     *                       behaviour). An empty collection raises
     *                       {@link IllegalArgumentException}.
     * @return zoneKey → publicUrl for each zone uploaded to
     *         successfully (failed zones are absent from the map but
     *         logged)
     */
    public Map<String, String> uploadBytesMulti(Path source, String objectPath, String contentType,
                                                Collection<String> selectedKeys) throws IOException {
        List<ResolvedZone> targets = resolveTargets(selectedKeys);
        if (!bunnyEnabled) {
            return syntheticUrls(targets, objectPath);
        }
        ensureConfigured(targets);
        LinkedHashMap<String, String> urls = new LinkedHashMap<>();
        // Sequential rather than parallel: each upload reuses the
        // same source Path via independent HTTP connections. The
        // pipeline serialises everything else (ffmpeg, catalog
        // write) so parallelizing here would just risk tripping the
        // CDN's per-key rate limit. If a future use case needs
        // parallel uploads, swap to an ExecutorService.
        for (ResolvedZone zone : targets) {
            try {
                HttpURLConnection conn = openConnection(zone, objectPath, contentType);
                try (OutputStream out = conn.getOutputStream()) {
                    byte[] buf = new byte[STREAM_BUFFER_BYTES];
                    int read;
                    try (var in = Files.newInputStream(source)) {
                        while ((read = in.read(buf)) != -1) {
                            out.write(buf, 0, read);
                        }
                    }
                }
                urls.put(zone.key(), checkStatusAndReturn(zone, objectPath, conn));
            } catch (IOException e) {
                // Fail-soft per zone: log and continue. The caller
                // (VideoEnrichmentService) detects the missing zone in
                // the returned map and adds a warning so the user
                // sees which CDN is unreachable.
                log.warn("Bunny upload to zone '{}' failed (other zones will still receive the upload): {}",
                        zone.key(), e.getMessage());
            }
        }
        return urls;
    }

    /**
     * Byte-array overload of {@link #uploadBytesMulti(Path, String, String, Collection)}.
     * Same fail-soft semantics — see that method for the full rationale.
     */
    public Map<String, String> uploadBytesMulti(byte[] content, String objectPath, String contentType,
                                                Collection<String> selectedKeys) throws IOException {
        List<ResolvedZone> targets = resolveTargets(selectedKeys);
        if (!bunnyEnabled) {
            return syntheticUrls(targets, objectPath);
        }
        ensureConfigured(targets);
        LinkedHashMap<String, String> urls = new LinkedHashMap<>();
        for (ResolvedZone zone : targets) {
            try {
                HttpURLConnection conn = openConnection(zone, objectPath, contentType);
                try (OutputStream out = conn.getOutputStream()) {
                    out.write(content);
                }
                urls.put(zone.key(), checkStatusAndReturn(zone, objectPath, conn));
            } catch (IOException e) {
                log.warn("Bunny upload to zone '{}' failed (other zones will still receive the upload): {}",
                        zone.key(), e.getMessage());
            }
        }
        return urls;
    }

    private List<ResolvedZone> resolveTargets(Collection<String> selectedKeys) {
        if (selectedKeys == null) {
            return zones;
        }
        if (selectedKeys.isEmpty()) {
            throw new IllegalArgumentException(
                    "No Bunny zones selected for upload — pass null to upload to every configured zone");
        }
        List<ResolvedZone> out = new ArrayList<>(selectedKeys.size());
        List<String> unknown = new ArrayList<>();
        for (String key : selectedKeys) {
            ResolvedZone z = zonesByKey.get(key);
            if (z != null) {
                out.add(z);
            } else {
                unknown.add(key);
            }
        }
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("Unknown Bunny zone(s): " + unknown
                    + " — configured: " + zoneKeys);
        }
        // Preserve canonical order (primary first) regardless of how
        // the caller listed the keys — makes the JSON output stable.
        out.sort((a, b) -> Integer.compare(a.order(), b.order()));
        return out;
    }

    private void ensureConfigured(List<ResolvedZone> targets) {
        for (ResolvedZone z : targets) {
            if (z.key() == null || z.key().isBlank()
                    || z.apiKey() == null || z.apiKey().isBlank()) {
                throw new IllegalStateException(
                        "Bunny enabled but zone '" + z.key() + "' is missing api-key or storage-zone-name");
            }
        }
    }

    private HttpURLConnection openConnection(ResolvedZone zone, String objectPath, String contentType)
            throws IOException {
        // PUT URL: {storage-endpoint}/{storage-zone-name}/{object-path}.
        // Default endpoint is https://storage.bunnycdn.com; a regional edge
        // (e.g. "ny") is prepended to .storage.bunnycdn.com.
        String host = zone.endpoint();
        if (host == null || host.isBlank()) {
            host = (zone.region() == null || zone.region().isBlank())
                    ? "storage.bunnycdn.com"
                    : zone.region() + ".storage.bunnycdn.com";
        }
        URL url = new URL(host + "/" + zone.key() + "/" + normalizeObjectPath(objectPath));
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("PUT");
        conn.setDoOutput(true);
        conn.setRequestProperty("AccessKey", zone.apiKey());
        conn.setRequestProperty("Content-Type", contentType);
        conn.setConnectTimeout(UPLOAD_CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(UPLOAD_READ_TIMEOUT_MS);
        return conn;
    }

    private String checkStatusAndReturn(ResolvedZone zone, String objectPath, HttpURLConnection conn)
            throws IOException {
        int status = conn.getResponseCode();
        if (status < 200 || status >= 300) {
            throw new IOException("Bunny upload to zone '" + zone.key()
                    + "' failed with status " + status + " for " + objectPath);
        }
        return publicUrlFor(zone, objectPath);
    }

    private String publicUrlFor(ResolvedZone zone, String objectPath) {
        // Public read URL: https://{cdn-hostname}/{object-path}
        return "https://" + stripTrailingSlash(zone.cdnHostname()) + "/" + normalizeObjectPath(objectPath);
    }

    private Map<String, String> syntheticUrls(List<ResolvedZone> targets, String objectPath) {
        // Used when bunny is disabled — return a public URL per zone
        // without hitting the network. Lets dev environments without
        // bunny credentials still produce sane catalog entries.
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        for (ResolvedZone z : targets) {
            out.put(z.key(), publicUrlFor(z, objectPath));
        }
        return out;
    }

    /**
     * Return the primary zone's URL from a multi-zone upload map. Falls
     * back to the first available entry, then to {@code null} when the
     * map is empty.
     */
    public static String firstUrl(Map<String, String> zoneUrls) {
        if (zoneUrls == null || zoneUrls.isEmpty()) return null;
        // Insertion order is canonical (primary first) because
        // LinkedHashMap preserves the order resolveTargets used.
        return zoneUrls.values().iterator().next();
    }

    private String normalizeObjectPath(String objectPath) {
        return objectPath.startsWith("/") ? objectPath.substring(1) : objectPath;
    }

    private String stripTrailingSlash(String s) {
        if (s == null || s.isBlank()) {
            return "";
        }
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    /**
     * Immutable snapshot of one configured Bunny zone. {@code order} is
     * the index in the final ordered list (0 = primary) — used by
     * {@link #resolveTargets} to keep multi-zone output in canonical
     * order regardless of how the caller listed the keys.
     */
    record ResolvedZone(String key, String cdnHostname, String apiKey,
                        String endpoint, String region, int order) {
    }

    /** Reserved for future use — surfaces the runtime-configured zone order to ops tooling. */
    public String describeZones() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < zones.size(); i++) {
            ResolvedZone z = zones.get(i);
            if (i > 0) sb.append(", ");
            sb.append(z.key()).append('@').append(stripTrailingSlash(z.cdnHostname() == null ? "" : z.cdnHostname()));
        }
        return sb.toString();
    }

    /**
     * @return comma-separated list of zone keys in canonical order.
     *         Used by the UI to build per-zone checkboxes. Lowercase
     *         so the JSON side can match keys case-insensitively.
     */
    public List<String> getZoneKeysLower() {
        List<String> out = new ArrayList<>(zoneKeys.size());
        for (String k : zoneKeys) {
            out.add(k.toLowerCase(Locale.ROOT));
        }
        return Collections.unmodifiableList(out);
    }
}
