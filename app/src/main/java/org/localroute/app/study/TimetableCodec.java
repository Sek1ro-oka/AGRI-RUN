package org.localroute.app.study;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 课表 JSON 编解码。坏条目跳过，整体损坏时返回空课表，不影响其它功能。
 */
public final class TimetableCodec {

    private TimetableCodec() {
    }

    @NonNull
    public static String encode(@NonNull List<TimetableCourse> courses) {
        JSONArray array = new JSONArray();
        for (TimetableCourse course : courses) {
            if (!course.isValid()) {
                continue;
            }
            try {
                array.put(course.toJson());
            } catch (JSONException ignored) {
                // JSONObject 写入字符串不会失败；保留容错，不中断整张课表。
            }
        }
        return array.toString();
    }

    @NonNull
    public static List<TimetableCourse> decode(@Nullable String json) {
        List<TimetableCourse> courses = new ArrayList<>();
        if (json == null || json.isEmpty()) {
            return courses;
        }
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                TimetableCourse course = TimetableCourse.fromJson(item);
                if (course != null) {
                    courses.add(course);
                }
            }
        } catch (JSONException ignored) {
            // 存储损坏时当作空课表，用户可重新导入。
        }
        return courses;
    }
}
