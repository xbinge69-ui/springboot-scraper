package com.example.scraper.service;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link BunnyAssetService}'s zone-resolution logic —
 * the part that decides which configured zones get a given upload and
 * in what order. Doesn't exercise the real HTTP I/O; the multi-zone
 * behaviour is exercised end-to-end by
 * {@link VideoEnrichmentServiceTest#enrich_multiZone_recordsPerZoneUrlsAndBackupEmbed()}.
 *
 * <p>Builds a {@link BunnyAssetService} instance, sets the legacy
 * primary zone + mirror zones via reflection, then runs
 * {@code initZones()} and asserts on the resulting zone list /
 * selection behaviour.
 */
class BunnyAssetServiceZoneTest {

    @Test
    void initZones_primaryFirstThenMirrorsInDeclaredOrder() throws Exception {
        BunnyAssetService svc = newSvc(
                /*legacyZoneName*/ "spankycouples",
                /*legacyCdn*/     "spankycouples.b-cdn.net",
                /*legacyApiKey*/  "k1",
                /*legacyEndpoint*/"https://storage.bunnycdn.com",
                List.of(
                        zone("youjav",  "youjav.b-cdn.net",  "k2"),
                        zone("mirror3", "mirror3.b-cdn.net", "k3")));

        assertThat(svc.getZoneKeys())
                .as("primary first, mirrors in declared order")
                .containsExactly("spankycouples", "youjav", "mirror3");
    }

    @Test
    void resolveTargets_nullSelectionMeansAllZones() throws Exception {
        BunnyAssetService svc = newSvc("primary", "p.b-cdn.net", "k", null,
                List.of(zone("mirror", "m.b-cdn.net", "k")));

        // Call via reflection — resolveTargets is private.
        List<BunnyAssetService.ResolvedZone> all = invokeResolveTargets(svc, null);
        assertThat(all).extracting(BunnyAssetService.ResolvedZone::key)
                .containsExactly("primary", "mirror");
    }

    @Test
    void resolveTargets_filtersBySelectionAndPreservesCanonicalOrder() throws Exception {
        BunnyAssetService svc = newSvc("primary", "p.b-cdn.net", "k", null,
                List.of(
                        zone("youjav",  "y.b-cdn.net", "k"),
                        zone("mirror3", "m3.b-cdn.net", "k")));

        // Caller lists the zones in the WRONG order — service must
        // sort them back to canonical (primary first).
        List<BunnyAssetService.ResolvedZone> picked = invokeResolveTargets(
                svc, List.of("mirror3", "primary"));
        assertThat(picked).extracting(BunnyAssetService.ResolvedZone::key)
                .containsExactly("primary", "mirror3");
    }

    @Test
    void resolveTargets_unknownKeyThrows() throws Exception {
        BunnyAssetService svc = newSvc("primary", "p.b-cdn.net", "k", null, List.of());

        assertThatThrownBy(() -> invokeResolveTargets(svc, Set.of("primary", "ghost")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ghost")
                .hasMessageContaining("primary");
    }

    @Test
    void resolveTargets_emptySelectionThrows() throws Exception {
        BunnyAssetService svc = newSvc("primary", "p.b-cdn.net", "k", null, List.of());

        assertThatThrownBy(() -> invokeResolveTargets(svc, new ArrayList<>()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No Bunny zones selected");
    }

    @Test
    void firstUrl_returnsPrimaryOrNull() {
        // Static helper — easy to test directly.
        assertThat(BunnyAssetService.firstUrl(null)).isNull();
        assertThat(BunnyAssetService.firstUrl(java.util.Map.of())).isNull();
        // LinkedHashMap preserves insertion order — firstUrl returns the
        // first value, which is the primary zone's URL.
        var map = new java.util.LinkedHashMap<String, String>();
        map.put("primary", "https://primary/");
        map.put("mirror",  "https://mirror/");
        assertThat(BunnyAssetService.firstUrl(map)).isEqualTo("https://primary/");
    }

    @Test
    void initZones_disabledMirrorIsSkipped() throws Exception {
        BunnyProperties props = new BunnyProperties();
        BunnyProperties.Zone disabled = zone("hidden", "h.b-cdn.net", "k");
        disabled.setEnabled(false);
        props.setZones(List.of(disabled, zone("youjav", "y.b-cdn.net", "k")));

        BunnyAssetService svc = newSvc("primary", "p.b-cdn.net", "k", null, null);
        inject(svc, "properties", props);
        svc.initZones();

        assertThat(svc.getZoneKeys())
                .as("disabled mirror is skipped")
                .containsExactly("primary", "youjav");
    }

    // ---- helpers ----

    private static BunnyAssetService newSvc(String legacyZone, String legacyCdn,
                                            String legacyKey, String legacyEndpoint,
                                            List<BunnyProperties.Zone> mirrors) throws Exception {
        BunnyAssetService svc = new BunnyAssetService(new BunnyProperties());
        inject(svc, "storageZoneName",  legacyZone);
        inject(svc, "cdnHostname",      legacyCdn);
        inject(svc, "storageApiKey",    legacyKey);
        inject(svc, "storageEndpoint",  legacyEndpoint == null ? "https://storage.bunnycdn.com" : legacyEndpoint);
        if (mirrors != null) {
            BunnyProperties props = new BunnyProperties();
            props.setZones(new ArrayList<>(mirrors));
            inject(svc, "properties", props);
        }
        svc.initZones();
        return svc;
    }

    private static BunnyProperties.Zone zone(String key, String cdn, String apiKey) {
        BunnyProperties.Zone z = new BunnyProperties.Zone();
        z.setKey(key);
        z.setCdnHostname(cdn);
        z.setStorageApiKey(apiKey);
        z.setStorageEndpoint("https://storage.bunnycdn.com");
        return z;
    }

    /**
     * Invokes the private {@code resolveTargets} via reflection.
     * Unwraps {@link java.lang.reflect.InvocationTargetException} and
     * re-throws the original cause as a {@link RuntimeException} so
     * the caller can assert on it without having to declare a checked
     * {@code throws Throwable}.
     */
    @SuppressWarnings("unchecked")
    private static List<BunnyAssetService.ResolvedZone> invokeResolveTargets(
            BunnyAssetService svc, java.util.Collection<String> selected) {
        try {
            java.lang.reflect.Method m = BunnyAssetService.class
                    .getDeclaredMethod("resolveTargets", java.util.Collection.class);
            m.setAccessible(true);
            return (List<BunnyAssetService.ResolvedZone>) m.invoke(svc, selected);
        } catch (java.lang.reflect.InvocationTargetException ite) {
            Throwable cause = ite.getCause();
            if (cause instanceof RuntimeException re) throw re;
            if (cause instanceof Error err) throw err;
            throw new RuntimeException(cause);
        } catch (ReflectiveOperationException roe) {
            throw new RuntimeException(roe);
        }
    }

    private static void inject(Object target, String field, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }
}
