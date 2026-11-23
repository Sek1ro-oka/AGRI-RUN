package org.localroute.app.study;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** 课表导出的 iCalendar 文本：逐行断言，免得日历应用把课表读歪。 */
public class IcsTimetableWriterTest {

    /** 2026-09-07 是星期一，正好当学期第 1 周的起点。 */
    private static final LocalDate MONDAY = LocalDate.of(2026, 9, 7);
    private static final StudySchedule SCHEDULE = StudySchedule.winterDefault();
    /** 生成时刻：东八区 21:05:30 折成 UTC 应是 13:05:30Z。 */
    private static final ZonedDateTime MOMENT =
            ZonedDateTime.of(2026, 11, 24, 21, 5, 30, 0, ZoneId.of("Asia/Shanghai"));

    private static TimetableCourse course(String id, String name, int weekday,
                                          int start, int end, String weeks) {
        return new TimetableCourse(id, name, "张老师", "教一101", weekday, start, end, weeks);
    }

    private static List<TimetableCourse> list(TimetableCourse... courses) {
        return new ArrayList<>(Arrays.asList(courses));
    }

    private static String write(List<TimetableCourse> courses) {
        return IcsTimetableWriter.write(courses, SCHEDULE, MONDAY, 16, "本校课表", MOMENT);
    }

    @Test
    public void calendarEnvelopeIsCrlfSeparated() {
        String text = write(list(course("a", "高等数学", 1, 1, 2, "1-16")));
        assertTrue(text.startsWith("BEGIN:VCALENDAR\r\nVERSION:2.0\r\n"));
        assertTrue(text.contains("PRODID:-//UNLOOP//Route Workshop//CN\r\n"));
        assertTrue(text.contains("X-WR-CALNAME:本校课表\r\n"));
        assertTrue(text.endsWith("END:VCALENDAR\r\n"));
        // 裸 LF 会让严格的解析器报错：把 CRLF 收拢之后不该再留下独立的 \n。
        assertFalse(text.replace("\r\n", "|").indexOf('\n') >= 0);
    }

    @Test
    public void oneCourseBecomesOneWeeklyEventUntilTheLastActiveWeek() {
        String text = write(list(course("a", "高等数学", 1, 1, 2, "1-16")));
        assertTrue(text.contains("UID:unloop-a@localroute.org\r\n"));
        // 第 1 周星期一 08:00 起，两节连上到 09:35。
        assertTrue(text.contains("DTSTART:20260907T080000\r\n"));
        assertTrue(text.contains("DTEND:20260907T093500\r\n"));
        assertTrue(text.contains("SUMMARY:高等数学\r\n"));
        assertTrue(text.contains("LOCATION:教一101\r\n"));
        assertTrue(text.contains("RRULE:FREQ=WEEKLY;BYDAY=MO;COUNT=16\r\n"));
        assertFalse("整学期都有课，不该出现 EXDATE", text.contains("EXDATE:"));
        assertEquals("一门课只写一条事件", 1, count(text, "BEGIN:VEVENT"));
    }

    @Test
    public void contiguousWeeksNeedNoExceptions() {
        // 3~12 周连着上：重复次数盖住这 10 周即可，一节都不用排除。
        String text = write(list(course("b", "体育课", 1, 1, 1, "3-12")));
        assertTrue(text.contains("DTSTART:20260921T080000\r\n"));
        assertTrue(text.contains("COUNT=10\r\n"));
        assertFalse(text.contains("EXDATE:"));
    }

    @Test
    public void oddWeeksOnlyListTheEvenOnesAsExdates() {
        // 单周上课：第 2、4……14 周各被剔掉，共 7 次。
        String text = write(list(course("c", "形势与政策", 3, 1, 1, "1-15单")));
        assertTrue(text.contains("DTSTART:20260909T080000\r\n"));
        assertTrue(text.contains("COUNT=15\r\n"));
        String exdates = property(text, "EXDATE");
        assertTrue(exdates.startsWith("20260916T080000,20260930T080000"));
        assertTrue("最后一节落空的是第 14 周周三", exdates.endsWith(",20261209T080000"));
        assertEquals(6, count(exdates, ","));
    }

    @Test
    public void firstActiveWeekSetsTheStartEvenWhenWeekOneIsBlank() {
        // 双周课：首课在第 2 周星期五（09-18），第 1 周那天压根不出现。
        String text = write(list(course("d", "实验课", 5, 3, 4, "1-8双")));
        assertTrue(text.contains("DTSTART:20260918T095000\r\n"));
        assertTrue(text.contains("DTEND:20260918T112500\r\n"));
        assertTrue(text.contains("COUNT=7\r\n"));
        assertEquals("20260925T095000,20261009T095000,20261023T095000",
                property(text, "EXDATE"));
        assertFalse(text.contains("20260911T"));
    }

