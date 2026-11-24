package org.localroute.app.study;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

/**
 * 用户自建的一条作业：只有名称和截止时间，不来自学习通。
 *
 * <p>提前提醒的分钟数是全局设置（和课前提醒一样），不存在每条作业一份上，
 * 这样改一次设置就能影响全部作业。
 *
 * <p>纯 Java + org.json，可在 JVM 单元测试里直接验证编码与提醒时刻推算。
 */
public final class CustomHomework {

    private final String id;
    private final String title;
    private final long deadlineEpochMillis;

    public CustomHomework(@NonNull String id, @NonNull String title,
                          long deadlineEpochMillis) {
        this.id = id;
        this.title = title;
        this.deadlineEpochMillis = deadlineEpochMillis;
    }

    public static CustomHomework create(@NonNull String title, long deadlineEpochMillis) {
        return new CustomHomework(UUID.randomUUID().toString(), title, deadlineEpochMillis);
    }

    @NonNull
    public String id() {
        return id;
    }

    @NonNull
    public String title() {
        return title;
    }

    public long deadlineEpochMillis() {
        return deadlineEpochMillis;
    }

    /** 提醒时刻：截止时间减去全局提前量；没有截止时间时没有提醒点。 */
    public long remindEpochMillis(int leadMinutes) {
        if (deadlineEpochMillis <= 0) {
            return 0L;
        }
        return deadlineEpochMillis - HomeworkReminderPlanner.clampLead(leadMinutes) * 60_000L;
    }

    @NonNull
    public String deadlineLabel() {
        if (deadlineEpochMillis <= 0) {
            return "未设置截止时间";
        }
        return formatDeadline() + " 截止";
    }

    /** 只有时刻部分的「MM-dd HH:mm」，通知正文用它。 */
    @NonNull
    public String formatDeadline() {
        return formatDateTime(deadlineEpochMillis);
    }

    /** 带年份的完整时刻，编辑器里选时间用。 */
    @NonNull
    public static String formatDateTime(long epochMillis) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
                .format(new Date(epochMillis));
    }

    /** 某时刻的「MM-dd HH:mm」。 */
    @NonNull
    public static String formatShortDateTime(long epochMillis) {
        return new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
                .format(new Date(epochMillis));
    }

    @NonNull
    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("id", id);
        json.put("title", title);
        json.put("deadline", deadlineEpochMillis);
        return json;
    }

    @Nullable
    public static CustomHomework fromJson(@Nullable JSONObject json) {
        if (json == null) {
            return null;
        }
        String title = json.optString("title", "").trim();
        long deadline = json.optLong("deadline", 0L);
        if (title.isEmpty() || deadline <= 0) {
            return null;
        }
        String id = json.optString("id", "");
        return new CustomHomework(id.isEmpty() ? UUID.randomUUID().toString() : id,
                title, deadline);
    }

    @NonNull
    public CustomHomework withTitle(@NonNull String title) {
        return new CustomHomework(id, title, deadlineEpochMillis);
    }

    @NonNull
    public CustomHomework withDeadline(long deadlineEpochMillis) {
        return new CustomHomework(id, title, deadlineEpochMillis);
    }
}
