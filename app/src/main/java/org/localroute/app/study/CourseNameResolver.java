package org.localroute.app.study;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 聚合作业页会把课程名渲染成「《计算机组成原...》」。作业链接里带 courseId，
 * 用课程列表 JSON 的完整名称按 id 精确补全；没有 id 时退回唯一前缀匹配，
 * 匹配不到或多门课同前缀时保留页面原文。
 */
public final class CourseNameResolver {

    private static final Pattern COURSE_ID = Pattern.compile(
            "[?&]courseId=(\\d+)", Pattern.CASE_INSENSITIVE);

    private CourseNameResolver() {
    }

    /** 返回补全后的课程名；无需补全、找不到唯一匹配时返回 null。 */
    @Nullable
    public static String resolve(@Nullable String rawName, @Nullable String url,
                                 @NonNull Map<String, String> namesById) {
        String courseId = courseIdOf(url);
        if (courseId != null) {
            String name = namesById.get(courseId);
            if (name != null) {
                String normalized = normalize(name);
                if (!normalized.isEmpty()) {
                    return normalized;
                }
            }
        }
        if (!mayBeTruncated(rawName)) {
            return null;
        }
        String prefix = normalize(rawName);
        if (prefix.length() < 2) {
            return null;
        }
        String match = null;
        for (String value : namesById.values()) {
            String name = normalize(value);
            if (name.isEmpty() || !name.startsWith(prefix)) {
                continue;
            }
            if (match == null) {
                match = name;
            } else if (!match.equals(name)) {
                // 同时匹配多门课（例如「计算机组成原理」和「计算机组成原理实验」），宁可保留原文也不猜。
                return null;
            }
        }
        return match;
    }

    /** 课程名带书名号或被省略号截断时，才值得去查课程列表。 */
    public static boolean mayBeTruncated(@Nullable String rawName) {
        if (rawName == null) {
            return false;
        }
        String trimmed = rawName.trim();
        String normalized = normalize(trimmed);
        return !normalized.isEmpty() && !normalized.equals(trimmed);
    }

    /** 作业链接里的 courseId；没有时返回 null。 */
    @Nullable
    public static String courseIdOf(@Nullable String url) {
        if (url == null || url.isEmpty()) {
            return null;
        }
        Matcher matcher = COURSE_ID.matcher(url);
        return matcher.find() ? matcher.group(1) : null;
    }

    /** 去掉书名号和末尾的省略号，得到可用于匹配的文本。 */
    @NonNull
    static String normalize(@Nullable String rawName) {
        if (rawName == null) {
            return "";
        }
        String text = rawName.trim();
        if (text.length() >= 2) {
            char first = text.charAt(0);
            char last = text.charAt(text.length() - 1);
            if ((first == '《' && last == '》')
                    || (first == '〈' && last == '〉')
                    || (first == '「' && last == '」')) {
                text = text.substring(1, text.length() - 1).trim();
            }
        }
        while (true) {
            if (text.endsWith("…") || text.endsWith("⋯")) {
                text = text.substring(0, text.length() - 1).trim();
            } else if (text.endsWith("...")) {
                text = text.substring(0, text.length() - 3).trim();
            } else if (text.endsWith("..")) {
                text = text.substring(0, text.length() - 2).trim();
            } else {
                return text;
            }
        }
    }
}
