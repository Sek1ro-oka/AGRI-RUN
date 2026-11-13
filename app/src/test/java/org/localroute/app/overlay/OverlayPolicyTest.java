package org.localroute.app.overlay;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.localroute.app.run.RunSnapshot;
import org.localroute.core.RunState;

/** 悬浮条显隐条件：只在虚拟定位运行中、离开跑圈地图时出现。 */
public class OverlayPolicyTest {

    private static RunSnapshot snapshot(RunState state) {
        return RunSnapshot.builder(state).runId("r1").build();
    }

    @Test
    public void showsOnlyWhileVirtualLocationIsRunning() {
        assertFalse("空闲时不应出现",
                OverlayPolicy.shouldShow(snapshot(RunState.IDLE), true, true, false));
        assertTrue("跑圈中切到别的页面应出现",
                OverlayPolicy.shouldShow(snapshot(RunState.RUNNING), true, true, false));
        assertTrue("暂停中仍算运行，可以继续或结束",
                OverlayPolicy.shouldShow(snapshot(RunState.PAUSED), true, true, false));
        assertTrue("启动阶段也显示，避免切走后失去控制",
                OverlayPolicy.shouldShow(snapshot(RunState.STARTING), true, true, false));
        assertFalse("结束/出错后应收起",
                OverlayPolicy.shouldShow(snapshot(RunState.STOPPED), true, true, false));
        assertFalse("完成快照同样收起",
                OverlayPolicy.shouldShow(snapshot(RunState.COMPLETED), true, true, false));
        assertFalse("出错快照同样收起",
                OverlayPolicy.shouldShow(snapshot(RunState.ERROR), true, true, false));
    }

    @Test
    public void hidesOnRunMapEvenWhenRunning() {
        RunSnapshot running = snapshot(RunState.RUNNING);
        assertFalse("跑圈地图上不遮挡地图",
                OverlayPolicy.shouldShow(running, true, true, true));
    }

    @Test
    public void respectsSwitchAndSystemPermission() {
        RunSnapshot running = snapshot(RunState.RUNNING);
        assertFalse("用户关闭悬浮屏后不显示",
                OverlayPolicy.shouldShow(running, false, true, false));
        assertFalse("没有悬浮窗权限时不显示",
                OverlayPolicy.shouldShow(running, true, false, false));
    }

    @Test
    public void nullSnapshotIsSafe() {
        assertFalse(OverlayPolicy.shouldShow(null, true, true, false));
    }
}
