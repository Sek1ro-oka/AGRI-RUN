package org.localroute.app.campus;

import org.localroute.core.GeoMath;
import org.localroute.core.GeoPoint;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * 校园快跑的设计坐标 ↔ WGS-84 换算与几何工具。
 *
 * <p>地图是 360 × 620 的示意图，不是真实比例尺；每条路线自带
 * {@code metersPerUnit}（每设计单位对应多少米）。所有可见路线与提交给
 * 系统的模拟位置都由同一组设计点换算，跑者位置再换算回设计坐标绘制。
 *
 * <p>路线以浙江农林大学东湖校区附近的固定点为基准。这是课程演示用的
 * 虚拟位置，不声称是专业测绘坐标。
 */
public final class CampusGeo {

    public static final double MAP_WIDTH = 360d;
    public static final double MAP_HEIGHT = 620d;
    public static final double CENTER_X = 180d;
    public static final double CENTER_Y = 310d;

    /** 演示基准点（WGS-84）。 */
    public static final double ANCHOR_LAT = 30.2561d;
    public static final double ANCHOR_LON = 119.7236d;

    /** 1 度纬度对应的米数。局部小范围内使用等距圆柱近似即可。 */
    private static final double METERS_PER_DEGREE_LAT = 111320d;

    private CampusGeo() {
    }

    private static double metersPerDegreeLon(double latitude) {
        return METERS_PER_DEGREE_LAT * Math.cos(Math.toRadians(latitude));
    }

    /**
     * 设计坐标 → WGS-84：把设计中心 {@code (CENTER_X, CENTER_Y)} 放到基准点，
     * y 向下为南，x 向右为东。
     */
    public static GeoPoint toGeo(CampusPoint point, double metersPerUnit) {
        double latitude = ANCHOR_LAT - (point.y - CENTER_Y) * metersPerUnit / METERS_PER_DEGREE_LAT;
        double longitude = ANCHOR_LON + (point.x - CENTER_X) * metersPerUnit / metersPerDegreeLon(ANCHOR_LAT);
        return new GeoPoint(latitude, longitude);
    }

    /** WGS-84 → 设计坐标，与 {@link #toGeo(CampusPoint, double)} 互逆。 */
    public static CampusPoint toDesign(GeoPoint point, double metersPerUnit) {
        double x = CENTER_X + (point.longitude() - ANCHOR_LON) * metersPerDegreeLon(ANCHOR_LAT) / metersPerUnit;
        double y = CENTER_Y - (point.latitude() - ANCHOR_LAT) * METERS_PER_DEGREE_LAT / metersPerUnit;
        return new CampusPoint(x, y);
    }

    public static List<GeoPoint> toGeoList(List<CampusPoint> points, double metersPerUnit) {
        List<GeoPoint> result = new ArrayList<>(points.size());
        for (CampusPoint point : points) {
            result.add(toGeo(point, metersPerUnit));
        }
        return result;
    }

    /** 椭圆跑道采样：从最左侧点开始，顺时针一圈（与原型地图的起点一致）。 */
    public static List<CampusPoint> ellipse(double cx, double cy, double rx, double ry, int samples) {
        if (samples < 8) {
            throw new IllegalArgumentException("椭圆采样点太少：" + samples);
        }
        List<CampusPoint> points = new ArrayList<>(samples);
        for (int i = 0; i < samples; i++) {
            double angle = Math.PI + Math.PI * 2d * i / samples;
            points.add(new CampusPoint(cx + rx * Math.cos(angle), cy + ry * Math.sin(angle)));
        }
        return points;
    }

    /**
     * 把用户点选的航点平滑成闭合环（Catmull-Rom，每段 14 个采样点）。
     * 输入点不要求重复首点；输出不重复首点，闭合由运行引擎补边。
     */
    public static List<CampusPoint> smoothClosed(List<CampusPoint> waypoints) {
        if (waypoints.size() < 3) {
            return new ArrayList<>(waypoints);
        }
        final int samplesPerSegment = 14;
        int n = waypoints.size();
        List<CampusPoint> result = new ArrayList<>(n * samplesPerSegment);
        for (int i = 0; i < n; i++) {
            CampusPoint p0 = waypoints.get((i - 1 + n) % n);
            CampusPoint p1 = waypoints.get(i);
            CampusPoint p2 = waypoints.get((i + 1) % n);
            CampusPoint p3 = waypoints.get((i + 2) % n);
            for (int s = 0; s < samplesPerSegment; s++) {
                double t = s / (double) samplesPerSegment;
                double t2 = t * t;
                double t3 = t2 * t;
                double x = 0.5d * ((2d * p1.x)
                        + (-p0.x + p2.x) * t
                        + (2d * p0.x - 5d * p1.x + 4d * p2.x - p3.x) * t2
                        + (-p0.x + 3d * p1.x - 3d * p2.x + p3.x) * t3);
                double y = 0.5d * ((2d * p1.y)
                        + (-p0.y + p2.y) * t
                        + (2d * p0.y - 5d * p1.y + 4d * p2.y - p3.y) * t2
                        + (-p0.y + 3d * p1.y - 3d * p2.y + p3.y) * t3);
                result.add(new CampusPoint(x, y));
            }
        }
        return result;
    }

