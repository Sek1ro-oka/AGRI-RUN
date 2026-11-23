package org.localroute.app.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.localroute.core.GeoMath;
import org.localroute.core.GeoPoint;

/**
 * 粘贴文本的坐标识别：认得出的要准，认不出的要安静退回地名搜索。
 */
public class CoordinateInputTest {

    private static GeoPoint hit(String raw) {
        CoordinateInput.Hit parsed = CoordinateInput.parse(raw);
        assertNotNull(raw, parsed);
        return parsed.point();
    }

    private static void assertNoHit(String raw) {
        assertNull(raw + " 不该被当成坐标", CoordinateInput.parse(raw));
    }

    @Test
    public void barePairIsTakenAsLatitudeThenLongitude() {
        GeoPoint point = hit("30.123456,120.654321");
        assertEquals(30.123456, point.latitude(), 1e-9);
        assertEquals(120.654321, point.longitude(), 1e-9);
        assertFalse(CoordinateInput.parse("30.123456,120.654321").converted());
    }

    @Test
    public void separatorsAndSurroundingSpaceAreAccepted() {
        assertEquals(120.2d, hit("30.1，120.2").longitude(), 1e-9);
        assertEquals(120.2d, hit("  30.1 , 120.2  ").longitude(), 1e-9);
        assertEquals(120.2d, hit("30.1 120.2").longitude(), 1e-9);
        assertEquals(120.2d, hit("30.1;120.2").longitude(), 1e-9);
        assertEquals(120.2d, hit("30.1/120.2").longitude(), 1e-9);
        // 分享文本常见的第三个数是缩放级，不参与坐标。
        assertEquals(120.2d, hit("30.1,120.2,15").longitude(), 1e-9);
        assertEquals(120.2d, hit("30.1, 120.2, 15z").longitude(), 1e-9);
    }

    @Test
    public void negativeAndSignedNumbersWork() {
        GeoPoint sydney = hit("-33.856, 151.215");
        assertEquals(-33.856, sydney.latitude(), 1e-9);
        assertEquals(151.215, sydney.longitude(), 1e-9);
        assertEquals(-8.5, hit("+30.1,-8.5").longitude(), 1e-9);
    }

    @Test
    public void appOwnFormatRoundTrips() {
        GeoPoint source = new GeoPoint(28.198765, -63.123456);
        GeoPoint parsed = hit(source.format());
        assertEquals(source.latitude(), parsed.latitude(), 1e-9);
        assertEquals(source.longitude(), parsed.longitude(), 1e-9);
    }

    @Test
    public void outOfRangeIsNotACoordinate() {
        assertNoHit("120.2, 300.5");
        assertNoHit("190, 20");
        assertNoHit("30.1, 181.5");
        assertNoHit("91,91");
    }

    @Test
    public void ordinarySearchWordsAreLeftAlone() {
        assertNoHit(null);
        assertNoHit("");
        assertNoHit("30");
        assertNoHit("西湖");
        assertNoHit("文一西路 30.1");
        assertNoHit("30.1, 120.2 附近");
        assertNoHit("abc,def");
        assertNoHit("2026-11-24");
        assertNoHit("https://maps.app.goo.gl/xYz123");
    }

    @Test
    public void degreeMinutesSecondsNeedHemispheres() {
        GeoPoint point = hit("30°15'12.6\"N 120°27'15.6\"E");
        assertEquals(30.2535, point.latitude(), 1e-4);
        assertEquals(120.4543, point.longitude(), 1e-4);

        GeoPoint south = hit("33°51'S, 151°12'E");
        assertTrue(south.latitude() < 0);
        assertTrue(south.longitude() > 0);
        // 没有半球字母就不算度分秒，免得把「30°15'」当成完整坐标。
        assertNoHit("30°15' 120°27'");
    }

    @Test
    public void googleMapsUrlsUseTheirNumbersAsIs() {
        GeoPoint at = hit("https://www.google.com/maps/place/杭州/@30.2741,120.1551,15z/data=!3m1!1b3");
        assertEquals(30.2741, at.latitude(), 1e-9);
        assertEquals(120.1551, at.longitude(), 1e-9);
        assertFalse(CoordinateInput.parse(
                "https://www.google.com/maps/place/杭州/@30.2741,120.1551,15z").converted());

        GeoPoint query = hit("https://www.google.com/maps/search/?api=1&query=30.2741,120.1551");
        assertEquals(30.2741, query.latitude(), 1e-9);

        GeoPoint encoded = hit("https://www.google.com/maps?q=30.2741%2C120.1551");
        assertEquals(120.1551, encoded.longitude(), 1e-9);
    }

    @Test
    public void amapUrlsAreConvertedOutOfGcj02() {
        String raw = "https://ditu.amap.com/regeo?lng=120.1551&lat=30.2741&name=公司";
        CoordinateInput.Hit parsed = CoordinateInput.parse(raw);

        assertNotNull(parsed);
        assertTrue("高德链接要说明换算过", parsed.converted());
        // 杭州城区的 GCJ-02 偏移量级是百米，既不是原值、也不该偏出几公里。
        double shift = GeoMath.distanceMeters(parsed.point(), new GeoPoint(30.2741, 120.1551));
        assertTrue("偏移 " + shift + " 米不合理", shift > 20d && shift < 2_000d);
        assertEquals(30.2741, parsed.point().latitude(), 0.05);
        assertEquals(120.1551, parsed.point().longitude(), 0.05);
    }

    @Test
    public void amapPositionParameterIsLongitudeFirst() {
        String raw = "https://uri.amap.com/marker?position=120.1551,30.2741&name=东门";
        CoordinateInput.Hit parsed = CoordinateInput.parse(raw);

        assertNotNull(parsed);
        assertTrue(parsed.converted());
        // 顺序认错会把点丢到几千公里外，这里用纬度量级兜住。
        assertTrue(parsed.point().latitude() > 29d && parsed.point().latitude() < 31d);
        assertTrue(parsed.point().longitude() > 119d && parsed.point().longitude() < 121d);
    }

    @Test
    public void foreignGcjPointIsNotShifted() {
        // 高德境外坐标不做偏移（GCJ-02 只在境内生效），换算标记要跟着变回「未转换」。
        String raw = "https://ditu.amap.com/regeo?lng=139.74&lat=35.68";
        CoordinateInput.Hit parsed = CoordinateInput.parse(raw);

        assertNotNull(parsed);
        assertFalse(parsed.converted());
        assertEquals(35.68, parsed.point().latitude(), 1e-9);
    }

    @Test
    public void baiduLinksAreNotClaimed() {
        // BD-09 没有换算实现，认出来反而会给出错位置，所以整条不认。
        assertNoHit("https://j.map.baidu.com/ab12x");
        assertNoHit("https://api.map.baidu.com/geocoder?location=30.2741,120.1551&output=json");
    }

    @Test
    public void keyedParametersWorkInEitherOrder() {
        GeoPoint first = hit("lat=30.2741&lng=120.1551");
        GeoPoint second = hit("lng=120.1551&lat=30.2741");
        assertEquals(first.latitude(), second.latitude(), 1e-9);
        assertEquals(first.longitude(), second.longitude(), 1e-9);
    }

    @Test
    public void shortTextAndNoiseNeverProduceAHit() {
        assertNoHit("a,b");
        assertNoHit(",,");
        assertNoHit("30.1,");
        assertNoHit("点击 https://example.com 查看");
    }
}
