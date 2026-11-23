package org.localroute.app.settings;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Criteria;
import android.location.LocationManager;
import android.os.Build;

import androidx.core.content.ContextCompat;

import org.localroute.app.campus.PresetUnlock;
import org.localroute.app.diagnostics.DiagnosticLog;
import org.localroute.app.overlay.OverlayPanel;

import java.util.ArrayList;
import java.util.List;

/**
 * 运行前的环境体检（任务书第 10 步「设置检查页」）。
 *
 * <p>只做只读检查与“探测”，不修改系统设置；模拟位置权限的探测会短暂创建并立即移除
 * 一个 {@code gps} 测试 provider——这是判断“本应用是否被选为模拟位置信息应用”最可靠的方式，
 * 因此只在用户主动点击时执行。
 *
 * <p>检查结果按 {@link SetupItem} 的顺序给出，供 {@link SetupGuide} 读成「下一步做什么」。
 * 探测成功/失败会写入应用自己的偏好（{@link #KEY_MOCK_ALLOWED_AT}）：系统不提供“读取当前模拟位置应用”
 * 的接口，只能靠最近一次探测结果在 {@link #MOCK_TRUST_WINDOW_MS} 内代替，超窗后该项重新变成待确认。
 */
public final class SettingsInspector {

    public enum Status {
        OK, WARN, FAIL
    }

    /** 一项检查结果。 */
    public static final class Check {
        private final SetupItem item;
        private final String title;
        private final Status status;
        private final String detail;

        Check(SetupItem item, String title, Status status, String detail) {
            this.item = item;
            this.title = title;
            this.status = status;
            this.detail = detail;
        }

        public SetupItem item() {
            return item;
        }

        public String title() {
            return title;
        }

        public Status status() {
            return status;
        }

        public String detail() {
            return detail;
        }
    }

    public enum MockProbeResult {
        ALLOWED, ALLOWED_AFTER_CLEANUP, DENIED, PROVIDER_UNSUPPORTED, MANAGER_UNAVAILABLE
    }

    /** 系统确实允许本应用模拟位置的时刻（毫秒）；0 表示从未确认。 */
    public static final String KEY_MOCK_ALLOWED_AT = "mock_allowed_at";
    private static final long DAY_MS = 24L * 60 * 60 * 1000;
    /** 超过这个窗口就不再假定模拟位置应用还选中着，引导清单会重新要求确认。 */
    public static final long MOCK_TRUST_WINDOW_MS = 14L * DAY_MS;

    private SettingsInspector() {
    }

    /**
     * 按 {@link SetupItem} 的推荐顺序返回检查结果：阻塞项在前，可选项在后，
     * 这样引导页与主页卡片取「第一项未就绪」就是用户当下最该处理的事。
     */
    public static List<Check> inspect(Context context) {
        List<Check> checks = new ArrayList<>();

        boolean fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        checks.add(new Check(SetupItem.LOCATION_PERMISSION, "定位权限（精确位置）", fine ? Status.OK : Status.FAIL,
                fine ? "已授权" : "未授权：开始运行前必须授予“精确位置”，否则无法创建模拟位置"));

        LocationManager manager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (manager == null) {
            checks.add(new Check(SetupItem.LOCATION_SWITCH, "系统定位开关", Status.FAIL, "LocationManager 不可用"));
        } else {
            boolean enabled;
            try {
                enabled = manager.isLocationEnabled();
            } catch (RuntimeException e) {
                enabled = false;
            }
            checks.add(new Check(SetupItem.LOCATION_SWITCH, "系统定位开关", enabled ? Status.OK : Status.FAIL,
                    enabled ? "已开启" : "已关闭：请在系统设置里打开定位开关"));
        }

        checks.add(mockAppCheck(context));

        boolean overlay = OverlayPanel.canDrawOverlays(context);
        checks.add(new Check(SetupItem.OVERLAY, "悬浮窗权限（显示在其他应用上层）", overlay ? Status.OK : Status.WARN,
                overlay ? "已授权：运行中切到其他应用仍可暂停/继续/结束"
                        : "未授权：切到其他应用后没有悬浮控制台；只影响悬浮窗，不影响跑圈本身。"));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            boolean notifications = ContextCompat.checkSelfPermission(context,
                    Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
            checks.add(new Check(SetupItem.NOTIFICATIONS, "通知权限", notifications ? Status.OK : Status.WARN,
                    notifications ? "已授权" : "未授权：前台服务仍会运行，但通知栏看不到暂停/停止按钮"));
        } else {
            checks.add(new Check(SetupItem.NOTIFICATIONS, "通知权限", Status.OK, "当前系统版本无需单独授权"));
        }

        return checks;
    }

