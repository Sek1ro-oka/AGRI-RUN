package org.localroute.app.study;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 课表 → iCalendar（RFC 5545）文本：导出的 .ics 可以直接进系统日历，
 * 手机上没有 UNLOOP 的人也能看，熄屏锁屏上的日程同样由日历应用负责提醒。
 *
 * <p>每门课一个 {@code VEVENT}，用 {@code RRULE:FREQ=WEEKLY;BYDAY=…;COUNT=…} 表达「每周重复」，
 * 单双周与断开的周次区间用 {@code EXDATE} 逐周排除——这样一学期一门课只写一条事件，
 * 而不是把 16 周摊成 16 条，日历应用里也才谈得上「改一次时间全部跟着改」。
 *
 * <p>时间写作不带时区的本地时间（floating time）：作息本来就是本校当天的钟点，
 * 换成 UTC 反而会在别的时区被偏移。这一点连同「日历里没有调课与节假日」写进 PR 说明。
 *
 * <p>纯 Java，不碰 {@code ContentResolver}，可在 JVM 单元测试里逐行断言。
 */
public final class IcsTimetableWriter {

    /** RFC 5545 规定行分隔符是 CRLF，很多日历解析器对裸 LF 会直接报错。 */
    private static final String NL = "\r\n";
    /** 一行最多 75 字节，超出要折行（续行以一个空格开头）。 */
    private static final int MAX_LINE_OCTETS = 75;
    private static final String[] BYDAY = {"MO", "TU", "WE", "TH", "FR", "SA", "SU"};

    private IcsTimetableWriter() {
    }

    /**
     * 生成整份日历文本。
     *
     * @param courses       本机课表；周次一个都不落在学期内的课程不会出现在结果里
     * @param schedule      当前生效的作息，决定每节开始/结束钟点
     * @param semesterStart 开学第 1 周内的某天；日历里的具体日期全部由它推出来
     * @param totalWeeks    学期总周数，重复的上界
     * @param calendarName  日历名，导入后在日历应用里显示成一组课表的名字
     */
    @NonNull
    public static String write(@NonNull List<TimetableCourse> courses,
                               @NonNull StudySchedule schedule,
                               @NonNull LocalDate semesterStart,
                               int totalWeeks,
                               @NonNull String calendarName) {
        return write(courses, schedule, semesterStart, totalWeeks, calendarName,
                ZonedDateTime.now());
    }

    /** {@code moment} 只影响 {@code DTSTAMP}（说明这份日历何时生成），课程日期全由学期推算。 */
    @NonNull
    public static String write(@NonNull List<TimetableCourse> courses,
                               @NonNull StudySchedule schedule,
                               @NonNull LocalDate semesterStart,
                               int totalWeeks,
                               @NonNull String calendarName,
                               @NonNull ZonedDateTime moment) {
        StringBuilder text = new StringBuilder();
        append(text, "BEGIN:VCALENDAR");
        append(text, "VERSION:2.0");
        append(text, "PRODID:-//UNLOOP//Route Workshop//CN");
        append(text, "CALSCALE:GREGORIAN");
        append(text, "METHOD:PUBLISH");
        append(text, "X-WR-CALNAME:" + escape(calendarName));
        for (TimetableCourse course : courses) {
            String event = event(schedule, semesterStart, totalWeeks, course, moment);
            if (event != null) {
                text.append(event);
            }
        }
        append(text, "END:VCALENDAR");
        return text.toString();
    }

    /**
     * 默认文件名：带开学的年月与导出日期，用户在系统保存面板里仍可改。
     * 开学月份最容易被认出，比「课表.ics」这种一次性名字好归档。
     */
    @NonNull
    public static String suggestedFileName(@NonNull LocalDate semesterStart,
                                           @NonNull LocalDate today) {
        return String.format(Locale.US, "课表-%04d%02d起-%04d%02d%02d.ics",
                semesterStart.getYear(), semesterStart.getMonthValue(),
                today.getYear(), today.getMonthValue(), today.getDayOfMonth());
    }

