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

    /** 自动修正时判定重合点的容差（设计单位，约 1 米）。 */
    private static final double CORRECT_DUPLICATE_UNITS = 1.2d;

    /** 航点偏离相邻两点连线小于该值时视为可删除的冗余点（设计单位）。 */
    private static final double CORRECT_LINE_UNITS = 0.8d;

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
     * 自动修正用户点选的航点：去掉相邻重合点、首尾重复点，以及像手抖造成的小尖刺
     * （走出去几米又立刻折回）和几乎共线的冗余点。修正只作用于真正影响轨迹平滑的
     * 异常点，正常情况下保留用户点下的每个节点。
     */
    public static List<CampusPoint> autoCorrectClosed(List<CampusPoint> waypoints) {
        List<CampusPoint> points = new ArrayList<>();
        if (waypoints == null) {
            return points;
        }
        for (CampusPoint point : waypoints) {
            if (point == null) {
                continue;
            }
            if (points.isEmpty()
                    || points.get(points.size() - 1).distanceTo(point) > CORRECT_DUPLICATE_UNITS) {
                points.add(point);
            }
        }
        while (points.size() > 3
                && points.get(0).distanceTo(points.get(points.size() - 1))
                <= CORRECT_DUPLICATE_UNITS) {
            points.remove(points.size() - 1);
        }
        if (points.size() < 4) {
            return points;
        }

        boolean removed = true;
        while (removed && points.size() > 3) {
            removed = false;
            for (int i = 0; i < points.size() && points.size() > 3; i++) {
                CampusPoint prev = points.get((i - 1 + points.size()) % points.size());
                CampusPoint current = points.get(i);
                CampusPoint next = points.get((i + 1) % points.size());
                double in = prev.distanceTo(current);
                double out = current.distanceTo(next);
                double chord = prev.distanceTo(next);
                if (in < 1e-9d || out < 1e-9d) {
                    points.remove(i);
                    removed = true;
                    i--;
                    continue;
                }
                double detour = in + out - chord;
                double deviation = distanceToSegment(current, prev, next);
                boolean spike = in < 12d && out < 12d && detour > 2.5d && deviation < 6d;
                boolean redundant = detour > 1.5d && deviation < CORRECT_LINE_UNITS;
                if (spike || redundant) {
                    points.remove(i);
                    removed = true;
                    i--;
                }
            }
        }
        return points;
    }

    /**
     * 把用户点选的航点平滑成闭合环（向心 Catmull-Rom，按段长自适应采样）。
     *
     * <p>向心参数化（α=0.5）能避免均匀 Catmull-Rom 在疏密不均的航点上过冲、
     * 打结；采样密度按约 2 个设计单位一个点，长直段不会只剩几段折线。采完后再做
     * 一次环形三点低通，消掉节点处残留的拐角，整条轨迹更顺。
     * 输入点不要求重复首点；输出不重复首点，闭合由运行引擎补边。
     */
    public static List<CampusPoint> smoothClosed(List<CampusPoint> waypoints) {
        if (waypoints.size() < 3) {
            return new ArrayList<>(waypoints);
        }
        int n = waypoints.size();
        int sampleCap = Math.max(4, Math.min(32, 20000 / n));
        List<CampusPoint> result = new ArrayList<>(n * Math.min(sampleCap, 12));
        for (int i = 0; i < n; i++) {
            CampusPoint p0 = waypoints.get((i - 1 + n) % n);
            CampusPoint p1 = waypoints.get(i);
            CampusPoint p2 = waypoints.get((i + 1) % n);
            CampusPoint p3 = waypoints.get((i + 2) % n);
            int samples = (int) Math.max(4,
                    Math.min(sampleCap, Math.ceil(p1.distanceTo(p2) / 2d)));
            appendCentripetalSegment(p0, p1, p2, p3, samples, result);
        }
        return smoothCircular(result);
    }

    /** 用向心 Catmull-Rom 在 p1→p2 之间采 {@code samples} 个点（不含终点）。 */
    private static void appendCentripetalSegment(CampusPoint p0, CampusPoint p1,
                                                 CampusPoint p2, CampusPoint p3,
                                                 int samples, List<CampusPoint> out) {
        double t0 = 0d;
        double t1 = t0 + Math.pow(Math.max(1e-9d, p0.distanceTo(p1)), 0.5d);
        double t2 = t1 + Math.pow(Math.max(1e-9d, p1.distanceTo(p2)), 0.5d);
        double t3 = t2 + Math.pow(Math.max(1e-9d, p2.distanceTo(p3)), 0.5d);
        for (int s = 0; s < samples; s++) {
            double t = t1 + (t2 - t1) * s / (double) samples;
            double x = catmullRomCoordinate(p0.x, p1.x, p2.x, p3.x, t0, t1, t2, t3, t);
            double y = catmullRomCoordinate(p0.y, p1.y, p2.y, p3.y, t0, t1, t2, t3, t);
            out.add(new CampusPoint(x, y));
        }
    }

    /** Barry–Goldman 形式的一维向心 Catmull-Rom 取值。 */
    private static double catmullRomCoordinate(double a0, double a1, double a2, double a3,
                                               double t0, double t1, double t2, double t3,
                                               double t) {
        double a1v = lerpIfPossible(a0, a1, t0, t1, t);
        double a2v = lerpIfPossible(a1, a2, t1, t2, t);
        double a3v = lerpIfPossible(a2, a3, t2, t3, t);
        double b1 = lerpIfPossible(a1v, a2v, t0, t2, t);
        double b2 = lerpIfPossible(a2v, a3v, t1, t3, t);
        return lerpIfPossible(b1, b2, t1, t2, t);
    }

    private static double lerpIfPossible(double from, double to, double fromT, double toT, double t) {
        if (toT - fromT < 1e-12d) {
            return to;
        }
        double ratio = (t - fromT) / (toT - fromT);
        return from + (to - from) * ratio;
    }

    /** 环形三点低通（0.25 / 0.5 / 0.25），只做一次，避免把拐角削得过圆。 */
    private static List<CampusPoint> smoothCircular(List<CampusPoint> points) {
        int n = points.size();
        if (n < 5) {
            return points;
        }
        List<CampusPoint> result = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            CampusPoint prev = points.get((i - 1 + n) % n);
            CampusPoint current = points.get(i);
            CampusPoint next = points.get((i + 1) % n);
            result.add(new CampusPoint(
                    0.25d * prev.x + 0.5d * current.x + 0.25d * next.x,
                    0.25d * prev.y + 0.5d * current.y + 0.25d * next.y));
        }
        return result;
    }

    /** 点到线段的最短距离（设计单位）；早退的线段按端点处理。 */
    private static double distanceToSegment(CampusPoint point, CampusPoint from, CampusPoint to) {
        double dx = to.x - from.x;
        double dy = to.y - from.y;
        double lengthSquared = dx * dx + dy * dy;
        if (lengthSquared < 1e-12d) {
            return point.distanceTo(from);
        }
        double t = ((point.x - from.x) * dx + (point.y - from.y) * dy) / lengthSquared;
        t = Math.max(0d, Math.min(1d, t));
        double x = from.x + dx * t;
        double y = from.y + dy * t;
        return Math.hypot(point.x - x, point.y - y);
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
            // 低频为主，高频只留一点「人气」，避免轨迹出现肉眼可见的锯齿抖动。
            double wave = 0.62d * Math.sin(Math.PI * 4d * t + p1)
                    + 0.26d * Math.sin(Math.PI * 8d * t + p2)
                    + 0.12d * Math.sin(Math.PI * 14d * t + p3);
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
