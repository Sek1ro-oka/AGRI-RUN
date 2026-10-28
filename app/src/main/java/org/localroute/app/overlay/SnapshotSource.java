package org.localroute.app.overlay;

/**
 * 「谁能提供运行快照」的最小抽象（R32-04）。
 *
 * <p>真实实现是 {@code SimulationStateRepo} 的 LiveData；测试里换成假实现即可验证
 * 「订阅与注销是否成对」——这正是过去悬浮条在 Activity 停止后不再更新的根因。
 */
public interface SnapshotSource {

    /** 订阅；实现方应立刻回调一次当前值（没有值就不回调）。 */
    void subscribe(OverlaySession.Listener listener);

    /** 注销；必须与 {@link #subscribe} 严格成对。 */
    void unsubscribe(OverlaySession.Listener listener);
}
