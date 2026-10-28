package org.localroute.simulation;
import org.localroute.core.GeoMath;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 一条定位通道（provider / 客户端）收到回调后的统计：首帧延迟、条数、间隔分布、断流，
 * 以及「速度声明」和「两点间实测位移」是否自洽。
 *
 * <p>用途是让「收到了吗」这个模糊问题变成可核对的数字：客户端可能按请求条件降采样、
 * 也可能批量回调，所以这里只报告观测值，不把「不是 500ms」当成失败。
 */
public final class ChannelStats {

    private final String channel;
    private final List<Long> arrivalsNanos = new ArrayList<>();
    private double travelledMeters;
    private double speedSumMps;
    private double speedMaxMps;
    private int speedSamples;
    private int mockFrames;
    private int totalFrames;
    private double firstLatitude;
    private double firstLongitude;
    private double lastLatitude;
    private double lastLongitude;
    private long firstLatencyMillis = -1L;
    private int inFlight;
    private int errorCallbacks;

    public ChannelStats(String channel) {
        this.channel = channel;
    }

    public String channel() {
        return channel;
    }

    /** 记录一帧。{@code latencyMillis} 是「从开始监听到这一帧」的毫秒数，未知传 -1。 */
    public void addFrame(long arrivalNanos, double latitude, double longitude,
                         boolean hasSpeed, double speedMps, boolean markedMock, long latencyMillis) {
        totalFrames++;
        if (totalFrames == 1) {
            firstLatitude = latitude;
            firstLongitude = longitude;
            if (latencyMillis >= 0) {
                firstLatencyMillis = latencyMillis;
            }
        } else {
            travelledMeters += distance(firstLatitude, firstLongitude, latitude, longitude);
            firstLatitude = latitude;
            firstLongitude = longitude;
        }
        lastLatitude = latitude;
        lastLongitude = longitude;
        if (hasSpeed) {
            speedSamples++;
            speedSumMps += Math.max(0d, speedMps);
            speedMaxMps = Math.max(speedMaxMps, Math.max(0d, speedMps));
        }
        if (markedMock) {
            mockFrames++;
        }
        if (!arrivalsNanos.isEmpty() && arrivalNanos > arrivalsNanos.get(arrivalsNanos.size() - 1)) {
            arrivalsNanos.add(arrivalNanos);
        } else if (arrivalsNanos.isEmpty()) {
            arrivalsNanos.add(arrivalNanos);
        }
    }

    /** 用于统计「有回调在途」的简单计数（GMS 的 Task 场景）。 */
    public void taskStarted() {
        inFlight++;
    }

    public void taskFinished(boolean success) {
        inFlight = Math.max(0, inFlight - 1);
        if (!success) {
            failedTasks++;
        }
    }

    private int failedTasks;

    /** 记录一次「有回调但不可用」的帧（例如高德 SDK 明确报错）：只计回调，不进距离与速度（R32-05）。 */
    public void addErrorCallback() {
        errorCallbacks++;
    }

    /** 该通道收到的回调总数：有效帧 + 错误帧。 */
    public int callbacks() {
        return totalFrames + errorCallbacks;
    }

    public int errorCallbacks() {
        return errorCallbacks;
    }

    /** 是否有任何一帧带速度字段。没有的话「均速 0.00」是假数据，必须显示「未提供」。 */
    public boolean hasSpeedSamples() {
        return speedSamples > 0;
    }

    /** 最后一帧的到达时刻（elapsedRealtimeNanos）；没有帧返回 -1。 */
    public long lastArrivalNanos() {
        return arrivalsNanos.isEmpty() ? -1L : arrivalsNanos.get(arrivalsNanos.size() - 1);
    }

    /** 距离最后一帧过了多久（毫秒）；没有帧返回 -1。 */
    public long ageSinceLastFrameMillis(long nowNanos) {
        long last = lastArrivalNanos();
        return last < 0L ? -1L : Math.max(0L, (nowNanos - last) / 1_000_000L);
    }

