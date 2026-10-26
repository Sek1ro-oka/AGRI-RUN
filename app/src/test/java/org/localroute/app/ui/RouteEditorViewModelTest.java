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
}
