package org.localroute.app;

import android.app.Application;

import org.localroute.app.settings.AppearancePreferences;

/**
 * 进程启动时把用户选过的界面风格应用到全局。
 *
 * <p>必须在第一个 Activity 创建之前执行，否则深色/浅色会在进页面的一瞬间跳一次。
 * 其它组件（悬浮窗、模拟定位服务）仍按需懒加载，这里不提前初始化。
 */
public final class UnloopApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        AppearancePreferences.apply(this);
    }
}
