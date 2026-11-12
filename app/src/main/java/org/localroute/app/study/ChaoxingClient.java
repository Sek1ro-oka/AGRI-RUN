package org.localroute.app.study;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 只读学习通接口：课程列表、作业入口、作业列表。
 *
 * <p>Cookie 全部来自用户在官方 WebView 页面完成登录后捕获的会话，本类不接触账号密码。
 * 请求串行发出并带有间隔，避免短时间内触发平台风控。
 */
public final class ChaoxingClient {

    public static final String LOGIN_URL =
            "https://passport2.chaoxing.com/login?fid=&newversion=true&refer=https%3A%2F%2Fi.chaoxing.com";
    private static final String COURSE_LIST_URL =
            "https://mooc1-api.chaoxing.com/mycourse/backclazzdata?view=json&rss=1";
    private static final String SSO_USER_URL =
            "https://sso.chaoxing.com/apis/login/userLogin4UAP.do";
    private static final String WORK_LIST_URL =
            "https://mooc1.chaoxing.com/mooc-ans/mooc2/work/list";
    /** 聚合作业页：一次请求拿到所有课程的作业，明显降低触发风控的概率。 */
    private static final String AGGREGATE_WORK_URL =
            "https://mooc1-api.chaoxing.com/work/stu-work";
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
    private static final Pattern CHARSET = Pattern.compile(
            "charset=([^;]+)", Pattern.CASE_INSENSITIVE);
    private static final int MAX_PAGES = 20;
    private static final long COURSE_DELAY_MS = 320L;

    private final LinkedHashMap<String, String> cookies = new LinkedHashMap<>();

    public ChaoxingClient(@Nullable String cookieHeader) {
        if (cookieHeader != null) {
            for (String part : cookieHeader.split(";")) {
                int equals = part.indexOf('=');
                if (equals <= 0) {
                    continue;
                }
                String name = part.substring(0, equals).trim();
                String value = part.substring(equals + 1).trim();
                if (!name.isEmpty() && !value.isEmpty()) {
                    cookies.put(name, value);
                }
            }
        }
    }

    @NonNull
    public String cookieHeader() {
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, String> entry : cookies.entrySet()) {
            if (builder.length() > 0) {
                builder.append("; ");
            }
            builder.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return builder.toString();
    }

    /** 课程列表能正常返回 result=1 就说明 Cookie 仍然有效。 */
    public boolean validateSession() throws IOException {
        try {
            requestCourses();
            return true;
        } catch (SessionExpiredException expired) {
            return false;
        }
    }

    /** 登录后向 SSO 查询展示用昵称；失败不影响绑定。 */
    @NonNull
    public String fetchUserName() {
        try {
            Response response = get(SSO_USER_URL, "https://i.chaoxing.com/");
            JSONObject root = new JSONObject(response.body);
            JSONObject message = root.optJSONObject("msg");
            if (message != null) {
                return message.optString("name", "").trim();
            }
        } catch (IOException | JSONException ignored) {
            // 昵称只是辅助信息，取不到时用 uid 显示。
        }
        return "";
    }

