package org.localroute.app.study;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/** 教务课表页 HTML 的结构识别：正方 #table1 与强智 #kbtable 两套格子表。 */
public class TimetableHtmlParserTest {

    private static final String ZHENG_FANG_PAGE = "<html><body><table id=\"table1\">"
            + "<tr><th>时间</th><th>周一</th><th>周二</th></tr>"
            + "<tr><td class=\"festival\">1</td>"
            + "<td id=\"1021314151617181\"><div><span class=\"title\">高等数学</span>"
            + "<p title=\"教师\">王涛</p><p title=\"上课地点\">逸夫楼101</p>"
            + "<p title=\"节/周\">1-2节 1-16周</p></div></td>"
            + "<td></td></tr>"
            + "<tr><td class=\"festival\">3</td><td></td>"
            + "<td id=\"206070809101112\"><div><span class=\"title\">大学英语</span>"
            + "<p title=\"教师\">李四</p><p title=\"上课地点\">外语楼203</p>"
            + "<p title=\"节/周\">(3-4节) 1-8周(单),9-16周(双)</p></div></td></tr>"
            + "</table></body></html>";

    private static final String QIANG_ZHI_PAGE = "<html><body><table id=\"kbtable\">"
            + "<tr><th>时间</th><th>星期一</th></tr>"
            + "<tr><td>第1-2节</td><td><div><div class=\"kbcontent\">"
            + "高等数学<br>"
            + "<font color=\"blue\"><span title=\"周次(节次)\">1-16(周)[1-2节]</span></font>"
            + "<span title=\"老师\">王涛</span>"
            + "<span title=\"教室\">主楼101</span>"
            + "</div></div></td></tr>"
            + "<tr><td>第3-4节</td><td><div><div class=\"kbcontent\">"
            + "数据结构<br>-----<span title=\"周次(节次)\">2-9(周)[3-4节]</span>"
            + "<span title=\"老师\">张老师</span><span title=\"教室\">主楼302</span>"
            + "</div></div></td></tr>"
            + "</table></body></html>";

    @Test
    public void detectsVendorFromTableId() {
        assertEquals(TimetableHtmlParser.VENDOR_ZHENG_FANG,
                TimetableHtmlParser.detectedVendor(ZHENG_FANG_PAGE));
        assertEquals(TimetableHtmlParser.VENDOR_QIANG_ZHI,
                TimetableHtmlParser.detectedVendor(QIANG_ZHI_PAGE));
        assertNull(TimetableHtmlParser.detectedVendor("<html><body><p>不是课表</p></body></html>"));
        assertNull(TimetableHtmlParser.detectedVendor(null));
        assertNull(TimetableHtmlParser.detectedVendor("   "));
    }

    @Test
    public void parsesZhengFangCellsIntoRows() {
        List<TimetableImportRow> rows =
                TimetableHtmlParser.rows(ZHENG_FANG_PAGE, TimetableHtmlParser.VENDOR_ZHENG_FANG);

        assertEquals(3, rows.size());
        TimetableImportRow maths = rows.get(0);
        assertEquals("高等数学", maths.name());
        assertEquals("王涛", maths.teacher());
        assertEquals("逸夫楼101", maths.location());
        assertEquals(1, maths.weekday());
        assertEquals(1, maths.startPeriod());
        assertEquals(2, maths.endPeriod());
        assertEquals(1, maths.startWeek());
        assertEquals(16, maths.endWeek());
        assertEquals(TimetableImportRow.PARITY_ALL, maths.parity());

        TimetableImportRow englishOdd = rows.get(1);
        assertEquals("大学英语", englishOdd.name());
        assertEquals(2, englishOdd.weekday());
        assertEquals(3, englishOdd.startPeriod());
        assertEquals(4, englishOdd.endPeriod());
        assertEquals(1, englishOdd.startWeek());
        assertEquals(8, englishOdd.endWeek());
        assertEquals(TimetableImportRow.PARITY_ODD, englishOdd.parity());
        assertEquals(TimetableImportRow.PARITY_EVEN, rows.get(2).parity());
        assertEquals(9, rows.get(2).startWeek());
    }

    @Test
    public void parsesQiangZhiCellsWithAndWithoutSeparators() {
        List<TimetableImportRow> rows =
                TimetableHtmlParser.rows(QIANG_ZHI_PAGE, TimetableHtmlParser.VENDOR_QIANG_ZHI);

        assertEquals(2, rows.size());
        TimetableImportRow maths = rows.get(0);
        assertEquals("高等数学", maths.name());
        assertEquals("王涛", maths.teacher());
        assertEquals("主楼101", maths.location());
        assertEquals(1, maths.weekday());
        // 节次写在 title 里，取它而不是行号推算。
        assertEquals(1, maths.startPeriod());
        assertEquals(2, maths.endPeriod());
        assertEquals(1, maths.startWeek());
        assertEquals(16, maths.endWeek());

        TimetableImportRow ds = rows.get(1);
        assertEquals("数据结构", ds.name());
        assertEquals("主楼302", ds.location());
        // 第二行「-----」只当字段分隔用，整格仍是一门课。
        assertEquals(3, ds.startPeriod());
        assertEquals(9, ds.endWeek());
    }

