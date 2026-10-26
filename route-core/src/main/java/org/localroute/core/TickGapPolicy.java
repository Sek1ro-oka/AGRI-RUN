package org.localroute.core;

import java.util.Locale;

/**
 * 调度间隔策略（任务书 T11）：两次 tick 的**实际**间隔如果明显超过配置节奏，说明调度被系统
 * 冻结（省电策略、深度睡眠、后台限制）了。这时如果照旧按累计距离推算位置，坐标会一步跨过
 * 一大段路线，运动记录会得到一条明显不真实的轨迹。正确做法是：停在「上一次成功 tick」的位置，
 * 自动转为暂停，并把原因写给用户看。
 *
 * <p>这里是纯逻辑，服务只负责把 {@code SystemClock.elapsedRealtimeNanos()} 的纳秒时间喂进来，
 * 因此可以用单元测试覆盖边界，而不必依赖真机。</p>
 */
public final class TickGapPolicy {

    /** 默认上限：5 秒（任务书 T11 的阈值）。 */
    public static final long DEFAULT_MAX_GAP_NANOS = 5_000_000_000L;

    private final long maxGapNanos;

    /**
     * @param maxGapNanos 允许的最大间隔（纳秒），必须为正
     * @throws IllegalArgumentException 当上限不是正数时
     */
    public TickGapPolicy(long maxGapNanos) {
        if (maxGapNanos <= 0L) {
            throw new IllegalArgumentException("最大调度间隔必须为正数：" + maxGapNanos);
        }
        this.maxGapNanos = maxGapNanos;
    }

    /** 默认策略：5 秒。 */
    public static TickGapPolicy defaults() {
        return new TickGapPolicy(DEFAULT_MAX_GAP_NANOS);
    }

    public long maxGapNanos() {
        return maxGapNanos;
    }

    /**
     * 是否应当因为调度间隔过长而自动暂停。
     *
     * @param lastTickNanos 上一次成功 tick 的时刻；{@code <= 0} 表示还没有成功过，绝不暂停
     * @param nowNanos      本次 tick 的时刻
     * @return 间隔严格大于上限时为 {@code true}
     */
    public boolean shouldPause(long lastTickNanos, long nowNanos) {
        if (lastTickNanos <= 0L) {
            return false;
        }
        long gap = nowNanos - lastTickNanos;
        if (gap <= 0L) {
            // 单调时钟理论上不会回退；真回退了也不能据此暂停。
            return false;
        }
        return gap > maxGapNanos;
    }

    /** 给用户看的原因，例如「调度间隔 12.3 秒（上限 5 秒），已自动暂停；点「继续」从暂停点恢复」。 */
    public String reasonForGap(long gapNanos) {
        long safeGap = Math.max(gapNanos, 0L);
        return String.format(Locale.US,
                "调度间隔 %s 秒（上限 %s 秒），已自动暂停；点「继续」从暂停点恢复",
                formatSeconds(safeGap), formatSeconds(maxGapNanos));
    }

    /** 秒数格式化：整秒不带小数，否则保留一位。 */
    static String formatSeconds(long nanos) {
        double seconds = nanos / 1_000_000_000d;
        boolean whole = Math.abs(seconds - Math.rint(seconds)) < 1e-9;
        return String.format(Locale.US, whole ? "%.0f" : "%.1f", seconds);
    }
}
