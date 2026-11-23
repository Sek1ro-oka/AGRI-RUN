package org.localroute.app.run;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 跑圈历史的汇总口径，纯 Java、不碰 Android API，因此可直接单测。
 *
 * <p>「一天」按东八区切（{@link #CN_OFFSET_MILLIS}）：校园跑是国内校内场景，用设备时区会让
 * 出差、跨时区或系统时区被改过之后，同一次跑落到不同的「今天」里，历史看起来像凭空少了一截。
 * 一周从周一开始，与课表、教务的周次口径一致。
 */
public final class RunStats {

    public static final long DAY_MILLIS = 24L * 60 * 60 * 1000;
    public static final long CN_OFFSET_MILLIS = 8L * 60 * 60 * 1000;
    /** 没有可用里程或有效用时时给出的配速哨兵值。 */
    public static final int NO_PACE = -1;

    private final int runCount;
    private final double totalMeters;
    private final long totalActiveMillis;
    private final double todayMeters;
    private final double weekMeters;
    private final double bestMeters;
    private final int streakDays;
    private final int averagePaceSecondsPerKm;
    private final long lastRunAtMillis;

    private RunStats(int runCount, double totalMeters, long totalActiveMillis, double todayMeters,
                     double weekMeters, double bestMeters, int streakDays,
                     int averagePaceSecondsPerKm, long lastRunAtMillis) {
        this.runCount = runCount;
        this.totalMeters = totalMeters;
        this.totalActiveMillis = totalActiveMillis;
        this.todayMeters = todayMeters;
        this.weekMeters = weekMeters;
        this.bestMeters = bestMeters;
        this.streakDays = streakDays;
        this.averagePaceSecondsPerKm = averagePaceSecondsPerKm;
        this.lastRunAtMillis = lastRunAtMillis;
    }

    public static RunStats of(List<RunRecord> records, long nowMillis) {
        int count = 0;
        double total = 0d;
        long active = 0L;
        double today = 0d;
        double week = 0d;
        double best = 0d;
        long last = 0L;
        long paceDistanceMeters = 0L;
        long paceActiveMillis = 0L;
        Set<Long> days = new HashSet<>();
        long todayIndex = dayIndex(nowMillis);
        long weekIndex = weekIndex(nowMillis);

        List<RunRecord> safe = records == null ? new ArrayList<RunRecord>() : records;
        for (RunRecord record : safe) {
            if (record == null) {
                continue;
            }
            count++;
            total += record.distanceMeters();
            active += record.activeMillis();
            days.add(dayIndex(record.endedAtMillis()));
            best = Math.max(best, record.distanceMeters());
            last = Math.max(last, record.endedAtMillis());
            if (dayIndex(record.endedAtMillis()) == todayIndex) {
                today += record.distanceMeters();
            }
            if (weekIndex(record.endedAtMillis()) == weekIndex) {
                week += record.distanceMeters();
            }
            if (record.distanceMeters() >= 1d && record.activeMillis() >= 1000L) {
                paceDistanceMeters += (long) record.distanceMeters();
                paceActiveMillis += record.activeMillis();
            }
        }

        int averagePace = paceDistanceMeters <= 0L || paceActiveMillis <= 0L ? NO_PACE
                : Math.round((float) (paceActiveMillis / 1000d) / (float) (paceDistanceMeters / 1000d));
        return new RunStats(count, total, active, today, week, best,
                streak(days, todayIndex), averagePace, last);
    }

    /** 东八区的一天序号，1970-01-01 为 0。 */
    public static long dayIndex(long epochMillis) {
        return Math.floorDiv(epochMillis + CN_OFFSET_MILLIS, DAY_MILLIS);
    }

    /** 周一为一周的第一天。 */
    public static long weekIndex(long epochMillis) {
        // 1970-01-01 是周四，先挪到周一再整除，避免每周七天里有六天被算到上一周。
        return Math.floorDiv(dayIndex(epochMillis) + 4L, 7L);
    }

    /**
     * 从今天往前数连续有记录的天数；今天还没跑时从昨天数，不把「还没开始」当成断签。
     */
    private static int streak(Set<Long> days, long todayIndex) {
        long cursor = days.contains(todayIndex) ? todayIndex : todayIndex - 1;
        int streak = 0;
        while (days.contains(cursor)) {
            streak++;
            cursor--;
        }
        return streak;
    }

    public int runCount() {
        return runCount;
    }

    public double totalMeters() {
        return totalMeters;
    }

    public long totalActiveMillis() {
        return totalActiveMillis;
    }

    public double todayMeters() {
        return todayMeters;
    }

    public double weekMeters() {
        return weekMeters;
    }

    public double bestMeters() {
        return bestMeters;
    }

    public int streakDays() {
        return streakDays;
    }

    /** 按里程加权的平均配速（秒/公里），无可用数据返回 {@link #NO_PACE}。 */
    public int averagePaceSecondsPerKm() {
        return averagePaceSecondsPerKm;
    }

    public long lastRunAtMillis() {
        return lastRunAtMillis;
    }

    public boolean isEmpty() {
        return runCount == 0;
    }
}
