package org.localroute.app.study;

import org.junit.Test;

import java.time.LocalDate;

import static org.junit.Assert.assertEquals;

/** 教学周换算与首尾循环翻页。 */
public class SemesterCalendarTest {

    private static final LocalDate START = LocalDate.of(2026, 9, 7); // 周一

    @Test
    public void weekOneOnStartMonday() {
        assertEquals(1, SemesterCalendar.teachingWeek(START, START, 20));
        assertEquals(1, SemesterCalendar.teachingWeek(START, START.plusDays(6), 20));
    }

    @Test
    public void countsMondays() {
        assertEquals(2, SemesterCalendar.teachingWeek(START, START.plusWeeks(1), 20));
        assertEquals(11, SemesterCalendar.teachingWeek(START, LocalDate.of(2026, 11, 16), 20));
    }

    @Test
    public void midWeekStartUsesThatMonday() {
        // 设置成周三，仍按该周周一算第 1 周；周日属于同一周。
        LocalDate wednesday = START.plusDays(2);
        assertEquals(1, SemesterCalendar.teachingWeek(wednesday, START, 20));
        assertEquals(2, SemesterCalendar.teachingWeek(wednesday, START.plusWeeks(1), 20));
    }

    @Test
    public void outsideSemesterHasNoCurrentWeek() {
        assertEquals(0, SemesterCalendar.teachingWeek(START, START.minusDays(1), 20));
        assertEquals(0, SemesterCalendar.teachingWeek(START, START.plusWeeks(20), 20));
        assertEquals(0, SemesterCalendar.teachingWeek(null, START, 20));
    }

    @Test
    public void wrapWeekLoopsBothDirections() {
        assertEquals(1, SemesterCalendar.wrapWeek(20, 1, 20));
        assertEquals(20, SemesterCalendar.wrapWeek(1, -1, 20));
        assertEquals(2, SemesterCalendar.wrapWeek(1, 1, 20));
        assertEquals(19, SemesterCalendar.wrapWeek(20, -1, 20));
        assertEquals(1, SemesterCalendar.wrapWeek(1, 1, 1));
        assertEquals(1, SemesterCalendar.wrapWeek(1, -1, 1));
    }
}
