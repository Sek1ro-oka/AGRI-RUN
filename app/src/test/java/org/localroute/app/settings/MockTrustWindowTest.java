package org.localroute.app.settings;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 「系统还允许本应用模拟位置吗」的时间窗判断。
 *
 * <p>Android 没有读取当前模拟位置应用的接口，只能记住最近一次探测成功的时间，
 * 所以窗口的边界必须由测试钉住：太短会把已配好的用户反复赶回设置页，太长会让
 * 用户改选项之后清单还显示「已就绪」。
 */
public class MockTrustWindowTest {

    private static final long DAY = 24L * 60 * 60 * 1000;
    private static final long NOW = 1_700_000_000_000L;

    @Test
    public void neverRecordedIsNotTrusted() {
        assertFalse(SettingsInspector.withinTrustWindow(0L, NOW, DAY));
        assertFalse(SettingsInspector.withinTrustWindow(-1L, NOW, DAY));
    }

    @Test
    public void momentOfRecordingIsTrusted() {
        assertTrue(SettingsInspector.withinTrustWindow(NOW, NOW, DAY));
    }

    @Test
    public void insideWindowStaysTrusted() {
        long recorded = NOW - SettingsInspector.MOCK_TRUST_WINDOW_MS + DAY;
        assertTrue(SettingsInspector.withinTrustWindow(recorded, NOW, SettingsInspector.MOCK_TRUST_WINDOW_MS));
    }

    @Test
    public void windowEdgeIsStillTrusted() {
        long recorded = NOW - SettingsInspector.MOCK_TRUST_WINDOW_MS;
        assertTrue(SettingsInspector.withinTrustWindow(recorded, NOW, SettingsInspector.MOCK_TRUST_WINDOW_MS));
    }

    @Test
    public void pastWindowNeedsReconfirmation() {
        long recorded = NOW - SettingsInspector.MOCK_TRUST_WINDOW_MS - 1L;
        assertFalse(SettingsInspector.withinTrustWindow(recorded, NOW, SettingsInspector.MOCK_TRUST_WINDOW_MS));
    }

    @Test
    public void recordingInTheFutureIsNotTrusted() {
        // 时钟被往回调过：不能因为时间戳「还没到」就一直显示已就绪。
        assertFalse(SettingsInspector.withinTrustWindow(NOW + DAY, NOW, SettingsInspector.MOCK_TRUST_WINDOW_MS));
    }

    @Test
    public void trustWindowIsTwoWeeks() {
        assertTrue(SettingsInspector.MOCK_TRUST_WINDOW_MS >= 7 * DAY);
        assertTrue(SettingsInspector.MOCK_TRUST_WINDOW_MS <= 30 * DAY);
    }
}
