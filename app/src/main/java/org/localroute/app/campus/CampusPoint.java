package org.localroute.app.campus;

/**
 * 校园快跑地图上的设计坐标（单位：设计像素，画布固定为 360 × 620）。
 *
 * <p>设计坐标只用于绘制和交互；真正提交给系统模拟定位的坐标由
 * {@link CampusGeo} 按每条路线自己的米/单位比例换算成 WGS-84。
 */
public final class CampusPoint {

    public final double x;
    public final double y;

    public CampusPoint(double x, double y) {
        if (!Double.isFinite(x) || !Double.isFinite(y)) {
            throw new IllegalArgumentException("设计坐标必须是有限数：" + x + "," + y);
        }
        this.x = x;
        this.y = y;
    }

    public double distanceTo(CampusPoint other) {
        double dx = x - other.x;
        double dy = y - other.y;
        return Math.hypot(dx, dy);
    }

    @Override
    public String toString() {
        return String.format(java.util.Locale.US, "(%.1f, %.1f)", x, y);
    }
}
