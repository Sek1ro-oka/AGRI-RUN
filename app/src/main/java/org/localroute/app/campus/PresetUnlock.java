package org.localroute.app.campus;

import android.content.SharedPreferences;

import java.util.Locale;

/**
 * 预设路线的解锁状态：激活码正确后才把 A操 / B操 加入路线列表。
 *
 * <p>仅保存本地标记与激活码校验，不涉及网络；预置内容对所有人可见，
 * 激活码只是本地开关。校验函数保持纯逻辑，便于 JVM 单测。
 *
 * <p>0.8.0 起激活码是可重新打开入口也无法再次使用的一次性操作：旧版本已激活的
 * 安装升级时保留一次重新输入机会（用于找回被删掉的预设），用掉后永久锁定；
 * 新安装同样只能激活一次。锁定只认本地标记，清除应用数据或卸载重装会重置。
 */
public final class PresetUnlock {

    /** 与校园快跑共用的 SharedPreferences 文件名。 */
    public static final String PREFS_NAME = "campus_run";

    /** 解锁标记：true 表示已激活，预设路线可用。 */
    public static final String KEY_UNLOCKED = "presets_unlocked";

    /**
     * 一次性添加标记：激活码只生效一次，预设加入列表后即置 true。
     * 之后用户删除预设与普通路线一样永久生效，不会被自动补回。
     */
    public static final String KEY_PRESETS_ADDED = "presets_added";

    /** 激活规则版本：1 = 旧版；2 = 一次性锁定并保留一次升级重置。 */
    public static final String KEY_ACTIVATION_REVISION = "activation_revision";
    public static final int ACTIVATION_REVISION = 2;

    /**
     * 升级重置标记：旧版已激活的安装只保留一次重新输入机会。
     * 为 true 时设置页仍可打开激活对话框，成功后永久关闭。
     */
    public static final String KEY_ACTIVATION_RESET_PENDING = "activation_reset_pending";

    private static final String CODE = "ZNLU";

    private PresetUnlock() {
    }

    /** 激活码是否匹配：忽略首尾空白与大小写，但不接受其它内容。 */
    public static boolean matches(String input) {
        return input != null
                && CODE.equals(input.trim().toUpperCase(Locale.US));
    }

    /**
     * 升级到 0.8.0 的激活状态迁移：旧版用过激活码的安装保留一次重新激活机会，
     * 同时保持预设路线原样，避免升级瞬间被移出列表。未用过激活码的安装不受影响。
     */
    public static void migrate(SharedPreferences prefs) {
        if (prefs.getInt(KEY_ACTIVATION_REVISION, 1) >= ACTIVATION_REVISION) {
            return;
        }
        boolean used = prefs.getBoolean(KEY_UNLOCKED, false)
                || prefs.getBoolean(KEY_PRESETS_ADDED, false);
        SharedPreferences.Editor editor = prefs.edit()
                .putInt(KEY_ACTIVATION_REVISION, ACTIVATION_REVISION);
        if (used) {
            editor.putBoolean(KEY_UNLOCKED, true)
                    .putBoolean(KEY_PRESETS_ADDED, true)
                    .putBoolean(KEY_ACTIVATION_RESET_PENDING, true);
        }
        editor.apply();
    }

    /** 激活码是否已经永久用完，入口不再接受输入。 */
    public static boolean isActivated(SharedPreferences prefs) {
        return prefs.getBoolean(KEY_UNLOCKED, false)
                && !prefs.getBoolean(KEY_ACTIVATION_RESET_PENDING, false);
    }

    /** 旧版已激活安装是否还有一次升级重置机会。 */
    public static boolean hasResetPending(SharedPreferences prefs) {
        return prefs.getBoolean(KEY_ACTIVATION_RESET_PENDING, false);
    }

    /**
     * 消费激活码：标记永久锁定，并让校园快跑补回缺失的预设。
     * 已经存在的预设按 id 去重，不会重复添加。
     */
    public static void consume(SharedPreferences prefs) {
        prefs.edit()
                .putBoolean(KEY_UNLOCKED, true)
                .putBoolean(KEY_PRESETS_ADDED, false)
                .putBoolean(KEY_ACTIVATION_RESET_PENDING, false)
                .putInt(KEY_ACTIVATION_REVISION, ACTIVATION_REVISION)
                .apply();
    }
}
