package org.localroute.app.overlay;

import org.localroute.app.run.RunSnapshot;

/**
 * 悬浮控制台的显示条件（纯 Java，便于单测）。
 *
 * <p>0.8.3 起悬浮条不再「一切后台就出现」，而是只在虚拟定位任务真正运行、且用户已经离开
 * 跑圈地图时出现：
 * <ul>
 *   <li>没有运行中的任务（空闲 / 已结束）→ 不显示，避免冷启动就盖住别的应用；</li>
 *   <li>正在跑圈地图上看跑者 → 不显示，避免挡住地图；</li>
 *   <li>运行中切到校园快跑设置页、主页、作业/课表等其它页面或系统后台 → 显示。</li>
 * </ul>
 *
 * <p>用户关闭开关或没有「显示在其他应用上层」权限时同样不显示。
 */
public final class OverlayPolicy {

    private OverlayPolicy() {
    }

    public static boolean shouldShow(RunSnapshot snapshot, boolean enabled,
                                     boolean canDrawOverlays, boolean runMapVisible) {
        return enabled
                && canDrawOverlays
                && !runMapVisible
                && snapshot != null
                && snapshot.isActive();
    }
}
