package org.localroute.app.study;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class StudyTimetableStoreTest {

    @Test
    public void legacyMinutesArePreservedWhenScheduleStaysValid() {
        StudySchedule schedule = StudyTimetableStore.migrateLegacy(
                StudySchedule.SEASON_WINTER, 45);
        assertEquals(45, schedule.periodMinutes());
        assertEquals("08:00", schedule.startLabel(1));
        assertTrue(schedule.isValid());
    }

    @Test
    public void migrationFallsBackWhenLegacyMinutesOverflowSegment() {
        StudySchedule schedule = StudyTimetableStore.migrateLegacy(
                StudySchedule.SEASON_WINTER, 120);
        assertEquals(StudySchedule.winterDefault().periodMinutes(),
                schedule.periodMinutes());
        assertEquals("13:30", schedule.startLabel(5));
        assertTrue(schedule.isValid());
    }

    @Test
    public void outOfRangeLegacyMinutesUseSeasonDefaults() {
        assertEquals(StudySchedule.summerDefault().encode(),
                StudyTimetableStore.migrateLegacy(StudySchedule.SEASON_SUMMER, 0).encode());
        assertEquals(StudySchedule.summerDefault().encode(),
                StudyTimetableStore.migrateLegacy(StudySchedule.SEASON_SUMMER, 999).encode());
    }
}
