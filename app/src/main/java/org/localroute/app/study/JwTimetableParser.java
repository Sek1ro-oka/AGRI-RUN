package org.localroute.app.study;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 正方教务（jwglxt）课表 JSON 解析：把「学生课表查询」接口返回的 kbList
 * 转成应用内的时间表课程。
 *
 * <p>只读取课表字段，不处理账号密码；解析失败的条目会跳过，不影响其它课程。
 * 一个 jc 字段包含多段节次（如 3-4,6-7）时会拆成多条课程记录。
 */
public final class JwTimetableParser {

    /** 节次里的区间连接符，教务系统会用「-」「~」「至」「到」四种写法。 */
    private static final Pattern SECTION_SEPARATOR = Pattern.compile("[-~至到]");
    private static final Pattern SECTION_NUMBER = Pattern.compile("^\\d{1,2}$");

    private JwTimetableParser() {
    }

    public static final class Result {
        private final List<TimetableCourse> courses;
        private final int rawCount;
        private final List<String> unparsedCourses;

        Result(@NonNull List<TimetableCourse> courses, int rawCount,
                @NonNull List<String> unparsedCourses) {
            this.courses = courses;
            this.rawCount = rawCount;
            this.unparsedCourses = unparsedCourses;
        }

        @NonNull
        public List<TimetableCourse> courses() {
            return courses;
        }

        public int rawCount() {
            return rawCount;
        }

        /**
         * 因为节次读不出来而被丢掉的课程名。教务系统换一种写法就静默少几节课，
         * 用户看到的是一张「看起来完整」的课表，所以这里必须把漏掉的课程报出来。
         */
        @NonNull
        public List<String> unparsedCourses() {
            return unparsedCourses;
        }
    }

