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
import org.localroute.app.ui.MainActivity;
import org.localroute.core.GeoPoint;
import org.localroute.core.RunState;

/**
 * 跨应用悬浮「摇杆控制台」。
 *
 * <p>布局对齐参考图：顶部一行两个圆形按钮——左「返回主界面」、右「暂停/继续跑圈」，
 * 中间是拖动把手；中间一个大摇杆；底部三个圆形按钮依次是行走 / 跑步 / 骑行。
 *
 * <p>摇杆行为：按住时每 200 毫秒按当前运动速度推进一小步，通过
 * {@link SimulationService#teleportTo(Context, GeoPoint)} 提交新的模拟位置。没有运行任务时
 * 第一下拖动会自动开始「单点保持」；路线跑圈中点摇杆会把任务就地切成单点模式（服务的既有
 * 规则），暂停时摇杆不生效。
 *
 * <p>面板可拖动、位置持久化；没有「显示在其他应用上层」权限时整体不显示，由设置页引导授权。
 */
public final class OverlayPanel {

    /** 用户在设置页打开「悬浮控制台」后写这个键。 */
    public static final String PREF_ENABLED = "overlay_enabled";

    private static final String PREFS = "ui";
    private static final String KEY_X = "overlay_x";
    private static final String KEY_Y = "overlay_y";
    private static final String KEY_GEAR = "overlay_gear";

    /** 摇杆推进节拍：200 ms 一小步，接近模拟服务 500 ms 的定位节奏，又足够跟手。 */
    private static final long MOVE_INTERVAL_MS = 200L;
    private static final double METERS_PER_DEGREE_LAT = 111320d;

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

    private LinearLayout root;
    private JoystickView joystick;
    private ImageView pauseButton;
    private WindowManager.LayoutParams params;

    private RunSnapshot snapshot = RunSnapshot.idle();
    private boolean running;
    private boolean moving;
    private boolean gestureAnnounced;
    private int gear;
    private float moveX;
    private float moveY;
    private GeoPoint lastPoint;

    private final Runnable moveRunnable = this::moveStep;