    @Test
    public void splitsCoursesSeparatedInsideOneQiangZhiCell() {
        String page = "<table id=\"kbtable\"><tr><td>第1-2节</td><td><div>"
                + "<div class=\"kbcontent\">体育<br><span title=\"周次(节次)\">1-8(周)[1-2节]</span>"
                + "<span title=\"老师\">教练</span><span title=\"教室\">体育馆</span><br>-----<br>"
                + "足球<br><span title=\"周次(节次)\">9-16(周)[3-4节]</span>"
                + "<span title=\"老师\">教练</span><span title=\"教室\">操场</span></div>"
                + "</div></td></tr></table>";

        List<TimetableImportRow> rows = TimetableHtmlParser.rows(page,
                TimetableHtmlParser.VENDOR_QIANG_ZHI);

        assertEquals(2, rows.size());
        assertEquals("体育", rows.get(0).name());
        assertEquals(1, rows.get(0).startWeek());
        assertEquals("足球", rows.get(1).name());
        assertEquals(3, rows.get(1).startPeriod());
        assertEquals(16, rows.get(1).endWeek());
    }

    @Test
    public void prefersRowLabelWhenCellDoesNotCarryPeriods() {
        String page = "<table id=\"kbtable\"><tr><td>第3-4节</td><td><div>"
                + "<div class=\"kbcontent\">体育<br><span title=\"周次(节次)\">1-12(周)</span>"
                + "<span title=\"老师\">教练</span><span title=\"教室\">体育馆</span></div>"
                + "</div></td></tr></table>";

        List<TimetableImportRow> rows = TimetableHtmlParser.rows(page,
                TimetableHtmlParser.VENDOR_QIANG_ZHI);

        assertEquals(1, rows.size());
        assertEquals(3, rows.get(0).startPeriod());
        assertEquals(4, rows.get(0).endPeriod());
        assertEquals(12, rows.get(0).endWeek());
    }

    @Test
    public void fallsBackToRowPositionWhenQiangZhiCellHasNoPeriodText() {
        String page = "<table id=\"kbtable\">"
                + "<tr><td></td><td><div><div class=\"kbcontent\">高等数学<br>"
                + "<span title=\"周次(节次)\">1-16(周)</span></div></div></td></tr>"
                + "<tr><td></td><td><div><div class=\"kbcontent\">大学英语<br>"
                + "<span title=\"周次(节次)\">1-16(周)</span></div></div></td></tr></table>";

        List<TimetableImportRow> rows = TimetableHtmlParser.rows(page,
                TimetableHtmlParser.VENDOR_QIANG_ZHI);

        assertEquals(2, rows.size());
        // 强智一格排两节：第一个课程行 1-2 节，第二个 3-4 节。
        assertEquals(1, rows.get(0).startPeriod());
        assertEquals(2, rows.get(0).endPeriod());
        assertEquals(3, rows.get(1).startPeriod());
        assertEquals(4, rows.get(1).endPeriod());
    }

    @Test
    public void mapsColumnsToWeekdayFromTheHeaderRow() {
        // 有的学校把行首的节次写成 th，课程全在 td 里，列位和星期只能照表头对。
        String page = "<table id=\"kbtable\">"
                + "<tr><th>时间</th><th>星期一</th><th>星期二</th></tr>"
                + "<tr><th>第1-2节</th>"
                + "<td><div class=\"kbcontent\">周一课<br>"
                + "<span title=\"周次(节次)\">1-8(周)[1-2节]</span></div></td>"
                + "<td><div class=\"kbcontent\">周二课<br>"
                + "<span title=\"周次(节次)\">1-8(周)[1-2节]</span></div></td></tr></table>";

        List<TimetableImportRow> rows = TimetableHtmlParser.rows(page,
                TimetableHtmlParser.VENDOR_QIANG_ZHI);

        assertEquals(2, rows.size());
        assertEquals("周一课", rows.get(0).name());
        assertEquals(1, rows.get(0).weekday());
        assertEquals("周二课", rows.get(1).name());
        assertEquals(2, rows.get(1).weekday());
    }

    @Test
    public void readsKbcontent1ClassUsedBySomeQiangZhiBuilds() {
        String page = "<table id=\"kbtable\"><tr><td>第1-2节</td><td><div>"
                + "<div class=\"kbcontent1\">线性代数<br><span title=\"周次(节次)\">1-8(周)[1-2节]</span>"
                + "<span title=\"老师\">赵</span><span title=\"教室\">B101</span></div>"
                + "</div></td></tr></table>";

        List<TimetableImportRow> rows = TimetableHtmlParser.rows(page,
                TimetableHtmlParser.VENDOR_QIANG_ZHI);

        assertEquals(1, rows.size());
        assertEquals("线性代数", rows.get(0).name());
        assertEquals("B101", rows.get(0).location());
    }

    @Test
    public void mergesGroupedLocationWithCourseGroupSuffix() {
        String page = "<table id=\"kbtable\"><tr><td>第1-2节</td><td><div>"
                + "<div class=\"kbcontent\">物理<br><span title=\"周次(节次)\">1-16(周)[1-2节]</span>"
                + "<span title=\"老师\">孙</span><span title=\"教室\">A101</span>"
                + "<span title=\"分组\">1班</span></div></div></td></tr></table>";

        List<TimetableImportRow> rows = TimetableHtmlParser.rows(page,
                TimetableHtmlParser.VENDOR_QIANG_ZHI);

        assertEquals("A101 1班", rows.get(0).location());
    }

    @Test
    public void ignoresRowsWithoutCourseContent() {
        String page = "<table id=\"table1\"><tr><td class=\"festival\">1</td>"
                + "<td id=\"1021\"><div><p title=\"节/周\">1-2节 1-16周</p></div></td>"
                + "</tr></table>";

        assertTrue(TimetableHtmlParser.rows(page, TimetableHtmlParser.VENDOR_ZHENG_FANG)
                .isEmpty());
    }

    @Test
    public void emptyOrUnknownHtmlYieldsNothing() {
        assertTrue(TimetableHtmlParser.rows(null, null).isEmpty());
        assertTrue(TimetableHtmlParser.rows("<html><body>空白</body></html>", null).isEmpty());
    }
}
