package com.example.scraper.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration for additional Bunny.net pull zones. The primary zone
 * (spankycouples) is still configured via the legacy {@code app.bunny.storage.*}
 * and {@code app.bunny.cdn.*} properties on {@link BunnyAssetService} so the
 * existing config files don't need to change. Anything in
 * {@link #zones} adds a mirror zone that every upload is replicated to.
 *
 * <p>Sample {@code application.properties}:
 * <pre>
 * # Primary zone (legacy config) — spankycouples
 * app.bunny.storage.zone-name=spankycouples
 * app.bunny.cdn.hostname=SpankyCouples6969.b-cdn.net
 *
 * # Optional mirror zones (replicated to alongside the primary)
 * app.bunny.zones[0].key=youjav
 * app.bunny.zones[0].cdn-hostname=youjav.b-cdn.net
 * app.bunny.zones[0].storage.api-key=...
 * app.bunny.zones[0].storage.endpoint=https://storage.bunnycdn.com
 * </pre>
 *
 * <p>Spring binds kebab-case and camelCase interchangeably
 * ({@code cdn-hostname} ↔ {@code cdnHostname},
 * {@code storage.api-key} ↔ {@code storage.apiKey}), so the exact
 * casing used in the file doesn't matter.
 *
 * <p>Each zone gets a stable {@link #getKey() key} used by the
 * controller/UI to address it. The key is also persisted on
 * {@code VideoCatalogEntry.cdnZoneKeys} so downstream consumers can
 * tell which mirror a given entry lives on.
 */
@ConfigurationProperties(prefix = "app.bunny")
public class BunnyProperties {

    private List<Zone> zones = new ArrayList<>();

    public List<Zone> getZones() {
        return zones;
    }

    public void setZones(List<Zone> zones) {
        this.zones = (zones == null) ? new ArrayList<>() : zones;
    }

    public static class Zone {
        /** Short identifier (e.g. "youjav"). Surfaced in the UI and persisted on catalog entries. */
        private String key;
        /** Public CDN hostname (e.g. "youjav.b-cdn.net"). Used to build the public read URL. */
        private String cdnHostname;
        /** Storage API key. Sourced from the env var named {@code BUNNY_<KEY>_STORAGE_API_KEY} when blank. */
        private String storageApiKey;
        /** Storage API base URL. Default {@code https://storage.bunnycdn.com}. */
        private String storageEndpoint = "https://storage.bunnycdn.com";
        /** Optional regional edge prefix (e.g. "ny" → {@code ny.storage.bunnycdn.com}). */
        private String storageRegion;
        /** Set false to temporarily disable uploads to this zone without removing the config. */
        private boolean enabled = true;

        public String getKey() { return key; }
        public void setKey(String key) { this.key = key; }

        public String getCdnHostname() { return cdnHostname; }
        public void setCdnHostname(String cdnHostname) { this.cdnHostname = cdnHostname; }

        public String getStorageApiKey() { return storageApiKey; }
        public void setStorageApiKey(String storageApiKey) { this.storageApiKey = storageApiKey; }

        public String getStorageEndpoint() { return storageEndpoint; }
        public void setStorageEndpoint(String storageEndpoint) { this.storageEndpoint = storageEndpoint; }

        public String getStorageRegion() { return storageRegion; }
        public void setStorageRegion(String storageRegion) { this.storageRegion = storageRegion; }

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }
}
