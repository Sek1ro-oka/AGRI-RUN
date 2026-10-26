package org.localroute.app.model;

import org.localroute.core.GeoPoint;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一条可保存的路线定义（与任务书的数据对象一致）。
 *
 * <p>只有 WGS-84 一种坐标系；路线始终闭合，{@code points} 不重复保存闭合终点。
 */
public final class RouteDefinition {

    public static final int SCHEMA_VERSION = 1;
    public static final String COORDINATE_SYSTEM_WGS84 = "WGS84";

    private final String id;
    private final String name;
    private final List<GeoPoint> points;
    private final Instant createdAt;
    private final Instant updatedAt;

    public RouteDefinition(String id, String name, List<GeoPoint> points, Instant createdAt, Instant updatedAt) {
        if (id == null || id.trim().isEmpty()) {
            throw new IllegalArgumentException("路线 id 不能为空");
        }
        if (points == null) {
            throw new IllegalArgumentException("points == null");
        }
        this.id = id;
        this.name = name == null ? "" : name;
        this.points = Collections.unmodifiableList(new ArrayList<>(points));
        this.createdAt = createdAt == null ? Instant.now() : createdAt;
        this.updatedAt = updatedAt == null ? this.createdAt : updatedAt;
    }

    public static RouteDefinition createNew(String id, String name, List<GeoPoint> points) {
        Instant now = Instant.now();
        return new RouteDefinition(id, name, points, now, now);
    }

    public RouteDefinition withName(String newName) {
        return new RouteDefinition(id, newName, points, createdAt, Instant.now());
    }

    public RouteDefinition withPoints(List<GeoPoint> newPoints) {
        return new RouteDefinition(id, name, newPoints, createdAt, Instant.now());
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public List<GeoPoint> points() {
        return points;
    }

    public int schemaVersion() {
        return SCHEMA_VERSION;
    }

    public String coordinateSystem() {
        return COORDINATE_SYSTEM_WGS84;
    }

    /** 路线始终闭合；保留该字段是为了让文件自解释。 */
    public boolean closed() {
        return true;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public String displayName() {
        return name == null || name.trim().isEmpty() ? "未命名路线" : name;
    }
}
