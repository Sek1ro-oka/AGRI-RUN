package org.localroute.app.study;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 课表与作息时间的本机存储。课表是用户手动录入的数据，不依赖学习通接口。
 *
 * <p>作息按冬令时/夏令时各存一套；旧版本的「每节课时长」会在首次读取时
 * 迁移为默认起点的作息，保留用户原来的节长。
 */
public final class StudyTimetableStore {

    /** 课表与作息共用的偏好文件名；小组件靠监听它来感知数据变化。 */
    public static final String PREFS = "study_timetable";
    /** 学期归档要按同样的键读写这几个值，所以对本包可见，不对外公开。 */
    static final String KEY_COURSES = "courses";
    private static final String KEY_SEASON = "season";
    private static final String KEY_SCHEDULE_WINTER = "schedule_winter";
    private static final String KEY_SCHEDULE_SUMMER = "schedule_summer";
    static final String KEY_TOTAL_WEEKS = "total_weeks";
    private static final String KEY_CURRENT_WEEK = "current_week";
    static final String KEY_SEMESTER_START = "semester_start";
    private static final String KEY_CLASS_REMINDER_ENABLED = "class_reminder_enabled";
    private static final String KEY_CLASS_REMINDER_LEAD = "class_reminder_lead_minutes";
    /** 调课、停课、补课：按日期生效的例外清单。 */
    private static final String KEY_ADJUSTMENTS = "day_adjustments";
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

    /**
     * 把当前学期的键值原样抄一份，交给 {@link SemesterStore} 归档。
     *
     * <p>不筛键也不转类型：偏好文件里是什么类型就带出去什么类型，还原时按同样类型写回，
     * 开学日期这类 long 才不会被折成 int 后越界。
     */
    @NonNull
    public static Map<String, Object> snapshotValues(Context context) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, ?> entry : prefs(context).getAll().entrySet()) {
            copy.put(entry.getKey(), entry.getValue());
        }
        return copy;
    }

    /** 整份换成另一个学期的数据：先清后写、一次提交，中途不会读到两套学期拼起来的样子。 */
    public static void restoreValues(@NonNull Context context,
                                     @NonNull Map<String, Object> values) {
        SharedPreferences.Editor editor = prefs(context).edit().clear();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Integer) {
                editor.putInt(entry.getKey(), (Integer) value);
            } else if (value instanceof Long) {
                editor.putLong(entry.getKey(), (Long) value);
            } else if (value instanceof Float) {
                editor.putFloat(entry.getKey(), (Float) value);
            } else if (value instanceof Boolean) {
                editor.putBoolean(entry.getKey(), (Boolean) value);
            } else if (value instanceof String) {
                editor.putString(entry.getKey(), (String) value);
            }
        }
        editor.apply();
    }

    /**
     * 调课与节假日记录（只存今天及以后的，写入时顺手清掉过期的）。
     * 小组件与课前提醒都读它，所以写在同一个偏好文件里，一次改动一起生效。
     */
    @NonNull
    public static List<DayAdjustment> loadAdjustments(Context context) {
        return DayAdjustmentCodec.decode(prefs(context).getString(KEY_ADJUSTMENTS, "[]"));
    }

    public static void saveAdjustments(Context context, List<DayAdjustment> items) {
        prefs(context).edit().putString(KEY_ADJUSTMENTS, DayAdjustmentCodec.encode(items))
                .apply();
    }

    /** 同一天同一门课只留最后一次安排。 */
    public static void putAdjustment(Context context, @NonNull DayAdjustment adjustment) {
        saveAdjustments(context,
                TimetableDay.replacing(loadAdjustments(context), adjustment));
    }

    public static void removeAdjustment(Context context, @NonNull DayAdjustment adjustment) {
        saveAdjustments(context, TimetableDay.removing(loadAdjustments(context), adjustment));
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

    /** 学期第 1 周开始日期；未设置时返回 null，课表不标注「本周」。 */
    @Nullable
    public static LocalDate loadSemesterStart(Context context) {
        long epochDay = prefs(context).getLong(KEY_SEMESTER_START, Long.MIN_VALUE);
        if (epochDay == Long.MIN_VALUE) {
            return null;
        }
        return LocalDate.ofEpochDay(epochDay);
    }

    public static void saveSemesterStart(Context context, @NonNull LocalDate date) {
        prefs(context).edit().putLong(KEY_SEMESTER_START, date.toEpochDay()).apply();
    }

    public static void clearSemesterStart(Context context) {
        prefs(context).edit().remove(KEY_SEMESTER_START).apply();
    }

    /** 今天所在的教学周；未设置开学日期或不在学期内时为 0。 */
    public static int currentTeachingWeek(Context context) {
        return SemesterCalendar.teachingWeek(loadSemesterStart(context), LocalDate.now(),
                loadTotalWeeks(context));
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

    /** 课前提醒开关，默认关闭。 */
    public static boolean loadClassReminderEnabled(Context context) {
        return prefs(context).getBoolean(KEY_CLASS_REMINDER_ENABLED, false);
    }

    public static void saveClassReminderEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_CLASS_REMINDER_ENABLED, enabled).apply();
    }

    /** 课前提醒的提前分钟数，收进合法范围。 */
    public static int loadClassReminderLeadMinutes(Context context) {
        return ClassReminderPlanner.clampLead(prefs(context)
                .getInt(KEY_CLASS_REMINDER_LEAD, ClassReminderPlanner.DEFAULT_LEAD_MINUTES));
    }

    public static void saveClassReminderLeadMinutes(Context context, int minutes) {
        prefs(context).edit()
                .putInt(KEY_CLASS_REMINDER_LEAD, ClassReminderPlanner.clampLead(minutes))
                .apply();
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
