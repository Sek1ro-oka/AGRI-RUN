package org.localroute.app.study;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Calendar;
import java.util.Locale;

/**
 * 一套作息时间：上午、下午、晚课三个上课起点、每段节数，加上每节课时长与课间休息。
 *
 * <p>节次按上午、下午、晚课顺序连续编号：上午从第 1 节开始，下午接在上午之后，
 * 晚课再接在下午之后。每段可单独设置 1-6 节，合计不超过 15 节；同一段内按
 * 「每节时长 + 课间休息」逐节推进。午休与晚餐是相邻两段之间的空档，由下午/晚课
 * 起点自然决定，不单独存储。
 *
 * <p>冬令时与夏令时各存一套，用户可在设置页切换；两套独立生效，互不覆盖。
 */
public final class StudySchedule {

    public static final int SEASON_WINTER = 0;
    public static final int SEASON_SUMMER = 1;

    public static final int SEGMENT_MORNING = 0;
    public static final int SEGMENT_AFTERNOON = 1;
    public static final int SEGMENT_EVENING = 2;

    /** 每段至少 1 节、至多 6 节；三段合计最多 15 节，与课表网格上限一致。 */
    public static final int MIN_PERIODS_PER_SEGMENT = 1;
    public static final int MAX_PERIODS_PER_SEGMENT = 6;
    public static final int MAX_TOTAL_PERIODS = 15;

    public static final int MIN_PERIOD_MINUTES = 30;
    public static final int MAX_PERIOD_MINUTES = 120;
    public static final int MIN_BREAK_MINUTES = 0;
    public static final int MAX_BREAK_MINUTES = 30;

    public static final int DEFAULT_PERIOD_MINUTES = 45;
    public static final int DEFAULT_BREAK_MINUTES = 10;
    private static final int DEFAULT_PERIODS_PER_SEGMENT = 4;

    private static final int MORNING_START = 8 * 60;
    private static final int DAY_MINUTES = 24 * 60;

    private final int morningStart;
    private final int afternoonStart;
    private final int eveningStart;
    private final int periodMinutes;
    private final int breakMinutes;
    private final int morningPeriods;
    private final int afternoonPeriods;
    private final int eveningPeriods;

    public StudySchedule(int morningStart, int afternoonStart, int eveningStart,
                         int periodMinutes, int breakMinutes,
                         int morningPeriods, int afternoonPeriods, int eveningPeriods) {
        this.morningStart = morningStart;
        this.afternoonStart = afternoonStart;
        this.eveningStart = eveningStart;
        this.periodMinutes = periodMinutes;
        this.breakMinutes = breakMinutes;
        this.morningPeriods = morningPeriods;
        this.afternoonPeriods = afternoonPeriods;
        this.eveningPeriods = eveningPeriods;
    }

    /** 冬令时默认：08:00 / 14:00 / 18:30，上午、下午、晚课各 4 节，45 分钟一节。 */
    @NonNull
    public static StudySchedule winterDefault() {
        return new StudySchedule(MORNING_START, 14 * 60, 18 * 60 + 30,
                DEFAULT_PERIOD_MINUTES, DEFAULT_BREAK_MINUTES,
                DEFAULT_PERIODS_PER_SEGMENT, DEFAULT_PERIODS_PER_SEGMENT,
                DEFAULT_PERIODS_PER_SEGMENT);
    }