    @Test
    public void courseOutsideTermIsDropped() {
        String text = write(list(course("e", "新课", 1, 1, 1, "20-24")));
        assertEquals(0, count(text, "BEGIN:VEVENT"));
        assertTrue("日历本身仍然有效", text.contains("END:VCALENDAR"));
    }

    @Test
    public void courseStartingAfterTheLastPeriodIsDropped() {
        String text = write(list(course("f", "深夜课", 1, 40, 40, "1-16")));
        assertEquals(0, count(text, "BEGIN:VEVENT"));
    }

    @Test
    public void classTimesStayFloatingButDtstampIsUtc() {
        String text = write(list(course("g", "高等数学", 1, 1, 1, "1-2")));
        assertTrue(text.contains("DTSTAMP:20261124T130530Z\r\n"));
        assertFalse("事件时间不带 Z，按本校钟点理解",
                text.contains("DTSTART:20260907T080000Z"));
    }

    @Test
    public void uidIsStableAcrossExports() {
        TimetableCourse same = course("h", "高等数学", 1, 1, 1, "1-16");
        assertEquals("unloop-h@localroute.org", IcsTimetableWriter.uid(same));
        assertEquals(write(list(same)), write(list(same)));
    }

    @Test
    public void separatorsInsideCourseNamesAreEscaped() {
        String text = write(list(course("i", "高等数学, 英语; 强化\\班", 1, 1, 1, "1-16")));
        assertTrue(text.contains("SUMMARY:高等数学\\, 英语\\; 强化\\\\班\r\n"));
    }

    @Test
    public void blankCourseNameStillGetsASummary() {
        String text = write(list(course("j", "   ", 1, 1, 1, "1-16")));
        assertTrue(text.contains("SUMMARY:未命名课程\r\n"));
    }

    @Test
    public void descriptionCarriesPeriodsWeeksAndTeacher() {
        String detail = property(write(list(course("k", "高等数学", 1, 1, 2, "1-16"))),
                "DESCRIPTION");
        assertTrue(detail.contains("时间：08:00-09:35"));
        assertTrue(detail.contains("节次：第1-2节"));
        assertTrue(detail.contains("周次：1-16周"));
        assertTrue(detail.contains("教师：张老师"));
        assertTrue(detail.contains("来自 UNLOOP 课表导出"));
        // 说明里的换行得写成字面量反斜杠 n，否则一行会被断成两行。
        assertTrue(detail.contains("\\n"));
        assertFalse(detail.contains("\n"));
    }

    @Test
    public void singlePeriodCourseNamesOnePeriod() {
        String detail = property(write(list(course("l", "高等数学", 1, 2, 2, "1-16"))),
                "DESCRIPTION");
        assertTrue(detail.contains("节次：第2节"));
    }

    @Test
    public void missingTeacherAndRoomLeaveThoseLinesOut() {
        String text = write(list(new TimetableCourse("m", "自修", null, "", 1, 1, 1, "1-16")));
        assertFalse(text.contains("LOCATION:"));
        assertFalse(property(text, "DESCRIPTION").contains("教师："));
    }

