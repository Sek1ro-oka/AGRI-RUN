package org.localroute.simulation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** {@link TickScheduler} 的行为：任何时刻最多一条待执行 tick（R32-03）。 */
public class TickSchedulerTest {

    /** 假队列：记录投递次数与待执行任务，可手动执行「时间到了」。 */
    private static final class FakeQueue implements TickScheduler.Queue {
        int postCount;
        int removeCount;
        Runnable pendingTask;
        long lastDelayMillis = -1L;
        boolean acceptPosts = true;

        @Override
        public boolean postDelayed(Runnable runnable, long delayMillis) {
            if (!acceptPosts) {
                return false;
            }
            postCount++;
            pendingTask = runnable;
            lastDelayMillis = delayMillis;
            return true;
        }

        @Override
        public void removeCallbacks(Runnable runnable) {
            removeCount++;
            if (pendingTask == runnable) {
                pendingTask = null;
            }
        }

        /** 模拟 Handler 到点执行。 */
        void fire() {
            Runnable task = pendingTask;
            pendingTask = null;
            if (task != null) {
                task.run();
            }
        }
    }

    @Test
    public void schedulesOneTickAndSwallowsDuplicates() {
        FakeQueue queue = new FakeQueue();
        int[] runs = {0};
        TickScheduler scheduler = new TickScheduler(queue, () -> runs[0]++);

        assertTrue(scheduler.schedule(500L));
        assertTrue(scheduler.pending());
        assertEquals(1, queue.postCount);
        assertEquals(500L, queue.lastDelayMillis);

        assertFalse("已有待执行 tick 时不应再投递", scheduler.schedule(500L));
        assertEquals(1, queue.postCount);

        queue.fire();
        assertEquals(1, runs[0]);
        assertFalse(scheduler.pending());
    }

    @Test
    public void repeatedScheduleNeverStacksUp() {
        FakeQueue queue = new FakeQueue();
        int[] runs = {0};
        TickScheduler scheduler = new TickScheduler(queue, () -> runs[0]++);

        for (int i = 0; i < 20; i++) {
            scheduler.schedule(500L);
        }
        assertEquals("20 次连续排程只允许一次投递", 1, queue.postCount);
        assertTrue(scheduler.pending());

        queue.fire();
        assertEquals(1, runs[0]);
    }

    @Test
    public void tickCanRescheduleItself() {
        FakeQueue queue = new FakeQueue();
        int[] runs = {0};
        TickScheduler[] holder = new TickScheduler[1];
        holder[0] = new TickScheduler(queue, () -> {
            runs[0]++;
            if (runs[0] < 3) {
                holder[0].schedule(500L);
            }
        });
        holder[0].schedule(500L);
        queue.fire();
        queue.fire();
        queue.fire();
        assertEquals(3, runs[0]);
        assertFalse(holder[0].pending());
    }

    @Test
    public void cancelClearsPendingAndAllowsRescheduling() {
        FakeQueue queue = new FakeQueue();
        int[] runs = {0};
        TickScheduler scheduler = new TickScheduler(queue, () -> runs[0]++);

        scheduler.schedule(500L);
        scheduler.cancel();
        assertFalse(scheduler.pending());
        assertEquals(1, queue.removeCount);

        assertTrue("撤销后应能重新排程", scheduler.schedule(1000L));
        assertEquals(2, queue.postCount);
        assertEquals(1000L, queue.lastDelayMillis);
    }

    @Test
    public void failedPostDoesNotLeavePendingFlagSet() {
        FakeQueue queue = new FakeQueue();
        queue.acceptPosts = false;
        TickScheduler scheduler = new TickScheduler(queue, () -> { });

        assertFalse(scheduler.schedule(500L));
        assertFalse("投递失败不应留下 pending 标记", scheduler.pending());
        assertEquals(0, queue.postCount);

        queue.acceptPosts = true;
        assertTrue(scheduler.schedule(500L));
    }

    @Test
    public void cancelIsSafeWhenNothingPending() {
        FakeQueue queue = new FakeQueue();
        TickScheduler scheduler = new TickScheduler(queue, () -> { });
        scheduler.cancel();
        assertFalse(scheduler.pending());
        assertEquals(1, queue.removeCount);
    }
}
