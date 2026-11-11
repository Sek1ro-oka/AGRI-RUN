package org.localroute.app.ui;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import org.localroute.app.R;
import org.localroute.app.databinding.ActivitySettingsCheckBinding;
import org.localroute.app.diagnostics.DiagnosticLog;
import org.localroute.app.overlay.OverlayHost;
import org.localroute.app.overlay.OverlayPanel;
import org.localroute.app.run.SimulationStateRepo;
import org.localroute.app.settings.SettingsInspector;

import java.util.List;

/**
 * 设置页：主页同款白底 + 薄荷绿卡片。
 *
 * <p>上半部分是只读环境体检（与用户主动触发的权限探测），不修改任何系统设置；
 * 下半部分管理跨应用悬浮摇杆：需要系统「显示在其他应用上层」权限，授权后开关才真正生效。
 */
public class SettingsCheckActivity extends AppCompatActivity {

    private ActivitySettingsCheckBinding binding;
    /** 程序化勾选开关时避免再次触发监听。 */
    private boolean overlaySwitchGuard;
    /** 用户想打开悬浮窗但还在等系统授权页返回。 */
    private boolean pendingOverlayEnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        binding = ActivitySettingsCheckBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        ViewCompat.setOnApplyWindowInsetsListener(binding.getRoot(), (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        setSupportActionBar(binding.toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        binding.buttonRecheck.setOnClickListener(v -> renderChecks());
        binding.buttonProbe.setOnClickListener(v -> runProbe());
        binding.buttonDevOptions.setOnClickListener(v -> openDeveloperOptions());
        binding.buttonAppDetails.setOnClickListener(v -> openAppDetails());

        binding.switchOverlay.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (overlaySwitchGuard) {
                return;
            }
            if (isChecked) {
                requestEnableOverlay();
            } else {
                pendingOverlayEnable = false;
                OverlayHost.get(this).setEnabled(false);
                toast(getString(R.string.settings_overlay_disable_toast));
                refreshOverlaySection();
            }
        });
        binding.buttonOverlayPermission.setOnClickListener(v -> {
            if (OverlayPanel.canDrawOverlays(this)) {
                pendingOverlayEnable = true;
                enableOverlayNow();
            } else {
                pendingOverlayEnable = true;
                OverlayPanel.requestPermission(this);
            }
        });

        renderChecks();
        refreshOverlaySection();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (pendingOverlayEnable) {
            if (OverlayPanel.canDrawOverlays(this)) {
                enableOverlayNow();
            } else {
                pendingOverlayEnable = false;
                toast(getString(R.string.settings_overlay_denied_toast));
            }
        }
        refreshOverlaySection();
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // ---------------------------------------------------------------- 悬浮控制台

    private void requestEnableOverlay() {
        if (!OverlayPanel.canDrawOverlays(this)) {
            pendingOverlayEnable = true;
            setOverlaySwitch(false);
            OverlayPanel.requestPermission(this);
            toast(getString(R.string.settings_overlay_need_permission_toast));
            return;
        }
        OverlayHost.get(this).setEnabled(true);
        toast(getString(R.string.settings_overlay_enable_toast));
        refreshOverlaySection();
    }

    private void enableOverlayNow() {
        pendingOverlayEnable = false;
        OverlayHost.get(this).setEnabled(true);
        toast(getString(R.string.settings_overlay_enable_toast));
        refreshOverlaySection();
    }

    private void refreshOverlaySection() {
        boolean enabled = OverlayHost.get(this).isEnabled();
        boolean allowed = OverlayPanel.canDrawOverlays(this);
        setOverlaySwitch(enabled);
        int statusRes;
        if (!enabled) {
            statusRes = R.string.settings_overlay_status_off;
        } else if (allowed) {
            statusRes = R.string.settings_overlay_status_on;
        } else {
            statusRes = R.string.settings_overlay_status_on_missing;
        }
        binding.textOverlayStatus.setText(statusRes);
        binding.buttonOverlayPermission.setVisibility(allowed ? View.GONE : View.VISIBLE);
        if (enabled) {
            // 权限可能刚在系统页里授予：让面板按当前开关重新渲染一次。
            OverlayHost.get(this).refresh();
        }
    }

    private void setOverlaySwitch(boolean checked) {
        overlaySwitchGuard = true;
        binding.switchOverlay.setChecked(checked);
        overlaySwitchGuard = false;
    }

    // ---------------------------------------------------------------- 环境检查

    private void renderChecks() {
        binding.checkContainer.removeAllViews();
        List<SettingsInspector.Check> checks = SettingsInspector.inspect(this);
        for (int i = 0; i < checks.size(); i++) {
            binding.checkContainer.addView(row(checks.get(i)));
            if (i < checks.size() - 1) {
                View divider = new View(this);
                divider.setBackgroundColor(Color.parseColor("#EFF5F2"));
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
                binding.checkContainer.addView(divider, params);
            }
        }
    }

    private LinearLayout row(SettingsInspector.Check check) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(9), 0, dp(9));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText(check.title());
        title.setTextSize(13f);
        title.setTextColor(ContextCompat.getColor(this, R.color.campus_ink));
        title.setTypeface(Typeface.DEFAULT_BOLD);
        head.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView chip = new TextView(this);
        chip.setText(statusLabel(check.status()));
        chip.setTextSize(11f);
        chip.setTextColor(Color.parseColor(textColorFor(check.status())));
        chip.setPadding(dp(8), dp(2), dp(8), dp(3));
        GradientDrawable chipBackground = new GradientDrawable();
        chipBackground.setCornerRadius(dp(8));
        chipBackground.setColor(Color.parseColor(backgroundFor(check.status())));
        chip.setBackground(chipBackground);
        head.addView(chip);
        row.addView(head);

