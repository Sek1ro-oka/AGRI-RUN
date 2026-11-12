package org.localroute.app.study;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class StudyScheduleTest {

    @Test
    public void winterDefaultHasExpectedTimes() {
        StudySchedule schedule = StudySchedule.winterDefault();
        assertTrue(schedule.isValid());
        assertEquals(45, schedule.periodMinutes());
        assertEquals(10, schedule.breakMinutes());
        assertEquals("08:00", schedule.startLabel(1));
        assertEquals("08:45", schedule.endLabel(1));
        assertEquals("09:50", schedule.startLabel(3));
        assertEquals("11:30", schedule.endLabel(4));
        assertEquals("14:00", schedule.startLabel(5));
        assertEquals("17:30", schedule.endLabel(8));
        assertEquals("18:30", schedule.startLabel(9));
        assertEquals("22:00", schedule.endLabel(12));
    }

    @Test
    public void summerDefaultMovesAfternoonAndEvening() {
        StudySchedule schedule = StudySchedule.summerDefault();
        assertTrue(schedule.isValid());
        assertEquals("08:00", schedule.startLabel(1));
        assertEquals("14:30", schedule.startLabel(5));
        assertEquals("18:00", schedule.endLabel(8));
        assertEquals("19:00", schedule.startLabel(9));
    }

    @Test
    public void breakMinutesPushLaterPeriods() {
        StudySchedule schedule = StudySchedule.winterDefault().withBreakMinutes(0);
        assertEquals("10:15", schedule.startLabel(4));
        assertEquals("11:00", schedule.endLabel(4));
        assertTrue(schedule.isValid());
    }

    @Test
    public void changedSegmentStartOnlyAffectsThatSegment() {
        StudySchedule schedule = StudySchedule.winterDefault()
                .withSegmentStart(StudySchedule.SEGMENT_AFTERNOON, 14 * 60 + 5);
        assertEquals("14:05", schedule.startLabel(5));
        assertEquals("08:00", schedule.startLabel(1));
        assertEquals("18:30", schedule.startLabel(9));
        assertTrue(schedule.isValid());
    }

    @Test
    public void overlapWithNextSegmentIsInvalid() {
        StudySchedule schedule = new StudySchedule(8 * 60, 11 * 60, 18 * 60, 45, 10);
        assertFalse(schedule.isValid());
    }

    @Test
    public void eveningOverflowIsInvalid() {
        StudySchedule schedule = new StudySchedule(8 * 60, 14 * 60, 21 * 60, 45, 10);
        assertFalse(schedule.isValid());
    }

    @Test
    public void encodeDecodeRoundTrip() {
        StudySchedule schedule = new StudySchedule(8 * 60 + 5, 13 * 60 + 55,
                18 * 60 + 40, 50, 5);
        StudySchedule decoded = StudySchedule.decode(schedule.encode());
        assertNotNull(decoded);
        assertEquals(schedule.morningStart(), decoded.morningStart());
        assertEquals(schedule.afternoonStart(), decoded.afternoonStart());
        assertEquals(schedule.eveningStart(), decoded.eveningStart());
        assertEquals(schedule.periodMinutes(), decoded.periodMinutes());
        assertEquals(schedule.breakMinutes(), decoded.breakMinutes());
    }

    @Test
    public void decodeRejectsBrokenOrInvalidJson() {
        assertNull(StudySchedule.decode(null));
        assertNull(StudySchedule.decode("not json"));
        assertNull(StudySchedule.decode("{\"morning\":480,\"afternoon\":300,"
                + "\"evening\":1110,\"period\":45,\"break\":10}"));
    }

    @Test
    public void decodeFillsMissingFieldsWithDefaults() {
        StudySchedule decoded = StudySchedule.decode("{\"morning\":510}");
        assertNotNull(decoded);
        assertEquals(510, decoded.morningStart());
        assertEquals(14 * 60, decoded.afternoonStart());
        assertEquals(45, decoded.periodMinutes());
    }

    @Test
    public void seasonForMonthUsesSummerMayToOctober() {
        assertEquals(StudySchedule.SEASON_SUMMER, StudySchedule.seasonForMonth(5));
        assertEquals(StudySchedule.SEASON_SUMMER, StudySchedule.seasonForMonth(10));
        assertEquals(StudySchedule.SEASON_WINTER, StudySchedule.seasonForMonth(11));
        assertEquals(StudySchedule.SEASON_WINTER, StudySchedule.seasonForMonth(4));
    }

    @Test
    public void snapToFiveMinutesRoundsAndClamps() {
        assertEquals(8 * 60, StudySchedule.snapToFiveMinutes(8 * 60 + 2));
        assertEquals(8 * 60 + 5, StudySchedule.snapToFiveMinutes(8 * 60 + 3));
        assertEquals(23 * 60 + 55, StudySchedule.snapToFiveMinutes(23 * 60 + 59));
        assertEquals(0, StudySchedule.snapToFiveMinutes(-5));
    }

    @Test
    public void formatMinutesWrapsAtMidnight() {
        assertEquals("00:00", StudySchedule.formatMinutes(24 * 60));
        assertEquals("08:05", StudySchedule.formatMinutes(8 * 60 + 5));
    }
}
