package org.localroute.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 路线→单点就地切换的状态规则（R32-01）。 */
public class TaskModeSwitchTest {

    @Test
    public void runningStaysRunningWithoutTouchingStateMachine() {
        assertEquals(TaskModeSwitch.StateAction.KEEP_RUNNING,
                TaskModeSwitch.stateActionFor(RunState.RUNNING));
        assertFalse("RUNNING 再调 providerReady 会抛 IllegalRunStateException",
                TaskModeSwitch.requiresProviderReady(RunState.RUNNING));
        assertEquals(RunState.RUNNING, TaskModeSwitch.publishedState(RunState.RUNNING));
        assertEquals(TaskModeSwitch.ClockAction.RESTART,
                TaskModeSwitch.clockActionFor(RunState.RUNNING));
    }

    @Test
    public void pausedStaysPausedAndDoesNotAutoResume() {
        assertEquals(TaskModeSwitch.StateAction.KEEP_PAUSED,
                TaskModeSwitch.stateActionFor(RunState.PAUSED));
        assertFalse(TaskModeSwitch.requiresProviderReady(RunState.PAUSED));
        assertEquals(RunState.PAUSED, TaskModeSwitch.publishedState(RunState.PAUSED));
        assertEquals(TaskModeSwitch.ClockAction.KEEP_PAUSED,
                TaskModeSwitch.clockActionFor(RunState.PAUSED));
    }

    @Test
    public void startingIsPromoted() {
        assertEquals(TaskModeSwitch.StateAction.PROMOTE_TO_RUNNING,
                TaskModeSwitch.stateActionFor(RunState.STARTING));
        assertTrue("STARTING→RUNNING 是合法边，必须推进",
                TaskModeSwitch.requiresProviderReady(RunState.STARTING));
        assertEquals(RunState.RUNNING, TaskModeSwitch.publishedState(RunState.STARTING));
        assertEquals(TaskModeSwitch.ClockAction.RESTART,
                TaskModeSwitch.clockActionFor(RunState.STARTING));
    }

    @Test
    public void terminalStatesAreUnsupported() {
        RunState[] unsupported = {
                RunState.IDLE, RunState.READY, RunState.COMPLETED, RunState.STOPPED, RunState.ERROR
        };
        for (RunState state : unsupported) {
            assertEquals("状态 " + state, TaskModeSwitch.StateAction.UNSUPPORTED,
                    TaskModeSwitch.stateActionFor(state));
            assertFalse(TaskModeSwitch.requiresProviderReady(state));
        }
        assertEquals(TaskModeSwitch.StateAction.UNSUPPORTED, TaskModeSwitch.stateActionFor(null));
        assertFalse(TaskModeSwitch.requiresProviderReady(null));
    }

    @Test
    public void describeMentionsTheState() {
        assertTrue(TaskModeSwitch.describe(RunState.RUNNING).contains("RUNNING"));
        assertTrue(TaskModeSwitch.describe(RunState.PAUSED).contains("PAUSED"));
        assertTrue(TaskModeSwitch.describe(RunState.STARTING).contains("STARTING"));
        assertTrue(TaskModeSwitch.describe(RunState.IDLE).contains("IDLE"));
    }
}
