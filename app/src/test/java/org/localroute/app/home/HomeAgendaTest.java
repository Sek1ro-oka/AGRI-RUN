package org.localroute.app.home;

import org.junit.Test;
import org.localroute.app.study.DayAdjustment;
import org.localroute.app.study.StudySchedule;
import org.localroute.app.study.TimetableCourse;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** 首页课程时间轴：节次钟点、状态、收起已结束、调课与停课。 */
public class HomeAgendaTest {

    /** 2026-09-07 是星期一，第 1 周。 */
    private static final LocalDate MONDAY = LocalDate.of(2026, 9, 7);
    private static final LocalDate WEDNESDAY = LocalDate.of(2026, 9, 9);
    private static final LocalDate THURSDAY = LocalDate.of(2026, 9, 10);
    private static final int TOTAL_WEEKS = 20;
    private static final StudySchedule WINTER = StudySchedule.winterDefault();

    private static TimetableCourse course(String id, int weekday, int start, int end) {
        return new TimetableCourse(id, "课程" + id, "杨慧", "教一北205", weekday, start, end, "1-20");
    }

    private static List<TimetableCourse> courses(TimetableCourse... items) {
        return new ArrayList<>(Arrays.asList(items));
    }

    private static HomeAgenda.Day day(List<TimetableCourse> courses, LocalDate day,
                                      LocalDate today, int nowMinutes,
                                      List<DayAdjustment> adjustments) {
        return HomeAgenda.of(courses, WINTER, MONDAY, TOTAL_WEEKS, adjustments, day, today,
                nowMinutes);
    }

    @Test public void coursesUseRealPeriodClockTimes() {
        HomeAgenda.Day result = day(courses(course("a", 3, 1, 2), course("b", 3, 3, 4)),
                WEDNESDAY, WEDNESDAY, 0, Collections.emptyList());

        assertEquals(2, result.items().size());
        assertEquals("08:00", result.items().get(0).timeLabel());
        assertEquals("09:50", result.items().get(1).timeLabel());
        assertEquals("教一北205 · 杨慧", result.items().get(0).subtitle());
        assertEquals(2, result.courseCount());
    }

    @Test public void todaySplitsDoneLiveAndUpcoming() {
        List<TimetableCourse> list = courses(course("a", 3, 1, 2), course("b", 3, 3, 4));
        // 09:00 落在第一节（08:00-09:35）里。
        HomeAgenda.Day result = day(list, WEDNESDAY, WEDNESDAY, 9 * 60, Collections.emptyList());

        assertEquals(HomeAgenda.ClassState.LIVE, result.items().get(0).classState());
        assertEquals(HomeAgenda.ClassState.UPCOMING, result.items().get(1).classState());
    }

    /** 今天已结束的课收起，但当天课程总数不变。 */
    @Test public void finishedClassesAreHiddenOnToday() {
        List<TimetableCourse> list = courses(course("a", 3, 1, 2), course("b", 3, 3, 4));
        HomeAgenda.Day result = day(list, WEDNESDAY, WEDNESDAY, 10 * 60, Collections.emptyList());

        assertEquals(1, result.items().size());
        assertEquals("09:50", result.items().get(0).timeLabel());
        assertEquals(1, result.hiddenFinished());
        assertEquals(2, result.courseCount());
    }

    /** 翻到过去日期时不收起，否则历史全是空白。 */
    @Test public void pastDayKeepsFinishedClasses() {
        List<TimetableCourse> list = courses(course("a", 3, 1, 2), course("b", 3, 3, 4));
        HomeAgenda.Day result = day(list, WEDNESDAY, THURSDAY, 10 * 60, Collections.emptyList());

        assertEquals(2, result.items().size());
        assertEquals(0, result.hiddenFinished());
        assertEquals(HomeAgenda.ClassState.DONE, result.items().get(0).classState());
    }

    @Test public void holidayDropsAllClasses() {
        List<DayAdjustment> adjustments = Collections.singletonList(
                DayAdjustment.holiday(WEDNESDAY));
        HomeAgenda.Day result = day(courses(course("a", 3, 1, 2)), WEDNESDAY, WEDNESDAY, 8 * 60,
                adjustments);

        assertTrue(result.closed());
        assertTrue(result.items().isEmpty());
        assertEquals(0, result.courseCount());
    }

    @Test public void emptyTimetableIsReportedAsNotImported() {
        HomeAgenda.Day result = day(courses(), WEDNESDAY, WEDNESDAY, 8 * 60,
                Collections.emptyList());

        assertTrue(result.notImported());
        assertFalse(result.closed());
        assertTrue(result.items().isEmpty());
    }

    @Test public void movedClassFollowsTheAdjustment() {
        List<TimetableCourse> list = courses(course("a", 3, 1, 2));
        List<DayAdjustment> adjustments = Collections.singletonList(
                DayAdjustment.move(WEDNESDAY, "a", 4, 5, 6));

        HomeAgenda.Day origin = day(list, WEDNESDAY, WEDNESDAY, 8 * 60, adjustments);
        HomeAgenda.Day target = day(list, THURSDAY, WEDNESDAY, 8 * 60, adjustments);

        assertTrue(origin.items().isEmpty());
        assertEquals(1, target.items().size());
        assertEquals("13:30", target.items().get(0).timeLabel());
    }

    /** 冬令时默认 4+4+4=12 节；第 13 节起的课没有钟点，和小组件、课前提醒一样跳过。 */
    @Test public void classBeyondSchedulePeriodsIsSkipped() {
        HomeAgenda.Day result = day(courses(course("a", 3, 13, 15)), WEDNESDAY, WEDNESDAY,
                8 * 60, Collections.emptyList());

        assertTrue(result.items().isEmpty());
        assertEquals(0, result.courseCount());
    }

    /** 末节超出作息时钳到最后一节：21:45 下课，21:50 就该判已结束（不钳的话会算成进行中）。 */
    @Test public void endPeriodIsClampedToScheduleTotal() {
        HomeAgenda.Day result = day(courses(course("a", 3, 11, 15)), WEDNESDAY, WEDNESDAY,
                21 * 60 + 50, Collections.emptyList());

        assertEquals(1, result.courseCount());
        assertEquals(1, result.hiddenFinished());
        assertTrue(result.items().isEmpty());
    }
}