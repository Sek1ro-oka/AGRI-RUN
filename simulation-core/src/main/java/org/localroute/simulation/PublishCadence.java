package org.localroute.simulation;

import java.util.Arrays;
import java.util.Locale;

/**
 * 发送节奏记录器：把「标称间隔」与「实际间隔」放在一起看。
 *
 * <p>为什么需要它：「时快时慢」这类现象，一半来自目标 App 自己的滤波，一半来自我们这边
 * 调度被系统拖慢（后台冻结、主线程卡顿、doze）。没有实际间隔的数据，就只能猜；有了它，
 * 诊断页能直接回答「标称 500 ms 到底是按 500 ms 发的，还是拖到了 800 ms」。
 *
 * <p>纯逻辑、无 Android 依赖，可单测。不保存位置，只保存间隔，内存恒定。
 */
public final class PublishCadence {

    private final long[] gapsNanos;
    private int count;
    private int next;
    private long lastTickNanos = -1L;

    public PublishCadence() {
        this(64);
    }

    public PublishCadence(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("容量必须为正数");
        }
        this.gapsNanos = new long[capacity];
    }

    /** 每帧调用一次（在成功发布之后）。第一次调用只记时间基准。 */
    public void onTick(long elapsedRealtimeNanos) {
        if (lastTickNanos >= 0L) {
            long gap = elapsedRealtimeNanos - lastTickNanos;
            if (gap > 0L) {
                gapsNanos[next] = gap;
                next = (next + 1) % gapsNanos.length;
                if (count < gapsNanos.length) {
                    count++;
                }
            }
        }
        lastTickNanos = elapsedRealtimeNanos;
    }

    public boolean hasSamples() {
        return count > 0;
    }

    public int sampleCount() {
        return count;
    }

    /** 中位间隔（毫秒）；没有样本时返回 {@code Double.NaN}。 */
    public double medianGapMillis() {
        return percentileMillis(0.5d);
    }

    /** 最长间隔（毫秒）；没有样本时返回 {@code Double.NaN}。 */
    public double maxGapMillis() {
        if (count == 0) {
            return Double.NaN;
        }
        long[] copy = sortedCopy();
        return copy[copy.length - 1] / 1_000_000d;
    }

    /** 中位间隔相对标称值的偏差比例（+0.2 表示慢了 20%）。 */
    public double medianDeviation(double nominalMillis) {
        if (count == 0 || nominalMillis <= 0d) {
            return Double.NaN;
        }
        return medianGapMillis() / nominalMillis - 1d;
    }

    /** 给人看的一行摘要。 */
    public String summary(double nominalMillis) {
        if (count == 0) {
            return String.format(Locale.US, "实际间隔：样本不足（标称 %.0f ms）", nominalMillis);
        }
        double median = medianGapMillis();
        double max = maxGapMillis();
        double deviation = medianDeviation(nominalMillis);
        return String.format(Locale.US,
                "实际间隔：标称 %.0f ms，中位 %.0f ms（%+.1f%%），最长 %.0f ms，样本 %d 帧",
                nominalMillis, median, deviation * 100d, max, count);
    }

    public void reset() {
        count = 0;
        next = 0;
        lastTickNanos = -1L;
        Arrays.fill(gapsNanos, 0L);
    }

    /** 取第 {@code fraction} 分位（0.5=中位），用最接近的次序统计量，样本少也不会插值出错。 */
    private double percentileMillis(double fraction) {
        if (count == 0) {
            return Double.NaN;
        }
        long[] copy = sortedCopy();
        int index = (int) Math.round(fraction * (copy.length - 1));
        index = Math.max(0, Math.min(copy.length - 1, index));
        return copy[index] / 1_000_000d;
    }

    private long[] sortedCopy() {
        long[] copy = Arrays.copyOf(gapsNanos, count);
        Arrays.sort(copy);
        return copy;
    }
}
