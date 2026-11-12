package org.localroute.app.study;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** 正方教务课表 JSON 的字段映射与容错。 */
public class JwTimetableParserTest {

    @Test
    public void mapsCommonKbListFields() throws Exception {
        JSONArray kbList = new JSONArray();
        kbList.put(new JSONObject()
                .put("kcmc", "高等数学")
                .put("xm", "王老师")
                .put("cdmc", "教1-101")
                .put("xqj", 1)
                .put("jc", "1-2")
                .put("zcd", "1-16周"));

        JwTimetableParser.Result result =
                JwTimetableParser.parse(wrap(kbList).toString());

        assertEquals(1, result.rawCount());
        assertEquals(1, result.courses().size());
        TimetableCourse course = result.courses().get(0);
        assertEquals("高等数学", course.name());
        assertEquals("王老师", course.teacher());
        assertEquals("教1-101", course.location());
        assertEquals(1, course.weekday());
        assertEquals(1, course.startPeriod());
        assertEquals(2, course.endPeriod());
        assertEquals("1-16", course.weeks());
        assertTrue(course.isValid());
    }

    @Test
    public void splitsDiscontinuousSections() throws Exception {
        JSONArray kbList = new JSONArray();
        kbList.put(new JSONObject()
                .put("kcmc", "大学物理")
                .put("xqj", 3)
                .put("jc", "3-4,6-7")
                .put("zcd", "1-8周"));

        List<TimetableCourse> courses = JwTimetableParser.parse(kbList.toString()).courses();

        assertEquals(2, courses.size());
        assertEquals(3, courses.get(0).startPeriod());
        assertEquals(4, courses.get(0).endPeriod());
        assertEquals(6, courses.get(1).startPeriod());
        assertEquals(7, courses.get(1).endPeriod());
    }

    @Test
    public void keepsOddAndEvenWeeks() throws Exception {
        JSONArray kbList = new JSONArray();
        kbList.put(new JSONObject()
                .put("kcmc", "单周课")
                .put("xqj", 2)
                .put("jc", "1-2")
                .put("zcd", "1-16周(单)"));
        kbList.put(new JSONObject()
                .put("kcmc", "双周课")
                .put("xqj", 2)
                .put("jc", "3-4")
                .put("zcd", "1-16周（双）"));

        List<TimetableCourse> courses = JwTimetableParser.parse(kbList.toString()).courses();

        assertEquals(2, courses.size());
        assertEquals("1-16单", courses.get(0).weeks());
        assertEquals("1-16双", courses.get(1).weeks());
    }

    @Test
    public void supportsChineseSectionAndWeekdayText() throws Exception {
        JSONArray kbList = new JSONArray();
        kbList.put(new JSONObject()
                .put("kcmc", "体育")
                .put("xqjmc", "星期四")
                .put("jc", "第3-5节")
                .put("zcd", "第1-12周"));

        List<TimetableCourse> courses = JwTimetableParser.parse(kbList.toString()).courses();

        assertEquals(1, courses.size());
        assertEquals(4, courses.get(0).weekday());
        assertEquals(3, courses.get(0).startPeriod());
        assertEquals(5, courses.get(0).endPeriod());
        assertEquals("1-12", courses.get(0).weeks());
    }

    @Test
    public void acceptsCompactSectionsAndMergesDuplicates() throws Exception {
        JSONArray kbList = new JSONArray();
        JSONObject item = new JSONObject()
                .put("kcmc", "英语")
                .put("xqj", 5)
                .put("jc", "0102")
                .put("zcd", "1-16");
        kbList.put(item);
        kbList.put(new JSONObject(item.toString()));

        List<TimetableCourse> courses = JwTimetableParser.parse(kbList.toString()).courses();

        assertEquals(1, courses.size());
        assertEquals(1, courses.get(0).startPeriod());
        assertEquals(2, courses.get(0).endPeriod());
    }

    @Test
    public void skipsInvalidEntries() throws Exception {
        JSONArray kbList = new JSONArray();
        kbList.put(new JSONObject().put("xqj", 1).put("jc", "1-2"));
        kbList.put(new JSONObject().put("kcmc", "无星期").put("jc", "1-2"));
        kbList.put(new JSONObject().put("kcmc", "节次越界").put("xqj", 1).put("jc", "16-17"));
        kbList.put(new JSONObject().put("kcmc", "正常课").put("xqj", 6).put("jc", "9-10"));

        JwTimetableParser.Result result =
                JwTimetableParser.parse(kbList.toString());

        assertEquals(4, result.rawCount());
        assertEquals(1, result.courses().size());
        assertEquals("正常课", result.courses().get(0).name());
    }

    @Test
    public void parsesNestedEnvelope() throws Exception {
        JSONObject inner = new JSONObject()
                .put("kcmc", "线性代数")
                .put("xqj", 3)
                .put("jc", "5-6");
        JSONObject payload = new JSONObject()
                .put("data", new JSONObject().put("kbList", new JSONArray().put(inner)));

        JwTimetableParser.Result result =
                JwTimetableParser.parse(payload.toString());

        assertEquals(1, result.courses().size());
        assertEquals("线性代数", result.courses().get(0).name());
    }

    @Test
    public void handlesEmptyAndMalformedInput() {
        assertEquals(0, JwTimetableParser.parse(null).courses().size());
        assertEquals(0, JwTimetableParser.parse("").courses().size());
        assertEquals(0, JwTimetableParser.parse("not json").courses().size());
        assertEquals(0, JwTimetableParser.parse("{}").courses().size());
    }

    @Test
    public void formatsTermLabel() {
        assertEquals("2026-2027 学年第一学期",
                JwTimetableParser.termLabel("2026", "3"));
        assertEquals("2026-2027 学年第二学期",
                JwTimetableParser.termLabel("2026", "12"));
        assertEquals("2025-2026 学年第三学期",
                JwTimetableParser.termLabel("2025", "16"));
        assertEquals("教务系统课表", JwTimetableParser.termLabel(null, null));
        assertEquals("2026-2027 学年学期 1", JwTimetableParser.termLabel("2026", "1"));
    }

    @Test
    public void parsesSectionAndWeekHelpers() {
        assertEquals("1-16", JwTimetableParser.parseWeeks(null));
        assertEquals("1-16", JwTimetableParser.parseWeeks("1-16周"));
        assertEquals("1-16", JwTimetableParser.parseWeeks("周次未知"));
        List<int[]> ranges = JwTimetableParser.parseSections("1-2, 8");
        assertEquals(2, ranges.size());
        assertEquals(1, ranges.get(0)[0]);
        assertEquals(2, ranges.get(0)[1]);
        assertEquals(8, ranges.get(1)[0]);
        assertEquals(8, ranges.get(1)[1]);
        assertTrue(JwTimetableParser.parseSections("19-20").isEmpty());
    }

    private static JSONObject wrap(JSONArray kbList) throws Exception {
        return new JSONObject()
                .put("xnm", "2026")
                .put("xqm", "3")
                .put("kbList", kbList);
    }
}
