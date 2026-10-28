package org.localroute.probe;

/**
 * 高德两条通道的「选项自检」规则（R32-02）。
 *
 * <p>为什么单独抽出来：`setLocationPurpose(Sport)` 会按场景预设覆盖一批选项，0.3.2 就是先设
 * `setMockEnable(true)` 再套场景，结果对照通道实测仍是 `mockEnable=false`——对照形同虚设，
 * 而报告上看不出任何异常。规则很简单：主通道必须 false、对照通道必须 true，任何一个反了都要吼出来。
 *
 * <p>不依赖高德 SDK，可以在 JVM 里直接测。
 */
public final class AmapOptionCheck {

    private AmapOptionCheck() {
    }

    public static boolean primaryOk(boolean primaryMockEnable) {
        return !primaryMockEnable;
    }

    public static boolean devOk(boolean devMockEnable) {
        return devMockEnable;
    }

    public static boolean allOk(boolean primaryMockEnable, boolean devMockEnable) {
        return primaryOk(primaryMockEnable) && devOk(devMockEnable);
    }

    /** 一行结论，✓/✗ 直接进报告。 */
    public static String describe(boolean primaryMockEnable, boolean devMockEnable) {
        StringBuilder text = new StringBuilder("自检：主通道 mockEnable=").append(primaryMockEnable);
        text.append(primaryOk(primaryMockEnable)
                ? " ✓"
                : " ✗（复现目标策略时必须是 false）");
        text.append("，对照通道 mockEnable=").append(devMockEnable);
        text.append(devOk(devMockEnable)
                ? " ✓"
                : " ✗（必须是 true；为 false 说明 Sport 场景把 mockEnable 覆盖回去了，对照无效）");
        return text.toString();
    }
}
