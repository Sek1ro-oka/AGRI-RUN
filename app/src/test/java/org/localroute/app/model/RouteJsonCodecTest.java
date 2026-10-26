package org.localroute.app.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONException;
import org.junit.Test;
import org.localroute.core.CompiledRoute;
import org.localroute.core.GeoMath;
import org.localroute.core.GeoPoint;
import org.localroute.core.InvalidRouteException;
import org.localroute.core.RouteCompiler;
import org.localroute.core.RunErrorCode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** 路线 JSON 编解码与字段校验。 */
public class RouteJsonCodecTest {

    private RouteDefinition sample() {
        Instant created = Instant.parse("2026-10-26T10:00:00Z");
        Instant updated = Instant.parse("2026-10-26T10:05:00Z");
        List<GeoPoint> points = Arrays.asList(
                new GeoPoint(30.100000d, 120.100000d),
                new GeoPoint(30.101000d, 120.101000d),
                new GeoPoint(30.102000d, 120.100000d));
        return new RouteDefinition("route-20261026-100000-abcdef", "操场测试", points, created, updated);
    }

    @Test
    public void roundTripKeepsAllFields() {
        RouteDefinition original = sample();
        try {
            RouteDefinition decoded = RouteJsonCodec.decode(RouteJsonCodec.encode(original));
            assertEquals(original.id(), decoded.id());
            assertEquals(original.name(), decoded.name());
            assertEquals(1, decoded.schemaVersion());
            assertEquals("WGS84", decoded.coordinateSystem());
            assertTrue(decoded.closed());
            assertEquals(original.createdAt(), decoded.createdAt());
            assertEquals(original.updatedAt(), decoded.updatedAt());
            assertEquals(original.points().size(), decoded.points().size());
            for (int i = 0; i < original.points().size(); i++) {
                assertEquals(original.points().get(i), decoded.points().get(i));
            }
        } catch (JSONException | RouteJsonCodec.RouteFormatException e) {
            fail("编解码不应失败：" + e);
        }
    }

    @Test
    public void keepsOpenRouteFlagAndCompilesAsOutAndBack() {
        // 0.1.1 起 closed=false 是合法状态：路线沿原路返回，不再被拒绝。
        String json = replaceJson(encode(sample()), "\"closed\"", "true", "false");
        try {
            RouteDefinition decoded = RouteJsonCodec.decode(json);
            assertFalse("closed=false 应原样保留", decoded.closed());
            CompiledRoute compiled = RouteCompiler.compile(decoded.points(), decoded.closed());
            assertFalse(compiled.closed());
            assertEquals(4, compiled.vertexCount());
            double oneWay = GeoMath.distanceMeters(decoded.points().get(0), decoded.points().get(1))
                    + GeoMath.distanceMeters(decoded.points().get(1), decoded.points().get(2));
            assertEquals("往返一圈 = 单程的两倍", 2 * oneWay, compiled.lapLengthMeters(), 0.05);
        } catch (JSONException | RouteJsonCodec.RouteFormatException | InvalidRouteException e) {
            fail("不闭合路线应可保存并编译：" + e);
        }
    }

    @Test
    public void rejectsUnknownCoordinateSystem() {
        String json = encode(sample()).replace("\"WGS84\"", "\"GCJ02\"");
        expectFormatError(json);
    }

    @Test
    public void rejectsUnknownSchemaVersion() {
        String json = replaceJson(encode(sample()), "\"schemaVersion\"", "1", "2");
        expectFormatError(json);
    }

    @Test
    public void rejectsOutOfRangeLatitude() {
        String json = "{\"schemaVersion\":1,\"id\":\"route-a\",\"name\":\"x\","
                + "\"coordinateSystem\":\"WGS84\",\"closed\":true,"
                + "\"createdAt\":\"2026-10-26T10:00:00Z\",\"updatedAt\":\"2026-10-26T10:00:00Z\","
                + "\"points\":[{\"latitude\":91.0,\"longitude\":120.0},"
                + "{\"latitude\":30.0,\"longitude\":120.0}]}";
        expectFormatError(json);
    }

