package org.localroute.app.home;

import org.junit.Test;
import org.localroute.app.study.DayAdjustment;
import org.localroute.app.study.HomeworkItem;
import org.localroute.app.study.StudySchedule;
import org.localroute.app.study.TimetableCourse;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** 首页时间轴：课程节次钟点 + 作业真实截止时刻，没有时刻的不编时间。 */
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

    private static long millisAt(LocalDate day, int hour, int minute) {
        return day.atTime(hour, minute).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private static HomeAgenda.Task task(String title, long deadline, HomeworkItem.Status status) {
        return new HomeAgenda.Task(title, "食品原料生产安全控制", deadline, status);
    }

    private static HomeAgenda.Day day(List<TimetableCourse> courses, LocalDate day, LocalDate today,
                                      int nowMinutes, List<HomeAgenda.Task> tasks,
                                      List<DayAdjustment> adjustments) {
        return HomeAgenda.of(courses, WINTER, MONDAY, TOTAL_WEEKS, adjustments, day, today,
                nowMinutes, tasks);
    }

    @Test public void coursesUseRealPeriodClockTimes() {
        HomeAgenda.Day result = day(courses(course("a", 3, 1, 2), course("b", 3, 3, 4)),
                WEDNESDAY, WEDNESDAY, 0, Collections.emptyList(), Collections.emptyList());

        assertEquals(2, result.items().size());
        assertEquals("08:00", result.items().get(0).timeLabel());
        assertEquals("09:50", result.items().get(1).timeLabel());
        assertEquals("教一北205 · 杨慧", result.items().get(0).subtitle());
        assertEquals(2, result.courseCount());
        assertEquals(0, result.taskCount());
    }

    @Test public void todaySplitsDoneLiveAndUpcoming() {
        List<TimetableCourse> list = courses(course("a", 3, 1, 2), course("b", 3, 3, 4));
        // 09:00 落在第一节（08:00-09:35）里。
        HomeAgenda.Day result = day(list, WEDNESDAY, WEDNESDAY, 9 * 60,
                Collections.emptyList(), Collections.emptyList());

        assertEquals(HomeAgenda.ClassState.LIVE, result.items().get(0).classState());
        assertEquals(HomeAgenda.ClassState.UPCOMING, result.items().get(1).classState());
    }

    @Test public void pastDayIsAllDoneAndFutureDayAllUpcoming() {
        List<TimetableCourse> list = courses(course("a", 3, 1, 2), course("b", 3, 3, 4));
        HomeAgenda.Day past = day(list, WEDNESDAY, THURSDAY, 9 * 60,
                Collections.emptyList(), Collections.emptyList());
        HomeAgenda.Day future = day(list, WEDNESDAY, MONDAY, 9 * 60,
                Collections.emptyList(), Collections.emptyList());

        assertEquals(HomeAgenda.ClassState.DONE, past.items().get(0).classState());
        assertEquals(HomeAgenda.ClassState.DONE, past.items().get(1).classState());
        assertEquals(HomeAgenda.ClassState.UPCOMING, future.items().get(0).classState());
        assertEquals(HomeAgenda.ClassState.UPCOMING, future.items().get(1).classState());
    }

    @Test public void homeworkWithRealDeadlineSortsIntoTheTimeline() {
        List<TimetableCourse> list = courses(course("a", 3, 1, 2), course("b", 3, 5, 6));
        List<HomeAgenda.Task> tasks = Collections.singletonList(
                task("实验报告三", millisAt(WEDNESDAY, 10, 0), HomeworkItem.Status.PENDING));

        HomeAgenda.Day result = day(list, WEDNESDAY, WEDNESDAY, 9 * 60, tasks,
                Collections.emptyList());

        assertEquals(3, result.items().size());
        assertEquals("08:00", result.items().get(0).timeLabel());
        assertEquals("10:00", result.items().get(1).timeLabel());
        assertEquals(HomeAgenda.Kind.HOMEWORK, result.items().get(1).kind());
        assertEquals(HomeworkItem.Status.PENDING, result.items().get(1).taskStatus());
        assertNull(result.items().get(1).classState());
        assertEquals("13:30", result.items().get(2).timeLabel());
        assertEquals(1, result.taskCount());
    }

    @Test public void undatedHomeworkGoesLastWithoutInventedTime() {
        List<TimetableCourse> list = courses(course("a", 3, 5, 6));
        List<HomeAgenda.Task> tasks = Arrays.asList(
                task("没有时刻的作业", 0L, HomeworkItem.Status.PENDING),
                task("有时刻的作业", millisAt(WEDNESDAY, 9, 0), HomeworkItem.Status.PENDING));

        HomeAgenda.Day result = day(list, WEDNESDAY, WEDNESDAY, 8 * 60, tasks,
                Collections.emptyList());

        assertEquals(3, result.items().size());
        assertEquals("09:00", result.items().get(0).timeLabel());
        assertEquals("13:30", result.items().get(1).timeLabel());
        assertNull(result.items().get(2).timeLabel());
        assertEquals(-1, result.items().get(2).minutes());
        assertEquals("没有时刻的作业", result.items().get(2).title());
    }

    @Test public void undatedHomeworkOnlyAppearsOnToday() {
        List<HomeAgenda.Task> tasks = Collections.singletonList(
                task("没有时刻的作业", 0L, HomeworkItem.Status.PENDING));

        HomeAgenda.Day today = day(courses(), WEDNESDAY, WEDNESDAY, 8 * 60, tasks,
                Collections.emptyList());
        HomeAgenda.Day tomorrow = day(courses(), THURSDAY, WEDNESDAY, 8 * 60, tasks,
                Collections.emptyList());

        assertEquals(1, today.items().size());
        assertTrue(tomorrow.items().isEmpty());
    }

    @Test public void submittedUndatedHomeworkDoesNotTakeHomeSpace() {
        List<HomeAgenda.Task> tasks = Collections.singletonList(
                task("已交的没时刻作业", 0L, HomeworkItem.Status.SUBMITTED));

        HomeAgenda.Day result = day(courses(), WEDNESDAY, WEDNESDAY, 8 * 60, tasks,
                Collections.emptyList());

        assertTrue(result.items().isEmpty());
        assertEquals(0, result.taskCount());
    }

    @Test public void deadlineOnAnotherDayDoesNotLeakIntoToday() {
        List<HomeAgenda.Task> tasks = Collections.singletonList(
                task("明天截止", millisAt(THURSDAY, 23, 59), HomeworkItem.Status.PENDING));

        HomeAgenda.Day today = day(courses(), WEDNESDAY, WEDNESDAY, 8 * 60, tasks,
                Collections.emptyList());
        HomeAgenda.Day tomorrow = day(courses(), THURSDAY, WEDNESDAY, 8 * 60, tasks,
                Collections.emptyList());

        assertTrue(today.items().isEmpty());
        assertEquals(1, tomorrow.items().size());
        assertEquals("23:59", tomorrow.items().get(0).timeLabel());
    }

    @Test public void holidayKeepsHomeworkButDropsClasses() {
        List<DayAdjustment> adjustments = Collections.singletonList(
                DayAdjustment.holiday(WEDNESDAY));

        HomeAgenda.Day result = day(courses(course("a", 3, 1, 2)), WEDNESDAY, WEDNESDAY, 8 * 60,
                Collections.singletonList(task("实验报告三", millisAt(WEDNESDAY, 10, 0),
                        HomeworkItem.Status.PENDING)), adjustments);

        assertTrue(result.closed());
        assertEquals(0, result.courseCount());
        assertEquals(1, result.taskCount());
        assertEquals(HomeAgenda.Kind.HOMEWORK, result.items().get(0).kind());
    }

    @Test public void emptyTimetableIsReportedAsNotImported() {
        HomeAgenda.Day result = day(courses(), WEDNESDAY, WEDNESDAY, 8 * 60,
                Collections.emptyList(), Collections.emptyList());

        assertTrue(result.notImported());
        assertFalse(result.closed());
        assertTrue(result.items().isEmpty());
    }

    @Test public void movedClassFollowsTheAdjustment() {
        List<TimetableCourse> list = courses(course("a", 3, 1, 2));
        List<DayAdjustment> adjustments = Collections.singletonList(
                DayAdjustment.move(WEDNESDAY, "a", 4, 5, 6));

        HomeAgenda.Day origin = day(list, WEDNESDAY, WEDNESDAY, 8 * 60,
                Collections.emptyList(), adjustments);
        HomeAgenda.Day target = day(list, THURSDAY, WEDNESDAY, 8 * 60,
                Collections.emptyList(), adjustments);

        assertTrue(origin.items().isEmpty());
        assertEquals(1, target.items().size());
        assertEquals("13:30", target.items().get(0).timeLabel());
    }

    /** 冬令时默认 4+4+4=12 节；第 13 节起的课没有钟点，和小组件、课前提醒一样跳过。 */
    @Test public void classBeyondSchedulePeriodsIsSkipped() {
        HomeAgenda.Day result = day(courses(course("a", 3, 13, 15)), WEDNESDAY, WEDNESDAY,
                8 * 60, Collections.emptyList(), Collections.emptyList());

        assertTrue(result.items().isEmpty());
        assertEquals(0, result.courseCount());
    }

    /** 末节超出作息时钳到最后一节：21:45 下课，21:50 就该是已结束而不是进行中。 */
    @Test public void endPeriodIsClampedToScheduleTotal() {
        HomeAgenda.Day result = day(courses(course("a", 3, 11, 15)), WEDNESDAY, WEDNESDAY,
                21 * 60 + 50, Collections.emptyList(), Collections.emptyList());

        assertEquals(1, result.items().size());
        assertEquals("20:10", result.items().get(0).timeLabel());
        assertEquals(HomeAgenda.ClassState.DONE, result.items().get(0).classState());
    }
}