package org.localroute.app.diagnostics;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * 带开关的诊断日志：默认关闭坐标输出，只有用户在诊断页显式打开后才记录位置细节。
 *
 * <p>位置属于敏感数据，任务书要求“坐标日志默认关闭”；这里把开关存进
 * SharedPreferences，同时保留最近 200 条，便于诊断页直接查看，无需连 adb。
 */
public final class DiagnosticLog {

    public static final String TAG = "RouteWorkshop";
    private static final String PREFS = "diagnostics";
    private static final String KEY_LOG_COORDS = "log_coordinates";
    private static final int MAX_RING = 200;

    private static final Deque<String> RING = new ArrayDeque<>();
    private static volatile boolean coordinateLoggingEnabled;

    private DiagnosticLog() {
    }

    public static void init(Context context) {
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        coordinateLoggingEnabled = prefs.getBoolean(KEY_LOG_COORDS, false);
    }

    public static boolean isCoordinateLoggingEnabled() {
        return coordinateLoggingEnabled;
    }

    public static void setCoordinateLoggingEnabled(Context context, boolean enabled) {
        coordinateLoggingEnabled = enabled;
        context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_LOG_COORDS, enabled).apply();
        info("坐标日志已" + (enabled ? "开启" : "关闭"));
    }

    public static void info(String message) {
        append("I", message);
        Log.i(TAG, message);
    }

    public static void warn(String message) {
        append("W", message);
        Log.w(TAG, message);
    }

    public static void error(String message, Throwable error) {
        append("E", error == null ? message : message + " / " + error);
        if (error == null) {
            Log.e(TAG, message);
        } else {
            Log.e(TAG, message, error);
        }
    }

    /** 位置细节：仅在用户开启坐标日志时记录。 */
    public static void fix(String message) {
        if (!coordinateLoggingEnabled) {
            return;
        }
        append("D", message);
        Log.d(TAG, message);
    }

    public static List<String> recentLines() {
        synchronized (RING) {
            return new ArrayList<>(RING);
        }
    }

    public static void clearRecent() {
        synchronized (RING) {
            RING.clear();
        }
    }

    private static void append(String level, String message) {
        String stamp = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
        synchronized (RING) {
            RING.addLast(stamp + " " + level + " " + message);
            while (RING.size() > MAX_RING) {
                RING.removeFirst();
            }
        }
    }
}