    @Test
    public void rejectsTooManyPoints() {
        List<GeoPoint> points = new ArrayList<>();
        for (int i = 0; i <= RouteCompiler.MAX_POINTS; i++) {
            points.add(new GeoPoint(30d + i * 1e-5d, 120d));
        }
        Instant now = Instant.parse("2026-10-26T10:00:00Z");
        RouteDefinition route = new RouteDefinition("route-big", "big", points, now, now);
        expectFormatError(encode(route));
    }

    @Test
    public void rejectsEmptyId() {
        Instant now = Instant.parse("2026-10-26T10:00:00Z");
        // 空 id 不能构造出 RouteDefinition（否则会写出 .json 这种诡异文件名）。
        assertThrows(IllegalArgumentException.class, () -> new RouteDefinition("", "x",
                Arrays.asList(new GeoPoint(30d, 120d), new GeoPoint(30.1d, 120.1d)), now, now));
        // 手改文件把 id 清空时，解码必须拒绝。
        String json = replaceJson(encode(sample()), "\"id\"", "\"route-20261026-100000-abcdef\"", "\"\"");
        expectFormatError(json);
    }

    @Test
    public void singlePointDraftCanBeSavedButNotCompiled() throws Exception {
        Instant now = Instant.parse("2026-10-26T10:00:00Z");
        RouteDefinition draft = new RouteDefinition("route-one", "草稿",
                Arrays.asList(new GeoPoint(30d, 120d)), now, now);
        // 少于 2 点只是草稿：允许保存、允许读回，但不允许开始运行（编译期拦截）。
        RouteDefinition decoded = RouteJsonCodec.decode(RouteJsonCodec.encode(draft));
        assertEquals(1, decoded.points().size());
        InvalidRouteException error = assertThrows(InvalidRouteException.class,
                () -> RouteCompiler.compile(decoded.points()));
        assertEquals(RunErrorCode.ROUTE_INVALID, error.errorCode());
    }

    /** 把某个键的值替换掉（兼容 org.json 的 `"key": value` 与 `"key":value` 两种输出）。 */
    private String replaceJson(String json, String quotedKey, String fromValue, String toValue) {
        String replaced = json.replaceAll(
                java.util.regex.Pattern.quote(quotedKey) + "\\s*:\\s*" + java.util.regex.Pattern.quote(fromValue),
                quotedKey + ":" + toValue);
        assertFalse("测试用例没有匹配到 " + quotedKey, replaced.equals(json));
        return replaced;
    }

    @Test
    public void decodeIgnoresUnknownKeysButKeepsKnownOnes() {
        String json = encode(sample()).replaceFirst("\\{", "{\"futureField\":\"ignored\",");
        try {
            RouteDefinition decoded = RouteJsonCodec.decode(json);
            assertFalse(decoded.points().isEmpty());
        } catch (JSONException | RouteJsonCodec.RouteFormatException e) {
            fail("未知字段不应导致失败：" + e);
        }
    }

    private String encode(RouteDefinition route) {
        try {
            return RouteJsonCodec.encode(route);
        } catch (JSONException e) {
            fail("编码失败：" + e);
            return "";
        }
    }

    private void expectFormatError(String json) {
        try {
            RouteJsonCodec.decode(json);
            fail("应当抛出 RouteFormatException");
        } catch (RouteJsonCodec.RouteFormatException expected) {
            assertTrue(expected.getMessage() != null && !expected.getMessage().isEmpty());
        } catch (JSONException e) {
            // 结构性非法 JSON 也属于拒绝，但本测试期望的是格式校验异常。
            fail("应抛出 RouteFormatException 而不是 JSONException：" + e);
        }
    }
}
