package org.localroute.app.study;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 课表文本字段的校验与归一化：手动编辑与教务导入共用。 */
public final class TimetableText {

    private static final Pattern WEEKS = Pattern.compile(
            "\\d+(?:-\\d+)?(?:,\\d+(?:-\\d+)?)*(?:单|双)?");
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
}
