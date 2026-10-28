package org.localroute.app.overlay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.localroute.app.run.RunSnapshot;

/**
 * 悬浮条状态订阅的所有权（R32-04）。
 *
 * <p>真机症状：Activity 进后台后窗口还在，但不再更新（点 A 后仍显示「未运行」）。原因是订阅挂在
 * Activity 的 LiveData 观察者上，STOPPED 就停收。这里用一个「假快照源」模拟 LiveData：
 * 只要会话还 attach 着，就与 Activity 生命周期无关地继续收快照；detach 后必须彻底不再投递，
 * 且重复启用不能变成两条订阅（否则窗口会闪、监听器泄漏）。
 */
public class OverlaySessionTest {

    /** 假快照源：记录当前订阅者数量，用来断言「订阅成对」。 */
    private static final class FakeSource implements SnapshotSource {
        private OverlaySession.Listener listener;
        private int subscribeCalls;
        private int unsubscribeCalls;

        @Override
        public void subscribe(OverlaySession.Listener target) {
            subscribeCalls++;
            listener = target;
            target.onSnapshot(RunSnapshot.idle());
        }

        @Override
        public void unsubscribe(OverlaySession.Listener target) {
            unsubscribeCalls++;
            if (listener == target) {
                listener = null;
            }
        }

        boolean hasSubscriber() {
            return listener != null;
        }

        int activeSubscribers() {
            return listener == null ? 0 : 1;
        }

        /** 模拟一次 LiveData 推送（与 Activity 是否在前台无关）。 */
        void push(RunSnapshot snapshot) {
            if (listener != null) {
                listener.onSnapshot(snapshot);
            }
        }
    }

    private final FakeSource source = new FakeSource();
    private final java.util.List<RunSnapshot> received = new java.util.ArrayList<>();
    private final OverlaySession session = new OverlaySession(source, received::add);

    @Test
    public void attachSubscribesAndReplaysCurrentValue() {
        assertTrue(session.attach());
        assertTrue(session.isAttached());
        assertEquals("attach 之后应立即回放当前快照", 1, received.size());
        assertEquals(1, source.activeSubscribers());
    }

    @Test
    public void repeatedAttachDoesNotDuplicateSubscription() {
        assertTrue(session.attach());
        assertFalse("重复 attach 应返回 false", session.attach());
        assertFalse(session.attach());
        assertEquals("只允许订阅一次", 1, source.subscribeCalls);
        assertEquals(1, source.activeSubscribers());
    }

    @Test
    public void detachIsPairedAndStopsDelivery() {
        session.attach();
        int before = received.size();
        assertTrue(session.detach());
        assertFalse(session.isAttached());
        assertEquals(0, source.activeSubscribers());

        source.push(RunSnapshot.builder(org.localroute.core.RunState.RUNNING)
                .runId("r1").singlePoint(true).build());
        assertEquals("detach 之后不应再收到快照", before, received.size());
    }

    @Test
    public void detachWithoutAttachIsSafe() {
        assertFalse(session.detach());
        assertEquals(0, source.unsubscribeCalls);
    }

    @Test
    public void snapshotKeepsFlowingWhileOwnerIsInBackground() {
        session.attach();
        // 没有任何「Activity 生命周期」参与：会话 attach 着就一直收。
        source.push(RunSnapshot.builder(org.localroute.core.RunState.RUNNING)
                .runId("r1").singlePoint(true).build());
        source.push(RunSnapshot.builder(org.localroute.core.RunState.RUNNING)
                .runId("r1").singlePoint(true).build());
        assertTrue(received.size() >= 3);
        RunSnapshot last = received.get(received.size() - 1);
        assertTrue(last.singlePoint());
        assertEquals(OverlayViewState.Mode.RUNNING_SINGLE_POINT, OverlayViewState.from(last).mode());
    }

    @Test
    public void reenableCyclesKeepSubscriberCountAtMostOne() {
        for (int i = 0; i < 5; i++) {
            session.attach();
            assertTrue(source.activeSubscribers() <= 1);
            session.detach();
        }
        assertEquals(0, source.activeSubscribers());
        assertEquals("关闭后不应残留订阅", 5, source.unsubscribeCalls);
    }
}
