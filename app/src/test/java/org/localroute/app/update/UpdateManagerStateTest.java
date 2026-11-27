package org.localroute.app.update;

import android.app.DownloadManager;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 更新页进度条用的状态快照：哪些算「还没结束」、百分比怎么算。
 */
public class UpdateManagerStateTest {

    @Test
    public void activeCoversQueuedRunningAndPaused() {
        assertTrue(state(DownloadManager.STATUS_PENDING, 0, 0).active());
        assertTrue(state(DownloadManager.STATUS_RUNNING, 1, 2).active());
        assertTrue(state(DownloadManager.STATUS_PAUSED, 1, 2).active());
        assertFalse(state(DownloadManager.STATUS_SUCCESSFUL, 2, 2).active());
        assertFalse(state(DownloadManager.STATUS_FAILED, 1, 2).active());
    }

    @Test
    public void percentIsClampedAndUnknownTotalIsNegative() {
        assertEquals(-1, state(DownloadManager.STATUS_RUNNING, 10, 0).percent());
        assertEquals(0, state(DownloadManager.STATUS_RUNNING, 0, 100).percent());
        assertEquals(42, state(DownloadManager.STATUS_RUNNING, 42, 100).percent());
        assertEquals(100, state(DownloadManager.STATUS_RUNNING, 120, 100).percent());
    }

    @Test
    public void sizeLabelKeepsOneDecimalMebibytes() {
        assertEquals("0.0", UpdateManager.sizeLabel(0));
        assertEquals("1.5", UpdateManager.sizeLabel(1572864));
        assertEquals("74.4", UpdateManager.sizeLabel(78014054));
    }

    private static UpdateManager.DownloadState state(int status, long bytes, long total) {
        return new UpdateManager.DownloadState(status, bytes, total);
    }
}
