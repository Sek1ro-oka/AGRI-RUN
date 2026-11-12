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
        assertEquals(5, schedule.breakMinutes());
        assertEquals(15, schedule.longBreakMinutes());
        assertEquals("08:00", schedule.startLabel(1));
        assertEquals("08:45", schedule.endLabel(1));
        assertEquals("09:50", schedule.startLabel(3));
        assertEquals("11:25", schedule.endLabel(4));
        assertEquals("13:30", schedule.startLabel(5));
        assertEquals("16:55", schedule.endLabel(8));
        assertEquals("18:30", schedule.startLabel(9));
        assertEquals("21:45", schedule.endLabel(12));
    }

    @Test
    public void summerDefaultMovesAfternoonAndEvening() {
        StudySchedule schedule = StudySchedule.summerDefault();
        assertTrue(schedule.isValid());
        assertEquals("08:00", schedule.startLabel(1));
        assertEquals("14:00", schedule.startLabel(5));
        assertEquals("17:25", schedule.endLabel(8));
        assertEquals("19:00", schedule.startLabel(9));
    }

    @Test
    public void longBreakOnlyFollowsSecondPeriodOfMorningAndAfternoon() {
        StudySchedule schedule = StudySchedule.winterDefault();
        assertEquals(5, schedule.breakBeforePeriod(2));
        assertEquals(15, schedule.breakBeforePeriod(3));
        assertEquals(5, schedule.breakBeforePeriod(4));
        assertEquals(15, schedule.breakBeforePeriod(7));
        assertEquals(5, schedule.breakBeforePeriod(8));
        assertEquals(5, schedule.breakBeforePeriod(10));
        assertEquals("10:40", schedule.startLabel(4));
        assertEquals("16:10", schedule.startLabel(8));
        assertEquals("19:20", schedule.startLabel(10));
    }

    @Test
    public void breakMinutesPushLaterPeriods() {
        StudySchedule schedule = StudySchedule.winterDefault().withBreakMinutes(0);
        assertEquals(15, schedule.longBreakMinutes());
        assertEquals("10:30", schedule.startLabel(4));
        assertEquals("11:15", schedule.endLabel(4));
        assertTrue(schedule.isValid());
    }

    @Test
    public void longBreakMinutesPushMorningAndAfternoonPeriods() {
        StudySchedule schedule = StudySchedule.winterDefault().withLongBreakMinutes(30);
        assertEquals("09:35", schedule.endLabel(2));
        assertEquals("10:05", schedule.startLabel(3));
        assertEquals("10:55", schedule.startLabel(4));
        assertEquals("15:35", schedule.startLabel(7));
        assertEquals("18:30", schedule.startLabel(9));
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
        StudySchedule schedule = new StudySchedule(8 * 60, 11 * 60, 18 * 60,
                45, 10, 15, 4, 4, 4);
        assertFalse(schedule.isValid());
    }

    @Test
    public void eveningOverflowIsInvalid() {
        StudySchedule schedule = new StudySchedule(8 * 60, 14 * 60, 21 * 60,
                45, 10, 15, 4, 4, 4);
        assertFalse(schedule.isValid());
    }

    @Test
    public void encodeDecodeRoundTrip() {
        StudySchedule schedule = new StudySchedule(8 * 60 + 5, 13 * 60 + 55,
                18 * 60 + 40, 50, 5, 15, 5, 4, 3);
        StudySchedule decoded = StudySchedule.decode(schedule.encode());
        assertNotNull(decoded);
        assertEquals(schedule.morningStart(), decoded.morningStart());
        assertEquals(schedule.afternoonStart(), decoded.afternoonStart());
        assertEquals(schedule.eveningStart(), decoded.eveningStart());
        assertEquals(schedule.periodMinutes(), decoded.periodMinutes());
        assertEquals(schedule.breakMinutes(), decoded.breakMinutes());
        assertEquals(schedule.longBreakMinutes(), decoded.longBreakMinutes());
        assertEquals(5, decoded.periodCount(StudySchedule.SEGMENT_MORNING));
        assertEquals(4, decoded.periodCount(StudySchedule.SEGMENT_AFTERNOON));
        assertEquals(3, decoded.periodCount(StudySchedule.SEGMENT_EVENING));
    }

    @Test
    public void decodeRejectsBrokenOrInvalidJson() {
        assertNull(StudySchedule.decode(null));
        assertNull(StudySchedule.decode(""));
        assertNull(StudySchedule.decode("not json"));
        assertNull(StudySchedule.decode("{\"morning\":480,\"afternoon\":300,"
                + "\"evening\":1110,\"period\":45,\"break\":10}"));
    }

    @Test
    public void decodeFillsMissingFieldsWithDefaults() {
        StudySchedule decoded = StudySchedule.decode("{\"morning\":510}");
        assertNotNull(decoded);
        assertEquals(510, decoded.morningStart());
        assertEquals(13 * 60 + 30, decoded.afternoonStart());
        assertEquals(18 * 60 + 30, decoded.eveningStart());
        assertEquals(45, decoded.periodMinutes());
        assertEquals(5, decoded.breakMinutes());
        assertEquals(15, decoded.longBreakMinutes());
        assertEquals(4, decoded.periodCount(StudySchedule.SEGMENT_MORNING));
        assertEquals(4, decoded.periodCount(StudySchedule.SEGMENT_AFTERNOON));
        assertEquals(4, decoded.periodCount(StudySchedule.SEGMENT_EVENING));
    }

    @Test
    public void legacyJsonWithoutLongBreakMigratesToNewDefaults() {
        String legacy = "{\"morning\":480,\"afternoon\":840,\"evening\":1110,"
                + "\"period\":45,\"break\":10,\"morningCount\":5,"
                + "\"afternoonCount\":4,\"eveningCount\":4}";
        StudySchedule decoded = StudySchedule.decode(legacy, StudySchedule.SEASON_WINTER);
        assertNotNull(decoded);
        assertEquals(13 * 60 + 30, decoded.afternoonStart());
        assertEquals(5, decoded.breakMinutes());
        assertEquals(15, decoded.longBreakMinutes());
        assertEquals(5, decoded.periodCount(StudySchedule.SEGMENT_MORNING));
        assertEquals("18:30", decoded.startLabel(10));
        assertTrue(decoded.isValid());
    }

    @Test
    public void legacyCustomAfternoonStartIsPreserved() {
        String legacy = "{\"morning\":480,\"afternoon\":900,\"evening\":1110,"
                + "\"period\":45,\"break\":10}";
        StudySchedule decoded = StudySchedule.decode(legacy, StudySchedule.SEASON_WINTER);
        assertNotNull(decoded);
        assertEquals(900, decoded.afternoonStart());
        assertEquals(5, decoded.breakMinutes());
        assertEquals(15, decoded.longBreakMinutes());
        assertTrue(decoded.isValid());
    }

    @Test
    public void legacyTightScheduleKeepsOldBreakInsteadOfLosingCounts() {
        String legacy = "{\"morning\":480,\"afternoon\":750,\"evening\":960,"
                + "\"period\":45,\"break\":0,\"morningCount\":6,"
                + "\"afternoonCount\":4,\"eveningCount\":4}";
        StudySchedule decoded = StudySchedule.decode(legacy, StudySchedule.SEASON_WINTER);
        assertNotNull(decoded);
        assertEquals(0, decoded.breakMinutes());
        assertEquals(0, decoded.longBreakMinutes());
        assertEquals(6, decoded.periodCount(StudySchedule.SEGMENT_MORNING));
        assertEquals(750, decoded.afternoonStart());
        assertTrue(decoded.isValid());
    }

    @Test
    public void legacySummerAfternoonStartMovesToNewDefault() {
        String legacy = "{\"morning\":480,\"afternoon\":870,\"evening\":1140,"
                + "\"period\":45,\"break\":10}";
        StudySchedule decoded = StudySchedule.decode(legacy, StudySchedule.SEASON_SUMMER);
        assertNotNull(decoded);
        assertEquals(14 * 60, decoded.afternoonStart());
        assertEquals(19 * 60, decoded.eveningStart());
        assertTrue(decoded.isValid());
    }

    @Test
    public void morningFivePeriodsShiftAfternoonAndEveningNumbering() {
        StudySchedule schedule = StudySchedule.winterDefault()
                .withPeriodCount(StudySchedule.SEGMENT_MORNING, 5);
        assertTrue(schedule.isValid());
        assertEquals(5, schedule.periodCount(StudySchedule.SEGMENT_MORNING));
        assertEquals(6, schedule.firstPeriod(StudySchedule.SEGMENT_AFTERNOON));
        assertEquals(10, schedule.firstPeriod(StudySchedule.SEGMENT_EVENING));
        assertEquals(13, schedule.totalPeriods());
        assertEquals("11:30", schedule.startLabel(5));
        assertEquals("12:15", schedule.endLabel(5));
        assertEquals("13:30", schedule.startLabel(6));
        assertEquals("18:30", schedule.startLabel(10));
    }

    @Test
    public void customCountsKeepTimeCalculationConsistent() {
        StudySchedule schedule = StudySchedule.winterDefault()
                .withPeriodCount(StudySchedule.SEGMENT_MORNING, 5)
                .withPeriodCount(StudySchedule.SEGMENT_EVENING, 3);
        assertTrue(schedule.isValid());
        assertEquals(12, schedule.totalPeriods());
        assertEquals(9, schedule.lastPeriod(StudySchedule.SEGMENT_AFTERNOON));
        assertEquals(10, schedule.firstPeriod(StudySchedule.SEGMENT_EVENING));
        assertEquals("18:30", schedule.startLabel(10));
        assertEquals("20:55", schedule.endLabel(12));
    }

    @Test
    public void tooManyTotalPeriodsIsInvalid() {
        StudySchedule schedule = StudySchedule.winterDefault()
                .withPeriodCount(StudySchedule.SEGMENT_MORNING, 6)
                .withPeriodCount(StudySchedule.SEGMENT_AFTERNOON, 6)
                .withPeriodCount(StudySchedule.SEGMENT_EVENING, 6);
        assertEquals(18, schedule.totalPeriods());
        assertFalse(schedule.isValid());
    }

    @Test
    public void periodCountOutsideRangeIsInvalid() {
        StudySchedule schedule = StudySchedule.winterDefault()
                .withPeriodCount(StudySchedule.SEGMENT_MORNING, 0);
        assertFalse(schedule.isValid());
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
