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
        assertEquals(4 * 14, route.points().size());
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
    }

    @Test
    public void startPointIsFirstWaypoint() {
        CampusRoute route = sampleRoute();
        assertEquals(120d, route.startPoint().x, 1e-9d);
        assertEquals(400d, route.startPoint().y, 1e-9d);
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
}
