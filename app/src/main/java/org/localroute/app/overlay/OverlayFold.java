package org.localroute.app.overlay;

/**
 * 悬浮控制台折叠 / 展开时的位置算术（纯 Java，便于单测）。
 *
 * <p>面板和小球是同一个悬浮窗的两种内容，尺寸差得多（面板约 192×150，小球 56），
 * 而窗口的 {@code x/y} 指的是左上角。直接换内容会让小球跳到面板的左上角，看着像「飞走了」，
 * 所以先按中心不动换算左上角，再夹回屏幕里。横竖两个方向共用同一套算法。
 */
public final class OverlayFold {

    private OverlayFold() {
    }

    /**
     * 内容换成另一个尺寸后，让中心留在原处的左上角坐标。
     *
     * <p>变小（面板→小球）时往里收，变大（小球→面板）时往外挪，奇数尺寸按整除丢半像素，
     * 视觉上看不出来。结果可能是负数，越界交给 {@link #clamp} 收边。
     */
    public static int keepCenter(int start, int oldSize, int newSize) {
        return start + (oldSize - newSize) / 2;
    }

    /**
     * 把左上角夹在屏幕内。
     *
     * <p>屏幕比视图还窄（横屏分屏、极小窗口）时贴左 / 上边，不让小球跑到完全看不见的地方。
     */
    public static int clamp(int start, int size, int screen) {
        return Math.max(0, Math.min(start, Math.max(0, screen - size)));
    }

    /** 松手时位移没超过触摸阈值就算「点一下」，否则算拖动。 */
    public static boolean isTap(float deltaX, float deltaY, int slopPixels) {
        return Math.hypot(deltaX, deltaY) <= Math.max(1, slopPixels);
    }
}
