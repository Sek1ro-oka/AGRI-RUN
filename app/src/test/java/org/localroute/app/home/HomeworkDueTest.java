package org.localroute.app.home;

import org.junit.Test;
import org.localroute.app.study.HomeworkItem;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** 待交作业清单：跨天升序、只留待交、没时刻的排最后。 */
public class HomeworkDueTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 25);

    private static long millisAt(LocalDate day, int hour, int minute) {
        return day.atTime(hour, minute).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private static HomeworkDue.Task task(String title, long deadline, HomeworkItem.Status status) {
        return new HomeworkDue.Task(title, "课程", deadline, status);
    }

    @Test public void sortsAcrossDaysByDeadline() {
        List<HomeworkDue.Task> tasks = Arrays.asList(
                task("后天交", millisAt(TODAY.plusDays(2), 9, 0), HomeworkItem.Status.PENDING),
                task("今天晚些", millisAt(TODAY, 23, 59), HomeworkItem.Status.PENDING),
                task("明天早", millisAt(TODAY.plusDays(1), 8, 0), HomeworkItem.Status.PENDING));

        List<HomeworkDue.Item> items = HomeworkDue.upcoming(tasks, TODAY, HomeworkDue.HORIZON_DAYS);

        assertEquals(3, items.size());
        assertEquals("今天晚些", items.get(0).title());
        assertEquals(0, items.get(0).dayOffset());
        assertEquals("23:59", items.get(0).timeLabel());
        assertEquals("明天早", items.get(1).title());
        assertEquals(1, items.get(1).dayOffset());
        assertEquals("08:00", items.get(1).timeLabel());
        assertEquals(2, items.get(2).dayOffset());
    }

    @Test public void sameDaySortsByTimeThenTitle() {
        List<HomeworkDue.Task> tasks = Arrays.asList(
                task("作业B", millisAt(TODAY.plusDays(1), 10, 0), HomeworkItem.Status.PENDING),
                task("作业A", millisAt(TODAY.plusDays(1), 10, 0), HomeworkItem.Status.PENDING),
                task("作业早", millisAt(TODAY.plusDays(1), 9, 0), HomeworkItem.Status.PENDING));

        List<HomeworkDue.Item> items = HomeworkDue.upcoming(tasks, TODAY, HomeworkDue.HORIZON_DAYS);

        assertEquals("作业早", items.get(0).title());
        assertEquals("作业A", items.get(1).title());
        assertEquals("作业B", items.get(2).title());
    }

    @Test public void submittedAndExpiredAreDropped() {
        List<HomeworkDue.Task> tasks = Arrays.asList(
                task("已交", millisAt(TODAY, 12, 0), HomeworkItem.Status.SUBMITTED),
                task("已完成", millisAt(TODAY, 12, 0), HomeworkItem.Status.DONE),
                task("已截止", millisAt(TODAY, 12, 0), HomeworkItem.Status.EXPIRED),
                task("待交", millisAt(TODAY, 12, 0), HomeworkItem.Status.PENDING));

        List<HomeworkDue.Item> items = HomeworkDue.upcoming(tasks, TODAY, HomeworkDue.HORIZON_DAYS);

        assertEquals(1, items.size());
        assertEquals("待交", items.get(0).title());
    }

    @Test public void pastDeadlinesAreDroppedButTodayCounts() {
        List<HomeworkDue.Task> tasks = Arrays.asList(
                task("昨天交", millisAt(TODAY.minusDays(1), 23, 0), HomeworkItem.Status.PENDING),
                task("今天交", millisAt(TODAY, 8, 0), HomeworkItem.Status.PENDING));

        List<HomeworkDue.Item> items = HomeworkDue.upcoming(tasks, TODAY, HomeworkDue.HORIZON_DAYS);

        assertEquals(1, items.size());
        assertEquals("今天交", items.get(0).title());
    }

    @Test public void horizonExcludesFarFuture() {
        List<HomeworkDue.Task> tasks = Collections.singletonList(
                task("第七天", millisAt(TODAY.plusDays(7), 9, 0), HomeworkItem.Status.PENDING));

        List<HomeworkDue.Item> items = HomeworkDue.upcoming(tasks, TODAY, HomeworkDue.HORIZON_DAYS);

        assertTrue(items.isEmpty());
    }

    @Test public void undatedGoesLastWithoutInventedTime() {
        List<HomeworkDue.Task> tasks = Arrays.asList(
                task("没时刻", 0L, HomeworkItem.Status.PENDING),
                task("有时刻", millisAt(TODAY.plusDays(1), 9, 0), HomeworkItem.Status.PENDING));

        List<HomeworkDue.Item> items = HomeworkDue.upcoming(tasks, TODAY, HomeworkDue.HORIZON_DAYS);

        assertEquals(2, items.size());
        assertEquals("有时刻", items.get(0).title());
        assertTrue(items.get(1).undated());
        assertNull(items.get(1).timeLabel());
        assertEquals(-1, items.get(1).dayOffset());
    }

    @Test public void submittedUndatedIsDropped() {
        List<HomeworkDue.Task> tasks = Collections.singletonList(
                task("已交没时刻", 0L, HomeworkItem.Status.SUBMITTED));

        assertTrue(HomeworkDue.upcoming(tasks, TODAY, HomeworkDue.HORIZON_DAYS).isEmpty());
    }
}