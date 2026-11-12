package org.localroute.app.study;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 学习通返回内容的解析。方法是纯函数，便于用 JVM 单测覆盖页面结构变化。
 *
 * <p>接口结构参考公开的页面逆向资料：课程列表是 JSON；作业入口需要 workEnc；
 * 作业列表是移动端 HTML，条目在 {@code li[data]} 中。
 */
public final class ChaoxingHtmlParser {

    private static final Pattern WORK_ITEM = Pattern.compile(
            "<li\\b[^>]*\\bdata\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>(.*?)</li>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern WORK_TITLE = Pattern.compile(
            "<p\\b[^>]*class\\s*=\\s*[\"'][^\"']*overHidden2[^\"']*[\"'][^>]*>(.*?)</p>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern WORK_STATUS = Pattern.compile(
            "<p\\b[^>]*class\\s*=\\s*[\"'][^\"']*\\bstatus\\b[^\"']*[\"'][^>]*>(.*?)</p>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern WORK_TIME = Pattern.compile(
            "<div\\b[^>]*class\\s*=\\s*[\"']([^\"']*\\btime\\b[^\"']*)[\"'][^>]*>(.*?)</div>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern WORK_ENC_INPUT = Pattern.compile(
            "<input\\b[^>]*\\bid\\s*=\\s*[\"']workEnc[\"'][^>]*>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern HTML_VALUE = Pattern.compile(
            "\\bvalue\\s*=\\s*[\"']([^\"']+)[\"']",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ENC_FALLBACK = Pattern.compile(
            "enc=([a-f0-9]{32})", Pattern.CASE_INSENSITIVE);
    private static final Pattern PAGE_BOX = Pattern.compile(
            "<div\\b[^>]*\\bid\\s*=\\s*[\"']page[\"'][^>]*>(.*?)</div>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern PAGE_NUMBER = Pattern.compile(
            "<li\\b[^>]*>\\s*(\\d+)\\s*</li>", Pattern.CASE_INSENSITIVE);
    /** 新版分页把总数写在初始化脚本里：pageNum : 3。 */
    private static final Pattern PAGE_SCRIPT = Pattern.compile(
            "pageNum\\s*:\\s*(\\d+)", Pattern.CASE_INSENSITIVE);

    /** 聚合作业页（work/stu-work）的条目：ul.nav > li > div[role=option]。 */
    private static final Pattern AGG_ITEM = Pattern.compile(
            "<li\\b([^>]*)>(.*?)</li>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern AGG_DATA = Pattern.compile(
            "\\bdata\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern AGG_OPTION = Pattern.compile(
            "<div\\b[^>]*\\brole\\s*=\\s*[\"']option[\"'][^>]*>(.*?)</div>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern AGG_PARAGRAPH = Pattern.compile(
            "<p\\b[^>]*>(.*?)</p>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern AGG_PARAGRAPH_BLOCK = Pattern.compile(
            "<p\\b[^>]*>.*?</p>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern AGG_SPAN = Pattern.compile(
            "<span\\b([^>]*)>(.*?)</span>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern PENDING_STATUS = Pattern.compile(
            "未交|未提交|待互評|待互评|待批閱|待批阅|待批改|待做");
    private static final Pattern DONE_STATUS = Pattern.compile(
            "已完成|已批改|已互評|已互评");
    private static final Pattern EXPIRED_TEXT = Pattern.compile(
            "已结束|已截止|已过期|已過期");
    private static final Pattern REMAIN_DAYS = Pattern.compile(
            "(\\d+)\\s*天");
    private static final Pattern REMAIN_HOURS = Pattern.compile(
            "(\\d+)\\s*(?:小时|小時|时|時)");
    private static final Pattern REMAIN_MINUTES = Pattern.compile(
            "(\\d+)\\s*分(?:钟|鐘)?");
    private static final Pattern ABSOLUTE_DATE = Pattern.compile(
            "(\\d{4})[-/.年](\\d{1,2})[-/.月](\\d{1,2})日?\\s*(\\d{1,2}):(\\d{2})");
    private static final Pattern SHORT_DATE = Pattern.compile(
            "(?<!\\d)(\\d{1,2})[-/.月](\\d{1,2})日?\\s*(\\d{1,2}):(\\d{2})");

    private ChaoxingHtmlParser() {
    }

    /** 解析课程列表 JSON 的 channelList；已归档/已结课课程直接跳过。 */
    @NonNull
    public static List<ChaoxingCourse> parseCourseList(@Nullable JSONObject root) {
        List<ChaoxingCourse> courses = new ArrayList<>();
        if (root == null) {
            return courses;
        }
        JSONArray channels = root.optJSONArray("channelList");
        if (channels == null) {
            return courses;
        }
        for (int i = 0; i < channels.length(); i++) {
            JSONObject channel = channels.optJSONObject(i);
            if (channel == null) {
                continue;
            }
            JSONObject content = channel.optJSONObject("content");
            if (content == null || content.optInt("isretire", 0) != 0) {
                continue;
            }
            String classId = content.optString("id", "");
            String cpi = content.optString("cpi", "");
            JSONObject courseBlock = content.optJSONObject("course");
            JSONArray data = courseBlock == null ? null : courseBlock.optJSONArray("data");
            if (data == null || classId.isEmpty() || cpi.isEmpty()) {
                continue;
            }
            for (int j = 0; j < data.length(); j++) {
                JSONObject item = data.optJSONObject(j);
                if (item == null) {
                    continue;
                }
                String courseId = item.optString("id", "");
                String name = item.optString("name", "").trim();
                if (courseId.isEmpty() || name.isEmpty()) {
                    continue;
                }
                courses.add(new ChaoxingCourse(courseId, classId, cpi, name,
                        item.optString("teacherfactor", "").trim()));
            }
        }
        return courses;
    }

    /** 从课程中间页提取 workEnc；先找隐藏 input，再从任意 enc= 兜底。 */
    @Nullable
    public static String extractWorkEnc(@Nullable String html) {
        if (html == null || html.isEmpty()) {
            return null;
        }
        Matcher input = WORK_ENC_INPUT.matcher(html);
        if (input.find()) {
            Matcher value = HTML_VALUE.matcher(input.group());
            if (value.find() && isHex32(value.group(1))) {
                return value.group(1);
            }
        }
        Matcher fallback = ENC_FALLBACK.matcher(html);
        if (fallback.find()) {
            String enc = fallback.group(1);
            return isHex32(enc) ? enc : null;
        }
        return null;
    }

    /** 解析一页作业列表。 */
    @NonNull
    public static WorkPage parseWorkPage(@Nullable String html, String courseName, long nowMillis) {
        List<HomeworkItem> items = new ArrayList<>();
        if (html == null || html.isEmpty()) {
            return new WorkPage(items, 1);
        }
        Matcher itemMatcher = WORK_ITEM.matcher(html);
        while (itemMatcher.find()) {
            String url = decodeEntities(itemMatcher.group(1)).trim();
            String block = itemMatcher.group(2);
            String title = stripTags(firstGroup(WORK_TITLE, block));
            if (title.isEmpty()) {
                continue;
            }
            String statusText = stripTags(firstGroup(WORK_STATUS, block));
            String timeRaw = "";
            boolean hasTime = false;
            boolean notOver = false;
            Matcher timeMatcher = WORK_TIME.matcher(block);
            if (timeMatcher.find()) {
                hasTime = true;
                String classes = timeMatcher.group(1);
                notOver = classes.contains("notOver");
                timeRaw = stripTags(timeMatcher.group(2));
            }
            HomeworkItem.Status status = statusOf(statusText);
            boolean expired = false;
            long deadline = 0L;
            if (hasTime) {
                long remaining = parseRemainingMillis(timeRaw);
                if (remaining >= 0) {
                    deadline = nowMillis + remaining;
                } else {
                    deadline = parseAbsoluteDeadline(timeRaw, nowMillis);
                    if (deadline == 0L && (!notOver || EXPIRED_TEXT.matcher(timeRaw).find())) {
                        expired = true;
                    }
                }
                if (deadline > 0L && deadline <= nowMillis
                        && status != HomeworkItem.Status.DONE) {
                    expired = true;
                }
            } else if (status == HomeworkItem.Status.PENDING) {
                // 未交但没有时间块：按接口文档视为已过期。
                expired = true;
            }
            if (expired) {
                status = HomeworkItem.Status.EXPIRED;
            }
            boolean peer = block.contains(">互评<") || block.contains(">互評<");
            items.add(new HomeworkItem(courseName, title, statusText, status,
                    deadline, timeRaw, url, peer));
        }
        return new WorkPage(items, parseTotalPages(html));
    }

    /**
     * 聚合作业页（{@code mooc1-api.chaoxing.com/work/stu-work}）的识别与解析。
     *
     * <p>这个页面一次返回所有课程的作业，条目形如
     * {@code ul.nav > li[data] > div[role=option]}：标题在第一个 {@code p}，
     * 状态、课程名、剩余时间依次在 {@code span} 中，剩余时间通常带 {@code fr} class。
     * 不是这个页面结构时返回 {@code null}，调用方可以退回逐课程读取。
     */
    @Nullable
    public static List<HomeworkItem> parseAggregateWorkPage(@Nullable String html, long nowMillis) {
        if (!looksLikeAggregatePage(html)) {
            return null;
        }
        List<HomeworkItem> items = new ArrayList<>();
        Matcher itemMatcher = AGG_ITEM.matcher(html);
        while (itemMatcher.find()) {
            String attributes = itemMatcher.group(1);
            String block = itemMatcher.group(2);
            String url = "";
            Matcher data = AGG_DATA.matcher(attributes);
            if (data.find()) {
                url = decodeEntities(data.group(1)).trim();
            }
            Matcher optionMatcher = AGG_OPTION.matcher(block);
            String option = optionMatcher.find() ? optionMatcher.group(1) : block;
            String title = stripTags(firstGroup(AGG_PARAGRAPH, option));
            if (title.isEmpty()) {
                continue;
            }
            // 标题里的 span 不算状态/课程/剩余时间，先去掉 p 块再读 span。
            String meta = AGG_PARAGRAPH_BLOCK.matcher(option).replaceAll(" ");
            String statusText = "";
            String courseName = "";
            String timeRaw = "";
            boolean statusSeen = false;
            Matcher spanMatcher = AGG_SPAN.matcher(meta);
            while (spanMatcher.find()) {
                String classes = spanMatcher.group(1);
                String text = stripTags(spanMatcher.group(2));
                if (text.isEmpty()) {
                    continue;
                }
                if (classes.contains("fr") || looksLikeRemainingTime(text)) {
                    if (timeRaw.isEmpty()) {
                        timeRaw = text;
                    }
                } else if (!statusSeen) {
                    statusText = text;
                    statusSeen = true;
                } else if (courseName.isEmpty()) {
                    courseName = text;
                }
            }
            HomeworkItem.Status status = statusOf(statusText);
            long deadline = 0L;
            boolean expired = false;
            if (!timeRaw.isEmpty()) {
                long remaining = parseRemainingMillis(timeRaw);
                if (remaining >= 0L) {
                    deadline = nowMillis + remaining;
                } else {
                    deadline = parseAbsoluteDeadline(timeRaw, nowMillis);
                    if (deadline == 0L && EXPIRED_TEXT.matcher(timeRaw).find()) {
                        expired = true;
                    }
                }
                if (deadline > 0L && deadline <= nowMillis
                        && status != HomeworkItem.Status.DONE) {
                    expired = true;
                }
            }
            if (expired) {
                status = HomeworkItem.Status.EXPIRED;
            }
            boolean peer = block.contains("互评") || block.contains("互評");
            items.add(new HomeworkItem(courseName, title, statusText, status,
                    deadline, timeRaw, url, peer));
        }
        return items;
    }

    /** 聚合页一定带 {@code role="option"}；用它区分真正的聚合页和登录/风控页。 */
    public static boolean looksLikeAggregatePage(@Nullable String html) {
        return html != null
                && (html.contains("role=\"option\"") || html.contains("role='option'"));
    }

    private static boolean looksLikeRemainingTime(@NonNull String text) {
        return text.contains("剩余") || text.contains("剩餘")
                || EXPIRED_TEXT.matcher(text).find();
    }

    /** 解析「剩余 2天3小时」为毫秒；没有剩余时间或已过期时返回 -1。 */
    public static long parseRemainingMillis(@Nullable String text) {
        if (text == null || text.isEmpty()) {
            return -1L;
        }
        if (EXPIRED_TEXT.matcher(text).find()
                || text.contains("不限") || text.contains("无限制") || text.contains("無限制")) {
            return -1L;
        }
        String filtered = text;
        int remainIndex = text.indexOf("剩余");
        if (remainIndex < 0) {
            remainIndex = text.indexOf("剩餘");
        }
        if (remainIndex >= 0) {
            filtered = text.substring(remainIndex + 2);
        }
        long total = 0L;
        boolean found = false;
        Matcher days = REMAIN_DAYS.matcher(filtered);
        if (days.find()) {
            total += Long.parseLong(days.group(1)) * 24L * 60L * 60L * 1000L;
            found = true;
        }
        Matcher hours = REMAIN_HOURS.matcher(filtered);
        if (hours.find()) {
            total += Long.parseLong(hours.group(1)) * 60L * 60L * 1000L;
            found = true;
        }
        Matcher minutes = REMAIN_MINUTES.matcher(filtered);
        if (minutes.find()) {
            total += Long.parseLong(minutes.group(1)) * 60L * 1000L;
            found = true;
        }
        return found ? total : -1L;
    }

    /** 绝对时间：优先年-月-日，其次月-日（按抓取年份）。 */
    public static long parseAbsoluteDeadline(@Nullable String text, long nowMillis) {
        if (text == null || text.isEmpty()) {
            return 0L;
        }
        Matcher full = ABSOLUTE_DATE.matcher(text);
        if (full.find()) {
            return toMillis(Integer.parseInt(full.group(1)),
                    Integer.parseInt(full.group(2)),
                    Integer.parseInt(full.group(3)),
                    Integer.parseInt(full.group(4)),
                    Integer.parseInt(full.group(5)));
        }
        Matcher shortDate = SHORT_DATE.matcher(text);
        if (shortDate.find()) {
            Calendar calendar = Calendar.getInstance();
            calendar.setTimeInMillis(nowMillis);
            int year = calendar.get(Calendar.YEAR);
            return toMillis(year,
                    Integer.parseInt(shortDate.group(1)),
                    Integer.parseInt(shortDate.group(2)),
                    Integer.parseInt(shortDate.group(3)),
                    Integer.parseInt(shortDate.group(4)));
        }
        return 0L;
    }

    /** 分页总数，解析不到时按 1 页处理。 */
    public static int parseTotalPages(@Nullable String html) {
        if (html == null) {
            return 1;
        }
        Matcher script = PAGE_SCRIPT.matcher(html);
        if (script.find()) {
            try {
                return Math.max(1, Integer.parseInt(script.group(1)));
            } catch (NumberFormatException ignored) {
                // 数字异常时退回 DOM 解析。
            }
        }
        Matcher box = PAGE_BOX.matcher(html);
        if (!box.find()) {
            return 1;
        }
        Matcher number = PAGE_NUMBER.matcher(box.group(1));
        int max = 1;
        while (number.find()) {
            try {
                max = Math.max(max, Integer.parseInt(number.group(1)));
            } catch (NumberFormatException ignored) {
                // 非页码的 li 直接跳过。
            }
        }
        return max;
    }

    /** 登录页兜底识别：接口被重定向到 passport 时不应把登录 HTML 当作业列表。 */
    public static boolean looksLikeLogin(@Nullable String html, @Nullable String url) {
        if (url != null && url.contains("passport2.chaoxing.com")) {
            return true;
        }
        return html != null && (html.contains("id=\"loginForm\"")
                || html.contains("id='loginForm'")
                || html.contains("passport2.chaoxing.com/login"));
    }

    @NonNull
    public static String stripTags(@Nullable String html) {
        if (html == null || html.isEmpty()) {
            return "";
        }
        String withoutScripts = html
                .replaceAll("(?is)<script\\b[^>]*>.*?</script>", " ")
                .replaceAll("(?is)<style\\b[^>]*>.*?</style>", " ");
        String text = withoutScripts.replaceAll("(?s)<[^>]+>", " ");
        text = decodeEntities(text);
        return text.replaceAll("\\s+", " ").trim();
    }

    @NonNull
    private static String decodeEntities(@NonNull String value) {
        return value
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&#x27;", "'");
    }

    @NonNull
    private static HomeworkItem.Status statusOf(@NonNull String text) {
        if (PENDING_STATUS.matcher(text).find()) {
            return HomeworkItem.Status.PENDING;
        }
        if (DONE_STATUS.matcher(text).find()) {
            return HomeworkItem.Status.DONE;
        }
        return HomeworkItem.Status.OTHER;
    }

    @NonNull
    private static String firstGroup(Pattern pattern, String input) {
        Matcher matcher = pattern.matcher(input);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static boolean isHex32(@Nullable String value) {
        return value != null && value.matches("[a-fA-F0-9]{32}");
    }

    private static long toMillis(int year, int month, int day, int hour, int minute) {
        if (month < 1 || month > 12 || day < 1 || day > 31
                || hour < 0 || hour > 23 || minute < 0 || minute > 59) {
            return 0L;
        }
        Calendar calendar = Calendar.getInstance();
        calendar.clear();
        calendar.set(year, month - 1, day, hour, minute, 0);
        Date date = calendar.getTime();
        SimpleDateFormat check = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA);
        // Calendar 会把 2 月 31 日自动进位；检查回读是否一致，避免把非法日期当成有效截止时间。
        return check.format(date).equals(String.format(Locale.CHINA, "%04d-%02d-%02d %02d:%02d",
                year, month, day, hour, minute)) ? date.getTime() : 0L;
    }

    /** 一页作业列表 + 分页信息。 */
    public static final class WorkPage {
        public final List<HomeworkItem> items;
        public final int totalPages;

        public WorkPage(List<HomeworkItem> items, int totalPages) {
            this.items = items;
            this.totalPages = totalPages;
        }
    }
}
