package org.localroute.app.study;

import androidx.annotation.Nullable;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;

/**
 * 教学周计算：把「学期第 1 周开始日期」和今天的日期换算成当前教学周。
 *
 * <p>周一到周日算同一周；开始日期落在周中时，按所在周的周一作为第 1 周起点。
 * 不在学期范围内（开学前、超过总周数）时返回 0，界面不标注「本周」。
 *
 * <p>纯 Java，不依赖 Android，可在 JVM 单元测试中直接验证。
 */
public final class SemesterCalendar {

    private SemesterCalendar() {
    }

    /**
     * 当前教学周。
     *
     * @param semesterStart 学期第 1 周内任意一天；为 null 表示未设置
     * @param today         今天
     * @param totalWeeks    学期总周数
     * @return 1..totalWeeks；未设置或不在学期内时为 0
     */
    public static int teachingWeek(@Nullable LocalDate semesterStart, @Nullable LocalDate today,
                                   int totalWeeks) {
        if (semesterStart == null || today == null || totalWeeks < 1) {
            return 0;
        }
        LocalDate startMonday = mondayOf(semesterStart);
        LocalDate todayMonday = mondayOf(today);
        long weeks = ChronoUnit.WEEKS.between(startMonday, todayMonday);
        if (weeks < 0) {
            return 0;
        }
        long week = weeks + 1;
        return week <= totalWeeks ? (int) week : 0;
    }

    /** 该日期所在周的周一。 */
    public static LocalDate mondayOf(LocalDate date) {
        return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    /**
     * 翻页后的周次：首尾循环。
     *
     * @param week       当前周
     * @param delta      正数向下一周，负数向上一周
     * @param totalWeeks 总周数
     * @return 1..totalWeeks 之间的周次
     */
    public static int wrapWeek(int week, int delta, int totalWeeks) {
        int total = Math.max(1, totalWeeks);
        int current = Math.max(1, Math.min(total, week));
        int next = (current - 1 + delta) % total;
        if (next < 0) {
            next += total;
        }
        return next + 1;
    }
}
