package org.localroute.app.backup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SharedPreferences 值的带类型序列化。
 *
 * <p>必须带类型标记：JSON 会把 3 和 3.0 混成一类数字，读回来时
 * 用 getInt/getLong/getFloat 猜错就抛 ClassCastException，进而让整次导入中断。
 */
public final class BackupValues {

    public static final String TYPE_BOOLEAN = "b";
    public static final String TYPE_INT = "i";
    public static final String TYPE_LONG = "l";
    public static final String TYPE_FLOAT = "f";
    public static final String TYPE_STRING = "s";

    private BackupValues() {
    }

    @NonNull
    public static JSONObject pack(@NonNull Map<String, Object> values) throws JSONException {
        JSONObject root = new JSONObject();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            Object value = entry.getValue();
            if (value == null) {
                continue;
            }
            JSONObject item = new JSONObject();
            if (value instanceof Boolean) {
                item.put("t", TYPE_BOOLEAN);
                item.put("v", value);
            } else if (value instanceof Integer) {
                item.put("t", TYPE_INT);
                item.put("v", ((Integer) value).longValue());
            } else if (value instanceof Long) {
                item.put("t", TYPE_LONG);
                item.put("v", value);
            } else if (value instanceof Float || value instanceof Double) {
                item.put("t", TYPE_FLOAT);
                item.put("v", value);
            } else {
                item.put("t", TYPE_STRING);
                item.put("v", String.valueOf(value));
            }
            root.put(entry.getKey(), item);
        }
        return root;
    }

    /** 未知类型标记的条目直接跳过，导入时宁可少一个偏好也不要写坏成另一种类型。 */
    @NonNull
    public static Map<String, Object> unpack(@Nullable JSONObject root) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (root == null) {
            return values;
        }
        Iterator<String> keys = root.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            JSONObject item = root.optJSONObject(key);
            if (item == null || !item.has("v")) {
                continue;
            }
            Object raw = item.opt("v");
            switch (item.optString("t", "")) {
                case TYPE_BOOLEAN:
                    values.put(key, item.optBoolean("v"));
                    break;
                case TYPE_INT:
                    values.put(key, (int) asLong(raw, 0L));
                    break;
                case TYPE_LONG:
                    values.put(key, asLong(raw, 0L));
                    break;
                case TYPE_FLOAT:
                    values.put(key, asFloat(raw, 0f));
                    break;
                case TYPE_STRING:
                    values.put(key, raw == null || JSONObject.NULL.equals(raw)
                            ? "" : String.valueOf(raw));
                    break;
                default:
                    break;
            }
        }
        return values;
    }

    public static boolean asBoolean(@Nullable Object value, boolean fallback) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof String) {
            return Boolean.parseBoolean((String) value);
        }
        return fallback;
    }

    public static long asLong(@Nullable Object value, long fallback) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof String) {
            try {
                return Long.parseLong(((String) value).trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    public static float asFloat(@Nullable Object value, float fallback) {
        if (value instanceof Number) {
            return ((Number) value).floatValue();
        }
        if (value instanceof String) {
            try {
                return Float.parseFloat(((String) value).trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    public static String asString(@Nullable Object value, String fallback) {
        if (value == null || JSONObject.NULL.equals(value)) {
            return fallback;
        }
        return String.valueOf(value);
    }
}
