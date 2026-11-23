package org.localroute.app.run;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 统计口径：今天／本周按东八区切，配速按里程加权，连续天数不因「今天还没跑」归零。
 * 这三条都会直接影响用户看到的数字，所以日期边界用真实毫秒对齐，而不是靠注释说明。
 */
public class RunStatsTest {

    private static final long DAY = RunStats.DAY_MILLIS;
    private static final long HOUR = 60L * 60L * 1000L;
    /** 任意一个「现在」：2026-11-24 15:30（东八区）。 */
    private static final long NOW = RunStats.dayIndex(1_766_000_000_000L) * DAY
            - RunStats.CN_OFFSET_MILLIS + 15 * HOUR + 30 * 60_000L;

    private static long startOfToday() {
        return RunStats.dayIndex(NOW) * DAY - RunStats.CN_OFFSET_MILLIS;
    }

    /**
     * 本周一 00:00（东八区）。1970-01-01 是周四，周一的日序号才是 7×周序号 − 4；
     * 偏移写错就会把「本周」的起点挪到周日，测试里的两条记录会一起落进本周。
     */
    private static long startOfWeek() {
        return (RunStats.weekIndex(NOW) * 7 - 4) * DAY - RunStats.CN_OFFSET_MILLIS;
    }

    private static RunRecord record(long endedAt, double meters, long activeMs, boolean finished) {
        return new RunRecord("r" + endedAt, "操场", endedAt, activeMs, meters, 5, 5, 400d, finished);
    }

    private static List<RunRecord> of(RunRecord... records) {
        return new ArrayList<>(Arrays.asList(records));
    }

    @Test
    public void noRecordsMeansAllZero() {
        RunStats stats = RunStats.of(Collections.<RunRecord>emptyList(), NOW);
        assertTrue(stats.isEmpty());
        assertEquals(0, stats.runCount());
        assertEquals(0d, stats.totalMeters(), 0.0001);
        assertEquals(0d, stats.todayMeters(), 0.0001);
        assertEquals(0, stats.streakDays());
        assertEquals(RunStats.NO_PACE, stats.averagePaceSecondsPerKm());
    }

    @Test
    public void nullListIsTreatedAsEmpty() {
        assertTrue(RunStats.of(null, NOW).isEmpty());
    }

    @Test
    public void sameDayRecordsAddUpToTodayAndTotal() {
        RunStats stats = RunStats.of(of(
                record(startOfToday() + 8 * HOUR, 2000d, 600_000L, true),
                record(startOfToday() + 12 * HOUR, 1000d, 300_000L, false)), NOW);
        assertEquals(2, stats.runCount());
        assertEquals(3000d, stats.totalMeters(), 0.0001);
        assertEquals(3000d, stats.todayMeters(), 0.0001);
        // 900 秒 / 3 公里 = 300 秒每公里，两次跑合并算，不是两次配速的平均。
        assertEquals(300, stats.averagePaceSecondsPerKm());
        assertEquals(900_000L, stats.totalActiveMillis());
    }

    @Test
    public void paceWeighsByDistanceInsteadOfAveragingPaces() {
        // 1 km 用 300 秒（5'00"）+ 4 km 用 1600 秒（6'40"）：加权后是 6'20"，简单平均会是 5'50"。
        RunStats stats = RunStats.of(of(
                record(startOfToday() + HOUR, 1000d, 300_000L, true),
                record(startOfToday() + 2 * HOUR, 4000d, 1_600_000L, true)), NOW);
        assertEquals(380, stats.averagePaceSecondsPerKm());
    }

    @Test
    public void yesterdayStillCountsInTotalButNotInToday() {
        RunStats stats = RunStats.of(of(
                record(startOfToday() - HOUR, 1500d, 500_000L, true)), NOW);
        assertEquals(0d, stats.todayMeters(), 0.0001);
        assertEquals(1500d, stats.totalMeters(), 0.0001);
        assertEquals(1, stats.streakDays());
    }

    @Test
    public void weekOnlyCountsFromMondayOfCurrentWeek() {
        double inWeek = 1200d;
        double beforeWeek = 5000d;
        RunStats stats = RunStats.of(of(
                record(startOfWeek() + HOUR, inWeek, 400_000L, true),
                record(startOfWeek() - HOUR, beforeWeek, 400_000L, true)), NOW);
        assertEquals(inWeek, stats.weekMeters(), 0.0001);
        assertEquals(inWeek + beforeWeek, stats.totalMeters(), 0.0001);
    }

    @Test
    public void streakCountsConsecutiveDays() {
        RunStats stats = RunStats.of(of(
                record(startOfToday() + HOUR, 1000d, 300_000L, true),
                record(startOfToday() - DAY + HOUR, 1000d, 300_000L, true),
                record(startOfToday() - 2 * DAY + HOUR, 1000d, 300_000L, true)), NOW);
        assertEquals(3, stats.streakDays());
        assertEquals(3000d, stats.totalMeters(), 0.0001);
    }

    @Test
    public void streakDoesNotResetJustBecauseTodayHasNoRunYet() {
        RunStats stats = RunStats.of(of(
                record(startOfToday() - DAY + HOUR, 1000d, 300_000L, true),
                record(startOfToday() - 2 * DAY + HOUR, 1000d, 300_000L, true)), NOW);
        assertEquals(2, stats.streakDays());
        assertEquals(0d, stats.todayMeters(), 0.0001);
    }

    @Test
    public void streakStopsAtFirstGap() {
        RunStats stats = RunStats.of(of(
                record(startOfToday() + HOUR, 1000d, 300_000L, true),
                record(startOfToday() - 2 * DAY + HOUR, 1000d, 300_000L, true)), NOW);
        assertEquals(1, stats.streakDays());
    }

    @Test
    public void twoRunsSameDayCountOnceForStreak() {
        RunStats stats = RunStats.of(of(
                record(startOfToday() + HOUR, 800d, 200_000L, false),
                record(startOfToday() + 3 * HOUR, 900d, 300_000L, false)), NOW);
        assertEquals(1, stats.streakDays());
        assertEquals(2, stats.runCount());
    }

    @Test
    public void dayBoundaryFollowsChinaTimeNotUtc() {
        // 东八区 00:30 在 UTC 里还是前一天 16:30，切错时区就会把这次跑记到昨天。
        long earlyMorning = startOfToday() + 30 * 60_000L;
        assertEquals(RunStats.dayIndex(NOW), RunStats.dayIndex(earlyMorning));
        assertEquals(RunStats.dayIndex(NOW) - 1, RunStats.dayIndex(startOfToday() - 30 * 60_000L));
        RunStats stats = RunStats.of(of(record(earlyMorning, 600d, 100_000L, false)), NOW);
        assertEquals(600d, stats.todayMeters(), 0.0001);
    }

    @Test
    public void bestIsTheLongestSingleRun() {
        RunStats stats = RunStats.of(of(
                record(startOfToday() + HOUR, 1000d, 300_000L, true),
                record(startOfToday() - DAY + HOUR, 3400d, 1_000_000L, true),
                record(startOfToday() - 2 * DAY + HOUR, 2000d, 600_000L, true)), NOW);
        assertEquals(3400d, stats.bestMeters(), 0.0001);
        assertEquals(startOfToday() + HOUR, stats.lastRunAtMillis());
    }

    @Test
    public void recordsWithoutDistanceLeavePaceUnknown() {
        RunStats stats = RunStats.of(of(record(startOfToday() + HOUR, 0d, 600_000L, false)), NOW);
        assertEquals(1, stats.runCount());
        assertEquals(RunStats.NO_PACE, stats.averagePaceSecondsPerKm());
    }
}
