package org.localroute.app.study;

import org.junit.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** 某一天的课怎么排：常规周次之上再套调课与节假日。 */
public class TimetableDayTest {

    /** 2026-09-07 是星期一，第 1 周。 */
    private static final LocalDate MONDAY = LocalDate.of(2026, 9, 7);
    private static final LocalDate WEDNESDAY = LocalDate.of(2026, 9, 9);
    private static final LocalDate FRIDAY = LocalDate.of(2026, 9, 11);
    private static final LocalDate SATURDAY = LocalDate.of(2026, 9, 12);
    private static final int TOTAL_WEEKS = 20;

    private static TimetableCourse course(String id, int weekday, int start, int end,
                                          String weeks) {
        return new TimetableCourse(id, "课程" + id, null, "教一101", weekday, start, end, weeks);
    }

    private static List<TimetableCourse> courses(TimetableCourse... items) {
        return new ArrayList<>(Arrays.asList(items));
    }

    private static List<DayAdjustment> adjustments(DayAdjustment... items) {
        return new ArrayList<>(Arrays.asList(items));
    }

    private static String ids(List<TimetableCourse> list) {
        StringBuilder text = new StringBuilder();
        for (TimetableCourse course : list) {
            if (text.length() > 0) {
                text.append(',');
            }
            text.append(course.id());
        }
        return text.toString();
    }

    @Test
    public void withoutAdjustmentsItIsJustThatWeekday() {
        List<TimetableCourse> all = courses(course("a", 3, 1, 2, "1-20"),
                course("m", 1, 1, 2, "1-20"));
        assertEquals("a", ids(TimetableDay.classesOn(all, WEDNESDAY, MONDAY, TOTAL_WEEKS,
                adjustments())));
    }

    @Test
    public void weekOutsideTheCourseRangeHasNothing() {
        List<TimetableCourse> all = courses(course("a", 3, 1, 2, "3-12"));
        assertTrue(TimetableDay.classesOn(all, WEDNESDAY, MONDAY, TOTAL_WEEKS, adjustments())
                .isEmpty());
    }

    @Test
    public void dayBeforeTermStartHasNoClasses() {
        List<TimetableCourse> all = courses(course("a", 3, 1, 2, "1-20"));
        assertTrue(TimetableDay.classesOn(all, LocalDate.of(2026, 9, 2), MONDAY, TOTAL_WEEKS,
                adjustments()).isEmpty());
    }

    @Test
    public void dayAfterTheTermEndsHasNoClasses() {
        // 学期只有 20 周：第 21 周的周三就算写着课也不算，周次认不出来的脏写法更不该漏进来。
        List<TimetableCourse> all = courses(course("a", 3, 1, 2, "1-20"),
                course("weird", 3, 3, 4, "看不懂"));
        LocalDate afterTerm = MONDAY.plusWeeks(TOTAL_WEEKS).plusDays(2);
        assertTrue(TimetableDay.classesOn(all, afterTerm, MONDAY, TOTAL_WEEKS, adjustments())
                .isEmpty());
        // 没设置开学日期时本来就没有周次可筛，这天照星期几给课。
        assertEquals("a,weird", ids(TimetableDay.classesOn(all, afterTerm, null, TOTAL_WEEKS,
                adjustments())));
    }

    @Test
    public void holidayClearsTheWholeDay() {
        List<TimetableCourse> all = courses(course("a", 3, 1, 2, "1-20"));
        assertTrue(TimetableDay.classesOn(all, WEDNESDAY, MONDAY, TOTAL_WEEKS,
                adjustments(DayAdjustment.holiday(WEDNESDAY))).isEmpty());
        assertTrue(TimetableDay.isClosed(WEDNESDAY, adjustments(
                DayAdjustment.holiday(WEDNESDAY))));
        assertFalse(TimetableDay.isClosed(FRIDAY, adjustments(
                DayAdjustment.holiday(WEDNESDAY))));
    }

    @Test
    public void holidayBeatsMakeupOnTheSameDay() {
        // 既写放假又写补课时无从判断用户到底想什么，按「不上课」这条更保守的走。
        List<TimetableCourse> all = courses(course("m", 1, 1, 2, "1-20"));
        assertTrue(TimetableDay.classesOn(all, WEDNESDAY, MONDAY, TOTAL_WEEKS, adjustments(
                DayAdjustment.makeup(WEDNESDAY, 1),
                DayAdjustment.holiday(WEDNESDAY))).isEmpty());
    }

    @Test
    public void cancelDropsOnlyThatCourse() {
        List<TimetableCourse> all = courses(course("a", 3, 1, 2, "1-20"),
                course("b", 3, 5, 6, "1-20"));
        assertEquals("b", ids(TimetableDay.classesOn(all, WEDNESDAY, MONDAY, TOTAL_WEEKS,
                adjustments(DayAdjustment.cancel(WEDNESDAY, "a")))));
    }