        TextView detail = new TextView(this);
        detail.setText(check.detail());
        detail.setTextSize(11f);
        detail.setTextColor(ContextCompat.getColor(this, R.color.campus_muted));
        detail.setLineSpacing(dp(1), 1f);
        detail.setPadding(0, dp(3), 0, 0);
        row.addView(detail);
        return row;
    }

    private String statusLabel(SettingsInspector.Status status) {
        switch (status) {
            case OK:
                return "正常";
            case WARN:
                return "需确认";
            case FAIL:
                return "失败";
            default:
                return status.name();
        }
    }

    private String textColorFor(SettingsInspector.Status status) {
        switch (status) {
            case OK:
                return "#1B7A52";
            case FAIL:
                return "#B3261E";
            case WARN:
            default:
                return "#8D6E00";
        }
    }

    private String backgroundFor(SettingsInspector.Status status) {
        switch (status) {
            case OK:
                return "#E7F7EF";
            case FAIL:
                return "#FCE9E7";
            case WARN:
            default:
                return "#FFF6E0";
        }
    }

    // ---------------------------------------------------------------- 探测与跳转

    private void runProbe() {
        if (SimulationStateRepo.get().current().isActive()) {
            toast("正在运行模拟位置，请先停止再检测");
            return;
        }
        SettingsInspector.MockProbeResult result = SettingsInspector.probeMockProvider(this);
        String message;
        String title;
        switch (result) {
            case ALLOWED:
                title = "系统允许模拟位置";
                message = "可以开始运行。若第三方应用仍收不到位置，请确认它自己使用了系统定位，且没有被系统限电策略冻结。";
                break;
            case ALLOWED_AFTER_CLEANUP:
                title = "系统允许模拟位置（已清理残留）";
                message = "上次运行可能没有正常结束，检测到残留的测试 provider，已自动清理。可以开始运行；"
                        + "如果反复出现，请在运行中用通知里的「停止」结束，或重启手机。";
                break;
            case DENIED:
                title = "系统拒绝了模拟位置";
                message = "请到开发者选项 →「选择模拟位置信息应用」里选中「利群工坊」，本应用需要出现在该列表中。";
                break;
            case PROVIDER_UNSUPPORTED:
                title = "设备不支持测试 provider";
                message = "当前设备的定位服务拒绝了测试 provider，无法伪造位置。";
                break;
            case MANAGER_UNAVAILABLE:
            default:
                title = "定位服务不可用";
                message = "未获取到 LocationManager，请重启应用或检查系统定位服务。";
                break;
        }
        DiagnosticLog.info("设置检查页探测结果：" + result);
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("去开发者选项", (d, w) -> openDeveloperOptions())
                .setNegativeButton("关闭", null)
                .show();
    }

    private void openDeveloperOptions() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
        } catch (ActivityNotFoundException e) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    private void openAppDetails() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", getPackageName(), null));
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
