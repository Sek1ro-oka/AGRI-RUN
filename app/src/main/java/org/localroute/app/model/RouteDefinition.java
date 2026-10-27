package org.localroute.app.model;

import org.localroute.core.EndBehavior;
import org.localroute.core.GeoPoint;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一条可保存的路线定义（schemaVersion 2）。
 *
 * <p>几何与运行策略分开表达（审查方案 5.2）：
 * <ul>
 *   <li>{@code closed}：几何是否首尾相接，由页面上的「将当前末点连接到第一个点」决定；</li>
 *   <li>{@code endBehavior}：怎么跑。闭合路线固定 {@link EndBehavior#LOOP}；开口路线默认
 *       {@link EndBehavior#STOP_AT_END}（开到终点就停），也可选 {@link EndBehavior#SHUTTLE}（原路往返）。</li>
 * </ul>
 *
 * <p>{@code runDefaults} 只保存下次预填的次数与速度，不参与正在运行的任务。
 */
public final class RouteDefinition {

    public static final int SCHEMA_VERSION = 2;
    public static final String COORDINATE_SYSTEM_WGS84 = "WGS84";

    private final String id;
    private final String name;
    private final List<GeoPoint> points;
    private final boolean closed;
    private final EndBehavior endBehavior;
    private final RunDefaults runDefaults;
    private final Instant createdAt;
    private final Instant updatedAt;

    /** v1 兼容构造器：{@code closed=false} 按旧的“原路往返”语义解释。 */
    public RouteDefinition(String id, String name, List<GeoPoint> points, Instant createdAt, Instant updatedAt) {
        this(id, name, points, true, createdAt, updatedAt);
    }

    /** v1 兼容构造器。 */
    public RouteDefinition(String id, String name, List<GeoPoint> points, boolean closed,
                           Instant createdAt, Instant updatedAt) {
        this(id, name, points, closed, EndBehavior.legacyForClosed(closed), RunDefaults.defaults(),
                createdAt, updatedAt);
    }

    public RouteDefinition(String id, String name, List<GeoPoint> points, boolean closed,
                           EndBehavior endBehavior, RunDefaults runDefaults,
                           Instant createdAt, Instant updatedAt) {
        if (id == null || id.trim().isEmpty()) {
            throw new IllegalArgumentException("路线 id 不能为空");
        }
        if (points == null) {
            throw new IllegalArgumentException("points == null");
        }
        if (endBehavior == null) {
            throw new IllegalArgumentException("endBehavior == null");
        }
        if (closed && endBehavior != EndBehavior.LOOP) {
            throw new IllegalArgumentException("闭合几何只能搭配 LOOP：" + endBehavior);
        }
        if (!closed && endBehavior == EndBehavior.LOOP) {
            throw new IllegalArgumentException("开口几何不能搭配 LOOP");
        }
        this.id = id;
        this.name = name == null ? "" : name;
        this.points = Collections.unmodifiableList(new ArrayList<>(points));
        this.closed = closed;
        this.endBehavior = endBehavior;
        this.runDefaults = runDefaults == null ? RunDefaults.defaults() : runDefaults;
        this.createdAt = createdAt == null ? Instant.now() : createdAt;
        this.updatedAt = updatedAt == null ? this.createdAt : updatedAt;
    }

    /** 新建草稿：几何开口、到终点停止（审查方案 5.1 / 5.2）。 */
    public static RouteDefinition createNew(String id, String name, List<GeoPoint> points) {
        Instant now = Instant.now();
        return new RouteDefinition(id, name, points, false, EndBehavior.STOP_AT_END, RunDefaults.defaults(),
                now, now);
    }

    public RouteDefinition withName(String newName) {
        return new RouteDefinition(id, newName, points, closed, endBehavior, runDefaults, createdAt, Instant.now());
    }

    public RouteDefinition withPoints(List<GeoPoint> newPoints) {
        return new RouteDefinition(id, name, newPoints, closed, endBehavior, runDefaults, createdAt, Instant.now());
    }

    /**
     * 切换几何闭合。
     *
     * <p>勾选闭合 → 绕圈；取消勾选 → 回到开口策略：优先沿用之前的开口策略（往返），
     * 否则用默认的“到终点停止”。不在这里把开口草稿悄悄改成往返。
     */
    public RouteDefinition withClosed(boolean newClosed) {
        if (newClosed == closed) {
            return this;
        }
        EndBehavior behavior;
        if (newClosed) {
            behavior = EndBehavior.LOOP;
        } else {
            behavior = endBehavior == EndBehavior.SHUTTLE ? EndBehavior.SHUTTLE : EndBehavior.STOP_AT_END;
        }
        return new RouteDefinition(id, name, points, newClosed, behavior, runDefaults, createdAt, Instant.now());
    }

    /** 设置开口路线的终点策略；闭合路线只能 LOOP。 */
    public RouteDefinition withEndBehavior(EndBehavior newBehavior) {
        if (newBehavior == endBehavior) {
            return this;
        }
        if (closed && newBehavior != EndBehavior.LOOP) {
            throw new IllegalArgumentException("闭合路线只能绕圈：" + newBehavior);
        }
        if (!closed && newBehavior == EndBehavior.LOOP) {
            return withClosed(true).withEndBehavior(EndBehavior.LOOP);
        }
        return new RouteDefinition(id, name, points, closed, newBehavior, runDefaults, createdAt, Instant.now());
    }

    public RouteDefinition withRunDefaults(RunDefaults newDefaults) {
        if (newDefaults == null || newDefaults == runDefaults) {
            return this;
        }
        return new RouteDefinition(id, name, points, closed, endBehavior, newDefaults, createdAt, Instant.now());
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

    /** 几何是否闭合。 */
    public boolean closed() {
        return closed;
    }

    /** 运行策略。 */
    public EndBehavior endBehavior() {
        return endBehavior;
    }

    public RunDefaults runDefaults() {
        return runDefaults;
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