    @Test
    public void moveEmptiesTheOldDayAndFillsTheNewOne() {
        List<TimetableCourse> all = courses(course("a", 3, 1, 2, "1-20"));
        List<DayAdjustment> items = adjustments(
                DayAdjustment.move(WEDNESDAY, "a", 5, 3, 4));
        assertTrue(TimetableDay.classesOn(all, WEDNESDAY, MONDAY, TOTAL_WEEKS, items).isEmpty());
        List<TimetableCourse> moved = TimetableDay.classesOn(all, FRIDAY, MONDAY, TOTAL_WEEKS,
                items);
        assertEquals("a", ids(moved));
        assertEquals(5, moved.get(0).weekday());
        assertEquals(3, moved.get(0).startPeriod());
        assertEquals(4, moved.get(0).endPeriod());
        assertEquals("教一101", moved.get(0).location());
    }

    @Test
    public void moveInsideTheSameDayKeepsTheCourseWithNewPeriods() {
        List<TimetableCourse> all = courses(course("a", 3, 1, 2, "1-20"));
        List<TimetableCourse> result = TimetableDay.classesOn(all, WEDNESDAY, MONDAY, TOTAL_WEEKS,
                adjustments(DayAdjustment.move(WEDNESDAY, "a", 3, 7, 8)));
        assertEquals(1, result.size());
        assertEquals(7, result.get(0).startPeriod());
        assertEquals(8, result.get(0).endPeriod());
    }

    @Test
    public void moveFromAStaleRecordAddsNothing() {
        // 课程其实是周二的，记录却写在周三：这种陈旧条目不该凭空造出一节周五的课。
        List<TimetableCourse> all = courses(course("a", 2, 1, 2, "1-20"));
        assertTrue(TimetableDay.classesOn(all, FRIDAY, MONDAY, TOTAL_WEEKS, adjustments(
                DayAdjustment.move(WEDNESDAY, "a", 5, 1, 2))).isEmpty());
    }

    @Test
    public void makeupTeachesAnotherWeekdaysTimetable() {
        List<TimetableCourse> all = courses(course("m", 1, 1, 2, "1-20"),
                course("n", 1, 3, 4, "1-20"));
        List<TimetableCourse> result = TimetableDay.classesOn(all, SATURDAY, MONDAY, TOTAL_WEEKS,
                adjustments(DayAdjustment.makeup(SATURDAY, 1)));
        assertEquals("m,n", ids(result));
        assertEquals(6, result.get(0).weekday());
        assertEquals(1, result.get(0).startPeriod());
    }

    @Test
    public void makeupStillRespectsOddEvenWeeks() {
        // 双周才上的课，补课落在奇数周就不该出现。
        List<TimetableCourse> all = courses(course("e", 1, 1, 2, "1-16双"));
        assertTrue(TimetableDay.classesOn(all, SATURDAY, MONDAY, TOTAL_WEEKS,
                adjustments(DayAdjustment.makeup(SATURDAY, 1))).isEmpty());
    }

    @Test
    public void makeupNeverDuplicatesACourseAlreadyThatDay() {
        List<TimetableCourse> all = courses(course("a", 3, 1, 2, "1-20"));
        // 周三本身有课，又被补成周三：只该出现一次。
        assertEquals("a", ids(TimetableDay.classesOn(all, WEDNESDAY, MONDAY, TOTAL_WEEKS,
                adjustments(DayAdjustment.makeup(WEDNESDAY, 3)))));
    }

    @Test
    public void withoutSemesterStartWeekdayFilterAndCancelsStillWork() {
        List<TimetableCourse> all = courses(course("a", 3, 1, 2, "1-20"),
                course("b", 3, 5, 6, "1-20"));
        List<DayAdjustment> items = adjustments(DayAdjustment.cancel(WEDNESDAY, "a"));
        assertEquals("b", ids(TimetableDay.classesOn(all, WEDNESDAY, null, TOTAL_WEEKS, items)));
    }

    @Test
    public void targetDateStaysInsideTheSameWeek() {
        // 第 2 周周二的课改到星期一，仍是同一周的那一周。
        LocalDate tuesdayWeek2 = MONDAY.plusWeeks(1).plusDays(1);
        DayAdjustment move = DayAdjustment.move(tuesdayWeek2, "a", 1, 1, 1);
        assertEquals(MONDAY.plusWeeks(1), move.targetDate());
        assertNull(DayAdjustment.holiday(tuesdayWeek2).targetDate());
    }

