package org.localroute.app.campus;

import org.junit.Test;
import org.localroute.core.GeoPoint;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** 校园快跑几何：自定义路线、坐标往返、路径浮动与取点工具。 */
public class CampusRouteTest {

    private static CampusRoute sampleRoute() {
        List<CampusPoint> waypoints = new ArrayList<>(Arrays.asList(
                new CampusPoint(120, 400),
                new CampusPoint(240, 400),
                new CampusPoint(260, 500),
                new CampusPoint(100, 500)));
        return CampusRoute.custom("custom-1", "自定义 1", waypoints);
    }

    @Test
    public void customRouteIsClosedAndSmooth() {
        List<CampusPoint> waypoints = new ArrayList<>(Arrays.asList(
                new CampusPoint(120, 400),
                new CampusPoint(240, 400),
                new CampusPoint(260, 500),
                new CampusPoint(100, 500)));
        CampusRoute route = CampusRoute.custom("custom-1", "自定义 1", waypoints);
        // 长边按约 2 个设计单位一点自适应采样，4 个航点应远多于旧的固定 14 点/段。
        assertTrue("采样点太少：" + route.points().size(), route.points().size() >= 100);
        assertTrue("采样点异常过多：" + route.points().size(), route.points().size() <= 20000);
        assertTrue("自定义路线太短：" + route.lapMeters(), route.lapMeters() > 120d);
        assertTrue(route.lapMeters() < 800d);
        assertEquals(route.lapMeters(),
                CampusGeo.perimeterMeters(route.points(), route.metersPerUnit()), 1e-6d);

        // 平滑后的曲线仍应经过每个航点附近。
        for (CampusPoint waypoint : waypoints) {
            double nearest = Double.MAX_VALUE;
            for (CampusPoint point : route.points()) {
                nearest = Math.min(nearest, waypoint.distanceTo(point));
            }
            assertTrue("航点偏离平滑曲线：" + nearest, nearest < 12d);
        }

        // 相邻线段方向不能突变：自动修正 + 向心平滑后不应再有折线拐角。
        double maxTurnDegrees = maxTurnDegrees(route.points());
        assertTrue("轨迹存在急拐：" + maxTurnDegrees, maxTurnDegrees < 30d);
    }

    @Test
    public void autoCorrectionRemovesDuplicateAndSpikePoints() {
        List<CampusPoint> waypoints = new ArrayList<>(Arrays.asList(
                new CampusPoint(120, 400),
                new CampusPoint(200, 400),
                new CampusPoint(204, 400),   // 手抖：走出去又立刻折回
                new CampusPoint(200, 400),
                new CampusPoint(260, 500),
                new CampusPoint(260, 500),   // 重合点
                new CampusPoint(100, 500),
                new CampusPoint(120, 400))); // 首尾重复
        List<CampusPoint> corrected = CampusGeo.autoCorrectClosed(waypoints);
        assertEquals(4, corrected.size());
        assertEquals(120d, corrected.get(0).x, 1e-9d);
        assertEquals(400d, corrected.get(0).y, 1e-9d);
    }

    @Test
    public void autoCorrectionKeepsDeliberateCorners() {
        List<CampusPoint> waypoints = new ArrayList<>(Arrays.asList(
                new CampusPoint(120, 400),
                new CampusPoint(240, 400),
                new CampusPoint(260, 500),
                new CampusPoint(100, 500)));
        List<CampusPoint> corrected = CampusGeo.autoCorrectClosed(waypoints);
        assertEquals(waypoints.size(), corrected.size());
    }

    @Test
    public void startPointIsFirstWaypoint() {
        CampusRoute route = sampleRoute();
        // 环形低通会让起点沿切线挪动不到 1 个设计单位，仍应视为同一位置。
        assertEquals(120d, route.startPoint().x, 1d);
        assertEquals(400d, route.startPoint().y, 1d);
    }

    @Test
    public void designAndGeoRoundTrip() {
        CampusRoute route = sampleRoute();
        for (CampusPoint point : route.points()) {
            GeoPoint geo = CampusGeo.toGeo(point, route.metersPerUnit());
            CampusPoint back = CampusGeo.toDesign(geo, route.metersPerUnit());
            assertEquals(point.x, back.x, 0.001d);
            assertEquals(point.y, back.y, 0.001d);
        }
        CampusPoint backStart = route.toDesign(
                route.startGeo().latitude(), route.startGeo().longitude());
        assertEquals(route.startPoint().x, backStart.x, 0.001d);
        assertEquals(route.startPoint().y, backStart.y, 0.001d);
    }

