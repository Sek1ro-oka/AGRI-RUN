package org.localroute.app.ui;

import androidx.lifecycle.ViewModel;

import org.localroute.app.model.RouteDefinition;
import org.localroute.app.storage.RouteRepository;
import org.localroute.core.CompiledRoute;
import org.localroute.core.GeoPoint;
import org.localroute.core.InvalidRouteException;
import org.localroute.core.RouteCompiler;
import org.localroute.core.RunConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * 路线编辑草稿的状态容器。
 *
 * <p>旋转屏幕时 Activity 会重建，草稿保存在这里可以避免丢点；运行状态不受影响，
 * 它由 {@code SimulationService} 持有并推送到 {@code SimulationStateRepo}。
 */
public class RouteEditorViewModel extends ViewModel {

    private RouteDefinition draft =
            RouteDefinition.createNew(RouteRepository.newRouteId(), "", new ArrayList<>());
    private final List<GeoPoint> undoStack = new ArrayList<>();
    private boolean dirty;
    private boolean persisted;

    private static final int MAX_UNDO = 200;

    // 权限弹窗期间 Activity 可能重建，待启动参数放这里才不会丢（见 MainActivity.requestPermissionsThenStart）。
    private CompiledRoute pendingStartRoute;
    private RunConfig pendingStartConfig;
    /** 权限弹窗期间待发送的单点坐标（0.1.1）。 */
    private GeoPoint pendingSinglePoint;

    public void setPendingSinglePoint(GeoPoint point) {
        this.pendingSinglePoint = point;
    }

    public GeoPoint pendingSinglePoint() {
        return pendingSinglePoint;
    }

    public void clearPendingSinglePoint() {
        pendingSinglePoint = null;
    }

    public void setPendingStart(CompiledRoute route, RunConfig config) {
        this.pendingStartRoute = route;
        this.pendingStartConfig = config;
    }

    public CompiledRoute pendingStartRoute() {
        return pendingStartRoute;
    }

    public RunConfig pendingStartConfig() {
        return pendingStartConfig;
    }

    public void clearPendingStart() {
        pendingStartRoute = null;
        pendingStartConfig = null;
    }

    public List<GeoPoint> points() {
        return draft.points();
    }

    public int pointCount() {
        return draft.points().size();
    }

    public String routeId() {
        return draft.id();
    }

    public String routeName() {
        return draft.name();
    }

    public String displayName() {
        return draft.displayName();
    }

    public boolean isDirty() {
        return dirty;
    }

    public boolean isPersisted() {
        return persisted;
    }

    /** 当前草稿是否闭合（默认闭合；关闭后运行期按“原路返回”计圈）。 */
    public boolean isClosed() {
        return draft.closed();
    }

    /** 切换闭合/开口；返回切换后的值。 */
    public boolean toggleClosed() {
        draft = draft.withClosed(!draft.closed());
        dirty = true;
        return draft.closed();
    }

    /** 添加节点；与最后一个节点重复或超过 5000 点时拒绝，返回是否成功。 */
    public boolean addPoint(GeoPoint point) {
        if (point == null) {
            return false;
        }
        List<GeoPoint> points = new ArrayList<>(draft.points());
        if (!points.isEmpty() && points.get(points.size() - 1).isNear(point, 0.01d)) {
            return false;
        }
        if (points.size() >= RouteCompiler.MAX_POINTS) {
            return false;
        }
        points.add(point);
        applyPoints(points);
        pushUndo(point);
        return true;
    }

    /** 撤销最后一个节点；返回被移除的点，没有可撤销时返回 null。 */
    public GeoPoint undo() {
        List<GeoPoint> points = new ArrayList<>(draft.points());
        if (points.isEmpty()) {
            return null;
        }
        GeoPoint removed = points.remove(points.size() - 1);
        applyPoints(points);
        if (!undoStack.isEmpty()) {
            undoStack.remove(undoStack.size() - 1);
        }
        return removed;
    }

    public boolean canUndo() {
        return !draft.points().isEmpty();
    }

    /** 清空全部节点（保留路线 ID 与名称）。 */
    public void clearPoints() {
        if (draft.points().isEmpty()) {
            return;
        }
        applyPoints(new ArrayList<>());
        undoStack.clear();
    }

    /** 载入已保存路线作为当前草稿。 */
    public void load(RouteDefinition route) {
        draft = route;
        undoStack.clear();
        dirty = false;
        persisted = true;
    }

    /** 丢弃当前草稿，换一条新路线。 */
    public void newDraft() {
        draft = RouteDefinition.createNew(RouteRepository.newRouteId(), "", new ArrayList<>());
        undoStack.clear();
        dirty = false;
        persisted = false;
    }

    public void rename(String name) {
        String cleaned = name == null ? "" : name.trim();
        if (cleaned.equals(draft.name())) {
            return;
        }
        draft = draft.withName(cleaned);
        dirty = true;
    }

    /** 保存成功后调用：草稿变成“已落盘”状态。 */
    public void markSaved() {
        dirty = false;
        persisted = true;
    }

    /** 删除文件后调用：保留 ID 与名称，但标记为未落盘。 */
    public void markDeleted() {
        persisted = false;
        dirty = true;
    }

    public RouteDefinition toDefinition() {
        return draft;
    }

    /**
     * 尝试把当前草稿编译成可运行路线。
     *
     * @return 编译结果；草稿不足 2 个不同节点或单圈不足 1 米时返回 null
     */
    public CompiledRoute compileOrNull() {
        try {
            return RouteCompiler.compile(draft.points(), draft.closed());
        } catch (InvalidRouteException e) {
            return null;
        }
    }

    /** 给界面用的一句话校验说明。 */
    public String validationMessage() {
        List<GeoPoint> points = draft.points();
        if (points.isEmpty()) {
            return "点击地图添加节点，或使用「手动输入」";
        }
        if (points.size() == 1) {
            return "已添加 1 个节点：至少需要 2 个不同节点才能运行";
        }
        try {
            CompiledRoute route = RouteCompiler.compile(points, draft.closed());
            if (draft.closed()) {
                return String.format(java.util.Locale.US, "%d 个节点 · 闭合 · 单圈 %.1f 米",
                        points.size(), route.lapLengthMeters());
            }
            return String.format(java.util.Locale.US, "%d 个节点 · 不闭合（原路返回）· 单圈 %.1f 米",
                    points.size(), route.lapLengthMeters());
        } catch (InvalidRouteException e) {
            return e.getMessage() == null ? "路线无效" : e.getMessage();
        }
    }

    private void applyPoints(List<GeoPoint> points) {
        draft = draft.withPoints(points);
        dirty = true;
    }

    private void pushUndo(GeoPoint point) {
        undoStack.add(point);
        if (undoStack.size() > MAX_UNDO) {
            undoStack.remove(0);
        }
    }
}
