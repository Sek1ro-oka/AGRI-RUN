package org.localroute.app.campus;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** 多条自定义路线的 JSON 存取：往返、坏数据隔离与重复 ID 过滤。 */
public class CampusRouteCodecTest {

    private static CampusRoute route(String id, String name) {
        return CampusRoute.custom(id, name, Arrays.asList(
                new CampusPoint(120, 400),
                new CampusPoint(240, 400),
                new CampusPoint(260, 500),
                new CampusPoint(100, 500)));
    }

    @Test
    public void roundTripKeepsNamesAndWaypoints() {
        List<CampusRoute> routes = new ArrayList<>();
        routes.add(route("route-a", "东湖一圈"));
        routes.add(route("route-b", "操场热身"));

        List<CampusRoute> decoded = CampusRouteCodec.decode(CampusRouteCodec.encode(routes));

        assertEquals(2, decoded.size());
        for (int i = 0; i < routes.size(); i++) {
            CampusRoute before = routes.get(i);
            CampusRoute after = decoded.get(i);
            assertEquals(before.id(), after.id());
            assertEquals(before.name(), after.name());
            assertEquals(before.lapMeters(), after.lapMeters(), 1e-6d);
            assertEquals(before.waypointsForSave().size(), after.waypointsForSave().size());
            for (int j = 0; j < before.waypointsForSave().size(); j++) {
                assertEquals(before.waypointsForSave().get(j).x,
                        after.waypointsForSave().get(j).x, 1e-9d);
                assertEquals(before.waypointsForSave().get(j).y,
                        after.waypointsForSave().get(j).y, 1e-9d);
            }
        }
    }

    @Test
    public void presetFlagAndCalibratedLengthSurviveRoundTrip() {
        List<CampusRoute> routes = new ArrayList<>();
        routes.add(CampusPresets.routeA());
        routes.add(CampusPresets.routeB());

        List<CampusRoute> decoded = CampusRouteCodec.decode(CampusRouteCodec.encode(routes));

        assertEquals(2, decoded.size());
        assertEquals(CampusPresets.routeA().lapMeters(), decoded.get(0).lapMeters(), 1e-6d);
        assertEquals(CampusPresets.routeB().lapMeters(), decoded.get(1).lapMeters(), 1e-6d);
        assertTrue("预设路线重新解码后仍应保持预设标记，避免被再次平滑",
                decoded.get(0).isPreset());
        assertTrue(decoded.get(1).isPreset());
    }

    @Test
    public void legacyEntriesDecodeAsCustomRoutes() {
        String raw = "{\"version\":1,\"routes\":["
                + "{\"id\":\"legacy\",\"name\":\"旧路线\",\"waypoints\":["
                + "{\"x\":120,\"y\":400},{\"x\":240,\"y\":400},{\"x\":260,\"y\":500}]}"
                + "]}";
        List<CampusRoute> decoded = CampusRouteCodec.decode(raw);
        assertEquals(1, decoded.size());
        assertTrue("v1 数据没有 preset 字段，应按自定义路线处理", !decoded.get(0).isPreset());
    }

    @Test
    public void emptyListRoundTripsToEmpty() {
        String encoded = CampusRouteCodec.encode(new ArrayList<>());
        assertTrue(encoded.contains("\"version\""));
        assertTrue(CampusRouteCodec.decode(encoded).isEmpty());
        assertTrue(CampusRouteCodec.decode("").isEmpty());
        assertTrue(CampusRouteCodec.decode(null).isEmpty());
    }

    @Test
    public void invalidJsonReturnsEmptyInsteadOfCrashing() {
        assertTrue(CampusRouteCodec.decode("{not json").isEmpty());
        assertTrue(CampusRouteCodec.decode("{\"version\":1}").isEmpty());
    }

    @Test
    public void malformedEntryIsSkipped() {
        String raw = "{\"version\":1,\"routes\":["
                + "{\"id\":\"bad-points\",\"name\":\"坏路线\",\"waypoints\":[{\"x\":1,\"y\":2}]},"
                + "{\"id\":\"bad-number\",\"name\":\"坏坐标\",\"waypoints\":["
                + "{\"x\":\"abc\",\"y\":2},{\"x\":3,\"y\":4},{\"x\":5,\"y\":6}]},"
                + "{\"id\":\"good\",\"name\":\"好路线\",\"waypoints\":["
                + "{\"x\":120,\"y\":400},{\"x\":240,\"y\":400},{\"x\":260,\"y\":500}]}"
                + "]}";
        List<CampusRoute> decoded = CampusRouteCodec.decode(raw);
        assertEquals(1, decoded.size());
        assertEquals("good", decoded.get(0).id());
    }

    @Test
    public void duplicateIdsKeepFirstEntry() {
        String raw = "{\"version\":1,\"routes\":["
                + "{\"id\":\"same\",\"name\":\"第一条\",\"waypoints\":["
                + "{\"x\":120,\"y\":400},{\"x\":240,\"y\":400},{\"x\":260,\"y\":500}]},"
                + "{\"id\":\"same\",\"name\":\"第二条\",\"waypoints\":["
                + "{\"x\":120,\"y\":400},{\"x\":240,\"y\":400},{\"x\":260,\"y\":500}]}"
                + "]}";
        List<CampusRoute> decoded = CampusRouteCodec.decode(raw);
        assertEquals(1, decoded.size());
        assertEquals("第一条", decoded.get(0).name());
    }

    @Test
    public void missingNameFallsBack() {
        String raw = "{\"version\":1,\"routes\":["
                + "{\"id\":\"unnamed\",\"waypoints\":["
                + "{\"x\":120,\"y\":400},{\"x\":240,\"y\":400},{\"x\":260,\"y\":500}]}"
                + "]}";
        List<CampusRoute> decoded = CampusRouteCodec.decode(raw);
        assertEquals(1, decoded.size());
        assertEquals("未命名路线", decoded.get(0).name());
    }
}