    private static Check mockAppCheck(Context context) {
        long recordedAt = mockAllowedAt(context);
        long now = System.currentTimeMillis();
        boolean trusted = withinTrustWindow(recordedAt, now, MOCK_TRUST_WINDOW_MS);
        return new Check(SetupItem.MOCK_APP, "开发者选项中的模拟位置应用", trusted ? Status.OK : Status.WARN,
                trusted ? "最近一次确认（" + Math.max(1, (now - recordedAt) / DAY_MS) + " 天前）系统允许本应用模拟位置。"
                        + "若之后失效，点「检测模拟位置权限」重新确认。"
                        : "需要在「开发者选项 → 选择模拟位置信息应用」里选中本应用；点「检测模拟位置权限」确认。");
    }

    /** 探测结果是否在可信窗口内（未来时间视为不可信，说明设备时钟被改过）。 */
    public static boolean withinTrustWindow(long recordedAtMs, long nowMs, long windowMs) {
        return recordedAtMs > 0 && nowMs >= recordedAtMs && nowMs - recordedAtMs <= windowMs;
    }

    /** 已确认允许模拟位置的时间戳，0 表示从未确认。 */
    public static long mockAllowedAt(Context context) {
        return prefs(context).getLong(KEY_MOCK_ALLOWED_AT, 0L);
    }

    public static void rememberMockAllowed(Context context) {
        prefs(context).edit().putLong(KEY_MOCK_ALLOWED_AT, System.currentTimeMillis()).apply();
    }

    public static void forgetMockAllowed(Context context) {
        prefs(context).edit().remove(KEY_MOCK_ALLOWED_AT).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PresetUnlock.PREFS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * 主动探测：尝试创建并立刻移除 {@code gps} 测试 provider。
     *
     * <p>调用前应确认当前没有进行中的运行，避免打断自己的会话。
     */
    // 与 AndroidMockProviderApi 同理：旧重载吃 Criteria 常量，lint 会用 ProviderProperties 误判。
    @SuppressLint("WrongConstant")
    public static MockProbeResult probeMockProvider(Context context) {
        LocationManager manager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (manager == null) {
            return MockProbeResult.MANAGER_UNAVAILABLE;
        }
        try {
            if (!manager.getAllProviders().contains(LocationManager.GPS_PROVIDER)) {
                return MockProbeResult.PROVIDER_UNSUPPORTED;
            }
            // 上次异常退出（崩溃/OOM/被划掉）可能在 system_server 里留下同名测试 provider，
            // 此时 addTestProvider 只会抛 "already exists"。先尝试清掉残留，再正常探测。
            boolean clearedStale = false;
            try {
                manager.removeTestProvider(LocationManager.GPS_PROVIDER);
                clearedStale = true;
                DiagnosticLog.warn("检测到上次残留的测试 provider，已清理");
            } catch (SecurityException e) {
                forgetMockAllowed(context);
                DiagnosticLog.warn("模拟位置权限探测：被系统拒绝（未选中模拟位置应用）");
                return MockProbeResult.DENIED;
            } catch (RuntimeException ignored) {
                // 没有残留时 removeTestProvider 会抛 IllegalArgumentException，属正常情况。
            }
            manager.addTestProvider(LocationManager.GPS_PROVIDER,
                    false, false, false, false, true, true, true,
                    Criteria.POWER_LOW, Criteria.ACCURACY_FINE);
            try {
                manager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true);
            } finally {
                manager.removeTestProvider(LocationManager.GPS_PROVIDER);
            }
            rememberMockAllowed(context);
            DiagnosticLog.info("模拟位置权限探测：允许" + (clearedStale ? "（已清理残留 provider）" : ""));
            return clearedStale ? MockProbeResult.ALLOWED_AFTER_CLEANUP : MockProbeResult.ALLOWED;
        } catch (SecurityException e) {
            forgetMockAllowed(context);
            DiagnosticLog.warn("模拟位置权限探测：被系统拒绝（未选中模拟位置应用）");
            return MockProbeResult.DENIED;
        } catch (RuntimeException e) {
            DiagnosticLog.warn("模拟位置权限探测失败：" + e);
            return MockProbeResult.PROVIDER_UNSUPPORTED;
        }
    }
}
