package org.localroute.app.study;

import org.json.JSONObject;
import org.junit.Test;

import java.time.LocalDate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 教务学期锚点推算：本周周次、校历日期、两者的优先级与坏值容忍。
 *
 * <p>今天固定取 2026-11-24（周二），所在周的周一是 2026-11-23，倒推第 9 周的
 * 第 1 周起点是 2026-09-28。
 */
public class JwSemesterInfoTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 24);
    private static final LocalDate THIS_MONDAY = LocalDate.of(2026, 11, 23);
    private static final LocalDate WEEK_ONE_MONDAY = LocalDate.of(2026, 9, 28);

    private static JSONObject json(String raw) throws Exception {
        return new JSONObject(raw);
    }

    @Test
    public void readsWeekAndCalendarDates() throws Exception {
        JwSemesterInfo info = JwSemesterInfo.read(json(
                "{\"xnWeeks\":\"9\",\"dates\":[\"2026-09-28\",\"2026-10-05\"]}"));
        assertEquals(9, info.declaredWeek());
        assertEquals(WEEK_ONE_MONDAY, info.calendarStart());
        assertEquals(2, info.calendarWeeks());
    }

    @Test
    public void emptyPayloadHasNothingToApply() throws Exception {
        JwSemesterInfo info = JwSemesterInfo.read(json("{\"kbList\":[]}"));
        assertEquals(0, info.declaredWeek());
        assertNull(info.calendarStart());
        JwSemesterInfo.Plan plan = info.plan(TODAY, 20);
        assertNull(plan.semesterStart);
        assertEquals(0, plan.week);
    }

    @Test
    public void nullRootIsSafe() {
        JwSemesterInfo info = JwSemesterInfo.read(null);
        assertEquals(0, info.declaredWeek());
        assertNull(info.calendarStart());
        assertNull(info.plan(TODAY, 20).semesterStart);
    }

    /** 教务只说「本周第 9 周」时，按今天所在周的周一倒推第 1 周起点。 */
    @Test
    public void anchorsWeekBackFromToday() throws Exception {
        JwSemesterInfo.Plan plan = JwSemesterInfo.read(json("{\"xnWeeks\":\"9\"}"))
                .plan(TODAY, 20);
        assertEquals(THIS_MONDAY.minusWeeks(8), plan.semesterStart);
        assertEquals(WEEK_ONE_MONDAY, plan.semesterStart);
        assertEquals(9, plan.week);
        assertFalse(plan.fromCalendar);
        assertEquals(9, SemesterCalendar.teachingWeek(plan.semesterStart, TODAY, 20));
    }

    /** 校历日期和教务周次对得上时，用真实开学日（哪怕落在周中）。 */
    @Test
    public void prefersCalendarDateWhenItAgrees() throws Exception {
        JwSemesterInfo.Plan monday = JwSemesterInfo.read(json(
                "{\"xnWeeks\":\"9\",\"dates\":[\"2026-09-28\",\"2026-10-05\"]}"))
                .plan(TODAY, 20);
        assertTrue(monday.fromCalendar);
        assertEquals(WEEK_ONE_MONDAY, monday.semesterStart);
        assertEquals(9, monday.week);

        JwSemesterInfo.Plan wednesday = JwSemesterInfo.read(json(
                "{\"xnWeeks\":\"9\",\"dates\":[\"2026-09-30\",\"2026-10-07\"]}"))
                .plan(TODAY, 20);
        assertTrue(wednesday.fromCalendar);
        assertEquals(LocalDate.of(2026, 9, 30), wednesday.semesterStart);
        assertEquals(9, wednesday.week);
    }

    /** 两个口径打架时以教务周次为准：课程周次「9-10 周」是按它编排的。 */
    @Test
    public void fallsBackToDerivedStartWhenCalendarDisagrees() throws Exception {
        JwSemesterInfo.Plan plan = JwSemesterInfo.read(
                json("{\"xnWeeks\":\"9\",\"dates\":[\"2026-09-07\"]}")).plan(TODAY, 20);
        assertFalse(plan.fromCalendar);
        assertEquals(WEEK_ONE_MONDAY, plan.semesterStart);
        assertEquals(9, plan.week);
    }

    @Test
    public void calendarOnlyComputesWeekFromDates() throws Exception {
        JwSemesterInfo.Plan plan = JwSemesterInfo.read(json(
                "{\"dates\":[\"2026-09-28\",\"2026-10-05\",\"2026-10-12\"]}")).plan(TODAY, 20);
        assertEquals(WEEK_ONE_MONDAY, plan.semesterStart);
        assertEquals(9, plan.week);
        assertTrue(plan.fromCalendar);
    }

    /** 今天不在校历覆盖的周里：日期仍然值得记下来，但当前查看的周不动。 */
    @Test
    public void todayOutsideCalendarKeepsWeekUnknown() throws Exception {
        JwSemesterInfo.Plan plan = JwSemesterInfo.read(
                json("{\"dates\":[\"2026-03-02\"],\"weekCount\":\"16\"}")).plan(TODAY, 20);
        assertEquals(LocalDate.of(2026, 3, 2), plan.semesterStart);
        assertEquals(0, plan.week);
    }

    /** 总周数只增不减：校历比本机短、教务周次比本机长，都按大的算。 */
    @Test
    public void totalWeeksNeverShrinks() throws Exception {
        assertEquals(20, JwSemesterInfo.read(json(
                "{\"dates\":[\"2026-09-28\",\"2026-10-05\"]}")).plan(TODAY, 20).totalWeeks);
        assertEquals(25, JwSemesterInfo.read(json("{\"xnWeeks\":\"25\"}"))
                .plan(TODAY, 20).totalWeeks);
        assertEquals(18, JwSemesterInfo.read(json("{\"xnWeeks\":\"9\",\"weekCount\":\"18\"}"))
                .plan(TODAY, 16).totalWeeks);
    }

    @Test
    public void weekCountStandsInForCalendarDates() throws Exception {
        JwSemesterInfo.Plan plan = JwSemesterInfo.read(
                json("{\"xnWeeks\":\"9\",\"weekCount\":\"18\"}")).plan(TODAY, 20);
        assertEquals(WEEK_ONE_MONDAY, plan.semesterStart);
        assertEquals(9, plan.week);
        assertEquals(20, plan.totalWeeks);
    }

    @Test
    public void weekLabelsAndBounds() throws Exception {
        assertEquals(9, JwSemesterInfo.parseWeek("第9周"));
        assertEquals(9, JwSemesterInfo.parseWeek(9));
        assertEquals(0, JwSemesterInfo.parseWeek("第几周"));
        assertEquals(0, JwSemesterInfo.parseWeek("0"));
        // 「100」不是周次，多半是别的编号，宁可当没读到。
        assertEquals(0, JwSemesterInfo.parseWeek("100"));
        assertEquals(0, JwSemesterInfo.parseWeek(null));
    }

    @Test
    public void dateFormatsAndGarbage() throws Exception {
        assertEquals(WEEK_ONE_MONDAY, JwSemesterInfo.parseDate("2026-09-28"));
        assertEquals(WEEK_ONE_MONDAY, JwSemesterInfo.parseDate("20260928"));
        assertEquals(LocalDate.of(2026, 10, 5), JwSemesterInfo.parseDate("2026/10/5"));
        assertEquals(LocalDate.of(2026, 10, 5), JwSemesterInfo.parseDate("2026-10-5 08:00"));
        assertNull(JwSemesterInfo.parseDate("0000-00-00"));
        assertNull(JwSemesterInfo.parseDate("1970-01-01"));
        assertNull(JwSemesterInfo.parseDate("2026-13-01"));
        assertNull(JwSemesterInfo.parseDate("2026-02-30"));
        assertNull(JwSemesterInfo.parseDate("第 1 周"));
        assertNull(JwSemesterInfo.parseDate(null));
    }

    /** 坏条目被丢掉后，剩下的最早日期才是第 1 周起点。 */
    @Test
    public void brokenDateEntriesAreSkipped() throws Exception {
        JwSemesterInfo info = JwSemesterInfo.read(json(
                "{\"dates\":[\"\",\"0000-00-00\",\"2026-10-05\",\"2026-09-28\"]}"));
        assertEquals(WEEK_ONE_MONDAY, info.calendarStart());
        assertEquals(2, info.calendarWeeks());
    }

    /** 各校键名不统一：脚本归一失败时原始写法也要能读出来。 */
    @Test
    public void alternateWeekKeys() throws Exception {
        assertEquals(7, JwSemesterInfo.read(json("{\"xq\":\"第7周\"}")).declaredWeek());
        assertEquals(7, JwSemesterInfo.read(json("{\"zxs\":7}")).declaredWeek());
        assertEquals(1, JwSemesterInfo.read(json("{\"xnDates\":[\"2026-09-28\"]}"))
                .calendarWeeks());
    }

    /** 导入当前学期：按教务周次倒推开学日期。 */
    @Test
    public void currentTermAnchorsByDeclaredWeek() throws Exception {
        JwSemesterInfo.Plan plan = JwSemesterInfo.read(json(
                "{\"xnm\":\"2026-2027\",\"xqm\":\"1\",\"xnWeeks\":\"9\"}")).plan(TODAY, 20);
        assertEquals(WEEK_ONE_MONDAY, plan.semesterStart);
        assertEquals(9, plan.week);
    }

    /** 导入往学期课：教务照样报「本周第 N 周」，但那份周次不属于今天，不能挪本机锚点。 */
    @Test
    public void otherTermImportLeavesSemesterAlone() throws Exception {
        JwSemesterInfo.Plan past = JwSemesterInfo.read(json(
                "{\"xnm\":\"2025-2026\",\"xqm\":\"2\",\"xnWeeks\":\"9\"}")).plan(TODAY, 20);
        assertNull(past.semesterStart);
        assertEquals(0, past.week);
        assertFalse(JwSemesterInfo.read(json("{\"xnm\":\"2025-2026\",\"xqm\":\"2\"}"))
                .termCoversToday(TODAY));
        assertTrue(JwSemesterInfo.read(json("{\"xnm\":\"2026-2027\",\"xqm\":\"2\"}"))
                .termCoversToday(LocalDate.of(2027, 3, 15)));
        assertTrue(JwSemesterInfo.read(json("{\"xnm\":\"2026-2027\",\"xqm\":\"1\"}"))
                .termCoversToday(TODAY));
    }

    /** 校历日期自带学期，读得到就照实用，不受学年学期写法影响。 */
    @Test
    public void calendarDatesBypassTermGuard() throws Exception {
        JwSemesterInfo.Plan plan = JwSemesterInfo.read(json(
                "{\"xnm\":\"2025-2026\",\"xqm\":\"2\",\"xnWeeks\":\"9\","
                        + "\"dates\":[\"2026-09-28\",\"2026-10-05\"]}")).plan(TODAY, 20);
        assertEquals(WEEK_ONE_MONDAY, plan.semesterStart);
        assertEquals(9, plan.week);
        assertTrue(plan.fromCalendar);
    }

    @Test
    public void termLabelsParse() throws Exception {
        assertEquals(2026, JwSemesterInfo.parseTermYear("2026-2027"));
        assertEquals(2026, JwSemesterInfo.parseTermYear("2026~2027"));
        assertEquals(0, JwSemesterInfo.parseTermYear("1999-2000"));
        assertEquals(0, JwSemesterInfo.parseTermYear(null));
        assertEquals(2, JwSemesterInfo.parseTermIndex("2"));
        assertEquals(2, JwSemesterInfo.parseTermIndex(2));
        assertEquals(0, JwSemesterInfo.parseTermIndex("4"));
    }
}
