package org.localroute.app.entry;

/**
 * 悬浮台磁贴该显示成哪一种状态（纯 Java，便于单测）。
 *
 * <p>磁贴只有一个格子，但真实情形有三种：开关开着且已授权、开关关着、开关开着却没给
 * 「显示在其他应用上层」权限。第三种如果也显示点亮，用户点下去什么也不会出现，只会以为功能坏了；
 * 所以把它单列出来，让磁贴呈「不可用」态并把点击带去授权页。
 */
public enum OverlayTileState {

    /** 开关开着且已授权：悬浮台此刻能被唤起。 */
    ON,
    /** 开关关着。 */
    OFF,
    /** 开关开着但缺悬浮窗权限：状态是真的，画不出来。 */
    NO_PERMISSION;

    public static OverlayTileState from(boolean enabled, boolean canDrawOverlays) {
        if (!enabled) {
            return OFF;
        }
        return canDrawOverlays ? ON : NO_PERMISSION;
    }
}