    /**
     * 会真正写成事件的课程数，供「已导出 N 节课」的提示用。
     * 判据与 {@link #write} 完全一致，免得数字和文件内容各说一套。
     */
    public static int exportable(@NonNull List<TimetableCourse> courses,
                                 @NonNull StudySchedule schedule, int totalWeeks) {
        int count = 0;
        for (TimetableCourse course : courses) {
            if (!activeWeeks(course.weeks(), totalWeeks).isEmpty()
                    && course.startPeriod() <= schedule.totalPeriods()) {
                count++;
            }
        }
        return count;
    }

    /** 有课的周数；周次写法完全落在学期之外时返回空表。 */
    @NonNull
    public static List<Integer> activeWeeks(@Nullable String weeks, int totalWeeks) {
        List<Integer> active = new ArrayList<>();
        for (int week = 1; week <= totalWeeks; week++) {
            if (TimetableText.activeInWeek(weeks, week)) {
                active.add(week);
            }
        }
        return active;
    }

    /** 第 week 周星期 weekday 对应的日期；周一到周日依次加 0～6 天。 */
    @NonNull
    public static LocalDate dateOfWeekday(@NonNull LocalDate semesterStart, int week,
                                          int weekday) {
        return SemesterCalendar.mondayOf(semesterStart)
                .plusWeeks(Math.max(1, week) - 1L)
                .plusDays(Math.max(1, weekday) - 1L);
    }

    /** {@code yyyyMMddTHHmmss}，不带 Z 也不带 TZID：按本地时间理解。 */
    @NonNull
    public static String timestamp(@NonNull LocalDate day, int minutesOfDay) {
        return String.format(Locale.US, "%04d%02d%02dT%02d%02d00",
                day.getYear(), day.getMonthValue(), day.getDayOfMonth(),
                minutesOfDay / 60, minutesOfDay % 60);
    }

    /**
     * {@code DTSTAMP} 用 UTC 并带 {@code Z}：RFC 5545 里它是「这条记录写于何时」，
     * 与课程钟点无关，写成裸时间会被按各人时区解释成不同瞬间。
     */
    @NonNull
    public static String utcStamp(@NonNull ZonedDateTime moment) {
        ZonedDateTime utc = moment.withZoneSameInstant(ZoneOffset.UTC);
        return String.format(Locale.US, "%04d%02d%02dT%02d%02d%02dZ",
                utc.getYear(), utc.getMonthValue(), utc.getDayOfMonth(),
                utc.getHour(), utc.getMinute(), utc.getSecond());
    }

    @Nullable
    private static String event(StudySchedule schedule, LocalDate semesterStart, int totalWeeks,
                                TimetableCourse course, ZonedDateTime moment) {
        List<Integer> active = activeWeeks(course.weeks(), totalWeeks);
        if (active.isEmpty() || course.startPeriod() > schedule.totalPeriods()) {
            return null;
        }
        int firstWeek = active.get(0);
        int lastWeek = active.get(active.size() - 1);
        int startMinutes = schedule.periodStartMinutes(course.startPeriod());
        int endMinutes = schedule.periodEndMinutes(
                Math.min(course.endPeriod(), schedule.totalPeriods()));
        LocalDate firstDay = dateOfWeekday(semesterStart, firstWeek, course.weekday());

        StringBuilder event = new StringBuilder();
        append(event, "BEGIN:VEVENT");
        append(event, "UID:" + uid(course));
        append(event, "DTSTAMP:" + utcStamp(moment));
        append(event, "DTSTART:" + timestamp(firstDay, startMinutes));
        append(event, "DTEND:" + timestamp(firstDay, endMinutes));
        append(event, "SUMMARY:" + escape(displayName(course)));
        if (!course.location().isEmpty()) {
            append(event, "LOCATION:" + escape(course.location()));
        }
        append(event, "DESCRIPTION:" + escape(description(schedule, course)));
        // COUNT 覆盖首末周之间的每一周，落空的周次随后用 EXDATE 一节节剔掉。
        append(event, "RRULE:FREQ=WEEKLY;BYDAY=" + byday(course.weekday())
                + ";COUNT=" + (lastWeek - firstWeek + 1));
        List<String> exceptions = new ArrayList<>();
        for (int week = firstWeek; week <= lastWeek; week++) {
            if (active.contains(week)) {
                continue;
            }
            exceptions.add(timestamp(dateOfWeekday(semesterStart, week, course.weekday()),
                    startMinutes));
        }
        if (!exceptions.isEmpty()) {
            append(event, "EXDATE:" + String.join(",", exceptions));
        }
        append(event, "TRANSP:OPAQUE");
        append(event, "END:VEVENT");
        return event.toString();
    }

