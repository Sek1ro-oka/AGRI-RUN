package org.localroute.app.study;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 课前提醒的计划逻辑：根据课表、作息与提前分钟数，算出未来某个时间窗内的提醒点。
 *
 * <p>纯 Java，不依赖 Android，可在 JVM 单元测试中直接验证。
 * 未来若干天统一使用当前选择的冬季/夏季作息；跨季切换当天可能出现少量偏差，
 * 属于可接受的限制（用户也可手动切换作息）。
 */
public final class ClassReminderPlanner {

    public static final int MIN_LEAD_MINUTES = 1;
    public static final int MAX_LEAD_MINUTES = 120;
    public static final int DEFAULT_LEAD_MINUTES = 10;
    /** 一次向后安排的日历天数；闹钟触发后由接收器滚动续排。 */
    public static final int DEFAULT_HORIZON_DAYS = 7;

    private ClassReminderPlanner() {
    }

    /** 一条课前提醒：提醒时刻与对应课程。 */
    public static final class Reminder {

        @NonNull
        public final LocalDateTime remindAt;
        @NonNull
        public final LocalDate day;
        @NonNull
        public final TimetableCourse course;
        /** 上课开始时间（当天从 0 点起的分钟数）。 */
        public final int startMinutesOfDay;

        Reminder(@NonNull LocalDateTime remindAt, @NonNull LocalDate day,
                 @NonNull TimetableCourse course, int startMinutesOfDay) {
            this.remindAt = remindAt;
            this.day = day;
            this.course = course;
            this.startMinutesOfDay = startMinutesOfDay;
        }
    }

    /** 提前分钟数收进合法范围。 */
    public static int clampLead(int minutes) {
        return Math.max(MIN_LEAD_MINUTES, Math.min(MAX_LEAD_MINUTES, minutes));
    }

    /**
     * {@code now} 之后 horizonDays 天内、按周次与调课排掉后的全部提醒点，按时间升序。
     *
     * @param semesterStart 开学第 1 周日期；null 时没有结果（无法确定周次）
     * @param adjustments   调课与节假日：停课的那天不提醒，改到别处的课在新那天提醒
     */
    @NonNull
    public static List<Reminder> upcoming(@NonNull List<TimetableCourse> courses,
                                          @NonNull StudySchedule schedule,
                                          @Nullable LocalDate semesterStart,
                                          int totalWeeks,
                                          @NonNull LocalDateTime now,
                                          int leadMinutes,
                                          int horizonDays,
                                          @NonNull List<DayAdjustment> adjustments) {
        List<Reminder> result = new ArrayList<>();
        if (semesterStart == null || horizonDays < 1) {
            return result;
        }
        int lead = clampLead(leadMinutes);
        LocalDate firstDay = now.toLocalDate()
                .minusDays(1); // 提前量可能把「今天第一节课」的提醒点压到昨天
        LocalDate lastDay = now.toLocalDate().plusDays(horizonDays - 1L);
        for (LocalDate day = firstDay; !day.isAfter(lastDay); day = day.plusDays(1)) {
            for (TimetableCourse course : TimetableDay.classesOn(courses, day, semesterStart,
                    totalWeeks, adjustments)) {
                if (course.startPeriod() > schedule.totalPeriods()) {
                    continue;
                }
                int start = schedule.periodStartMinutes(course.startPeriod());
                LocalDateTime remindAt = day.atStartOfDay()
                        .plusMinutes(start)
                        .minusMinutes(lead);
                if (remindAt.isAfter(now)) {
                    result.add(new Reminder(remindAt, day, course, start));
                }
            }
        }
        result.sort(Comparator.comparing(reminder -> reminder.remindAt));
        return result;
    }

    /** 下一个提醒点；没有时返回 null。 */
    @Nullable
    public static Reminder next(@NonNull List<TimetableCourse> courses,
                                @NonNull StudySchedule schedule,
                                @Nullable LocalDate semesterStart,
                                int totalWeeks,
                                @NonNull LocalDateTime now,
                                int leadMinutes,
                                int horizonDays,
                                @NonNull List<DayAdjustment> adjustments) {
        List<Reminder> upcoming = upcoming(courses, schedule, semesterStart, totalWeeks,
                now, leadMinutes, horizonDays, adjustments);
        return upcoming.isEmpty() ? null : upcoming.get(0);
    }

    /** 提醒时刻落在 [from, to) 区间内的提醒点，用于闹钟触发后补发全部到期项。 */
    @NonNull
    public static List<Reminder> dueBetween(@NonNull List<TimetableCourse> courses,
                                            @NonNull StudySchedule schedule,
                                            @Nullable LocalDate semesterStart,
                                            int totalWeeks,
                                            @NonNull LocalDateTime from,
                                            @NonNull LocalDateTime to,
                                            int leadMinutes,
                                            @NonNull List<DayAdjustment> adjustments) {
        List<Reminder> due = new ArrayList<>();
        for (Reminder reminder : upcoming(courses, schedule, semesterStart, totalWeeks,
                from.minusDays(1), leadMinutes, 2, adjustments)) {
            if (!reminder.remindAt.isBefore(from) && reminder.remindAt.isBefore(to)) {
                due.add(reminder);
            }
        }
        return due;
    }
}
