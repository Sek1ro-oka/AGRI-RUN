package org.localroute.app.study;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

/**
 * 课表与上课时间的本机存储。课表是用户手动录入的数据，不依赖学习通接口。
 */
public final class StudyTimetableStore {

    private static final String PREFS = "study_timetable";
    private static final String KEY_COURSES = "courses";
    private static final String KEY_PERIOD_MINUTES = "period_minutes";

    public static final int DEFAULT_PERIOD_MINUTES = 45;
    public static final int MIN_PERIOD_MINUTES = 30;
    public static final int MAX_PERIOD_MINUTES = 120;
    /** 第一节固定 08:00 开始；每节课时长可在设置里调整。 */
    public static final int FIRST_CLASS_MINUTES = 8 * 60;

    private StudyTimetableStore() {
    }

    @NonNull
    public static List<TimetableCourse> loadCourses(Context context) {
        return TimetableCodec.decode(prefs(context).getString(KEY_COURSES, "[]"));
    }

    public static void saveCourses(Context context, List<TimetableCourse> courses) {
        prefs(context).edit().putString(KEY_COURSES, TimetableCodec.encode(courses)).apply();
    }

    public static void clearCourses(Context context) {
        prefs(context).edit().remove(KEY_COURSES).apply();
    }

    public static int loadPeriodMinutes(Context context) {
        int value = prefs(context).getInt(KEY_PERIOD_MINUTES, DEFAULT_PERIOD_MINUTES);
        return Math.max(MIN_PERIOD_MINUTES, Math.min(MAX_PERIOD_MINUTES, value));
    }

    public static void savePeriodMinutes(Context context, int minutes) {
        int value = Math.max(MIN_PERIOD_MINUTES, Math.min(MAX_PERIOD_MINUTES, minutes));
        prefs(context).edit().putInt(KEY_PERIOD_MINUTES, value).apply();
    }

    /** 第 period 节课的开始时间，例如第 1 节 08:00、第 3 节 09:30（45 分钟课时）。 */
    @NonNull
    public static String periodStartLabel(int period, int periodMinutes) {
        int minutes = FIRST_CLASS_MINUTES + Math.max(0, period - 1) * periodMinutes;
        return String.format(java.util.Locale.CHINA, "%02d:%02d",
                (minutes / 60) % 24, minutes % 60);
    }

    @NonNull
    public static String periodEndLabel(int period, int periodMinutes) {
        int minutes = FIRST_CLASS_MINUTES + Math.max(0, period) * periodMinutes;
        return String.format(java.util.Locale.CHINA, "%02d:%02d",
                (minutes / 60) % 24, minutes % 60);
    }

    /** 新导入的课程按星期与节次排序，避免列表随输入顺序跳动。 */
    @NonNull
    public static List<TimetableCourse> sorted(@NonNull List<TimetableCourse> courses) {
        List<TimetableCourse> copy = new ArrayList<>(courses);
        copy.sort((left, right) -> {
            int day = Integer.compare(left.weekday(), right.weekday());
            if (day != 0) {
                return day;
            }
            int start = Integer.compare(left.startPeriod(), right.startPeriod());
            if (start != 0) {
                return start;
            }
            return left.name().compareTo(right.name());
        });
        return copy;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
