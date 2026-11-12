package org.localroute.app.study;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

/**
 * 学习通绑定信息的本机存储。Cookie 属于敏感凭据，只放在应用私有 SharedPreferences 中，
 * 不写日志、不进入备份。
 */
public final class StudySessionStore {

    private static final String PREFS = "study_auth";
    private static final String KEY_BOUND = "bound";
    private static final String KEY_UID = "uid";
    private static final String KEY_NAME = "name";
    private static final String KEY_COOKIE = "cookie";
    private static final String KEY_BOUND_AT = "bound_at";

    private StudySessionStore() {
    }

    @Nullable
    public static StudySession load(Context context) {
        SharedPreferences prefs = prefs(context);
        if (!prefs.getBoolean(KEY_BOUND, false)) {
            return null;
        }
        String uid = prefs.getString(KEY_UID, "");
        String cookie = prefs.getString(KEY_COOKIE, "");
        if (uid == null || uid.isEmpty() || cookie == null || cookie.isEmpty()) {
            return null;
        }
        return new StudySession(uid,
                prefs.getString(KEY_NAME, ""),
                cookie,
                prefs.getLong(KEY_BOUND_AT, 0L));
    }

    public static void save(Context context, StudySession session) {
        prefs(context).edit()
                .putBoolean(KEY_BOUND, true)
                .putString(KEY_UID, session.uid())
                .putString(KEY_NAME, session.name())
                .putString(KEY_COOKIE, session.cookie())
                .putLong(KEY_BOUND_AT, session.boundAt())
                .apply();
    }

    /** 刷新 Cookie 时保留绑定时间与昵称。 */
    public static void updateCookie(Context context, StudySession session) {
        prefs(context).edit().putString(KEY_COOKIE, session.cookie()).apply();
    }

    public static void clear(Context context) {
        prefs(context).edit().clear().apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
