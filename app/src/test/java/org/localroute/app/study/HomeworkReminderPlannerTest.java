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

/** 作业截止提醒计划：全局提前量、提醒点推算、时间窗、排序与到期补发。 */
public class HomeworkReminderPlannerTest {

    private static long millisOf(int year, int month, int day, int hour, int minute) {
        return LocalDateTime.of(year, month, day, hour, minute)
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private static long millisOf(LocalDateTime time) {
        return time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private static CustomHomework homework(String title, int year, int month, int day,
                                           int hour, int minute) {
        return CustomHomework.create(title, millisOf(year, month, day, hour, minute));
    }

    @Test
    public void clampLeadBounds() {
        assertEquals(HomeworkReminderPlanner.MIN_LEAD_MINUTES,
                HomeworkReminderPlanner.clampLead(0));
        assertEquals(HomeworkReminderPlanner.MIN_LEAD_MINUTES,
                HomeworkReminderPlanner.clampLead(-30));
        assertEquals(60, HomeworkReminderPlanner.clampLead(60));
        assertEquals(HomeworkReminderPlanner.MAX_LEAD_MINUTES,
                HomeworkReminderPlanner.clampLead(HomeworkReminderPlanner.MAX_LEAD_MINUTES + 1));
    }

    @Test
    public void leadLabelReadsLikeHumanTime() {
        assertEquals("30 分钟", HomeworkReminderPlanner.leadLabel(30));
        assertEquals("2 小时", HomeworkReminderPlanner.leadLabel(120));
        assertEquals("1 天", HomeworkReminderPlanner.leadLabel(1440));
        assertEquals("90 分钟", HomeworkReminderPlanner.leadLabel(90));
        // 越界值先收进范围再描述，设置页不会显示「0 分钟」。
        assertEquals("1 分钟", HomeworkReminderPlanner.leadLabel(0));
    }

    @Test
    public void remindEpochSubtractsGlobalLeadFromDeadline() {
        CustomHomework item = homework("实验报告", 2026, 12, 1, 23, 59);
        assertEquals(item.deadlineEpochMillis() - 30 * 60_000L, item.remindEpochMillis(30));
        assertEquals(item.deadlineEpochMillis() - 1440 * 60_000L,
                item.remindEpochMillis(1440));
    }

    /** 一条设置影响全部作业：提前量变了，所有提醒点一起平移。 */
    @Test
    public void globalLeadMovesEveryReminder() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 23, 12, 0);
        List<CustomHomework> items = Arrays.asList(
                homework("先交", 2026, 11, 25, 8, 0),
                homework("后交", 2026, 11, 26, 8, 0));
        assertEquals(LocalDateTime.of(2026, 11, 25, 7, 0),
                HomeworkReminderPlanner.next(items, now, 60, 30).remindAt);
        assertEquals(LocalDateTime.of(2026, 11, 24, 8, 0),
                HomeworkReminderPlanner.next(items, now, 1440, 30).remindAt);
    }

