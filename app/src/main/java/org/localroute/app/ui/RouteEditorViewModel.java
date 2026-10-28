package org.localroute.app.ui;

import androidx.lifecycle.ViewModel;

import org.localroute.app.model.RouteDefinition;
import org.localroute.app.model.RunDefaults;
import org.localroute.app.storage.RouteRepository;
import org.localroute.core.CompiledRoute;
import org.localroute.core.EndBehavior;
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

    private RouteDefinition draft = RouteDefinition.createNew(RouteRepository.newRouteId(), "", new ArrayList<>());
    private final List<GeoPoint> undoStack = new ArrayList<>();
    private boolean dirty;

    /** 编辑代次：每次改动草稿 +1，用来发现「保存期间又编辑了」（R-009）。 */
    private long editRevision;
    /** 草稿被整体替换的次数（加载 / 新建 / 删除）；与 editRevision 一起构成保存令牌。 */
    private long draftGeneration;
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

    /**
     * 传送选点模式（0.1.2）：进入后，下一次点地图不再是「加节点」，而是把模拟位置传送过去。
     *
     * <p>放在 ViewModel 里是因为选点期间旋转屏幕会重建 Activity，模式状态不能丢。
     */
    private boolean teleportMode;

    public boolean isTeleportMode() {
        return teleportMode;
    }

    public void beginTeleport() {
        teleportMode = true;
    }

    public void cancelTeleport() {
        teleportMode = false;
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

    /** 当前草稿的几何是否闭合（新草稿默认开口，由页面上的勾选框决定）。 */
    public boolean isClosed() {
        return draft.closed();
    }

    /** 切换「将当前末点连接到第一个点」；返回切换后的值。 */
    public boolean toggleClosed() {
        draft = draft.withClosed(!draft.closed());
        markDirty();
        return draft.closed();
    }

    /** 终点策略：闭合=绕圈；开口=到终点停止或原路往返。 */
    public EndBehavior endBehavior() {
        return draft.endBehavior();
    }

    /** 开口路线的终点策略；闭合路线传非 LOOP 会被忽略（仍是绕圈）。 */
    public void setEndBehavior(EndBehavior behavior) {
        if (behavior == null || behavior == draft.endBehavior()) {
            return;
        }
        if (draft.closed() && behavior != EndBehavior.LOOP) {
            // 闭合几何只能绕圈；界面在闭合时隐藏这组单选，程序性恢复时应静默忽略。
            return;
        }
        draft = draft.withEndBehavior(behavior);
        markDirty();
    }

    /** 开口单程：只走一次，次数不可设。 */
    public boolean isSingleRun() {
        return draft.endBehavior().isSingleRun();
    }

    public RunDefaults runDefaults() {
        return draft.runDefaults();
    }

    /** 把界面的速度记进路线的运行默认值（只影响下次预填，不影响正在运行的任务）。 */
    public void rememberSpeed(double speedKmh) {
        try {
            RunDefaults updated = draft.runDefaults().withSpeedKmh(speedKmh);
            if (updated.speedKmh() != draft.runDefaults().speedKmh()) {
                draft = draft.withRunDefaults(updated);
                markDirty();
            }
        } catch (IllegalArgumentException ignored) {
            // 非法中间输入不落盘，等用户改到合法值再记。
        }
    }

    /** 把界面的次数记进路线的运行默认值。 */
    public void rememberRepeatCount(int repeatCount) {
        try {
            RunDefaults updated = draft.runDefaults().withRepeatCount(repeatCount);
            if (updated.repeatCount() != draft.runDefaults().repeatCount()) {
                draft = draft.withRunDefaults(updated);
                markDirty();
            }
        } catch (IllegalArgumentException ignored) {
            // 同上。
        }
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

    /**
     * 用一批外部来源的点整体替换草稿点（目前用于 GPX 导入）。
     *
     * <p>保留路线 ID、名称与「闭合」开关——导入的是点，不是一条新路线；
     * 长度不是 2 个点也不拦，让用户接着编辑。历史撤销栈会清空：
     * 一次导入是「整体替换」，逐点撤销回不到导入前的状态，留着只会误导。
     *
     * @return 实际写入的点数（已去掉 {@code null} 与超上限的部分）
     */
    public int replacePoints(List<GeoPoint> newPoints) {
        List<GeoPoint> points = new ArrayList<>();
        if (newPoints != null) {
            for (GeoPoint point : newPoints) {
                if (point == null) {
                    continue;
                }
                if (points.size() >= RouteCompiler.MAX_POINTS) {
                    break;
                }
                points.add(point);
            }
        }
        applyPoints(points);
        undoStack.clear();
        return points.size();
    }

    /** 载入已保存路线作为当前草稿。 */
    public void load(RouteDefinition route) {
        draft = route;
        undoStack.clear();
        dirty = false;
        persisted = true;
        // 草稿整体换了：递增代次，让「保存上一条路线」的旧回调失效（R32-06）。
        draftGeneration++;
        editRevision++;
    }

    /** 丢弃当前草稿，换一条新路线。 */
    public void newDraft() {
        draft = RouteDefinition.createNew(RouteRepository.newRouteId(), "", new ArrayList<>());
        undoStack.clear();
        dirty = false;
        persisted = false;
        draftGeneration++;
        editRevision++;
    }

    public void rename(String name) {
        String cleaned = name == null ? "" : name.trim();
        if (cleaned.equals(draft.name())) {
            return;
        }
        draft = draft.withName(cleaned);
        markDirty();
    }

    /**
     * 保存成功后调用：草稿变成“已落盘”状态。
     *
     * <p>注意：只在「保存期间草稿没有被继续编辑」时才对；后台保存的真实调用方应使用
     * {@link #markSavedIfUnchanged(long)}，否则保存过程中新加的点会被误标成已保存（R-009）。
     */
    public void markSaved() {
        dirty = false;
        persisted = true;
    }

    /**
     * 仅当草稿自 {@code revision} 之后没有被修改过，才标记为已保存。
     *
     * @return 是否真的标记了（false 表示保存期间又有编辑，草稿仍然是 dirty）
     */
    public boolean markSavedIfUnchanged(long revision) {
        if (revision != editRevision) {
            return false;
        }
        markSaved();
        return true;
    }

    /** 当前编辑代次：每次草稿内容变化 +1，用于判断异步保存期间是否又有编辑。 */
    public long editRevision() {
        return editRevision;
    }

    /** 草稿整体替换（加载 / 新建 / 删除）的代次。 */
    public long draftGeneration() {
        return draftGeneration;
    }

    /** 取一份「保存此刻草稿」的令牌，保存回调时用它核对身份（R32-06）。 */
    public DraftSaveToken saveToken() {
        return new DraftSaveToken(draft.id(), draftGeneration, editRevision);
    }

    /**
     * 仅当令牌与当前草稿完全一致（同一条路线、同一代次、没有新编辑）时才标记已保存。
     *
     * @return 是否真的标记了；false 表示保存期间草稿被换掉或又被编辑
     */
    public boolean markSavedIfCurrent(DraftSaveToken token) {
        if (token == null || !token.matches(draft.id(), draftGeneration, editRevision)) {
            return false;
        }
        markSaved();
        return true;
    }

    private void markDirty() {
        dirty = true;
        editRevision++;
    }

    /** 删除文件后调用：保留 ID 与名称，但标记为未落盘。 */
    public void markDeleted() {
        persisted = false;
        draftGeneration++;
        markDirty();
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
            return RouteCompiler.compile(draft.points(), draft.endBehavior());
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
            CompiledRoute route = RouteCompiler.compile(points, draft.endBehavior());
            switch (draft.endBehavior()) {
                case LOOP:
                    return String.format(java.util.Locale.US, "%d 个节点 · 闭合 · 单圈 %.1f 米",
                            points.size(), route.lapLengthMeters());
                case SHUTTLE:
                    return String.format(java.util.Locale.US, "%d 个节点 · 原路往返 · 单程 %.1f 米 · 往返 %.1f 米",
                            points.size(), route.pathLengthMeters(), route.lapLengthMeters());
                default:
                    return String.format(java.util.Locale.US, "%d 个节点 · 单程 %.1f 米（到终点停止）",
                            points.size(), route.lapLengthMeters());
            }
        } catch (InvalidRouteException e) {
            return e.getMessage() == null ? "路线无效" : e.getMessage();
        }
    }

    private void applyPoints(List<GeoPoint> points) {
        draft = draft.withPoints(points);
        markDirty();
    }

    private void pushUndo(GeoPoint point) {
        undoStack.add(point);
        if (undoStack.size() > MAX_UNDO) {
            undoStack.remove(0);
        }
    }
}
