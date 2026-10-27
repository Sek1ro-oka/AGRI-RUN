package org.localroute.app.model;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.localroute.core.EndBehavior;
import org.localroute.core.GeoPoint;
import org.localroute.core.RouteCompiler;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * 路线文件的 JSON 编解码。
 *
 * <p>文件格式（schemaVersion=2，v1 仍可读）：
 * <pre>
 * {
 *   "schemaVersion": 2,
 *   "id": "20261026T120000-abc123",
 *   "name": "示例方形路线",
 *   "coordinateSystem": "WGS84",
 *   "closed": true,
 *   "createdAt": "2026-10-26T12:00:00Z",
 *   "updatedAt": "2026-10-26T12:00:00Z",
 *   "points": [ { "latitude": 0.0, "longitude": 0.0 }, ... ],
 *   "runDefaults": { "endBehavior": "STOP_AT_END", "repeatCount": 1, "speedKmh": 9.0 }
 * }
 * </pre>
 *
 * <p>v1 文件没有 {@code runDefaults}：{@code closed=true} 仍按绕圈，{@code closed=false} 保留旧的
 * “原路往返”运行默认值；只有新建的 v2 草稿才默认“到终点停止”（审查方案 5.3 第 5 条）。
 * 经纬度字段名固定为 {@code latitude}/{@code longitude}，顺序为先纬度后经度；
 * GeoJSON 导出（P1）才使用 {@code [lon, lat]} 的顺序。
 */
public final class RouteJsonCodec {

    private RouteJsonCodec() {
    }

    public static String encode(RouteDefinition route) throws JSONException {
        JSONObject root = new JSONObject();
        root.put("schemaVersion", RouteDefinition.SCHEMA_VERSION);
        root.put("id", route.id());
        root.put("name", route.name());
        root.put("coordinateSystem", route.coordinateSystem());
        root.put("closed", route.closed());
        root.put("createdAt", route.createdAt().toString());
        root.put("updatedAt", route.updatedAt().toString());
        JSONObject runDefaults = new JSONObject();
        runDefaults.put("endBehavior", route.endBehavior().name());
        runDefaults.put("repeatCount", route.runDefaults().repeatCount());
        runDefaults.put("speedKmh", route.runDefaults().speedKmh());
        root.put("runDefaults", runDefaults);
        JSONArray points = new JSONArray();
        for (GeoPoint point : route.points()) {
            JSONObject item = new JSONObject();
            item.put("latitude", point.latitude());
            item.put("longitude", point.longitude());
            points.put(item);
        }
        root.put("points", points);
        return root.toString(2);
    }

    public static RouteDefinition decode(String json) throws JSONException, RouteFormatException {
        JSONObject root = new JSONObject(json);
        int schemaVersion = root.optInt("schemaVersion", -1);
        if (schemaVersion != 1 && schemaVersion != RouteDefinition.SCHEMA_VERSION) {
            throw new RouteFormatException("不支持的 schemaVersion：" + schemaVersion);
        }
        String coordinateSystem = root.optString("coordinateSystem", "");
        if (!RouteDefinition.COORDINATE_SYSTEM_WGS84.equals(coordinateSystem)) {
            throw new RouteFormatException("不支持的坐标系：" + coordinateSystem);
        }
        String id = root.optString("id", "").trim();
        if (id.isEmpty()) {
            throw new RouteFormatException("缺少路线 id");
        }
        String name = root.optString("name", "");
        JSONArray pointsArray = root.optJSONArray("points");
        if (pointsArray == null) {
            throw new RouteFormatException("缺少 points");
        }
        if (pointsArray.length() > RouteCompiler.MAX_POINTS) {
            throw new RouteFormatException("节点数超过上限：" + pointsArray.length());
        }
        List<GeoPoint> points = new ArrayList<>(pointsArray.length());
        for (int i = 0; i < pointsArray.length(); i++) {
            JSONObject item = pointsArray.optJSONObject(i);
            if (item == null || !item.has("latitude") || !item.has("longitude")) {
                throw new RouteFormatException("第 " + i + " 个节点缺少经纬度");
            }
            double latitude = item.optDouble("latitude", Double.NaN);
            double longitude = item.optDouble("longitude", Double.NaN);
            try {
                points.add(new GeoPoint(latitude, longitude));
            } catch (IllegalArgumentException e) {
                throw new RouteFormatException("第 " + i + " 个节点坐标非法：" + e.getMessage(), e);
            }
        }
        Instant createdAt = parseInstant(root.optString("createdAt", ""));
        Instant updatedAt = parseInstant(root.optString("updatedAt", ""));
        boolean closed = root.optBoolean("closed", true);
        EndBehavior endBehavior;
        RunDefaults runDefaults;
        if (schemaVersion == 1) {
            // v1 没有终点策略：保持旧行为，绝不把旧路线悄悄改成单程。
            endBehavior = EndBehavior.legacyForClosed(closed);
            runDefaults = RunDefaults.defaults();
        } else {
            JSONObject defaults = root.optJSONObject("runDefaults");
            EndBehavior fallback = EndBehavior.forDraft(closed);
            endBehavior = defaults == null
                    ? fallback
                    : EndBehavior.parse(defaults.optString("endBehavior", ""), fallback);
            if (closed && endBehavior != EndBehavior.LOOP) {
                endBehavior = EndBehavior.LOOP;
            }
            if (!closed && endBehavior == EndBehavior.LOOP) {
                endBehavior = EndBehavior.STOP_AT_END;
            }
            int repeatCount = defaults == null ? 1 : defaults.optInt("repeatCount", 1);
            double speedKmh = defaults == null ? RunDefaults.DEFAULT_SPEED_KMH
                    : defaults.optDouble("speedKmh", RunDefaults.DEFAULT_SPEED_KMH);
            try {
                runDefaults = new RunDefaults(repeatCount, speedKmh);
            } catch (IllegalArgumentException e) {
                throw new RouteFormatException("runDefaults 非法：" + e.getMessage(), e);
            }
        }
        return new RouteDefinition(id, name, points, closed, endBehavior, runDefaults,
                createdAt == null ? Instant.now() : createdAt,
                updatedAt == null ? (createdAt == null ? Instant.now() : createdAt) : updatedAt);
    }

    private static Instant parseInstant(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** 文件内容不合法（与 JSON 语法错误区分开，便于界面提示）。 */
    public static class RouteFormatException extends Exception {
        public RouteFormatException(String message) {
            super(message);
        }

        public RouteFormatException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
