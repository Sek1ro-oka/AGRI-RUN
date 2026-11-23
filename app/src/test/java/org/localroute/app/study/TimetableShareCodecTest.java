package org.localroute.app.study;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** 课表分享码：压缩往返、粘帖容错与坏输入。 */
public class TimetableShareCodecTest {

    private static TimetableCourse course(String id, String name, int weekday, int start,
                                          int end, String weeks) {
        return new TimetableCourse(id, name, "张老师", "教一101", weekday, start, end, weeks);
    }

    private static List<TimetableCourse> list(TimetableCourse... courses) {
        return new ArrayList<>(Arrays.asList(courses));
    }

    @Test
    public void roundTripsEveryField() {
        List<TimetableCourse> courses = list(
                course("a", "高等数学", 1, 1, 2, "1-16"),
                course("b", "大学英语", 3, 3, 4, "1-12双"));
        TimetableShareCodec.DecodeResult result =
                TimetableShareCodec.decode(TimetableShareCodec.encode(courses));
        assertTrue(result.error(), result.ok());
        assertEquals(2, result.courses().size());
        assertEquals("a", result.courses().get(0).id());
        assertEquals("高等数学", result.courses().get(0).name());
        assertEquals("教一101", result.courses().get(0).location());
        assertEquals(3, result.courses().get(1).weekday());
        assertEquals("1-12双", result.courses().get(1).weeks());
        assertEquals(0, result.skipped());
    }

    @Test
    public void codeCarriesNoReadableJson() {
        String code = TimetableShareCodec.encode(list(course("a", "高等数学", 1, 1, 2, "1-16")));
        assertTrue(code.startsWith(TimetableShareCodec.PREFIX));
        // 正文是 gzip 后的 Base64，不该出现裸字段名，否则说明压根没压缩。
        assertFalse(code.contains("weekday"));
        assertFalse(code.contains("高等数学"));
    }

    @Test
    public void survivesPasteFromChat() {
        String code = TimetableShareCodec.encode(list(course("a", "高等数学", 1, 1, 2, "1-16")));
        TimetableShareCodec.DecodeResult result = TimetableShareCodec.decode(
                "我的课表发你 " + code.substring(0, code.length() / 2) + "\n"
                        + code.substring(code.length() / 2) + " 记得导入哈");
        assertTrue(result.error(), result.ok());
        assertEquals(1, result.courses().size());
    }

    @Test
    public void acceptsLowercasePrefix() {
        String code = TimetableShareCodec.encode(list(course("a", "高等数学", 1, 1, 2, "1-16")));
        String lower = code.replace(TimetableShareCodec.PREFIX,
                TimetableShareCodec.PREFIX.toLowerCase());
        assertTrue(TimetableShareCodec.decode(lower).ok());
        assertTrue(TimetableShareCodec.extract(lower).startsWith(TimetableShareCodec.PREFIX));
    }

    @Test
    public void otherVersionPrefixIsNotACode() {
        String code = TimetableShareCodec.encode(list(course("a", "高等数学", 1, 1, 2, "1-16")))
                .replace(TimetableShareCodec.PREFIX, "UNLOOP2-");
        assertEquals("", TimetableShareCodec.extract("新版课表：" + code));
        assertFalse(TimetableShareCodec.looksLikeShareCode(code));
    }

    @Test
    public void plainTextHasNoCode() {
        assertEquals("", TimetableShareCodec.extract("明天没课，一起去跑步"));
        assertFalse(TimetableShareCodec.looksLikeShareCode(null));
        assertFalse(TimetableShareCodec.decode("明天没课").ok());
    }

    @Test
    public void truncatedCodeFailsWithoutThrowing() {
        String code = TimetableShareCodec.encode(list(course("a", "高等数学", 1, 1, 2, "1-16")));
        TimetableShareCodec.DecodeResult result =
                TimetableShareCodec.decode(code.substring(0, code.length() - 6));
        assertFalse("截断的码必须算失败", result.ok());
        assertFalse(result.error().isEmpty());
    }

