package org.localroute.app.overlay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 折叠 / 展开时的位置换算：小球要落在面板的中心附近，且永远留在屏幕里。
 */
public class OverlayFoldTest {

    @Test
    public void shrinkingMovesTheTopLeftInwardByHalfTheDifference() {
        // 面板 200px 宽换成 56px 的小球：左上角右移 72，中心不动。
        assertEquals(172, OverlayFold.keepCenter(100, 200, 56));
    }

    @Test
    public void growingMovesTheTopLeftOutward() {
        // 56px 的小球换成 200px 的面板：左上角左移 72，仍在原位 100 的右边。
        assertEquals(28, OverlayFold.keepCenter(100, 56, 200));
    }

    @Test
    public void sameSizeKeepsTheTopLeft() {
        assertEquals(37, OverlayFold.keepCenter(37, 120, 120));
    }

    @Test
    public void oddSizesLoseAtMostOnePixel() {
        assertEquals(100 + 25, OverlayFold.keepCenter(100, 101, 50));
    }

    @Test
    public void clampingKeepsTheContentInsideTheScreen() {
        assertEquals(800, OverlayFold.clamp(1200, 100, 900));
        assertEquals(0, OverlayFold.clamp(-40, 100, 900));
        assertEquals(420, OverlayFold.clamp(420, 100, 900));
    }

    @Test
    public void clampingOnANarrowerScreenPinsToZero() {
        // 分屏 / 极小窗口：屏幕放不下也要贴左 / 上边，而不是躲到负坐标里。
        assertEquals(0, OverlayFold.clamp(300, 400, 200));
    }

    @Test
    public void shortDragCountsAsTap() {
        assertTrue(OverlayFold.isTap(3f, 4f, 12));
        assertTrue(OverlayFold.isTap(0f, 0f, 12));
    }

    @Test
    public void longDragCountsAsMove() {
        assertFalse(OverlayFold.isTap(9f, 9f, 12));
        assertFalse(OverlayFold.isTap(20f, 0f, 12));
    }

    @Test
    public void missingSlopStillNeedsMoreThanOnePixel() {
        assertTrue(OverlayFold.isTap(1f, 0f, 0));
        assertFalse(OverlayFold.isTap(2f, 2f, 0));
    }
}
