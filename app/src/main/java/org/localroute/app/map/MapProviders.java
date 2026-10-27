package org.localroute.app.map;

import java.util.Locale;

/**
 * 地图底图来源的「能力声明」层。
 *
 * <p>当前实际渲染只有栅格瓦片（高德 / OSM），官方 SDK 还没有接入：
 * 接高德 SDK 需要开放平台 Key + 包名/SHA1 白名单，Key 从 `local.properties` 的
 * `amapKey=...` 注入 {@code BuildConfig.AMAP_KEY}，不进仓库。这个类只负责三件事：
 * 判断 Key 在不在、生成给用户看的一句话说明、以及把 Key 打码后用于日志。</p>
 */
public final class MapProviders {

    /** 高德 SDK 需要但当前缺失/已配置的状态。 */
    public enum SdkStatus {
        /** 没配 Key，只能用栅格瓦片。 */
        KEY_MISSING,
        /** 配了 Key，但官方 SDK 还没接进来，仍走栅格瓦片。 */
        KEY_PRESENT_SDK_PENDING,
        /** 官方 SDK 已接入（当前代码没有这条路径，保留给后续版本）。 */
        SDK_ACTIVE
    }

    private MapProviders() {
    }

    public static boolean hasKey(String amapKey) {
        return amapKey != null && !amapKey.trim().isEmpty();
    }

    /**
     * 当前状态：有 Key 也只到「待接入」，因为本版本没有引入高德 SDK 依赖。
     */
    public static SdkStatus status(String amapKey) {
        return hasKey(amapKey) ? SdkStatus.KEY_PRESENT_SDK_PENDING : SdkStatus.KEY_MISSING;
    }

    /** Key 打码：只留前 4 位与后 4 位，避免整串出现在日志/截图里。 */
    public static String maskKey(String amapKey) {
        if (!hasKey(amapKey)) {
            return "（未配置）";
        }
        String key = amapKey.trim();
        if (key.length() <= 8) {
            return "****";
        }
        return String.format(Locale.US, "%s…%s", key.substring(0, 4),
                key.substring(key.length() - 4));
    }

    /**
     * 给界面用的一句话说明；两种状态都必须让用户知道「现在用的是瓦片，不是官方 SDK」。
     */
    public static String note(String amapKey) {
        if (hasKey(amapKey)) {
            return "高德 Key 已配置（" + maskKey(amapKey)
                    + "）：本版本仍使用栅格瓦片，官方 SDK 适配层待接入";
        }
        return "未配置高德 Key：当前使用非官方栅格瓦片；申请到 Key 后填入 local.properties 的 amapKey=…";
    }
}
