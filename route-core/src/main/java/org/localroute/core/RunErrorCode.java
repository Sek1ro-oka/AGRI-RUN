package org.localroute.core;

/**
 * 统一错误码。界面与日志只展示这些码，避免出现没人认识的自由文本。
 */
public enum RunErrorCode {

    /** 路线不合法（点数不足、单圈过短、坐标越界等）。 */
    ROUTE_INVALID("路线不可用"),
    /** 缺少定位权限（含被用户撤回）。 */
    LOCATION_PERMISSION_MISSING("缺少定位权限"),
    /** 未在开发者选项中选择本应用为模拟位置应用。 */
    MOCK_APP_NOT_SELECTED("未选择模拟位置应用"),
    /** 系统定位开关关闭。 */
    LOCATION_DISABLED("系统定位已关闭"),
    /** 创建/启用测试 provider 失败。 */
    PROVIDER_SETUP_FAILED("模拟位置 provider 创建失败"),
    /** 提交位置失败。 */
    PUBLISH_FAILED("位置提交失败"),
    /** 调度被中断（Service 被系统或用户终止、WakeLock 丢失等）。 */
    SCHEDULER_INTERRUPTED("模拟调度被中断");

    private final String defaultMessage;

    RunErrorCode(String defaultMessage) {
        this.defaultMessage = defaultMessage;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
