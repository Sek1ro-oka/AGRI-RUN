package org.localroute.app.update;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.DownloadManager;

import org.junit.Test;

/**
 * describe() 是纯函数：把系统下载器的状态码与原因码翻成提示文案，
 * 让「更新下载失败」不再是查不出原因的一句话。
 */
public class UpdateManagerDescribeTest {

    @Test
    public void failedDownloadNamesHttpReasonAndLine() {
        String text = UpdateManager.describe(DownloadManager.STATUS_FAILED,
                DownloadManager.ERROR_HTTP_DATA_ERROR, 1048576, 74177693, "gh-proxy.org");
        assertTrue(text, text.startsWith("系统下载失败"));
        assertTrue(text, text.contains("HTTP 数据传输错误"));
        assertTrue(text, text.contains("原因码 " + DownloadManager.ERROR_HTTP_DATA_ERROR));
        assertTrue(text, text.contains("已下 1.0/70.7 MiB"));
        assertTrue(text, text.endsWith("线路 gh-proxy.org"));
    }

    @Test
    public void unknownReasonKeepsRawCode() {
        String text = UpdateManager.describe(DownloadManager.STATUS_FAILED,
                404, 0, 0, null);
        assertTrue(text, text.contains("原因码 404"));
        assertFalse(text, text.contains("线路"));
        assertFalse(text, text.contains("MiB"));
    }

    @Test
    public void successfulDownloadReportsProgressWithoutReason() {
        String text = UpdateManager.describe(DownloadManager.STATUS_SUCCESSFUL,
                DownloadManager.ERROR_HTTP_DATA_ERROR, 74177693, 74177693, "GitHub 直连");
        assertTrue(text, text.startsWith("系统下载已完成"));
        assertFalse(text, text.contains("原因"));
        assertTrue(text, text.contains("已下 70.7/70.7 MiB"));
    }

    @Test
    public void pausedAndRunningStatesAreDescribed() {
        assertEquals("系统下载已暂停",
                UpdateManager.describe(DownloadManager.STATUS_PAUSED, 0, 0, 0, null));
        assertEquals("系统下载中",
                UpdateManager.describe(DownloadManager.STATUS_RUNNING, 0, 0, 0, null));
        assertEquals("下载记录不可用", UpdateManager.describe(-1, 0, 0, 0, null));
    }

    @Test
    public void partialProgressWithoutTotal() {
        String text = UpdateManager.describe(DownloadManager.STATUS_FAILED,
                DownloadManager.ERROR_INSUFFICIENT_SPACE, 3145728, -1, null);
        assertTrue(text, text.contains("存储空间不足"));
        assertTrue(text, text.contains("已下 3.0 MiB"));
    }
}
