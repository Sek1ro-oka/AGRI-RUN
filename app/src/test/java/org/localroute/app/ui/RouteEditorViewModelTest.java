package org.localroute.app.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;
import org.localroute.app.model.RouteDefinition;
import org.localroute.core.CompiledRoute;
import org.localroute.core.GeoPoint;

import java.time.Instant;
import java.util.Arrays;

/** 路线草稿编辑逻辑（增点、撤销、清空、校验、命名）。 */
public class RouteEditorViewModelTest {

    private RouteEditorViewModel viewModel;

    @Before
    public void setUp() {
        viewModel = new RouteEditorViewModel();
    }

    @Test
    public void startsEmptyAndNotPersisted() {
        assertEquals(0, viewModel.pointCount());
        assertFalse(viewModel.isPersisted());
        assertNull(viewModel.compileOrNull());
    }

    @Test
    public void addPointAppendsAndRejectsDuplicateOfLast() {
        GeoPoint first = new GeoPoint(30.0d, 120.0d);
        GeoPoint second = new GeoPoint(30.001d, 120.001d);
        assertTrue(viewModel.addPoint(first));
        assertFalse("与上一个节点重合应被拒绝", viewModel.addPoint(new GeoPoint(30.0d, 120.0d)));
        assertTrue(viewModel.addPoint(second));
        assertEquals(2, viewModel.pointCount());
        assertTrue(viewModel.isDirty());
    }

    @Test
    public void undoRemovesLastPointAndReportsIt() {
        viewModel.addPoint(new GeoPoint(30.0d, 120.0d));
        viewModel.addPoint(new GeoPoint(30.001d, 120.0d));
        GeoPoint removed = viewModel.undo();
        assertNotNull(removed);
        assertEquals(30.001d, removed.latitude(), 1e-9);
        assertEquals(1, viewModel.pointCount());
        viewModel.undo();
        assertNull("空草稿撤销应返回 null", viewModel.undo());
    }

    @Test
    public void clearPointsKeepsRouteId() {
        String id = viewModel.routeId();
        viewModel.addPoint(new GeoPoint(30.0d, 120.0d));
        viewModel.clearPoints();
        assertEquals(0, viewModel.pointCount());
        assertEquals(id, viewModel.routeId());
    }

    @Test
    public void compileSucceedsForTriangleAndReportsLapLength() {
        viewModel.addPoint(new GeoPoint(30.0d, 120.0d));
        viewModel.addPoint(new GeoPoint(30.0d, 120.001d));
        viewModel.addPoint(new GeoPoint(30.001d, 120.001d));
        CompiledRoute route = viewModel.compileOrNull();
        assertNotNull(route);
        assertTrue("单圈应大于 0", route.lapLengthMeters() > 0d);
        assertTrue(viewModel.validationMessage().contains("单圈"));
        assertTrue(viewModel.validationMessage().contains("3 个节点"));
    }

    @Test
    public void singlePointIsNotRunnable() {
        viewModel.addPoint(new GeoPoint(30.0d, 120.0d));
        assertNull(viewModel.compileOrNull());
        assertTrue(viewModel.validationMessage().contains("至少需要 2 个"));
    }

    @Test
    public void renameTrimsAndMarksDirty() {
        viewModel.rename("  操场北侧  ");
        assertEquals("操场北侧", viewModel.routeName());
        assertTrue(viewModel.isDirty());
    }

    @Test
    public void loadResetsDirtyAndPersistedFlags() {
        Instant now = Instant.parse("2026-10-26T12:00:00Z");
        RouteDefinition route = new RouteDefinition("route-loaded", "已保存路线",
                Arrays.asList(new GeoPoint(30.0d, 120.0d), new GeoPoint(30.0d, 120.001d)), now, now);
        viewModel.load(route);
        assertEquals("route-loaded", viewModel.routeId());
        assertEquals(2, viewModel.pointCount());
        assertFalse(viewModel.isDirty());
        assertTrue(viewModel.isPersisted());
    }

    @Test
    public void newDraftCreatesFreshIdentity() {
        String first = viewModel.routeId();
        viewModel.newDraft();
        assertFalse("新草稿应换一个 ID", first.equals(viewModel.routeId()));
        assertFalse(viewModel.isPersisted());
        assertEquals(0, viewModel.pointCount());
    }

    @Test
    public void toggleClosedChangesLapLengthAndPersistsFlag() {
        viewModel.addPoint(new GeoPoint(30.0d, 120.0d));
        viewModel.addPoint(new GeoPoint(30.0d, 120.001d));
        viewModel.addPoint(new GeoPoint(30.001d, 120.001d));

        CompiledRoute closed = viewModel.compileOrNull();
        assertNotNull(closed);
        assertTrue(viewModel.isClosed());
        assertTrue(closed.closed());
        assertTrue(viewModel.validationMessage().contains("闭合"));

        boolean nowClosed = viewModel.toggleClosed();
        assertFalse("切换后应变成不闭合", nowClosed);
        assertTrue("切换闭合状态属于未保存修改", viewModel.isDirty());

        CompiledRoute opened = viewModel.compileOrNull();
        assertNotNull(opened);
        assertFalse(opened.closed());
        assertTrue("往返一圈一定比直接闭合更长",
                opened.lapLengthMeters() > closed.lapLengthMeters());
        assertTrue(viewModel.validationMessage().contains("原路返回"));
        assertFalse(viewModel.toDefinition().closed());

        assertTrue("再切一次应恢复闭合", viewModel.toggleClosed());
        assertTrue(viewModel.toDefinition().closed());
    }