    /** 闭合环的地理长度（米）。 */
    public static double perimeterMeters(List<CampusPoint> points, double metersPerUnit) {
        int n = points.size();
        if (n < 2) {
            return 0d;
        }
        double total = 0d;
        for (int i = 0; i < n; i++) {
            GeoPoint a = toGeo(points.get(i), metersPerUnit);
            GeoPoint b = toGeo(points.get((i + 1) % n), metersPerUnit);
            total += GeoMath.distanceMeters(a, b);
        }
        return total;
    }

    /**
     * 路径浮动：在闭环两侧叠加平滑的法向偏移。
     *
     * <p>偏移量以米计（约 0.7—1.3 米），换算成设计单位后加在法线方向；
     * 同一次跑圈内振幅和相位固定，不同跑圈由 seed 决定，因此每次轨迹略有差异。
     * 该偏移会真正进入提交给系统的路线，而不是只改画面。
     */
    public static List<CampusPoint> drift(List<CampusPoint> points, double metersPerUnit, long seed) {
        int n = points.size();
        if (n < 3) {
            return new ArrayList<>(points);
        }
        Random random = new Random(seed);
        double amplitudeMeters = 0.7d + random.nextDouble() * 0.6d;
        double p1 = random.nextDouble() * Math.PI * 2d;
        double p2 = random.nextDouble() * Math.PI * 2d;
        double p3 = random.nextDouble() * Math.PI * 2d;
        List<CampusPoint> result = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            CampusPoint prev = points.get((i - 1 + n) % n);
            CampusPoint next = points.get((i + 1) % n);
            double tx = next.x - prev.x;
            double ty = next.y - prev.y;
            double length = Math.hypot(tx, ty);
            double nx = length == 0d ? 0d : -ty / length;
            double ny = length == 0d ? 0d : tx / length;
            double t = i / (double) n;
            double wave = 0.55d * Math.sin(Math.PI * 4d * t + p1)
                    + 0.30d * Math.sin(Math.PI * 10d * t + p2)
                    + 0.15d * Math.sin(Math.PI * 18d * t + p3);
            double offsetUnits = amplitudeMeters * wave / metersPerUnit;
            result.add(new CampusPoint(points.get(i).x + nx * offsetUnits, points.get(i).y + ny * offsetUnits));
        }
        return result;
    }

    /**
     * 闭合环上按真实线段长度取 {@code fraction}（0..1，1 回到起点）处的位置。
     * 用于把跑者的累计距离换算成地图上的位置。
     */
    public static CampusPoint pointAtFraction(List<CampusPoint> points, double fraction) {
        int n = points.size();
        if (n == 0) {
            return null;
        }
        if (n == 1) {
            return points.get(0);
        }
        double clamped = fraction % 1d;
        if (clamped < 0d) {
            clamped += 1d;
        }
        if (clamped == 0d) {
            return points.get(0);
        }
        double[] segments = new double[n];
        double total = 0d;
        for (int i = 0; i < n; i++) {
            segments[i] = points.get(i).distanceTo(points.get((i + 1) % n));
            total += segments[i];
        }
        if (total == 0d) {
            return points.get(0);
        }
        double target = total * clamped;
        double walked = 0d;
        for (int i = 0; i < n; i++) {
            if (walked + segments[i] >= target) {
                double t = segments[i] == 0d ? 0d : (target - walked) / segments[i];
                CampusPoint a = points.get(i);
                CampusPoint b = points.get((i + 1) % n);
                return new CampusPoint(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t);
            }
            walked += segments[i];
        }
        return points.get(0);
    }

    /** 取闭合环的一部分用于绘制进度线：返回 0..fraction 的折线（含插值末点）。 */
    public static List<CampusPoint> partialLoop(List<CampusPoint> points, double fraction) {
        if (points.isEmpty()) {
            return Collections.emptyList();
        }
        double clamped = Math.max(0d, Math.min(1d, fraction));
        int n = points.size();
        if (clamped <= 0d) {
            return new ArrayList<>(Collections.singletonList(points.get(0)));
        }
        if (clamped >= 1d) {
            List<CampusPoint> full = new ArrayList<>(points);
            full.add(points.get(0));
            return full;
        }
        // 按段数近似：各段设计长度相差不大，进度线只用于视觉。
        double totalSegments = clamped * n;
        int whole = (int) Math.floor(totalSegments);
        double rest = totalSegments - whole;
        List<CampusPoint> result = new ArrayList<>(whole + 2);
        for (int i = 0; i <= whole && i < n; i++) {
            result.add(points.get(i));
        }
        if (rest > 1e-9d && whole < n) {
            CampusPoint a = points.get(whole % n);
            CampusPoint b = points.get((whole + 1) % n);
            result.add(new CampusPoint(a.x + (b.x - a.x) * rest, a.y + (b.y - a.y) * rest));
        }
        return result;
    }
}