    @NonNull
    public FetchResult fetchHomework(@Nullable ProgressListener listener) throws IOException {
        if (listener != null) {
            listener.onProgress(0, 1, "");
        }
        List<HomeworkItem> aggregate = null;
        try {
            aggregate = requestAggregateHomework();
        } catch (SessionExpiredException | AntiSpiderException fatal) {
            throw fatal;
        } catch (IOException | RuntimeException ignored) {
            // 聚合页不可用时退回逐课程读取，兼容旧版页面。
        }
        if (aggregate != null) {
            aggregate = fillAggregateCourseNames(aggregate);
            if (listener != null) {
                listener.onProgress(1, 1, "");
            }
            return new FetchResult(sortByDeadline(aggregate), 0,
                    System.currentTimeMillis(), cookieHeader(), false);
        }

        List<ChaoxingCourse> courses = requestCourses();
        List<HomeworkItem> items = new ArrayList<>();
        int failed = 0;
        boolean blocked = false;
        for (int index = 0; index < courses.size(); index++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new IOException("已取消");
            }
            ChaoxingCourse course = courses.get(index);
            if (listener != null) {
                listener.onProgress(index, courses.size(), course.name);
            }
            try {
                fetchCourseHomework(course, items);
            } catch (SessionExpiredException expired) {
                throw expired;
            } catch (AntiSpiderException blockedError) {
                // 已经读到的课程不再丢弃；完全没有结果时才要求用户做安全验证。
                blocked = true;
                failed++;
                break;
            } catch (IOException | RuntimeException error) {
                failed++;
            }
            if (index < courses.size() - 1) {
                sleep(COURSE_DELAY_MS);
            }
        }
        if (blocked && items.isEmpty()) {
            throw new AntiSpiderException();
        }
        if (listener != null) {
            listener.onProgress(courses.size(), courses.size(), "");
        }
        return new FetchResult(sortByDeadline(items), failed,
                System.currentTimeMillis(), cookieHeader(), blocked);
    }

    /** 聚合页返回 null 表示页面结构不认识，需要退回逐课程读取。 */
    @Nullable
    private List<HomeworkItem> requestAggregateHomework() throws IOException {
        Response response = get(AGGREGATE_WORK_URL, "https://i.chaoxing.com/");
        ensureUsable(response);
        List<HomeworkItem> items = ChaoxingHtmlParser.parseAggregateWorkPage(
                response.body, System.currentTimeMillis());
        // 识别到聚合页却一条都没读出来时退回逐课程，避免结构变化被误报成「没有作业」。
        return items == null || items.isEmpty() ? null : items;
    }

    /**
     * 聚合页把课程名截断成「《计算机组成原...》」，用课程列表的完整名称补全。
     * 作业链接带 courseId，可以直接精确匹配；课程列表请求失败时保留页面原文。
     */
    @NonNull
    private List<HomeworkItem> fillAggregateCourseNames(@NonNull List<HomeworkItem> items) {
        boolean truncated = false;
        for (HomeworkItem item : items) {
            if (CourseNameResolver.mayBeTruncated(item.courseName())) {
                truncated = true;
                break;
            }
        }
        if (!truncated) {
            return items;
        }
        Map<String, String> namesById;
        try {
            namesById = ChaoxingHtmlParser.parseCourseNames(requestCourseListJson());
        } catch (IOException | RuntimeException ignored) {
            return items;
        }
        if (namesById.isEmpty()) {
            return items;
        }
        List<HomeworkItem> resolved = new ArrayList<>(items.size());
        for (HomeworkItem item : items) {
            String name = CourseNameResolver.resolve(
                    item.courseName(), item.url(), namesById);
            resolved.add(name == null ? item : item.withCourseName(name));
        }
        return resolved;
    }

    @NonNull
    private static List<HomeworkItem> sortByDeadline(@NonNull List<HomeworkItem> items) {
        List<HomeworkItem> sorted = new ArrayList<>(items);
        sorted.sort((left, right) -> {
            int rank = Integer.compare(left.statusRank(), right.statusRank());
            if (rank != 0) {
                return rank;
            }
            long leftDeadline = left.deadlineEpochMillis();
            long rightDeadline = right.deadlineEpochMillis();
            if (leftDeadline == 0L && rightDeadline == 0L) {
                return left.title().compareTo(right.title());
            }
            if (leftDeadline == 0L) {
                return 1;
            }
            if (rightDeadline == 0L) {
                return -1;
            }
            return Long.compare(leftDeadline, rightDeadline);
        });
        return sorted;
    }

    private void fetchCourseHomework(ChaoxingCourse course, List<HomeworkItem> out)
            throws IOException {
        String enc = fetchWorkEnc(course);
        int page = 1;
        int totalPages = 1;
        do {
            String url = WORK_LIST_URL
                    + "?courseId=" + encode(course.courseId)
                    + "&classId=" + encode(course.classId)
                    + "&cpi=" + encode(course.cpi)
                    + "&enc=" + encode(enc)
                    + "&pageNum=" + page;
            Response response = get(url, course.courseUrl());
            ensureUsable(response);
            ChaoxingHtmlParser.WorkPage workPage = ChaoxingHtmlParser.parseWorkPage(
                    response.body, course.name, System.currentTimeMillis());
            out.addAll(workPage.items);
            totalPages = Math.min(Math.max(1, workPage.totalPages), MAX_PAGES);
            page++;
            if (page <= totalPages) {
                sleep(450L);
            }
        } while (page <= totalPages);
    }

    @NonNull
    private String fetchWorkEnc(ChaoxingCourse course) throws IOException {
        Response response = get(course.courseUrl(), COURSE_LIST_URL);
        ensureUsable(response);
        String enc = ChaoxingHtmlParser.extractWorkEnc(response.body);
        if (enc == null) {
            throw new IOException("未找到作业入口");
        }
        return enc;
    }

    @NonNull
    private List<ChaoxingCourse> requestCourses() throws IOException {
        return ChaoxingHtmlParser.parseCourseList(requestCourseListJson());
    }

    @NonNull
    private JSONObject requestCourseListJson() throws IOException {
        Response response = get(COURSE_LIST_URL, "https://i.chaoxing.com/");
        ensureUsable(response);
        try {
            JSONObject root = new JSONObject(response.body);
            if (root.optInt("result", 0) != 1) {
                throw new SessionExpiredException();
            }
            return root;
        } catch (JSONException error) {
            throw new IOException("课程列表返回格式异常", error);
        }
    }

    private void ensureUsable(Response response) throws IOException {
        if (response.code == 202 || response.url.contains("antispiderShowVerify")) {
            throw new AntiSpiderException();
        }
        if (response.code >= 400) {
            throw new IOException("学习通返回 HTTP " + response.code);
        }
        if (ChaoxingHtmlParser.looksLikeLogin(response.body, response.url)) {
            throw new SessionExpiredException();
        }
    }

    @NonNull
    private Response get(String url, @Nullable String referer) throws IOException {
        String current = url;
        String currentReferer = referer;
        for (int hop = 0; hop < 6; hop++) {
            HttpURLConnection connection = (HttpURLConnection) new URL(current).openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(20000);
            connection.setUseCaches(false);
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setRequestProperty("Accept",
                    "text/html,application/json,application/xhtml+xml,*/*;q=0.8");
            connection.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
            connection.setRequestProperty("Accept-Encoding", "identity");
            if (currentReferer != null) {
                connection.setRequestProperty("Referer", currentReferer);
            }
            String cookie = cookieHeader();
            if (!cookie.isEmpty()) {
                connection.setRequestProperty("Cookie", cookie);
            }
            int code = connection.getResponseCode();
            absorbCookies(connection);
            if (code >= 300 && code < 400) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                if (location == null || location.isEmpty()) {
                    throw new IOException("学习通重定向缺少地址");
                }
                currentReferer = current;
                current = new URL(new URL(current), location).toString();
                continue;
            }
            InputStream stream = code >= 400
                    ? connection.getErrorStream() : connection.getInputStream();
            String body = stream == null ? "" : readAll(stream, charsetOf(
                    connection.getContentType()));
            connection.disconnect();
            return new Response(code, current, body);
        }
        throw new IOException("学习通重定向次数过多");
    }

    private void absorbCookies(HttpURLConnection connection) {
        for (Map.Entry<String, List<String>> entry : connection.getHeaderFields().entrySet()) {
            String key = entry.getKey();
            if (key == null || !"Set-Cookie".equalsIgnoreCase(key)) {
                continue;
            }
            for (String value : entry.getValue()) {
                absorbCookie(value);
            }
        }
    }

    private void absorbCookie(String header) {
        if (header == null) {
            return;
        }
        String[] parts = header.split(";");
        if (parts.length == 0) {
            return;
        }
        int equals = parts[0].indexOf('=');
        if (equals <= 0) {
            return;
        }
        String name = parts[0].substring(0, equals).trim();
        String value = parts[0].substring(equals + 1).trim();
        boolean expired = value.isEmpty()
                || header.toLowerCase(Locale.US).contains("max-age=0")
                || header.toLowerCase(Locale.US).contains("1970");
        if (name.isEmpty()) {
            return;
        }
        if (expired) {
            cookies.remove(name);
        } else {
            cookies.put(name, value);
        }
    }

    private static String readAll(InputStream stream, Charset charset) throws IOException {
        try (InputStream input = stream;
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return output.toString(charset.name());
        }
    }

    private static Charset charsetOf(@Nullable String contentType) {
        if (contentType != null) {
            Matcher matcher = CHARSET.matcher(contentType);
            if (matcher.find()) {
                try {
                    return Charset.forName(matcher.group(1).trim().replace("\"", ""));
                } catch (RuntimeException ignored) {
                    // 未知字符集按 UTF-8 处理。
                }
            }
        }
        return StandardCharsets.UTF_8;
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (Exception error) {
            return value;
        }
    }

    private static void sleep(long millis) throws IOException {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("已取消", interrupted);
        }
    }

    public interface ProgressListener {
        void onProgress(int done, int total, String courseName);
    }

    /** 一次抓取的结果；cookieHeader 可能包含服务端新下发的路由 Cookie。 */
    public static final class FetchResult {
        public final List<HomeworkItem> items;
        public final int failedCourses;
        public final long fetchedAt;
        public final String cookieHeader;
        /** 有课程因安全验证被跳过，本次只返回部分结果。 */
        public final boolean blocked;

        FetchResult(List<HomeworkItem> items, int failedCourses,
                    long fetchedAt, String cookieHeader, boolean blocked) {
            this.items = items;
            this.failedCourses = failedCourses;
            this.fetchedAt = fetchedAt;
            this.cookieHeader = cookieHeader;
            this.blocked = blocked;
        }
    }

    public static final class SessionExpiredException extends IOException {
    }

    public static final class AntiSpiderException extends IOException {
        public AntiSpiderException() {
            super("学习通要求安全验证");
        }
    }

    private static final class Response {
        final int code;
        final String url;
        final String body;

        Response(int code, String url, String body) {
            this.code = code;
            this.url = url;
            this.body = body;
        }
    }
}
