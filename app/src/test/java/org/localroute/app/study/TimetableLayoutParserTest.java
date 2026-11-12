package org.localroute.app.study;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** 用合成坐标固定正方课表 PDF 的还原规则。 */
public class TimetableLayoutParserTest {

    @Test
    public void parsesHeaderColumnsAndCourseBlocks() {
        List<PdfTextChunk> page = new ArrayList<>();
        addDays(page, 100f, 50f);
        // 左侧节次号应被忽略。
        page.add(chunk("1", 15f, 150f, 8f));
        page.add(chunk("高等数学", 50f, 150f, 10f));
        page.add(chunk("(1-2节)1-16周", 50f, 160f, 8f));
        page.add(chunk("/校区:本部/场地:教一101", 50f, 168f, 8f));
        page.add(chunk("/教师:张老师", 50f, 176f, 8f));

        page.add(chunk("大学英语", 150f, 200f, 10f));
        page.add(chunk("(3-4节)1-8周/校区:本部/场地:外语楼201/教师:李老师",
                150f, 210f, 8f));

        // 同列相隔较远的第二门课。
        page.add(chunk("体育★", 50f, 300f, 10f));
        page.add(chunk("(5-6节)1-16周/场地:操场/教师:王老师", 50f, 310f, 8f));

        TimetableLayoutParser.Result result = TimetableLayoutParser.parse(
                Collections.singletonList(page));

        assertEquals(3, result.courses.size());
        TimetableCourse math = find(result, "高等数学");
        assertEquals(1, math.weekday());
        assertEquals(1, math.startPeriod());
        assertEquals(2, math.endPeriod());
        assertEquals("1-16", math.weeks());
        assertEquals("教一101", math.location());
        assertEquals("张老师", math.teacher());

        TimetableCourse english = find(result, "大学英语");
        assertEquals(2, english.weekday());
        assertEquals(3, english.startPeriod());
        assertEquals(4, english.endPeriod());
        assertEquals("1-8", english.weeks());
        assertEquals("外语楼201", english.location());

        TimetableCourse sport = find(result, "体育");
        assertEquals(1, sport.weekday());
        assertEquals(5, sport.startPeriod());
        assertEquals("操场", sport.location());
        assertEquals("王老师", sport.teacher());
    }

    @Test
    public void usesPerPageHeaderWhenPagesDiffer() {
        List<PdfTextChunk> first = new ArrayList<>();
        addDays(first, 100f, 50f);
        first.add(chunk("高等数学", 50f, 150f, 10f));
        first.add(chunk("(1-2节)1-16周/场地:教一101/教师:张老师", 50f, 160f, 8f));

        // 第二页表头下移，若沿用第一页的 y 会把整页内容排除。
        List<PdfTextChunk> second = new ArrayList<>();
        addDays(second, 400f, 50f);
        second.add(chunk("线性代数", 250f, 450f, 10f));
        second.add(chunk("(7-8节)1-16周/场地:教二202/教师:赵老师", 250f, 460f, 8f));

        TimetableLayoutParser.Result result = TimetableLayoutParser.parse(
                Arrays.asList(first, second));
        assertEquals(2, result.courses.size());
        TimetableCourse algebra = find(result, "线性代数");
        assertEquals(3, algebra.weekday());
        assertEquals(7, algebra.startPeriod());
        assertEquals(8, algebra.endPeriod());
    }

    @Test
    public void deduplicatesSameCourseAcrossPages() {
        List<PdfTextChunk> page = new ArrayList<>();
        addDays(page, 100f, 50f);
        page.add(chunk("高等数学", 50f, 150f, 10f));
        page.add(chunk("(1-2节)1-16周/场地:教一101/教师:张老师", 50f, 160f, 8f));

        TimetableLayoutParser.Result result = TimetableLayoutParser.parse(
                Arrays.asList(page, new ArrayList<>(page)));
        assertEquals(1, result.courses.size());
    }

    @Test
    public void warnsWhenNoTextOrHeader() {
        TimetableLayoutParser.Result empty = TimetableLayoutParser.parse(
                Collections.singletonList(Collections.emptyList()));
        assertTrue(empty.courses.isEmpty());
        assertTrue(empty.warningSummary().contains("扫描件"));

        List<PdfTextChunk> noHeader = new ArrayList<>();
        noHeader.add(chunk("高等数学", 50f, 150f, 10f));
        TimetableLayoutParser.Result missing = TimetableLayoutParser.parse(
                Collections.singletonList(noHeader));
        assertTrue(missing.courses.isEmpty());
        assertTrue(missing.warningSummary().contains("表头"));
    }

    @Test
    public void skipsDetailOnlyBlockWithoutCourseName() {
        List<PdfTextChunk> page = new ArrayList<>();
        addDays(page, 100f, 50f);
        page.add(chunk("(1-2节)1-16周/场地:教一101", 50f, 150f, 8f));

        TimetableLayoutParser.Result result = TimetableLayoutParser.parse(
                Collections.singletonList(page));
        assertTrue(result.courses.isEmpty());
        assertTrue(result.warningSummary().contains("跳过"));
    }

    private static void addDays(List<PdfTextChunk> page, float y, float firstX) {
        page.add(chunk("星期一", firstX, y, 9f));
        page.add(chunk("星期二", firstX + 100f, y, 9f));
        page.add(chunk("星期三", firstX + 200f, y, 9f));
    }

    private static PdfTextChunk chunk(String text, float x, float y, float fontSize) {
        return new PdfTextChunk(text, x, y, text.length() * fontSize * 0.6f, fontSize);
    }

    private static TimetableCourse find(TimetableLayoutParser.Result result, String name) {
        for (TimetableCourse course : result.courses) {
            if (course.name().equals(name)) {
                return course;
            }
        }
        throw new AssertionError("没有解析到课程：" + name);
    }
}
