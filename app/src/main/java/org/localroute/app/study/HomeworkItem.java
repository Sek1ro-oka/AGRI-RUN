package org.localroute.app.study;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 一条作业。截止时间优先使用页面上的绝对时间，只有“剩余 X 小时”时按抓取时刻折算。
 */
public final class HomeworkItem {

    /** 逐课程列表用 workId，聚合作业页用 taskrefId，两者都指向同一条作业。 */
    private static final Pattern WORK_ID = Pattern.compile(
            "[?&](?:workId|taskrefId)=(\\d+)", Pattern.CASE_INSENSITIVE);
    /** 已提交但还没出成绩的作业；学习通通常显示「待批阅」「待互评」或「已提交」。 */
    private static final Pattern SUBMITTED_STATUS = Pattern.compile(
            "待批阅|待批閱|待批改|待评阅|待評閱|待评分|待評分|待互评|待互評|已提交|已交卷|已交");
    private static final Pattern PENDING_STATUS = Pattern.compile(
            "未交|未提交|待做");
    private static final Pattern DONE_STATUS = Pattern.compile(
            "已完成|已批改|已批阅|已批閱|已评分|已評分|已互评|已互評");

    public enum Status {
        PENDING,
        SUBMITTED,
        DONE,
        EXPIRED,
        OTHER
    }

    private final String courseName;
    private final String title;
    private final String statusText;
    private final Status status;
    private final long deadlineEpochMillis;
    private final String deadlineRaw;
    private final String url;
    private final boolean peerReview;

    public HomeworkItem(String courseName, String title, String statusText, Status status,
                        long deadlineEpochMillis, String deadlineRaw, String url,
                        boolean peerReview) {
        this.courseName = courseName;
        this.title = title;
        this.statusText = statusText;
        this.status = status;
        this.deadlineEpochMillis = deadlineEpochMillis;
        this.deadlineRaw = deadlineRaw;
        this.url = url;
        this.peerReview = peerReview;
    }

    public String courseName() {
        return courseName;
    }

    public String title() {
        return title;
    }

    public String statusText() {
        return statusText;
    }

    public Status status() {
        return status;
    }

    public long deadlineEpochMillis() {
        return deadlineEpochMillis;
    }

    public String deadlineRaw() {
        return deadlineRaw;
    }

    public String url() {
        return url;
    }

    public boolean peerReview() {
        return peerReview;
    }

    /** 同课程下优先用 workId 识别同一条作业，缺少链接时退回课程名 + 标题。 */
    @NonNull
    public String key() {
        Matcher matcher = WORK_ID.matcher(url);
        if (matcher.find()) {
            return courseName + '|' + matcher.group(1);
        }
        return courseName + '|' + title;
    }

    /** 用缓存里的截止时间补全本次列表丢失的时间字段。 */
    @NonNull
    public HomeworkItem withDeadline(long deadlineEpochMillis, @NonNull String deadlineRaw) {
        return new HomeworkItem(courseName, title, statusText, status,
                deadlineEpochMillis, deadlineRaw, url, peerReview);
    }

    /** 聚合页把课程名截断成「《计算机组成原...》」时，换成课程列表里的完整名称。 */
    @NonNull
    public HomeworkItem withCourseName(@NonNull String courseName) {
        return new HomeworkItem(courseName, title, statusText, status,
                deadlineEpochMillis, deadlineRaw, url, peerReview);
    }

    public int statusRank() {
        switch (status) {
            case PENDING:
                return 0;
            case SUBMITTED:
                return 1;
            case DONE:
                return 2;
            case EXPIRED:
                return 3;
            default:
                return 4;
        }
    }

    @NonNull
    public String deadlineLabel() {
        if (deadlineEpochMillis > 0) {
            SimpleDateFormat format = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA);
            return format.format(new Date(deadlineEpochMillis)) + " 截止";
        }
        if (status == Status.SUBMITTED) {
            return statusText.isEmpty() ? "已提交" : statusText;
        }
        if (status == Status.EXPIRED) {
            return "已截止";
        }
        if (status == Status.DONE) {
            return "已完成";
        }
        return "未标注截止时间";
    }

    /** 按学习通列表上的状态文字归类；「待批阅」属于已提交，不能再当成待完成。 */
    @NonNull
    public static Status statusOf(@NonNull String text) {
        if (SUBMITTED_STATUS.matcher(text).find()) {
            return Status.SUBMITTED;
        }
        if (DONE_STATUS.matcher(text).find()) {
            return Status.DONE;
        }
        if (PENDING_STATUS.matcher(text).find()) {
            return Status.PENDING;
        }
        return Status.OTHER;
    }

    @NonNull
    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("course", courseName);
        json.put("title", title);
        json.put("status_text", statusText);
        json.put("status", status.name());
        json.put("deadline", deadlineEpochMillis);
        json.put("deadline_raw", deadlineRaw);
        json.put("url", url);
        json.put("peer", peerReview);
        return json;
    }

    @Nullable
    public static HomeworkItem fromJson(@Nullable JSONObject json) {
        if (json == null) {
            return null;
        }
        String title = json.optString("title", "");
        if (title.isEmpty()) {
            return null;
        }
        Status status;
        try {
            status = Status.valueOf(json.optString("status", Status.OTHER.name()));
        } catch (IllegalArgumentException error) {
            status = Status.OTHER;
        }
        String statusText = json.optString("status_text", "");
        if (status == Status.PENDING && statusOf(statusText) == Status.SUBMITTED) {
            // 旧缓存把「待批阅」存成了 PENDING，读取时纠正为已提交。
            status = Status.SUBMITTED;
        }
        return new HomeworkItem(
                json.optString("course", ""),
                title,
                statusText,
                status,
                json.optLong("deadline", 0L),
                json.optString("deadline_raw", ""),
                json.optString("url", ""),
                json.optBoolean("peer", false));
    }
}
