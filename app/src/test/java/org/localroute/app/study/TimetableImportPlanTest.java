package org.localroute.app.study;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** 分享码导入前的冲突预演：哪些课会撞进本机已有的那一节。 */
public class TimetableImportPlanTest {

    private static TimetableCourse course(String id, String name, int weekday, int start,
                                          int end, String weeks) {
        return new TimetableCourse(id, name, null, null, weekday, start, end, weeks);
    }

    private static List<TimetableCourse> list(TimetableCourse... courses) {
        return new ArrayList<>(Arrays.asList(courses));
    }

    @Test
    public void sameDayAndOverlappingPeriodsCollide() {
        TimetableCourse local = course("a", "高等数学", 1, 1, 2, "1-16");
        TimetableCourse incoming = course("b", "大学英语", 1, 2, 3, "1-16");
        assertTrue(TimetableImportPlan.overlaps(local, incoming));
        assertTrue(TimetableImportPlan.overlaps(incoming, local));
    }

    @Test
    public void backToBackPeriodsDoNotCollide() {
        // 第 1-2 节与第 3-4 节只是紧挨着，中间没有共占。
        assertFalse(TimetableImportPlan.overlaps(
                course("a", "高等数学", 1, 1, 2, "1-16"),
                course("b", "大学英语", 1, 3, 4, "1-16")));
    }

    @Test
    public void differentDayNeverCollides() {
        assertFalse(TimetableImportPlan.overlaps(
                course("a", "高等数学", 1, 1, 2, "1-16"),
                course("b", "大学英语", 2, 1, 2, "1-16")));
    }

    @Test
    public void disjointWeeksDoNotCollide() {
        // 同一节次但单双周错开，日历上不会真撞车。
        assertFalse(TimetableImportPlan.overlaps(
                course("a", "体育", 3, 1, 2, "1-16单"),
                course("b", "实验", 3, 1, 2, "1-16双")));
        assertTrue("第 1 周与整学期仍有重合", TimetableImportPlan.overlaps(
                course("a", "体育", 3, 1, 2, "1-8单"),
                course("b", "实验", 3, 1, 2, "1-8")));
    }

    @Test
    public void unreadableWeeksAreTreatedAsEveryWeek() {
        // 宁可多提醒一次：写法看不懂时按「每周」算，别把冲突漏掉。
        assertTrue(TimetableImportPlan.weeksCollide("每周都有课", "1-8"));
        assertTrue(TimetableImportPlan.weeksCollide(null, "5"));
    }

    @Test
    public void planSplitsAddsFromDuplicates() {
        TimetableCourse same = course("a", "高等数学", 1, 1, 2, "1-16");
        TimetableCourse fresh = course("b", "大学英语", 2, 3, 4, "1-16");
        TimetableImportPlan plan = TimetableImportPlan.of(list(same, fresh), list(same));
        assertEquals(1, plan.duplicates());
        assertEquals(1, plan.toAdd().size());
        assertEquals("b", plan.toAdd().get(0).id());
        assertFalse("本机没有第二门周二的课", plan.hasConflicts());
    }

    @Test
    public void conflictsNameBothCoursesAndTheSlot() {
        TimetableImportPlan plan = TimetableImportPlan.of(
                list(course("b", "大学英语", 1, 2, 3, "1-16")),
                list(course("a", "高等数学", 1, 1, 2, "1-16")));
        assertTrue(plan.hasConflicts());
        assertEquals(1, plan.conflicts().size());
        assertEquals("周一 第2节：高等数学 与 大学英语", plan.conflicts().get(0));
    }

    @Test
    public void oneCourseCanClashWithSeveralLocalCourses() {
        TimetableImportPlan plan = TimetableImportPlan.of(
                list(course("c", "选修", 1, 1, 4, "1-16")),
                list(course("a", "高等数学", 1, 1, 2, "1-16"),
                        course("b", "线性代数", 1, 3, 4, "1-16")));
        assertEquals(2, plan.conflicts().size());
        assertTrue(plan.conflicts().get(1).contains("线性代数"));
    }

    @Test
    public void blankLocalTimetableHasNothingToClash() {
        TimetableImportPlan plan = TimetableImportPlan.of(
                list(course("a", "高等数学", 1, 1, 2, "1-16")), list());
        assertEquals(1, plan.toAdd().size());
        assertEquals(0, plan.duplicates());
        assertFalse(plan.hasConflicts());
        assertTrue(plan.conflicts().isEmpty());
    }

    @Test
    public void appendKeepsLocalOrderAndSkipsSameId() {
        TimetableCourse local = course("a", "高等数学", 1, 1, 2, "1-16");
        TimetableCourse twin = course("a", "改名了", 1, 1, 2, "1-16");
        TimetableCourse fresh = course("b", "大学英语", 2, 1, 2, "1-16");
        List<TimetableCourse> merged = TimetableImportPlan.appendTo(list(local), list(twin, fresh));
        assertEquals(2, merged.size());
        assertEquals("高等数学", merged.get(0).name());
        assertEquals("b", merged.get(1).id());
    }

    @Test
    public void appendIgnoresCoursesTheStoreWouldReject() {
        List<TimetableCourse> merged = TimetableImportPlan.appendTo(list(),
                list(new TimetableCourse("x", "", null, null, 9, 1, 1, "1-16")));
        assertTrue(merged.isEmpty());
    }
}
