package org.localroute.app.campus;

import android.content.SharedPreferences;

import java.util.Locale;

/**
 * 预设路线的激活状态：激活码正确后把 A操 / B操 加入路线列表。
 *
 * <p>仅保存本地标记与激活码校验，不涉及网络；预置内容对所有人可见，
 * 激活码只是本地开关。校验函数保持纯逻辑，便于 JVM 单测。
 *
 * <p>激活码不限制使用次数：每次输入都会补回缺失的 A操 / B操，
 * 已经存在的预设按 id 去重，不会重复添加；用户删除预设后再次输入即可找回。
 */
public final class PresetUnlock {

    /** 与校园快跑共用的 SharedPreferences 文件名。 */
    public static final String PREFS_NAME = "campus_run";

    /** 解锁标记：true 表示已激活，预设路线可用。 */
    public static final String KEY_UNLOCKED = "presets_unlocked";

    /**
     * 添加标记：本次激活后预设是否已经检查/补回。
     * 再次输入激活码会把它重置为 false，让校园快跑补回被删掉的预设。
     */
    public static final String KEY_PRESETS_ADDED = "presets_added";

    /** 旧版一次性锁定留下的标记，升级后清理，不再参与判断。 */
    private static final String LEGACY_KEY_REVISION = "activation_revision";
    private static final String LEGACY_KEY_RESET_PENDING = "activation_reset_pending";

    private static final String CODE = "ZNLU";

    private PresetUnlock() {
    }

    /** 激活码是否匹配：忽略首尾空白与大小写，但不接受其它内容。 */
    public static boolean matches(String input) {
        return input != null
                && CODE.equals(input.trim().toUpperCase(Locale.US));
    }

    /**
     * 清理旧版一次性锁定状态。激活码现在始终可以再次输入，
     * 旧标记只保留「已解锁」，避免升级后预设路线突然消失。
     */
    public static void migrate(SharedPreferences prefs) {
        if (!prefs.contains(LEGACY_KEY_REVISION)
                && !prefs.contains(LEGACY_KEY_RESET_PENDING)) {
            return;
        }
        prefs.edit()
                .remove(LEGACY_KEY_REVISION)
                .remove(LEGACY_KEY_RESET_PENDING)
                .apply();
    }

    /**
     * 使用激活码：标记已解锁，并让校园快跑在下次读取时补回缺失的预设。
     * 已存在的预设按 id 去重，不会重复添加。
     */
    public static void activate(SharedPreferences prefs) {
        prefs.edit()
                .putBoolean(KEY_UNLOCKED, true)
                .putBoolean(KEY_PRESETS_ADDED, false)
                .remove(LEGACY_KEY_REVISION)
                .remove(LEGACY_KEY_RESET_PENDING)
                .apply();
    }
}
