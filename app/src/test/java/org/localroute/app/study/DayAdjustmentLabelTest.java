package org.localroute.app.study;

import org.junit.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** 例外记录怎么写成人话：删了的课、跨年的日期、单节与连节。 */
public class DayAdjustmentLabelTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 24);

    private static List<TimetableCourse> courses() {
        return new ArrayList<>(Arrays.asList(
                new TimetableCourse("a", "高等数学", null, null, 4, 1, 2, "1-20")));
    }

    @Test
    public void weekdayNamesCoverTheWholeWeek() {
        assertEquals("周一", DayAdjustmentLabel.weekdayName(1));
        assertEquals("周日", DayAdjustmentLabel.weekdayName(7));
        assertEquals("", DayAdjustmentLabel.weekdayName(0));
        assertEquals("", DayAdjustmentLabel.weekdayName(9));
    }

    @Test
    public void sameYearDropsTheYearButCrossingKeepsIt() {
        assertEquals("12-24", DayAdjustmentLabel.dateLabel(
                LocalDate.of(2026, 12, 24), TODAY));
        assertEquals("2027-01-05", DayAdjustmentLabel.dateLabel(
                LocalDate.of(2027, 1, 5), TODAY));
    }

    @Test
    public void holidayAndMakeupReadLikeASchoolNotice() {
        assertEquals("12-24 周四 放假", DayAdjustmentLabel.label(
                DayAdjustment.holiday(LocalDate.of(2026, 12, 24)), courses(), TODAY));
        assertEquals("12-24 周四 按周三上课", DayAdjustmentLabel.label(
                DayAdjustment.makeup(LocalDate.of(2026, 12, 24), 3), courses(), TODAY));
    }

    @Test
    public void cancelNamesTheCourse() {
        assertEquals("12-24 周四 停上《高等数学》", DayAdjustmentLabel.label(
                DayAdjustment.cancel(LocalDate.of(2026, 12, 24), "a"), courses(), TODAY));
    }

    @Test
    public void moveNamesTheNewSlot() {
        LocalDate date = LocalDate.of(2026, 12, 24);
        assertEquals("12-24 周四 《高等数学》改到周五 第3-4节", DayAdjustmentLabel.label(
                DayAdjustment.move(date, "a", 5, 3, 4), courses(), TODAY));
        // 只占一节时不说成「第3-3节」。
        assertTrue(DayAdjustmentLabel.label(DayAdjustment.move(date, "a", 5, 3, 3),
                courses(), TODAY).endsWith("第3节"));
    }

    @Test
    public void deletedCourseStillGetsALabel() {
        assertEquals("12-24 周四 停上《已删除的课程》", DayAdjustmentLabel.label(
                DayAdjustment.cancel(LocalDate.of(2026, 12, 24), "gone"), courses(), TODAY));
        assertEquals("12-24 周四 停上《已删除的课程》", DayAdjustmentLabel.label(
                DayAdjustment.cancel(LocalDate.of(2026, 12, 24), "a"), null, TODAY));
    }

    @Test
    public void kindNamesAreDistinct() {
        List<String> names = Arrays.asList(
                DayAdjustmentLabel.kindLabel(DayAdjustment.Kind.HOLIDAY),
                DayAdjustmentLabel.kindLabel(DayAdjustment.Kind.MAKEUP),
                DayAdjustmentLabel.kindLabel(DayAdjustment.Kind.CANCEL),
                DayAdjustmentLabel.kindLabel(DayAdjustment.Kind.MOVE));
        assertEquals(4, new java.util.HashSet<>(names).size());
        assertTrue(names.get(0).contains("放假"));
        assertTrue(names.get(3).contains("改课"));
    }
}