    @Test
    public void designCenterMapsToCampusAnchor() {
        GeoPoint center = CampusGeo.toGeo(
                new CampusPoint(CampusGeo.CENTER_X, CampusGeo.CENTER_Y), 0.9d);
        assertEquals(CampusGeo.ANCHOR_LAT, center.latitude(), 1e-9d);
        assertEquals(CampusGeo.ANCHOR_LON, center.longitude(), 1e-9d);
    }

    @Test
    public void pathDriftIsSmoothAndKeepsRoughLength() {
        CampusRoute route = sampleRoute();
        long seed = 20261110L;
        List<CampusPoint> base = route.points();
        List<CampusPoint> drifted = CampusGeo.drift(base, route.metersPerUnit(), seed);
        assertEquals(base.size(), drifted.size());

        double maxShift = 0d;
        for (int i = 0; i < base.size(); i++) {
            maxShift = Math.max(maxShift, base.get(i).distanceTo(drifted.get(i)));
        }
        // 偏移不超过约 1.3 米对应的设计单位。
        assertTrue("偏移过大：" + maxShift, maxShift * route.metersPerUnit() <= 1.5d);

        double driftedMeters = CampusGeo.perimeterMeters(drifted, route.metersPerUnit());
        assertEquals(route.lapMeters(), driftedMeters, 6d);
    }

    @Test
    public void pathDriftIsDeterministicForSameSeed() {
        List<CampusPoint> base = CampusGeo.ellipse(180d, 310d, 62d, 104d, 160);
        List<CampusPoint> first = CampusGeo.drift(base, 0.9d, 42L);
        List<CampusPoint> second = CampusGeo.drift(base, 0.9d, 42L);
        List<CampusPoint> other = CampusGeo.drift(base, 0.9d, 43L);
        for (int i = 0; i < first.size(); i++) {
            assertEquals(first.get(i).x, second.get(i).x, 1e-9d);
            assertEquals(first.get(i).y, second.get(i).y, 1e-9d);
        }
        boolean differs = false;
        for (int i = 0; i < first.size(); i++) {
            if (first.get(i).distanceTo(other.get(i)) > 1e-6d) {
                differs = true;
                break;
            }
        }
        assertTrue(differs);
    }

    @Test
    public void pointAtFractionFollowsRealLength() {
        List<CampusPoint> points = CampusGeo.ellipse(180d, 310d, 62d, 104d, 160);
        CampusPoint start = CampusGeo.pointAtFraction(points, 0d);
        assertEquals(118d, start.x, 1e-9d);
        assertEquals(310d, start.y, 1e-9d);
        CampusPoint quarter = CampusGeo.pointAtFraction(points, 0.25d);
        // 从左端点出发 1/4 圈位于椭圆北端（设计坐标 y 向下，故 y 更小）。
        assertTrue("1/4 圈 x：" + quarter.x, quarter.x >= 175d && quarter.x <= 185d);
        assertEquals(310d - 104d, quarter.y, 1.5d);
        CampusPoint afterOneLap = CampusGeo.pointAtFraction(points, 1d);
        assertEquals(start.x, afterOneLap.x, 1e-9d);
    }

    @Test
    public void partialLoopInterpolates() {
        List<CampusPoint> points = CampusGeo.ellipse(180d, 310d, 62d, 104d, 160);
        List<CampusPoint> none = CampusGeo.partialLoop(points, 0d);
        assertEquals(1, none.size());
        List<CampusPoint> half = CampusGeo.partialLoop(points, 0.5d);
        assertEquals(81, half.size());
        List<CampusPoint> full = CampusGeo.partialLoop(points, 1d);
        assertEquals(161, full.size());
        assertEquals(points.get(0).x, full.get(full.size() - 1).x, 1e-9d);
    }

    /** 闭合环上相邻线段的最大转角（度）。 */
    private static double maxTurnDegrees(List<CampusPoint> points) {
        int n = points.size();
        double maxTurn = 0d;
        for (int i = 0; i < n; i++) {
            CampusPoint prev = points.get((i - 1 + n) % n);
            CampusPoint current = points.get(i);
            CampusPoint next = points.get((i + 1) % n);
            double ax = current.x - prev.x;
            double ay = current.y - prev.y;
            double bx = next.x - current.x;
            double by = next.y - current.y;
            double cross = ax * by - ay * bx;
            double dot = ax * bx + ay * by;
            if (Math.hypot(ax, ay) < 1e-9d || Math.hypot(bx, by) < 1e-9d) {
                continue;
            }
            maxTurn = Math.max(maxTurn, Math.abs(Math.toDegrees(Math.atan2(cross, dot))));
        }
        return maxTurn;
    }
}
