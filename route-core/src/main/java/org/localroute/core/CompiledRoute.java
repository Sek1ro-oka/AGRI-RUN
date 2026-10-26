package org.localroute.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 编译后的不可变路线。
 *
 * <p>几何约定：
 * <ul>
 *   <li>路线始终闭合：最后一个顶点与第一个顶点之间有一条“闭合边”；</li>
 *   <li>顶点不再包含重复的闭合终点（编译期已清理）；</li>
 *   <li>单圈长度包含闭合边；</li>
 *   <li>{@code prefixDistances[i]} 表示从顶点 0 沿路线到顶点 i 的距离，{@code prefixDistances[0] == 0}。</li>
 * </ul>
 *
 * <p>该类只回答“沿路线走了 d 米时在哪里、朝向如何”，不保存任何运行期状态，
 * 也不依赖 Android（可在 JVM 单元测试中直接验证）。
 */
public final class CompiledRoute {

    private final GeoPoint[] vertices;
    private final double[] prefixDistances;
    private final double lapLengthMeters;

    CompiledRoute(GeoPoint[] vertices, double[] prefixDistances, double lapLengthMeters) {
        this.vertices = vertices;
        this.prefixDistances = prefixDistances;
        this.lapLengthMeters = lapLengthMeters;
    }

    /** 顶点（不含重复闭合点），至少 2 个。 */
    public List<GeoPoint> vertices() {
        return Collections.unmodifiableList(Arrays.asList(vertices.clone()));
    }

    public int vertexCount() {
        return vertices.length;
    }

    public GeoPoint vertex(int index) {
        return vertices[index];
    }

    /** 单圈长度（米，含闭合边）。 */
    public double lapLengthMeters() {
        return lapLengthMeters;
    }

    /** 从顶点 0 到顶点 i 的累计距离。 */
    public double prefixDistance(int index) {
        return prefixDistances[index];
    }

    /** 闭合后的绘制用环（首点会再次出现在末尾）。 */
    public List<GeoPoint> closedRing() {
        List<GeoPoint> ring = new ArrayList<>(vertices.length + 1);
        Collections.addAll(ring, vertices);
        ring.add(vertices[0]);
        return ring;
    }

    /** 第 {@code index} 段（{@code index} 从 0 到 {@link #vertexCount()}-1）的长度；最后一段是闭合边。 */
    public double segmentLength(int index) {
        int n = vertices.length;
        if (index < n - 1) {
            return prefixDistances[index + 1] - prefixDistances[index];
        }
        return lapLengthMeters - prefixDistances[n - 1];
    }

    /** 沿路线走了 {@code alongLapMeters} 米后的位置；超过单圈长度会自动取模。 */
    public GeoPoint pointAt(double alongLapMeters) {
        double distance = normalizeAlongLap(alongLapMeters);
        int index = segmentIndexAt(distance);
        double segmentStart = prefixDistances[index];
        double segmentLength = segmentLength(index);
        if (segmentLength <= 0d) {
            return vertices[index];
        }
        double fraction = (distance - segmentStart) / segmentLength;
        return GeoMath.interpolate(vertices[index], vertices[(index + 1) % vertices.length], fraction);
    }

    /** 沿路线走了 {@code alongLapMeters} 米时的行进方位角（度，[0,360)）。 */
    public double bearingAt(double alongLapMeters) {
        int index = segmentIndexAt(normalizeAlongLap(alongLapMeters));
        return GeoMath.initialBearingDegrees(vertices[index], vertices[(index + 1) % vertices.length]);
    }

    /**
     * 找到包含 {@code distance} 的段下标：满足 {@code prefixDistances[i] <= distance < prefixDistances[i+1]}，
     * 最后一段为闭合边。
     */
    private int segmentIndexAt(double distance) {
        int low = 0;
        int high = vertices.length - 1;
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            if (prefixDistances[mid] <= distance) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }
        return low;
    }

    private double normalizeAlongLap(double alongLapMeters) {
        double value = alongLapMeters % lapLengthMeters;
        if (value < 0d) {
            value += lapLengthMeters;
        }
        // 浮点误差可能让取模结果等于 lapLengthMeters，夹回 0。
        if (value >= lapLengthMeters) {
            value = 0d;
        }
        return value;
    }

    @Override
    public String toString() {
        return "CompiledRoute{vertices=" + vertices.length + ", lap=" + String.format(java.util.Locale.US, "%.2f", lapLengthMeters) + "m}";
    }
}
