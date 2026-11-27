package org.localroute.app.study;

import org.junit.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** 提醒中心的排序与情形判定：谁先出现、谁说「不会响」。 */
public class ReminderCenterTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 24);
    private static final LocalDateTime NOW = TODAY.atTime(9, 0);

    private static ReminderCenter.State everythingOn() {
        ReminderCenter.State state = new ReminderCenter.State();
        state.classEnabled = true;
        state.semesterStartKnown = true;
        state.nextClassReminder = NOW.plusMinutes(10);
        state.courseCount = 8;
        state.homeworkEnabled = true;
        state.nextHomeworkReminder = NOW.plusDays(1);
        state.openHomeworkCount = 2;
        state.adjustmentCount = 1;
        state.nextAdjustmentDate = TODAY.plusDays(2);
        state.nextSeasonChange = LocalDate.of(2026, 12, 1);
        return state;
    }

    private static ReminderCenter.Entry find(List<ReminderCenter.Entry> entries,
                                             ReminderCenter.Kind kind) {
        for (ReminderCenter.Entry entry : entries) {
            if (entry.kind == kind) {
                return entry;
            }
        }
        throw new AssertionError("少了这一项：" + kind);
    }

    /** 没有下一次钟点的项按固定次序排，界面文案才对得上。 */
    @Test
    public void everyKindIsListedEvenWhenNothingIsOn() {
        List<ReminderCenter.Entry> entries = ReminderCenter.entries(new ReminderCenter.State());
        assertEquals(5, entries.size());
        assertEquals(ReminderCenter.Kind.CLASS, entries.get(0).kind);
        assertEquals(ReminderCenter.Kind.HOMEWORK, entries.get(1).kind);
        assertEquals(ReminderCenter.Kind.ADJUSTMENT, entries.get(2).kind);
        assertEquals(ReminderCenter.Kind.SEASON, entries.get(3).kind);
        assertEquals(ReminderCenter.Kind.UPDATE, entries.get(4).kind);
        assertEquals(ReminderCenter.Status.OFF,
                ReminderCenter.status(find(entries, ReminderCenter.Kind.CLASS)));
    }

    @Test
    public void classReminderNeedsMoreThanTheSwitch() {
        ReminderCenter.State state = new ReminderCenter.State();
        state.classEnabled = true;
        state.semesterStartKnown = false;
        state.courseCount = 8;
        ReminderCenter.Entry entry = find(ReminderCenter.entries(state),
                ReminderCenter.Kind.CLASS);
        assertFalse(entry.enabled);
        assertEquals(ReminderCenter.Status.OFF, ReminderCenter.status(entry));
        assertEquals(0, ReminderCenter.firingCount(state));
        // 条数照旧带出来，界面才说得清「课表有 8 门课，但没设开学日期」。
        assertEquals(8, entry.pendingCount);
    }

    @Test
    public void theSoonerReminderComesFirst() {
        ReminderCenter.State state = everythingOn();
        // 作业的提醒点在今天之前，时间更早就该排在第一行。
        state.nextHomeworkReminder = NOW.minusDays(1);
        List<ReminderCenter.Entry> entries = ReminderCenter.entries(state);
        assertEquals(ReminderCenter.Kind.HOMEWORK, entries.get(0).kind);
        assertEquals(ReminderCenter.Kind.CLASS, entries.get(1).kind);
    }

    @Test
    public void alarmsComeBeforeArrangements() {
        List<ReminderCenter.Entry> entries = ReminderCenter.entries(everythingOn());
        assertEquals(ReminderCenter.Kind.CLASS, entries.get(0).kind);
        assertEquals(ReminderCenter.Kind.HOMEWORK, entries.get(1).kind);
        assertEquals(ReminderCenter.Kind.ADJUSTMENT, entries.get(2).kind);
        assertEquals(ReminderCenter.Kind.SEASON, entries.get(3).kind);
        assertEquals(ReminderCenter.Kind.UPDATE, entries.get(4).kind);
    }

    @Test
    public void pinnedSeasonSaysSoInsteadOfNothing() {
        ReminderCenter.State state = everythingOn();
        state.seasonManuallyPinned = true;
        state.nextSeasonChange = null;
        ReminderCenter.Entry entry = find(ReminderCenter.entries(state),
                ReminderCenter.Kind.SEASON);
        assertFalse(entry.enabled);
        assertEquals(ReminderCenter.Status.PINNED_SEASON, ReminderCenter.status(entry));
    }

    @Test
    public void statusWordsFollowTheData() {
        ReminderCenter.State state = everythingOn();
        state.nextClassReminder = null;
        state.adjustmentCount = 0;
        state.nextAdjustmentDate = null;
        List<ReminderCenter.Entry> entries = ReminderCenter.entries(state);
        // 开着但近期没内容，和根本没开，是两件不同的事。
        assertEquals(ReminderCenter.Status.NOTHING_PENDING,
                ReminderCenter.status(find(entries, ReminderCenter.Kind.CLASS)));
        assertEquals(ReminderCenter.Status.NOTHING_PENDING,
                ReminderCenter.status(find(entries, ReminderCenter.Kind.ADJUSTMENT)));
        assertEquals(ReminderCenter.Status.WILL_FIRE,
                ReminderCenter.status(find(entries, ReminderCenter.Kind.HOMEWORK)));
        assertEquals(ReminderCenter.Status.WILL_CHANGE,
                ReminderCenter.status(find(entries, ReminderCenter.Kind.SEASON)));

        state.adjustmentCount = 3;
        state.nextAdjustmentDate = TODAY.plusDays(1);
        assertEquals(ReminderCenter.Status.SCHEDULE_AHEAD, ReminderCenter.status(
                find(ReminderCenter.entries(state), ReminderCenter.Kind.ADJUSTMENT)));
    }

    /** 更新检查没有开关，只在冷启动时静默查一次；写「没开」是假话。 */
    @Test
    public void updateCheckRunsAtStartupWithoutSwitch() {
        ReminderCenter.Entry entry = find(ReminderCenter.entries(everythingOn()),
                ReminderCenter.Kind.UPDATE);
        assertFalse(entry.enabled);
        assertEquals(ReminderCenter.Status.STARTUP_CHECK, ReminderCenter.status(entry));
    }

    @Test
    public void labelsAreWrittenByHandSoEveryRomReadsTheSame() {
        assertEquals("11月25日 07:50",
                ReminderCenter.momentLabel(NOW.plusDays(1).withHour(7).withMinute(50)));
        assertEquals("", ReminderCenter.momentLabel(null));
        assertEquals("12月1日", ReminderCenter.dateLabel(LocalDate.of(2026, 12, 1), TODAY));
        assertEquals("2027年1月5日", ReminderCenter.dateLabel(LocalDate.of(2027, 1, 5), TODAY));
        assertEquals("", ReminderCenter.dateLabel(null, TODAY));
    }

    @Test
    public void countsMatchWhatWouldActuallyFire() {
        ReminderCenter.State state = everythingOn();
        assertEquals(2, ReminderCenter.firingCount(state));
        assertEquals(2, ReminderCenter.scheduledCount(ReminderCenter.entries(state)));
        ReminderCenter.State off = new ReminderCenter.State();
        assertEquals(5, ReminderCenter.entries(off).size());
        assertEquals(0, ReminderCenter.firingCount(off));
        assertTrue(ReminderCenter.scheduledCount(ReminderCenter.entries(off)) == 0);
    }
}
