package org.localroute.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 状态机迁移测试（任务书第 7 步、T24 的“重复开始只允许一个 runId”逻辑前提）。 */
public class LoopStateMachineTest {

    @Test
    public void initialState_isIdle() {
        LoopStateMachine machine = new LoopStateMachine();
        assertEquals(RunState.IDLE, machine.state());
        assertFalse(machine.isActive());
    }

    @Test
    public void happyPath_idleToReadyToStartingToRunningToPausedToCompleted() {
        LoopStateMachine machine = new LoopStateMachine();
        machine.markReady();
        assertEquals(RunState.READY, machine.state());
        machine.startRun();
        assertEquals(RunState.STARTING, machine.state());
        assertTrue(machine.isActive());
        // 还没有 provider 就绪，不能进入 RUNNING。
        assertFalse(machine.canTransitionTo(RunState.COMPLETED));
        machine.providerReady();
        assertEquals(RunState.RUNNING, machine.state());
        machine.pause();
        assertEquals(RunState.PAUSED, machine.state());
        machine.resume();
        assertEquals(RunState.RUNNING, machine.state());
        machine.complete();
        assertEquals(RunState.COMPLETED, machine.state());
        machine.backToReady();
        assertEquals(RunState.READY, machine.state());
    }

    @Test
    public void illegalTransitions_throw() {
        LoopStateMachine machine = new LoopStateMachine();
        assertThrows(LoopStateMachine.IllegalRunStateException.class,
                () -> machine.transitionTo(RunState.RUNNING));
        machine.markReady();
        assertThrows(LoopStateMachine.IllegalRunStateException.class, machine::pause);
        machine.startRun();
        assertThrows(LoopStateMachine.IllegalRunStateException.class, machine::complete);
        assertThrows(LoopStateMachine.IllegalRunStateException.class, machine::resume);
        assertThrows(LoopStateMachine.IllegalRunStateException.class,
                () -> machine.transitionTo(RunState.READY));
    }

    @Test
    public void stop_isIdempotentFromStartingAndStopped() {
        LoopStateMachine machine = new LoopStateMachine();
        machine.markReady();
        machine.startRun();
        machine.stop();
        assertEquals(RunState.STOPPED, machine.state());
        machine.stop();
        assertEquals("重复 stop 必须幂等", RunState.STOPPED, machine.state());
        machine.backToReady();
        assertEquals(RunState.READY, machine.state());
        machine.startRun();
        machine.providerReady();
        machine.stop();
        assertEquals(RunState.STOPPED, machine.state());
    }

    @Test
    public void fail_recordsErrorCodeAndOnlyFromActiveStates() {
        LoopStateMachine machine = new LoopStateMachine();
        machine.markReady();
        machine.startRun();
        machine.fail(RunErrorCode.MOCK_APP_NOT_SELECTED, "未选择模拟位置应用");
        assertEquals(RunState.ERROR, machine.state());
        assertEquals(RunErrorCode.MOCK_APP_NOT_SELECTED, machine.errorCode());
        assertEquals("未选择模拟位置应用", machine.errorMessage());
        // 已 ERROR 时再次 fail 不改变已有信息。
        machine.fail(RunErrorCode.PUBLISH_FAILED, "另一个错误");
        assertEquals(RunErrorCode.MOCK_APP_NOT_SELECTED, machine.errorCode());
        machine.backToReady();
        assertEquals(RunState.READY, machine.state());
        assertNull(machine.errorCode());
    }

    @Test
    public void stop_isNoOpWhenNotRunning() {
        LoopStateMachine machine = new LoopStateMachine();
        machine.markReady();
        // READY 下误触“停止”不应抛异常，也不改变状态。
        machine.stop();
        assertEquals(RunState.READY, machine.state());

        machine.startRun();
        machine.providerReady();
        machine.complete();
        machine.stop();
        assertEquals("已完成的任务不应被改写成已停止", RunState.COMPLETED, machine.state());
        assertTrue(machine.canTransitionTo(RunState.READY));
        machine.backToReady();
        assertEquals(RunState.READY, machine.state());
    }

    @Test
    public void markIdle_clearsError() {
        LoopStateMachine machine = new LoopStateMachine();
        machine.markReady();
        machine.startRun();
        machine.fail(RunErrorCode.PUBLISH_FAILED, "提交失败");
        machine.backToReady();
        machine.markIdle();
        assertEquals(RunState.IDLE, machine.state());
        assertNull(machine.errorCode());
    }
}
