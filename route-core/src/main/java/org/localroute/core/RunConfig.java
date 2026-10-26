package org.localroute.core;

/**
 * 运行参数：速度（km/h）、圈数、调度间隔。
 *
 * <p>速度单位在界面与存储中统一为 km/h，进入引擎前统一换算为 m/s（{@code kmh / 3.6}）。
 */
public final class RunConfig {

    public static final double MIN_SPEED_KMH = 0.5d;
    public static final double MAX_SPEED_KMH = 60d;
    public static final double DEFAULT_SPEED_KMH = 9d;
    public static final int MIN_LOOPS = 1;
    public static final int MAX_LOOPS = 1000;
    public static final int DEFAULT_LOOPS = 5;
    public static final int DEFAULT_TICK_INTERVAL_MS = 500;
    public static final int MIN_TICK_INTERVAL_MS = 100;
    public static final int MAX_TICK_INTERVAL_MS = 5000;

    private final double speedKmh;
    private final int loopCount;
    private final int tickIntervalMs;

    public RunConfig(double speedKmh, int loopCount) {
        this(speedKmh, loopCount, DEFAULT_TICK_INTERVAL_MS);
    }

    public RunConfig(double speedKmh, int loopCount, int tickIntervalMs) {
        if (!Double.isFinite(speedKmh) || speedKmh < MIN_SPEED_KMH || speedKmh > MAX_SPEED_KMH) {
            throw new IllegalArgumentException(String.format(java.util.Locale.US,
                    "速度必须在 %.1f—%.0f km/h 之间：%.2f", MIN_SPEED_KMH, MAX_SPEED_KMH, speedKmh));
        }
        if (loopCount < MIN_LOOPS || loopCount > MAX_LOOPS) {
            throw new IllegalArgumentException("圈数必须在 " + MIN_LOOPS + "—" + MAX_LOOPS + " 之间：" + loopCount);
        }
        if (tickIntervalMs < MIN_TICK_INTERVAL_MS || tickIntervalMs > MAX_TICK_INTERVAL_MS) {
            throw new IllegalArgumentException("调度间隔必须在 " + MIN_TICK_INTERVAL_MS + "—" + MAX_TICK_INTERVAL_MS
                    + " 毫秒之间：" + tickIntervalMs);
        }
        this.speedKmh = speedKmh;
        this.loopCount = loopCount;
        this.tickIntervalMs = tickIntervalMs;
    }

    public static RunConfig defaults() {
        return new RunConfig(DEFAULT_SPEED_KMH, DEFAULT_LOOPS);
    }

    public double speedKmh() {
        return speedKmh;
    }

    /** 引擎使用的速度：m/s。 */
    public double speedMetersPerSecond() {
        return speedKmh / 3.6d;
    }

    public int loopCount() {
        return loopCount;
    }

    public int tickIntervalMs() {
        return tickIntervalMs;
    }

    public RunConfig withTickIntervalMs(int newTickIntervalMs) {
        return new RunConfig(speedKmh, loopCount, newTickIntervalMs);
    }

    public String speedText() {
        return String.format(java.util.Locale.US, "%.1f km/h", speedKmh);
    }

    @Override
    public String toString() {
        return "RunConfig{" + speedText() + ", loops=" + loopCount + ", tick=" + tickIntervalMs + "ms}";
    }
}