    /**
     * 解析 kbList 数组，也接受外层包着 kbList/对象/数组的 JSON。
     *
     * @param json WebView 里读取到的课表 JSON 文本
     */
    @NonNull
    public static Result parse(@Nullable String json) {
        List<String> unparsedNames = new ArrayList<>();
        if (json == null || json.trim().isEmpty()) {
            return new Result(List.of(), 0, List.of());
        }
        JSONArray array = findKbList(json.trim());
        if (array == null) {
            return new Result(List.of(), 0, List.of());
        }
        Map<String, Group> groups = new LinkedHashMap<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) {
                continue;
            }
            String name = firstString(item, "kcmc", "kcm", "courseName");
            if (name.isEmpty()) {
                continue;
            }
            String teacher = firstString(item, "xm", "jsxm", "teacher");
            String location = firstString(item, "cdmc", "jxcd", "classroomName");
            int weekday = parseWeekday(item);
            if (weekday < 1) {
                continue;
            }
            String sectionText = firstString(item, "jc", "jcs", "classSessions");
            List<int[]> ranges = parseSections(sectionText);
            if (ranges.isEmpty()) {
                if (!sectionText.isEmpty() && !unparsedNames.contains(name)) {
                    unparsedNames.add(name);
                }
                continue;
            }
            String weeks = parseWeeks(firstString(item, "zcd", "kkzc", "zc"));
            String key = name + '|' + teacher + '|' + location + '|' + weekday + '|' + weeks;
            Group group = groups.get(key);
            if (group == null) {
                group = new Group(name, teacher, location, weekday, weeks);
                groups.put(key, group);
            }
            for (int[] range : ranges) {
                for (int period = range[0]; period <= range[1]; period++) {
                    group.periods.add(period);
                }
            }
        }
        List<TimetableCourse> courses = new ArrayList<>();
        for (Group group : groups.values()) {
            for (int[] range : mergeConsecutive(group.periods)) {
                TimetableCourse course = TimetableCourse.create(group.name, group.teacher,
                        group.location, group.weekday, range[0], range[1], group.weeks);
                if (course.isValid()) {
                    courses.add(course);
                }
            }
        }
        return new Result(courses, array.length(), unparsedNames);
    }

    /** 同一门课在同一天的所有节次；教务系统可能把连堂拆成多条记录。 */
    private static final class Group {
        private final String name;
        private final String teacher;
        private final String location;
        private final int weekday;
        private final String weeks;
        private final Set<Integer> periods = new TreeSet<>();

        Group(String name, String teacher, String location, int weekday, String weeks) {
            this.name = name;
            this.teacher = teacher;
            this.location = location;
            this.weekday = weekday;
            this.weeks = weeks;
        }
    }

    /** 把学年与学期代码转成展示文本，例如「2026-2027 学年第一学期」。 */
    @NonNull
    public static String termLabel(@Nullable String xnm, @Nullable String xqm) {
        String year = xnm == null ? "" : xnm.trim();
        int start = -1;
        try {
            start = Integer.parseInt(year);
        } catch (NumberFormatException ignored) {
            // 非数字学年代码保持空，下面只显示学期。
        }
        String yearLabel;
        if (start > 2000) {
            yearLabel = start + "-" + (start + 1) + " 学年";
        } else {
            yearLabel = "";
        }
        String semester;
        if (xqm == null) {
            semester = "";
        } else {
            switch (xqm.trim()) {
                case "3":
                    semester = "第一学期";
                    break;
                case "12":
                    semester = "第二学期";
                    break;
                case "16":
                    semester = "第三学期";
                    break;
                default:
                    semester = xqm.trim().isEmpty() ? "" : "学期 " + xqm.trim();
                    break;
            }
        }
        String label = yearLabel + semester;
        return label.isEmpty() ? "教务系统课表" : label;
    }

    @Nullable
    private static JSONArray findKbList(@NonNull String json) {
        try {
            if (json.startsWith("[")) {
                return new JSONArray(json);
            }
            JSONObject root = new JSONObject(json);
            JSONArray direct = root.optJSONArray("kbList");
            if (direct != null) {
                return direct;
            }
            return findKbList(root, 0);
        } catch (Exception ignored) {
            return null;
        }
    }

    @Nullable
    private static JSONArray findKbList(@NonNull JSONObject object, int depth) {
        if (depth > 5) {
            return null;
        }
        JSONArray kbList = object.optJSONArray("kbList");
        if (kbList != null) {
            return kbList;
        }
        JSONArray names = object.names();
        if (names == null) {
            return null;
        }
        for (int i = 0; i < names.length(); i++) {
            Object value = object.opt(names.optString(i));
            if (value instanceof JSONArray) {
                JSONArray array = (JSONArray) value;
                for (int j = 0; j < array.length(); j++) {
                    JSONObject child = array.optJSONObject(j);
                    if (child != null) {
                        JSONArray found = findKbList(child, depth + 1);
                        if (found != null) {
                            return found;
                        }
                    }
                }
            } else if (value instanceof JSONObject) {
                JSONArray found = findKbList((JSONObject) value, depth + 1);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    @NonNull
    private static String firstString(@NonNull JSONObject object, @NonNull String... keys) {
        for (String key : keys) {
            String value = object.optString(key, "").trim();
            if (!value.isEmpty()) {
                return value;
            }
        }
        return "";
    }

    private static int parseWeekday(@NonNull JSONObject object) {
        int weekday = object.optInt("xqj", 0);
        if (weekday >= 1 && weekday <= 7) {
            return weekday;
        }
        String text = firstString(object, "xqjmc", "weekdayName");
        if (text.isEmpty()) {
            return 0;
        }
        String[] labels = {"一", "二", "三", "四", "五", "六", "日"};
        for (int i = 0; i < labels.length; i++) {
            if (text.contains("周" + labels[i]) || text.contains("星期" + labels[i])) {
                return i + 1;
            }
        }
        try {
            int parsed = Integer.parseInt(text.replace("周", "").replace("星期", ""));
            return parsed >= 1 && parsed <= 7 ? parsed : 0;
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    /**
     * 解析 jc 节次：支持「1-2」「第1-2节」「3-4,6-7」「1-2-3」「0102」「030405」「345」。
     * 返回的每段都保证在 1..15 内且开始不晚于结束。
     */
    @NonNull
    static List<int[]> parseSections(@Nullable String raw) {
        List<int[]> result = new ArrayList<>();
        if (raw == null) {
            return result;
        }
        String value = raw.trim()
                .replace("第", "")
                .replace("节", "")
                .replace("，", ",")
                .replace("、", ",")
                .replace("；", ",")
                .replace(";", ",")
                .replace(" ", "");
        if (value.isEmpty()) {
            return result;
        }
        for (String part : value.split(",")) {
            if (!part.isEmpty()) {
                result.addAll(parseSectionSegment(part));
            }
        }
        return result;
    }

    /**
     * 单段节次。各校教务系统对连堂的写法并不统一：区间写「3-5」，逐节罗列写
     * 「3-4-5」，紧凑拼接写「030405」，三种都得占成三节课。
     */
    private static List<int[]> parseSectionSegment(String part) {
        String text = SECTION_SEPARATOR.matcher(part).replaceAll("-");
        if (!text.contains("-")) {
            Integer single = toPeriod(text);
            if (single != null) {
                return List.of(new int[]{single, single});
            }
            return mergeConsecutive(periodsOf(text));
        }
        String[] tokens = text.split("-");
        List<List<Integer>> groups = new ArrayList<>();
        for (String token : tokens) {
            List<Integer> periods = periodsOf(token);
            if (periods.isEmpty()) {
                return List.of();
            }
            groups.add(periods);
        }
        // 「2-11」是节次区间，两端各自只代表一节课；写成两端之外都要按逐个罗列处理。
        if (groups.size() == 2 && groups.get(0).size() == 1 && groups.get(1).size() == 1) {
            int start = groups.get(0).get(0);
            int end = groups.get(1).get(0);
            return start <= end ? List.of(new int[]{start, end}) : List.of();
        }
        List<Integer> periods = new ArrayList<>();
        for (List<Integer> group : groups) {
            periods.addAll(group);
        }
        return mergeConsecutive(periods);
    }

    /**
     * 一个节次片段包含的节次：单位数就是它自己，「0304」按两位一节，
     * 「345」按一位一节。「12」优先当作第 12 节，只有超出范围才拆成 1、2 节。
     */
    @NonNull
    private static List<Integer> periodsOf(@NonNull String token) {
        Integer single = toPeriod(token);
        if (single != null) {
            return List.of(single);
        }
        List<Integer> pairs = splitPairs(token);
        return pairs.isEmpty() ? splitDigits(token) : pairs;
    }

    /** 单个节次号；非数字或越界返回 null。 */
    @Nullable
    private static Integer toPeriod(@Nullable String token) {
        if (token == null || !SECTION_NUMBER.matcher(token).matches()) {
            return null;
        }
        int value = Integer.parseInt(token);
        return value >= TimetableCourse.MIN_PERIOD && value <= TimetableCourse.MAX_PERIOD
                ? value : null;
    }

    /** 两位一节的紧凑写法：030405 → 3,4,5。任何一节越界就不算这种写法。 */
    @NonNull
    private static List<Integer> splitPairs(String text) {
        List<Integer> periods = new ArrayList<>();
        if (text.length() < 4 || !text.matches("^\\d+$")) {
            return periods;
        }
        for (int i = 0; i + 2 <= text.length(); i += 2) {
            Integer period = toPeriod(text.substring(i, i + 2));
            if (period == null) {
                return List.of();
            }
            periods.add(period);
        }
        return periods;
    }

    /**
     * 一位一节的紧凑写法：345 → 3,4,5。含 0 就不成立。
     *
     * <p>两位数字不参与：「16」「17」是越界的节次号，不是 1、6 节，
     * 拆开会凭空多出课程，丢掉才能让界面提示用户核对。
     */
    @NonNull
    private static List<Integer> splitDigits(String text) {
        List<Integer> periods = new ArrayList<>();
        if (text.length() < 3 || !text.matches("^\\d+$")) {
            return periods;
        }
        for (int i = 0; i < text.length(); i++) {
            Integer period = toPeriod(String.valueOf(text.charAt(i)));
            if (period == null) {
                return List.of();
            }
            periods.add(period);
        }
        return periods;
    }

    /** 把逐个节次压成连续区间，中间断开就分成多段。 */
    @NonNull
    private static List<int[]> mergeConsecutive(@NonNull Collection<Integer> periods) {
        List<Integer> sorted = new ArrayList<>(new TreeSet<>(periods));
        List<int[]> ranges = new ArrayList<>();
        int index = 0;
        while (index < sorted.size()) {
            int start = sorted.get(index);
            int end = start;
            while (index + 1 < sorted.size() && sorted.get(index + 1) == end + 1) {
                end = sorted.get(++index);
            }
            ranges.add(new int[]{start, end});
            index++;
        }
        return ranges;
    }

    /** zcd（如 1-16周、1-16周(单)）转成应用内的周次文本。 */
    @NonNull
    static String parseWeeks(@Nullable String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return "1-16";
        }
        String value = raw.trim()
                .replace("第", "")
                .replace("周", "")
                .replace("（", "")
                .replace("）", "")
                .replace("(", "")
                .replace(")", "")
                .replace(" ", "")
                .toUpperCase(Locale.US);
        boolean odd = value.contains("单");
        boolean even = value.contains("双");
        Matcher matcher = Pattern.compile("\\d+(?:-\\d+)?(?:,\\d+(?:-\\d+)?)*")
                .matcher(value);
        String base = matcher.find() ? matcher.group() : "";
        if (base.isEmpty()) {
            return "1-16";
        }
        String normalized = TimetableText.normalizeWeeks(base);
        if (normalized == null) {
            return "1-16";
        }
        if (odd) {
            return normalized + "单";
        }
        if (even) {
            return normalized + "双";
        }
        return normalized;
    }
}
