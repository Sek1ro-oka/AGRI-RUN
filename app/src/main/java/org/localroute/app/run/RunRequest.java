package org.localroute.app.run;

import androidx.annotation.Nullable;

import org.localroute.core.CompiledRoute;
import org.localroute.core.GeoPoint;
import org.localroute.core.RunConfig;

import java.util.UUID;

/**
 * 一次「开始运行」请求。
 *
 * <p>两种模式（0.1.1 起）：
 * <ul>
 *   <li><b>路线模式</b>：{@link #route()} 非空，按距离推进位置；</li>
 *   <li><b>单点模式</b>：{@link #singlePoint()} 非空、{@link #route()} 为 null，
 *       前台服务一直把同一个坐标（速度 0）提交给系统，直到用户停止。</li>
 * </ul>
 */
public final class RunRequest {

    private final String runId;
    private final String routeName;
    private final CompiledRoute route;
    private final RunConfig config;
    private final GeoPoint singlePoint;

    public RunRequest(String runId, String routeName, CompiledRoute route, RunConfig config) {
        this(runId, routeName, route, config, null);
    }

    private RunRequest(String runId, String routeName, CompiledRoute route, RunConfig config,
                       @Nullable GeoPoint singlePoint) {
        this.runId = runId;
        this.routeName = routeName == null ? "" : routeName;
        this.route = route;
        this.config = config;
        this.singlePoint = singlePoint;
    }

    public static RunRequest create(String routeName, CompiledRoute route, RunConfig config) {
        return new RunRequest(UUID.randomUUID().toString(), routeName, route, config);
    }

    /** 单点位置请求：路线为空，服务只反复提交这一个坐标。 */
    public static RunRequest createSinglePoint(GeoPoint point) {
        if (point == null) {
            throw new IllegalArgumentException("单点位置不能为空");
        }
        return new RunRequest(UUID.randomUUID().toString(), "单点位置", null,
                new RunConfig(RunConfig.DEFAULT_SPEED_KMH, 1), point);
    }

    public String runId() {
        return runId;
    }

    public String routeName() {
        return routeName;
    }

    public CompiledRoute route() {
        return route;
    }

    public RunConfig config() {
        return config;
    }

    /** 单点模式下要固定发送的坐标；路线模式为 null。 */
    @Nullable
    public GeoPoint singlePoint() {
        return singlePoint;
    }

    public boolean isSinglePoint() {
        return singlePoint != null;
    }
}
