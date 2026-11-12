package org.localroute.app.study;

import org.json.JSONObject;
import org.junit.Test;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** 学习通页面结构解析：用合成 JSON/HTML 固定契约，避免真实账号依赖。 */
public class ChaoxingHtmlParserTest {

    @Test
    public void parsesCourseListAndSkipsArchived() throws Exception {
        String json = "{"
                + "\"result\":1,"
                + "\"channelList\":["
                + "{\"content\":{\"id\":\"class-1\",\"cpi\":\"cpi-1\",\"isretire\":0,"
                + "\"course\":{\"data\":[{\"id\":\"course-1\",\"name\":\" 高等数学 \","
                + "\"teacherfactor\":\"张老师\"}]}}},"
                + "{\"content\":{\"id\":\"class-2\",\"cpi\":\"cpi-2\",\"isretire\":1,"
                + "\"course\":{\"data\":[{\"id\":\"course-2\",\"name\":\"已归档课程\"}]}}},"
                + "{\"content\":{\"id\":\"\",\"cpi\":\"cpi-3\","
                + "\"course\":{\"data\":[{\"id\":\"course-3\",\"name\":\"缺少班级\"}]}}}"
                + "]}";
        List<ChaoxingCourse> courses = ChaoxingHtmlParser.parseCourseList(
                new JSONObject(json));
        assertEquals(1, courses.size());
        ChaoxingCourse course = courses.get(0);
        assertEquals("course-1", course.courseId);
        assertEquals("class-1", course.classId);
        assertEquals("cpi-1", course.cpi);
        assertEquals("高等数学", course.name);
        assertEquals("张老师", course.teacher);
        assertTrue(course.courseUrl().contains("courseid=course-1"));
    }

    @Test
    public void collectsCourseNamesIncludingArchived() throws Exception {
        String json = "{"
                + "\"result\":1,"
                + "\"channelList\":["
                + "{\"content\":{\"id\":\"class-1\",\"cpi\":\"cpi-1\",\"isretire\":1,"
                + "\"course\":{\"data\":[{\"id\":\"227917214\",\"name\":\" 计算机组成原理 \"}]}}},"
                + "{\"content\":{\"id\":\"class-2\",\"cpi\":\"cpi-2\",\"isretire\":0,"
                + "\"course\":{\"data\":[{\"id\":\"course-2\",\"name\":\"高等数学\"},"
                + "{\"id\":\"\",\"name\":\"缺少课程号\"}]}}}"
                + "]}";
        java.util.Map<String, String> names = ChaoxingHtmlParser.parseCourseNames(
                new JSONObject(json));
        assertEquals(2, names.size());
        assertEquals("计算机组成原理", names.get("227917214"));
        assertEquals("高等数学", names.get("course-2"));
        assertTrue(ChaoxingHtmlParser.parseCourseNames(null).isEmpty());
    }

    @Test
    public void extractsWorkEncFromInputOrUrl() {
        assertEquals("0123456789abcdef0123456789abcdef",
                ChaoxingHtmlParser.extractWorkEnc(
                        "<input type=\"hidden\" id=\"workEnc\" value=\"0123456789abcdef0123456789abcdef\">"));
        assertEquals("fedcba9876543210fedcba9876543210",
                ChaoxingHtmlParser.extractWorkEnc(
                        "<a href=\"/visit?enc=fedcba9876543210fedcba9876543210\">作业</a>"));
        assertNull(ChaoxingHtmlParser.extractWorkEnc("<div>没有入口</div>"));
        assertNull(ChaoxingHtmlParser.extractWorkEnc(null));
    }

