package org.localroute.app.study;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** 近一周作业的过滤与排序，以及用缓存补回截止时间。 */
public class HomeworkFilterTest {

    private static final long HOUR = 60L * 60L * 1000L;
    private static final long DAY = 24L * HOUR;
    private static final long NOW = 1_800_000_000_000L;

    @Test
    public void keepsUpcomingFirstThenRecentlyExpired() {
        HomeworkItem later = item("高数", "第二次作业", "https://x/work/view?workId=1",
                HomeworkItem.Status.PENDING, NOW + 2 * HOUR);
        HomeworkItem sooner = item("英语", "Unit 3", "https://x/work/view?workId=2",
                HomeworkItem.Status.PENDING, NOW + 30 * 60 * 1000L);
        HomeworkItem expiredRecently = item("物理", "实验报告", "https://x/work/view?workId=3",
                HomeworkItem.Status.EXPIRED, NOW - DAY);
        HomeworkItem expiredLongAgo = item("化学", "旧作业", "https://x/work/view?workId=4",
                HomeworkItem.Status.EXPIRED, NOW - 10 * DAY);
        HomeworkItem noDeadline = item("体育", "打卡", "https://x/work/view?workId=5",
                HomeworkItem.Status.PENDING, 0L);

        List<HomeworkItem> recent = HomeworkFilter.recent(
                Arrays.asList(later, sooner, expiredRecently, expiredLongAgo, noDeadline), NOW);

        assertEquals(3, recent.size());
        assertEquals("Unit 3", recent.get(0).title());
        assertEquals("第二次作业", recent.get(1).title());
        assertEquals("实验报告", recent.get(2).title());
    }

    @Test
    public void treatsJustExpiredAsRecent() {
        HomeworkItem border = item("高数", "边界", "https://x/work/view?workId=6",
                HomeworkItem.Status.EXPIRED, NOW - HomeworkFilter.RECENT_WINDOW_MS);
        List<HomeworkItem> recent = HomeworkFilter.recent(
                Collections.singletonList(border), NOW);
        assertEquals(1, recent.size());
    }

    @Test
    public void sortsSameDeadlineByCourseThenTitle() {
        HomeworkItem first = item("B 课程", "A 作业", "https://x/work/view?workId=7",
                HomeworkItem.Status.PENDING, NOW + HOUR);
        HomeworkItem second = item("A 课程", "Z 作业", "https://x/work/view?workId=8",
                HomeworkItem.Status.PENDING, NOW + HOUR);
        HomeworkItem third = item("A 课程", "A 作业", "https://x/work/view?workId=9",
                HomeworkItem.Status.PENDING, NOW + HOUR);

        List<HomeworkItem> recent = HomeworkFilter.recent(
                Arrays.asList(first, second, third), NOW);
        assertEquals("A 作业", recent.get(0).title());
        assertEquals("A 课程", recent.get(0).courseName());
        assertEquals("Z 作业", recent.get(1).title());
        assertEquals("A 作业", recent.get(2).title());
        assertEquals("B 课程", recent.get(2).courseName());
    }

    @Test
    public void mergesCachedDeadlineByWorkId() {
        HomeworkItem cached = item("高数", "第一次作业", "https://x/work/view?workId=42",
                HomeworkItem.Status.PENDING, NOW + DAY);
        // 截止后列表页移除时间块，只剩标题和链接。
        HomeworkItem fresh = item("高数", "第一次作业", "https://x/work/view?workId=42",
                HomeworkItem.Status.PENDING, 0L);
        HomeworkItem unrelated = item("高数", "新作业", "https://x/work/view?workId=43",
                HomeworkItem.Status.PENDING, 0L);

        List<HomeworkItem> merged = HomeworkFilter.mergeCachedDeadlines(
                Arrays.asList(fresh, unrelated), Collections.singletonList(cached));

        assertEquals(NOW + DAY, merged.get(0).deadlineEpochMillis());
        assertEquals(0L, merged.get(1).deadlineEpochMillis());
    }

    @Test
    public void keepsFreshDeadlineAndIgnoresCachedEntriesWithoutTime() {
        HomeworkItem cached = item("高数", "作业", "https://x/work/view?workId=44",
                HomeworkItem.Status.PENDING, NOW + DAY);
        HomeworkItem fresh = item("高数", "作业", "https://x/work/view?workId=44",
                HomeworkItem.Status.PENDING, NOW + 2 * DAY);

        List<HomeworkItem> merged = HomeworkFilter.mergeCachedDeadlines(
                Collections.singletonList(fresh), Collections.singletonList(cached));
        assertEquals(NOW + 2 * DAY, merged.get(0).deadlineEpochMillis());
    }

    @Test
    public void matchesByTitleWhenUrlHasNoWorkId() {
        HomeworkItem cached = item("高数", "课堂练习", "https://x/work/view?id=abc",
                HomeworkItem.Status.PENDING, NOW + DAY);
        HomeworkItem fresh = item("高数", "课堂练习", "https://x/work/view?id=abc",
                HomeworkItem.Status.PENDING, 0L);
        List<HomeworkItem> merged = HomeworkFilter.mergeCachedDeadlines(
                Collections.singletonList(fresh), Collections.singletonList(cached));
        assertTrue(merged.get(0).deadlineEpochMillis() > 0L);
    }

    private static HomeworkItem item(String course, String title, String url,
                                     HomeworkItem.Status status, long deadline) {
        return new HomeworkItem(course, title, "", status, deadline, "", url, false);
    }
}
