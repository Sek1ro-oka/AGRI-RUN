package org.localroute.app.study;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * 某一天的例外：节假日、补课、某门课改时间或干脆不上。
 *
 * <p>课表本身是「每周重复」的，而调课永远落在具体某一天，所以每条记录都以日期为准：
 * {@code date} 是这条例外生效的那一天（对改课来说就是<b>原来要上课</b>的那天），
 * 改去哪一天由 {@code toWeekday} 在同一周内推出来，不必让用户再填一次日期。
 *
 * <p>值对象 + JSON，纯 Java，可在 JVM 测试里逐条断言。
 */
public final class DayAdjustment {

    public enum Kind {
        /** 当天全部停课（节假日、运动会）。 */
        HOLIDAY,
        /** 当天按某个星期几的课表上（放假后调休补课）。 */
        MAKEUP,
        /** 某门课当天不上，其它照旧。 */
        CANCEL,
        /** 某门课改到同一周的另一天、另一头节次。 */
        MOVE
    }

    private final LocalDate date;
    private final Kind kind;
    private final String courseId;
    private final int refWeekday;
    private final int toWeekday;
    private final int toStart;
    private final int toEnd;

    private DayAdjustment(LocalDate date, Kind kind, String courseId, int refWeekday,
                          int toWeekday, int toStart, int toEnd) {
        this.date = date;
        this.kind = kind;
        this.courseId = courseId == null ? "" : courseId;
        this.refWeekday = refWeekday;
        this.toWeekday = toWeekday;
        this.toStart = toStart;
        this.toEnd = toEnd;
    }

    @NonNull
    public static DayAdjustment holiday(@NonNull LocalDate date) {
        return new DayAdjustment(date, Kind.HOLIDAY, "", 0, 0, 0, 0);
    }

    /** {@code refWeekday} 是「按星期几的课表上」：1=周一 … 7=周日。 */
    @NonNull
    public static DayAdjustment makeup(@NonNull LocalDate date, int refWeekday) {
        return new DayAdjustment(date, Kind.MAKEUP, "", refWeekday, 0, 0, 0);
    }

    @NonNull
    public static DayAdjustment cancel(@NonNull LocalDate date, @NonNull String courseId) {
        return new DayAdjustment(date, Kind.CANCEL, courseId, 0, 0, 0, 0);
    }

    /** 改到同一周 {@code toWeekday} 的 {@code toStart-toEnd} 节。 */
    @NonNull
    public static DayAdjustment move(@NonNull LocalDate date, @NonNull String courseId,
                                     int toWeekday, int toStart, int toEnd) {
        return new DayAdjustment(date, Kind.MOVE, courseId, 0, toWeekday, toStart,
                Math.max(toStart, toEnd));
    }

    public LocalDate date() {
        return date;
    }

    @NonNull
    public Kind kind() {
        return kind;
    }

    @NonNull
    public String courseId() {
        return courseId;
    }

    public int refWeekday() {
        return refWeekday;
    }

    public int toWeekday() {
        return toWeekday;
    }

    public int toStart() {
        return toStart;
    }

    public int toEnd() {
        return toEnd;
    }

    /**
     * 改课之后真正要上的那天：与原日期同一个周一，只把星期几换掉。
     * 非改课记录返回 {@code null}。
     */
    @Nullable
    public LocalDate targetDate() {
        if (kind != Kind.MOVE) {
            return null;
        }
        return SemesterCalendar.mondayOf(date).plusDays(Math.min(7, Math.max(1, toWeekday)) - 1L);
    }

    /**
     * 同一件事的新记录覆盖旧记录：同一天同一门课只留最后一次安排，
     * 免得「先改课再取消」留下两条互相打架的例外。
     */
    @NonNull
    public String slot() {
        return date + "|" + (courseId.isEmpty() ? kind.name() : courseId);
    }

    public boolean isValid() {
        switch (kind) {
            case MAKEUP:
                return refWeekday >= 1 && refWeekday <= 7;
            case CANCEL:
                return !courseId.isEmpty();
            case MOVE:
                return !courseId.isEmpty() && toWeekday >= 1 && toWeekday <= 7
                        && toStart >= 1 && toEnd >= toStart;
            default:
                return true;
        }
    }

    @NonNull
    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("date", date.toString());
        json.put("kind", kind.name());
        json.put("courseId", courseId);
        json.put("refWeekday", refWeekday);
        json.put("weekday", toWeekday);
        json.put("start", toStart);
        json.put("end", toEnd);
        return json;
    }

    /** 日期认不出来、或缺必填字段的条目一律返回 {@code null}，由调用方跳过。 */
    @Nullable
    public static DayAdjustment fromJson(@Nullable JSONObject json) {
        if (json == null) {
            return null;
        }
        Kind kind;
        try {
            kind = Kind.valueOf(json.optString("kind", ""));
        } catch (IllegalArgumentException e) {
            return null;
        }
        LocalDate date;
        try {
            date = LocalDate.parse(json.optString("date", ""));
        } catch (DateTimeParseException e) {
            return null;
        }
        DayAdjustment adjustment = new DayAdjustment(date, kind,
                json.optString("courseId", ""),
                json.optInt("refWeekday", 0), json.optInt("weekday", 0),
                json.optInt("start", 0), json.optInt("end", 0));
        return adjustment.isValid() ? adjustment : null;
    }

    @Override
    public boolean equals(@Nullable Object other) {
        if (!(other instanceof DayAdjustment)) {
            return false;
        }
        DayAdjustment that = (DayAdjustment) other;
        return that.date.equals(date) && that.kind == kind && that.courseId.equals(courseId)
                && that.refWeekday == refWeekday && that.toWeekday == toWeekday
                && that.toStart == toStart && that.toEnd == toEnd;
    }

    @Override
    public int hashCode() {
        return (date.toString() + kind + courseId + refWeekday + toWeekday + toStart + toEnd)
                .hashCode();
    }
}
