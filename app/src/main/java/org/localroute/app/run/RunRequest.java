package org.localroute.app.run;

import org.localroute.core.CompiledRoute;
import org.localroute.core.RunConfig;

import java.util.UUID;

/** 一次「开始运行」请求：路线已编译、参数已校验。 */
public final class RunRequest {

    private final String runId;
    private final String routeName;
    private final CompiledRoute route;
    private final RunConfig config;

    public RunRequest(String runId, String routeName, CompiledRoute route, RunConfig config) {
        this.runId = runId;
        this.routeName = routeName == null ? "" : routeName;
        this.route = route;
        this.config = config;
    }

    public static RunRequest create(String routeName, CompiledRoute route, RunConfig config) {
        return new RunRequest(UUID.randomUUID().toString(), routeName, route, config);
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
}
