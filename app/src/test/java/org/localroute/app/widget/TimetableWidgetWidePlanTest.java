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

/** 双日课表：今天只留未结束、下一个有课日跳过空日/周末/放假日。 */
public class TimetableWidgetWidePlanTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 18); // 周三
    private static final LocalDate FRIDAY = LocalDate.of(2026, 11, 20);
    private static final LocalDate SEMESTER_START = monday(TODAY);
    private static final int AT_10 = 10 * 60;
    private static final StudySchedule WINTER = StudySchedule.winterDefault();

    private static LocalDate monday(LocalDate date) {
        return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    private static int weekday(LocalDate date) {
        return date.getDayOfWeek().getValue();
    }

    private static TimetableCourse course(String name, String place, LocalDate date,
                                          int start, int end) {
        return TimetableCourse.create(name, "", place, weekday(date), start, end, "1-16");
    }

    private static TimetableWidgetWidePlan plan(List<TimetableCourse> courses,
                                                LocalDate today, int now,
                                                LocalDate semesterStart,
                                                List<DayAdjustment> adjustments) {
        return TimetableWidgetWidePlan.of(courses, WINTER, semesterStart, 20, today, now,
                adjustments);
    }

    private static TimetableWidgetWidePlan plan(List<TimetableCourse> courses,
                                                LocalDate today, int now) {
        return plan(courses, today, now, SEMESTER_START, Collections.emptyList());
    }

    @Test
    public void noCoursesMeansNothingImported() {
        TimetableWidgetWidePlan plan = plan(Collections.emptyList(), TODAY, AT_10);
        assertTrue(plan.notImported());
        assertTrue(plan.todayUpcoming().isEmpty());
        assertNull(plan.nextDay());
    }

    @Test
    public void onlyUnfinishedCoursesTakeRows() {
        TimetableWidgetWidePlan plan = plan(Arrays.asList(
                course("已结束", "A101", TODAY, 1, 2),
                course("正在上", "B202", TODAY, 3, 4),
                course("下午课", "C303", TODAY, 5, 6)), TODAY, AT_10);

        assertEquals(3, plan.todayTotal());
        assertEquals(1, plan.todayFinished());
        assertEquals(2, plan.todayUpcoming().size());
        assertEquals("正在上", plan.todayUpcoming().get(0).course().name());
        assertTrue(plan.todayUpcoming().get(0).live());
        assertFalse(plan.todayUpcoming().get(1).live());
        assertEquals("09:50", plan.todayUpcoming().get(0).startLabel());
        assertEquals("11:25", plan.todayUpcoming().get(0).endLabel());
    }

    @Test
    public void nextDaySkipsWeekend() {
        TimetableWidgetWidePlan plan = plan(Arrays.asList(
                course("周五课", "A101", FRIDAY, 3, 4),
                course("周一课", "B202", FRIDAY.plusDays(3), 1, 2)), FRIDAY, AT_10);

        assertNotNull(plan.nextDay());
        assertEquals(FRIDAY.plusDays(3), plan.nextDay().date());
        assertEquals("周一课", plan.nextDay().slots().get(0).course().name());
        assertEquals(1, plan.nextDay().total());
    }

    @Test
    public void nextDaySkipsHoliday() {
        LocalDate thursday = TODAY.plusDays(1);
        LocalDate friday = TODAY.plusDays(2);
        List<DayAdjustment> holidays = Collections.singletonList(
                DayAdjustment.holiday(thursday));
        TimetableWidgetWidePlan plan = plan(Arrays.asList(
                course("周四课", "A101", thursday, 3, 4),
                course("周五课", "B202", friday, 3, 4)), TODAY, AT_10,
                SEMESTER_START, holidays);

        assertNotNull(plan.nextDay());
        assertEquals(friday, plan.nextDay().date());
        assertEquals("周五课", plan.nextDay().slots().get(0).course().name());
    }

    @Test
    public void closedTodayStillHasNextClassDay() {
        List<DayAdjustment> holidays = Collections.singletonList(
                DayAdjustment.holiday(TODAY));
        TimetableWidgetWidePlan plan = plan(
                Collections.singletonList(course("明天课", "A101", TODAY.plusDays(1), 3, 4)),
                TODAY, AT_10, SEMESTER_START, holidays);

        assertTrue(plan.todayClosed());
        assertTrue(plan.todayUpcoming().isEmpty());
        assertEquals(0, plan.todayTotal());
        assertNotNull(plan.nextDay());
        assertEquals(TODAY.plusDays(1), plan.nextDay().date());
    }

    @Test
    public void todayAllDoneStillShowsNextClassDay() {
        TimetableWidgetWidePlan plan = plan(Arrays.asList(
                course("早课", "A101", TODAY, 1, 2),
                course("明天课", "B202", TODAY.plusDays(1), 3, 4)), TODAY, AT_10);

        assertEquals(1, plan.todayTotal());
        assertEquals(1, plan.todayFinished());
        assertTrue(plan.todayUpcoming().isEmpty());
        assertNotNull(plan.nextDay());
        assertEquals(TODAY.plusDays(1), plan.nextDay().date());
    }

    @Test
    public void unknownSemesterStartStillGroupsByWeekday() {
        LocalDate tomorrow = TODAY.plusDays(1);
        TimetableWidgetWidePlan plan = plan(Arrays.asList(
                course("今天课", "A101", TODAY, 3, 4),
                course("明天课", "B202", tomorrow, 3, 4)), TODAY, AT_10, null,
                Collections.emptyList());

        assertFalse(plan.weekKnown());
        assertEquals(1, plan.todayUpcoming().size());
        assertNotNull(plan.nextDay());
        assertEquals(tomorrow, plan.nextDay().date());
    }

    @Test
    public void rowsAndHiddenCountsFollowTheLimit() {
        TimetableWidgetWidePlan plan = plan(Arrays.asList(
                course("一", "A101", TODAY, 3, 4),
                course("二", "A102", TODAY, 5, 6),
                course("三", "A103", TODAY, 7, 8),
                course("四", "B101", TODAY.plusDays(1), 1, 2),
                course("五", "B102", TODAY.plusDays(1), 3, 4),
                course("六", "B103", TODAY.plusDays(1), 5, 6)), TODAY, 9 * 60);

        assertEquals(2, plan.todayRows(2).size());
        assertEquals(1, plan.todayHidden(2));
        assertNotNull(plan.nextDay());
        assertEquals(1, plan.nextRows(plan.nextDay(), 1).size());
        assertEquals(2, plan.nextHidden(plan.nextDay(), 1));
    }

    @Test
    public void maxRowsFitsWideSizes() {
        assertEquals(1, TimetableWidgetWidePlan.maxRows(110));
        assertEquals(2, TimetableWidgetWidePlan.maxRows(180));
        assertEquals(2, TimetableWidgetWidePlan.maxRows(400));
    }

    @Test
    public void noNextClassWithinHorizonMeansNull() {
        // 只排第 1 周：下周同一星期几不算「接下来」，七天内确实没有下一节。
        TimetableWidgetWidePlan plan = plan(
                Collections.singletonList(TimetableCourse.create(
                        "今天课", "", "A101", weekday(TODAY), 3, 4, "1")),
                TODAY, 9 * 60);

        assertNull(plan.nextDay());
        assertEquals(0, plan.nextRows(null, 2).size());
        assertEquals(0, plan.nextHidden(null, 2));
    }
}
