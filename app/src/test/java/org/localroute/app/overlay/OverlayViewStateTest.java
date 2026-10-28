package org.localroute.app.overlay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.localroute.app.run.RunSnapshot;
import org.localroute.core.RunState;

/**
 * 悬浮条的展示状态（R32-04）。
 *
 * <p>真机反馈的两个症状都在这里变成可断言的结论：冷态时按钮必须是灰的；传送之后必须显示
 * 「单点」且停止可点；暂停态主动作按钮必须是「继续」。
 */
public class OverlayViewStateTest {

    @Test
    public void idleSnapshotDisablesBothButtons() {
        OverlayViewState state = OverlayViewState.from(RunSnapshot.idle());
        assertEquals(OverlayViewState.Mode.IDLE, state.mode());
        assertFalse("冷态下暂停/停止都该置灰", state.actionEnabled());
        assertFalse(state.stopEnabled());
        assertTrue(state.isIdle());
    }

    @Test
    public void nullSnapshotIsTreatedAsIdle() {
        OverlayViewState state = OverlayViewState.from(null);
        assertEquals(OverlayViewState.Mode.IDLE, state.mode());
        assertFalse(state.actionEnabled());
    }

    @Test
    public void completedSnapshotIsTerminalAndDisabled() {
        RunSnapshot done = RunSnapshot.builder(RunState.COMPLETED)
                .runId("r1").distanceMeters(600d).build();
        OverlayViewState state = OverlayViewState.from(done);
        assertEquals(OverlayViewState.Mode.TERMINAL, state.mode());
        assertFalse(state.actionEnabled());
        assertFalse(state.stopEnabled());
    }

    @Test
    public void runningRouteShowsRouteMode() {
        RunSnapshot running = RunSnapshot.builder(RunState.RUNNING)
                .runId("r1").singlePoint(false).speedMps(2.5d).distanceMeters(120d).build();
        OverlayViewState state = OverlayViewState.from(running);
        assertEquals(OverlayViewState.Mode.RUNNING_ROUTE, state.mode());
        assertTrue(state.actionEnabled());
        assertTrue(state.stopEnabled());
        assertFalse(state.actionIsResume());
        assertEquals(9d, state.speedKmh(), 1e-6);
        assertEquals(120d, state.distanceMeters(), 1e-6);
    }

    @Test
    public void singlePointRunAfterTeleportShowsSinglePointMode() {
        RunSnapshot single = RunSnapshot.builder(RunState.RUNNING)
                .runId("r1").singlePoint(true).build();
        OverlayViewState state = OverlayViewState.from(single);
        assertEquals(OverlayViewState.Mode.RUNNING_SINGLE_POINT, state.mode());
        assertTrue("单点运行中停止必须可点", state.stopEnabled());
        assertTrue("单点运行中暂停也应有意义", state.actionEnabled());
    }

    @Test
    public void pausedSnapshotAsksForResume() {
        RunSnapshot paused = RunSnapshot.builder(RunState.PAUSED).runId("r1").build();
        OverlayViewState state = OverlayViewState.from(paused);
        assertEquals(OverlayViewState.Mode.PAUSED, state.mode());
        assertTrue("暂停态主动作按钮应是「继续」", state.actionIsResume());
        assertTrue(state.actionEnabled());
        assertTrue(state.stopEnabled());
    }

    @Test
    public void startingSnapshotIsNotTerminal() {
        RunSnapshot starting = RunSnapshot.builder(RunState.STARTING).runId("r1").build();
        OverlayViewState state = OverlayViewState.from(starting);
        assertEquals(OverlayViewState.Mode.STARTING, state.mode());
        assertTrue(state.stopEnabled());
    }
}
