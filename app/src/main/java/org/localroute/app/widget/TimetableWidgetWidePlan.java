package org.localroute.app.widget;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.localroute.app.study.DayAdjustment;
import org.localroute.app.study.SemesterCalendar;
import org.localroute.app.study.StudySchedule;
import org.localroute.app.study.TimetableCourse;
import org.localroute.app.study.TimetableDay;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 「双日课表」宽版小组件的纯 Java 数据：今天未结束的课 + 下一个有课日。
 *
 * <p>与 {@link TimetableWidgetPlan} 分开：旧的「今日课表」继续回答「现在在上什么、下一节还有多久」，
 * 本类回答「今天还剩什么、下一个上课日是谁」。今天已结束的课不占行，只给「已上 N 节」；
 * 右栏跳过无课日、周末和放假日，避免出现空栏。
 *
 * <p>课程仍由 {@link TimetableDay} 统一裁出，作息与调课复用同一套；纯 Java，可在 JVM 逐条断言。
 */
public final class TimetableWidgetWidePlan {

    /** 右栏向后找下一个有课日的范围。 */
    public static final int HORIZON_DAYS = 7;
    public static final int MIN_ROWS = 1;
    public static final int MAX_ROWS = 2;
    /** 日期头、状态行、底部说明与内边距大约占掉的 dp。 */
    private static final int CHROME_DP = 58;
    /** 一行时间线（起止时间两行 + 课程两行）约占的 dp。 */
    private static final int ROW_DP = 38;

    /** 时间线上的一节课。 */
    public static final class Slot {
        private final TimetableCourse course;
        private final LocalDate day;
        private final int startMinutes;
        private final int endMinutes;
        private final boolean live;

        private Slot(TimetableCourse course, LocalDate day, int startMinutes, int endMinutes,
                     boolean live) {
            this.course = course;
            this.day = day;
            this.startMinutes = startMinutes;
            this.endMinutes = endMinutes;
            this.live = live;
        }

        @NonNull public TimetableCourse course() {
            return course;
        }

        @NonNull public LocalDate day() {
            return day;
        }

        public int startMinutes() {
            return startMinutes;
        }

        public int endMinutes() {
            return endMinutes;
        }

        /** 今天正在上的那节；右栏的未来课永远为 false。 */
        public boolean live() {
            return live;
        }

        @NonNull public String startLabel() {
            return StudySchedule.formatMinutes(startMinutes);
        }

        @NonNull public String endLabel() {
            return StudySchedule.formatMinutes(endMinutes);
        }

        @NonNull public String timeLabel() {
            return startLabel() + "-" + endLabel();
        }
    }

    /** 下一个有课日的完整课程列表。 */
    public static final class Day {
        private final LocalDate date;
        private final List<Slot> slots;

        private Day(LocalDate date, List<Slot> slots) {
            this.date = date;
            this.slots = Collections.unmodifiableList(slots);
        }

        @NonNull public LocalDate date() {
            return date;
        }

        @NonNull public List<Slot> slots() {
            return slots;
        }

        public int total() {
            return slots.size();
        }
    }

    private final boolean notImported;
    private final LocalDate today;
    private final int teachingWeek;
    private final boolean weekKnown;
    private final boolean todayClosed;
    private final int todayTotal;
    private final int todayFinished;
    private final List<Slot> todayUpcoming;
    private final Day nextDay;

    private TimetableWidgetWidePlan(boolean notImported, LocalDate today, int teachingWeek,
                                    boolean weekKnown, boolean todayClosed, int todayTotal,
                                    int todayFinished, List<Slot> todayUpcoming,
                                    @Nullable Day nextDay) {
        this.notImported = notImported;
        this.today = today;
        this.teachingWeek = teachingWeek;
        this.weekKnown = weekKnown;
        this.todayClosed = todayClosed;
        this.todayTotal = todayTotal;
        this.todayFinished = todayFinished;
        this.todayUpcoming = Collections.unmodifiableList(todayUpcoming);
        this.nextDay = nextDay;
    }

