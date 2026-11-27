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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 首页「接下来」：固定上限、跳过已结束的课、按天分组、放假日与隐藏数量。
 */
public class HomeUpcomingTest {

    /** 2026-09-07 是星期一，第 1 周。 */
    private static final LocalDate MONDAY = LocalDate.of(2026, 9, 7);
    private static final LocalDate WEDNESDAY = LocalDate.of(2026, 9, 9);
    private static final LocalDate THURSDAY = LocalDate.of(2026, 9, 10);
    private static final LocalDate FRIDAY = LocalDate.of(2026, 9, 11);
    private static final int TOTAL_WEEKS = 20;
    private static final int NOW = 10 * 60 + 30;
    private static final StudySchedule WINTER = StudySchedule.winterDefault();

    private static TimetableCourse course(String id, int weekday, int start, int end) {
        return course(id, weekday, start, end, "1-20");
    }

    private static TimetableCourse weekCourse(String id, int weekday, int start, int end) {
        return course(id, weekday, start, end, "1");
    }

    private static TimetableCourse course(String id, int weekday, int start, int end,
                                          String weeks) {
        return new TimetableCourse(id, "课程" + id, "杨慧", "教一北205", weekday, start, end,
                weeks);
    }

    private static HomeUpcoming.Feed feed(List<TimetableCourse> courses, int limit) {
        return HomeUpcoming.of(courses, WINTER, MONDAY, TOTAL_WEEKS,
                Collections.emptyList(), WEDNESDAY, NOW, limit);
    }

    private static List<TimetableCourse> weekdays() {
        List<TimetableCourse> list = new ArrayList<>(Arrays.asList(
                // 今天：1-2 已结束，3-4 正在上，5-6、7-8 还没上。
                course("a", 3, 1, 2), course("b", 3, 3, 4),
                course("c", 3, 5, 6), course("d", 3, 7, 8),
                // 明天三节（最后一节是晚课）。
                course("e", 4, 1, 2), course("f", 4, 3, 4), course("g", 4, 9, 10),
                // 周五两节。
                course("h", 5, 3, 4), course("i", 5, 5, 6)));
        return list;
    }

    private static List<TimetableCourse> weekOnly() {
        List<TimetableCourse> list = new ArrayList<>(Arrays.asList(
                weekCourse("a", 3, 1, 2), weekCourse("b", 3, 3, 4),
                weekCourse("c", 3, 5, 6), weekCourse("d", 3, 7, 8),
                weekCourse("e", 4, 1, 2), weekCourse("f", 4, 3, 4),
                weekCourse("g", 4, 9, 10),
                weekCourse("h", 5, 3, 4), weekCourse("i", 5, 5, 6)));
        return list;
    }

    @Test
    public void showsSixClassesAcrossDaysAndKeepsNextHiddenDay() {
        HomeUpcoming.Feed feed = feed(weekdays(), HomeUpcoming.DEFAULT_LIMIT);

        assertEquals(6, feed.shown());
        assertEquals(4, feed.todayTotal());
        // 今天四节里已结束一节，所以行数是三节。
        assertEquals(1, feed.todayFinished());
        assertEquals(2, feed.groups().size());
        HomeUpcoming.DayGroup today = feed.groups().get(0);
        assertEquals(WEDNESDAY, today.date());
        assertEquals(4, today.total());
        assertEquals(1, today.finished());
        assertEquals(3, today.remaining());
        assertEquals(3, today.items().size());
        HomeUpcoming.DayGroup tomorrow = feed.groups().get(1);
        assertEquals(THURSDAY, tomorrow.date());
        assertEquals(3, tomorrow.remaining());
        assertEquals(3, tomorrow.items().size());
        assertEquals("18:30", tomorrow.items().get(2).timeLabel());
        // 上限之外的第一天是周五，剩两节。
        assertEquals(FRIDAY, feed.nextHiddenDate());
        assertEquals(2, feed.nextHiddenCount());
        assertFalse(feed.empty());
    }

    @Test
    public void limitCanEndInTheMiddleOfADay() {
        HomeUpcoming.Feed feed = feed(weekdays(), 4);

        assertEquals(4, feed.shown());
        assertEquals(THURSDAY, feed.nextHiddenDate());
        assertEquals(2, feed.nextHiddenCount());
        assertEquals(2, feed.groups().size());
        assertEquals(3, feed.groups().get(0).items().size());
        assertEquals(1, feed.groups().get(1).items().size());
        assertEquals(3, feed.groups().get(1).remaining());
    }

    @Test
    public void fewerClassesThanLimitHasNothingHidden() {
        HomeUpcoming.Feed feed = feed(new ArrayList<>(Arrays.asList(
                weekCourse("a", 3, 1, 2), weekCourse("b", 3, 5, 6), weekCourse("c", 3, 7, 8))),
                HomeUpcoming.DEFAULT_LIMIT);

        assertEquals(2, feed.shown());
        assertNull(feed.nextHiddenDate());
        assertEquals(1, feed.todayFinished());
        assertEquals(3, feed.todayTotal());
    }

