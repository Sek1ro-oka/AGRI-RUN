package org.localroute.app.campus;

import org.localroute.core.GeoPoint;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一条校园跑圈路线：地图上的设计点 + 这条路线自己的米/单位比例。
 *
 * <p>目前路线只来自用户在地图上绘制的自定义路线，使用固定比例 0.9 米/单位
 * 与默认校园基准点；后续可由导入能力补充更多路线。路线本身不保存运行状态；
 * 每次开始跑圈时再叠加路径浮动并编译成 {@link org.localroute.core.CompiledRoute}。
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

    private CampusRoute(String id, String name, List<CampusPoint> points,
                        List<CampusPoint> rawWaypoints, double metersPerUnit,
                        double lapMeters) {
        this.id = id;
        this.name = name;
        this.points = Collections.unmodifiableList(new ArrayList<>(points));
        this.rawWaypoints = Collections.unmodifiableList(new ArrayList<>(rawWaypoints));
        this.metersPerUnit = metersPerUnit;
        this.lapMeters = lapMeters;
    }

    /** 自定义路线：把航点平滑成闭合环并计算实际长度，沿用默认校园基准点。 */
    public static CampusRoute custom(String id, String name, List<CampusPoint> waypoints) {
        List<CampusPoint> loop = CampusGeo.smoothClosed(waypoints);
        double meters = CampusGeo.perimeterMeters(loop, CUSTOM_METERS_PER_UNIT);
        return new CampusRoute(id, name, loop, waypoints, CUSTOM_METERS_PER_UNIT, meters);
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
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

    /** 用于持久化的原始航点：自定义路线保存用户点选的节点。 */
    public List<CampusPoint> waypointsForSave() {
        return rawWaypoints;
    }

    public CampusPoint startPoint() {
        return points.get(0);
    }

    /** 当前路线在真实地图上的 WGS-84 坐标（不叠加路径浮动）。 */
    public List<GeoPoint> geoPoints() {
        return CampusGeo.toGeoList(points, metersPerUnit);
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
