package org.localroute.app.study;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import java.util.List;

/**
 * 自建作业的本机存储与「作业截止提醒」总开关。
 *
 * <p>与学习通作业缓存分开存放：抓取刷新会覆盖缓存，不能把用户手录的作业放在里面。
 */
public final class CustomHomeworkStore {

    private static final String PREFS = "custom_homework";
    private static final String KEY_ITEMS = "items";
    private static final String KEY_REMINDER_ENABLED = "homework_reminder_enabled";

    private CustomHomeworkStore() {
    }

    @NonNull
    public static List<CustomHomework> load(Context context) {
        return CustomHomeworkCodec.decode(prefs(context).getString(KEY_ITEMS, "[]"));
    }

    public static void save(Context context, @NonNull List<CustomHomework> items) {
        prefs(context).edit().putString(KEY_ITEMS, CustomHomeworkCodec.encode(items)).apply();
    }

    @NonNull
    public static CustomHomework add(Context context, @NonNull CustomHomework homework) {
        List<CustomHomework> items = load(context);
        items.add(homework);
        save(context, items);
        return homework;
    }

    /** 按 id 替换；id 不存在时只追加，调用方不需要先读列表。 */
    public static void upsert(Context context, @NonNull CustomHomework homework) {
        List<CustomHomework> items = load(context);
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).id().equals(homework.id())) {
                items.set(i, homework);
                save(context, items);
                return;
            }
        }
        items.add(homework);
        save(context, items);
    }

    public static void remove(Context context, @NonNull String id) {
        List<CustomHomework> items = load(context);
        if (items.removeIf(item -> item.id().equals(id))) {
            save(context, items);
        }
    }

    public static boolean loadReminderEnabled(Context context) {
        return prefs(context).getBoolean(KEY_REMINDER_ENABLED, false);
    }

    public static void saveReminderEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_REMINDER_ENABLED, enabled).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
