package org.localroute.app;

import android.app.Application;

import org.localroute.app.settings.AppearancePreferences;
import org.localroute.app.widget.TimetableWidgetProvider;
import org.localroute.app.widget.TimetableWidgetSync;

/**
 * 进程启动时把用户选过的界面风格应用到全局。
 *
 * <p>必须在第一个 Activity 创建之前执行，否则深色/浅色会在进页面的一瞬间跳一次。
 * 其它组件（悬浮窗、模拟定位服务）仍按需懒加载，这里不提前初始化。
 * 桌面上已经有课表小组件时顺带挂上数据变更监听：小组件的广播入口只在刷新时被系统唤起，
 * 不保证还活着，改完课表要能立刻看到变化只能靠应用自己通知。
 */
public final class UnloopApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        AppearancePreferences.apply(this);
        if (TimetableWidgetProvider.hasInstances(this)) {
            TimetableWidgetSync.attach(this);
        }
    }
}