    @Test
    public void everyLineFitsSeventyFiveOctets() {
        StringBuilder longName = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            longName.append('课');
        }
        String text = write(list(course("n", longName.toString(), 1, 1, 1, "1-16")));
        for (String line : text.split("\r\n", -1)) {
            assertTrue("折行失败：" + line,
                    line.getBytes(StandardCharsets.UTF_8).length <= 75);
        }
        // 折回来的值仍是完整的 40 个字，说明空格只是折行标记、不是内容。
        assertEquals(40, property(text, "SUMMARY").length());
    }

    @Test
    public void foldingKeepsMultiByteCharactersWhole() {
        StringBuilder raw = new StringBuilder("SUMMARY:");
        for (int i = 0; i < 40; i++) {
            raw.append('课');
        }
        List<String> lines = IcsTimetableWriter.fold(raw.toString());
        assertTrue("长行应被折成多行", lines.size() > 1);
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            assertTrue(line.getBytes(StandardCharsets.UTF_8).length <= 75);
            if (i > 0) {
                assertTrue("续行以一个空格开头", line.startsWith(" "));
                line = line.substring(1);
            }
            joined.append(line);
        }
        // 一个「课」3 字节，75 字节装不下第 25 个，所以绝不出现半个字符。
        assertEquals(raw.toString(), joined.toString());
    }

    @Test
    public void shortLinesAreNotFolded() {
        List<String> lines = IcsTimetableWriter.fold("BEGIN:VCALENDAR");
        assertEquals(1, lines.size());
        assertEquals("BEGIN:VCALENDAR", lines.get(0));
    }

    @Test
    public void activeWeeksStopsAtTermLength() {
        assertEquals(16, IcsTimetableWriter.activeWeeks("1-20", 16).size());
        assertEquals(0, IcsTimetableWriter.activeWeeks("20-24", 16).size());
        assertEquals(1, IcsTimetableWriter.activeWeeks("5", 16).size());
    }

    @Test
    public void weekdayDatesCountFromMonday() {
        assertEquals(LocalDate.of(2026, 9, 7), IcsTimetableWriter.dateOfWeekday(MONDAY, 1, 1));
        assertEquals(LocalDate.of(2026, 9, 13), IcsTimetableWriter.dateOfWeekday(MONDAY, 1, 7));
        assertEquals(LocalDate.of(2026, 9, 25), IcsTimetableWriter.dateOfWeekday(MONDAY, 3, 5));
        // 开学日落在周中时，仍按那一周的周一算第 1 周。
        assertEquals(LocalDate.of(2026, 9, 7),
                IcsTimetableWriter.dateOfWeekday(LocalDate.of(2026, 9, 9), 1, 1));
    }

    @Test
    public void timestampsAreLocalAndPadded() {
        assertEquals("20260907T080000", IcsTimetableWriter.timestamp(MONDAY, 8 * 60));
        assertEquals("20260907T235900", IcsTimetableWriter.timestamp(MONDAY, 23 * 60 + 59));
    }

    @Test
    public void utcStampConvertsOffTheDisplayedZone() {
        assertEquals("20261124T130530Z", IcsTimetableWriter.utcStamp(MOMENT));
        assertEquals("20260101T000000Z", IcsTimetableWriter.utcStamp(
                ZonedDateTime.of(2026, 1, 1, 8, 0, 0, 0, ZoneId.of("Asia/Shanghai"))));
    }

    @Test
    public void escapeOnlyRewritesTheSpecialCharacters() {
        assertEquals("普通课程名", IcsTimetableWriter.escape("普通课程名"));
        assertEquals("a\\,b\\;c\\\\d", IcsTimetableWriter.escape("a,b;c\\d"));
        assertEquals("a\\nb", IcsTimetableWriter.escape("a\nb"));
        assertEquals("a\\nb", IcsTimetableWriter.escape("a\r\nb"));
    }

    @Test
    public void multipleCoursesShareOneCalendar() {
        String text = write(list(course("o", "高等数学", 1, 1, 2, "1-16"),
                course("p", "大学英语", 2, 3, 4, "1-16")));
        assertEquals(2, count(text, "BEGIN:VEVENT"));
        assertEquals(1, count(text, "BEGIN:VCALENDAR"));
        assertEquals(1, count(text, "END:VCALENDAR"));
        assertTrue(text.contains("BYDAY=TU"));
    }

    /** 按 RFC 5545 把折行的物理行还原成一条逻辑值。 */
    private static String property(String text, String name) {
        StringBuilder value = null;
        for (String line : text.split("\r\n", -1)) {
            if (value == null) {
                if (line.startsWith(name + ":")) {
                    value = new StringBuilder(line.substring(name.length() + 1));
                }
            } else if (line.startsWith(" ")) {
                value.append(line.substring(1));
            } else {
                break;
            }
        }
        return value == null ? "" : value.toString();
    }

    @Test
    public void exportableMatchesTheEventsActuallyWritten() {
        List<TimetableCourse> courses = list(
                course("q", "高等数学", 1, 1, 2, "1-16"),
                course("r", "小学期", 2, 1, 1, "20-24"),
                course("s", "深夜课", 3, 40, 40, "1-16"));
        assertEquals(1, IcsTimetableWriter.exportable(courses, SCHEDULE, 16));
        String text = IcsTimetableWriter.write(courses, SCHEDULE, MONDAY, 16, "本校课表", MOMENT);
        assertEquals(IcsTimetableWriter.exportable(courses, SCHEDULE, 16),
                count(text, "BEGIN:VEVENT"));
    }

    @Test
    public void suggestedFileNameCarriesSemesterAndExportDay() {
        assertEquals("课表-202609起-20261124.ics",
                IcsTimetableWriter.suggestedFileName(MONDAY, LocalDate.of(2026, 11, 24)));
        assertTrue(IcsTimetableWriter.suggestedFileName(MONDAY, MONDAY).endsWith(".ics"));
    }

    private static int count(String text, String needle) {
        int found = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + needle.length())) {
            found++;
        }
        return found;
    }
}
