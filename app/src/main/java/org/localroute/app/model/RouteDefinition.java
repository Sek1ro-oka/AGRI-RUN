package org.localroute.app.model;

import org.localroute.core.GeoPoint;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一条可保存的路线定义（与任务书的数据对象一致）。
 *
 * <p>只有 WGS-84 一种坐标系；{@code points} 不重复保存闭合终点。
 *
 * <p>{@code closed} 从 0.1.1 起由用户选择：
 * <ul>
 *   <li>{@code true}（默认）：最后一点连回第一点，单圈长度含闭合边；</li>
 *   <li>{@code false}：保持开口折线，运行期按“原路返回”计圈。</li>
 * </ul>
 */
public final class RouteDefinition {

    public static final int SCHEMA_VERSION = 1;
    public static final String COORDINATE_SYSTEM_WGS84 = "WGS84";

    private final String id;
    private final String name;
    private final List<GeoPoint> points;
    private final boolean closed;
    private final Instant createdAt;
    private final Instant updatedAt;

    public RouteDefinition(String id, String name, List<GeoPoint> points, Instant createdAt, Instant updatedAt) {
        this(id, name, points, true, createdAt, updatedAt);
    }

    public RouteDefinition(String id, String name, List<GeoPoint> points, boolean closed,
                           Instant createdAt, Instant updatedAt) {
        if (id == null || id.trim().isEmpty()) {
            throw new IllegalArgumentException("路线 id 不能为空");
        }
        if (points == null) {
            throw new IllegalArgumentException("points == null");
        }
        this.id = id;
        this.name = name == null ? "" : name;
        this.points = Collections.unmodifiableList(new ArrayList<>(points));
        this.closed = closed;
        this.createdAt = createdAt == null ? Instant.now() : createdAt;
        this.updatedAt = updatedAt == null ? this.createdAt : updatedAt;
    }

    public static RouteDefinition createNew(String id, String name, List<GeoPoint> points) {
        Instant now = Instant.now();
        return new RouteDefinition(id, name, points, true, now, now);
    }

    public RouteDefinition withName(String newName) {
        return new RouteDefinition(id, newName, points, closed, createdAt, Instant.now());
    }

    public RouteDefinition withPoints(List<GeoPoint> newPoints) {
        return new RouteDefinition(id, name, newPoints, closed, createdAt, Instant.now());
    }

    public RouteDefinition withClosed(boolean newClosed) {
        if (newClosed == closed) {
            return this;
        }
        return new RouteDefinition(id, name, points, newClosed, createdAt, Instant.now());
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

    /** 是否闭合；{@code false} 表示运行期按“原路返回”计圈。 */
    public boolean closed() {
        return closed;
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
