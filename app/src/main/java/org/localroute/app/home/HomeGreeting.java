package org.localroute.app.home;

import androidx.annotation.NonNull;

/**
 * 首页顶部的时段：决定问候语与那张插画用晨景、日景还是夜景。
 *
 * <p>纯 Java，只认小时数，措辞与图片资源由界面层映射，便于 JVM 断言边界。
 */
public final class HomeGreeting {

    public enum Phase {
        MORNING, DAY, NIGHT
    }

    /** 早晨从 5 点起，下午从 12 点起，18 点之后算晚上。 */
    public static final int MORNING_FROM_HOUR = 5;
    public static final int DAY_FROM_HOUR = 12;
    public static final int NIGHT_FROM_HOUR = 18;

    private HomeGreeting() {
    }

    @NonNull
    public static Phase phaseOf(int hourOfDay) {
        if (hourOfDay >= NIGHT_FROM_HOUR || hourOfDay < MORNING_FROM_HOUR) {
            return Phase.NIGHT;
        }
        return hourOfDay >= DAY_FROM_HOUR ? Phase.DAY : Phase.MORNING;
    }
}