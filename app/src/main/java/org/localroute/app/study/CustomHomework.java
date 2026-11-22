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
 * 用户自建的一条作业：只有名称、截止时间和提前提醒分钟数，不来自学习通。
 *
 * <p>纯 Java + org.json，可在 JVM 单元测试里直接验证编解码与提醒时刻推算。
 */
public final class CustomHomework {

    public static final int MIN_LEAD_MINUTES = 1;
    /** 最多提前 7 天提醒；再早的需求属于日历而不是本功能。 */
    public static final int MAX_LEAD_MINUTES = 7 * 24 * 60;
    public static final int DEFAULT_LEAD_MINUTES = 60;

    private final String id;
    private final String title;
    private final long deadlineEpochMillis;
    private final int leadMinutes;

    public CustomHomework(@NonNull String id, @NonNull String title,
                          long deadlineEpochMillis, int leadMinutes) {
        this.id = id;
        this.title = title;
        this.deadlineEpochMillis = deadlineEpochMillis;
        this.leadMinutes = clampLead(leadMinutes);
    }

    public static CustomHomework create(@NonNull String title, long deadlineEpochMillis,
                                        int leadMinutes) {
        return new CustomHomework(UUID.randomUUID().toString(), title,
                deadlineEpochMillis, leadMinutes);
    }

    /** 提前分钟数收进合法范围。 */
    public static int clampLead(int minutes) {
        return Math.max(MIN_LEAD_MINUTES, Math.min(MAX_LEAD_MINUTES, minutes));
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

    public int leadMinutes() {
        return leadMinutes;
    }

    /** 提醒时刻；截止时间本身已过期时没有提醒点。 */
    public long remindEpochMillis() {
        if (deadlineEpochMillis <= 0) {
            return 0L;
        }
        return deadlineEpochMillis - leadMinutes * 60_000L;
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

    /** 提前量的人话描述，只给时长本身：30 分钟 / 2 小时 / 1 天。 */
    @NonNull
    public String leadLabel() {
        if (leadMinutes >= 60 && leadMinutes % 60 == 0) {
            int hours = leadMinutes / 60;
            return hours >= 24 && hours % 24 == 0 ? (hours / 24) + " 天" : hours + " 小时";
        }
        return leadMinutes + " 分钟";
    }

    @NonNull
    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("id", id);
        json.put("title", title);
        json.put("deadline", deadlineEpochMillis);
        json.put("lead", leadMinutes);
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
                title, deadline, json.optInt("lead", DEFAULT_LEAD_MINUTES));
    }

    @NonNull
    public CustomHomework withTitle(@NonNull String title) {
        return new CustomHomework(id, title, deadlineEpochMillis, leadMinutes);
    }

    @NonNull
    public CustomHomework withDeadline(long deadlineEpochMillis) {
        return new CustomHomework(id, title, deadlineEpochMillis, leadMinutes);
    }

    @NonNull
    public CustomHomework withLeadMinutes(int leadMinutes) {
        return new CustomHomework(id, title, deadlineEpochMillis, leadMinutes);
    }
}
