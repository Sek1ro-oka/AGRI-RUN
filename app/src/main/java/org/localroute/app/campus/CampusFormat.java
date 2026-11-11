package org.localroute.app.campus;

import java.util.Locale;

/** 校园快跑的配速 / 时间 / 距离格式化。 */
public final class CampusFormat {

    private CampusFormat() {
    }

    public static String pad2(long value) {
        return value < 10 ? "0" + value : String.valueOf(value);
    }

    /** 毫秒 → 00:00:00。 */
    public static String time(long milliseconds) {
        long totalSeconds = Math.max(0L, milliseconds) / 1000L;
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        return pad2(hours) + ":" + pad2(minutes) + ":" + pad2(seconds);
    }

    /** 每公里秒数 → 6'40"；非法值显示 --'--"。 */
    public static String pace(double secondsPerKm) {
        if (!Double.isFinite(secondsPerKm) || secondsPerKm <= 0d || secondsPerKm > 35999d) {
            return "--'--\"";
        }
        long total = Math.round(secondsPerKm);
        long minutes = total / 60L;
        long seconds = total % 60L;
        if (seconds == 60L) {
            minutes += 1L;
            seconds = 0L;
        }
        return minutes + "'" + pad2(seconds) + "\"";
    }

    /** 设置页使用的带单位配速：6'40"/km。 */
    public static String paceWithUnit(double secondsPerKm) {
        return pace(secondsPerKm) + "/km";
    }

    /** 累计用时与已跑距离 → 平均每公里配速（秒）。 */
    public static double averagePaceSeconds(long elapsedMs, double distanceMeters) {
        if (distanceMeters <= 0d || elapsedMs <= 0L) {
            return 0d;
        }
        return (elapsedMs / 1000d) / (distanceMeters / 1000d);
    }

    /** 米 → 0.00（公里数值，不带单位）。 */
    public static String kilometers(double meters) {
        return String.format(Locale.US, "%.2f", Math.max(0d, meters) / 1000d);
    }

    /** 单圈长度：400 m / 1.2 km。 */
    public static String lapMeters(double meters) {
        if (meters >= 1000d) {
            return String.format(Locale.US, "%.1f km", meters / 1000d);
        }
        return Math.round(meters) + " m";
    }

    /** 坐标展示：30.25610°N 119.72360°E。 */
    public static String coordinates(double latitude, double longitude) {
        return String.format(Locale.US, "%.5f°N %.5f°E", latitude, longitude);
    }
}