    /** 夏令时默认：08:00 / 14:30 / 19:00，其余与冬令时一致。 */
    @NonNull
    public static StudySchedule summerDefault() {
        return new StudySchedule(MORNING_START, 14 * 60 + 30, 19 * 60,
                DEFAULT_PERIOD_MINUTES, DEFAULT_BREAK_MINUTES,
                DEFAULT_PERIODS_PER_SEGMENT, DEFAULT_PERIODS_PER_SEGMENT,
                DEFAULT_PERIODS_PER_SEGMENT);
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

    /** 某一段的节数：0 上午、1 下午、2 晚课。 */
    public int periodCount(int segment) {
        switch (segment) {
            case SEGMENT_AFTERNOON:
                return afternoonPeriods;
            case SEGMENT_EVENING:
                return eveningPeriods;
            default:
                return morningPeriods;
        }
    }

    public int totalPeriods() {
        return morningPeriods + afternoonPeriods + eveningPeriods;
    }

    /** 某一段的第一节编号；节次在上午、下午、晚课之间连续编号。 */
    public int firstPeriod(int segment) {
        switch (segment) {
            case SEGMENT_AFTERNOON:
                return 1 + morningPeriods;
            case SEGMENT_EVENING:
                return 1 + morningPeriods + afternoonPeriods;
            default:
                return 1;
        }
    }

    /** 某一段的最后一节编号。 */
    public int lastPeriod(int segment) {
        return firstPeriod(segment) + periodCount(segment) - 1;
    }

    @NonNull
    public StudySchedule withSegmentStart(int segment, int minutes) {
        switch (segment) {
            case SEGMENT_AFTERNOON:
                return new StudySchedule(morningStart, minutes, eveningStart,
                        periodMinutes, breakMinutes, morningPeriods, afternoonPeriods,
                        eveningPeriods);
            case SEGMENT_EVENING:
                return new StudySchedule(morningStart, afternoonStart, minutes,
                        periodMinutes, breakMinutes, morningPeriods, afternoonPeriods,
                        eveningPeriods);
            default:
                return new StudySchedule(minutes, afternoonStart, eveningStart,
                        periodMinutes, breakMinutes, morningPeriods, afternoonPeriods,
                        eveningPeriods);
        }
    }

    @NonNull
    public StudySchedule withPeriodMinutes(int minutes) {
        return new StudySchedule(morningStart, afternoonStart, eveningStart,
                minutes, breakMinutes, morningPeriods, afternoonPeriods, eveningPeriods);
    }

    @NonNull
    public StudySchedule withBreakMinutes(int minutes) {
        return new StudySchedule(morningStart, afternoonStart, eveningStart,
                periodMinutes, minutes, morningPeriods, afternoonPeriods, eveningPeriods);
    }

    /** 修改某一段的节数；后续段的节次编号随之顺延。 */
    @NonNull
    public StudySchedule withPeriodCount(int segment, int count) {
        switch (segment) {
            case SEGMENT_AFTERNOON:
                return new StudySchedule(morningStart, afternoonStart, eveningStart,
                        periodMinutes, breakMinutes, morningPeriods, count, eveningPeriods);
            case SEGMENT_EVENING:
                return new StudySchedule(morningStart, afternoonStart, eveningStart,
                        periodMinutes, breakMinutes, morningPeriods, afternoonPeriods, count);
            default:
                return new StudySchedule(morningStart, afternoonStart, eveningStart,
                        periodMinutes, breakMinutes, count, afternoonPeriods, eveningPeriods);
        }
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

    /** 第 period 节属于哪一段：0 上午、1 下午、2 晚课。 */
    public int segmentOfPeriod(int period) {
        if (period <= lastPeriod(SEGMENT_MORNING)) {
            return SEGMENT_MORNING;
        }
        if (period <= lastPeriod(SEGMENT_AFTERNOON)) {
            return SEGMENT_AFTERNOON;
        }
        return SEGMENT_EVENING;
    }

    /** 第 period 节的开始时间（从 0 点起的分钟数）。 */
    public int periodStartMinutes(int period) {
        int segment = segmentOfPeriod(period);
        int first = firstPeriod(segment);
        int offset = Math.max(0, period - first) * (periodMinutes + breakMinutes);
        return segmentStartMinutes(segment) + offset;
    }

    public int periodEndMinutes(int period) {
        return periodStartMinutes(period) + periodMinutes;
    }

    /** 某一段最后一节的结束时间。 */
    public int segmentEndMinutes(int segment) {
        return periodEndMinutes(lastPeriod(segment));
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
     * 节数与时间点是否自洽：每段 1-6 节、合计不超过 15 节；上午/下午各自不能压到
     * 下一段，晚课不能超过当天 24:00。
     */
    public boolean isValid() {
        if (morningPeriods < MIN_PERIODS_PER_SEGMENT
                || morningPeriods > MAX_PERIODS_PER_SEGMENT
                || afternoonPeriods < MIN_PERIODS_PER_SEGMENT
                || afternoonPeriods > MAX_PERIODS_PER_SEGMENT
                || eveningPeriods < MIN_PERIODS_PER_SEGMENT
                || eveningPeriods > MAX_PERIODS_PER_SEGMENT) {
            return false;
        }
        if (totalPeriods() > MAX_TOTAL_PERIODS) {
            return false;
        }
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
            json.put("morningCount", morningPeriods);
            json.put("afternoonCount", afternoonPeriods);
            json.put("eveningCount", eveningPeriods);
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
            StudySchedule defaults = winterDefault();
            StudySchedule schedule = new StudySchedule(
                    json.optInt("morning", defaults.morningStart),
                    json.optInt("afternoon", defaults.afternoonStart),
                    json.optInt("evening", defaults.eveningStart),
                    json.optInt("period", defaults.periodMinutes),
                    json.optInt("break", defaults.breakMinutes),
                    json.optInt("morningCount", defaults.morningPeriods),
                    json.optInt("afternoonCount", defaults.afternoonPeriods),
                    json.optInt("eveningCount", defaults.eveningPeriods));
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
