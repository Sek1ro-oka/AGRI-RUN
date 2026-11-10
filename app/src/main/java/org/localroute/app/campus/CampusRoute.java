package org.localroute.app.campus;

import org.localroute.core.GeoPoint;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一条校园跑圈路线：地图上的设计点 + 这条路线自己的米/单位比例。
 *
 * <p>预设 A 操 / B 操按目标长度反推比例（400 米 / 600 米），自定义路线使用
 * 固定比例 0.9 米/单位。路线本身不保存运行状态；每次开始跑圈时再叠加
 * 路径浮动并编译成 {@link org.localroute.core.CompiledRoute}。
 */
public final class CampusRoute {

    /** 自定义路线的显示比例：地图上 1 个设计单位 = 0.9 米。 */
    public static final double CUSTOM_METERS_PER_UNIT = 0.9d;

    private final String id;
    private final String name;
    private final List<CampusPoint> points;
    private final List<CampusPoint> rawWaypoints;
    private final double metersPerUnit;
    private final double lapMeters;
    private final boolean custom;

    private CampusRoute(String id, String name, List<CampusPoint> points,
                        List<CampusPoint> rawWaypoints, double metersPerUnit,
                        double lapMeters, boolean custom) {
        this.id = id;
        this.name = name;
        this.points = Collections.unmodifiableList(new ArrayList<>(points));
        this.rawWaypoints = Collections.unmodifiableList(new ArrayList<>(rawWaypoints));
        this.metersPerUnit = metersPerUnit;
        this.lapMeters = lapMeters;
        this.custom = custom;
    }

    public static CampusRoute presetA() {
        return preset("A", "A操", 180d, 490d, 104d, 62d, 400d);
    }

    public static CampusRoute presetB() {
        return preset("B", "B操", 180d, 120d, 126d, 76d, 600d);
    }

    /**
     * 预设跑道：保持地图上的椭圆形状，按目标圈长反算比例。
     * 迭代 3 次，让地理长度与目标值的误差远小于 1 米。
     */
    public static CampusRoute preset(String id, String name, double cx, double cy,
                                     double rx, double ry, double targetMeters) {
        List<CampusPoint> points = CampusGeo.ellipse(cx, cy, rx, ry, 160);
        double scale = targetMeters / CampusGeo.perimeterMeters(points, 1d);
        for (int i = 0; i < 3; i++) {
            double actual = CampusGeo.perimeterMeters(points, scale);
            if (Math.abs(actual - targetMeters) < 0.05d) {
                break;
            }
            scale *= targetMeters / actual;
        }
        double actual = CampusGeo.perimeterMeters(points, scale);
        return new CampusRoute(id, name, points, points, scale, actual, false);
    }

    /** 自定义路线：把航点平滑成闭合环并计算实际长度。 */
    public static CampusRoute custom(String id, String name, List<CampusPoint> waypoints) {
        List<CampusPoint> loop = CampusGeo.smoothClosed(waypoints);
        double meters = CampusGeo.perimeterMeters(loop, CUSTOM_METERS_PER_UNIT);
        return new CampusRoute(id, name, loop, waypoints, CUSTOM_METERS_PER_UNIT, meters, true);
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public boolean isCustom() {
        return custom;
    }

    public double metersPerUnit() {
        return metersPerUnit;
    }

    /** 未叠加路径浮动时的单圈长度（米）。 */
    public double lapMeters() {
        return lapMeters;
    }

    public List<CampusPoint> points() {
        return points;
    }

    /** 用于持久化的原始航点：自定义路线保存用户点选的节点，预设路线保存完整采样点。 */
    public List<CampusPoint> waypointsForSave() {
        return rawWaypoints;
    }

    public CampusPoint startPoint() {
        return points.get(0);
    }

    public GeoPoint startGeo() {
        return CampusGeo.toGeo(startPoint(), metersPerUnit);
    }

    /** 把设计点换算成系统模拟定位使用的 WGS-84 坐标；可选叠加本次跑圈的路径浮动。 */
    public List<GeoPoint> buildGeoPoints(boolean varyPath, long seed) {
        List<CampusPoint> design = varyPath
                ? CampusGeo.drift(points, metersPerUnit, seed)
                : points;
        return CampusGeo.toGeoList(design, metersPerUnit);
    }

    public CampusPoint toDesign(double latitude, double longitude) {
        return CampusGeo.toDesign(new GeoPoint(latitude, longitude), metersPerUnit);
    }
}
