package org.localroute.app.study;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 自建作业的 JSON 编解码。坏条目跳过，整体损坏时返回空列表，不影响学习通作业缓存。
 */
public final class CustomHomeworkCodec {

    private CustomHomeworkCodec() {
    }

    @NonNull
    public static String encode(@NonNull List<CustomHomework> items) {
        JSONArray array = new JSONArray();
        for (CustomHomework item : items) {
            try {
                array.put(item.toJson());
            } catch (JSONException ignored) {
                // JSONObject 写入字符串与整数不会失败；保留容错，不中断整个列表。
            }
        }
        return array.toString();
    }

    @NonNull
    public static List<CustomHomework> decode(@Nullable String json) {
        List<CustomHomework> items = new ArrayList<>();
        if (json == null || json.isEmpty()) {
            return items;
        }
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                CustomHomework item = CustomHomework.fromJson(array.optJSONObject(i));
                if (item != null) {
                    items.add(item);
                }
            }
        } catch (JSONException ignored) {
            // 存储损坏时当作空列表，用户可重新添加。
        }
        return items;
    }
}
