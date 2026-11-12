package org.localroute.app.study;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.UUID;

/**
 * 一门手动导入/添加的课程。周次只做展示，不参与“当前周”过滤。
 */
public final class TimetableCourse {

    public static final int MIN_PERIOD = 1;
    /** 与 StudySchedule.MAX_TOTAL_PERIODS 一致：上午/下午/晚课三段合计最多 15 节。 */
    public static final int MAX_PERIOD = 15;

    private final String id;
    private final String name;
    private final String teacher;
    private final String location;
    private final int weekday;
    private final int startPeriod;
    private final int endPeriod;
    private final String weeks;

    public TimetableCourse(@NonNull String id, @NonNull String name, @Nullable String teacher,
                           @Nullable String location, int weekday, int startPeriod,
                           int endPeriod, @Nullable String weeks) {
        this.id = id;
        this.name = name;
        this.teacher = teacher == null ? "" : teacher;
        this.location = location == null ? "" : location;
        this.weekday = weekday;
        this.startPeriod = startPeriod;
        this.endPeriod = endPeriod;
        this.weeks = weeks == null || weeks.isEmpty() ? "1-16" : weeks;
    }

    public static TimetableCourse create(String name, String teacher, String location,
                                         int weekday, int startPeriod, int endPeriod,
                                         String weeks) {
        return new TimetableCourse(UUID.randomUUID().toString(), name, teacher, location,
                weekday, startPeriod, endPeriod, weeks);
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public String teacher() {
        return teacher;
    }

    public String location() {
        return location;
    }

    public int weekday() {
        return weekday;
    }

    public int startPeriod() {
        return startPeriod;
    }

    public int endPeriod() {
        return endPeriod;
    }

    public String weeks() {
        return weeks;
    }

    public boolean isValid() {
        return !name.trim().isEmpty()
                && weekday >= 1 && weekday <= 7
                && startPeriod >= MIN_PERIOD && endPeriod <= MAX_PERIOD
                && startPeriod <= endPeriod;
    }

    @NonNull
    public String weekdayLabel() {
        String[] labels = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
        return weekday >= 1 && weekday <= 7 ? labels[weekday - 1] : "";
    }

    @NonNull
    public String periodLabel() {
        if (startPeriod == endPeriod) {
            return "第" + startPeriod + "节";
        }
        return "第" + startPeriod + "-" + endPeriod + "节";
    }

    @NonNull
    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("id", id);
        json.put("name", name);
        json.put("teacher", teacher);
        json.put("location", location);
        json.put("weekday", weekday);
        json.put("start", startPeriod);
        json.put("end", endPeriod);
        json.put("weeks", weeks);
        return json;
    }

    @Nullable
    public static TimetableCourse fromJson(@Nullable JSONObject json) {
        if (json == null) {
            return null;
        }
        String name = json.optString("name", "").trim();
        String id = json.optString("id", "");
        if (id.isEmpty()) {
            id = UUID.randomUUID().toString();
        }
        TimetableCourse course = new TimetableCourse(
                id,
                name,
                json.optString("teacher", ""),
                json.optString("location", ""),
                json.optInt("weekday", 0),
                json.optInt("start", 0),
                json.optInt("end", 0),
                json.optString("weeks", "1-16"));
        return course.isValid() ? course : null;
    }
}
