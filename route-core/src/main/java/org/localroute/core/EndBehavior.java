package org.localroute.core;

/**
 * 路线终点策略：几何（是否闭合）与运行策略分开表达（审查方案 5.2）。
 *
 * <ul>
 *   <li>{@link #LOOP}：闭合路线，A→B→C→A 为一圈，圈数可设；</li>
 *   <li>{@link #SHUTTLE}：开口路线原路往返，A→B→C→B→A 为一次，次数可设；</li>
 *   <li>{@link #STOP_AT_END}：开口路线到终点停止，只走一次，停在最后一个节点，不回跳。</li>
 * </ul>
 */
public enum EndBehavior {

    STOP_AT_END("到终点停止"),
    SHUTTLE("原路往返"),
    LOOP("绕圈");

    private final String label;

    EndBehavior(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** 几何是否首尾相接：只有绕圈会把最后一个点连回第一个点。 */
    public boolean closed() {
        return this == LOOP;
    }

    /** 运行路径是否首尾相接（只有这种才有“圈”的概念）。 */
    public boolean wraps() {
        return this == LOOP || this == SHUTTLE;
    }

    /** 是否需要把开口路线展开成“去程 + 原路返回”。 */
    public boolean repeatsPath() {
        return this == SHUTTLE;
    }

    /** 一次行程是否走完整条用户路径（单程）。 */
    public boolean isSingleRun() {
        return this == STOP_AT_END;
    }

    /** 由草稿的“闭合”开关推导默认策略：闭合即绕圈，开口默认到终点停止。 */
    public static EndBehavior forDraft(boolean closed) {
        return closed ? LOOP : STOP_AT_END;
    }

    /** v1 旧文件的兼容默认：{@code closed=false} 在 0.1.x 里就是原路往返。 */
    public static EndBehavior legacyForClosed(boolean closed) {
        return closed ? LOOP : SHUTTLE;
    }

    public static EndBehavior parse(String raw, EndBehavior fallback) {
        if (raw == null) {
            return fallback;
        }
        for (EndBehavior value : values()) {
            if (value.name().equalsIgnoreCase(raw.trim())) {
                return value;
            }
        }
        return fallback;
    }
}
