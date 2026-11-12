package org.localroute.app.study;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 作业列表缓存：换账号后不会串数据，无网时也能看到上次结果。
 */
public final class StudyHomeworkCache {

    private static final String PREFS = "study_homework_cache";
    private static final String KEY_CACHE = "cache";

    public final String uid;
    public final long fetchedAt;
    public final int failedCourses;
    public final List<HomeworkItem> items;

    public StudyHomeworkCache(String uid, long fetchedAt, int failedCourses,
                              List<HomeworkItem> items) {
        this.uid = uid;
        this.fetchedAt = fetchedAt;
        this.failedCourses = failedCourses;
        this.items = items;
    }

    public static void save(Context context, StudyHomeworkCache cache) {
        JSONObject root = new JSONObject();
        try {
            root.put("uid", cache.uid);
            root.put("fetched_at", cache.fetchedAt);
            root.put("failed", cache.failedCourses);
            JSONArray array = new JSONArray();
            for (HomeworkItem item : cache.items) {
                array.put(item.toJson());
            }
            root.put("items", array);
        } catch (JSONException ignored) {
            return;
        }
        prefs(context).edit().putString(KEY_CACHE, root.toString()).apply();
    }

    @Nullable
    public static StudyHomeworkCache load(Context context, String uid) {
        String raw = prefs(context).getString(KEY_CACHE, "");
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            JSONObject root = new JSONObject(raw);
            if (!uid.equals(root.optString("uid", ""))) {
                return null;
            }
            List<HomeworkItem> items = new ArrayList<>();
            JSONArray array = root.optJSONArray("items");
            if (array != null) {
                for (int i = 0; i < array.length(); i++) {
                    HomeworkItem item = HomeworkItem.fromJson(array.optJSONObject(i));
                    if (item != null) {
                        items.add(item);
                    }
                }
            }
            return new StudyHomeworkCache(uid,
                    root.optLong("fetched_at", 0L),
                    root.optInt("failed", 0),
                    items);
        } catch (JSONException error) {
            return null;
        }
    }

    public static void clear(Context context) {
        prefs(context).edit().remove(KEY_CACHE).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
