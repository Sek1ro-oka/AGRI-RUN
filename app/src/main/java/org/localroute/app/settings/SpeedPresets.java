package org.localroute.app.settings;

import java.util.Locale;

/**
 * 三种可编辑配速预设（行走 / 跑步 / 骑行）。
 *
 * <p>值存在偏好里，但读写通过 {@link Store} 抽象，便于纯 JVM 单测；
 * 约束与手动输入一致：0.5—60 km/h，非法值退回默认。</p>
 */
public final class SpeedPresets {

    public static final double MIN_KMH = 0.5;
    public static final double MAX_KMH = 60.0;

    public static final double DEFAULT_WALK_KMH = 5.0;
    public static final double DEFAULT_RUN_KMH = 9.0;
    public static final double DEFAULT_RIDE_KMH = 15.0;

    /** 偏好读写的最小接口，Android 侧用 SharedPreferences 适配。 */
    public interface Store {
        String get(String key, String fallback);

        void put(String key, String value);
    }

    private static final String KEY_WALK = "speed_preset_walk";
    private static final String KEY_RUN = "speed_preset_run";
    private static final String KEY_RIDE = "speed_preset_ride";

    private final Store store;

    public SpeedPresets(Store store) {
        this.store = store;
    }

    public double walk() {
        return read(KEY_WALK, DEFAULT_WALK_KMH);
    }

    public double run() {
        return read(KEY_RUN, DEFAULT_RUN_KMH);
    }

    public double ride() {
        return read(KEY_RIDE, DEFAULT_RIDE_KMH);
    }

    public void setWalk(double kmh) {
        write(KEY_WALK, kmh);
    }

    public void setRun(double kmh) {
        write(KEY_RUN, kmh);
    }

    public void setRide(double kmh) {
        write(KEY_RIDE, kmh);
    }

    /** 按序号取（0=行走，1=跑步，2=骑行），未知序号抛 IllegalArgumentException。 */
    public double get(int index) {
        switch (index) {
            case 0:
                return walk();
            case 1:
                return run();
            case 2:
                return ride();
            default:
                throw new IllegalArgumentException("未知配速序号: " + index);
        }
    }

    public void set(int index, double kmh) {
        switch (index) {
            case 0:
                setWalk(kmh);
                return;
            case 1:
                setRun(kmh);
                return;
            case 2:
                setRide(kmh);
                return;
            default:
                throw new IllegalArgumentException("未知配速序号: " + index);
        }
    }

    /** 把任意输入夹到合法区间；NaN / 无穷退回 fallback。 */
    public static double clamp(double kmh, double fallback) {
        if (Double.isNaN(kmh) || Double.isInfinite(kmh)) {
            return fallback;
        }
        if (kmh < MIN_KMH) {
            return MIN_KMH;
        }
        if (kmh > MAX_KMH) {
            return MAX_KMH;
        }
        return kmh;
    }

    /** 界面上显示成一位小数：5.0、9.0、15.0。 */
    public static String format(double kmh) {
        return String.format(Locale.US, "%.1f", clamp(kmh, DEFAULT_WALK_KMH));
    }

    private double read(String key, double fallback) {
        String raw = store.get(key, null);
        if (raw == null || raw.trim().isEmpty()) {
            return fallback;
        }
        try {
            return clamp(Double.parseDouble(raw.trim()), fallback);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private void write(String key, double kmh) {
        double value = clamp(kmh, DEFAULT_WALK_KMH);
        store.put(key, Double.toString(value));
    }
}
