package org.localroute.app.study;

import org.junit.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** 课前提醒计划：提醒时刻、周次过滤、节次越界与到期窗口。 */
public class ClassReminderPlannerTest {

    private static final LocalDate START = LocalDate.of(2026, 9, 7); // 周一，第 1 周
    private static final StudySchedule WINTER = StudySchedule.winterDefault();

    private static TimetableCourse course(String name, int weekday, int start, int end,
                                          String weeks) {
        return TimetableCourse.create(name, "", "一教101", weekday, start, end, weeks);
    }

    @Test
    public void clampLeadBounds() {
        assertEquals(ClassReminderPlanner.MIN_LEAD_MINUTES, ClassReminderPlanner.clampLead(0));
        assertEquals(ClassReminderPlanner.MIN_LEAD_MINUTES, ClassReminderPlanner.clampLead(-50));
        assertEquals(10, ClassReminderPlanner.clampLead(10));
        assertEquals(ClassReminderPlanner.MAX_LEAD_MINUTES, ClassReminderPlanner.clampLead(999));
    }

    @Test
    public void sameDayReminderUsesLeadMinutes() {
        // 周一 08:00 第一节，提前 10 分钟 → 07:50。
        LocalDateTime now = START.atTime(7, 0);
        List<ClassReminderPlanner.Reminder> reminders = ClassReminderPlanner.upcoming(
                Collections.singletonList(course("高数", 1, 1, 2, "1-16")),
                WINTER, START, 20, now, 10, 7);
        assertEquals(1, reminders.size());
        assertEquals(START.atTime(7, 50), reminders.get(0).remindAt);
        assertEquals(8 * 60, reminders.get(0).startMinutesOfDay);
        assertEquals("高数", reminders.get(0).course.name());
    }

    @Test
    public void pastStartTodayNotIncluded() {
        // 今天 07:50 的提醒点已过，不再补发；周二向后看 7 天，下周一才会再出现。
        LocalDateTime now = START.plusDays(1).atTime(0, 0);
        List<ClassReminderPlanner.Reminder> reminders = ClassReminderPlanner.upcoming(
                Collections.singletonList(course("高数", 1, 1, 1, "1-16")),
                WINTER, START, 20, now, 10, 7);
        assertEquals(1, reminders.size());
        assertEquals(START.plusDays(7).atTime(7, 50), reminders.get(0).remindAt);
    }

    @Test
    public void leadCrossingMidnightStillScanned() {
        // 极早作息（01:00 第一节）+ 提前 120 分钟 → 提醒点落在前一天 23:00。
        StudySchedule early = new StudySchedule(60, 600, 720, 45, 5, 5, 1, 1, 1);
        LocalDateTime now = START.atTime(22, 0); // 周一晚，课程在周二
        List<ClassReminderPlanner.Reminder> reminders = ClassReminderPlanner.upcoming(
                Collections.singletonList(course("早课", 2, 1, 1, "1-16")),
                early, START, 20, now, 120, 7);
        assertEquals(1, reminders.size());
        assertEquals(START.atTime(23, 0), reminders.get(0).remindAt);
        assertEquals(START.plusDays(1), reminders.get(0).day);
    }

    @Test
    public void oddEvenWeeksFiltered() {
        List<TimetableCourse> courses = Arrays.asList(
                course("单周课", 1, 1, 1, "1-16单"),
                course("双周课", 1, 1, 1, "1-16双"));
        // 周一在第 1 周（单周）：只有单周课；第 2 周只有双周课。
        List<ClassReminderPlanner.Reminder> weekOne = ClassReminderPlanner.upcoming(
                courses, WINTER, START, 20, START.atTime(7, 0), 10, 1);
        assertEquals(1, weekOne.size());
        assertEquals("单周课", weekOne.get(0).course.name());

        LocalDateTime weekTwoMorning = START.plusDays(7).atTime(7, 0);
        List<ClassReminderPlanner.Reminder> weekTwo = ClassReminderPlanner.upcoming(
                courses, WINTER, START, 20, weekTwoMorning, 10, 1);
        assertEquals(1, weekTwo.size());
        assertEquals("双周课", weekTwo.get(0).course.name());
    }

    @Test
    public void missingSemesterStartMeansNoReminders() {
        List<TimetableCourse> courses =
                Collections.singletonList(course("高数", 1, 1, 1, "1-16"));
        assertTrue(ClassReminderPlanner.upcoming(courses, WINTER, null, 20,
                START.atTime(7, 0), 10, 7).isEmpty());
        assertNull(ClassReminderPlanner.next(courses, WINTER, null, 20,
                START.atTime(7, 0), 10, 7));
    }

    @Test
    public void beforeSemesterStartSkipped() {
        // 开学前两周：teachingWeek < 1，全部跳过。
        LocalDateTime now = START.minusWeeks(2).atTime(7, 0);
        assertTrue(ClassReminderPlanner.upcoming(
                Collections.singletonList(course("高数", 1, 1, 1, "1-16")),
                WINTER, START, 20, now, 10, 7).isEmpty());
    }

    @Test
    public void periodBeyondScheduleSkipped() {
        StudySchedule early = new StudySchedule(60, 600, 720, 45, 5, 5, 1, 1, 1); // 共 3 节
        LocalDateTime now = START.atTime(0, 0);
        assertTrue(ClassReminderPlanner.upcoming(
                Collections.singletonList(course("第 5 节", 1, 5, 5, "1-16")),
                early, START, 20, now, 10, 7).isEmpty());
        assertEquals(1, ClassReminderPlanner.upcoming(
                Collections.singletonList(course("第 3 节", 1, 3, 3, "1-16")),
                early, START, 20, now, 10, 7).size());
    }

    @Test
    public void multipleCoursesSortedByTime() {
        List<TimetableCourse> courses = Arrays.asList(
                course("晚课", 1, 9, 10, "1-16"),
                course("高数", 1, 1, 2, "1-16"),
                course("英语", 2, 5, 6, "1-16"));
        LocalDateTime now = START.atTime(0, 0);
        List<ClassReminderPlanner.Reminder> reminders = ClassReminderPlanner.upcoming(
                courses, WINTER, START, 20, now, 10, 2);
        assertEquals(3, reminders.size());
        assertEquals("高数", reminders.get(0).course.name()); // 周一 08:00 → 07:50
        assertEquals("晚课", reminders.get(1).course.name()); // 周一晚段
        assertEquals("英语", reminders.get(2).course.name()); // 周二下午第一节
    }

    @Test
    public void dueBetweenWindowBounds() {
        LocalDateTime first = START.atTime(7, 50);
        List<TimetableCourse> courses =
                Collections.singletonList(course("高数", 1, 1, 1, "1-16"));
        // from 含、to 不含。
        assertEquals(1, ClassReminderPlanner.dueBetween(courses, WINTER, START, 20,
                first, first.plusMinutes(5), 10).size());
        assertTrue(ClassReminderPlanner.dueBetween(courses, WINTER, START, 20,
                first.plusMinutes(1), first.plusMinutes(5), 10).isEmpty());
        assertTrue(ClassReminderPlanner.dueBetween(courses, WINTER, START, 20,
                first.minusMinutes(30), first, 10).isEmpty());
    }

    @Test
    public void nextReturnsEarliest() {
        List<TimetableCourse> courses = Arrays.asList(
                course("英语", 2, 5, 6, "1-16"),
                course("高数", 1, 1, 2, "1-16"));
        ClassReminderPlanner.Reminder next = ClassReminderPlanner.next(
                courses, WINTER, START, 20, START.atTime(0, 0), 10, 7);
        assertEquals("高数", next.course.name());
    }
}
