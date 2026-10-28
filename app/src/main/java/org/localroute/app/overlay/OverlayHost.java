package org.localroute.app.overlay;

import android.content.Context;
import android.content.SharedPreferences;

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
 */
public final class OverlayHost {

    private static final String PREFS = "ui";
    private static final String PREF_ENABLED = "overlay_enabled";

    private static OverlayHost instance;

    public static synchronized OverlayHost get(Context context) {
        if (instance == null) {
            instance = new OverlayHost(context.getApplicationContext());
        }
        return instance;
    }

    private final SharedPreferences prefs;
    private final OverlayPanel panel;
    private final LiveData<RunSnapshot> snapshots;
    private final Observer<RunSnapshot> observer;
    private final OverlaySession session;

    private OverlaySession.Listener currentListener;
    private boolean enabled;
    private RunSnapshot lastSnapshot;

    private OverlayHost(Context appContext) {
        this.prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.enabled = prefs.getBoolean(PREF_ENABLED, false);
        this.panel = new OverlayPanel(appContext);
        this.snapshots = SimulationStateRepo.get().snapshot();
        this.observer = snapshot -> {
            lastSnapshot = snapshot;
            OverlaySession.Listener listener = currentListener;
            if (listener != null) {
                listener.onSnapshot(snapshot);
            }
        };
        this.session = new OverlaySession(new SnapshotSource() {
            @Override
            public void subscribe(OverlaySession.Listener listener) {
                currentListener = listener;
                snapshots.observeForever(observer);
                RunSnapshot current = snapshots.getValue();
                listener.onSnapshot(current == null ? RunSnapshot.idle() : current);
            }

            @Override
            public void unsubscribe(OverlaySession.Listener listener) {
                // 与 subscribe 严格成对：不是「永不注销」的 observeForever。
                currentListener = null;
                snapshots.removeObserver(observer);
            }
        }, this::render);
    }

    /** 按已保存的偏好恢复悬浮条（应用启动时调用一次即可，和任何 Activity 无关）。 */
    public void restore() {
        applyEnabled(enabled);
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** 用户开关悬浮控制条：写偏好 + 建立/注销订阅。 */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        prefs.edit().putBoolean(PREF_ENABLED, enabled).apply();
        applyEnabled(enabled);
    }

    private void applyEnabled(boolean on) {
        if (on) {
            session.attach();
            render(lastSnapshot == null ? RunSnapshot.idle() : lastSnapshot);
        } else {
            session.detach();
            panel.hide();
        }
    }

    /** 渲染面板；由 LiveData 主线程回调驱动，不受 Activity 生命周期影响。 */
    private void render(RunSnapshot snapshot) {
        if (!enabled) {
            return;
        }
        panel.sync(snapshot == null ? RunSnapshot.idle() : snapshot, true);
    }

    /** 测试与诊断用：当前是否有活跃订阅。 */
    boolean sessionAttached() {
        return session.isAttached();
    }
}