    /**
     * 事件标识固定由课程 id 推出：同一个课表反复导出、重复导入时，日历应用按 UID 合并
     * 而不是攒出一堆同名事件。
     */
    @NonNull
    public static String uid(@NonNull TimetableCourse course) {
        return "unloop-" + course.id() + "@localroute.org";
    }

    private static String displayName(TimetableCourse course) {
        return course.name().trim().isEmpty() ? "未命名课程" : course.name().trim();
    }

    /** 说明里用真实换行分隔各字段，交给 {@link #escape} 统一写成 iCalendar 的 {@code \n}。 */
    private static String description(StudySchedule schedule, TimetableCourse course) {
        StringBuilder detail = new StringBuilder();
        detail.append("时间：").append(schedule.startLabel(course.startPeriod()))
                .append('-').append(schedule.endLabel(
                        Math.min(course.endPeriod(), schedule.totalPeriods())));
        detail.append('\n').append("节次：第").append(course.startPeriod());
        if (course.endPeriod() != course.startPeriod()) {
            detail.append('-').append(course.endPeriod());
        }
        detail.append("节");
        detail.append('\n').append("周次：").append(course.weeks()).append("周");
        if (!course.teacher().isEmpty()) {
            detail.append('\n').append("教师：").append(course.teacher());
        }
        detail.append('\n').append("来自 UNLOOP 课表导出");
        return detail.toString();
    }

    private static String byday(int weekday) {
        return BYDAY[Math.min(7, Math.max(1, weekday)) - 1];
    }

    /**
     * 转义 TEXT 类字段：反斜杠、分号、逗号先转义，换行写成 {@code \n}。
     *
     * <p>课程名里出现「高等数学, 英语」这类逗号时，不转义的解析器会把后半截当成下一个值。
     */
    @NonNull
    public static String escape(@NonNull String raw) {
        StringBuilder escaped = new StringBuilder(raw.length() + 8);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '\\':
                    escaped.append("\\\\");
                    break;
                case ';':
                    escaped.append("\\;");
                    break;
                case ',':
                    escaped.append("\\,");
                    break;
                case '\r':
                    // CRLF 只写一个换行，否则一行说明会被日历读成两段。
                    if (i + 1 < raw.length() && raw.charAt(i + 1) == '\n') {
                        break;
                    }
                    escaped.append("\\n");
                    break;
                case '\n':
                    escaped.append("\\n");
                    break;
                default:
                    escaped.append(c);
                    break;
            }
        }
        return escaped.toString();
    }

    private static void append(StringBuilder target, String line) {
        for (String folded : fold(line)) {
            target.append(folded).append(NL);
        }
    }

    /** 按 RFC 5545 折行：首行 75 字节，续行以一个空格开头。 */
    @NonNull
    public static List<String> fold(@NonNull String line) {
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int budget = MAX_LINE_OCTETS;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            int octets = octetsOf(c);
            // 一个字符最多 4 字节，装不下就先把当前段收尾，不能把字符劈成两半。
            if (octets > budget) {
                lines.add(current.toString());
                current.setLength(0);
                budget = MAX_LINE_OCTETS - 1;
                current.append(' ');
            }
            current.append(c);
            budget -= octets;
        }
        lines.add(current.toString());
        return lines;
    }

    /** 该字符在 UTF-8 里占几个字节（代理对按 4 字节算，且不劈开）。 */
    private static int octetsOf(char c) {
        if (Character.isHighSurrogate(c)) {
            return 4;
        }
        if (Character.isLowSurrogate(c)) {
            return 0;
        }
        if (c < 0x80) {
            return 1;
        }
        return c < 0x800 ? 2 : 3;
    }
}
