package org.localroute.app.overlay;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.DrawableRes;
import androidx.annotation.StringRes;
import androidx.core.content.ContextCompat;

import org.localroute.app.R;
import org.localroute.app.campus.CampusGeo;
import org.localroute.app.run.RunSnapshot;
import org.localroute.app.service.SimulationService;
import org.localroute.app.settings.SpeedPresets;
import org.localroute.app.ui.CampusRunActivity;
import org.localroute.app.ui.MainActivity;
import org.localroute.core.GeoPoint;
import org.localroute.core.RunState;

/**
 * 跨应用悬浮「摇杆控制台」。
 *
 * <p>整体是一块紧凑的小面板：顶部功能行左「返回主界面」、中「暂停/继续跑圈」、
 * 右上角「⌊ ⌉」缩放键；中间一行左侧是「移动」按钮、中央是摇杆、右侧是「关闭悬浮屏」按钮；
 * 下方并排行走 / 跑步 / 骑行三个档位按钮。暂停按钮左右两格等宽，所以始终居中；缩放键沿
 * 对角线拖动即可让整个控制台等比放大缩小（往左下缩小、往右上放大，0.7—1.4 倍，持久化）。
 *
 * <p>摇杆默认锁定，点一下切到开口锁图样并解锁，再点一下回到闭锁；只有没有跑圈任务
 * （空闲或悬浮单点保持）时才允许解锁，路线跑圈期间由快照强制锁定。解锁后按住摇杆，
 * 每 500 毫秒向
 * {@link SimulationService#drive(Context, GeoPoint, double, double, double)} 下发方向与速度，
 * 由服务端在定位节拍里推进坐标并发布带速度/航向的定位，松手立即停止。
 *
 * <p>面板只在虚拟定位任务运行中、且用户已经离开跑圈地图时显示：跑圈地图上不遮挡地图，
 * 切到设置页、主页、作业/课表或系统后台时出现；任务结束立即收起。面板可拖动、位置与
 * 缩放持久化；没有「显示在其他应用上层」权限时整体不显示，由设置页引导授权。
 */
public final class OverlayPanel {

    /** 用户在设置页打开「悬浮控制台」后写这个键。 */
    public static final String PREF_ENABLED = "overlay_enabled";

    private static final String PREFS = "ui";
    private static final String KEY_X = "overlay_x";
    private static final String KEY_Y = "overlay_y";
    private static final String KEY_GEAR = "overlay_gear";
    private static final String KEY_SCALE = "overlay_scale";

    /** 摇杆命令间隔：与服务端 500 ms 的定位节拍一致，方向变化即时生效。 */
    private static final long DRIVE_INTERVAL_MS = 500L;

