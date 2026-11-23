package org.localroute.app.run;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;

/**
 * 跑圈历史的 JSON 编解码。坏条目跳过，整体损坏返回空列表——历史记录不该挡住任何功能。
 */
public final class RunHistoryCodec {

    private RunHistoryCodec() {
    }

    @NonNull
    public static String encode(@NonNull List<RunRecord> records) {
        JSONArray array = new JSONArray();
        for (RunRecord record : records) {
            try {
                array.put(record.toJson());
            } catch (JSONException ignored) {
                // 写入的都是字符串与数字，实际不会失败；坏一条不影响其余记录。
            }
        }
        return array.toString();
    }

    @NonNull
    public static List<RunRecord> decode(@Nullable String json) {
        List<RunRecord> records = new ArrayList<>();
        if (json == null || json.isEmpty()) {
            return records;
        }
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                RunRecord record = RunRecord.fromJson(array.optJSONObject(i));
                if (record != null) {
                    records.add(record);
                }
            }
        } catch (JSONException e) {
            return new ArrayList<>();
        }
        return records;
    }
}
