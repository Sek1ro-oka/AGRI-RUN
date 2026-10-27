package org.localroute.app.map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

/** 地图引擎选择的纯逻辑：Key 缺失、架构不匹配时必须稳定退回瓦片底图。 */
public class MapBackendPolicyTest {

    @Test
    public void withoutKey_fallsBackToTiles() {
        MapBackendPolicy.Decision decision = MapBackendPolicy.choose("", Arrays.asList("arm64-v8a"));
        assertEquals(MapBackendPolicy.Backend.RASTER_TILES, decision.backend());
        assertTrue(decision.reason().contains("AMAP_KEY"));
    }

    @Test
    public void nullKey_fallsBackToTiles() {
        assertEquals(MapBackendPolicy.Backend.RASTER_TILES,
                MapBackendPolicy.choose(null, Arrays.asList("arm64-v8a")).backend());
    }

    @Test
    public void blankKeyWithSpaces_fallsBackToTiles() {
        assertEquals(MapBackendPolicy.Backend.RASTER_TILES,
                MapBackendPolicy.choose("   ", Arrays.asList("arm64-v8a")).backend());
    }

    @Test
    public void arm64WithKey_enablesAmapSdk() {
        MapBackendPolicy.Decision decision =
                MapBackendPolicy.choose("abc123", Arrays.asList("arm64-v8a", "armeabi-v7a"));
        assertTrue(decision.usesAmapSdk());
        assertEquals(MapBackendPolicy.Backend.AMAP_SDK, decision.backend());
        assertTrue(decision.reason().contains("arm64-v8a"));
    }

    @Test
    public void armeabiV7aWithKey_enablesAmapSdk() {
        assertTrue(MapBackendPolicy.choose("k", Collections.singletonList("armeabi-v7a")).usesAmapSdk());
    }

    @Test
    public void x86Emulator_fallsBackToTilesEvenWithKey() {
        MapBackendPolicy.Decision decision =
                MapBackendPolicy.choose("abc123", Arrays.asList("x86_64", "x86"));
        assertFalse(decision.usesAmapSdk());
        assertEquals(MapBackendPolicy.Backend.RASTER_TILES, decision.backend());
        assertTrue(decision.reason().contains("x86_64"));
    }

    @Test
    public void unknownArchitectureInfo_fallsBackToTiles() {
        assertEquals(MapBackendPolicy.Backend.RASTER_TILES,
                MapBackendPolicy.choose("abc123", Collections.emptyList()).backend());
        assertEquals(MapBackendPolicy.Backend.RASTER_TILES,
                MapBackendPolicy.choose("abc123", null).backend());
    }

    @Test
    public void mixedAbis_prefersArmWhenPresent() {
        // 真机上 SUPPORTED_ABIS 可能以 arm64 开头也可能以 x86 开头，只要含 arm 就用 SDK。
        assertTrue(MapBackendPolicy.choose("k", Arrays.asList("x86_64", "arm64-v8a")).usesAmapSdk());
    }

    @Test
    public void abiComparisonIsCaseInsensitive() {
        assertTrue(MapBackendPolicy.choose("k", Collections.singletonList("ARM64-V8A")).usesAmapSdk());
    }
}
