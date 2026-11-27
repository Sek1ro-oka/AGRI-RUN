package org.localroute.app.widget;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.localroute.app.study.StudyTimetableStore;

/**
 * 把「课表数据变了」接到小组件刷新上。
 *
 * <p>课表的写入点分散在手动添加、编辑、教务导入、清空、改作息、设开学日期六处，逐个去调刷新
 * 一定会漏；监听 {@link StudyTimetableStore#PREFS} 这一份偏好文件，一处挂钩覆盖全部写入，
 * 以后新增写入点也自动生效。代价是改一次作息也会重画组件——重画只是拼 RemoteViews，可以接受。
 *
 * <p>监听器必须一直被强引用持有：{@code SharedPreferences} 内部只用弱引用保存监听器，
 * 局部变量一离场就会被回收，刷新会静默失效。
 */
public final class TimetableWidgetSync {

    @Nullable
    private static SharedPreferences.OnSharedPreferenceChangeListener listener;

    private TimetableWidgetSync() {
    }

    /** 挂上监听；重复调用只做一次。桌面还没有小组件时不必调用，由调用方判断。 */
    public static synchronized void attach(@NonNull Context context) {
        if (listener != null) {
            return;
        }
        Context appContext = context.getApplicationContext();
        listener = (preferences, key) -> {
            TimetableWidgetProvider.updateAll(appContext);
            TimetableWidgetWideProvider.updateAll(appContext);
        };
        appContext.getSharedPreferences(StudyTimetableStore.PREFS, Context.MODE_PRIVATE)
                .registerOnSharedPreferenceChangeListener(listener);
    }

    /** 两个课表组件都没实例时才解除监听；任意一个还在桌面上就继续刷新。 */
    public static void detachIfUnused(@NonNull Context context) {
        if (TimetableWidgetProvider.hasInstances(context)
                || TimetableWidgetWideProvider.hasInstances(context)) {
            return;
        }
        detach(context);
    }

    /** 最后一个小组件被移除后解除监听，省掉之后每一次课表写入的重画。 */
    public static synchronized void detach(@NonNull Context context) {
        if (listener == null) {
            return;
        }
        context.getApplicationContext()
                .getSharedPreferences(StudyTimetableStore.PREFS, Context.MODE_PRIVATE)
                .unregisterOnSharedPreferenceChangeListener(listener);
        listener = null;
    }
}
