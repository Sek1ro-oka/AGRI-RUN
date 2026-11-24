package org.localroute.app.study;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** 自建作业的编解码：往返一致、坏条目跳过、旧版每条提前量字段可安全忽略。 */
public class CustomHomeworkCodecTest {

    @Test
    public void roundTripKeepsFields() {
        List<CustomHomework> items = Arrays.asList(
                new CustomHomework("id-1", "数据结构实验报告", 1_766_000_000_000L),
                new CustomHomework("id-2", "英语作文", 1_766_100_000_000L));
        String json = CustomHomeworkCodec.encode(items);
        List<CustomHomework> decoded = CustomHomeworkCodec.decode(json);
        assertEquals(2, decoded.size());
        assertEquals("id-1", decoded.get(0).id());
        assertEquals("数据结构实验报告", decoded.get(0).title());
        assertEquals(1_766_000_000_000L, decoded.get(0).deadlineEpochMillis());
        assertEquals("英语作文", decoded.get(1).title());
        // 提前量改成全局设置后不再写进每条作业。
        assertFalse(json.contains("lead"));
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

    /** 老版本每条作业带 lead，升级后必须照常读出来，只是不再按它提醒。 */
    @Test
    public void legacyPerHomeworkLeadIsIgnored() {
        List<CustomHomework> decoded = CustomHomeworkCodec.decode(
                "[{\"id\":\"a\",\"title\":\"作业\",\"deadline\":100,\"lead\":999999}]");
        assertEquals(1, decoded.size());
        assertEquals("作业", decoded.get(0).title());
        assertEquals(100L, decoded.get(0).deadlineEpochMillis());
        assertEquals(100L - HomeworkReminderPlanner.clampLead(60) * 60_000L,
                decoded.get(0).remindEpochMillis(60));
    }

    @Test
    public void missingIdGetsFreshUuid() {
        List<CustomHomework> decoded = CustomHomeworkCodec.decode(
                "[{\"title\":\"作业\",\"deadline\":100}]");
        assertEquals(36, decoded.get(0).id().length());
    }

    @Test
    public void labelsReadAsChineseSentences() {
        CustomHomework item = new CustomHomework("a", "作业", 1_766_000_000_000L);
        assertTrue(item.deadlineLabel().endsWith(" 截止"));
        assertTrue(item.formatDeadline().contains("-"));
        assertEquals("未设置截止时间",
                new CustomHomework("e", "作业", 0L).deadlineLabel());
    }

    @Test
    public void withHelpersKeepIdentityAndChangeOneField() {
        CustomHomework item = new CustomHomework("id-1", "旧名", 1_766_000_000_000L);
        assertEquals("新名", item.withTitle("新名").title());
        assertEquals("id-1", item.withTitle("新名").id());
        assertEquals(1_766_100_000_000L,
                item.withDeadline(1_766_100_000_000L).deadlineEpochMillis());
        assertEquals("旧名", item.withDeadline(1_766_100_000_000L).title());
    }
}
