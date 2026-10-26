package org.localroute.core;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 运行状态机：负责“谁能转到谁”，不负责计时、不负责 provider。
 *
 * <p>关键约束（任务书第 7 步）：
 * <ul>
 *   <li>只有 provider 创建成功并完成首次位置提交后，才能从 STARTING 进入 RUNNING；</li>
 *   <li>STARTING / RUNNING / PAUSED 都可以进入 STOPPED 或 ERROR；</li>
 *   <li>COMPLETED / STOPPED / ERROR 只能回到 READY；</li>
 *   <li>重复 stop 幂等，不抛异常（对应“重复点击停止不产生第二个任务”）。</li>
 * </ul>
 */
public final class LoopStateMachine {

    private static final Map<RunState, Set<RunState>> ALLOWED = new EnumMap<>(RunState.class);

    static {
        ALLOWED.put(RunState.IDLE, EnumSet.of(RunState.READY));
        ALLOWED.put(RunState.READY, EnumSet.of(RunState.IDLE, RunState.STARTING, RunState.READY));
        ALLOWED.put(RunState.STARTING, EnumSet.of(RunState.RUNNING, RunState.STOPPED, RunState.ERROR));
        ALLOWED.put(RunState.RUNNING,
                EnumSet.of(RunState.PAUSED, RunState.COMPLETED, RunState.STOPPED, RunState.ERROR));
        ALLOWED.put(RunState.PAUSED, EnumSet.of(RunState.RUNNING, RunState.STOPPED, RunState.ERROR));
        ALLOWED.put(RunState.COMPLETED, EnumSet.of(RunState.READY, RunState.COMPLETED));
        ALLOWED.put(RunState.STOPPED, EnumSet.of(RunState.READY, RunState.STOPPED));
        ALLOWED.put(RunState.ERROR, EnumSet.of(RunState.READY, RunState.ERROR));
    }

    private RunState state = RunState.IDLE;
    private RunErrorCode errorCode;
    private String errorMessage;

    public RunState state() {
        return state;
    }

    public RunErrorCode errorCode() {
        return errorCode;
    }

    public String errorMessage() {
        return errorMessage;
    }

    public boolean isActive() {
        return state == RunState.STARTING || state == RunState.RUNNING || state == RunState.PAUSED;
    }

    public boolean canTransitionTo(RunState next) {
        return ALLOWED.get(state).contains(next);
    }

    /** 显式转移；非法转移抛异常，避免出现“悄悄改了状态”的 bug。 */
    public void transitionTo(RunState next) {
        if (!canTransitionTo(next)) {
            throw new IllegalRunStateException(state, next);
        }
        state = next;
    }

    /** 路线就绪（编辑完成、校验通过）。 */
    public void markReady() {
        transitionTo(RunState.READY);
    }

    /** 路线被清空/失效。 */
    public void markIdle() {
        transitionTo(RunState.IDLE);
        clearError();
    }

    /** 用户点“开始”。 */
    public void startRun() {
        transitionTo(RunState.STARTING);
        clearError();
    }

    /** provider 创建成功且首次提交成功。 */
    public void providerReady() {
        transitionTo(RunState.RUNNING);
    }

    public void pause() {
        transitionTo(RunState.PAUSED);
    }

    public void resume() {
        // “继续”只对暂停中的任务有意义；其他状态调用属于调用方 bug，显式抛异常。
        if (state != RunState.PAUSED) {
            throw new IllegalRunStateException(state, RunState.RUNNING);
        }
        transitionTo(RunState.RUNNING);
    }

    /**
     * 幂等停止：重复停止不抛异常。
     *
     * <p>只有存在运行中任务的状态（STARTING/RUNNING/PAUSED）才会真正转成 STOPPED；
     * 已经 STOPPED 时保持不动；IDLE/READY/COMPLETED/ERROR 下“停止”没有意义，
     * 一律空操作，避免把“已完成”改写成“已停止”让用户以为任务被重启。
     */
    public void stop() {
        switch (state) {
            case STARTING:
            case RUNNING:
            case PAUSED:
                transitionTo(RunState.STOPPED);
                return;
            case STOPPED:
                return;
            default:
                // IDLE / READY / COMPLETED / ERROR：无任务在跑，空操作。
        }
    }

    public void complete() {
        transitionTo(RunState.COMPLETED);
    }

    public void fail(RunErrorCode code, String message) {
        if (state == RunState.ERROR) {
            return;
        }
        transitionTo(RunState.ERROR);
        this.errorCode = code;
        this.errorMessage = message;
    }

    /** 跑完/停止/出错后回到 READY，允许下一次开始。 */
    public void backToReady() {
        transitionTo(RunState.READY);
        clearError();
    }

    private void clearError() {
        errorCode = null;
        errorMessage = null;
    }

    /** 非法状态转移。 */
    public static final class IllegalRunStateException extends RuntimeException {
        public IllegalRunStateException(RunState from, RunState to) {
            super("非法状态转移：" + from + " -> " + to);
        }
    }
}
