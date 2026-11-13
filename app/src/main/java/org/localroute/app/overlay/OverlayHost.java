package org.localroute.app.overlay;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;

import org.localroute.app.run.RunSnapshot;
import org.localroute.app.run.SimulationStateRepo;

/**
 * 悬浮条的唯一持有者（R32-04）。
 *
 * <p>0.3.2 的接线是：{@code MainActivity} 在 {@code observe(this, …)} 里刷新悬浮条，并在
 * {@code onDestroy()} 里 {@code hide()}。后果有两个——Activity 一 STOPPED（切到别的 App 就是这种
 * 情况）观察者停收，窗口还在但状态冻住；Activity 重建又会再注册一次。
 *
 * <p>现在改成单例：面板、订阅、开关状态都挂在应用上下文上，只有「用户开关悬浮窗」时才
 * attach / detach 一次（成对）。Activity 被销毁不再影响悬浮条，重建也不会多出第二个窗口。
 *
 * <p>0.8.3 起显隐条件改为「虚拟定位任务运行中，且用户已经离开跑圈地图」：
 * 跑圈地图上不遮挡地图；切到校园快跑设置页、主页、作业/课表等其它页面或系统后台时出现；
 * 任务结束立即收起。冷启动、只画路线或只浏览地图时不再提前弹出。是否显示由
 * {@link OverlayPolicy} 统一判断，{@code CampusRunActivity} 只负责上报跑圈地图是否可见。
 */
public final class OverlayHost {

    private static final String PREFS = "ui";
    private static final String PREF_ENABLED = "overlay_enabled";

    /** 离开跑圈地图后稍等再显示，避开页面切换 / Activity 重建的中间态。 */
    private static final long SHOW_DELAY_MS = 150L;

    private static OverlayHost instance;

    public static synchronized OverlayHost get(Context context) {
        if (instance == null) {
            instance = new OverlayHost(context.getApplicationContext());
        }
        return instance;
    }

    private final Context app;
    private final SharedPreferences prefs;
    private final OverlayPanel panel;
    private final LiveData<RunSnapshot> snapshots;
    private final Observer<RunSnapshot> observer;
    private final OverlaySession session;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private boolean enabled;
    /** 跑圈地图是否正显示在前台；由 {@code CampusRunActivity} 上报。 */
    private boolean runMapVisible;

    private final Runnable showWhenAllowed = this::refreshNow;

    private OverlayHost(Context appContext) {
        this.app = appContext.getApplicationContext();
        this.prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.enabled = prefs.getBoolean(PREF_ENABLED, false);
        this.panel = new OverlayPanel(appContext, () -> setEnabled(false));
        this.snapshots = SimulationStateRepo.get().snapshot();
        this.observer = this::render;
        this.session = new OverlaySession(new SnapshotSource() {
            @Override
            public void subscribe(OverlaySession.Listener listener) {
                snapshots.observeForever(observer);
                listener.onSnapshot(current());
            }

            @Override
            public void unsubscribe(OverlaySession.Listener listener) {
                // 与 subscribe 严格成对：不是「永不注销」的 observeForever。
                snapshots.removeObserver(observer);
            }
        }, this::render);
    }

    /**
     * 按已保存的偏好恢复悬浮条。
     *
     * <p>应用启动时调用一次即可；只有虚拟定位已在下单且不在跑圈地图时才显示。
     */
    public void restore() {
        refresh();
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** 用户开关悬浮控制条：写偏好，并按「运行中 + 是否在看跑圈地图」立即重算显隐。 */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        prefs.edit().putBoolean(PREF_ENABLED, enabled).apply();
        refresh();
    }

    /**
     * 跑圈地图是否可见。
     *
     * <p>由 {@code CampusRunActivity} 在切页、onStart / onStop 时上报。进入跑圈地图立即收起；
     * 离开时延迟一拍再显示，避免页面切换或重建造成闪烁。
     */
    public void setRunMapVisible(boolean visible) {
        if (runMapVisible == visible) {
            return;
        }
        runMapVisible = visible;
        handler.removeCallbacks(showWhenAllowed);
        if (visible) {
            refreshNow();
        } else {
            handler.postDelayed(showWhenAllowed, SHOW_DELAY_MS);
        }
    }

    /** 权限或运行状态可能刚变化：按当前条件重新渲染一次。 */
    public void refresh() {
        handler.removeCallbacks(showWhenAllowed);
        refreshNow();
    }

    private void refreshNow() {
        RunSnapshot current = current();
        if (OverlayPolicy.shouldShow(current, enabled,
                OverlayPanel.canDrawOverlays(app), runMapVisible)) {
            showNow(current);
        } else {
            hideNow();
        }
    }

    private RunSnapshot current() {
        RunSnapshot value = snapshots.getValue();
        return value == null ? SimulationStateRepo.get().current() : value;
    }

    private void showNow(RunSnapshot snapshot) {
        if (!OverlayPolicy.shouldShow(snapshot, enabled,
                OverlayPanel.canDrawOverlays(app), runMapVisible)) {
            hideNow();
            return;
        }
        session.attach();
        panel.sync(snapshot, true);
    }

    private void hideNow() {
        session.detach();
        panel.hide();
    }

    /** 渲染面板；由 LiveData 主线程回调驱动，不受 Activity 生命周期影响。 */
    private void render(RunSnapshot snapshot) {
        if (!OverlayPolicy.shouldShow(snapshot, enabled,
                OverlayPanel.canDrawOverlays(app), runMapVisible)) {
            hideNow();
            return;
        }
        panel.sync(snapshot == null ? RunSnapshot.idle() : snapshot, true);
    }

    /** 测试与诊断用：当前是否有活跃订阅。 */
    boolean sessionAttached() {
        return session.isAttached();
    }
}
