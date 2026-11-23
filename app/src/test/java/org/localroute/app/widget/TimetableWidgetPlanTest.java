package org.localroute.app.widget;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.localroute.app.study.DayAdjustment;
import org.localroute.app.study.StudySchedule;
import org.localroute.app.study.TimetableCourse;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 桌面小组件「今天有什么课、下一节还有多久」的用例。
 *
 * <p>日期一律按「TODAY 所在周的周一」推算开学日期，用例不依赖今天真的是星期几。
 */
public class TimetableWidgetPlanTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 18);
    /** 这学期第 1 周就是今天所在的这一周。 */
    private static final LocalDate SEMESTER_START = monday(TODAY);

    private static LocalDate monday(LocalDate date) {
        return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    private static int todayWeekday() {
        return TODAY.getDayOfWeek().getValue();
    }

    private static int at(int hour, int minute) {
        return hour * 60 + minute;
    }

    private static TimetableCourse course(String name, String place, int start, int end,
                                          String weeks) {
        return TimetableCourse.create(name, "", place, todayWeekday(), start, end, weeks);
    }

    private static TimetableWidgetPlan plan(List<TimetableCourse> courses, LocalDate semesterStart,
                                           int nowMinutes) {
        return plan(courses, semesterStart, nowMinutes, Collections.emptyList());
    }

    private static TimetableWidgetPlan plan(List<TimetableCourse> courses, LocalDate semesterStart,
                                            int nowMinutes, List<DayAdjustment> adjustments) {
        return TimetableWidgetPlan.of(courses, StudySchedule.winterDefault(), semesterStart,
                20, TODAY, nowMinutes, adjustments);
    }

    @Test
    public void noCoursesMeansNothingImported() {
        TimetableWidgetPlan plan = plan(Collections.emptyList(), SEMESTER_START, at(9, 0));
        assertEquals(TimetableWidgetPlan.Status.NOT_IMPORTED, plan.status());
        assertTrue(plan.todaySlots().isEmpty());
        assertNull(plan.next());
        assertEquals(-1, plan.minutesToNext());
    }

    @Test
    public void todayClassesFollowClockOrderNotInsertOrder() {
        TimetableWidgetPlan plan = plan(Arrays.asList(
                course("下午课", "一教101", 5, 6, "1-16"),
                course("上午课", "三教301", 1, 2, "1-16")), SEMESTER_START, at(7, 0));
        assertEquals(TimetableWidgetPlan.Status.UPCOMING, plan.status());
        List<TimetableWidgetPlan.Slot> rows = plan.rows(4);
        assertEquals(2, rows.size());
        assertEquals("上午课", rows.get(0).course().name());
        assertEquals("08:00-09:35", rows.get(0).timeLabel());
        assertEquals("13:30-15:05", rows.get(1).timeLabel());
    }

    @Test
    public void inClassCountsDownRemainingMinutesAndStillShowsLaterClasses() {
        // 冬令时第 3 节 09:50-10:35，第 5 节 13:30 起。
        TimetableWidgetPlan plan = plan(Arrays.asList(
                course("第一节", "", 1, 2, "1-16"),
                course("第三节", "二教202", 3, 3, "1-16"),
                course("第五节", "体育馆", 5, 5, "1-16")), SEMESTER_START, at(10, 10));
        assertEquals(TimetableWidgetPlan.Status.IN_CLASS, plan.status());
        assertEquals(1, plan.currentIndex());
        assertEquals("第三节", plan.current().course().name());
        assertEquals(25, plan.minutesRemaining());
        assertEquals("第五节", plan.next().course().name());
        // 13:30 − 10:10。
        assertEquals(200, plan.minutesToNext());
    }

    @Test
    public void waitingBeforeFirstClassReportsCountdownToIt() {
        TimetableWidgetPlan plan = plan(
                Collections.singletonList(course("高等数学", "三教301", 1, 2, "1-16")),
                SEMESTER_START, at(7, 40));
        assertEquals(TimetableWidgetPlan.Status.UPCOMING, plan.status());
        assertEquals(20, plan.minutesToNext());
        assertEquals(-1, plan.minutesRemaining());
        assertNull(plan.current());
    }

    @Test
    public void afterTheLastClassLooksForwardToTomorrow() {
        int tomorrowWeekday = TODAY.plusDays(1).getDayOfWeek().getValue();
        List<TimetableCourse> courses = Arrays.asList(
                course("上午课", "", 1, 2, "1-16"),
                TimetableCourse.create("明天的课", "", "一教201", tomorrowWeekday, 5, 6, "1-16"));
        TimetableWidgetPlan plan = plan(courses, SEMESTER_START, at(20, 0));
        assertEquals(TimetableWidgetPlan.Status.TODAY_DONE, plan.status());
        assertEquals(1, plan.todaySlots().size());
        assertEquals(LocalDate.of(2026, 11, 19), plan.next().day());
        // 13:30 次日 − 20:00 当天 = 17 小时 30 分。
        assertEquals(17 * 60 + 30, plan.minutesToNext());
    }

    @Test
    public void freeDayStillPointsAtTheNextClassWithinHorizon() {
        int laterWeekday = TODAY.plusDays(2).getDayOfWeek().getValue();
        List<TimetableCourse> courses = Collections.singletonList(
                TimetableCourse.create("后天的课", "", "", laterWeekday, 1, 2, "1-16"));
        TimetableWidgetPlan plan = plan(courses, SEMESTER_START, at(12, 0));
        assertEquals(TimetableWidgetPlan.Status.TODAY_CLEAR, plan.status());
        assertTrue(plan.todaySlots().isEmpty());
        assertEquals("后天的课", plan.next().course().name());
        assertEquals(2 * 24 * 60 + 480 - 720, plan.minutesToNext());
    }

    @Test
    public void nothingWithinHorizonMeansNoNextClass() {
        // 任何一个星期都在七天内出现，所以「近期没课」只能靠周次凑不出来：
        // 今天是第 2 周，唯一的课只在上到第 5 周，往后七天都不生效。
        LocalDate twoWeeksAgoStart = SEMESTER_START.minusWeeks(1);
        List<TimetableCourse> courses = Collections.singletonList(
                course("第 5 周才开始的课", "", 1, 1, "5"));
        TimetableWidgetPlan plan = plan(courses, twoWeeksAgoStart, at(12, 0));
        assertEquals(TimetableWidgetPlan.Status.TODAY_CLEAR, plan.status());
        assertNull(plan.next());
    }

    @Test
    public void oddEvenWeeksDecideWhetherTodayHasClass() {
        // 开学日期往前推一周：今天是第 2 周，单周课不该出现。
        LocalDate twoWeeksAgoStart = SEMESTER_START.minusWeeks(1);
        TimetableWidgetPlan oddOnly = plan(
                Collections.singletonList(course("单周体育课", "体育馆", 3, 4, "1-7单")),
                twoWeeksAgoStart, at(9, 0));
        assertEquals(2, oddOnly.teachingWeek());
        assertEquals(TimetableWidgetPlan.Status.TODAY_CLEAR, oddOnly.status());

        TimetableWidgetPlan evenActive = plan(
                Collections.singletonList(course("双周实验", "实验楼", 3, 4, "2,4,6")),
                twoWeeksAgoStart, at(9, 0));
        assertEquals(TimetableWidgetPlan.Status.UPCOMING, evenActive.status());
        assertEquals(1, evenActive.todaySlots().size());
    }

    @Test
    public void missingSemesterStartShowsEveryWeekAndSaysSo() {
        TimetableWidgetPlan plan = plan(
                Collections.singletonList(course("未定学期的课", "", 1, 1, "1-4")), null, at(7, 0));
        assertFalse(plan.weekKnown());
        assertEquals(0, plan.teachingWeek());
        assertEquals(TimetableWidgetPlan.Status.UPCOMING, plan.status());
        assertEquals(1, plan.todaySlots().size());
    }

    @Test
    public void outsideTeachingWeekListsNothing() {
        // 开学日期在今天之后：还在假期，一节都不列。
        TimetableWidgetPlan plan = plan(
                Collections.singletonList(course("下学期的课", "", 1, 1, "1-16")),
                SEMESTER_START.plusWeeks(2), at(7, 0));
        assertTrue(plan.weekKnown());
        assertEquals(0, plan.teachingWeek());
        assertEquals(TimetableWidgetPlan.Status.TODAY_CLEAR, plan.status());
    }

    @Test
    public void smallWidgetsPullTheCurrentClassIntoView() {
        List<TimetableCourse> courses = Arrays.asList(
                course("第一节", "", 1, 1, "1-16"),
                course("第二节", "", 2, 2, "1-16"),
                course("第三节", "", 3, 3, "1-16"));
        // 第 3 节（09:50 起）正在进行，两行的小组件装不下第 6 行位置，把它提到最前。
        TimetableWidgetPlan tiny = plan(courses, SEMESTER_START, at(10, 10));
        assertEquals(2, tiny.currentIndex());
        assertEquals("第三节", tiny.rows(2).get(0).course().name());
        assertEquals(2, tiny.rows(2).size());
        // 放得下时保持时间顺序，读起来才是课表。
        assertEquals("第一节", tiny.rows(4).get(0).course().name());
        assertTrue(tiny.rows(0).isEmpty());
    }

    @Test
    public void rowBudgetFollowsWidgetHeightAndStaysInRange() {
        assertEquals(TimetableWidgetPlan.MIN_ROWS, TimetableWidgetPlan.maxRows(110));
        assertEquals(3, TimetableWidgetPlan.maxRows(135));
        assertEquals(6, TimetableWidgetPlan.maxRows(200));
        assertEquals(TimetableWidgetPlan.MAX_ROWS, TimetableWidgetPlan.maxRows(600));
    }

    @Test
    public void periodsBeyondTheScheduleAreIgnored() {
        TimetableWidgetPlan plan = plan(
                Collections.singletonList(course("超出节数的课", "", 99, 99, "1-16")),
                SEMESTER_START, at(9, 0));
        assertTrue(plan.todaySlots().isEmpty());
        assertEquals(TimetableWidgetPlan.Status.TODAY_CLEAR, plan.status());
    }

    @Test
    public void holidayEmptiesTodayAndSaysSo() {
        TimetableWidgetPlan plan = plan(
                Collections.singletonList(course("高等数学", "教一", 1, 2, "1-20")),
                SEMESTER_START, at(9, 0),
                Collections.singletonList(DayAdjustment.holiday(TODAY)));
        assertTrue(plan.todaySlots().isEmpty());
        assertEquals(TimetableWidgetPlan.Status.TODAY_CLEAR, plan.status());
        assertTrue(plan.closedToday());
    }

    @Test
    public void withoutHolidayNothingIsMarkedClosed() {
        TimetableWidgetPlan plan = plan(
                Collections.singletonList(course("高等数学", "教一", 1, 2, "1-20")),
                SEMESTER_START, at(9, 0));
        assertFalse(plan.closedToday());
    }

    @Test
    public void cancelledCourseLeavesTheRestOfToday() {
        List<TimetableCourse> courses = Arrays.asList(
                course("高等数学", "教一", 1, 2, "1-20"), course("英语", "教二", 3, 4, "1-20"));
        TimetableWidgetPlan plan = plan(courses, SEMESTER_START, at(9, 0),
                Collections.singletonList(DayAdjustment.cancel(TODAY, courses.get(0).id())));
        assertEquals(1, plan.todaySlots().size());
        assertEquals("英语", plan.todaySlots().get(0).course().name());
    }

    @Test
    public void movedCourseIsGoneTodayAndAppearsOnItsNewDay() {
        TimetableCourse math = course("高等数学", "教一", 1, 2, "1-20");
        // 周三第一节挪到周四第 3-4 节：周四那格按 09:50 开始，作息没变。
        TimetableWidgetPlan plan = plan(Collections.singletonList(math), SEMESTER_START, at(8, 0),
                Collections.singletonList(DayAdjustment.move(TODAY, math.id(), 4, 3, 4)));
        assertTrue(plan.todaySlots().isEmpty());
        assertNotNull(plan.next());
        assertEquals(TODAY.plusDays(1), plan.next().day());
        assertEquals("09:50-11:25", plan.next().timeLabel());
    }

    @Test
    public void durationWordsStayColloquial() {
        assertEquals("不到 1 分钟", TimetableWidgetPlan.durationLabel(0));
        assertEquals("25 分钟", TimetableWidgetPlan.durationLabel(25));
        assertEquals("2 小时", TimetableWidgetPlan.durationLabel(120));
        assertEquals("2 小时 15 分", TimetableWidgetPlan.durationLabel(135));
        assertEquals("1 天", TimetableWidgetPlan.durationLabel(24 * 60));
        assertEquals("1 天 1 小时", TimetableWidgetPlan.durationLabel(25 * 60));
    }
}
