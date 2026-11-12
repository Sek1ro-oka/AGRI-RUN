package org.localroute.app.study;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

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
}
