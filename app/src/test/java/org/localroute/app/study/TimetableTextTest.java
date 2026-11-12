package org.localroute.app.study;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** 手动编辑与教务导入共用的节次、周次归一化。 */
public class TimetableTextTest {

    @Test
    public void parsesPeriodRanges() {
        assertArrayEquals(new int[]{1, 2}, TimetableText.parsePeriods("1-2"));
        assertArrayEquals(new int[]{3, 3}, TimetableText.parsePeriods("第3节"));
        assertArrayEquals(new int[]{5, 6}, TimetableText.parsePeriods("5~6"));
        assertArrayEquals(new int[]{1, 2}, TimetableText.parsePeriods("1 - 2"));
        assertArrayEquals(new int[]{13, 14}, TimetableText.parsePeriods("13-14"));
        assertNull(TimetableText.parsePeriods("0-1"));
        assertNull(TimetableText.parsePeriods("16-17"));
        assertNull(TimetableText.parsePeriods("5-3"));
        assertNull(TimetableText.parsePeriods("早上"));
        assertNull(TimetableText.parsePeriods(null));
    }

    @Test
    public void normalizesWeeks() {
        assertEquals("1-16", TimetableText.normalizeWeeks(""));
        assertEquals("1-16", TimetableText.normalizeWeeks("第1-16周"));
        assertEquals("1,3,5", TimetableText.normalizeWeeks("1,3,5周"));
        assertEquals("1-16单", TimetableText.normalizeWeeks("1-16单周"));
        assertEquals("1-16双", TimetableText.normalizeWeeks("1-16双"));
        assertEquals("1-16", TimetableText.normalizeWeeks("第1周-第16周"));
        assertNull(TimetableText.normalizeWeeks("操场"));
        assertNull(TimetableText.normalizeWeeks(null));
    }

    @Test
    public void formatsSingleAndRangePeriods() {
        assertEquals("3", TimetableText.formatPeriods(3, 3));
        assertEquals("1-2", TimetableText.formatPeriods(1, 2));
    }

    @Test
    public void filtersCoursesByTeachingWeek() {
        assertTrue(TimetableText.activeInWeek("1-16", 3));
        assertFalse(TimetableText.activeInWeek("1-16", 17));
        assertTrue(TimetableText.activeInWeek("3-4,6-7", 7));
        assertFalse(TimetableText.activeInWeek("3-4,6-7", 5));
        assertTrue(TimetableText.activeInWeek("1-16单", 3));
        assertFalse(TimetableText.activeInWeek("1-16单", 4));
        assertTrue(TimetableText.activeInWeek("1-16双", 4));
        assertFalse(TimetableText.activeInWeek("1-16双", 3));
        assertTrue(TimetableText.activeInWeek("1-16单周", 15));
        assertTrue(TimetableText.activeInWeek(" 第1-16周 ", 2));
        assertFalse(TimetableText.activeInWeek("1-16", 0));
        // 无法解析的周次按「每周都上」处理，避免课程从课表中消失。
        assertTrue(TimetableText.activeInWeek("待定", 5));
        assertTrue(TimetableText.activeInWeek(null, 5));
    }

    @Test
    public void computesMaxWeek() {
        assertEquals(16, TimetableText.maxWeek("1-16"));
        assertEquals(7, TimetableText.maxWeek("3-4,6-7"));
        assertEquals(20, TimetableText.maxWeek("1-20单"));
        assertEquals(16, TimetableText.maxWeek("第1-16周"));
        assertEquals(0, TimetableText.maxWeek("待定"));
        assertEquals(0, TimetableText.maxWeek(null));
    }
}