    /** 提前量再大也不会越过截止时间本身：越界值先被收进 1 分钟–7 天。 */
    @Test
    public void leadIsClampedBeforeUse() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 23, 12, 0);
        List<CustomHomework> items = Collections.singletonList(
                homework("作业", 2026, 12, 24, 12, 0));
        assertEquals(LocalDateTime.of(2026, 12, 24, 11, 59),
                HomeworkReminderPlanner.next(items, now, 0, 60).remindAt);
        assertEquals(LocalDateTime.of(2026, 12, 17, 12, 0),
                HomeworkReminderPlanner.next(items, now, 999_999, 60).remindAt);
    }

    @Test
    public void homeworkWithoutDeadlineHasNoReminder() {
        CustomHomework item = new CustomHomework("id", "无截止时间", 0L);
        assertEquals(0L, item.remindEpochMillis(30));
        assertTrue(HomeworkReminderPlanner.upcoming(Collections.singletonList(item),
                LocalDateTime.now(), 30, HomeworkReminderPlanner.DEFAULT_HORIZON_DAYS).isEmpty());
    }

    @Test
    public void pastRemindPointIsNotScheduled() {
        // 截止在 10 分钟后、提前 30 分钟 → 提醒点已过去，不该再排。
        LocalDateTime now = LocalDateTime.of(2026, 11, 23, 20, 0);
        CustomHomework item = new CustomHomework("id", "马上截止",
                millisOf(now.plusMinutes(10)));
        assertTrue(HomeworkReminderPlanner.upcoming(Collections.singletonList(item),
                now, 30, 30).isEmpty());
        // 同一节课改成提前 5 分钟就有了。
        assertEquals(1, HomeworkReminderPlanner.upcoming(Collections.singletonList(item),
                now, 5, 30).size());
    }

    @Test
    public void upcomingSortedByRemindPoint() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 23, 12, 0);
        CustomHomework later = homework("后交", 2026, 12, 10, 12, 0);
        CustomHomework sooner = homework("先交", 2026, 11, 25, 8, 0);
        List<HomeworkReminderPlanner.Reminder> reminders = HomeworkReminderPlanner.upcoming(
                Arrays.asList(later, sooner), now, 60, 30);
        assertEquals(2, reminders.size());
        assertEquals("先交", reminders.get(0).homework.title());
        assertEquals("后交", reminders.get(1).homework.title());
    }

    @Test
    public void reminderBeyondHorizonIsIgnored() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 23, 12, 0);
        CustomHomework far = homework("下个月", 2027, 1, 20, 12, 0);
        assertTrue(HomeworkReminderPlanner.upcoming(
                Collections.singletonList(far), now, 60, 30).isEmpty());
        assertEquals(1, HomeworkReminderPlanner.upcoming(
                Collections.singletonList(far), now, 60, 60).size());
    }

    @Test
    public void nextReturnsEarliest() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 23, 12, 0);
        CustomHomework later = homework("后交", 2026, 12, 10, 12, 0);
        CustomHomework sooner = homework("先交", 2026, 11, 25, 8, 0);
        HomeworkReminderPlanner.Reminder next = HomeworkReminderPlanner.next(
                Arrays.asList(later, sooner), now, 60, 30);
        assertEquals("先交", next.homework.title());
        assertEquals(LocalDateTime.of(2026, 11, 25, 7, 0), next.remindAt);
    }

    @Test
    public void nextIsNullWhenNothingScheduled() {
        assertNull(HomeworkReminderPlanner.next(Collections.emptyList(),
                LocalDateTime.now(), 60, 30));
    }

    @Test
    public void dueBetweenUsesClosedOpenWindow() {
        LocalDateTime from = LocalDateTime.of(2026, 11, 23, 20, 0);
        LocalDateTime to = from.plusMinutes(10);
        List<CustomHomework> items = Arrays.asList(
                // 提醒点正好落在 from 上（闭区间）：截止 20:10、提前 10 分钟。
                new CustomHomework("a", "整点提醒", millisOf(to)),
                // 提醒点 20:05：截止 20:06、提前 1 分钟。
                new CustomHomework("b", "窗口内", millisOf(from.plusMinutes(6))),
                // 提醒点 20:15，超出右开边界。
                new CustomHomework("c", "窗口后", millisOf(to.plusMinutes(15))));
        List<HomeworkReminderPlanner.Reminder> due = HomeworkReminderPlanner.dueBetween(
                items, from, to, 10);
        assertEquals(1, due.size());
        assertEquals("整点提醒", due.get(0).homework.title());
        // 换成提前 1 分钟，三条里就有两条落进窗口。
        List<HomeworkReminderPlanner.Reminder> byOne = HomeworkReminderPlanner.dueBetween(
                items, from, to, 1);
        assertEquals(2, byOne.size());
        assertEquals("窗口内", byOne.get(0).homework.title());
        assertEquals("整点提醒", byOne.get(1).homework.title());
    }

    @Test
    public void emptyHorizonYieldsNothing() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 23, 12, 0);
        assertTrue(HomeworkReminderPlanner.upcoming(
                Collections.singletonList(homework("作业", 2026, 11, 24, 12, 0)),
                now, 10, 0).isEmpty());
    }
}
