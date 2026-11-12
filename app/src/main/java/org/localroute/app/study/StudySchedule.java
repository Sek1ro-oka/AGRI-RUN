package org.localroute.app.study;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Calendar;
import java.util.Locale;

/**
 * 一套作息时间：上午、下午、晚课三个上课起点，加上每节课时长与课间休息。
 *
 * <p>第 1-4 节从上午起点开始，第 5-8 节从下午起点开始，第 9-12 节从晚课起点开始；
 * 同一段内按「每节时长 + 课间休息」逐节推进。午休与晚餐是相邻两段之间的空档，
 * 由下午/晚课起点自然决定，不单独存储。
 *
 * <p>冬令时与夏令时各存一套，用户可在设置页切换；两套独立生效，互不覆盖。
 */
public final class StudySchedule {

    public static final int SEASON_WINTER = 0;
    public static final int SEASON_SUMMER = 1;

    public static final int SEGMENT_MORNING = 0;
    public static final int SEGMENT_AFTERNOON = 1;
    public static final int SEGMENT_EVENING = 2;

    public static final int MORNING_FIRST_PERIOD = 1;
    public static final int AFTERNOON_FIRST_PERIOD = 5;
    public static final int EVENING_FIRST_PERIOD = 9;
    /** 每个时间段固定 4 节；与课表网格最多 12 节保持一致。 */
    public static final int SEGMENT_PERIODS = 4;
    public static final int MAX_PERIOD = EVENING_FIRST_PERIOD + SEGMENT_PERIODS - 1;

    public static final int MIN_PERIOD_MINUTES = 30;
    public static final int MAX_PERIOD_MINUTES = 120;
    public static final int MIN_BREAK_MINUTES = 0;
    public static final int MAX_BREAK_MINUTES = 30;

    public static final int DEFAULT_PERIOD_MINUTES = 45;
    public static final int DEFAULT_BREAK_MINUTES = 10;

    private static final int MORNING_START = 8 * 60;
    private static final int DAY_MINUTES = 24 * 60;

    private final int morningStart;
    private final int afternoonStart;
    private final int eveningStart;
    private final int periodMinutes;
    private final int breakMinutes;

    public StudySchedule(int morningStart, int afternoonStart, int eveningStart,
                         int periodMinutes, int breakMinutes) {
        this.morningStart = morningStart;
        this.afternoonStart = afternoonStart;
        this.eveningStart = eveningStart;
        this.periodMinutes = periodMinutes;
        this.breakMinutes = breakMinutes;
    }

    /** 冬令时默认：08:00 / 14:00 / 18:30，45 分钟一节，课间 10 分钟。 */
    @NonNull
    public static StudySchedule winterDefault() {
        return new StudySchedule(MORNING_START, 14 * 60, 18 * 60 + 30,
                DEFAULT_PERIOD_MINUTES, DEFAULT_BREAK_MINUTES);
    }

    /** 夏令时默认：08:00 / 14:30 / 19:00，其余与冬令时一致。 */
    @NonNull
    public static StudySchedule summerDefault() {
        return new StudySchedule(MORNING_START, 14 * 60 + 30, 19 * 60,
                DEFAULT_PERIOD_MINUTES, DEFAULT_BREAK_MINUTES);
    }

    @NonNull
    public static StudySchedule defaultsForSeason(int season) {
        return season == SEASON_SUMMER ? summerDefault() : winterDefault();
    }

    /** 5-10 月按夏令时展示，其余月份按冬令时；只作为未手动选择时的初始值。 */
    public static int seasonForMonth(int month) {
        return month >= 5 && month <= 10 ? SEASON_SUMMER : SEASON_WINTER;
    }

    public static int currentSeason() {
        return seasonForMonth(Calendar.getInstance().get(Calendar.MONTH) + 1);
    }

    public int morningStart() {
        return morningStart;
    }

    public int afternoonStart() {
        return afternoonStart;
    }

    public int eveningStart() {
        return eveningStart;
    }

    public int periodMinutes() {
        return periodMinutes;
    }

    public int breakMinutes() {
        return breakMinutes;
    }

    @NonNull
    public StudySchedule withSegmentStart(int segment, int minutes) {
        switch (segment) {
            case SEGMENT_AFTERNOON:
                return new StudySchedule(morningStart, minutes, eveningStart,
                        periodMinutes, breakMinutes);
            case SEGMENT_EVENING:
                return new StudySchedule(morningStart, afternoonStart, minutes,
                        periodMinutes, breakMinutes);
            default:
                return new StudySchedule(minutes, afternoonStart, eveningStart,
                        periodMinutes, breakMinutes);
        }
    }

    @NonNull
    public StudySchedule withPeriodMinutes(int minutes) {
        return new StudySchedule(morningStart, afternoonStart, eveningStart,
                minutes, breakMinutes);
    }

    @NonNull
    public StudySchedule withBreakMinutes(int minutes) {
        return new StudySchedule(morningStart, afternoonStart, eveningStart,
                periodMinutes, minutes);
    }

