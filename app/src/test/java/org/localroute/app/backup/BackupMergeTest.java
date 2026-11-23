package org.localroute.app.backup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class BackupMergeTest {

    private static List<BackupMerge.Entry> entries(long... stamps) {
        List<BackupMerge.Entry> items = new ArrayList<>();
        for (int i = 0; i < stamps.length; i++) {
            items.add(new BackupMerge.Entry("id-" + i, stamps[i]));
        }
        return items;
    }

    @Test
    public void replacePolicy_dropsLocalEntriesMissingFromBackup() {
        BackupMerge.Plan plan = BackupMerge.plan(entries(0L, 0L), List.of(
                new BackupMerge.Entry("id-0", 0L)), BackupMerge.Policy.REPLACE);

        assertEquals(1, plan.count(BackupMerge.Action.REPLACE));
        assertEquals(1, plan.count(BackupMerge.Action.DROP));
        assertEquals(1, plan.dropped());
    }

    @Test
    public void keepMine_neverDropsAndOnlyFillsGaps() {
        BackupMerge.Plan plan = BackupMerge.plan(entries(0L, 0L), List.of(
                new BackupMerge.Entry("id-1", 5L),
                new BackupMerge.Entry("id-2", 7L)), BackupMerge.Policy.KEEP_MINE);

        assertEquals(1, plan.count(BackupMerge.Action.KEEP));
        assertEquals(1, plan.count(BackupMerge.Action.ADD));
        assertEquals(0, plan.count(BackupMerge.Action.REPLACE));
        assertEquals(0, plan.dropped());
        // 本机两条各自对应 KEEP / ADD，顺序是「先本机后新增」。
        assertEquals(List.of(BackupMerge.Action.KEEP, BackupMerge.Action.ADD), plan.actions());
    }

    @Test
    public void mergeNewer_usesBackupOnlyWhenItIsNewer() {
        List<BackupMerge.Entry> local = List.of(
                new BackupMerge.Entry("a", 100L),
                new BackupMerge.Entry("b", 100L),
                new BackupMerge.Entry("c", 100L));
        List<BackupMerge.Entry> incoming = List.of(
                new BackupMerge.Entry("a", 200L),
                new BackupMerge.Entry("b", 100L),
                new BackupMerge.Entry("c", 0L));

        BackupMerge.Plan plan = BackupMerge.plan(local, incoming, BackupMerge.Policy.MERGE_NEWER);

        assertEquals(List.of(BackupMerge.Action.REPLACE, BackupMerge.Action.KEEP,
                BackupMerge.Action.KEEP), plan.actions());
    }

    @Test
    public void entries_followTheActionTheyDescribe() {
        BackupMerge.Entry local = new BackupMerge.Entry("a", 50L);
        BackupMerge.Entry backup = new BackupMerge.Entry("a", 90L);

        BackupMerge.Plan replaced = BackupMerge.plan(List.of(local), List.of(backup),
                BackupMerge.Policy.MERGE_NEWER);
        assertEquals(90L, replaced.entries().get(0).updatedAt());

        BackupMerge.Plan kept = BackupMerge.plan(List.of(local), List.of(backup),
                BackupMerge.Policy.KEEP_MINE);
        assertEquals(50L, kept.entries().get(0).updatedAt());
    }

    @Test
    public void duplicateIdsInBackup_areAddedOnlyOnce() {
        BackupMerge.Plan plan = BackupMerge.plan(List.of(), List.of(
                new BackupMerge.Entry("a", 0L),
                new BackupMerge.Entry("a", 0L),
                new BackupMerge.Entry("b", 0L)), BackupMerge.Policy.KEEP_MINE);

        assertEquals(2, plan.count(BackupMerge.Action.ADD));
        assertEquals(2, plan.entries().size());
    }

    @Test
    public void nothingToDo_flagsAnImportThatChangesNothing() {
        List<BackupMerge.Entry> local = entries(0L, 0L);
        assertTrue(BackupMerge.plan(local, List.of(), BackupMerge.Policy.KEEP_MINE).nothingToDo());
        assertFalse(BackupMerge.plan(local, entries(0L, 0L, 0L),
                BackupMerge.Policy.KEEP_MINE).nothingToDo());
        // 整段替换时本机条目被顶掉，即使没有新增也算有变化。
        assertFalse(BackupMerge.plan(local, List.of(), BackupMerge.Policy.REPLACE).nothingToDo());
    }
}
