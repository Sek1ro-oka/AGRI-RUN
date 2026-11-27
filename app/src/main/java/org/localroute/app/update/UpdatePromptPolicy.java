package org.localroute.app.update;

import androidx.annotation.Nullable;

/**
 * 启动时是否要弹「发现新版本」。
 *
 * <p>同一版本只自动提醒一次：提醒过就记下版本号，手动检查仍可随时看。
 * 纯 Java，不依赖 Android，可在 JVM 单元测试中直接验证。
 */
public final class UpdatePromptPolicy {

    private UpdatePromptPolicy() {
    }

    /**
     * @param remoteVersion   GitHub 最新版本
     * @param localVersion    当前安装版本
     * @param promptedVersion 已经自动提醒过的版本；空串表示还没提醒过
     */
    public static boolean shouldPrompt(@Nullable String remoteVersion,
                                       @Nullable String localVersion,
                                       @Nullable String promptedVersion) {
        if (!UpdateChecker.isNewer(remoteVersion, localVersion)) {
            return false;
        }
        String prompted = promptedVersion == null ? "" : promptedVersion.trim();
        if (prompted.isEmpty()) {
            return true;
        }
        // 标签可能从 0.13 写成 0.13.0：按解析后的版本比，语义相同就不重复弹。
        return UpdateChecker.isNewer(remoteVersion, prompted)
                || UpdateChecker.isNewer(prompted, remoteVersion);
    }
}
