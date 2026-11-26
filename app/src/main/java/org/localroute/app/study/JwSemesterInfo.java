package org.localroute.app.study;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.LocalDate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 教务课表自带的学期锚点：本周是第几周、第 1 周从哪天开始。
 *
 * <p>正方各校部署的字段名不统一，「本周第几周」可能出现在课表接口里，也可能只写在建页
 * 脚本的 <code>xnWeeks</code>、周次下拉框和 <code>dates</code> 数组里。抓取脚本把页面与
 * 接口两处归一成 <code>xnWeeks</code>、<code>dates</code> 交回来，这里再认几个原始键名。
 *
 * <p>推算以教务口径的「本周第 N 周」优先：课程周次写的「9-10 周」就是按它编排的，按今天
 * 倒推出的第 1 周起点才能让网格日期和周次标签对上。只有教务没给周次、只给了校历日期时，
 * 才退回来按日期算本周。倒推之前还要确认这份课表就是今天在读的学期，否则导入往学期
 * 会把本机已经调好的开学日期挪走。
 *
 * <p>纯 Java，不依赖 Android，可在 JVM 单元测试中直接验证。
 */
public final class JwSemesterInfo {

    /** 候选键按「脚本归一后的名字 → 其他部署常见写法」排列。 */
    private static final String[] WEEK_KEYS = {"xnWeeks", "xq", "zxs", "dqz", "xzz"};
    private static final String[] DATE_KEYS = {"dates", "xnDates", "xqDates"};

    private static final Pattern WEEK_NUMBER = Pattern.compile("\\d+");
    private static final Pattern DATED = Pattern.compile("(\\d{4})[-/.](\\d{1,2})[-/.](\\d{1,2})");
    private static final Pattern DATED_COMPACT = Pattern.compile("^(\\d{4})(\\d{2})(\\d{2})$");
    /** 校历日期要落在这个区间才算可信：教务占位值常写成 0000-00-00 或 1970-01-01。 */
    private static final LocalDate MIN_DATE = LocalDate.of(2015, 1, 1);
    private static final LocalDate MAX_DATE = LocalDate.of(2035, 12, 31);

    private static final JwSemesterInfo EMPTY = new JwSemesterInfo(0, null, 0, 0, 0);

    private final int declaredWeek;
    @Nullable
    private final LocalDate calendarStart;
    private final int calendarWeeks;
    /** 学年「2026-2027」的起始年份，0 表示没读到。 */
    private final int termYear;
    /** 学期序号 1/2/3，0 表示没读到。 */
    private final int termIndex;

    private JwSemesterInfo(int declaredWeek, @Nullable LocalDate calendarStart,
                           int calendarWeeks, int termYear, int termIndex) {
        this.declaredWeek = declaredWeek;
        this.calendarStart = calendarStart;
        this.calendarWeeks = calendarWeeks;
        this.termYear = termYear;
        this.termIndex = termIndex;
    }

    /** 推算结果：导入确认时要写进本机偏好文件的开学日期、本周周次与总周数。 */
    public static final class Plan {
        /** 第 1 周内的任意一天；为 null 表示教务信息不够，保持本机原值。 */
        @Nullable
        public final LocalDate semesterStart;
        /** 本周周次；为 0 表示不知道，保持本机原来查看的周。 */
        public final int week;
        /** 学期总周数，不小于传入的当前值，也不小于教务给出的周次。 */
        public final int totalWeeks;
        /** 开学日期是否直接来自教务校历（否则是按本周倒推出来的周一）。 */
        public final boolean fromCalendar;

        Plan(@Nullable LocalDate semesterStart, int week, int totalWeeks, boolean fromCalendar) {
            this.semesterStart = semesterStart;
            this.week = week;
            this.totalWeeks = totalWeeks;
            this.fromCalendar = fromCalendar;
        }

        static final Plan NONE = new Plan(null, 0, 0, false);
    }

    public static JwSemesterInfo read(@Nullable JSONObject root) {
        if (root == null) {
            return EMPTY;
        }
        int week = 0;
        for (String key : WEEK_KEYS) {
            week = parseWeek(root.opt(key));
            if (week > 0) {
                break;
            }
        }
        LocalDate start = null;
        int count = 0;
        for (String key : DATE_KEYS) {
            JSONArray dates = root.optJSONArray(key);
            if (dates == null || dates.length() == 0) {
                continue;
            }
            int found = 0;
            LocalDate earliest = null;
            for (int i = 0; i < dates.length(); i++) {
                LocalDate date = parseDate(dates.opt(i));
                if (date == null) {
                    continue;
                }
                found++;
                if (earliest == null || date.isBefore(earliest)) {
                    earliest = date;
                }
            }
            if (found > 0) {
                start = earliest;
                count = found;
                break;
            }
        }
        if (count == 0) {
            // 周次下拉框的选项个数就是学期总周数，比日期更容易拿到。
            count = parseWeek(root.opt("weekCount"));
        }
        return new JwSemesterInfo(week, start, count,
                parseTermYear(root.opt("xnm")), parseTermIndex(root.opt("xqm")));
    }

