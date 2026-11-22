package org.localroute.app.study;

import org.junit.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** 作业截止提醒计划：提醒点推算、时间窗、排序与到期补发。 */
public class HomeworkReminderPlannerTest {

    private static long millisOf(int year, int month, int day, int hour, int minute) {
        return LocalDateTime.of(year, month, day, hour, minute)
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private static CustomHomework homework(String title, int year, int month, int day,
                                           int hour, int minute, int lead) {
        return CustomHomework.create(title, millisOf(year, month, day, hour, minute), lead);
    }

    @Test
    public void clampLeadBounds() {
        assertEquals(CustomHomework.MIN_LEAD_MINUTES, CustomHomework.clampLead(0));
        assertEquals(CustomHomework.MIN_LEAD_MINUTES, CustomHomework.clampLead(-30));
        assertEquals(60, CustomHomework.clampLead(60));
        assertEquals(CustomHomework.MAX_LEAD_MINUTES,
                CustomHomework.clampLead(CustomHomework.MAX_LEAD_MINUTES + 1));
    }

    @Test
    public void remindEpochSubtractsLeadFromDeadline() {
        CustomHomework item = homework("实验报告", 2026, 12, 1, 23, 59, 30);
        assertEquals(item.deadlineEpochMillis() - 30 * 60_000L, item.remindEpochMillis());
    }

    @Test
    public void leadWithoutDeadlineHasNoReminder() {
        CustomHomework item = new CustomHomework("id", "无截止时间", 0L, 30);
        assertEquals(0L, item.remindEpochMillis());
        assertTrue(HomeworkReminderPlanner.upcoming(Collections.singletonList(item),
                LocalDateTime.now(), HomeworkReminderPlanner.DEFAULT_HORIZON_DAYS).isEmpty());
    }

    @Test
    public void pastRemindPointIsNotScheduled() {
        // 截止在 10 分钟后、提前 30 分钟 → 提醒点已过去，不该再排。
        LocalDateTime now = LocalDateTime.of(2026, 11, 23, 20, 0);
        CustomHomework item = new CustomHomework("id", "马上截止",
                millisOf(now.plusMinutes(10)), 30);
        assertTrue(HomeworkReminderPlanner.upcoming(Collections.singletonList(item),
                now, 30).isEmpty());
    }

    @Test
    public void upcomingSortedByRemindPoint() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 23, 12, 0);
        CustomHomework later = homework("后交", 2026, 12, 10, 12, 0, 60);
        CustomHomework sooner = homework("先交", 2026, 11, 25, 8, 0, 60);
        List<HomeworkReminderPlanner.Reminder> reminders = HomeworkReminderPlanner.upcoming(
                Arrays.asList(later, sooner), now, 30);
        assertEquals(2, reminders.size());
        assertEquals("先交", reminders.get(0).homework.title());
        assertEquals("后交", reminders.get(1).homework.title());
    }

    @Test
    public void reminderBeyondHorizonIsIgnored() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 23, 12, 0);
        CustomHomework far = homework("下个月", 2027, 1, 20, 12, 0, 60);
        assertTrue(HomeworkReminderPlanner.upcoming(
                Collections.singletonList(far), now, 30).isEmpty());
        assertEquals(1, HomeworkReminderPlanner.upcoming(
                Collections.singletonList(far), now, 60).size());
    }

    @Test
    public void nextReturnsEarliest() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 23, 12, 0);
        CustomHomework later = homework("后交", 2026, 12, 10, 12, 0, 60);
        CustomHomework sooner = homework("先交", 2026, 11, 25, 8, 0, 60);
        HomeworkReminderPlanner.Reminder next = HomeworkReminderPlanner.next(
                Arrays.asList(later, sooner), now, 30);
        assertEquals("先交", next.homework.title());
        assertEquals(LocalDateTime.of(2026, 11, 25, 7, 0), next.remindAt);
    }

    @Test
    public void nextIsNullWhenNothingScheduled() {
        assertNull(HomeworkReminderPlanner.next(Collections.emptyList(),
                LocalDateTime.now(), 30));
    }

    @Test
    public void dueBetweenUsesClosedOpenWindow() {
        LocalDateTime from = LocalDateTime.of(2026, 11, 23, 20, 0);
        LocalDateTime to = from.plusMinutes(10);
        // 提醒点正好落在 from 上（闭区间）：截止 20:10、提前 10 分钟。
        CustomHomework onEdge = new CustomHomework("a", "整点提醒", millisOf(to), 10);
        // 提醒点 20:05：截止 20:06、提前 1 分钟。
        CustomHomework inside = new CustomHomework("b", "窗口内",
                millisOf(from.plusMinutes(6)), 1);
        // 提醒点 20:15，超出右开边界。
        CustomHomework after = new CustomHomework("c", "窗口后",
                millisOf(to.plusMinutes(15)), 1);
        List<HomeworkReminderPlanner.Reminder> due = HomeworkReminderPlanner.dueBetween(
                Arrays.asList(after, inside, onEdge), from, to);
        assertEquals(2, due.size());
        assertEquals("整点提醒", due.get(0).homework.title());
        assertEquals("窗口内", due.get(1).homework.title());
    }

    @Test
    public void emptyHorizonYieldsNothing() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 23, 12, 0);
        assertTrue(HomeworkReminderPlanner.upcoming(
                Collections.singletonList(homework("作业", 2026, 11, 24, 12, 0, 10)),
                now, 0).isEmpty());
    }

    private static long millisOf(LocalDateTime time) {
        return time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
}