    @Test
    public void loadKeepsOpenFlagFromDefinition() {
        Instant now = Instant.parse("2026-10-26T12:00:00Z");
        RouteDefinition route = new RouteDefinition("route-open", "不闭合路线",
                Arrays.asList(new GeoPoint(30.0d, 120.0d), new GeoPoint(30.0d, 120.001d)),
                false, now, now);
        viewModel.load(route);
        assertFalse(viewModel.isClosed());
        CompiledRoute compiled = viewModel.compileOrNull();
        assertNotNull(compiled);
        assertFalse(compiled.closed());
    }

    @Test
    public void pendingSinglePointIsClearedOnDemand() {
        GeoPoint point = new GeoPoint(30.0d, 120.0d);
        assertNull(viewModel.pendingSinglePoint());
        viewModel.setPendingSinglePoint(point);
        assertEquals(point, viewModel.pendingSinglePoint());
        viewModel.clearPendingSinglePoint();
        assertNull(viewModel.pendingSinglePoint());
    }

    @Test
    public void teleportModeStartsOffAndCanBeToggled() {
        assertFalse(viewModel.isTeleportMode());
        viewModel.beginTeleport();
        assertTrue(viewModel.isTeleportMode());
        viewModel.cancelTeleport();
        assertFalse(viewModel.isTeleportMode());
    }

    @Test
    public void teleportModeDoesNotTouchDraftPoints() {
        viewModel.addPoint(new GeoPoint(30.0d, 120.0d));
        viewModel.beginTeleport();
        assertEquals(1, viewModel.pointCount());
        viewModel.cancelTeleport();
        assertEquals(1, viewModel.pointCount());
    }

    // ------------------------------------------------------------ GPX 导入替换草稿

    @Test
    public void replacePointsSwapsAllPointsAndKeepsIdentity() {
        viewModel.rename("操场");
        String id = viewModel.routeId();
        viewModel.addPoint(new GeoPoint(30.0d, 120.0d));

        int written = viewModel.replacePoints(Arrays.asList(
                new GeoPoint(31.0d, 121.0d),
                new GeoPoint(31.001d, 121.001d),
                new GeoPoint(31.002d, 121.002d)));

        assertEquals(3, written);
        assertEquals(3, viewModel.pointCount());
        assertEquals(id, viewModel.routeId());
        assertEquals("操场", viewModel.routeName());
        assertTrue("导入属于未保存修改", viewModel.isDirty());
        assertEquals(31.0d, viewModel.points().get(0).latitude(), 1e-9d);
    }

    @Test
    public void replacePointsKeepsClosedFlag() {
        viewModel.toggleClosed();
        assertFalse(viewModel.isClosed());

        viewModel.replacePoints(Arrays.asList(
                new GeoPoint(31.0d, 121.0d), new GeoPoint(31.001d, 121.001d)));

        assertFalse("导入不应改动闭合开关", viewModel.isClosed());
        assertTrue(viewModel.toggleClosed());
        assertTrue(viewModel.isClosed());
    }

    @Test
    public void replacePointsClearsUndoHistory() {
        viewModel.addPoint(new GeoPoint(30.0d, 120.0d));
        assertTrue(viewModel.canUndo());

        viewModel.replacePoints(Arrays.asList(
                new GeoPoint(31.0d, 121.0d), new GeoPoint(31.001d, 121.001d)));

        // 整体替换后撤销会把点清空，而不是回到导入前，所以历史必须清掉。
        assertEquals(2, viewModel.pointCount());
        assertTrue(viewModel.canUndo());
        viewModel.undo();
        assertEquals(1, viewModel.pointCount());
    }

    @Test
    public void replacePointsDropsNullsAndCapsAtMaxPoints() {
        java.util.List<GeoPoint> many = new java.util.ArrayList<>();
        many.add(new GeoPoint(0d, 0d));
        many.add(null);
        for (int i = 1; i <= org.localroute.core.RouteCompiler.MAX_POINTS; i++) {
            // 交替两个坐标，避免全部重合被后续编译丢掉（这里只验证点数上限）。
            many.add(new GeoPoint(0.0001d * (i % 2), 0.0001d * (i % 3)));
        }

        int written = viewModel.replacePoints(many);

        assertEquals(org.localroute.core.RouteCompiler.MAX_POINTS, written);
        assertEquals(org.localroute.core.RouteCompiler.MAX_POINTS, viewModel.pointCount());
    }

    @Test
    public void replacePointsWithNullEmptiesTheDraft() {
        viewModel.addPoint(new GeoPoint(30.0d, 120.0d));

        assertEquals(0, viewModel.replacePoints(null));
        assertEquals(0, viewModel.pointCount());
        assertFalse(viewModel.canUndo());
    }
}
