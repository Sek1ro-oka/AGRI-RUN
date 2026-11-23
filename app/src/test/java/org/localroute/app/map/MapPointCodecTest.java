package org.localroute.app.map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.localroute.core.GeoPoint;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 收藏点位序列化的边界用例：坏一条只丢一条，超限只截断，绝不因为一条坏数据清空整表。
 */
public class MapPointCodecTest {

    private static MapPoint point(String id, String name, double lat, double lon, long createdAt) {
        return new MapPoint(id, name, new GeoPoint(lat, lon), createdAt);
    }

    @Test
    public void roundTripKeepsEveryField() {
        List<MapPoint> source = Arrays.asList(
                point("a", "南门", 30.123456, 120.654321, 1_700_000_000_000L),
                point("b", "图书馆", 30.5, 120.5, 0L));

        List<MapPoint> restored = MapPointCodec.decode(MapPointCodec.encode(source));

        assertEquals(2, restored.size());
        assertEquals("a", restored.get(0).id());
        assertEquals("南门", restored.get(0).name());
        assertEquals(30.123456, restored.get(0).point().latitude(), 1e-9);
        assertEquals(120.654321, restored.get(0).point().longitude(), 1e-9);
        assertEquals(1_700_000_000_000L, restored.get(0).createdAtMillis());
        assertEquals("b", restored.get(1).id());
    }

    @Test
    public void emptyAndBrokenInputGiveEmptyList() {
        assertTrue(MapPointCodec.decode(null).isEmpty());
        assertTrue(MapPointCodec.decode("").isEmpty());
        assertTrue(MapPointCodec.decode("   ").isEmpty());
        assertTrue(MapPointCodec.decode("不是 JSON").isEmpty());
        assertTrue(MapPointCodec.decode("{\"points\":[]}").isEmpty());
        // 结构对不上（缺 points 数组）也只当没有收藏，不能崩。
        assertTrue(MapPointCodec.decode("{\"version\":1,\"items\":[]}").isEmpty());
    }

    @Test
    public void encodeOfNullOrEmptyStillCarriesTheVersion() {
        assertEquals(0, MapPointCodec.decode(MapPointCodec.encode(null)).size());
        String raw = MapPointCodec.encode(new ArrayList<MapPoint>());
        assertTrue(raw, raw.contains("\"version\""));
        assertTrue(raw, raw.contains("\"points\""));
        assertEquals(0, MapPointCodec.decode(raw).size());
    }

    @Test
    public void badEntryIsSkippedAndTheRestSurvives() {
        String raw = "{\"version\":1,\"points\":["
                + "{\"id\":\"a\",\"name\":\"好的\",\"latitude\":30.1,\"longitude\":120.1,\"createdAt\":5},"
                + "{\"id\":\"b\",\"name\":\"越界\",\"latitude\":999,\"longitude\":120.1},"
                + "{\"id\":\"c\",\"name\":\"非数\",\"latitude\":\"abc\",\"longitude\":120.1},"
                + "{\"id\":\"\",\"name\":\"没有 id\",\"latitude\":30.2,\"longitude\":120.2},"
                + "{\"name\":\"也缺 id\",\"latitude\":30.3,\"longitude\":120.3},"
                + "{\"id\":\"e\",\"latitude\":null,\"longitude\":120.5},"
                + "{\"id\":\"g\",\"name\":\"又能读\",\"latitude\":30.4,\"longitude\":120.4}"
                + "]}";

        List<MapPoint> decoded = MapPointCodec.decode(raw);

        assertEquals(2, decoded.size());
        assertEquals("a", decoded.get(0).id());
        assertEquals("g", decoded.get(1).id());
        // 没有收藏时间时按 0 记，列表只显示名称与坐标，不影响可用性。
        assertEquals(0L, decoded.get(1).createdAtMillis());
    }

    @Test
    public void duplicateIdsKeepTheFirstEntry() {
        String raw = "{\"version\":1,\"points\":["
                + "{\"id\":\"a\",\"name\":\"第一次\",\"latitude\":30.1,\"longitude\":120.1},"
                + "{\"id\":\"a\",\"name\":\"第二次\",\"latitude\":30.9,\"longitude\":120.9}"
                + "]}";

        List<MapPoint> decoded = MapPointCodec.decode(raw);

        assertEquals(1, decoded.size());
        assertEquals("第一次", decoded.get(0).name());
    }

    @Test
    public void decodeStopsAtTheCapacity() {
        StringBuilder builder = new StringBuilder("{\"version\":1,\"points\":[");
        for (int i = 0; i < MapPointCodec.MAX_POINTS + 25; i++) {
            builder.append(i == 0 ? "" : ",")
                    .append("{\"id\":\"p").append(i)
                    .append("\",\"latitude\":30.0,\"longitude\":120.0}");
        }
        builder.append("]}");

        List<MapPoint> decoded = MapPointCodec.decode(builder.toString());

        assertEquals(MapPointCodec.MAX_POINTS, decoded.size());
        assertEquals("p0", decoded.get(0).id());
    }

    @Test
    public void namesFallBackAndTruncate() {
        MapPoint blank = new MapPoint("x", "   ", new GeoPoint(30d, 120d), 1L);
        assertEquals(MapPoint.FALLBACK_NAME, blank.name());

        StringBuilder longName = new StringBuilder();
        for (int i = 0; i < MapPoint.MAX_NAME_LENGTH + 20; i++) {
            longName.append('点');
        }
        MapPoint clipped = new MapPoint("y", longName.toString(), new GeoPoint(30d, 120d), 1L);
        assertEquals(MapPoint.MAX_NAME_LENGTH, clipped.name().length());

        assertEquals("改名", clipped.withName("改名").name());
        assertEquals("y", clipped.withName(null).id());
        assertEquals(MapPoint.FALLBACK_NAME, clipped.withName("").name());
        assertEquals(clipped.createdAtMillis(), clipped.withName("改名").createdAtMillis());
    }

    @Test
    public void createUsesRandomIdsAndClampsTime() {
        MapPoint first = MapPoint.create("东门", new GeoPoint(30d, 120d), -5L);
        MapPoint second = MapPoint.create("东门", new GeoPoint(30d, 120d), -5L);

        assertNotNull(first.id());
        assertTrue(first.id().length() > 8);
        assertTrue(!first.id().equals(second.id()));
        assertEquals(0L, first.createdAtMillis());
        assertEquals("东门", first.name());
    }

    @Test(expected = IllegalArgumentException.class)
    public void missingIdIsRejected() {
        new MapPoint("  ", "点", new GeoPoint(30d, 120d), 0L);
    }

    @Test
    public void nullEntriesAreIgnoredByEncode() {
        List<MapPoint> mixed = new ArrayList<>();
        mixed.add(point("a", "南门", 30.1, 120.1, 1L));
        mixed.add(null);

        List<MapPoint> restored = MapPointCodec.decode(MapPointCodec.encode(mixed));

        assertEquals(1, restored.size());
        assertEquals("南门", restored.get(0).name());
    }
}