    @Test
    public void emptyBodyFailsWithoutThrowing() {
        assertFalse(TimetableShareCodec.decode(TimetableShareCodec.PREFIX).ok());
        assertFalse(TimetableShareCodec.decode(TimetableShareCodec.PREFIX + "!!!").ok());
    }

    @Test
    public void brokenEntriesAreSkippedAndCounted() {
        String json = "[{\"id\":\"a\",\"name\":\"高等数学\",\"weekday\":1,\"start\":1,\"end\":2,"
                + "\"weeks\":\"1-16\"},{\"name\":\"没写星期几\"},\"字符串\"]";
        TimetableShareCodec.DecodeResult result = TimetableShareCodec.decodeJson(json);
        assertTrue(result.ok());
        assertEquals(1, result.courses().size());
        assertEquals(2, result.skipped());
    }

    @Test
    public void codeWithoutUsableCourseFails() {
        assertFalse(TimetableShareCodec.decodeJson("[]").ok());
        assertFalse(TimetableShareCodec.decodeJson("[{\"name\":\"缺字段\"}]").ok());
        assertFalse(TimetableShareCodec.decodeJson("不是 JSON").ok());
        assertFalse(TimetableShareCodec.decodeJson(null).ok());
    }

    @Test
    public void invalidCoursesNeverGetEncoded() {
        // TimetableCourse.isValid 要求有名字，坏条目在编码时就丢了。
        String code = TimetableShareCodec.encode(list(new TimetableCourse("x", "", null, null,
                9, 1, 1, "1-16")));
        TimetableShareCodec.DecodeResult result = TimetableShareCodec.decode(code);
        assertFalse(result.ok());
    }

    @Test
    public void oversizedPayloadIsRejectedBeforeDecompressingFully() throws Exception {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        Arrays.fill(chunk, (byte) 'a');
        for (int i = 0; i < TimetableShareCodec.MAX_JSON_BYTES / chunk.length + 2; i++) {
            raw.write(chunk);
        }
        ByteArrayOutputStream packed = new ByteArrayOutputStream();
        try (GZIPOutputStream zip = new GZIPOutputStream(packed)) {
            zip.write(raw.toByteArray());
        }
        String code = TimetableShareCodec.PREFIX
                + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(packed.toByteArray());
        TimetableShareCodec.DecodeResult result = TimetableShareCodec.decode(code);
        assertFalse(result.ok());
        assertTrue(result.error(), result.error().contains("过大"));
    }

    @Test
    public void missingIdGetsAFreshOne() {
        // 分享码里的课程 id 允许缺失：fromJson 会补一个新 id，避免撞车后互相覆盖。
        String json = "[{\"name\":\"高等数学\",\"weekday\":1,\"start\":1,\"end\":2}]";
        TimetableShareCodec.DecodeResult result = TimetableShareCodec.decodeJson(json);
        assertTrue(result.ok());
        assertEquals(1, result.courses().size());
        assertFalse(result.courses().get(0).id().isEmpty());
        assertEquals("1-16", result.courses().get(0).weeks());
    }

    @Test
    public void longTimetableStillRoundTrips() {
        List<TimetableCourse> courses = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            courses.add(course("c" + i, "课程" + i, i % 7 + 1, i % 4 * 3 + 1, i % 4 * 3 + 2,
                    "1-16"));
        }
        String code = TimetableShareCodec.encode(courses);
        TimetableShareCodec.DecodeResult result = TimetableShareCodec.decode(code);
        assertTrue(result.error(), result.ok());
        assertEquals(40, result.courses().size());
        assertTrue("重复字段名压缩后应短于原文",
                code.length() < jsonOf(courses).length());
    }

    private static String jsonOf(List<TimetableCourse> courses) {
        return TimetableCodec.encode(courses);
    }
}
