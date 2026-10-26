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
 *   <li>若终点重复了起点，去掉重复终点，不多算零长边；</li>
 *   <li>允许少于 2 个点的草稿继续编辑，但拒绝编译成可运行路线；</li>
 *   <li>单圈长度不足 1 米时拒绝；</li>
 *   <li>生成累计距离数组与闭合边长度。</li>
 * </ol>
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

    /**
     * 编译路线。
     *
     * @throws InvalidRouteException 路线无法运行时抛出（错误码固定为 {@link RunErrorCode#ROUTE_INVALID}）
     */
    public static CompiledRoute compile(List<GeoPoint> rawPoints) throws InvalidRouteException {
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

        // 终点重复起点：去掉重复终点（闭合边由编译期补上）。
        while (points.size() > 1
                && points.get(points.size() - 1).isNear(points.get(0), DUPLICATE_TOLERANCE_METERS)) {
            points.remove(points.size() - 1);
        }

        if (points.size() < 2) {
            throw invalid("至少需要 2 个不重合的节点，当前 " + points.size() + " 个");
        }

        int n = points.size();
        GeoPoint[] vertices = points.toArray(new GeoPoint[0]);
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

        return new CompiledRoute(vertices, prefix, lapLength);
    }

    private static InvalidRouteException invalid(String message) {
        return new InvalidRouteException(RunErrorCode.ROUTE_INVALID, message);
    }
}