    /**
     * 把教务数据换算成要写入本机的学期锚点。
     *
     * @param today             今天
     * @param currentTotalWeeks 本机当前的总周数（已含按课程周次扩展的结果），不会被调小
     */
    public Plan plan(LocalDate today, int currentTotalWeeks) {
        int total = Math.max(1, currentTotalWeeks);
        if (calendarWeeks > total) {
            total = Math.min(StudyTimetableStore.MAX_TOTAL_WEEKS, calendarWeeks);
        }
        if (declaredWeek > 0) {
            total = Math.max(total, declaredWeek);
            boolean agrees = calendarStart != null
                    && SemesterCalendar.teachingWeek(calendarStart, today, total) == declaredWeek;
            if (agrees) {
                return new Plan(calendarStart, declaredWeek, total, true);
            }
            // 只能靠「本周第 N 周」倒推时，先确认这份课表就是今天在读的学期。
            return termCoversToday(today)
                    ? new Plan(SemesterCalendar.mondayOf(today).minusWeeks(declaredWeek - 1L),
                    declaredWeek, total, false)
                    : Plan.NONE;
        }
        if (calendarStart == null) {
            return Plan.NONE;
        }
        return new Plan(calendarStart, SemesterCalendar.teachingWeek(calendarStart, today, total),
                total, true);
    }

    /**
     * 这份课表对应的学期是否覆盖今天。
     *
     * <p>倒推开学日期前要问一句：导入上学期课表时教务照样报「本周第 N 周」，按它倒推会把
     * 本机已经调好的学期挪到别处。校历日期自带学期，不受这条约束；缺 <code>xnm</code>
     * 或 <code>xqm</code> 判断不了时放行，正常导入当前学期不该被拦住。
     */
    public boolean termCoversToday(LocalDate today) {
        if (termYear <= 0 || termIndex <= 0) {
            return true;
        }
        LocalDate from;
        LocalDate to;
        switch (termIndex) {
            case 1:
                from = LocalDate.of(termYear, 8, 20);
                to = LocalDate.of(termYear + 1, 2, 10);
                break;
            case 2:
                from = LocalDate.of(termYear + 1, 2, 1);
                to = LocalDate.of(termYear + 1, 7, 31);
                break;
            case 3:
                from = LocalDate.of(termYear + 1, 6, 15);
                to = LocalDate.of(termYear + 1, 9, 20);
                break;
            default:
                return true;
        }
        return !today.isBefore(from) && !today.isAfter(to);
    }

    /** 教务周次直接说出的本周，0 表示没说。 */
    public int declaredWeek() {
        return declaredWeek;
    }

    /** 教务校历里第 1 周的起始日，null 表示没给。 */
    @Nullable
    public LocalDate calendarStart() {
        return calendarStart;
    }

    /** 教务校历覆盖的周数，0 表示没给。 */
    public int calendarWeeks() {
        return calendarWeeks;
    }

    /** 「2026-2027」读成 2026；不像学年就返回 0。 */
    static int parseTermYear(@Nullable Object value) {
        if (!scalar(value)) {
            return 0;
        }
        Matcher matcher = Pattern.compile("([0-9]{4})").matcher(String.valueOf(value));
        if (!matcher.find()) {
            return 0;
        }
        int year = Integer.parseInt(matcher.group(1));
        return year >= 2000 && year <= 2099 ? year : 0;
    }

    /**
     * 学期序号：正方常用 3/12/16 表示秋/春/短学期，部分部署直接写 1/2/3。
     *
     * <p>3 在两套写法里含义不同：正方把「第一学期」写成 3，
     * {@link JwTimetableParser#termLabel} 也按这个口径展示，这里跟随它，
     * 否则第一学期的导入会被当成短学期、被学期守护拦掉。
     */
    static int parseTermIndex(@Nullable Object value) {
        if (!scalar(value)) {
            return 0;
        }
        Matcher matcher = WEEK_NUMBER.matcher(String.valueOf(value));
        if (!matcher.find()) {
            return 0;
        }
        switch (Integer.parseInt(matcher.group())) {
            case 1:
            case 3:
                return 1;
            case 2:
            case 12:
                return 2;
            case 16:
                return 3;
            default:
                return 0;
        }
    }

    /** 「9」「第9周」都读成 9；没有数字、超过两位或超出周数上限时返回 0。 */
    static int parseWeek(@Nullable Object value) {
        if (scalar(value)) {
            Matcher matcher = WEEK_NUMBER.matcher(String.valueOf(value));
            if (matcher.find() && matcher.end() - matcher.start() <= 2) {
                int week = Integer.parseInt(matcher.group());
                if (week >= 1 && week <= StudyTimetableStore.MAX_TOTAL_WEEKS) {
                    return week;
                }
            }
        }
        return 0;
    }

    /** 支持 <code>2025-09-01</code>、<code>2025/9/1</code>、<code>20250901</code>。 */
    @Nullable
    static LocalDate parseDate(@Nullable Object value) {
        if (!scalar(value)) {
            return null;
        }
        String text = String.valueOf(value).trim();
        Matcher matcher = DATED_COMPACT.matcher(text);
        int year;
        int month;
        int day;
        if (matcher.matches()) {
            year = Integer.parseInt(matcher.group(1));
            month = Integer.parseInt(matcher.group(2));
            day = Integer.parseInt(matcher.group(3));
        } else {
            matcher = DATED.matcher(text);
            if (!matcher.find()) {
                return null;
            }
            year = Integer.parseInt(matcher.group(1));
            month = Integer.parseInt(matcher.group(2));
            day = Integer.parseInt(matcher.group(3));
        }
        LocalDate date;
        try {
            date = LocalDate.of(year, month, day);
        } catch (RuntimeException ignored) {
            return null;
        }
        return date.isBefore(MIN_DATE) || date.isAfter(MAX_DATE) ? null : date;
    }

    /** 只接受字符串与数字，数组和对象一律不看。 */
    private static boolean scalar(@Nullable Object value) {
        return value instanceof String || value instanceof Number;
    }
}
