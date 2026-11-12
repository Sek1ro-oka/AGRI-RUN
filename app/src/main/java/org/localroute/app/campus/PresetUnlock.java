package org.localroute.app.campus;

import java.util.Locale;

/**
 * 预设路线的解锁状态：激活码正确后才把 A操 / B操 加入路线列表。
 *
 * <p>仅保存一个布尔标记与激活码校验，不涉及网络；预置内容对所有人可见，
 * 激活码只是本地开关。校验函数保持纯逻辑，便于 JVM 单测。
 */
public final class PresetUnlock {

    /** 与校园快跑共用的 SharedPreferences 文件名。 */
    public static final String PREFS_NAME = "campus_run";

    /** 解锁标记：true 表示已兑换，预设路线可用。 */
    public static final String KEY_UNLOCKED = "presets_unlocked";

    private static final String CODE = "ZNLU";

    private PresetUnlock() {
    }

    /** 兑换码是否匹配：忽略首尾空白与大小写，但不接受其它内容。 */
    public static boolean matches(String input) {
        return input != null
                && CODE.equals(input.trim().toUpperCase(Locale.US));
    }
}
