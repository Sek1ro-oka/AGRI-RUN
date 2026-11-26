package org.localroute.app.settings;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

/** 首次安装的引导进度；覆盖升级的现有安装不重复进入引导。 */
public final class OnboardingState {
    public static final String PREFS = "onboarding";
    private static final String INITIALIZED = "initialized";
    private static final String COMPLETE = "complete";
    private static final String STEP = "step";
    private static final String JW_IMPORTED = "jw_imported";
    public static final int PERMISSIONS = 0;
    public static final int ACCOUNTS = 1;
    public static final int FINISH = 2;

    private OnboardingState() { }

    public static boolean isUpgrade(long installedAt, long updatedAt) {
        return installedAt <= 0 || updatedAt <= 0 || updatedAt > installedAt;
    }

    public static boolean shouldShow(Context context) {
        SharedPreferences prefs = prefs(context);
        if (!prefs.getBoolean(INITIALIZED, false)) {
            boolean upgraded = true;
            try {
                PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
                upgraded = isUpgrade(info.firstInstallTime, info.lastUpdateTime);
            } catch (PackageManager.NameNotFoundException ignored) {
                // 未能判定安装历史时保留已有用户的启动路径。
            }
            prefs.edit().putBoolean(INITIALIZED, true).putBoolean(COMPLETE, upgraded).commit();
        }
        return !prefs.getBoolean(COMPLETE, false);
    }

    public static int step(Context context) {
        return Math.max(PERMISSIONS, Math.min(FINISH, prefs(context).getInt(STEP, PERMISSIONS)));
    }

    public static void setStep(Context context, int step) {
        prefs(context).edit().putInt(STEP, Math.max(PERMISSIONS, Math.min(FINISH, step))).apply();
    }

    public static void complete(Context context) {
        prefs(context).edit().putBoolean(COMPLETE, true).apply();
    }

    public static boolean jwImported(Context context) {
        return prefs(context).getBoolean(JW_IMPORTED, false);
    }

    public static void markJwImported(Context context) {
        prefs(context).edit().putBoolean(JW_IMPORTED, true).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
