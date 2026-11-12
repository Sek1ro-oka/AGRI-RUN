package org.localroute.app.study;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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

    private static final Pattern SECTION_RANGE = Pattern.compile(
            "^(\\d{1,2})(?:[-~至到](\\d{1,2}))?$");
    private static final Pattern COMPACT_SECTIONS = Pattern.compile("^\\d{3,4}$");

    private JwTimetableParser() {
    }

    public static final class Result {
        private final List<TimetableCourse> courses;
        private final int rawCount;

        Result(@NonNull List<TimetableCourse> courses, int rawCount) {
            this.courses = courses;
            this.rawCount = rawCount;
        }

        @NonNull
        public List<TimetableCourse> courses() {
            return courses;
        }

        public int rawCount() {
            return rawCount;
        }
    }

    /**
     * 解析 kbList 数组，也接受外层包着 kbList/对象/数组的 JSON。
     *
     * @param json WebView 里读取到的课表 JSON 文本
     */
    @NonNull
    public static Result parse(@Nullable String json) {
        List<TimetableCourse> courses = new ArrayList<>();
        if (json == null || json.trim().isEmpty()) {
            return new Result(courses, 0);
        }
        JSONArray array = findKbList(json.trim());
        if (array == null) {
            return new Result(courses, 0);
        }
        Set<String> keys = new HashSet<>();
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
            String weeks = parseWeeks(firstString(item, "zcd", "kkzc", "zc"));
            List<int[]> ranges = parseSections(sectionText);
            for (int[] range : ranges) {
                TimetableCourse course = TimetableCourse.create(
                        name, teacher, location, weekday, range[0], range[1], weeks);
                if (!course.isValid()) {
                    continue;
                }
                String key = courseKey(course);
                if (keys.add(key)) {
                    courses.add(course);
                }
            }
        }
        return new Result(courses, array.length());
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
     * 解析 jc 节次：支持「1-2」「第1-2节」「3-4,6-7」「0102」等常见形态。
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
            if (part.isEmpty()) {
                continue;
            }
            Matcher matcher = SECTION_RANGE.matcher(part);
            int start;
            int end;
            if (matcher.matches()) {
                start = Integer.parseInt(matcher.group(1));
                end = matcher.group(2) == null ? start : Integer.parseInt(matcher.group(2));
            } else if (COMPACT_SECTIONS.matcher(part).matches()) {
                int half = part.length() / 2;
                try {
                    start = Integer.parseInt(part.substring(0, half));
                    end = Integer.parseInt(part.substring(half));
                } catch (NumberFormatException ignored) {
                    continue;
                }
            } else {
                continue;
            }
            if (start >= TimetableCourse.MIN_PERIOD && end <= TimetableCourse.MAX_PERIOD
                    && start <= end) {
                result.add(new int[]{start, end});
            }
        }
        return result;
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

    @NonNull
    private static String courseKey(@NonNull TimetableCourse course) {
        return course.name() + '|' + course.teacher() + '|' + course.location() + '|'
                + course.weekday() + '|' + course.startPeriod() + '|'
                + course.endPeriod() + '|' + course.weeks();
    }
}
