package org.localroute.core;

import java.util.ArrayList;
import java.util.List;

/**
 * 把用户输入的坐标序列编译成 {@link CompiledRoute}。
 *
 * <p>编译期会做这些事（对应任务书第 5 步）：
 * <ol>
 *   <li>校验坐标有效性与点数上限（5000）；</li>
 *   <li>去掉完全重合的连续点；</li>
 *   <li>闭合路线里若终点重复了起点，去掉重复终点，不多算零长边；</li>
 *   <li>允许少于 2 个点的草稿继续编辑，但拒绝编译成可运行路线；</li>
 *   <li>单圈长度不足 1 米时拒绝；</li>
 *   <li>生成累计距离数组与闭合边长度。</li>
 * </ol>
 *
 * <p>闭合与不闭合的区别（0.1.1 起由用户选择）：
 * <ul>
 *   <li><b>闭合</b>：最后一点直接连回第一点，单圈长度含这条闭合边；</li>
 *   <li><b>不闭合</b>：按“原路返回”计圈——走到最后一个节点就掉头沿原路回到起点，
 *       因此单圈长度是单程的两倍，行进轨迹仍然是一条首尾相接的环，可以反复运行。</li>
 * </ul>
 */
public final class RouteCompiler {

    /** 最多允许的原始节点数。 */
    public static final int MAX_POINTS = 5000;
    /** 单圈最短长度（米）。 */
    public static final double MIN_LAP_METERS = 1.0d;
    /** 判定“重合点”的容差（米）。 */
    static final double DUPLICATE_TOLERANCE_METERS = 0.01d;

    private RouteCompiler() {
    }

    /** 编译一条闭合路线（保持 0.1.0 的行为）。 */
    public static CompiledRoute compile(List<GeoPoint> rawPoints) throws InvalidRouteException {
        return compile(rawPoints, true);
    }

    /**
     * 编译路线。
     *
     * @param closed {@code true} 表示最后一点连回起点；{@code false} 表示沿原路返回计圈
     * @throws InvalidRouteException 路线无法运行时抛出（错误码固定为 {@link RunErrorCode#ROUTE_INVALID}）
     */
    public static CompiledRoute compile(List<GeoPoint> rawPoints, boolean closed) throws InvalidRouteException {
        if (rawPoints == null) {
            throw invalid("路线为空");
        }
        if (rawPoints.size() > MAX_POINTS) {
            throw invalid("节点数超过上限 " + MAX_POINTS + "：" + rawPoints.size());
        }

        List<GeoPoint> points = new ArrayList<>(rawPoints.size());
        for (GeoPoint point : rawPoints) {
            if (point == null) {
                throw invalid("存在空节点");
            }
            if (points.isEmpty() || !points.get(points.size() - 1).isNear(point, DUPLICATE_TOLERANCE_METERS)) {
                points.add(point);
            }
        }

        if (closed) {
            // 终点重复起点：去掉重复终点（闭合边由编译期补上）。
            while (points.size() > 1
                    && points.get(points.size() - 1).isNear(points.get(0), DUPLICATE_TOLERANCE_METERS)) {
                points.remove(points.size() - 1);
            }
        }

        if (points.size() < 2) {
            throw invalid("至少需要 2 个不重合的节点，当前 " + points.size() + " 个");
        }

        List<GeoPoint> ring = closed ? new ArrayList<>(points) : buildOutAndBackRing(points);
        int n = ring.size();
        GeoPoint[] vertices = ring.toArray(new GeoPoint[0]);
        double[] prefix = new double[n];
        double accumulated = 0d;
        for (int i = 1; i < n; i++) {
            accumulated += GeoMath.distanceMeters(vertices[i - 1], vertices[i]);
            prefix[i] = accumulated;
        }
        double closingEdge = GeoMath.distanceMeters(vertices[n - 1], vertices[0]);
        double lapLength = accumulated + closingEdge;

        if (!Double.isFinite(lapLength) || lapLength < MIN_LAP_METERS) {
            throw invalid(String.format(java.util.Locale.US,
                    "单圈长度不足 %.1f 米（当前 %.3f 米），请把节点放远一些", MIN_LAP_METERS, lapLength));
        }

        return new CompiledRoute(vertices, prefix, lapLength, closed, points.size());
    }

    /**
     * 不闭合路线在运行期的轨迹：去程 p0→pn-1，回程 pn-2→p1，再由闭合边回到 p0。
     * 这样 {@link CompiledRoute} 的距离/插值逻辑不需要为“往返”写第二套。
     */
    private static List<GeoPoint> buildOutAndBackRing(List<GeoPoint> points) {
        int n = points.size();
        List<GeoPoint> ring = new ArrayList<>(2 * n - 1);
        ring.addAll(points);
        for (int i = n - 2; i >= 1; i--) {
            ring.add(points.get(i));
        }
        return ring;
    }

    private static InvalidRouteException invalid(String message) {
        return new InvalidRouteException(RunErrorCode.ROUTE_INVALID, message);
    }
}