    @Test
    public void parsesWorkItemsStatusesAndDeadlines() {
        long now = fixedNow();
        String html = "<ul>"
                + "<li data=\"https://mooc1.chaoxing.com/work/view?workId=1\">"
                + "<p class=\"overHidden2\">第一次作业</p>"
                + "<p class=\"status\">未交</p>"
                + "<div class=\"time notOver\">剩余2天3小时</div></li>"
                + "<li data=\"https://mooc1.chaoxing.com/work/view?workId=2\">"
                + "<p class=\"overHidden2\">已完成作业</p>"
                + "<p class=\"status\">已完成</p>"
                + "<div class=\"time\">2026-11-01 23:59</div></li>"
                + "<li data=\"https://mooc1.chaoxing.com/work/view?workId=3\">"
                + "<p class=\"overHidden2\">已结束作业</p>"
                + "<p class=\"status\">未交</p>"
                + "<div class=\"time\">已结束</div></li>"
                + "<li data=\"https://mooc1.chaoxing.com/work/view?workId=4\">"
                + "<p class=\"overHidden2\">互评作业</p>"
                + "<p class=\"status\">待互评</p>"
                + "<div class=\"time notOver\">剩余5小时</div>"
                + "<span>互评</span></li>"
                + "</ul>"
                + "<div id=\"page\"><ul><li>1</li><li>4</li></ul></div>";

        ChaoxingHtmlParser.WorkPage page = ChaoxingHtmlParser.parseWorkPage(
                html, "高等数学", now);
        assertEquals(4, page.totalPages);
        assertEquals(4, page.items.size());

        HomeworkItem first = page.items.get(0);
        assertEquals("高等数学", first.courseName());
        assertEquals("第一次作业", first.title());
        assertEquals(HomeworkItem.Status.PENDING, first.status());
        assertEquals(now + (2L * 24 + 3) * 60 * 60 * 1000, first.deadlineEpochMillis());

        HomeworkItem done = page.items.get(1);
        assertEquals(HomeworkItem.Status.DONE, done.status());
        assertTrue(done.deadlineEpochMillis() > 0L);

        assertEquals(HomeworkItem.Status.EXPIRED, page.items.get(2).status());

        HomeworkItem peer = page.items.get(3);
        assertEquals(HomeworkItem.Status.PENDING, peer.status());
        assertTrue(peer.peerReview());
    }

    @Test
    public void parsesRemainingDurations() {
        assertEquals(((2L * 24 + 3) * 60 + 5) * 60 * 1000,
                ChaoxingHtmlParser.parseRemainingMillis("剩余2天3小时5分钟"));
        assertEquals(90L * 60 * 1000,
                ChaoxingHtmlParser.parseRemainingMillis("剩余90分钟"));
        assertEquals(-1L, ChaoxingHtmlParser.parseRemainingMillis("已结束"));
        assertEquals(-1L, ChaoxingHtmlParser.parseRemainingMillis("不限"));
        assertEquals(-1L, ChaoxingHtmlParser.parseRemainingMillis(null));
    }

    @Test
    public void parsesAbsoluteDeadlines() {
        long now = fixedNow();
        long millis = ChaoxingHtmlParser.parseAbsoluteDeadline(
                "截止时间：2026-12-31 23:59", now);
        assertEquals("2026-12-31 23:59", format(millis));

        long shortDate = ChaoxingHtmlParser.parseAbsoluteDeadline("11-20 18:30", now);
        assertEquals("2026-11-20 18:30", format(shortDate));

        assertEquals(0L, ChaoxingHtmlParser.parseAbsoluteDeadline("2026-02-31 10:00", now));
        assertEquals(0L, ChaoxingHtmlParser.parseAbsoluteDeadline("没有时间", now));
    }

    @Test
    public void detectsLoginAndPagination() {
        assertTrue(ChaoxingHtmlParser.looksLikeLogin(
                "<form id=\"loginForm\"></form>", "https://passport2.chaoxing.com/login"));
        assertTrue(ChaoxingHtmlParser.looksLikeLogin("", "https://passport2.chaoxing.com/x"));
        assertFalse(ChaoxingHtmlParser.looksLikeLogin("<ul></ul>",
                "https://mooc1.chaoxing.com/mooc-ans/mooc2/work/list"));
        assertEquals(1, ChaoxingHtmlParser.parseTotalPages(null));
        assertEquals(1, ChaoxingHtmlParser.parseTotalPages("<div id=\"page\"><li>共 3 页</li></div>"));
        assertEquals(7, ChaoxingHtmlParser.parseTotalPages(
                "<div id=\"page\"><ul><li>1</li><li>7</li></ul></div>"));
    }

