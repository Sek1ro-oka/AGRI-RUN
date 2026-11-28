package org.localroute.app.study;

/**
 * 离线导入解析出的一行课程：某星期某节次上的一段周次。
 *
 * <p>教务页面都是「格子表」，一个格子里同一门课常按单周/双周或分段拆成多条；
 * 这里保留拆开的原始粒度，由 {@link TimetableOfflineImport} 再把同一名次的段
 * 合并成本应用的一条周次文本。
 */
final class TimetableImportRow {

    static final int PARITY_ALL = 0;
    static final int PARITY_ODD = 1;
    static final int PARITY_EVEN = 2;

    private final String name;
    private final String teacher;
    private final String location;
    private final int weekday;
    private final int startPeriod;
    private final int endPeriod;
    private final int startWeek;
    private final int endWeek;
    private final int parity;

    TimetableImportRow(String name, String teacher, String location, int weekday,
                       int startPeriod, int endPeriod, int startWeek, int endWeek, int parity) {
        this.name = name == null ? "" : name.trim();
        this.teacher = teacher == null ? "" : teacher.trim();
        this.location = location == null ? "" : location.trim();
        this.weekday = weekday;
        this.startPeriod = startPeriod;
        this.endPeriod = endPeriod;
        this.startWeek = startWeek;
        this.endWeek = endWeek;
        this.parity = parity;
    }

    String name() {
        return name;
    }

    /** 教务里的「无」「-」「」都算没填，避免课表上出现一串「无」。 */
    String teacher() {
        return blankable(teacher);
    }

    String location() {
        return blankable(location);
    }

    int weekday() {
        return weekday;
    }

    int startPeriod() {
        return startPeriod;
    }

    int endPeriod() {
        return endPeriod;
    }

    int parity() {
        return parity;
    }

    int startWeek() {
        return startWeek;
    }

    int endWeek() {
        return endWeek;
    }

    /** 与本应用存的周次文本同构，供合并分组用。 */
    String groupKey() {
        return name + '|' + teacher() + '|' + location() + '|' + weekday
                + '|' + startPeriod + '|' + endPeriod + '|' + parity;
    }

    boolean isValid() {
        return !name.isEmpty()
                && weekday >= 1 && weekday <= 7
                && startPeriod >= TimetableCourse.MIN_PERIOD
                && endPeriod <= TimetableCourse.MAX_PERIOD
                && startPeriod <= endPeriod
                && startWeek >= 1 && endWeek >= startWeek;
    }

    private static String blankable(String value) {
        String trimmed = value.trim();
        return trimmed.isEmpty() || trimmed.equals("无") || trimmed.equals("-")
                || trimmed.equals("/") ? "" : trimmed;
    }
}