    @Test
    public void holidayTodayKeepsAClosedGroupBeforeFutureClasses() {
        List<DayAdjustment> holidays = Collections.singletonList(
                DayAdjustment.holiday(WEDNESDAY));
        HomeUpcoming.Feed feed = HomeUpcoming.of(weekOnly(), WINTER, MONDAY, TOTAL_WEEKS,
                holidays, WEDNESDAY, NOW, HomeUpcoming.DEFAULT_LIMIT);

        assertTrue(feed.todayClosed());
        assertEquals(0, feed.todayTotal());
        assertFalse(feed.empty());
        assertEquals(3, feed.groups().size());
        assertTrue(feed.groups().get(0).closed());
        assertEquals(WEDNESDAY, feed.groups().get(0).date());
        assertEquals(THURSDAY, feed.groups().get(1).date());
        assertEquals(0, feed.groups().get(0).items().size());
    }

    @Test
    public void holidayBetweenShownGroupsIsStillExplained() {
        List<DayAdjustment> holidays = Collections.singletonList(
                DayAdjustment.holiday(THURSDAY));
        HomeUpcoming.Feed feed = HomeUpcoming.of(weekOnly(), WINTER, MONDAY, TOTAL_WEEKS,
                holidays, WEDNESDAY, NOW, HomeUpcoming.DEFAULT_LIMIT);

        assertEquals(3, feed.groups().size());
        assertEquals(WEDNESDAY, feed.groups().get(0).date());
        assertTrue(feed.groups().get(1).closed());
        assertEquals(THURSDAY, feed.groups().get(1).date());
        assertEquals(FRIDAY, feed.groups().get(2).date());
        assertEquals(2, feed.groups().get(2).items().size());
        assertNull(feed.nextHiddenDate());
    }

    @Test
    public void withoutSemesterStartOnlyLooksAtOneWeek() {
        HomeUpcoming.Feed feed = HomeUpcoming.of(weekdays(), WINTER, null, TOTAL_WEEKS,
                Collections.emptyList(), WEDNESDAY, NOW, HomeUpcoming.DEFAULT_LIMIT);

        assertEquals(6, feed.shown());
        assertEquals(FRIDAY, feed.nextHiddenDate());
        assertEquals(2, feed.nextHiddenCount());
    }

    @Test
    public void noTimetableIsNotTheSameAsNoUpcomingClasses() {
        HomeUpcoming.Feed feed = feed(Collections.emptyList(), HomeUpcoming.DEFAULT_LIMIT);

        assertTrue(feed.notImported());
        assertTrue(feed.empty());
        assertEquals(0, feed.shown());
        assertEquals(0, feed.todayTotal());
        assertTrue(feed.groups().isEmpty());
    }

    @Test
    public void finishedClassesOnlyMeansNoUpcoming() {
        HomeUpcoming.Feed feed = feed(new ArrayList<>(Collections.singletonList(
                weekCourse("a", 3, 1, 2))), HomeUpcoming.DEFAULT_LIMIT);

        assertFalse(feed.notImported());
        assertTrue(feed.empty());
        assertEquals(1, feed.todayFinished());
        assertEquals(1, feed.todayTotal());
    }

    @Test
    public void zeroLimitIsTreatedAsOne() {
        HomeUpcoming.Feed feed = feed(weekdays(), 0);

        assertEquals(1, feed.shown());
        assertEquals(WEDNESDAY, feed.nextHiddenDate());
        assertEquals(2, feed.nextHiddenCount());
    }
    @Test
    public void withoutSemesterStartDoesNotRollIntoNextWeek() {
        // 只有今天已结束的一节课；如果扫描越过一周，下周三会再次出现。
        HomeUpcoming.Feed feed = HomeUpcoming.of(
                new ArrayList<>(Collections.singletonList(weekCourse("a", 3, 1, 2))),
                WINTER, null, TOTAL_WEEKS, Collections.emptyList(), WEDNESDAY, NOW,
                HomeUpcoming.DEFAULT_LIMIT);

        assertFalse(feed.notImported());
        assertTrue(feed.empty());
        assertNull(feed.nextHiddenDate());
        assertTrue(feed.groups().isEmpty());
    }

    @Test
    public void semesterEndStopsBeforeNextWeeksClasses() {
        List<TimetableCourse> courses = new ArrayList<>(Arrays.asList(
                weekCourse("friday", 5, 3, 4),
                // 开学只有一周，下周一已经超出总周数，不应出现。
                weekCourse("next-week", 1, 3, 4)));
        HomeUpcoming.Feed feed = HomeUpcoming.of(courses, WINTER, MONDAY, 1,
                Collections.emptyList(), WEDNESDAY, NOW, HomeUpcoming.DEFAULT_LIMIT);

        assertEquals(1, feed.shown());
        assertEquals(1, feed.groups().size());
        assertEquals(FRIDAY, feed.groups().get(0).date());
        assertNull(feed.nextHiddenDate());
    }

    @Test
    public void holidayAfterLastShownDayIsNotAdded() {
        List<TimetableCourse> courses = new ArrayList<>(Arrays.asList(
                weekCourse("a", 3, 3, 4), weekCourse("b", 3, 5, 6), weekCourse("c", 3, 7, 8)));
        List<DayAdjustment> holidays = Collections.singletonList(
                DayAdjustment.holiday(FRIDAY));
        HomeUpcoming.Feed feed = HomeUpcoming.of(courses, WINTER, MONDAY, TOTAL_WEEKS,
                holidays, WEDNESDAY, NOW, HomeUpcoming.DEFAULT_LIMIT);

        assertEquals(3, feed.shown());
        assertEquals(1, feed.groups().size());
        assertEquals(WEDNESDAY, feed.groups().get(0).date());
        assertFalse(feed.groups().get(0).closed());
        assertNull(feed.nextHiddenDate());
    }

}
