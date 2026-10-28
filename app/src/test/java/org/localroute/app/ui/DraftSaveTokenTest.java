package org.localroute.app.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;
import org.localroute.core.GeoPoint;

/**
 * 保存令牌（R32-06）：只比 editRevision 会漏掉「保存期间切了草稿」。
 *
 * <p>真实场景：保存 A 的后台任务还在跑，用户点了「加载 B」。B 本身没被编辑，editRevision 与
 * 保存 A 时相同，于是旧回调把 B 也标成已保存——B 的 dirty 被清掉，用户以为存过了。
 */
public class DraftSaveTokenTest {

    private RouteEditorViewModel viewModel;

    @Before
    public void setUp() {
        viewModel = new RouteEditorViewModel();
    }

    @Test
    public void tokenMatchesWhileNothingChanges() {
        viewModel.addPoint(new GeoPoint(30.0d, 120.0d));
        DraftSaveToken token = viewModel.saveToken();
        assertTrue(viewModel.isDirty());
        assertTrue(viewModel.markSavedIfCurrent(token));
        assertFalse("匹配成功应清掉 dirty", viewModel.isDirty());
    }

    @Test
    public void editingDuringSaveKeepsDirty() {
        viewModel.addPoint(new GeoPoint(30.0d, 120.0d));
        DraftSaveToken token = viewModel.saveToken();
        viewModel.addPoint(new GeoPoint(30.001d, 120.001d));
        assertFalse(viewModel.markSavedIfCurrent(token));
        assertTrue("保存期间又有编辑，必须保持 dirty", viewModel.isDirty());
    }

    @Test
    public void switchingDraftDuringSaveKeepsDirty() {
        viewModel.addPoint(new GeoPoint(30.0d, 120.0d));
        DraftSaveToken tokenSavingA = viewModel.saveToken();

        // 用户切到「新建草稿」：没有任何编辑，但草稿被整体替换了。
        viewModel.newDraft();
        viewModel.addPoint(new GeoPoint(31.0d, 121.0d));

        assertFalse("切了草稿，旧令牌必须失效", viewModel.markSavedIfCurrent(tokenSavingA));
        assertTrue(viewModel.isDirty());
    }

    @Test
    public void newDraftBumpsGenerationEvenWithoutEdits() {
        long before = viewModel.draftGeneration();
        viewModel.newDraft();
        assertNotEquals(before, viewModel.draftGeneration());
    }

    @Test
    public void deletingBumpsGeneration() {
        viewModel.addPoint(new GeoPoint(30.0d, 120.0d));
        DraftSaveToken token = viewModel.saveToken();
        long generationBefore = viewModel.draftGeneration();
        viewModel.markDeleted();
        assertNotEquals(generationBefore, viewModel.draftGeneration());
        assertFalse(viewModel.markSavedIfCurrent(token));
    }

    @Test
    public void nullTokenNeverMatches() {
        assertFalse(viewModel.markSavedIfCurrent(null));
    }

    @Test
    public void tokenDescribesItsIdentity() {
        viewModel.addPoint(new GeoPoint(30.0d, 120.0d));
        String text = viewModel.saveToken().describe();
        assertTrue(text, text.contains("gen="));
        assertTrue(text, text.contains("rev="));
    }

    @Test
    public void legacyRevisionCheckStillWorks() {
        viewModel.addPoint(new GeoPoint(30.0d, 120.0d));
        long revision = viewModel.editRevision();
        assertEquals(revision, viewModel.editRevision());
        assertTrue(viewModel.markSavedIfUnchanged(revision));
    }
}
