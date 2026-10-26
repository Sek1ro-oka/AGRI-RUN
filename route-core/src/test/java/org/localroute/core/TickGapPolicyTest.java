package org.localroute.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

/** 任务书 T11：调度间隔 >5 秒必须自动暂停，且原因要能读得懂。 */
public class TickGapPolicyTest {

    private static final long SECOND = 1_000_000_000L;

    @Test
    public void defaultsUseFiveSeconds() {
        assertEquals(5_000_000_000L, TickGapPolicy.defaults().maxGapNanos());
    }

    @Test
    public void neverPausesWithoutSuccessfulTick() {
        TickGapPolicy policy = TickGapPolicy.defaults();
        // 首次 tick 之前 lastTickNanos 是 0：即使时钟很大也不能判定为「卡顿」。
        assertFalse(policy.shouldPause(0L, 600L * SECOND));
        assertFalse(policy.shouldPause(-1L, 600L * SECOND));
    }

    @Test
    public void exactlyAtLimitIsNotPaused() {
        TickGapPolicy policy = TickGapPolicy.defaults();
        long last = 10L * SECOND;
        // 任务书写的是「超过 5 秒」，等于 5 秒仍算正常节奏（500ms 的 10 倍）。
        assertFalse(policy.shouldPause(last, last + 5L * SECOND));
    }

    @Test
    public void justOverLimitIsPaused() {
        TickGapPolicy policy = TickGapPolicy.defaults();
        long last = 10L * SECOND;
        assertTrue(policy.shouldPause(last, last + 5L * SECOND + 1L));
    }

    @Test
    public void clockGoingBackwardsIsNotTreatedAsGap() {
        TickGapPolicy policy = TickGapPolicy.defaults();
        assertFalse(policy.shouldPause(100L * SECOND, 99L * SECOND));
    }

    @Test
    public void customLimitIsHonoured() {
        TickGapPolicy policy = new TickGapPolicy(SECOND);
        assertFalse(policy.shouldPause(0L, SECOND));          // 没有上一次 tick
        assertFalse(policy.shouldPause(SECOND, 2L * SECOND));  // 恰好 1 秒
        assertTrue(policy.shouldPause(SECOND, 2L * SECOND + 1L));
    }

    @Test
    public void reasonExplainsLimitAndRecovery() {
        TickGapPolicy policy = TickGapPolicy.defaults();
        String reason = policy.reasonForGap(12_340_000_000L);
        assertTrue(reason, reason.contains("12.3 秒"));
        assertTrue(reason, reason.contains("上限 5 秒"));
        assertTrue(reason, reason.contains("自动暂停"));
        assertTrue(reason, reason.contains("继续"));
    }

    @Test
    public void reasonForNegativeGapDoesNotPrintNonsense() {
        TickGapPolicy policy = TickGapPolicy.defaults();
        String reason = policy.reasonForGap(-SECOND);
        assertTrue(reason, reason.contains("0 秒"));
        assertFalse(reason, reason.contains("-1"));
    }

    @Test
    public void wholeSecondsArePrintedWithoutDecimal() {
        assertEquals("5", TickGapPolicy.formatSeconds(5L * SECOND));
        assertEquals("0", TickGapPolicy.formatSeconds(0L));
        assertEquals("12.3", TickGapPolicy.formatSeconds(12_340_000_000L));
        assertEquals("0.5", TickGapPolicy.formatSeconds(SECOND / 2));
    }

    @Test
    public void nonPositiveLimitIsRejected() {
        try {
            new TickGapPolicy(0L);
            fail("上限为 0 应当被拒绝");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("必须为正数"));
        }
        try {
            new TickGapPolicy(-SECOND);
            fail("上限为负应当被拒绝");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("必须为正数"));
        }
    }
}