    /** 面板里圆形按钮的基准直径。 */
    private static final int BUTTON_DP = 36;
    /** 摇杆直径：与上下按钮排的宽度接近，居中摆放。 */
    private static final int JOYSTICK_DP = 108;
    /** 按钮之间的水平间距。 */
    private static final int GAP_DP = 6;
    /** 三枚按钮加两处间距并排放下的宽度，上下两行以它为基准宽度。 */
    private static final int CONTENT_DP = 120;
    /** 摇杆两侧各有「移动」「关闭」按钮，中间行比上下按钮排更宽。 */
    private static final int MIDDLE_DP = BUTTON_DP * 2 + JOYSTICK_DP + GAP_DP * 2;

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 是否已在系统设置里授予「显示在其他应用上层」。 */
    public static boolean canDrawOverlays(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }
        return Settings.canDrawOverlays(context);
    }

    /** 跳到系统的悬浮窗授权页。 */
    public static void requestPermission(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return;
        }
        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + context.getPackageName()));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(intent);
        } catch (RuntimeException ignored) {
            // 个别 ROM 没有这个页面：不崩，用户可去系统设置里手动找。
        }
    }

    private final Context context;
    private final WindowManager windowManager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SpeedPresets presets;
    private final ImageView[] gearButtons = new ImageView[3];
    /** 用户点「关闭悬浮屏」时回调宿主，由宿主关开关、停止订阅。 */
    private final Runnable closeAction;

    private OverlayScaleHost scaleHost;
    private LinearLayout root;
    private JoystickView joystick;
    private ScaleControlView scaleControl;
    private ImageView pauseButton;
    private WindowManager.LayoutParams params;

    private RunSnapshot snapshot = RunSnapshot.idle();
    private boolean running;
    private boolean moving;
    private boolean gestureAnnounced;
    private boolean joystickUnlocked;
    private float overlayScale = 1f;
    private int gear;
    private float moveX;
    private float moveY;

    private final Runnable moveRunnable = this::moveStep;

    public OverlayPanel(Context context, Runnable closeAction) {
        this.context = context.getApplicationContext();
        this.closeAction = closeAction;
        this.windowManager =
                (WindowManager) this.context.getSystemService(Context.WINDOW_SERVICE);
        SharedPreferences prefs = prefs(this.context);
        this.presets = new SpeedPresets(new SpeedPresets.Store() {
            @Override
            public String get(String key, String fallback) {
                return prefs.getString(key, fallback);
            }

            @Override
            public void put(String key, String value) {
                prefs.edit().putString(key, value).apply();
            }
        });
        this.gear = normalizeGear(prefs.getInt(KEY_GEAR, 1));
        this.overlayScale = ScaleControlView.clamp(prefs.getFloat(KEY_SCALE, 1f));
    }

    public boolean isShowing() {
        return running;
    }

    /**
     * 按快照决定显示、刷新或收起。
     *
     * <p>与运行状态无关：只在「用户关闭」或「没有悬浮权限」时收起。
     */
    public void sync(RunSnapshot snapshot, boolean enabled) {
        boolean allowed = canDrawOverlays(context);
        if (!enabled || !allowed) {
            hide();
            return;
        }
        if (root == null) {
            inflate();
        }
        update(snapshot == null ? RunSnapshot.idle() : snapshot);
        if (!running) {
            try {
                windowManager.addView(scaleHost, params);
                running = true;
                scaleHost.post(this::clampOnScreen);
            } catch (RuntimeException e) {
                android.util.Log.w("OverlayPanel", "addView 失败: " + e, e);
                // 权限被撤销或窗口被系统拒绝：保持安静，不要因为悬浮窗失败影响主流程。
                root = null;
                scaleHost = null;
                running = false;
            }
        }
    }

    public void hide() {
        stopMoving();
        // 每次收起（回前台或点关闭）都恢复默认闭锁，下次出现时不会带着解锁状态。
        joystickUnlocked = false;
        if (joystick != null) {
            joystick.setLocked(true);
            joystick.setInputEnabled(false);
        }
        if (!running || scaleHost == null) {
            running = false;
            return;
        }
        try {
            windowManager.removeView(scaleHost);
        } catch (RuntimeException ignored) {
            // 已经被系统移除。
        }
        running = false;
    }

    // ---------------------------------------------------------------- 界面

    private void inflate() {
        root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(6), dp(4), dp(6), dp(6));
        root.setClipChildren(false);
        root.setClipToPadding(false);

        // 中间行最宽：左「移动」、中摇杆、右「关闭」；上下两行仍按 120dp 居中。
        root.addView(buildTopRow());
        root.addView(buildControlCluster());
        updateGearStyles();

        scaleHost = new OverlayScaleHost(context);
        scaleHost.addView(root, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT));
        scaleHost.setScale(overlayScale);

        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                // 不抢焦点：底下的 App 仍能正常输入；同时触屏外区域透传。
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = prefs(context).getInt(KEY_X, dp(10));
        params.y = prefs(context).getInt(KEY_Y, dp(88));
        params.setTitle("Legion悬浮控制台");
        clampToScreen();
    }

    /** 顶部功能行：左返回、中暂停、右上角缩放键；左右两格等宽让暂停严格居中。 */
    private LinearLayout buildTopRow() {
        LinearLayout top = new LinearLayout(context);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        FrameLayout leftCell = new FrameLayout(context);
        ImageView home = circleIcon(R.drawable.ic_overlay_home, BUTTON_DP, 9,
                R.color.overlay_icon, R.string.overlay_home);
        home.setOnClickListener(v -> openMainScreen());
        leftCell.addView(home,
                new FrameLayout.LayoutParams(dp(BUTTON_DP), dp(BUTTON_DP), Gravity.CENTER));

        pauseButton = circleIcon(R.drawable.ic_overlay_pause, BUTTON_DP, 9,
                R.color.overlay_mint, R.string.overlay_pause_run);
        pauseButton.setOnClickListener(v -> togglePause());
        // 长按结束本次运动并直接回到跑圈地图，不再弹「再来一次 / 返回」确认。
        pauseButton.setOnLongClickListener(v -> {
            endRunFromOverlay();
            return true;
        });

        FrameLayout rightCell = new FrameLayout(context);
        scaleControl = new ScaleControlView(context);
        scaleControl.setScale(overlayScale);
        scaleControl.setListener(new ScaleControlView.Listener() {
            @Override
            public void onScaleChanged(float scale) {
                applyScale(scale, false);
            }

            @Override
            public void onScaleCommitted(float scale) {
                applyScale(scale, true);
            }
        });
        rightCell.addView(scaleControl,
                new FrameLayout.LayoutParams(dp(BUTTON_DP), dp(BUTTON_DP), Gravity.CENTER));

        top.addView(leftCell, new LinearLayout.LayoutParams(0, dp(BUTTON_DP), 1f));
        top.addView(pauseButton, new LinearLayout.LayoutParams(dp(BUTTON_DP), dp(BUTTON_DP)));
        top.addView(rightCell, new LinearLayout.LayoutParams(0, dp(BUTTON_DP), 1f));

        top.setLayoutParams(new LinearLayout.LayoutParams(dp(CONTENT_DP), dp(BUTTON_DP)));
        return top;
    }

    /** 控制区：左「移动」、中摇杆、右「关闭」，下方并排行走 / 跑步 / 骑行三个档位按钮。 */
    private LinearLayout buildControlCluster() {
        LinearLayout cluster = new LinearLayout(context);
        cluster.setOrientation(LinearLayout.VERTICAL);
        cluster.setGravity(Gravity.CENTER_HORIZONTAL);

        joystick = new JoystickView(context);
        joystick.setContentDescription(context.getString(R.string.overlay_joystick_locked));
        joystick.setLocked(!joystickUnlocked);
        joystick.setListener(new JoystickView.Listener() {
            @Override
            public void onMove(float x, float y) {
                moveX = x;
                moveY = y;
                if (Math.hypot(x, y) >= 0.04f) {
                    if (!gestureAnnounced) {
                        gestureAnnounced = true;
                        RunState state = snapshot.state();
                        if (state == RunState.IDLE || state == RunState.READY) {
                            toast(context.getString(R.string.overlay_idle_toast));
                        }
                    }
                    startMoving();
                }
            }

            @Override
            public void onRelease() {
                gestureAnnounced = false;
                moveX = 0f;
                moveY = 0f;
                stopMoving();
            }

            @Override
            public void onTap() {
                toggleJoystickLock();
            }
        });

        LinearLayout middle = new LinearLayout(context);
        middle.setOrientation(LinearLayout.HORIZONTAL);
        middle.setGravity(Gravity.CENTER);

        // 左：移动悬浮窗；按住拖动，与摇杆、关闭键互不抢触摸。
        ImageView moveButton = circleIcon(R.drawable.ic_overlay_move, BUTTON_DP, 7,
                R.color.overlay_icon, R.string.overlay_grip);
        moveButton.setOnTouchListener(new DragListener());
        middle.addView(moveButton,
                new LinearLayout.LayoutParams(dp(BUTTON_DP), dp(BUTTON_DP)));

        LinearLayout.LayoutParams joystickParams =
                new LinearLayout.LayoutParams(dp(JOYSTICK_DP), dp(JOYSTICK_DP));
        joystickParams.leftMargin = dp(GAP_DP);
        joystickParams.rightMargin = dp(GAP_DP);
        middle.addView(joystick, joystickParams);

        // 右：关闭悬浮屏并记住开关；下次在设置页手动开启，切到后台才再出现。
        ImageView closeButton = circleIcon(R.drawable.ic_overlay_close, BUTTON_DP, 9,
                R.color.overlay_close, R.string.overlay_close);
        closeButton.setOnClickListener(v -> {
            toast(context.getString(R.string.overlay_closed_toast));
            if (closeAction != null) {
                closeAction.run();
            } else {
                hide();
            }
        });
        middle.addView(closeButton,
                new LinearLayout.LayoutParams(dp(BUTTON_DP), dp(BUTTON_DP)));

        LinearLayout.LayoutParams middleParams =
                new LinearLayout.LayoutParams(dp(MIDDLE_DP), dp(JOYSTICK_DP));
        middleParams.topMargin = dp(GAP_DP);
        cluster.addView(middle, middleParams);

        LinearLayout gears = new LinearLayout(context);
        gears.setOrientation(LinearLayout.HORIZONTAL);
        gears.setGravity(Gravity.CENTER);

        int[] icons = {R.drawable.ic_overlay_walk, R.drawable.ic_overlay_run,
                R.drawable.ic_overlay_bike};
        int[] labels = {R.string.overlay_gear_walk, R.string.overlay_gear_run,
                R.string.overlay_gear_ride};
        for (int i = 0; i < gearButtons.length; i++) {
            final int index = i;
            ImageView button = circleIcon(icons[i], BUTTON_DP, 9,
                    R.color.overlay_icon, labels[i]);
            button.setOnClickListener(v -> selectGear(index, true));
            gearButtons[i] = button;
            LinearLayout.LayoutParams params =
                    new LinearLayout.LayoutParams(dp(BUTTON_DP), dp(BUTTON_DP));
            if (i > 0) {
                params.leftMargin = dp(GAP_DP);
            }
            gears.addView(button, params);
        }
        LinearLayout.LayoutParams gearParams =
                new LinearLayout.LayoutParams(dp(CONTENT_DP), dp(BUTTON_DP));
        gearParams.topMargin = dp(GAP_DP);
        cluster.addView(gears, gearParams);

        cluster.setLayoutParams(new LinearLayout.LayoutParams(dp(MIDDLE_DP),
                LinearLayout.LayoutParams.WRAP_CONTENT));
        return cluster;
    }

    /** 圆形深色按钮：薄荷绿描边 + 轻触涟漪。 */
    private ImageView circleIcon(@DrawableRes int iconRes, int sizeDp, int paddingDp,
                                 int tintRes, @StringRes int descRes) {
        ImageView view = new ImageView(context);
        view.setImageResource(iconRes);
        view.setImageTintList(ColorStateList.valueOf(
                ContextCompat.getColor(context, tintRes)));
        view.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int padding = dp(paddingDp);
        view.setPadding(padding, padding, padding, padding);
        view.setBackground(circleBackground(false));
        view.setContentDescription(context.getString(descRes));
        view.setClickable(true);
        return view;
    }

    private Drawable circleBackground(boolean selected) {
        GradientDrawable fill = new GradientDrawable();
        fill.setShape(GradientDrawable.OVAL);
        fill.setColor(ContextCompat.getColor(context, selected
                ? R.color.overlay_gear_selected_fill
                : R.color.overlay_button_fill));
        fill.setStroke(dp(selected ? 2 : 1), ContextCompat.getColor(context, selected
                ? R.color.overlay_mint
                : R.color.overlay_ring));

        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.OVAL);
        mask.setColor(Color.WHITE);
        return new RippleDrawable(ColorStateList.valueOf(
                ContextCompat.getColor(context, R.color.overlay_button_ripple)), fill, mask);
    }

    private void updateGearStyles() {
        for (int i = 0; i < gearButtons.length; i++) {
            ImageView button = gearButtons[i];
            if (button == null) {
                continue;
            }
            boolean selected = i == gear;
            button.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(context,
                    selected ? R.color.overlay_mint : R.color.overlay_icon)));
            button.setBackground(circleBackground(selected));
            button.setContentDescription(context.getString(gearLabelRes(i))
                    + " · " + SpeedPresets.format(presets.get(i)) + " km/h");
        }
    }

    private void update(RunSnapshot value) {
        snapshot = value == null ? RunSnapshot.idle() : value;
        if (pauseButton == null) {
            return;
        }
        OverlayViewState state = OverlayViewState.from(snapshot);
        boolean resume = state.actionIsResume();
        pauseButton.setImageResource(resume ? R.drawable.ic_overlay_play : R.drawable.ic_overlay_pause);
        pauseButton.setContentDescription(context.getString(resume
                ? R.string.overlay_resume_run
                : R.string.overlay_pause_run));
        pauseButton.setEnabled(state.actionEnabled());
        pauseButton.setAlpha(state.actionEnabled() ? 1f : 0.45f);

        boolean routeTask = snapshot.isActive() && !snapshot.singlePoint();
        if (routeTask && joystickUnlocked) {
            // 跑圈一开始就收回解锁：摇杆只服务于手动移动，不能干扰路线推进。
            joystickUnlocked = false;
            stopMoving();
        }
        boolean locked = routeTask || !joystickUnlocked;
        boolean paused = state.mode() == OverlayViewState.Mode.PAUSED;
        if (joystick != null) {
            joystick.setLocked(locked);
            joystick.setContentDescription(context.getString(locked
                    ? R.string.overlay_joystick_locked : R.string.overlay_joystick));
            joystick.setAlpha(paused ? 0.55f : locked ? 0.72f : 1f);
            joystick.setInputEnabled(!paused);
        }
        if (state.isIdle() || state.mode() == OverlayViewState.Mode.TERMINAL) {
            // 没有可推进的任务：停掉驱动，下一次拖动重新从当前位置/校园锚点开始。
            stopMoving();
        }
    }

    // ---------------------------------------------------------------- 交互

    private void togglePause() {
        OverlayViewState state = OverlayViewState.from(snapshot);
        switch (state.mode()) {
            case PAUSED:
                SimulationService.resume(context);
                toast(context.getString(R.string.overlay_resumed_toast));
                break;
            case RUNNING_ROUTE:
            case RUNNING_SINGLE_POINT:
                SimulationService.pause(context);
                toast(context.getString(R.string.overlay_paused_toast));
                break;
            case STARTING:
                toast(context.getString(R.string.overlay_starting_toast));
                break;
            default:
                toast(context.getString(R.string.overlay_idle_toast));
                break;
        }
    }

    /**
     * 长按顶部中间的暂停键：结束当前运动，并把跑圈地图调到前台显示本次结果。
     *
     * <p>停止命令和页面跳转都是异步的：页面先显示运行中的画面，服务发布终止快照后
     * 由 {@link CampusRunActivity} 切到「结果」模式，不弹完成确认框。
     */
    private void endRunFromOverlay() {
        if (!snapshot.isActive()) {
            toast(context.getString(R.string.overlay_idle_toast));
            return;
        }
        stopMoving();
        SimulationService.stop(context);
        toast(context.getString(R.string.overlay_end_toast));
        Intent intent = new Intent(context, CampusRunActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(CampusRunActivity.EXTRA_SHOW_RUN_RESULT, true);
        try {
            context.startActivity(intent);
        } catch (RuntimeException e) {
            // 个别 ROM 限制后台启动界面：停止已经发出，用户可从主界面进入查看。
            toast(context.getString(R.string.overlay_idle_toast));
        }
    }

    private void openMainScreen() {
        Intent intent = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try {
            context.startActivity(intent);
        } catch (RuntimeException e) {
            toast(context.getString(R.string.overlay_idle_toast));
        }
    }

    /**
     * 点一下摇杆切换锁定 / 解锁。
     *
     * <p>空闲或单点保持时允许切换；路线跑圈期间保持锁定并说明原因。解锁后摇杆中心显示
     * 开口锁图样，拖动才会产生移动；再点一下摇杆立刻回到闭锁图样并停止推进。
     */
    private void toggleJoystickLock() {
        if (snapshot.isActive() && !snapshot.singlePoint()) {
            toast(context.getString(R.string.overlay_locked_running_toast));
            return;
        }
        joystickUnlocked = !joystickUnlocked;
        if (joystickUnlocked) {
            toast(context.getString(R.string.overlay_unlock_toast));
        } else {
            stopMoving();
            toast(context.getString(R.string.overlay_lock_toast));
        }
        // 复用统一的渲染路径：锁定状态、透明度、无障碍描述都在这里更新。
        update(snapshot);
    }

    private void selectGear(int index, boolean announce) {
        gear = normalizeGear(index);
        prefs(context).edit().putInt(KEY_GEAR, gear).apply();
        updateGearStyles();
        if (!announce) {
            return;
        }
        double kmh = presets.get(gear);
        // 路线跑圈中立即改速；手动移动时下一次摇杆命令就按新档位计算速度。
        SimulationService.setSpeedKmh(context, kmh);
        toast(context.getString(R.string.overlay_gear_toast,
                context.getString(gearLabelRes(gear)), SpeedPresets.format(kmh)));
    }

    // ---------------------------------------------------------------- 缩放

    private void applyScale(float scale, boolean persist) {
        overlayScale = ScaleControlView.clamp(scale);
        if (scaleHost != null) {
            scaleHost.setScale(overlayScale);
        }
        if (persist) {
            prefs(context).edit().putFloat(KEY_SCALE, overlayScale).apply();
        }
        if (running && scaleHost != null) {
            try {
                windowManager.updateViewLayout(scaleHost, params);
            } catch (RuntimeException ignored) {
                // 视图已被移除。
            }
            scaleHost.post(this::clampOnScreen);
        }
    }

    // ---------------------------------------------------------------- 摇杆驱动

    private void startMoving() {
        if (moving || !running || isLocked()) {
            return;
        }
        moving = true;
        handler.post(moveRunnable);
    }

    private void stopMoving() {
        boolean wasMoving = moving;
        moving = false;
        handler.removeCallbacks(moveRunnable);
        if (wasMoving) {
            // 松手、暂停或收起都要让服务端把速度归零，人物不能自己一直走。
            SimulationService.releaseDrive(context);
        }
    }

    private boolean isLocked() {
        return joystick == null || joystick.isLocked();
    }

    private void moveStep() {
        if (!moving) {
            return;
        }
        if (!running || isLocked() || snapshot.state() == RunState.PAUSED
                || Math.hypot(moveX, moveY) < 0.04f) {
            stopMoving();
            return;
        }
        float magnitude = Math.min(1f, (float) Math.hypot(moveX, moveY));
        double speedKmh = presets.get(gear) * magnitude;
        // 摇杆向上为北（-y），向右为东（+x）；服务端按方向推进并发布带速度/航向的定位。
        SimulationService.drive(context, currentBase(), -moveY, moveX, speedKmh);
        handler.postDelayed(moveRunnable, DRIVE_INTERVAL_MS);
    }

    /** 摇杆起步的锚点：优先用快照里的当前位置，完全冷态才退回校园锚点。 */
    private GeoPoint currentBase() {
        RunSnapshot current = snapshot;
        if (current.hasPosition()
                && Double.isFinite(current.latitude())
                && Double.isFinite(current.longitude())
                && !(Math.abs(current.latitude()) < 1e-9
                && Math.abs(current.longitude()) < 1e-9)) {
            return new GeoPoint(current.latitude(), current.longitude());
        }
        return new GeoPoint(CampusGeo.ANCHOR_LAT, CampusGeo.ANCHOR_LON);
    }

    // ---------------------------------------------------------------- 拖动

    private void clampOnScreen() {
        if (!running || scaleHost == null || params == null) {
            return;
        }
        clampToScreen();
        try {
            windowManager.updateViewLayout(scaleHost, params);
        } catch (RuntimeException ignored) {
            // 视图已被移除。
        }
    }

    /** 把 x/y 夹在屏幕内，面板（含缩放后的尺寸）永远完整可见。 */
    private void clampToScreen() {
        if (params == null) {
            return;
        }
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        int width = scaleHost != null && scaleHost.getMeasuredWidth() > 0
                ? scaleHost.getMeasuredWidth() : dp(MIDDLE_DP + 12);
        int height = scaleHost != null && scaleHost.getMeasuredHeight() > 0
                ? scaleHost.getMeasuredHeight() : dp(320);
        int maxX = Math.max(0, metrics.widthPixels - width);
        int maxY = Math.max(0, metrics.heightPixels - height);
        params.x = Math.max(0, Math.min(params.x, maxX));
        params.y = Math.max(0, Math.min(params.y, maxY));
    }

    /** 只挂在左侧「移动」按钮上，避免吃掉摇杆和其余按钮的触摸。 */
    private final class DragListener implements View.OnTouchListener {
        private int startX;
        private int startY;
        private float touchX;
        private float touchY;

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            if (params == null) {
                return false;
            }
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startX = params.x;
                    startY = params.y;
                    touchX = event.getRawX();
                    touchY = event.getRawY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    params.x = startX + Math.round(event.getRawX() - touchX);
                    params.y = startY + Math.round(event.getRawY() - touchY);
                    clampToScreen();
                    if (running && scaleHost != null) {
                        try {
                            windowManager.updateViewLayout(scaleHost, params);
                        } catch (RuntimeException ignored) {
                            // 视图已被移除。
                        }
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    prefs(context).edit().putInt(KEY_X, params.x).putInt(KEY_Y, params.y).apply();
                    return true;
                default:
                    return false;
            }
        }
    }

    // ---------------------------------------------------------------- 辅助

    private int normalizeGear(int index) {
        return index < 0 || index > 2 ? 1 : index;
    }

    @StringRes
    private int gearLabelRes(int index) {
        switch (index) {
            case 0:
                return R.string.overlay_gear_walk;
            case 2:
                return R.string.overlay_gear_ride;
            default:
                return R.string.overlay_gear_run;
        }
    }

    private int dp(int value) {
        return Math.round(context.getResources().getDisplayMetrics().density * value);
    }

    private void toast(String text) {
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show();
    }
}
