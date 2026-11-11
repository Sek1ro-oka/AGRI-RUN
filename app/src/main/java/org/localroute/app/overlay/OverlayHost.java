package org.localroute.app.overlay;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
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
 * <p>0.5.0 起悬浮条只在 Legion 处于后台时显示：前台时隐藏，避免盖住应用自己的界面；
 * 应用被切到后台（所有 Activity 都 STOPPED）后再出现。判断不依赖 Activity 生命周期回调
 * 的瞬时状态，而是延迟 150 ms 复查，避免旋转屏幕等重建过程造成闪烁。用户在面板上点
 * 「关闭」会把开关写回 false，之后必须在设置页手动重新开启。
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

    /** Activity STOPPED 后稍等再显示，避开旋转屏幕/页面重建的中间态。 */
    private static final long BACKGROUND_SETTLE_MS = 150L;

    private final Application app;
    private final SharedPreferences prefs;
    private final OverlayPanel panel;
    private final LiveData<RunSnapshot> snapshots;
    private final Observer<RunSnapshot> observer;
    private final OverlaySession session;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private OverlaySession.Listener currentListener;
    private boolean enabled;
    private boolean foreground = true;
    private int startedActivities;
    private boolean lifecycleInstalled;
    private RunSnapshot lastSnapshot;

    private final Runnable showWhenBackground = () -> {
        if (enabled && !foreground) {
            showNow();
        }
    };

    private OverlayHost(Context appContext) {
        this.app = (Application) appContext.getApplicationContext();
        this.prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.enabled = prefs.getBoolean(PREF_ENABLED, false);
        this.panel = new OverlayPanel(appContext, () -> setEnabled(false));
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
        installLifecycle();
    }

    /**
     * 按已保存的偏好恢复悬浮条。
     *
     * <p>应用启动时调用一次即可；它只确保前后台监听已安装，不会在前台弹出悬浮条——
     * 用户要看到面板，需要等 Legion 真正被切到后台。
     */
    public void restore() {
        installLifecycle();
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** 用户开关悬浮控制条：写偏好；前台只记住开关，切到后台才真正显示。 */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        prefs.edit().putBoolean(PREF_ENABLED, enabled).apply();
        if (enabled && !foreground) {
            showNow();
        } else {
            hideNow();
        }
    }

    /**
     * 按当前开关与前后台状态重新渲染一次（权限可能刚在系统设置里变化）。
     *
     * <p>前台只收起并记住开关；从系统授权页回到应用后，等下次切到后台再显示。
     */
    public void refresh() {
        if (enabled && !foreground) {
            showNow();
        } else {
            hideNow();
        }
    }

    private void installLifecycle() {
        if (lifecycleInstalled) {
            return;
        }
        lifecycleInstalled = true;
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityStarted(@NonNull Activity activity) {
                startedActivities++;
                foreground = true;
                handler.removeCallbacks(showWhenBackground);
                hideNow();
            }

            @Override
            public void onActivityStopped(@NonNull Activity activity) {
                startedActivities = Math.max(0, startedActivities - 1);
                if (startedActivities == 0) {
                    foreground = false;
                    // 再等一拍：Activity 重建时新页面通常已经 STARTED，不会误判为切后台。
                    handler.removeCallbacks(showWhenBackground);
                    handler.postDelayed(showWhenBackground, BACKGROUND_SETTLE_MS);
                }
            }

            @Override
            public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {
            }

            @Override
            public void onActivityResumed(@NonNull Activity activity) {
            }

            @Override
            public void onActivityPaused(@NonNull Activity activity) {
            }

            @Override
            public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {
            }

            @Override
            public void onActivityDestroyed(@NonNull Activity activity) {
            }
        });
    }

    private void showNow() {
        if (!enabled || foreground) {
            return;
        }
        if (!OverlayPanel.canDrawOverlays(app)) {
            // 没授权时不建立订阅，避免后台空转；授权后用户再切一次前后台即可。
            panel.hide();
            session.detach();
            return;
        }
        session.attach();
        render(lastSnapshot == null ? RunSnapshot.idle() : lastSnapshot);
    }

    private void hideNow() {
        session.detach();
        panel.hide();
    }

    /** 渲染面板；由 LiveData 主线程回调驱动，不受 Activity 生命周期影响。 */
    private void render(RunSnapshot snapshot) {
        if (!enabled || foreground) {
            return;
        }
        panel.sync(snapshot == null ? RunSnapshot.idle() : snapshot, true);
    }

    /** 测试与诊断用：当前是否有活跃订阅。 */
    boolean sessionAttached() {
        return session.isAttached();
    }
}
