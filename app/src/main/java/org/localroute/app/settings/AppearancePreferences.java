package org.localroute.app.settings;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatDelegate;

import org.localroute.app.R;

/**
 * 界面风格（浅色 / 深色 / 跟随系统）的存取与生效。
 *
 * <p>只持久化三选一枚举，真正的换肤交给 {@link AppCompatDelegate#setDefaultNightMode}：
 * 应用主题本身是 {@code Theme.Material3.DayNight.NoActionBar}，自定义色板全部走
 * {@code fm_*} / {@code campus_*} 令牌，{@code values-night} 已经给了对应暗色值。
 *
 * <p>默认跟随系统：没有显式选过风格的设备保持和系统深色设置一致，这也是绝大多数用户的预期。
 */
public final class AppearancePreferences {

    public static final int MODE_FOLLOW_SYSTEM = 0;
    public static final int MODE_LIGHT = 1;
    public static final int MODE_DARK = 2;

    /** 从未选过风格时的取值。 */
    public static final int DEFAULT_MODE = MODE_FOLLOW_SYSTEM;

    /** 与悬浮窗开关同命名空间，便于「界面相关偏好」集中管理。 */
    private static final String PREFS = "ui";
    private static final String KEY_MODE = "night_mode";

    /** 设置页里单选对话框的固定顺序。 */
    public static final int[] MODES = {MODE_FOLLOW_SYSTEM, MODE_LIGHT, MODE_DARK};

    private AppearancePreferences() {
    }

    public static int load(@NonNull Context context) {
        int stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_MODE, DEFAULT_MODE);
        return isKnown(stored) ? stored : DEFAULT_MODE;
    }

    public static void save(@NonNull Context context, int mode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt(KEY_MODE, isKnown(mode) ? mode : DEFAULT_MODE)
                .apply();
    }

    public static boolean isKnown(int mode) {
        return mode == MODE_FOLLOW_SYSTEM || mode == MODE_LIGHT || mode == MODE_DARK;
    }

    /** 映射到 AppCompat 的夜间模式常量。 */
    public static int nightModeFor(int mode) {
        switch (mode) {
            case MODE_LIGHT:
                return AppCompatDelegate.MODE_NIGHT_NO;
            case MODE_DARK:
                return AppCompatDelegate.MODE_NIGHT_YES;
            case MODE_FOLLOW_SYSTEM:
            default:
                return AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
        }
    }

    @androidx.annotation.StringRes
    public static int labelFor(int mode) {
        switch (mode) {
            case MODE_LIGHT:
                return R.string.appearance_mode_light;
            case MODE_DARK:
                return R.string.appearance_mode_dark;
            case MODE_FOLLOW_SYSTEM:
            default:
                return R.string.appearance_mode_follow_system;
        }
    }

    /** 把已保存的风格应用到全局；应用启动时调用一次，选择后由设置页再调一次。 */
    public static void apply(@NonNull Context context) {
        AppCompatDelegate.setDefaultNightMode(nightModeFor(load(context)));
    }
}