    public int totalFrames() {
        return totalFrames;
    }

    public int mockFrames() {
        return mockFrames;
    }

    public int failedTasks() {
        return failedTasks;
    }

    public int inFlight() {
        return inFlight;
    }

    public double travelledMeters() {
        return travelledMeters;
    }

    public double averageSpeedMps() {
        return speedSamples == 0 ? 0d : speedSumMps / speedSamples;
    }

    public double maxSpeedMps() {
        return speedMaxMps;
    }

    /** 相邻回调间隔（毫秒）的排序副本；少于两帧返回空表。 */
    public List<Long> intervalsMillis() {
        List<Long> result = new ArrayList<>();
        for (int i = 1; i < arrivalsNanos.size(); i++) {
            long deltaMs = Math.round((arrivalsNanos.get(i) - arrivalsNanos.get(i - 1)) / 1_000_000d);
            if (deltaMs >= 0) {
                result.add(deltaMs);
            }
        }
        Collections.sort(result);
        return result;
    }

    public long medianIntervalMillis() {
        return percentileMillis(0.5);
    }

    public long p95IntervalMillis() {
        return percentileMillis(0.95);
    }

    public long maxIntervalMillis() {
        List<Long> intervals = intervalsMillis();
        return intervals.isEmpty() ? -1L : intervals.get(intervals.size() - 1);
    }

    private long percentileMillis(double fraction) {
        List<Long> intervals = intervalsMillis();
        if (intervals.isEmpty()) {
            return -1L;
        }
        int index = (int) Math.floor(fraction * (intervals.size() - 1));
        return intervals.get(Math.max(0, Math.min(intervals.size() - 1, index)));
    }

    /**
     * 速度与位移的一致性：用相邻帧的间隔 × 平均速度估算「应该走多远」，
     * 和实际位移比，给出比值。样本不足返回 -1。
     *
     * <p>只用于发现「速度字段和轨迹明显不符」，不是精度指标：定位抖动、
     * 降采样、暂停帧都会影响它。
     */
    public double speedToDisplacementRatio() {
        List<Long> intervals = intervalsMillis();
        if (intervals.isEmpty() || averageSpeedMps() <= 0.001d) {
            return -1d;
        }
        long totalMs = 0L;
        for (Long interval : intervals) {
            totalMs += interval;
        }
        if (totalMs <= 0L) {
            return -1d;
        }
        double expected = averageSpeedMps() * (totalMs / 1000d);
        return expected <= 0.001d ? -1d : travelledMeters / expected;
    }

    /** 一行摘要，给探针界面直接显示。 */
    public String summary() {
        if (callbacks() == 0) {
            return String.format(Locale.US, "%s：0 次回调（没有收到任何回调）", channel);
        }
        double ratio = speedToDisplacementRatio();
        String speed = hasSpeedSamples()
                ? String.format(Locale.US, "%.2f m/s", averageSpeedMps())
                : "未提供（目标未给速度字段）";
        return String.format(Locale.US,
                "%s：回调 %d 次（有效 %d / 错误 %d），首帧延迟 %s，间隔 中位 %s / P95 %s / 最长 %s，"
                        + "位移 %.1f 米（位置跳变累计，不是运动距离），均速 %s，模拟标记 %d/%d，位移速度比 %s",
                channel, callbacks(), totalFrames, errorCallbacks,
                firstLatencyMillis < 0 ? "未知" : firstLatencyMillis + " ms",
                ms(medianIntervalMillis()), ms(p95IntervalMillis()), ms(maxIntervalMillis()),
                travelledMeters, speed, mockFrames, totalFrames,
                ratio < 0 ? "无" : String.format(Locale.US, "%.2f", ratio));
    }

    private static String ms(long value) {
        return value < 0 ? "-" : value + " ms";
    }

    private static double distance(double lat1, double lon1, double lat2, double lon2) {
        return GeoMath.distanceMeters(
                new org.localroute.core.GeoPoint(lat1, lon1),
                new org.localroute.core.GeoPoint(lat2, lon2));
    }
}
