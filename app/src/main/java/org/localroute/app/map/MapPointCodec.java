package org.localroute.app.map;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.localroute.core.GeoPoint;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 收藏点位的本地序列化格式（JSON）。
 *
 * <p>与 {@code CampusRouteCodec} 同一套约定：格式带版本号，整体读不动就返回空列表，
 * 单条不合法只跳过该条——坏数据不该把用户其余的收藏一起带走。
 * 数量上限是为了让备份文件保持在一个能读懂的大小，而不是技术上存不下。
 */
public final class MapPointCodec {

    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_POINTS = 100;

    private static final String KEY_VERSION = "version";
    private static final String KEY_POINTS = "points";
    private static final String KEY_ID = "id";
    private static final String KEY_NAME = "name";
    private static final String KEY_LATITUDE = "latitude";
    private static final String KEY_LONGITUDE = "longitude";
    private static final String KEY_CREATED_AT = "createdAt";

    private MapPointCodec() {
    }

    /** 编码整个列表；列表为空时也返回带版本号的空数组，方便下一步加条目。 */
    @NonNull
    public static String encode(List<MapPoint> points) {
        JSONObject root = new JSONObject();
        JSONArray array = new JSONArray();
        try {
            root.put(KEY_VERSION, SCHEMA_VERSION);
            if (points != null) {
                for (MapPoint point : points) {
                    if (point == null) {
                        continue;
                    }
                    JSONObject item = new JSONObject();
                    item.put(KEY_ID, point.id());
                    item.put(KEY_NAME, point.name());
                    item.put(KEY_LATITUDE, point.point().latitude());
                    item.put(KEY_LONGITUDE, point.point().longitude());
                    item.put(KEY_CREATED_AT, point.createdAtMillis());
                    array.put(item);
                }
            }
            root.put(KEY_POINTS, array);
        } catch (JSONException e) {
            throw new IllegalStateException("收藏点位序列化失败", e);
        }
        return root.toString();
    }

    /** 解析收藏列表；空、坏 JSON、结构不符都返回空列表，单条非法只跳过该条。 */
    @NonNull
    public static List<MapPoint> decode(String raw) {
        List<MapPoint> result = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) {
            return result;
        }
        JSONArray array;
        try {
            array = new JSONObject(raw).optJSONArray(KEY_POINTS);
        } catch (JSONException e) {
            return result;
        }
        if (array == null) {
            return result;
        }
        Set<String> seenIds = new HashSet<>();
        for (int i = 0; i < array.length() && result.size() < MAX_POINTS; i++) {
            MapPoint point = decodeEntry(array.optJSONObject(i));
            if (point == null || !seenIds.add(point.id())) {
                continue;
            }
            result.add(point);
        }
        return result;
    }

    private static MapPoint decodeEntry(JSONObject item) {
        if (item == null) {
            return null;
        }
        String id = item.optString(KEY_ID, "").trim();
        if (id.isEmpty()) {
            return null;
        }
        double latitude = item.optDouble(KEY_LATITUDE, Double.NaN);
        double longitude = item.optDouble(KEY_LONGITUDE, Double.NaN);
        try {
            GeoPoint point = new GeoPoint(latitude, longitude);
            long createdAt = Math.max(0L, item.optLong(KEY_CREATED_AT, 0L));
            return new MapPoint(id, item.optString(KEY_NAME, ""), point, createdAt);
        } catch (RuntimeException e) {
            // 坐标越界或非有限数（GeoPoint 构造会抛）：跳过这条，不整表作废。
            return null;
        }
    }
}
