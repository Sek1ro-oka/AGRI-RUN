package org.localroute.app.campus;

import org.localroute.core.GeoPoint;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一条校园跑圈路线：地图上的设计点 + 这条路线自己的米/单位比例。
 *
 * <p>路线分两类：用户在地图上绘制的自定义路线（会经过自动修正与平滑），
 * 以及内置的预设路线（点已经按跑道像素校准好，原样使用）。路线本身不保存
 * 运行状态；每次开始跑圈时编译成 {@link org.localroute.core.CompiledRoute}，
 * 运行期的横向扰动由模拟服务叠加。
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
    private final boolean preset;

    private CampusRoute(String id, String name, List<CampusPoint> points,
                        List<CampusPoint> rawWaypoints, double metersPerUnit,
                        double lapMeters, boolean preset) {
        this.id = id;
        this.name = name;
        this.points = Collections.unmodifiableList(new ArrayList<>(points));
        this.rawWaypoints = Collections.unmodifiableList(new ArrayList<>(rawWaypoints));
        this.metersPerUnit = metersPerUnit;
        this.lapMeters = lapMeters;
        this.preset = preset;
    }

    /**
     * 自定义路线：先自动修正航点（去掉重合点、手抖尖刺和共线冗余点），
     * 再平滑成闭合环并计算实际长度，沿用默认校园基准点。
     * 修正后的航点会被保存，重新绘制时不会把已修正的异常点又带回来。
     */
    public static CampusRoute custom(String id, String name, List<CampusPoint> waypoints) {
        List<CampusPoint> corrected = CampusGeo.autoCorrectClosed(waypoints);
        List<CampusPoint> loop = CampusGeo.smoothClosed(corrected);
        double meters = CampusGeo.perimeterMeters(loop, CUSTOM_METERS_PER_UNIT);
        return new CampusRoute(id, name, loop, corrected, CUSTOM_METERS_PER_UNIT, meters, false);
    }

    /**
     * 内置预设路线：点已经沿真实跑道的像素中心线采样并校准过圈长，
     * 不再做自动修正/平滑，避免改变与橙色跑道的相对位置。
     */
    public static CampusRoute preset(String id, String name, List<CampusPoint> points) {
        List<CampusPoint> copy = new ArrayList<>(points);
        double meters = CampusGeo.perimeterMeters(copy, CUSTOM_METERS_PER_UNIT);
        return new CampusRoute(id, name, copy, copy, CUSTOM_METERS_PER_UNIT, meters, true);
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

    /** 是否为内置预设路线；预设路线不参与绘制时的自动修正。 */
    public boolean isPreset() {
        return preset;
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

    /** 把设计点换算成系统模拟定位使用的 WGS-84 坐标（不含运行期扰动）。 */
    public List<GeoPoint> buildGeoPoints() {
        return CampusGeo.toGeoList(points, metersPerUnit);
    }

    public CampusPoint toDesign(double latitude, double longitude) {
        return CampusGeo.toDesign(new GeoPoint(latitude, longitude), metersPerUnit);
    }
}
