package org.localroute.simulation;

/**
 * 只允许「同一个 tick 任务在队列里最多一份」的排程器（R32-03）。
 *
 * <p>原来 {@code SimulationService.scheduleNextTick()} 每次都是 {@code handler.postDelayed(tickRunnable, …)}，
 * 既不检查是否已有待执行的 tick、也不移除旧的。于是「开始 → 传送 → 传送」会在同一个 Handler 上叠出多条
 * 待执行任务，同一帧被发两次、间隔忽长忽短，正是真机上「时快时慢」的来源之一。
 *
 * <p>这里的约定很简单：开始、暂停、传送、模式切换、停止都走同一个入口；{@link #schedule(long)} 在已有
 * 待执行任务时直接返回 {@code false}，{@link #cancel()} 负责清掉待执行任务。
 *
 * <p>不依赖 Android：{@link Queue} 由调用方用 {@code Handler} 适配，JVM 测试里换成假队列即可。
 */
public final class TickScheduler {

    /** 只描述「投递 / 撤销」两个动作，便于在 JVM 里验证。 */
    public interface Queue {
        boolean postDelayed(Runnable runnable, long delayMillis);

        void removeCallbacks(Runnable runnable);
    }

    private final Queue queue;
    private final Runnable tick;
    /** 固定实例：post 与 remove 必须用同一个对象，否则撤销不掉。 */
    private final Runnable guarded = new Runnable() {
        @Override
        public void run() {
            pending = false;
            tick.run();
        }
    };

    private boolean pending;

    public TickScheduler(Queue queue, Runnable tick) {
        if (queue == null || tick == null) {
            throw new IllegalArgumentException("queue 与 tick 都不能为 null");
        }
        this.queue = queue;
        this.tick = tick;
    }

    /**
     * 投递下一帧。已有待执行任务时返回 {@code false}（不重复投递）。
     *
     * @return 是否真的投递了
     */
    public boolean schedule(long delayMillis) {
        if (pending) {
            return false;
        }
        pending = true;
        boolean posted = queue.postDelayed(guarded, Math.max(0L, delayMillis));
        if (!posted) {
            pending = false;
        }
        return posted;
    }

    /** 撤销待执行任务；无论有没有都安全。 */
    public void cancel() {
        pending = false;
        queue.removeCallbacks(guarded);
    }

    /** 当前是否有待执行的 tick。 */
    public boolean pending() {
        return pending;
    }
}
