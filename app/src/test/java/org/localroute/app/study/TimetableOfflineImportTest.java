package org.localroute.app.study;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** 离线导入的门面：格式自动分辨、周次合并、编码回退与失败口径。 */
public class TimetableOfflineImportTest {

    private static final String ZHENG_FANG_PAGE = "<table id=\"table1\">"
            + "<tr><td class=\"festival\">1</td>"
            + "<td id=\"1021314151617181\"><div><span class=\"title\">高等数学</span>"
            + "<p title=\"教师\">王涛</p><p title=\"上课地点\">逸夫楼101</p>"
            + "<p title=\"节/周\">1-2节 1-16周</p></div></td></tr>"
            + "<tr><td class=\"festival\">3</td><td></td>"
            + "<td id=\"206070809101112\"><div><span class=\"title\">大学英语</span>"
            + "<p title=\"教师\">李四</p><p title=\"上课地点\">外语楼203</p>"
            + "<p title=\"节/周\">(3-4节) 1-8周(单),9-16周(双)</p></div></td></tr>"
            + "</table>";

    @Test
    public void mergesWeekSegmentsThatShareParityIntoOneCourse() {
        TimetableOfflineImport.Result result = TimetableOfflineImport.parse(ZHENG_FANG_PAGE);

        assertTrue(result.ok());
        assertEquals(TimetableHtmlParser.VENDOR_ZHENG_FANG, result.source());
        assertEquals(3, result.courses().size());

        TimetableCourse maths = result.courses().get(0);
        assertEquals("高等数学", maths.name());
        assertEquals("逸夫楼101", maths.location());
        assertEquals(1, maths.weekday());
        assertEquals("1-16", maths.weeks());

        TimetableCourse englishOdd = result.courses().get(1);
        assertEquals("大学英语", englishOdd.name());
        assertEquals(3, englishOdd.startPeriod());
        assertEquals("1-8单", englishOdd.weeks());
        assertEquals("9-16双", result.courses().get(2).weeks());
    }

    @Test
    public void readsSevenColumnCsv() {
        String csv = "课程名称,星期,开始节数,结束节数,老师,地点,周数\n"
                + "高等数学,1,1,2,王涛,逸夫楼101,1-16\n"
                + "大学英语,5,3,4,李四,无,1-8、9-16双\n"
                + "有机化学,4,3,4,无,逸夫楼108,3\n";

        TimetableOfflineImport.Result result = TimetableOfflineImport.parse(csv);

        assertTrue(result.ok());
        assertEquals(TimetableOfflineImport.SOURCE_CSV, result.source());
        assertEquals(4, result.courses().size());

        TimetableCourse englishAll = result.courses().get(1);
        assertEquals(5, englishAll.weekday());
        assertEquals("", englishAll.location());
        // 「1-8」没写单双就是全周，「9-16双」另成一条，不会互相污染。
        assertEquals("1-8", englishAll.weeks());
        assertEquals("9-16双", result.courses().get(2).weeks());

        TimetableCourse chemistry = result.courses().get(3);
        assertEquals("3", chemistry.weeks());
        assertEquals("第3-4节", chemistry.periodLabel());
    }

    @Test
    public void keepsCommaInsideQuotedCsvField() {
        String csv = "课程名称,星期,开始节数,结束节数,老师,地点,周数\n"
                + "毛概,1,1,2,\"王老师,助教\",主楼101,1-16\n";

        TimetableOfflineImport.Result result = TimetableOfflineImport.parse(csv);

        assertTrue(result.ok());
        assertEquals(1, result.courses().size());
        assertEquals("王老师,助教", result.courses().get(0).teacher());
    }

    @Test
    public void recoversWeekRangeExcelTurnedIntoADate() {
        String csv = "课程名称,星期,开始节数,结束节数,老师,地点,周数\n"
                + "体育,2,1,2,教练,体育馆,1月12日\n";

        TimetableOfflineImport.Result result = TimetableOfflineImport.parse(csv);

        assertEquals("1-12", result.courses().get(0).weeks());
    }

    @Test
    public void skipsMalformedCsvLinesButKeepsTheRest() {
        String csv = "课程名称,星期,开始节数,结束节数,老师,地点,周数\n"
                + "少一列的课,1,1,2,老师,楼\n"
                + "星期是中文,二,1,2,老师,楼,1-16\n"
                + "正常课,3,5,6,老师,楼3,1-8\n";

        TimetableOfflineImport.Result result = TimetableOfflineImport.parse(csv);

        assertTrue(result.ok());
        assertEquals(1, result.courses().size());
        assertEquals("正常课", result.courses().get(0).name());
        assertEquals(5, result.courses().get(0).startPeriod());
    }

    @Test
    public void capsAbsurdCourseCountsInsteadOfFillingTheTimetable() {
        StringBuilder csv = new StringBuilder("课程名称,星期,开始节数,结束节数,老师,地点,周数\n");
        for (int i = 0; i < TimetableOfflineImport.MAX_COURSES + 50; i++) {
            csv.append("课程").append(i).append(",1,1,2,老师,楼,1-16\n");
        }

        TimetableOfflineImport.Result result = TimetableOfflineImport.parse(csv.toString());

        assertEquals(TimetableOfflineImport.MAX_COURSES, result.courses().size());
    }

