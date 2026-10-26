package org.localroute.app.model;

import java.time.Instant;

/** 路线列表用的摘要。 */
public final class RouteSummary {

    private final String id;
    private final String name;
    private final Instant updatedAt;
    private final int pointCount;

    public RouteSummary(String id, String name, Instant updatedAt, int pointCount) {
        this.id = id;
        this.name = name;
        this.updatedAt = updatedAt;
        this.pointCount = pointCount;
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public int pointCount() {
        return pointCount;
    }

    public String displayName() {
        return name == null || name.trim().isEmpty() ? "未命名路线" : name;
    }
}
