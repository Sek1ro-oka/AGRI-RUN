package org.localroute.app.campus;

import org.junit.Test;
import org.localroute.core.GeoPoint;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** 校园快跑几何：预设圈长、坐标往返、路径浮动与自定义平滑。 */
public class CampusRouteTest {

    @Test
    public void presetAIsAbout400Meters() {
        CampusRoute route = CampusRoute.presetA();
        assertEquals(400d, route.lapMeters(), 1d);
        assertEquals("A操", route.name());
        assertEquals(160, route.points().size());
    }

    @Test
    public void presetBIsAbout600Meters() {
        CampusRoute route = CampusRoute.presetB();
        assertEquals(600d, route.lapMeters(), 1d);
        assertEquals("B操", route.name());
    }

    @Test
    public void designAndGeoRoundTrip() {
        CampusRoute route = CampusRoute.presetA();
        for (CampusPoint point : route.points()) {
            GeoPoint geo = CampusGeo.toGeo(point, route.metersPerUnit());
            CampusPoint back = CampusGeo.toDesign(geo, route.metersPerUnit());
            assertEquals(point.x, back.x, 0.001d);
            assertEquals(point.y, back.y, 0.001d);
        }
    }

    @Test
    public void startPointIsLeftmost() {
        CampusRoute route = CampusRoute.presetA();
        assertEquals(180d - 104d, route.startPoint().x, 0.001d);
        assertEquals(490d, route.startPoint().y, 0.001d);
    }

    @Test
    public void pathDriftIsSmoothAndKeepsRoughLength() {
        CampusRoute route = CampusRoute.presetA();
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
        CampusRoute route = CampusRoute.presetB();
        List<CampusPoint> first = CampusGeo.drift(route.points(), route.metersPerUnit(), 42L);
        List<CampusPoint> second = CampusGeo.drift(route.points(), route.metersPerUnit(), 42L);
        List<CampusPoint> other = CampusGeo.drift(route.points(), route.metersPerUnit(), 43L);
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
    public void customRouteIsClosedAndSmooth() {
        List<CampusPoint> waypoints = new ArrayList<>(Arrays.asList(
                new CampusPoint(120, 400),
                new CampusPoint(240, 400),
                new CampusPoint(260, 500),
                new CampusPoint(100, 500)));
        CampusRoute route = CampusRoute.custom("custom-1", "自定义 1", waypoints);
        assertEquals(4 * 14, route.points().size());
        assertTrue(route.isCustom());
        assertTrue("自定义路线太短：" + route.lapMeters(), route.lapMeters() > 120d);
        assertTrue(route.lapMeters() < 800d);

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
    public void pointAtFractionFollowsRealLength() {
        CampusRoute route = CampusRoute.presetA();
        CampusPoint start = CampusGeo.pointAtFraction(route.points(), 0d);
        assertEquals(route.startPoint().x, start.x, 1e-9d);
        CampusPoint quarter = CampusGeo.pointAtFraction(route.points(), 0.25d);
        // 从左端点出发 1/4 圈位于椭圆顶点附近（设计坐标 y 向下，故 y 更小）。
        assertTrue("1/4 圈 x：" + quarter.x, quarter.x >= 175d && quarter.x <= 185d);
        assertEquals(490d - 62d, quarter.y, 1.5d);
        CampusPoint afterOneLap = CampusGeo.pointAtFraction(route.points(), 1d);
        assertEquals(start.x, afterOneLap.x, 1e-9d);
    }

    @Test
    public void partialLoopInterpolates() {
        CampusRoute route = CampusRoute.presetA();
        List<CampusPoint> none = CampusGeo.partialLoop(route.points(), 0d);
        assertEquals(1, none.size());
        List<CampusPoint> half = CampusGeo.partialLoop(route.points(), 0.5d);
        assertEquals(81, half.size());
        List<CampusPoint> full = CampusGeo.partialLoop(route.points(), 1d);
        assertEquals(161, full.size());
        assertEquals(route.points().get(0).x, full.get(full.size() - 1).x, 1e-9d);
    }
}
