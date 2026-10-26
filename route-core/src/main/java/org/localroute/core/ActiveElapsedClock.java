package org.localroute.core;

/**
 * 只统计“有效运行时间”的纳秒时钟：暂停期间不累计、重复开始不会重复计时。
 *
 * <p>实现上不读系统时间，所有时刻都由调用方传入（通常是 {@code SystemClock.elapsedRealtimeNanos()}），
 * 以便在 JVM 单元测试中用可控时间验证暂停行为（任务书 T07）。
 */
public final class ActiveElapsedClock {

    private long accumulatedNanos;
    private long startedAtNanos = -1L;

    /** 开始计时；若已在计时中则忽略（幂等，不重复累计）。 */
    public void start(long nowNanos) {
        if (startedAtNanos < 0L) {
            startedAtNanos = nowNanos;
        }
    }

    /** 暂停计时；若本就未计时则忽略。 */
    public void pause(long nowNanos) {
        if (startedAtNanos >= 0L) {
            accumulatedNanos += Math.max(0L, nowNanos - startedAtNanos);
            startedAtNanos = -1L;
        }
    }

    /** 清零并停止计时。 */
    public void reset() {
        accumulatedNanos = 0L;
        startedAtNanos = -1L;
    }

    public boolean isRunning() {
        return startedAtNanos >= 0L;
    }

    /** 截至 {@code nowNanos} 的有效运行时间。 */
    public long elapsedNanos(long nowNanos) {
        long total = accumulatedNanos;
        if (startedAtNanos >= 0L) {
            total += Math.max(0L, nowNanos - startedAtNanos);
        }
        return total;
    }

    /** 便于界面展示：HH:MM:SS 或 MM:SS。 */
    public static String formatDuration(long elapsedNanos) {
        long totalSeconds = Math.max(0L, elapsedNanos) / 1_000_000_000L;
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        if (hours > 0L) {
            return String.format(java.util.Locale.US, "%02d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(java.util.Locale.US, "%02d:%02d", minutes, seconds);
    }
}