    @NonNull
    public static TimetableWidgetWidePlan of(@NonNull List<TimetableCourse> courses,
                                             @NonNull StudySchedule schedule,
                                             @Nullable LocalDate semesterStart,
                                             int totalWeeks,
                                             @NonNull LocalDate today,
                                             int nowMinutes,
                                             @NonNull List<DayAdjustment> adjustments) {
        int week = SemesterCalendar.teachingWeek(semesterStart, today, totalWeeks);
        boolean weekKnown = semesterStart != null;
        boolean closed = TimetableDay.isClosed(today, adjustments);
        if (courses.isEmpty()) {
            return new TimetableWidgetWidePlan(true, today, week, weekKnown, closed,
                    0, 0, Collections.emptyList(), null);
        }

        List<Slot> allToday = slotsOn(schedule, courses, semesterStart, totalWeeks, today,
                adjustments, nowMinutes);
        List<Slot> upcoming = new ArrayList<>();
        int finished = 0;
        for (Slot slot : allToday) {
            if (slot.endMinutes() <= nowMinutes) {
                finished++;
            } else {
                upcoming.add(slot);
            }
        }
        Day next = nextClassDay(schedule, courses, semesterStart, totalWeeks, today,
                adjustments);
        return new TimetableWidgetWidePlan(false, today, week, weekKnown, closed,
                allToday.size(), finished, upcoming, next);
    }

    public boolean notImported() {
        return notImported;
    }

    @NonNull public LocalDate today() {
        return today;
    }

    public int teachingWeek() {
        return teachingWeek;
    }

    public boolean weekKnown() {
        return weekKnown;
    }

    public boolean todayClosed() {
        return todayClosed;
    }

    /** 今天课程总数，含已结束的；只用来判空和说明。 */
    public int todayTotal() {
        return todayTotal;
    }

    /** 今天已结束的课程数；标题「已上 N 节」用它。 */
    public int todayFinished() {
        return todayFinished;
    }

    /** 今天还没结束的课（含正在上的），按时间顺序。 */
    @NonNull public List<Slot> todayUpcoming() {
        return todayUpcoming;
    }

    /** 下一个有课日；{@link #HORIZON_DAYS} 天内没有则为 null。 */
    @Nullable public Day nextDay() {
        return nextDay;
    }

    @NonNull
    public List<Slot> todayRows(int limit) {
        if (limit <= 0) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(
                todayUpcoming.subList(0, Math.min(limit, todayUpcoming.size())));
    }

    public int todayHidden(int limit) {
        return Math.max(0, todayUpcoming.size() - Math.max(0, limit));
    }

    @NonNull
    public List<Slot> nextRows(@Nullable Day day, int limit) {
        if (day == null || limit <= 0) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(
                day.slots().subList(0, Math.min(limit, day.slots().size())));
    }

    public int nextHidden(@Nullable Day day, int limit) {
        return day == null ? 0 : Math.max(0, day.total() - Math.max(0, limit));
    }

    /**
     * 组件高度换算成每栏最多几行。4×2 约一行、4×3 约两行；上下限收在 1～2。
     */
    public static int maxRows(int heightDp) {
        int rows = (heightDp - CHROME_DP) / ROW_DP;
        return Math.max(MIN_ROWS, Math.min(MAX_ROWS, rows));
    }

    @NonNull
    private static List<Slot> slotsOn(@NonNull StudySchedule schedule,
                                      @NonNull List<TimetableCourse> courses,
                                      @Nullable LocalDate semesterStart, int totalWeeks,
                                      @NonNull LocalDate day,
                                      @NonNull List<DayAdjustment> adjustments,
                                      int nowMinutes) {
        List<Slot> slots = new ArrayList<>();
        for (TimetableCourse course : TimetableDay.classesOn(courses, day, semesterStart,
                totalWeeks, adjustments)) {
            if (course.startPeriod() > schedule.totalPeriods()) {
                continue;
            }
            int start = schedule.periodStartMinutes(course.startPeriod());
            int end = schedule.periodEndMinutes(
                    Math.min(course.endPeriod(), schedule.totalPeriods()));
            end = Math.max(start, end);
            boolean live = nowMinutes >= 0 && start <= nowMinutes && nowMinutes < end;
            slots.add(new Slot(course, day, start, end, live));
        }
        slots.sort(Comparator.comparingInt(Slot::startMinutes)
                .thenComparing(slot -> slot.course().name()));
        return slots;
    }

    @Nullable
    private static Day nextClassDay(@NonNull StudySchedule schedule,
                                    @NonNull List<TimetableCourse> courses,
                                    @Nullable LocalDate semesterStart, int totalWeeks,
                                    @NonNull LocalDate today,
                                    @NonNull List<DayAdjustment> adjustments) {
        for (int offset = 1; offset <= HORIZON_DAYS; offset++) {
            LocalDate day = today.plusDays(offset);
            if (TimetableDay.isClosed(day, adjustments)) {
                continue;
            }
            List<Slot> slots = slotsOn(schedule, courses, semesterStart, totalWeeks, day,
                    adjustments, -1);
            if (!slots.isEmpty()) {
                return new Day(day, slots);
            }
        }
        return null;
    }
}