    @Test
    public void stripsTagsAndDecodesEntities() {
        assertEquals("A & B 作业", ChaoxingHtmlParser.stripTags(
                "<p>A &amp; B <span>作业</span></p>"));
        assertNotNull(ChaoxingHtmlParser.stripTags(null));
        assertEquals("", ChaoxingHtmlParser.stripTags(null));
    }

    @Test
    public void parsesAggregateWorkPage() {
        long now = fixedNow();
        String html = "<ul class=\"nav\">"
                + "<li data=\"https://mooc1.chaoxing.com/mooc-ans/mooc2/work/task"
                + "?courseId=1&amp;classId=2&amp;taskrefId=77\">"
                + "<div role=\"option\">"
                + "<p>第三次作业</p>"
                + "<div class=\"info\">"
                + "<span class=\"status\">未交</span>"
                + "<span class=\"course\">高等数学</span>"
                + "<span class=\"fr\">剩余 2天3小时</span>"
                + "</div></div></li>"
                + "<li data=\"https://mooc1.chaoxing.com/mooc-ans/mooc2/work/task?taskrefId=78\">"
                + "<div role=\"option\">"
                + "<p>过期作业</p>"
                + "<div class=\"info\">"
                + "<span class=\"status\">未交</span>"
                + "<span class=\"course\">大学英语</span>"
                + "<span class=\"fr\">已过期</span>"
                + "</div></div></li>"
                + "<li data=\"https://mooc1.chaoxing.com/mooc-ans/mooc2/work/task?taskrefId=79\">"
                + "<div role=\"option\">"
                + "<p>已完成作业</p>"
                + "<div class=\"info\">"
                + "<span class=\"status\">已完成</span>"
                + "<span class=\"course\">大学英语</span>"
                + "</div></div></li>"
                + "</ul>";

        List<HomeworkItem> items = ChaoxingHtmlParser.parseAggregateWorkPage(html, now);
        assertNotNull(items);
        assertEquals(3, items.size());

        HomeworkItem pending = items.get(0);
        assertEquals("第三次作业", pending.title());
        assertEquals("高等数学", pending.courseName());
        assertEquals("未交", pending.statusText());
        assertEquals(HomeworkItem.Status.PENDING, pending.status());
        assertEquals(now + (2L * 24 + 3) * 60 * 60 * 1000, pending.deadlineEpochMillis());
        assertEquals("高等数学|77", pending.key());

        assertEquals(HomeworkItem.Status.EXPIRED, items.get(1).status());
        assertEquals(0L, items.get(1).deadlineEpochMillis());
        assertEquals(HomeworkItem.Status.DONE, items.get(2).status());
    }

    @Test
    public void aggregatePageRequiresOptionMarkup() {
        assertNull(ChaoxingHtmlParser.parseAggregateWorkPage(
                "<form id=\"loginForm\"></form>", fixedNow()));
        assertNull(ChaoxingHtmlParser.parseAggregateWorkPage(null, fixedNow()));
        assertFalse(ChaoxingHtmlParser.looksLikeAggregatePage(
                "<div class=\"antispider\">请输入验证码</div>"));
        assertTrue(ChaoxingHtmlParser.looksLikeAggregatePage(
                "<div role='option'><p>作业</p></div>"));
        List<HomeworkItem> empty = ChaoxingHtmlParser.parseAggregateWorkPage(
                "<div role=\"option\"></div>", fixedNow());
        assertNotNull(empty);
        assertTrue(empty.isEmpty());
    }

    private static long fixedNow() {
        Calendar calendar = Calendar.getInstance();
        calendar.clear();
        calendar.set(2026, Calendar.NOVEMBER, 12, 10, 0, 0);
        return calendar.getTimeInMillis();
    }

    private static String format(long millis) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
                .format(new Date(millis));
    }
}
