package org.localroute.app.study;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** 自建作业的编解码：往返一致、坏条目跳过、提前量收敛。 */
public class CustomHomeworkCodecTest {

    @Test
    public void roundTripKeepsFields() {
        List<CustomHomework> items = Arrays.asList(
                new CustomHomework("id-1", "数据结构实验报告", 1_766_000_000_000L, 30),
                new CustomHomework("id-2", "英语作文", 1_766_100_000_000L, 1440));
        List<CustomHomework> decoded = CustomHomeworkCodec.decode(
                CustomHomeworkCodec.encode(items));
        assertEquals(2, decoded.size());
        assertEquals("id-1", decoded.get(0).id());
        assertEquals("数据结构实验报告", decoded.get(0).title());
        assertEquals(1_766_000_000_000L, decoded.get(0).deadlineEpochMillis());
        assertEquals(30, decoded.get(0).leadMinutes());
        assertEquals("英语作文", decoded.get(1).title());
        assertEquals(1440, decoded.get(1).leadMinutes());
    }

    @Test
    public void brokenEntriesAreSkipped() {
        String json = "[{\"title\":\"缺时间\",\"lead\":30},"
                + "{\"id\":\"ok\",\"title\":\"有头有尾\",\"deadline\":123,\"lead\":5},"
                + "{\"title\":\"\",\"deadline\":123},"
                + "{\"deadline\":-1}]";
        List<CustomHomework> decoded = CustomHomeworkCodec.decode(json);
        assertEquals(1, decoded.size());
        assertEquals("ok", decoded.get(0).id());
    }

    @Test
    public void corruptedPayloadDecodesToEmpty() {
        assertTrue(CustomHomeworkCodec.decode("不是 JSON").isEmpty());
        assertTrue(CustomHomeworkCodec.decode(null).isEmpty());
        assertTrue(CustomHomeworkCodec.decode("").isEmpty());
    }

    @Test
    public void leadClampedOnDecode() {
        List<CustomHomework> decoded = CustomHomeworkCodec.decode(
                "[{\"id\":\"a\",\"title\":\"作业\",\"deadline\":100,\"lead\":999999}]");
        assertEquals(CustomHomework.MAX_LEAD_MINUTES, decoded.get(0).leadMinutes());
    }

    @Test
    public void missingLeadFallsBackToDefault() {
        List<CustomHomework> decoded = CustomHomeworkCodec.decode(
                "[{\"id\":\"a\",\"title\":\"作业\",\"deadline\":100}]");
        assertEquals(CustomHomework.DEFAULT_LEAD_MINUTES, decoded.get(0).leadMinutes());
    }

    @Test
    public void missingIdGetsFreshUuid() {
        List<CustomHomework> decoded = CustomHomeworkCodec.decode(
                "[{\"title\":\"作业\",\"deadline\":100}]");
        assertEquals(36, decoded.get(0).id().length());
    }

    @Test
    public void labelsReadAsChineseSentences() {
        CustomHomework minutes = new CustomHomework("a", "作业", 1_766_000_000_000L, 30);
        assertEquals("30 分钟", minutes.leadLabel());
        assertEquals("2 小时", new CustomHomework("b", "作业", 1L, 120).leadLabel());
        assertEquals("1 天", new CustomHomework("c", "作业", 1L, 1440).leadLabel());
        // 非整小时就按分钟说，避免出现「1.5 小时」这种别扭写法。
        assertEquals("90 分钟", new CustomHomework("d", "作业", 1L, 90).leadLabel());
        assertTrue(minutes.deadlineLabel().endsWith(" 截止"));
        assertEquals("未设置截止时间",
                new CustomHomework("e", "作业", 0L, 30).deadlineLabel());
    }
}
