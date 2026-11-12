package org.localroute.app.study;

import org.junit.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;

/** 聚合页课程名截断后的补全：优先按 courseId 精确匹配，其次唯一前缀匹配。 */
public class CourseNameResolverTest {

    @Test
    public void resolvesByCourseIdEvenWhenNameIsTruncated() {
        Map<String, String> names = names(
                "227917214", "计算机组成原理",
                "153143929", "操作系统",
                "260934650", "数据库原理与技术");
        assertEquals("计算机组成原理", CourseNameResolver.resolve(
                "《计算机组成原...》", urlWithCourseId("227917214"), names));
        assertEquals("操作系统", CourseNameResolver.resolve(
                "《操作系统》", urlWithCourseId("153143929"), names));
        assertEquals("数据库原理与技术", CourseNameResolver.resolve(
                "《数据库原理与...》", urlWithCourseId("260934650"), names));
    }

    @Test
    public void courseIdWinsOverAmbiguousPrefix() {
        Map<String, String> names = names(
                "1", "计算机组成原理",
                "2", "计算机组成原理实验");
        assertEquals("计算机组成原理实验", CourseNameResolver.resolve(
                "《计算机组成原...》", urlWithCourseId("2"), names));
    }

    @Test
    public void fallsBackToUniquePrefixWithoutCourseId() {
        Map<String, String> names = names(
                "1", "计算机组成原理",
                "2", "操作系统");
        assertEquals("计算机组成原理", CourseNameResolver.resolve(
                "《计算机组成原...》", "https://mooc1-api.chaoxing.com/task?taskrefId=1", names));
        assertEquals("计算机组成原理", CourseNameResolver.resolve(
                "《计算机组成原...》", null, names));
        assertEquals("Advanced English", CourseNameResolver.resolve(
                "《Advanced Engli…》", null, names("3", "Advanced English")));
    }

    @Test
    public void keepsRawNameWhenAmbiguousOrUnknown() {
        Map<String, String> names = names(
                "1", "计算机组成原理",
                "2", "计算机组成原理实验");
        assertNull(CourseNameResolver.resolve("《计算机组成原...》", null, names));
        assertNull(CourseNameResolver.resolve(
                "《不存在的课程...》", null, names));
        assertNull(CourseNameResolver.resolve("《计...》", null, names));
        assertNull(CourseNameResolver.resolve(
                "《未知课程...》", urlWithCourseId("999"), names));
    }

    @Test
    public void leavesPlainNamesAlone() {
        assertNull(CourseNameResolver.resolve("高等数学", null, names("1", "高等数学")));
        assertNull(CourseNameResolver.resolve("高等数学", null, Collections.emptyMap()));
        assertFalse(CourseNameResolver.mayBeTruncated("高等数学"));
        assertFalse(CourseNameResolver.mayBeTruncated(""));
        assertFalse(CourseNameResolver.mayBeTruncated(null));
        assertNull(CourseNameResolver.resolve(null, null, Collections.emptyMap()));
    }

    @Test
    public void extractsCourseIdFromUrl() {
        assertEquals("227917214", CourseNameResolver.courseIdOf(
                "https://mooc1-api.chaoxing.com/mooc-ans/android/mtaskmsgspecial"
                        + "?taskrefId=58322795&courseId=227917214&clazzId=153637527"));
        assertEquals("42", CourseNameResolver.courseIdOf("https://x/task?courseid=42"));
        assertNull(CourseNameResolver.courseIdOf("https://x/task?taskrefId=1"));
        assertNull(CourseNameResolver.courseIdOf(null));
    }

    @Test
    public void normalizesQuotesAndEllipsis() {
        assertEquals("计算机组成原理", CourseNameResolver.normalize("《计算机组成原理...》"));
        assertEquals("计算机组成原理", CourseNameResolver.normalize("〈计算机组成原理…〉"));
        assertEquals("计算机组成原理", CourseNameResolver.normalize(" 计算机组成原理 "));
        assertNotNull(CourseNameResolver.normalize(null));
        assertEquals("", CourseNameResolver.normalize(null));
    }

    @Test
    public void replacesCourseNameOnItem() {
        HomeworkItem item = new HomeworkItem("《计算机组成原...》", "第三次作业",
                "未交", HomeworkItem.Status.PENDING, 0L, "", "", false);
        HomeworkItem renamed = item.withCourseName("计算机组成原理");
        assertEquals("计算机组成原理", renamed.courseName());
        assertEquals(item.title(), renamed.title());
        assertEquals(item.status(), renamed.status());
        assertEquals(item.deadlineEpochMillis(), renamed.deadlineEpochMillis());
        assertEquals(item.url(), renamed.url());
        assertEquals(item.peerReview(), renamed.peerReview());
        assertEquals("未标注截止时间", renamed.deadlineLabel());
    }

    private static String urlWithCourseId(String courseId) {
        return "https://mooc1-api.chaoxing.com/mooc-ans/android/mtaskmsgspecial"
                + "?taskrefId=1&courseId=" + courseId + "&clazzId=2";
    }

    private static Map<String, String> names(String... idsAndNames) {
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i + 1 < idsAndNames.length; i += 2) {
            result.put(idsAndNames[i], idsAndNames[i + 1]);
        }
        return result;
    }
}
