package org.localroute.app.campus;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 多条自定义路线的本地序列化格式（JSON）。
 *
 * <p>只保存用户点下的航点，不保存平滑后的曲线与圈长：这些由 {@link CampusRoute#custom}
 * 在加载时重新计算，避免把派生数据写进存储后与几何不一致。格式带版本号，后续可以升级；
 * 单条损坏的路线会被跳过，不影响同一个文件里的其它路线。
 */
public final class CampusRouteCodec {

    public static final int SCHEMA_VERSION = 2;
    private static final String KEY_VERSION = "version";
    private static final String KEY_ROUTES = "routes";
    private static final String KEY_ID = "id";
    private static final String KEY_NAME = "name";
    private static final String KEY_PRESET = "preset";
    private static final String KEY_WAYPOINTS = "waypoints";
    private static final String KEY_X = "x";
    private static final String KEY_Y = "y";
    private static final String FALLBACK_NAME = "未命名路线";

    private CampusRouteCodec() {
    }

    /** 把路线列表编码成一段 JSON；列表为空时也返回带版本号的空数组。 */
    public static String encode(List<CampusRoute> routes) {
        JSONObject root = new JSONObject();
        JSONArray array = new JSONArray();
        try {
            root.put(KEY_VERSION, SCHEMA_VERSION);
            if (routes != null) {
                for (CampusRoute route : routes) {
                    if (route == null) {
                        continue;
                    }
                    JSONObject entry = new JSONObject();
                    entry.put(KEY_ID, route.id());
                    entry.put(KEY_NAME, route.name());
                    entry.put(KEY_PRESET, route.isPreset());
                    JSONArray waypoints = new JSONArray();
                    for (CampusPoint point : route.waypointsForSave()) {
                        JSONObject item = new JSONObject();
                        item.put(KEY_X, point.x);
                        item.put(KEY_Y, point.y);
                        waypoints.put(item);
                    }
                    entry.put(KEY_WAYPOINTS, waypoints);
                    array.put(entry);
                }
            }
            root.put(KEY_ROUTES, array);
        } catch (JSONException e) {
            throw new IllegalStateException("路线序列化失败", e);
        }
        return root.toString();
    }

    /**
     * 解析路线列表；无法识别的整体内容返回空列表，单条不合法只跳过该条。
     *
     * <p>过滤规则与绘制入口一致：至少 3 个有限坐标的航点，重新计算出的圈长必须为正。
     */
    public static List<CampusRoute> decode(String raw) {
        List<CampusRoute> result = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) {
            return result;
        }
        JSONArray array;
        try {
            JSONObject root = new JSONObject(raw);
            array = root.optJSONArray(KEY_ROUTES);
        } catch (JSONException e) {
            return result;
        }
        if (array == null) {
            return result;
        }
        Set<String> seenIds = new HashSet<>();
        for (int i = 0; i < array.length(); i++) {
            CampusRoute route = decodeEntry(array.optJSONObject(i));
            if (route == null || seenIds.contains(route.id())) {
                continue;
            }
            seenIds.add(route.id());
            result.add(route);
        }
        return result;
    }

    private static CampusRoute decodeEntry(JSONObject entry) {
        if (entry == null) {
            return null;
        }
        String id = entry.optString(KEY_ID, "").trim();
        if (id.isEmpty()) {
            return null;
        }
        String name = entry.optString(KEY_NAME, "").trim();
        if (name.isEmpty()) {
            name = FALLBACK_NAME;
        }
        JSONArray waypoints = entry.optJSONArray(KEY_WAYPOINTS);
        if (waypoints == null) {
            return null;
        }
        List<CampusPoint> points = new ArrayList<>();
        try {
            for (int i = 0; i < waypoints.length(); i++) {
                JSONObject item = waypoints.optJSONObject(i);
                if (item == null || !item.has(KEY_X) || !item.has(KEY_Y)) {
                    continue;
                }
                points.add(new CampusPoint(item.getDouble(KEY_X), item.getDouble(KEY_Y)));
            }
            if (points.size() < 3) {
                return null;
            }
            // v1 数据没有 preset 字段：按自定义路线重新修正/平滑。
            CampusRoute route = entry.optBoolean(KEY_PRESET, false)
                    ? CampusRoute.preset(id, name, points)
                    : CampusRoute.custom(id, name, points);
            return route.lapMeters() > 0d ? route : null;
        } catch (JSONException | RuntimeException e) {
            return null;
        }
    }
}
