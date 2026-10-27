package org.localroute.app.map;

import java.util.List;
import java.util.Locale;

/**
 * 选择地图引擎的纯逻辑（不碰 Android 类，方便 JVM 单测）。
 *
 * <p>高德官方 SDK 只提供 arm 动态库，模拟器（x86_64）装不进去；Key 也可能没配。
 * 两个条件任一不满足就退回 MapLibre + 栅格瓦片，保证应用在任何设备上都能显示地图。
 */
public final class MapBackendPolicy {

    public enum Backend {
        /** 高德官方 3D SDK，底图与路线都在 GCJ-02 上，需要 Key + arm 架构。 */
        AMAP_SDK,
        /** MapLibre + 栅格瓦片（高德瓦片/OSM），显示层做 WGS-84↔GCJ-02 换算。 */
        RASTER_TILES
    }

    /** 选择结果，带一句可以直接显示给用户的原因。 */
    public static final class Decision {
        private final Backend backend;
        private final String reason;

        Decision(Backend backend, String reason) {
            this.backend = backend;
            this.reason = reason;
        }

        public Backend backend() {
            return backend;
        }

        public String reason() {
            return reason;
        }

        public boolean usesAmapSdk() {
            return backend == Backend.AMAP_SDK;
        }

        @Override
        public String toString() {
            return backend + " (" + reason + ")";
        }
    }

    private MapBackendPolicy() {
    }

    public static Decision choose(String amapKey, List<String> supportedAbis) {
        if (amapKey == null || amapKey.trim().isEmpty()) {
            return new Decision(Backend.RASTER_TILES, "未配置 AMAP_KEY，使用栅格瓦片底图");
        }
        if (supportedAbis == null || supportedAbis.isEmpty()) {
            return new Decision(Backend.RASTER_TILES, "读不到 CPU 架构信息，保守使用栅格瓦片底图");
        }
        for (String abi : supportedAbis) {
            if (abi == null) {
                continue;
            }
            String value = abi.trim().toLowerCase(Locale.US);
            if ("arm64-v8a".equals(value) || "armeabi-v7a".equals(value)) {
                return new Decision(Backend.AMAP_SDK, "检测到 " + value + " 与 AMAP_KEY，启用高德官方 SDK");
            }
        }
        return new Decision(Backend.RASTER_TILES,
                "当前架构 " + supportedAbis.get(0) + " 不在高德 SDK 支持范围（仅 arm），退回栅格瓦片底图");
    }
}
