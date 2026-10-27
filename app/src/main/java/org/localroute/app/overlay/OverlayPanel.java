package org.localroute.app.overlay;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.localroute.app.R;
import org.localroute.app.run.RunSnapshot;
import org.localroute.app.service.SimulationService;

/**
 * 跨应用悬浮控制条：在别的 App 里也能看到状态、暂停/继续、停止。
 *
 * <p>审查 C 的诉求是「运行中切到导航/计步类 App 后，还能一键停或暂停」。这里只做
 * 最少的三件事：状态一行字 + 暂停/继续 + 停止；传送仍然在应用内点地图完成，避免
 * 悬浮窗里再堆一套选点交互。
 *
 * <p>没有「在其他应用上层显示」权限时什么也不做，由设置页引导去授权。
 */
public final class OverlayPanel {

    /** 用户在设置页勾选「显示悬浮控制条」后写这个键。 */
    public static final String PREF_ENABLED = "overlay_enabled";

    /** 与 MainActivity 共用的偏好文件名（Prefs 里存三个快捷传送槽位）。 */
    private static final String PREFS = "ui";
    private static final String[] SLOT_KEYS = {"slot_a", "slot_b", "slot_c"};
    private static final int[] SLOT_LABELS = {R.string.overlay_slot_a, R.string.overlay_slot_b,
            R.string.overlay_slot_c};

    private static android.content.SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 读某个槽位；未设置或写法损坏都返回 null，调用方据此提示。 */
    public static org.localroute.core.GeoPoint readSlot(Context context, int index) {
        String raw = prefs(context).getString(SLOT_KEYS[index], "");
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        int comma = raw.indexOf(',');
        if (comma <= 0) {
            return null;
        }
        try {
            return new org.localroute.core.GeoPoint(
                    Double.parseDouble(raw.substring(0, comma).trim()),
                    Double.parseDouble(raw.substring(comma + 1).trim()));
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static void writeSlot(Context context, int index, org.localroute.core.GeoPoint point) {
        prefs(context).edit()
                .putString(SLOT_KEYS[index], String.format(java.util.Locale.US, "%.6f,%.6f",
                        point.latitude(), point.longitude()))
                .apply();
    }

    public static void clearSlot(Context context, int index) {
        prefs(context).edit().remove(SLOT_KEYS[index]).apply();
    }

    private final Context context;
    private final WindowManager windowManager;
    private LinearLayout root;
    private TextView statusView;
    private TextView actionView;
    private WindowManager.LayoutParams params;
    private boolean running;

    public OverlayPanel(Context context) {
        this.context = context.getApplicationContext();
        this.windowManager =
                (WindowManager) this.context.getSystemService(Context.WINDOW_SERVICE);
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

    public boolean isShowing() {
        return running;
    }

    /** 按快照决定显示、刷新或收起。运行态变化时调用即可。 */
    public void sync(RunSnapshot snapshot, boolean enabled) {
        boolean allowed = canDrawOverlays(context);
        android.util.Log.d("OverlayPanel", "sync enabled=" + enabled + " allowed=" + allowed
                + " active=" + snapshot.isActive() + " state=" + snapshot.state() + " showing=" + running);
        boolean shouldShow = enabled && allowed && snapshot.isActive();
        if (!shouldShow) {
            hide();
            return;
        }
        if (root == null) {
            inflate();
        }
        update(snapshot);
        if (!running) {
            try {
                windowManager.addView(root, params);
                running = true;
            } catch (RuntimeException e) {
                android.util.Log.w("OverlayPanel", "addView 失败: " + e, e);
                // 权限被撤销或窗口被系统拒绝：保持安静，不要因为悬浮窗失败影响主流程。
                root = null;
                running = false;
            }
        }
    }

    public void hide() {
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

    private void inflate() {
        root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(10), dp(8), dp(10), dp(8));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.parseColor("#EE1B5E20"));
        background.setCornerRadius(dp(12));
        root.setBackground(background);
        root.setElevation(dp(6));

        statusView = new TextView(context);
        statusView.setTextColor(Color.WHITE);
        statusView.setTextSize(12f);
        root.addView(statusView, matchWrap());

        LinearLayout buttons = new LinearLayout(context);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(buttons, matchWrap());

        actionView = makeButton(buttons, R.string.overlay_pause);
        actionView.setOnClickListener(v -> {
            String label = actionView.getText().toString();
            if (label.equals(context.getString(R.string.overlay_pause))) {
                SimulationService.pause(context);
            } else {
                SimulationService.resume(context);
            }
        });
        TextView stop = makeButton(buttons, R.string.overlay_stop);
        stop.setOnClickListener(v -> SimulationService.stop(context));

        // 快捷传送：A / B / C 三个槽位（跨应用点一下就把当前位置挪过去，速度 0、复用同一个定位会话）。
        LinearLayout slots = new LinearLayout(context);
        slots.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(slots, matchWrap());
        for (int i = 0; i < SLOT_KEYS.length; i++) {
            final int index = i;
            TextView slot = makeButton(slots, SLOT_LABELS[i]);
            slot.setOnClickListener(v -> {
                org.localroute.core.GeoPoint point = readSlot(context, index);
                if (point == null) {
                    android.widget.Toast.makeText(context,
                            context.getString(R.string.overlay_slot_empty,
                                    String.valueOf((char) ('A' + index))),
                            android.widget.Toast.LENGTH_LONG).show();
                    return;
                }
                SimulationService.teleportTo(context, point);
            });
        }

        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                // 不抢焦点：这样底下的 App 仍然能正常输入。
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = dp(12);
        params.y = dp(96);
        root.setOnTouchListener(new DragListener());
    }

    private TextView makeButton(LinearLayout parent, int textRes) {
        TextView button = new TextView(context);
        button.setText(textRes);
        button.setTextColor(Color.WHITE);
        button.setTextSize(13f);
        button.setPadding(dp(10), dp(6), dp(10), dp(6));
        GradientDrawable stroke = new GradientDrawable();
        stroke.setColor(Color.parseColor("#33FFFFFF"));
        stroke.setCornerRadius(dp(8));
        button.setBackground(stroke);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(6), dp(8), 0);
        parent.addView(button, lp);
        return button;
    }

    private void update(RunSnapshot snapshot) {
        if (statusView == null || actionView == null) {
            return;
        }
        String state = stateText(snapshot);
        statusView.setText(context.getString(R.string.overlay_status,
                state,
                String.format(java.util.Locale.US, "%.1f", snapshot.speedKmh()),
                String.format(java.util.Locale.US, "%.0f", snapshot.distanceMeters())));
        actionView.setText(snapshot.state() == org.localroute.core.RunState.PAUSED
                ? R.string.overlay_resume
                : R.string.overlay_pause);
    }

    private String stateText(RunSnapshot snapshot) {
        switch (snapshot.state()) {
            case RUNNING:
                return context.getString(R.string.overlay_state_running);
            case PAUSED:
                return context.getString(R.string.overlay_state_paused);
            case STARTING:
                return context.getString(R.string.overlay_state_starting);
            default:
                return snapshot.state().name();
        }
    }

    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(context.getResources().getDisplayMetrics().density * value);
    }

    /** 拖动移动位置；松手后停在原处，不做吸边（够用且少一层状态）。 */
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
                    if (running) {
                        try {
                            windowManager.updateViewLayout(root, params);
                        } catch (RuntimeException ignored) {
                            // 视图已被移除。
                        }
                    }
                    return true;
                default:
                    return false;
            }
        }
    }
}
