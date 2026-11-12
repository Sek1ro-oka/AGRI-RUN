package org.localroute.app.run;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.localroute.core.GeoMath;
import org.localroute.core.GeoPoint;

import java.util.ArrayList;
import java.util.List;

/** 路线扰动的边界：只向外、幅度受限、圈间不重合、起终点淡入淡出。 */
public class RouteVaryTest {

    private static final double CENTER_LAT = 30.2561d;
    private static final double CENTER_LON = 119.7236d;
    private static final double RADIUS_METERS = 40d;
    private static final double LAP_METERS = 2d * Math.PI * RADIUS_METERS;

    private static List<GeoPoint> circle(int count) {
        List<GeoPoint> points = new ArrayList<>(count);
        double metersPerDegreeLon = 111320d * Math.cos(Math.toRadians(CENTER_LAT));
        for (int i = 0; i < count; i++) {
            double angle = 2d * Math.PI * i / count;
            double east = RADIUS_METERS * Math.cos(angle);
            double north = RADIUS_METERS * Math.sin(angle);
            points.add(new GeoPoint(CENTER_LAT + north / 111320d,
                    CENTER_LON + east / metersPerDegreeLon));
        }
        return points;
    }

    private static GeoPoint pointAt(double angleRadians) {
        double metersPerDegreeLon = 111320d * Math.cos(Math.toRadians(CENTER_LAT));
        return new GeoPoint(
                CENTER_LAT + RADIUS_METERS * Math.sin(angleRadians) / 111320d,
                CENTER_LON + RADIUS_METERS * Math.cos(angleRadians) / metersPerDegreeLon);
    }

    /** 逆时针行进时该点的方位角（十进制度）。 */
    private static double bearingAt(double angleRadians) {
        return Math.toDegrees(Math.atan2(-Math.sin(angleRadians), Math.cos(angleRadians)));
    }

    @Test
    public void zeroStrengthReturnsBaseUnchanged() {
        RouteVary vary = RouteVary.create(circle(64), LAP_METERS, 0d, 42L);
        assertTrue(!vary.enabled());
        GeoPoint base = pointAt(0.6d);
        assertSame(base, vary.apply(base, bearingAt(0.6d), 37d));
    }

    @Test
    public void strengthIsClampedToUnitRange() {
        assertEquals(0d, RouteVary.create(circle(64), LAP_METERS, -3d, 1L).strength(), 1e-12d);
        assertEquals(1d, RouteVary.create(circle(64), LAP_METERS, 4d, 1L).strength(), 1e-12d);
    }

    @Test
    public void offsetNeverExceedsLimitAndAlwaysPointsOutward() {
        RouteVary vary = RouteVary.create(circle(96), LAP_METERS, 1d, 7L);
        double maxOffset = 0d;
        for (double angle = 0.07d; angle < Math.PI * 2d; angle += 0.11d) {
            GeoPoint base = pointAt(angle);
            double baseRadius = GeoMath.distanceMeters(base, new GeoPoint(CENTER_LAT, CENTER_LON));
            for (double distance = 12d; distance < LAP_METERS - 12d; distance += 9d) {
                GeoPoint shifted = vary.apply(base, bearingAt(angle), distance);
                double radius = GeoMath.distanceMeters(shifted, new GeoPoint(CENTER_LAT, CENTER_LON));
                double offset = radius - baseRadius;
                assertTrue("扰动不应向内偏移: " + offset, offset >= -1e-3d);
                assertTrue("扰动超出上限: " + offset,
                        offset <= RouteVary.MAX_OFFSET_METERS + 1e-3d);
                maxOffset = Math.max(maxOffset, offset);
            }
        }
        assertTrue("多圈累计应出现明显偏移: " + maxOffset, maxOffset > 0.5d);
    }

    @Test
    public void lapBoundariesFadeBackToBase() {
        RouteVary vary = RouteVary.create(circle(64), LAP_METERS, 1d, 11L);
        GeoPoint base = pointAt(1.2d);
        double bearing = bearingAt(1.2d);
        assertSame(base, vary.apply(base, bearing, 0d));
        assertSame(base, vary.apply(base, bearing, LAP_METERS));
        GeoPoint nearEdge = vary.apply(base, bearing, 4d);
        double inner = GeoMath.distanceMeters(nearEdge, base);
        GeoPoint middle = vary.apply(base, bearing, LAP_METERS / 2d);
        double center = GeoMath.distanceMeters(middle, base);
        assertTrue("起终点附近偏移应小于圈中偏移", inner < center);
    }

    @Test
    public void samePhysicalSpotDiffersBetweenLaps() {
        RouteVary vary = RouteVary.create(circle(96), LAP_METERS, 1d, 2026L);
        GeoPoint base = pointAt(0.4d);
        double bearing = bearingAt(0.4d);
        int differing = 0;
        for (double distance = 30d; distance < LAP_METERS - 30d; distance += 17d) {
            GeoPoint first = vary.apply(base, bearing, distance);
            GeoPoint second = vary.apply(base, bearing, distance + LAP_METERS);
            if (GeoMath.distanceMeters(first, second) > 0.05d) {
                differing++;
            }
        }
        assertTrue("多数位置相邻两圈应不重合: " + differing, differing >= 5);
    }

    @Test
    public void sameSeedIsDeterministic() {
        GeoPoint base = pointAt(2.1d);
        double bearing = bearingAt(2.1d);
        RouteVary first = RouteVary.create(circle(96), LAP_METERS, 0.8d, 99L);
        RouteVary second = RouteVary.create(circle(96), LAP_METERS, 0.8d, 99L);
        RouteVary other = RouteVary.create(circle(96), LAP_METERS, 0.8d, 100L);
        boolean seedMatters = false;
        for (double distance = 25d; distance < LAP_METERS - 25d; distance += 23d) {
            GeoPoint a = first.apply(base, bearing, distance);
            GeoPoint b = second.apply(base, bearing, distance);
            assertEquals(a, b);
            if (GeoMath.distanceMeters(a, other.apply(base, bearing, distance)) > 0.02d) {
                seedMatters = true;
            }
        }
        assertTrue("不同种子应产生不同轨迹", seedMatters);
    }
}
