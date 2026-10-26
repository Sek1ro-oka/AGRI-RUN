package org.localroute.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** {@link ChinaShift} 的性质测试：不依赖某个具体实现版本的“标准答案”，只钉住可验证的性质。 */
public class ChinaShiftTest {

    /** 北京天安门附近的 WGS-84 坐标（示例值，不是任何真实任务点）。 */
    private static final double BEIJING_LAT = 39.9087;
    private static final double BEIJING_LON = 116.3975;

    @Test
    public void outOfChina_isTrueForLondon() {
        assertTrue(ChinaShift.outOfChina(51.5074, -0.1278));
    }

    @Test
    public void outOfChina_isFalseForBeijingAndShanghai() {
        assertFalse(ChinaShift.outOfChina(BEIJING_LAT, BEIJING_LON));
        assertFalse(ChinaShift.outOfChina(31.2304, 121.4737));
    }

    @Test
    public void wgs84ToGcj02_leavesForeignPointsUnchanged() {
        GeoPoint london = ChinaShift.wgs84ToGcj02(51.5074, -0.1278);
        assertEquals(51.5074, london.latitude(), 1e-12);
        assertEquals(-0.1278, london.longitude(), 1e-12);
    }

    @Test
    public void wgs84ToGcj02_inBeijing_shiftsHundredsOfMeters() {
        GeoPoint shifted = ChinaShift.wgs84ToGcj02(BEIJING_LAT, BEIJING_LON);
        double offset = GeoMath.distanceMeters(
                new GeoPoint(BEIJING_LAT, BEIJING_LON), shifted);
        // GCJ-02 在国内的偏移量级是数百米：太小说明没偏移，太大说明公式写错。
        assertTrue("偏移过小：" + offset, offset > 100.0);
        assertTrue("偏移过大：" + offset, offset < 1500.0);
        // 北京一带的偏移方向：纬度、经度都会变大，这是众所周知的“地图往西南挪”的表现。
        assertTrue(shifted.latitude() > BEIJING_LAT);
        assertTrue(shifted.longitude() > BEIJING_LON);
    }

    @Test
    public void gcj02ToWgs84_reversesWithinHalfMeter() {
        GeoPoint wgs = new GeoPoint(BEIJING_LAT, BEIJING_LON);
        GeoPoint gcj = ChinaShift.wgs84ToGcj02(wgs.latitude(), wgs.longitude());
        GeoPoint back = ChinaShift.gcj02ToWgs84(gcj.latitude(), gcj.longitude());
        double error = GeoMath.distanceMeters(wgs, back);
        assertTrue("往返误差过大：" + error + " 米", error < 0.5);
    }

    @Test
    public void gcj02ToWgs84_leavesForeignPointsUnchanged() {
        GeoPoint london = ChinaShift.gcj02ToWgs84(51.5074, -0.1278);
        assertEquals(51.5074, london.latitude(), 1e-12);
        assertEquals(-0.1278, london.longitude(), 1e-12);
    }

    @Test
    public void shiftedPoints_stayWithinValidCoordinateRange() {
        GeoPoint south = ChinaShift.wgs84ToGcj02(18.25, 109.51);
        GeoPoint north = ChinaShift.wgs84ToGcj02(45.75, 126.65);
        assertTrue(south.latitude() >= -90 && south.latitude() <= 90);
        assertTrue(south.longitude() >= -180 && south.longitude() <= 180);
        assertTrue(north.latitude() >= -90 && north.latitude() <= 90);
        assertTrue(north.longitude() >= -180 && north.longitude() <= 180);
    }
}