    @Test
    public void replacingCollapsesTheSameSlotOnly() {
        List<DayAdjustment> current = adjustments(
                DayAdjustment.move(WEDNESDAY, "a", 5, 1, 2),
                DayAdjustment.cancel(WEDNESDAY, "b"),
                DayAdjustment.holiday(FRIDAY));
        List<DayAdjustment> next = TimetableDay.replacing(current,
                DayAdjustment.cancel(WEDNESDAY, "a"));
        // 同一天同一门课只留最后一次安排：改课记录被停课顶掉，别的都不动。
        assertEquals(3, next.size());
        assertTrue(next.contains(DayAdjustment.cancel(WEDNESDAY, "a")));
        assertFalse(next.contains(DayAdjustment.move(WEDNESDAY, "a", 5, 1, 2)));
        assertEquals(4, TimetableDay.replacing(current, DayAdjustment.holiday(WEDNESDAY)).size());
        assertEquals(2, TimetableDay.removing(next,
                DayAdjustment.cancel(WEDNESDAY, "b")).size());
    }

    @Test
    public void onDayListsOnlyThatDate() {
        List<DayAdjustment> items = adjustments(DayAdjustment.holiday(WEDNESDAY),
                DayAdjustment.cancel(FRIDAY, "a"));
        assertEquals(1, TimetableDay.onDay(WEDNESDAY, items).size());
        assertTrue(TimetableDay.onDay(SATURDAY, items).isEmpty());
    }

    @Test
    public void adjustmentsRoundTripThroughJson() {
        // 编码会把过期记录清掉，所以这组用例全部用将来的日期。
        LocalDate base = LocalDate.now().plusDays(3);
        List<DayAdjustment> items = adjustments(
                DayAdjustment.holiday(base),
                DayAdjustment.makeup(base.plusDays(1), 3),
                DayAdjustment.cancel(base, "a"),
                DayAdjustment.move(base, "b", 5, 3, 4));
        List<DayAdjustment> back = DayAdjustmentCodec.decode(DayAdjustmentCodec.encode(items));
        assertEquals(items.size(), back.size());
        assertTrue(back.contains(DayAdjustment.makeup(base.plusDays(1), 3)));
        assertTrue(back.contains(DayAdjustment.move(base, "b", 5, 3, 4)));
        assertTrue(back.contains(DayAdjustment.cancel(base, "a")));
        assertTrue(back.contains(DayAdjustment.holiday(base)));
    }

    @Test
    public void encodingDropsDaysAlreadyGone() {
        LocalDate today = LocalDate.now();
        List<DayAdjustment> items = adjustments(
                DayAdjustment.holiday(today.minusDays(1)),
                DayAdjustment.holiday(today.plusDays(1)));
        List<DayAdjustment> back = DayAdjustmentCodec.decode(DayAdjustmentCodec.encode(items));
        assertEquals(1, back.size());
        assertEquals(today.plusDays(1), back.get(0).date());
    }

    @Test
    public void brokenEntriesAreSkippedNotFatal() {
        String json = "[{\"date\":\"2026-09-09\",\"kind\":\"HOLIDAY\"},"
                + "{\"date\":\"不是日期\",\"kind\":\"HOLIDAY\"},"
                + "{\"date\":\"2026-09-10\",\"kind\":\"FESTIVAL\"},"
                + "{\"date\":\"2026-09-11\",\"kind\":\"MOVE\",\"courseId\":\"a\","
                + "\"weekday\":9,\"start\":1,\"end\":2}]";
        List<DayAdjustment> items = DayAdjustmentCodec.decode(json);
        assertEquals(1, items.size());
        assertEquals(WEDNESDAY, items.get(0).date());
    }

    @Test
    public void corruptJsonDecodesToNoAdjustments() {
        assertTrue(DayAdjustmentCodec.decode("{不是数组}").isEmpty());
        assertTrue(DayAdjustmentCodec.decode(null).isEmpty());
        assertTrue(DayAdjustmentCodec.decode("[]").isEmpty());
    }

    @Test
    public void pastEntriesDropOutOfStorage() {
        List<DayAdjustment> items = adjustments(
                DayAdjustment.holiday(LocalDate.of(2026, 9, 9)),
                DayAdjustment.holiday(LocalDate.of(2026, 12, 31)));
        List<DayAdjustment> kept = DayAdjustmentCodec.keepRecent(items,
                LocalDate.of(2026, 10, 1));
        assertEquals(1, kept.size());
        assertEquals(LocalDate.of(2026, 12, 31), kept.get(0).date());
    }

    @Test
    public void encodingPrunesHistoryAndTrimsToTheCap() {
        LocalDate today = LocalDate.now();
        List<DayAdjustment> items = new ArrayList<>();
        for (int i = 0; i < DayAdjustmentCodec.MAX_ITEMS + 30; i++) {
            items.add(DayAdjustment.holiday(today.plusDays(i)));
        }
        assertEquals(DayAdjustmentCodec.MAX_ITEMS,
                DayAdjustmentCodec.decode(DayAdjustmentCodec.encode(items)).size());
    }

    @Test
    public void moveEndsAtOrAfterItsStart() {
        DayAdjustment move = DayAdjustment.move(WEDNESDAY, "a", 5, 4, 2);
        assertEquals(4, move.toStart());
        assertEquals(4, move.toEnd());
        assertTrue(move.isValid());
    }
}
