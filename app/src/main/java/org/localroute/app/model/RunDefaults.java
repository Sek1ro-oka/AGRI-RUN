package org.localroute.app.model;

/**
 * 路线的运行默认值（schemaVersion 2 的 {@code runDefaults}）。
 *
 * <p>只保存“下次启动时预填的值”，真正运行时仍由界面上的输入生成不可变的
 * {@link org.localroute.app.run.RunRequest}，两者互不覆盖（审查方案 5.3 第 4 条）。
 */
public final class RunDefaults {

    public static final int DEFAULT_REPEAT_COUNT = 1;
    public static final double DEFAULT_SPEED_KMH = 9.0d;

    private final int repeatCount;
    private final double speedKmh;

    public RunDefaults(int repeatCount, double speedKmh) {
        if (repeatCount < 1 || repeatCount > 1000) {
            throw new IllegalArgumentException("次数必须在 1—1000 之间：" + repeatCount);
        }
        if (!Double.isFinite(speedKmh) || speedKmh < 0.5d || speedKmh > 60.0d) {
            throw new IllegalArgumentException("速度必须在 0.5—60 km/h 之间：" + speedKmh);
        }
        this.repeatCount = repeatCount;
        this.speedKmh = speedKmh;
    }

    public static RunDefaults defaults() {
        return new RunDefaults(DEFAULT_REPEAT_COUNT, DEFAULT_SPEED_KMH);
    }

    public int repeatCount() {
        return repeatCount;
    }

    public double speedKmh() {
        return speedKmh;
    }

    public RunDefaults withRepeatCount(int newRepeatCount) {
        return new RunDefaults(newRepeatCount, speedKmh);
    }

    public RunDefaults withSpeedKmh(double newSpeedKmh) {
        return new RunDefaults(repeatCount, newSpeedKmh);
    }
}
