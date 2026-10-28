package org.localroute.app.overlay;

import org.localroute.app.run.RunSnapshot;

/**
 * 悬浮条的「会话级」状态订阅（R32-04）。
 *
 * <p>问题：0.3.2 里悬浮窗的刷新挂在 {@code MainActivity} 的 LiveData 观察者上，Activity 一 STOPPED
 * 观察者就停收——窗口还在屏幕上，但不再更新：冷态点 A 之后仍然显示「未运行」，按钮一直置灰。
 *
 * <p>修法：订阅的所有权从 Activity 挪到持有悬浮窗的会话对象（{@code OverlayHost}），
 * 由「开关悬浮窗」的时机 attach / detach，成对出现；Activity 只负责渲染自己的界面。
 *
 * <p>这个类刻意不依赖 Android：{@link SnapshotSource} 由调用方适配 LiveData，
 * 测试里用假实现就能断言「重复启用不会产生两条订阅」「关闭后订阅归零」。
 */
public final class OverlaySession {

    /** 快照回调。 */
    public interface Listener {
        void onSnapshot(RunSnapshot snapshot);
    }

    private final SnapshotSource source;
    private final Listener listener;
    private boolean attached;

    public OverlaySession(SnapshotSource source, Listener listener) {
        if (source == null || listener == null) {
            throw new IllegalArgumentException("source 与 listener 都不能为 null");
        }
        this.source = source;
        this.listener = listener;
    }

    /**
     * 建立订阅；已订阅时直接返回 {@code false}（不会重复注册）。
     *
     * @return 本次是否新建立了订阅
     */
    public boolean attach() {
        if (attached) {
            return false;
        }
        attached = true;
        source.subscribe(listener);
        return true;
    }

    /**
     * 注销订阅；本来就未订阅时返回 {@code false}。
     *
     * @return 本次是否真的注销了
     */
    public boolean detach() {
        if (!attached) {
            return false;
        }
        attached = false;
        source.unsubscribe(listener);
        return true;
    }

    public boolean isAttached() {
        return attached;
    }
}
