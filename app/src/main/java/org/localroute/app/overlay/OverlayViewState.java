package org.localroute.app.overlay;

import org.localroute.app.run.RunSnapshot;
import org.localroute.core.RunState;

/**
 * 悬浮条要显示的「状态模型」：把 {@link RunSnapshot} 翻译成与文案无关的展示状态（R32-04）。
 *
 * <p>抽出这个类的原因：悬浮窗是否更新、按钮是否可点，过去只能靠真机看。现在把「快照 → 展示状态」
 * 变成纯 Java 逻辑，JVM 测试就能断言「冷态点 A 之后确实变成单点运行、停止可点」这类结论。
 */
public final class OverlayViewState {

    /** 悬浮条当前该显示什么。 */
    public enum Mode {
        /** 没有运行中的任务：只显示「点 A/B/C 可传送」，暂停/停止置灰。 */
        IDLE,
        /** 路线任务运行中。 */
        RUNNING_ROUTE,
        /** 单点位置运行中（传送之后就是这个）。 */
        RUNNING_SINGLE_POINT,
        /** 已暂停：按钮变成「继续」。 */
        PAUSED,
        /** 启动中。 */
        STARTING,
        /** 已完成 / 已停止 / 出错：显示状态但按钮不可用。 */
        TERMINAL
    }

    private final Mode mode;
    private final boolean actionEnabled;
    private final boolean stopEnabled;
    private final boolean actionIsResume;
    private final double speedKmh;
    private final double distanceMeters;

    private OverlayViewState(Mode mode, boolean actionEnabled, boolean stopEnabled,
                             boolean actionIsResume, double speedKmh, double distanceMeters) {
        this.mode = mode;
        this.actionEnabled = actionEnabled;
        this.stopEnabled = stopEnabled;
        this.actionIsResume = actionIsResume;
        this.speedKmh = speedKmh;
        this.distanceMeters = distanceMeters;
    }

    public static OverlayViewState from(RunSnapshot snapshot) {
        if (snapshot == null || snapshot.state() == null) {
            return new OverlayViewState(Mode.IDLE, false, false, false, 0d, 0d);
        }
        RunState state = snapshot.state();
        Mode mode;
        boolean actionEnabled = true;
        boolean stopEnabled = true;
        boolean actionIsResume = false;
        switch (state) {
            case IDLE:
            case READY:
                // 还没开始：只剩「点 A/B/C 传送」可用，暂停/停止置灰。
                mode = Mode.IDLE;
                actionEnabled = false;
                stopEnabled = false;
                break;
            case RUNNING:
                mode = snapshot.singlePoint() ? Mode.RUNNING_SINGLE_POINT : Mode.RUNNING_ROUTE;
                break;
            case PAUSED:
                mode = Mode.PAUSED;
                actionIsResume = true;
                break;
            case STARTING:
                mode = Mode.STARTING;
                break;
            default:
                // COMPLETED / STOPPED / ERROR：显示「已结束」，按钮不可用，但保留最后一帧的数值。
                mode = Mode.TERMINAL;
                actionEnabled = false;
                stopEnabled = false;
                break;
        }
        return new OverlayViewState(mode, actionEnabled, stopEnabled, actionIsResume,
                snapshot.speedKmh(), snapshot.distanceMeters());
    }

    public Mode mode() {
        return mode;
    }

    public boolean actionEnabled() {
        return actionEnabled;
    }

    public boolean stopEnabled() {
        return stopEnabled;
    }

    /** 主动作按钮是不是「继续」（暂停态）。 */
    public boolean actionIsResume() {
        return actionIsResume;
    }

    public double speedKmh() {
        return speedKmh;
    }

    public double distanceMeters() {
        return distanceMeters;
    }

    public boolean isIdle() {
        return mode == Mode.IDLE;
    }
}
