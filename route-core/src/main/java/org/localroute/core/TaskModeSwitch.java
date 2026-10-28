package org.localroute.core;

/**
 * 「路线任务就地切换成单点任务」时的状态处理规则（R32-01）。
 *
 * <p>背景：{@code SimulationService.switchRouteTaskToSinglePoint()} 在换完字段、发完首帧后
 * 无条件调用 {@code LoopStateMachine.providerReady()}。这个方法只会往 RUNNING 转，而状态机
 * 没有 RUNNING→RUNNING 这条边，于是「运行中的路线收到传送命令」会抛
 * {@link LoopStateMachine.IllegalRunStateException}，被 {@code runGuarded()} 捕获后进入失败清理——
 * 表现就是「先过去一帧，然后自己停了」。
 *
 * <p>这里把「原状态 → 该做什么」抽成纯逻辑，让服务只负责执行动作，也便于用 JVM 测试覆盖
 * RUNNING / PAUSED / STARTING 三种入口。规则：
 * <ul>
 *   <li>RUNNING：保持 RUNNING，<b>不要</b>再调 providerReady；</li>
 *   <li>PAUSED：保持 PAUSED，不自动恢复（传送不应该悄悄把暂停中的任务跑起来），时钟清零后保持暂停；</li>
 *   <li>STARTING：启动流程与传送命令撞车，按「provider 已就绪」推进到 RUNNING（这条边是合法的）；</li>
 *   <li>其它（IDLE/READY/STOPPED/COMPLETED/ERROR）：不该走到这里，交给调用方回退成「停止后重开」。 </li>
 * </ul>
 */
public final class TaskModeSwitch {

    /** 就地切换时对状态机要做的动作。 */
    public enum StateAction {
        /** 已经是 RUNNING：什么都不用做，绝不能调 providerReady。 */
        KEEP_RUNNING,
        /** 原本暂停：保持暂停，不调用 providerReady，也不要在切换时恢复。 */
        KEEP_PAUSED,
        /** 启动中：允许 STARTING→RUNNING，调用 providerReady。 */
        PROMOTE_TO_RUNNING,
        /** 当前状态不支持就地切换，调用方需要回退路径。 */
        UNSUPPORTED
    }

    /** 切换后有效用时怎么处理。 */
    public enum ClockAction {
        /** 新任务从 0 开始计时（RUNNING / STARTING）。 */
        RESTART,
        /** 新任务保持暂停，有效用时为 0（PAUSED）。 */
        KEEP_PAUSED
    }

    private TaskModeSwitch() {
    }

    public static StateAction stateActionFor(RunState current) {
        if (current == null) {
            return StateAction.UNSUPPORTED;
        }
        switch (current) {
            case RUNNING:
                return StateAction.KEEP_RUNNING;
            case PAUSED:
                return StateAction.KEEP_PAUSED;
            case STARTING:
                return StateAction.PROMOTE_TO_RUNNING;
            default:
                return StateAction.UNSUPPORTED;
        }
    }

    /** 只有 STARTING 才需要（也才允许）调用 {@code providerReady()}。 */
    public static boolean requiresProviderReady(RunState current) {
        return stateActionFor(current) == StateAction.PROMOTE_TO_RUNNING;
    }

    public static ClockAction clockActionFor(RunState current) {
        return stateActionFor(current) == StateAction.KEEP_PAUSED
                ? ClockAction.KEEP_PAUSED
                : ClockAction.RESTART;
    }

    /** 切换完成后应当对外发布的状态。 */
    public static RunState publishedState(RunState current) {
        return stateActionFor(current) == StateAction.KEEP_PAUSED
                ? RunState.PAUSED
                : RunState.RUNNING;
    }

    /** 给诊断日志用的一句话说明。 */
    public static String describe(RunState current) {
        switch (stateActionFor(current)) {
            case KEEP_RUNNING:
                return "原状态 RUNNING：保持运行，不重复推进状态机";
            case KEEP_PAUSED:
                return "原状态 PAUSED：保持暂停，不自动恢复";
            case PROMOTE_TO_RUNNING:
                return "原状态 STARTING：推进到 RUNNING";
            default:
                return "原状态 " + current + "：不支持就地切换";
        }
    }
}
