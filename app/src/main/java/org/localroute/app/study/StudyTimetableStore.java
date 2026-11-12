package org.localroute.app.study;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

/**
 * 课表与作息时间的本机存储。课表是用户手动录入的数据，不依赖学习通接口。
 *
 * <p>作息按冬令时/夏令时各存一套；旧版本的「每节课时长」会在首次读取时
 * 迁移为默认起点的作息，保留用户原来的节长。
 */
public final class StudyTimetableStore {

    private static final String PREFS = "study_timetable";
    private static final String KEY_COURSES = "courses";
    private static final String KEY_SEASON = "season";
    private static final String KEY_SCHEDULE_WINTER = "schedule_winter";
    private static final String KEY_SCHEDULE_SUMMER = "schedule_summer";
    private static final String KEY_TOTAL_WEEKS = "total_weeks";
    private static final String KEY_CURRENT_WEEK = "current_week";
    /** 旧版本只保存了每节课分钟数，读取时迁移后删除。 */
    private static final String KEY_LEGACY_PERIOD_MINUTES = "period_minutes";

    /** 默认按 20 个教学周展示；导入课程超出时自动扩展，可手动调整。 */
    public static final int DEFAULT_TOTAL_WEEKS = 20;
    public static final int MIN_TOTAL_WEEKS = 1;
    public static final int MAX_TOTAL_WEEKS = 30;

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

    /** 学期教学总周数：默认 20，限制在 1–30。 */
    public static int loadTotalWeeks(Context context) {
        int stored = prefs(context).getInt(KEY_TOTAL_WEEKS, DEFAULT_TOTAL_WEEKS);
        return Math.max(MIN_TOTAL_WEEKS, Math.min(MAX_TOTAL_WEEKS, stored));
    }

    public static void saveTotalWeeks(Context context, int weeks) {
        int value = Math.max(MIN_TOTAL_WEEKS, Math.min(MAX_TOTAL_WEEKS, weeks));
        prefs(context).edit().putInt(KEY_TOTAL_WEEKS, value).apply();
    }

    /** 当前查看的教学周；超出总周数时收回到最后一周年。 */
    public static int loadWeek(Context context) {
        int total = loadTotalWeeks(context);
        int stored = prefs(context).getInt(KEY_CURRENT_WEEK, 1);
        return Math.max(1, Math.min(total, stored));
    }

    public static void saveWeek(Context context, int week) {
        int total = loadTotalWeeks(context);
        int value = Math.max(1, Math.min(total, week));
        prefs(context).edit().putInt(KEY_CURRENT_WEEK, value).apply();
    }

    /** 未手动选择时按当前月份推断：5-10 月夏令时，其余冬令时。 */
    public static int loadSeason(Context context) {
        int stored = prefs(context).getInt(KEY_SEASON, -1);
        if (stored == StudySchedule.SEASON_WINTER || stored == StudySchedule.SEASON_SUMMER) {
            return stored;
        }
        return StudySchedule.currentSeason();
    }

    public static void saveSeason(Context context, int season) {
        int value = season == StudySchedule.SEASON_SUMMER
                ? StudySchedule.SEASON_SUMMER : StudySchedule.SEASON_WINTER;
        prefs(context).edit().putInt(KEY_SEASON, value).apply();
    }

    @NonNull
    public static StudySchedule loadSchedule(Context context) {
        return loadSchedule(context, loadSeason(context));
    }

    @NonNull
    public static StudySchedule loadSchedule(Context context, int season) {
        SharedPreferences preferences = prefs(context);
        String raw = preferences.getString(keyForSeason(season), null);
        StudySchedule stored = StudySchedule.decode(raw, season);
        if (stored != null) {
            // 旧版本没有大课间字段，解析时已按新规则迁移；把结果写回避免每次重复迁移。
            if (!stored.encode().equals(raw)) {
                preferences.edit()
                        .putString(keyForSeason(season), stored.encode())
                        .apply();
            }
            return stored;
        }
        int legacyMinutes = preferences.getInt(KEY_LEGACY_PERIOD_MINUTES, -1);
        StudySchedule schedule = migrateLegacy(season, legacyMinutes);
        if (legacyMinutes >= StudySchedule.MIN_PERIOD_MINUTES
                && legacyMinutes <= StudySchedule.MAX_PERIOD_MINUTES) {
            // 迁移结果即使非法（旧节长过大）也要落盘，避免每次读取都重试。
            preferences.edit()
                    .putString(keyForSeason(season), schedule.encode())
                    .remove(KEY_LEGACY_PERIOD_MINUTES)
                    .apply();
        }
        return schedule;
    }

    /**
     * 旧版只存了每节课分钟数：套用该季默认起点，保留原节长；若节长过大导致时段
     * 重叠（如 120 分钟撑爆上午），回退到该季默认作息，至少保证课表可用。
     */
    @NonNull
    static StudySchedule migrateLegacy(int season, int legacyMinutes) {
        StudySchedule defaults = StudySchedule.defaultsForSeason(season);
        if (legacyMinutes < StudySchedule.MIN_PERIOD_MINUTES
                || legacyMinutes > StudySchedule.MAX_PERIOD_MINUTES) {
            return defaults;
        }
        StudySchedule migrated = defaults.withPeriodMinutes(legacyMinutes);
        return migrated.isValid() ? migrated : defaults;
    }

    public static void saveSchedule(Context context, int season, @NonNull StudySchedule schedule) {
        if (!schedule.isValid()) {
            return;
        }
        prefs(context).edit()
                .putString(keyForSeason(season), schedule.encode())
                .remove(KEY_LEGACY_PERIOD_MINUTES)
                .apply();
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

    @NonNull
    private static String keyForSeason(int season) {
        return season == StudySchedule.SEASON_SUMMER
                ? KEY_SCHEDULE_SUMMER : KEY_SCHEDULE_WINTER;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
