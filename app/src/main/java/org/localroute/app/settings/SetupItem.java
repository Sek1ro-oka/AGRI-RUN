package org.localroute.app.settings;

/**
 * 环境准备项，声明顺序即推荐完成顺序。
 *
 * <p>前三项缺任何一项都跑不起来（没有精确位置、系统定位总开关关着、没被选为模拟位置应用），
 * 因此排在前面且 {@link #blocking()} 为真；悬浮窗和通知只是让运行过程更可控、更可感知，
 * 缺了不影响伪造位置，所以标成可选、排在最后，引导时也不该拦住用户。
 */
public enum SetupItem {

    LOCATION_PERMISSION(Entry.RUN, true),
    LOCATION_SWITCH(Entry.SYSTEM_LOCATION_SETTINGS, true),
    MOCK_APP(Entry.DEVELOPER_OPTIONS, true),
    OVERLAY(Entry.OVERLAY_SETTINGS, false),
    NOTIFICATIONS(Entry.NOTIFICATION_SETTINGS, false);

    /** 完成这一项要去哪里，由界面翻译成按钮文案与跳转。 */
    public enum Entry {
        /** 已就绪，无需跳转。 */
        NONE,
        /** 应用内运行时授权，只在跑圈页发起：那里统一处理「不再询问」后的降级说明。 */
        RUN,
        SYSTEM_LOCATION_SETTINGS,
        DEVELOPER_OPTIONS,
        OVERLAY_SETTINGS,
        NOTIFICATION_SETTINGS
    }

    private final Entry entry;
    private final boolean blocking;

    SetupItem(Entry entry, boolean blocking) {
        this.entry = entry;
        this.blocking = blocking;
    }

    public Entry entry() {
        return entry;
    }

    /** 未完成时是否无法跑圈。 */
    public boolean blocking() {
        return blocking;
    }
}
