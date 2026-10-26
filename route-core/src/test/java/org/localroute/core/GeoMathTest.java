package org.localroute.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 球面几何与坐标校验的单元测试。 */
public class GeoMathTest {

    private static final double DELTA_METERS = 0.5d;

    @Test
    public void distance_oneDegreeOfLatitude_isAbout111km() {
        double distance = GeoMath.distanceMeters(new GeoPoint(0d, 0d), new GeoPoint(1d, 0d));
        assertEquals(111_194.9d, distance, 5d);
    }

    @Test
    public void distance_isSymmetric() {
        GeoPoint a = new GeoPoint(31.2304d, 121.4737d);
        GeoPoint b = new GeoPoint(31.2350d, 121.4800d);
        assertEquals(GeoMath.distanceMeters(a, b), GeoMath.distanceMeters(b, a), 1e-6d);
    }

    @Test
    public void distance_acrossAntimeridian_takesShortPath() {
        GeoPoint west = new GeoPoint(0d, 179.99d);
        GeoPoint east = new GeoPoint(0d, -179.99d);
        double distance = GeoMath.distanceMeters(west, east);
        // 0.02 度经度差在赤道约等于 2226 米，而不是绕地球大半圈。
        assertEquals(2_226d, distance, 20d);
    }

    @Test
    public void bearing_cardinalDirections() {
        GeoPoint origin = new GeoPoint(0d, 0d);
        assertEquals(0d, GeoMath.initialBearingDegrees(origin, new GeoPoint(1d, 0d)), 0.01d);
        assertEquals(90d, GeoMath.initialBearingDegrees(origin, new GeoPoint(0d, 1d)), 0.01d);
        assertEquals(180d, GeoMath.initialBearingDegrees(origin, new GeoPoint(-1d, 0d)), 0.01d);
        assertEquals(270d, GeoMath.initialBearingDegrees(origin, new GeoPoint(0d, -1d)), 0.01d);
    }

    @Test
    public void interpolate_midpointIsHalfway() {
        GeoPoint a = new GeoPoint(0d, 0d);
        GeoPoint b = new GeoPoint(0d, 1d);
        GeoPoint mid = GeoMath.interpolate(a, b, 0.5d);
        assertEquals(0d, mid.latitude(), 1e-9d);
        assertEquals(0.5d, mid.longitude(), 1e-6d);
        assertEquals(GeoMath.distanceMeters(a, b) / 2d, GeoMath.distanceMeters(a, mid), DELTA_METERS);
    }

    @Test
    public void interpolate_acrossAntimeridian_staysNearDateLine() {
        GeoPoint west = new GeoPoint(10d, 179.9d);
        GeoPoint east = new GeoPoint(10d, -179.9d);
        GeoPoint mid = GeoMath.interpolate(west, east, 0.5d);
        assertTrue("插值不应跑到本初子午线附近，实际 lon=" + mid.longitude(),
                Math.abs(mid.longitude()) > 179d);
        // 同纬两点的球面中点会略微偏向极点（大圆在两极之间“鼓起”），此处约 +1.5e-5 度；
        // 只要没有跑到 0 度附近、纬度仍在原纬度附近（1e-3 度 ≈ 110 米）就算正确。
        assertEquals(10d, mid.latitude(), 1e-3d);
    }

    @Test
    public void interpolate_clampsFraction() {
        GeoPoint a = new GeoPoint(1d, 2d);
        GeoPoint b = new GeoPoint(3d, 4d);
        assertEquals(a, GeoMath.interpolate(a, b, -0.5d));
        assertEquals(b, GeoMath.interpolate(a, b, 1.5d));
    }

    @Test
    public void normalizeLongitude_wrapsIntoRange() {
        assertEquals(179d, GeoMath.normalizeLongitude(179d), 1e-12d);
        assertEquals(-179d, GeoMath.normalizeLongitude(181d), 1e-12d);
        assertEquals(0d, GeoMath.normalizeLongitude(360d), 1e-12d);
        assertEquals(-170d, GeoMath.normalizeLongitude(190d), 1e-12d);
    }

    @Test
    public void geoPoint_rejectsInvalidValues() {
        assertThrows(IllegalArgumentException.class, () -> new GeoPoint(91d, 0d));
        assertThrows(IllegalArgumentException.class, () -> new GeoPoint(-91d, 0d));
        assertThrows(IllegalArgumentException.class, () -> new GeoPoint(0d, 181d));
        assertThrows(IllegalArgumentException.class, () -> new GeoPoint(Double.NaN, 0d));
        assertThrows(IllegalArgumentException.class,
                () -> new GeoPoint(Double.POSITIVE_INFINITY, 0d));
    }
}