    @Test
    public void reportsWhyNothingWasParsed() {
        assertFalse(TimetableOfflineImport.parse(null).ok());
        assertFalse(TimetableOfflineImport.parse("   ").ok());
        assertFalse(TimetableOfflineImport.parse("<html><body>没有课表表格</body></html>").ok());
        assertTrue(TimetableOfflineImport.parse("<table id=\"table1\"></table>").error()
                .contains("课表"));
        assertFalse(TimetableOfflineImport.parseBytes(new byte[0]).ok());
    }

    @Test
    public void honoursForcedVendorEvenWhenBothTablesExist() {
        String both = "<table id=\"table1\"></table>"
                + "<table id=\"kbtable\"><tr><td>第1-2节</td><td><div>"
                + "<div class=\"kbcontent\">强智课<br><span title=\"周次(节次)\">1-8(周)[1-2节]</span>"
                + "</div></div></td></tr></table>";

        TimetableOfflineImport.Result result = TimetableOfflineImport.parse(both,
                TimetableHtmlParser.VENDOR_QIANG_ZHI);

        assertTrue(result.ok());
        assertEquals(TimetableHtmlParser.VENDOR_QIANG_ZHI, result.source());
        assertEquals("强智课", result.courses().get(0).name());
    }

    @Test
    public void stripsUtf8BomBeforeParsing() {
        byte[] body = ZHENG_FANG_PAGE.getBytes(StandardCharsets.UTF_8);
        byte[] withBom = new byte[body.length + 3];
        withBom[0] = (byte) 0xEF;
        withBom[1] = (byte) 0xBB;
        withBom[2] = (byte) 0xBF;
        System.arraycopy(body, 0, withBom, 3, body.length);

        TimetableOfflineImport.Result result = TimetableOfflineImport.parseBytes(withBom);

        assertTrue(result.ok());
        assertEquals("高等数学", result.courses().get(0).name());
    }

    @Test
    public void fallsBackToGbkForChineseTeachingSystemSaves() {
        String csv = "课程名称,星期,开始节数,结束节数,老师,地点,周数\n"
                + "高等数学,1,1,2,王涛,逸夫楼101,1-16\n";
        byte[] gbk = csv.getBytes(Charset.forName("GBK"));

        TimetableOfflineImport.Result result = TimetableOfflineImport.parseBytes(gbk);

        // GB18030 的中文不是合法 UTF-8 序列，严格解码会失败并回退到 GBK。
        assertTrue(result.ok());
        assertEquals("高等数学", result.courses().get(0).name());
    }

    @Test
    public void labelsSourcesForTheUi() {
        TimetableOfflineImport.Result csv = TimetableOfflineImport.parse(
                "课程名称,星期,开始节数,结束节数,老师,地点,周数\n数学,1,1,2,王,楼,1-16\n");
        assertEquals("CSV 表格", csv.sourceLabel("正方", "强智", "CSV 表格"));
        TimetableOfflineImport.Result qz = TimetableOfflineImport.parse(
                "<table id=\"kbtable\"><tr><td>第1-2节</td><td><div><div class=\"kbcontent\">"
                        + "物理<br><span title=\"周次(节次)\">1-8(周)[1-2节]</span>"
                        + "</div></div></td></tr></table>");
        assertEquals("强智", qz.sourceLabel("正方", "强智", "CSV"));
    }

    @Test
    public void keepsCourseWithinStoredPeriodBounds() {
        List<TimetableImportRow> rows = List.of(
                new TimetableImportRow("超节次", "", "", 1, 1, 20, 1, 16,
                        TimetableImportRow.PARITY_ALL),
                new TimetableImportRow("坏星期", "", "", 9, 1, 2, 1, 16,
                        TimetableImportRow.PARITY_ALL),
                new TimetableImportRow("正常课", "", "", 2, 1, 2, 1, 16,
                        TimetableImportRow.PARITY_ALL));

        List<TimetableCourse> courses = TimetableOfflineImport.courses(rows);

        assertEquals(1, courses.size());
        assertEquals("正常课", courses.get(0).name());
    }

    @Test
    public void sameFileTwiceCountsAsAlreadyKnown() {
        List<TimetableCourse> first = TimetableOfflineImport.parse(ZHENG_FANG_PAGE).courses();
        List<TimetableCourse> again = TimetableOfflineImport.parse(ZHENG_FANG_PAGE).courses();

        // id 由课程内容算出来：重导同一份文件不会把课表再翻一倍。
        assertEquals(first.get(0).id(), again.get(0).id());
        TimetableImportPlan plan = TimetableImportPlan.of(again, first);
        assertEquals(first.size(), plan.duplicates());
        assertTrue(plan.toAdd().isEmpty());
    }
}
