package org.localroute.app.study;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 课表文本字段的校验与归一化：手动编辑与教务导入共用。 */
public final class TimetableText {

    private static final Pattern WEEKS = Pattern.compile(
            "\\d+(?:-\\d+)?(?:,\\d+(?:-\\d+)?)*(?:单|双)?");
    private static final Pattern WEEK_RANGE = Pattern.compile(
            "^(\\d{1,2})(?:-(\\d{1,2}))?$");
    private static final Pattern PERIODS = Pattern.compile(
            "^(\\d{1,2})\\s*(?:[-~至到]\\s*(\\d{1,2}))?$");

    private TimetableText() {
    }

    /** 1-2 / 1 / 第1-2节。返回 [开始, 结束]。 */
    @Nullable
    public static int[] parsePeriods(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim()
                .replace("第", "")
                .replace("节", "")
                .replace(" ", "");
        Matcher matcher = PERIODS.matcher(value);
        if (!matcher.matches()) {
            return null;
        }
        int start = Integer.parseInt(matcher.group(1));
        int end = matcher.group(2) == null ? start : Integer.parseInt(matcher.group(2));
        return validPeriods(start, end);
    }

    /** 第1-16周 / 1-16 / 1-16单。默认 1-16。 */
    @Nullable
    public static String normalizeWeeks(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim()
                .replace("第", "")
                .replace("周", "")
                .replace("，", ",")
                .replace(" ", "");
        if (value.isEmpty()) {
            return "1-16";
        }
        if (value.endsWith("单周")) {
            value = value.substring(0, value.length() - 2) + "单";
        } else if (value.endsWith("双周")) {
            value = value.substring(0, value.length() - 2) + "双";
        }
        return WEEKS.matcher(value).matches() ? value : null;
    }

    @Nullable
    private static int[] validPeriods(int start, int end) {
        if (start < TimetableCourse.MIN_PERIOD || end > TimetableCourse.MAX_PERIOD
                || start > end) {
            return null;
        }
        return new int[]{start, end};
    }

    @NonNull
    public static String formatPeriods(int start, int end) {
        return start == end ? String.valueOf(start) : start + "-" + end;
    }

    /**
     * 课程是否在指定教学周上课。支持「1-16」「3-4,6-7」「1-16单」「1-16双」；
     * 文本无法解析时按「每周都上」处理，避免课程从课表中消失。
     */
    public static boolean activeInWeek(@Nullable String weeks, int week) {
        if (week < 1) {
            return false;
        }
        ParsedWeeks parsed = parseWeeks(weeks);
        if (parsed.ranges.isEmpty()) {
            return true;
        }
        for (int[] range : parsed.ranges) {
            if (week < range[0] || week > range[1]) {
                continue;
            }
            if (parsed.odd && week % 2 == 0) {
                return false;
            }
            if (parsed.even && week % 2 == 1) {
                return false;
            }
            return true;
        }
        return false;
    }

    /** 课程周次覆盖到的最大周；无法解析时返回 0。 */
    public static int maxWeek(@Nullable String weeks) {
        int max = 0;
        for (int[] range : parseWeeks(weeks).ranges) {
            max = Math.max(max, range[1]);
        }
        return max;
    }

    @NonNull
    private static ParsedWeeks parseWeeks(@Nullable String raw) {
        String value = raw == null ? "" : raw.trim()
                .replace("第", "")
                .replace("周", "")
                .replace("，", ",")
                .replace(" ", "");
        boolean odd = false;
        boolean even = false;
        if (value.endsWith("单")) {
            odd = true;
            value = value.substring(0, value.length() - 1);
        } else if (value.endsWith("双")) {
            even = true;
            value = value.substring(0, value.length() - 1);
        }
        ParsedWeeks parsed = new ParsedWeeks(odd, even);
        if (value.isEmpty()) {
            return parsed;
        }
        for (String part : value.split(",")) {
            if (part.isEmpty()) {
                continue;
            }
            Matcher matcher = WEEK_RANGE.matcher(part);
            if (!matcher.matches()) {
                continue;
            }
            try {
                int start = Integer.parseInt(matcher.group(1));
                int end = matcher.group(2) == null
                        ? start : Integer.parseInt(matcher.group(2));
                if (start >= 1 && start <= end) {
                    parsed.ranges.add(new int[]{start, end});
                }
            } catch (NumberFormatException ignored) {
                // 单个异常区间跳过，不影响其它周次。
            }
        }
        return parsed;
    }

    private static final class ParsedWeeks {
        final boolean odd;
        final boolean even;
        final java.util.List<int[]> ranges = new java.util.ArrayList<>();

        ParsedWeeks(boolean odd, boolean even) {
            this.odd = odd;
            this.even = even;
        }
    }
}
