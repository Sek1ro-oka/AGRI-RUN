package org.localroute.core;

/**
 * 分段累计距离：把「走过的距离」和「当前这一段的速度」分开记。
 *
 * <p>为什么需要它：如果直接用 {@code 速度 × 总用时}，运行中改速会把之前走过的距离
 * 也按新速度重算，位置会突然跳一大段（审查 R-009）。这里在改速瞬间把已走距离冻结进
 * {@link #offsetMeters}，之后只按新速度累计新的一段，因此位置连续。
 *
 * <p>纯计算、无 Android 依赖，可被单元测试直接覆盖。
 */
public final class DistancePhase {

    private double offsetMeters;
    private long phaseStartElapsedNanos;
    private double speedKmh;

    public DistancePhase(double speedKmh) {
        this(speedKmh, 0L);
    }

    public DistancePhase(double speedKmh, long startElapsedNanos) {
        if (!Double.isFinite(speedKmh) || speedKmh < 0d) {
            throw new IllegalArgumentException("速度不能为负或非有限值：" + speedKmh);
        }
        this.speedKmh = speedKmh;
        this.phaseStartElapsedNanos = Math.max(0L, startElapsedNanos);
    }

    public double speedKmh() {
        return speedKmh;
    }

    public double offsetMeters() {
        return offsetMeters;
    }

    public long phaseStartElapsedNanos() {
        return phaseStartElapsedNanos;
    }

    /** 以 {@code elapsedNanos} 为起点重开一段（开始运行、继续运行时用）。 */
    public void restartAt(long elapsedNanos, double newSpeedKmh) {
        this.offsetMeters = 0d;
        this.phaseStartElapsedNanos = Math.max(0L, elapsedNanos);
        this.speedKmh = newSpeedKmh;
    }

    /**
     * 改速：把到 {@code elapsedNanos} 为止已走的距离计入 offset，然后换速度开新段。
     *
     * @return 改速瞬间的累计距离（调用方可用于日志/校验连续性）
     */
    public double changeSpeed(double newSpeedKmh, long elapsedNanos) {
        double travelled = distanceAt(elapsedNanos);
        offsetMeters = travelled;
        phaseStartElapsedNanos = Math.max(0L, elapsedNanos);
        speedKmh = newSpeedKmh;
        return travelled;
    }

    /** 从起点算到 {@code elapsedNanos} 的累计距离。时间倒流时返回 offset（不回退）。 */
    public double distanceAt(long elapsedNanos) {
        long delta = Math.max(0L, elapsedNanos - phaseStartElapsedNanos);
        return offsetMeters + (speedKmh / 3.6d) * (delta / 1_000_000_000d);
    }

    /** 当前分段的速度（m/s）。 */
    public double speedMetersPerSecond() {
        return speedKmh / 3.6d;
    }
}
