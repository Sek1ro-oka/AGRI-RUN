package org.localroute.app.settings;

import java.util.List;

/**
 * 把环境体检的结果读成「下一步做什么」。
 *
 * <p>{@link SettingsInspector#inspect(android.content.Context)} 给出的是一组状态，本页把它们按
 * {@link SetupItem} 的声明顺序重新排序后取第一个未就绪项，因此无论检查列表怎么拼，引导顺序都稳定：
 * 先把跑不起来的原因排掉（阻塞项），再谈悬浮窗和通知（可选项）。
 */
public final class SetupGuide {

    private SetupGuide() {
    }

    /** 第一个还没就绪的准备项；全部就绪（或列表为空）返回 {@code null}。 */
    public static SettingsInspector.Check next(List<SettingsInspector.Check> checks) {
        if (checks == null) {
            return null;
        }
        for (SetupItem item : SetupItem.values()) {
            for (SettingsInspector.Check check : checks) {
                if (check != null && check.item() == item && check.status() != SettingsInspector.Status.OK) {
                    return check;
                }
            }
        }
        return null;
    }

    /** 未就绪项数量，含可选项。 */
    public static int pending(List<SettingsInspector.Check> checks) {
        return count(checks, false);
    }

    /** 未就绪的阻塞项数量，即「还跑不起来」的原因有几条。 */
    public static int pendingBlocking(List<SettingsInspector.Check> checks) {
        return count(checks, true);
    }

    /** 阻塞项是否全部就绪（可选项缺了也算就绪）。 */
    public static boolean ready(List<SettingsInspector.Check> checks) {
        return pendingBlocking(checks) == 0;
    }

    private static int count(List<SettingsInspector.Check> checks, boolean blockingOnly) {
        if (checks == null) {
            return 0;
        }
        int total = 0;
        for (SettingsInspector.Check check : checks) {
            if (check == null || check.status() == SettingsInspector.Status.OK) {
                continue;
            }
            if (!blockingOnly || check.item().blocking()) {
                total++;
            }
        }
        return total;
    }
}
