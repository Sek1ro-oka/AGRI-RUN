package org.localroute.app.run;

/**
 * 启动请求的进程内交接点。
 *
 * <p>{@code CompiledRoute} 可能包含上千个节点，跨进程传递需要序列化；服务与界面在同一进程，
 * 因此界面先 {@link #stage(RunRequest)}，再用 {@code startForegroundService} 通知服务取走，
 * 避免把大对象塞进 Intent。
 */
public final class PendingRunRegistry {

    private static RunRequest pending;

    private PendingRunRegistry() {
    }

    public static synchronized void stage(RunRequest request) {
        pending = request;
    }

    /** 服务取走请求；没有则返回 {@code null}。 */
    public static synchronized RunRequest take() {
        RunRequest value = pending;
        pending = null;
        return value;
    }

    /** 启动失败时清理，避免下一次误用旧请求。 */
    public static synchronized void clear() {
        pending = null;
    }

    public static synchronized boolean hasPending() {
        return pending != null;
    }
}
