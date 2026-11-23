package org.localroute.app.entry;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * 悬浮台磁贴的三态判定：开关开着但没授权，不能显示成「已经生效」。
 */
public class OverlayTileStateTest {

    @Test
    public void switchOffIsOff() {
        assertEquals(OverlayTileState.OFF, OverlayTileState.from(false, true));
        assertEquals(OverlayTileState.OFF, OverlayTileState.from(false, false));
    }

    @Test
    public void enabledWithPermissionIsOn() {
        assertEquals(OverlayTileState.ON, OverlayTileState.from(true, true));
    }

    @Test
    public void enabledWithoutPermissionIsUnavailable() {
        assertEquals(OverlayTileState.NO_PERMISSION, OverlayTileState.from(true, false));
    }
}
