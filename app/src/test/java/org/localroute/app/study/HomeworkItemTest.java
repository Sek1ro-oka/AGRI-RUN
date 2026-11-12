package org.localroute.app.study;

import org.json.JSONObject;
import org.junit.Test;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** 单条作业的状态排序、截止时间显示与缓存序列化。 */
public class HomeworkItemTest {

    @Test
    public void ranksPendingBeforeDoneAndExpired() {
        HomeworkItem pending = item(HomeworkItem.Status.PENDING);
        HomeworkItem done = item(HomeworkItem.Status.DONE);
        HomeworkItem expired = item(HomeworkItem.Status.EXPIRED);
        HomeworkItem other = item(HomeworkItem.Status.OTHER);
        assertTrue(pending.statusRank() < done.statusRank());
        assertTrue(done.statusRank() < expired.statusRank());
        assertTrue(expired.statusRank() < other.statusRank());
    }

    @Test
    public void labelsDeadlineWithFallbacks() {
        assertEquals("未标注截止时间", item(HomeworkItem.Status.PENDING).deadlineLabel());
        assertEquals("已截止", item(HomeworkItem.Status.EXPIRED).deadlineLabel());
        assertEquals("已完成", item(HomeworkItem.Status.DONE).deadlineLabel());

        Calendar calendar = Calendar.getInstance();
        calendar.clear();
        calendar.set(2026, Calendar.NOVEMBER, 20, 18, 30, 0);
        long deadline = calendar.getTimeInMillis();
        HomeworkItem dated = new HomeworkItem("高数", "作业", "未交",
                HomeworkItem.Status.PENDING, deadline, "", "", false);
        String expected = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
                .format(new Date(deadline)) + " 截止";
        assertEquals(expected, dated.deadlineLabel());
    }

    @Test
    public void roundTripsThroughJson() throws Exception {
        HomeworkItem original = new HomeworkItem("大学英语", "Unit 3 作文",
                "待互评", HomeworkItem.Status.PENDING, 1_800_000_000_000L,
                "剩余2天", "https://example.com/work/1", true);
        JSONObject json = original.toJson();
        HomeworkItem decoded = HomeworkItem.fromJson(json);
        assertNotNull(decoded);
        assertEquals(original.courseName(), decoded.courseName());
        assertEquals(original.title(), decoded.title());
        assertEquals(original.statusText(), decoded.statusText());
        assertEquals(original.status(), decoded.status());
        assertEquals(original.deadlineEpochMillis(), decoded.deadlineEpochMillis());
        assertEquals(original.deadlineRaw(), decoded.deadlineRaw());
        assertEquals(original.url(), decoded.url());
        assertTrue(decoded.peerReview());
    }

    @Test
    public void rejectsJsonWithoutTitle() {
        assertEquals(null, HomeworkItem.fromJson(null));
        assertEquals(null, HomeworkItem.fromJson(new JSONObject()));
    }

    @Test
    public void defaultsUnknownStatusToOther() throws Exception {
        JSONObject json = new JSONObject();
        json.put("title", "作业");
        json.put("status", "SOMETHING_NEW");
        HomeworkItem decoded = HomeworkItem.fromJson(json);
        assertNotNull(decoded);
        assertEquals(HomeworkItem.Status.OTHER, decoded.status());
        assertFalse(decoded.peerReview());
    }

    private static HomeworkItem item(HomeworkItem.Status status) {
        return new HomeworkItem("课程", "作业", "", status, 0L, "", "", false);
    }
}