    public int segmentStartMinutes(int segment) {
        switch (segment) {
            case SEGMENT_AFTERNOON:
                return afternoonStart;
            case SEGMENT_EVENING:
                return eveningStart;
            default:
                return morningStart;
        }
    }

    public int segmentFirstPeriod(int period) {
        if (period <= MORNING_FIRST_PERIOD + SEGMENT_PERIODS - 1) {
            return MORNING_FIRST_PERIOD;
        }
        if (period <= AFTERNOON_FIRST_PERIOD + SEGMENT_PERIODS - 1) {
            return AFTERNOON_FIRST_PERIOD;
        }
        return EVENING_FIRST_PERIOD;
    }

    /** 第 period 节的开始时间（从 0 点起的分钟数）。 */
    public int periodStartMinutes(int period) {
        int first = segmentFirstPeriod(period);
        int offset = Math.max(0, period - first) * (periodMinutes + breakMinutes);
        return segmentStartMinutes(segmentOf(first)) + offset;
    }

    public int periodEndMinutes(int period) {
        return periodStartMinutes(period) + periodMinutes;
    }

    /** 一个时间段最后一节（第 4/8/12 节）的结束时间。 */
    public int segmentEndMinutes(int segment) {
        int first;
        switch (segment) {
            case SEGMENT_AFTERNOON:
                first = AFTERNOON_FIRST_PERIOD;
                break;
            case SEGMENT_EVENING:
                first = EVENING_FIRST_PERIOD;
                break;
            default:
                first = MORNING_FIRST_PERIOD;
                break;
        }
        return periodEndMinutes(first + SEGMENT_PERIODS - 1);
    }

    private static int segmentOf(int firstPeriod) {
        if (firstPeriod >= EVENING_FIRST_PERIOD) {
            return SEGMENT_EVENING;
        }
        return firstPeriod >= AFTERNOON_FIRST_PERIOD ? SEGMENT_AFTERNOON : SEGMENT_MORNING;
    }

    @NonNull
    public String startLabel(int period) {
        return formatMinutes(periodStartMinutes(period));
    }

    @NonNull
    public String endLabel(int period) {
        return formatMinutes(periodEndMinutes(period));
    }

    /**
     * 时间段与时长、时间点是否自洽：上午/下午各自 4 节不能压到下一段，
     * 晚课 4 节不能超过当天 24:00。
     */
    public boolean isValid() {
        if (morningStart < 0 || morningStart >= afternoonStart
                || afternoonStart >= eveningStart || eveningStart >= DAY_MINUTES) {
            return false;
        }
        if (periodMinutes < MIN_PERIOD_MINUTES || periodMinutes > MAX_PERIOD_MINUTES
                || breakMinutes < MIN_BREAK_MINUTES || breakMinutes > MAX_BREAK_MINUTES) {
            return false;
        }
        if (segmentEndMinutes(SEGMENT_MORNING) > afternoonStart) {
            return false;
        }
        if (segmentEndMinutes(SEGMENT_AFTERNOON) > eveningStart) {
            return false;
        }
        return segmentEndMinutes(SEGMENT_EVENING) <= DAY_MINUTES;
    }

    @NonNull
    public String encode() {
        JSONObject json = new JSONObject();
        try {
            json.put("morning", morningStart);
            json.put("afternoon", afternoonStart);
            json.put("evening", eveningStart);
            json.put("period", periodMinutes);
            json.put("break", breakMinutes);
        } catch (JSONException ignored) {
            // 只有放入非法键名时才会抛出，这里的键名固定合法。
        }
        return json.toString();
    }

    /** 解析存储的作息；字段缺失按默认值补齐，非法值返回 null 由调用方回退。 */
    @Nullable
    public static StudySchedule decode(@Nullable String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            JSONObject json = new JSONObject(raw);
            StudySchedule winter = winterDefault();
            StudySchedule schedule = new StudySchedule(
                    json.optInt("morning", winter.morningStart),
                    json.optInt("afternoon", winter.afternoonStart),
                    json.optInt("evening", winter.eveningStart),
                    json.optInt("period", winter.periodMinutes),
                    json.optInt("break", winter.breakMinutes));
            return schedule.isValid() ? schedule : null;
        } catch (JSONException error) {
            return null;
        }
    }

    @NonNull
    public static String formatMinutes(int minutes) {
        int normalized = Math.floorMod(minutes, DAY_MINUTES);
        return String.format(Locale.CHINA, "%02d:%02d", normalized / 60, normalized % 60);
    }

    /** 时间选择器只精确到 5 分钟，避免出现 08:03 这类过于琐碎的作息。 */
    public static int snapToFiveMinutes(int minutes) {
        int clamped = Math.max(0, Math.min(DAY_MINUTES - 5, minutes));
        return Math.min(DAY_MINUTES - 5, Math.round(clamped / 5f) * 5);
    }
}
