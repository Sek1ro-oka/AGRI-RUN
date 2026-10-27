package org.localroute.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 审查方案 §5.2：几何（是否闭合）与运行策略（怎么收尾）分开表达。
 */
public class EndBehaviorTest {

    @Test
    public void onlyStopAtEndIsSingleRun() {
        assertTrue(EndBehavior.STOP_AT_END.isSingleRun());
        assertFalse(EndBehavior.SHUTTLE.isSingleRun());
        assertFalse(EndBehavior.LOOP.isSingleRun());
    }

    @Test
    public void onlyLoopClosesGeometry() {
        assertTrue(EndBehavior.LOOP.closed());
        assertFalse(EndBehavior.SHUTTLE.closed());
        assertFalse(EndBehavior.STOP_AT_END.closed());
    }

    @Test
    public void stopAtEndDoesNotWrapButShuttleAndLoopDo() {
        assertFalse("到终点停止后不能瞬移回起点", EndBehavior.STOP_AT_END.wraps());
        assertTrue(EndBehavior.SHUTTLE.wraps());
        assertTrue(EndBehavior.LOOP.wraps());
    }

    @Test
    public void onlyShuttleExpandsThePathBothWays() {
        assertTrue(EndBehavior.SHUTTLE.repeatsPath());
        assertFalse(EndBehavior.STOP_AT_END.repeatsPath());
        assertFalse(EndBehavior.LOOP.repeatsPath());
    }

    @Test
    public void forDraftMapsCheckboxToLoopOrStopAtEnd() {
        assertEquals(EndBehavior.LOOP, EndBehavior.forDraft(true));
        assertEquals(EndBehavior.STOP_AT_END, EndBehavior.forDraft(false));
    }

    @Test
    public void legacyForClosedKeepsV1Semantics() {
        // 0.1.x 里 closed=false 就是原路往返，老文件不能因为新默认而变慢一半。
        assertEquals(EndBehavior.LOOP, EndBehavior.legacyForClosed(true));
        assertEquals(EndBehavior.SHUTTLE, EndBehavior.legacyForClosed(false));
    }

    @Test
    public void parseIsCaseInsensitiveAndFallsBack() {
        assertEquals(EndBehavior.STOP_AT_END, EndBehavior.parse("STOP_AT_END", EndBehavior.LOOP));
        assertEquals(EndBehavior.SHUTTLE, EndBehavior.parse(" shuttle ", EndBehavior.LOOP));
        assertEquals(EndBehavior.LOOP, EndBehavior.parse(null, EndBehavior.LOOP));
        assertEquals(EndBehavior.LOOP, EndBehavior.parse("unknown", EndBehavior.LOOP));
    }
}
