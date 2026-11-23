package org.localroute.app.study;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * 课表分享码：一段可以直接发微信的文本，对方粘进来就能导入。
 *
 * <p>正文是 {@code UNLOOP1-} + Base64URL(GZIP(课程 JSON))。要压缩是因为课表 JSON 里
 * 全是重复字段名，几十门课 gzip 后短得多，粘到聊天框里不至于糊满屏幕。
 *
 * <p>解码保持宽容：从一堆闲聊文本里挑出分享码本体，认不出的课程条目跳过并计数，
 * 但前缀版本号不认识时直接报错——把别的格式硬当课表读，用户只会得到一份看不懂的课表。
 *
 * <p>纯 Java，不碰 Android API（{@code java.util.Base64} 从 API 26 起就有），可在 JVM 测试里跑。
 */
public final class TimetableShareCodec {

    /** 版本前缀；将来字段结构变了就换 {@code UNLOOP2-}，旧版仍能报「不认识」。 */
    public static final String PREFIX = "UNLOOP1-";

    /** 分享码本身的上限，避免误粘一篇日志后把时间耗在解压上。 */
    static final int MAX_CODE_CHARS = 60_000;
    /** 解压后的上限，挡住「几 KB 解出几百 MB」这类压缩包。 */
    static final int MAX_JSON_BYTES = 2_000_000;

    private TimetableShareCodec() {
    }

    /** 坏课程交给 {@link TimetableCodec#encode} 跳过，因此返回的码一定可解。 */
    @NonNull
    public static String encode(@NonNull List<TimetableCourse> courses) {
        String json = TimetableCodec.encode(courses);
        return PREFIX + base64(gzip(json.getBytes(StandardCharsets.UTF_8)));
    }

    /** 文本里是否含分享码；用户从聊天里连上下文一起粘进来时用它先筛一下。 */
    public static boolean looksLikeShareCode(@Nullable String text) {
        return text != null && indexOfPrefix(text.replace(" ", "")) >= 0;
    }

    /**
     * 从任意粘贴文本里取出分享码本体：去掉空白，取前缀之后连续的首部字符。
     * 找不到时返回空串。
     */
    @NonNull
    public static String extract(@Nullable String pasted) {
        if (pasted == null) {
            return "";
        }
        StringBuilder compact = new StringBuilder(pasted.length());
        for (int i = 0; i < pasted.length(); i++) {
            char c = pasted.charAt(i);
            if (!Character.isWhitespace(c)) {
                compact.append(c);
            }
        }
        String text = compact.toString();
        int start = indexOfPrefix(text);
        if (start < 0) {
            return "";
        }
        int body = start + PREFIX.length();
        int end = body;
        while (end < text.length() && isBase64Url(text.charAt(end))) {
            end++;
        }
        // 小写前缀也认，但一律写成规范形式返回，免得后面 startsWith 判错。
        return PREFIX + text.substring(body, Math.min(end, body + MAX_CODE_CHARS));
    }

    @NonNull
    public static DecodeResult decode(@Nullable String pasted) {
        String code = extract(pasted);
        if (code.isEmpty()) {
            return DecodeResult.failure("没找到 UNLOOP 分享码");
        }
        byte[] packed;
        try {
            packed = Base64.getUrlDecoder().decode(code.substring(PREFIX.length()));
        } catch (IllegalArgumentException e) {
            return DecodeResult.failure("分享码被改动，解不开");
        }
        String json;
        try {
            json = new String(gunzip(packed), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return DecodeResult.failure("分享码内容损坏：" + e.getMessage());
        }
        return decodeJson(json);
    }

    /** 分享码正文（课程 JSON）直接解码，测试与后备路径都用它。 */
    @NonNull
    public static DecodeResult decodeJson(@Nullable String json) {
        List<TimetableCourse> courses = new ArrayList<>();
        int skipped = 0;
        JSONArray array;
        try {
            array = new JSONArray(json == null ? "" : json);
        } catch (JSONException e) {
            return DecodeResult.failure("课表内容不是列表，读不了");
        }
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) {
                skipped++;
                continue;
            }
            TimetableCourse course = TimetableCourse.fromJson(item);
            if (course == null) {
                skipped++;
            } else {
                courses.add(course);
            }
        }
        if (courses.isEmpty()) {
            return DecodeResult.failure(skipped > 0 ? "分享码里的课程都不合法" : "分享码里没有课程");
        }
        return DecodeResult.success(courses, skipped);
    }

    @NonNull
    private static String base64(@NonNull byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    @NonNull
    private static byte[] gzip(@NonNull byte[] raw) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream zip = new GZIPOutputStream(out)) {
            zip.write(raw);
        } catch (IOException e) {
            // ByteArrayOutputStream 不会真失败；退化成不压缩也比丢课表好。
            return raw;
        }
        return out.toByteArray();
    }

    @NonNull
    private static byte[] gunzip(@NonNull byte[] packed) throws IOException {
        try (InputStream input = new GZIPInputStream(new ByteArrayInputStream(packed))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            long total = 0;
            while ((read = input.read(buffer)) > 0) {
                total += read;
                if (total > MAX_JSON_BYTES) {
                    throw new IOException("内容过大（超过 " + MAX_JSON_BYTES / 1024 + " KB）");
                }
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    private static int indexOfPrefix(@NonNull String text) {
        int upper = text.indexOf(PREFIX);
        if (upper >= 0) {
            return upper;
        }
        return text.indexOf(PREFIX.toLowerCase(java.util.Locale.US));
    }

    private static boolean isBase64Url(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                || c == '-' || c == '_' || c == '=';
    }

    /** 解码结果：课程列表、跳过的坏条目数，或一句失败原因。 */
    public static final class DecodeResult {
        private final List<TimetableCourse> courses;
        private final int skipped;
        private final String error;

        private DecodeResult(List<TimetableCourse> courses, int skipped, String error) {
            this.courses = courses;
            this.skipped = skipped;
            this.error = error;
        }

        @NonNull
        static DecodeResult success(@NonNull List<TimetableCourse> courses, int skipped) {
            return new DecodeResult(courses, skipped, "");
        }

        @NonNull
        static DecodeResult failure(@NonNull String error) {
            return new DecodeResult(new ArrayList<>(), 0, error);
        }

        public boolean ok() {
            return error.isEmpty();
        }

        @NonNull
        public List<TimetableCourse> courses() {
            return courses;
        }

        public int skipped() {
            return skipped;
        }

        @NonNull
        public String error() {
            return error;
        }
    }
}