    public OverlayPanel(Context context) {
        this.context = context.getApplicationContext();
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
                windowManager.addView(root, params);
                running = true;
                root.post(this::clampOnScreen);
            } catch (RuntimeException e) {
                android.util.Log.w("OverlayPanel", "addView 失败: " + e, e);
                // 权限被撤销或窗口被系统拒绝：保持安静，不要因为悬浮窗失败影响主流程。
                root = null;
                running = false;
            }
        }
    }

    public void hide() {
        stopMoving();
        if (joystick != null) {
            joystick.setInputEnabled(false);
        }
        if (!running || root == null) {
            running = false;
            return;
        }
        try {
            windowManager.removeView(root);
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
        root.setPadding(dp(4), dp(4), dp(4), dp(4));
        root.setClipChildren(false);
        root.setClipToPadding(false);

        root.addView(buildTopRow());

        joystick = new JoystickView(context);
        joystick.setContentDescription(context.getString(R.string.overlay_joystick));
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
            }
        });
        LinearLayout.LayoutParams joyParams =
                new LinearLayout.LayoutParams(dp(224), dp(224));
        joyParams.topMargin = dp(12);
        root.addView(joystick, joyParams);

        root.addView(buildGearRow());
        updateGearStyles();

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
        params.setTitle("路线工坊悬浮控制台");
        clampToScreen();
    }

    private LinearLayout buildTopRow() {
        LinearLayout top = new LinearLayout(context);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER);

        ImageView home = circleIcon(R.drawable.ic_overlay_home, 56, 15,
                R.color.overlay_icon, R.string.overlay_home);
        home.setOnClickListener(v -> openMainScreen());
        top.addView(home, squareParams(56, 0, 0, 10));

        top.addView(buildGrip(), squareParams(56, 0, 0, 10));

        pauseButton = circleIcon(R.drawable.ic_overlay_pause, 56, 15,
                R.color.overlay_mint, R.string.overlay_pause_run);
        pauseButton.setOnClickListener(v -> togglePause());
        top.addView(pauseButton, squareParams(56, 0, 0, 0));

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        top.setLayoutParams(params);
        return top;
    }

    private View buildGrip() {
        FrameLayout grip = new FrameLayout(context);
        grip.setContentDescription(context.getString(R.string.overlay_grip));
        ImageView icon = new ImageView(context);
        icon.setImageResource(R.drawable.ic_overlay_grip);
        icon.setImageTintList(ColorStateList.valueOf(
                ContextCompat.getColor(context, R.color.overlay_icon_muted)));
        icon.setAlpha(0.8f);
        FrameLayout.LayoutParams iconParams = new FrameLayout.LayoutParams(
                dp(18), dp(18), Gravity.CENTER);
        grip.addView(icon, iconParams);
        grip.setOnTouchListener(new DragListener());
        return grip;
    }

    private LinearLayout buildGearRow() {
        LinearLayout bottom = new LinearLayout(context);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER);

        int[] icons = {R.drawable.ic_overlay_walk, R.drawable.ic_overlay_run,
                R.drawable.ic_overlay_bike};
        int[] labels = {R.string.overlay_gear_walk, R.string.overlay_gear_run,
                R.string.overlay_gear_ride};
        for (int i = 0; i < gearButtons.length; i++) {
            final int index = i;
            ImageView button = circleIcon(icons[i], 64, 17, R.color.overlay_icon, labels[i]);
            button.setOnClickListener(v -> selectGear(index, true));
            gearButtons[i] = button;
            bottom.addView(button, squareParams(64, 14, i == 0 ? 0 : 10, 0));
        }

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        bottom.setLayoutParams(params);
        return bottom;
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

        boolean paused = state.mode() == OverlayViewState.Mode.PAUSED;
        if (joystick != null) {
            joystick.setAlpha(paused ? 0.55f : 1f);
            joystick.setInputEnabled(!paused);
        }
        if (state.isIdle() || state.mode() == OverlayViewState.Mode.TERMINAL) {
            // 没有可推进的任务：清零起点，下一次拖动重新从当前位置/校园锚点开始。
            lastPoint = null;
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

    private void selectGear(int index, boolean announce) {
        gear = normalizeGear(index);
        prefs(context).edit().putInt(KEY_GEAR, gear).apply();
        updateGearStyles();
        if (!announce) {
            return;
        }
        double kmh = presets.get(gear);
        // 路线跑圈中立即改速；单点保持会忽略这条命令，速度由摇杆步进决定。
        SimulationService.setSpeedKmh(context, kmh);
        toast(context.getString(R.string.overlay_gear_toast,
                context.getString(gearLabelRes(gear)), SpeedPresets.format(kmh)));
    }

    // ---------------------------------------------------------------- 摇杆推进

    private void startMoving() {
        if (moving || !running) {
            return;
        }
        moving = true;
        handler.post(moveRunnable);
    }

    private void stopMoving() {
        moving = false;
        handler.removeCallbacks(moveRunnable);
    }

    private void moveStep() {
        if (!moving) {
            return;
        }
        if (!running || Math.hypot(moveX, moveY) < 0.04f) {
            moving = false;
            return;
        }
        if (snapshot.state() != RunState.PAUSED) {
            advance();
        }
        if (moving) {
            handler.postDelayed(moveRunnable, MOVE_INTERVAL_MS);
        }
    }

    /** 按摇杆方向与当前运动速度推进一帧，并提交为新的单点位置。 */
    private void advance() {
        float vx = moveX;
        float vy = moveY;
        float magnitude = (float) Math.hypot(vx, vy);
        if (magnitude < 1e-4f) {
            return;
        }
        GeoPoint base = currentBase();
        double speedKmh = presets.get(gear);
        double stepMeters = speedKmh / 3.6d * (MOVE_INTERVAL_MS / 1000d)
                * Math.min(1d, magnitude);
        double north = -vy / magnitude * stepMeters;
        double east = vx / magnitude * stepMeters;
        double latitude = base.latitude() + north / METERS_PER_DEGREE_LAT;
        double longitude = base.longitude() + east
                / (METERS_PER_DEGREE_LAT * Math.cos(Math.toRadians(base.latitude())));
        GeoPoint next = new GeoPoint(latitude, longitude);
        lastPoint = next;
        // 冷态时 teleportTo 会走前台服务启动路径，自动开始单点保持。
        SimulationService.teleportTo(context, next);
    }

    private GeoPoint currentBase() {
        if (lastPoint != null) {
            return lastPoint;
        }
        RunSnapshot current = snapshot;
        if (current.hasPosition()
                && Double.isFinite(current.latitude())
                && Double.isFinite(current.longitude())
                && !(Math.abs(current.latitude()) < 1e-9
                && Math.abs(current.longitude()) < 1e-9)) {
            return new GeoPoint(current.latitude(), current.longitude());
        }
        // 完全没有位置时从校园演示锚点起步，拖出第一帧后再按步进推进。
        return new GeoPoint(CampusGeo.ANCHOR_LAT, CampusGeo.ANCHOR_LON);
    }

    // ---------------------------------------------------------------- 拖动

    private void clampOnScreen() {
        if (!running || root == null || params == null) {
            return;
        }
        clampToScreen();
        try {
            windowManager.updateViewLayout(root, params);
        } catch (RuntimeException ignored) {
            // 视图已被移除。
        }
    }

    /** 把 x/y 夹在屏幕内，面板永远完整可见。 */
    private void clampToScreen() {
        if (params == null) {
            return;
        }
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        int width = root != null && root.getWidth() > 0 ? root.getWidth() : dp(232);
        int height = root != null && root.getHeight() > 0 ? root.getHeight() : dp(376);
        int maxX = Math.max(0, metrics.widthPixels - width);
        int maxY = Math.max(0, metrics.heightPixels - height);
        params.x = Math.max(0, Math.min(params.x, maxX));
        params.y = Math.max(0, Math.min(params.y, maxY));
    }

    /** 只挂在顶部中间的拖动把手上，避免吃掉摇杆和按钮的触摸。 */
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
                    if (running) {
                        try {
                            windowManager.updateViewLayout(root, params);
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

    private LinearLayout.LayoutParams squareParams(int sizeDp, int topDp, int startDp, int endDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp));
        params.topMargin = dp(topDp);
        params.setMarginStart(dp(startDp));
        params.setMarginEnd(dp(endDp));
        return params;
    }

    private int dp(int value) {
        return Math.round(context.getResources().getDisplayMetrics().density * value);
    }

    private void toast(String text) {
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show();
    }
}
