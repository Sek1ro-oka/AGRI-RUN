package org.localroute.app.ui;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.MenuItem;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import org.localroute.app.R;
import org.localroute.app.databinding.ActivitySettingsCheckBinding;
import org.localroute.app.diagnostics.DiagnosticLog;
import org.localroute.app.run.SimulationStateRepo;
import org.localroute.app.settings.SettingsInspector;

import java.util.List;

/**
 * 设置检查页：只做只读体检与用户主动触发的权限探测，不修改任何系统设置。
 */
public class SettingsCheckActivity extends AppCompatActivity {

    private ActivitySettingsCheckBinding binding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivitySettingsCheckBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        setSupportActionBar(binding.toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        binding.buttonRecheck.setOnClickListener(v -> renderChecks());
        binding.buttonProbe.setOnClickListener(v -> runProbe());
        binding.buttonDevOptions.setOnClickListener(v -> openDeveloperOptions());
        binding.buttonAppDetails.setOnClickListener(v -> openAppDetails());

        renderChecks();
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void renderChecks() {
        binding.checkContainer.removeAllViews();
        TextView intro = new TextView(this);
        intro.setText("按下面顺序准备环境。任一项是「失败」时，模拟位置不会被系统接受。");
        intro.setTextSize(12f);
        intro.setAlpha(0.8f);
        intro.setPadding(0, 0, 0, dp(12));
        binding.checkContainer.addView(intro);

        List<SettingsInspector.Check> checks = SettingsInspector.inspect(this);
        for (SettingsInspector.Check check : checks) {
            binding.checkContainer.addView(row(check));
        }

        TextView footer = new TextView(this);
        footer.setText(R.string.settings_probe_hint);
        footer.setTextSize(12f);
        footer.setAlpha(0.8f);
        footer.setPadding(0, dp(16), 0, 0);
        binding.checkContainer.addView(footer);
    }

    private LinearLayout row(SettingsInspector.Check check) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(12);
        row.setLayoutParams(params);

        TextView dot = new TextView(this);
        dot.setText("●");
        dot.setTextSize(14f);
        dot.setTextColor(ContextCompat.getColor(this, colorFor(check.status())));
        dot.setPadding(0, 0, dp(10), 0);
        row.addView(dot);

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView title = new TextView(this);
        title.setText(check.title() + "  " + statusLabel(check.status()));
        title.setTextSize(14f);
        title.setTextColor(ContextCompat.getColor(this, colorFor(check.status())));
        text.addView(title);

        TextView detail = new TextView(this);
        detail.setText(check.detail());
        detail.setTextSize(12f);
        detail.setAlpha(0.85f);
        detail.setPadding(0, dp(2), 0, 0);
        text.addView(detail);

        row.addView(text);
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

    private int colorFor(SettingsInspector.Status status) {
        switch (status) {
            case OK:
                return R.color.state_running;
            case FAIL:
                return R.color.state_error;
            case WARN:
            default:
                return R.color.state_paused;
        }
    }

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
                message = "请到开发者选项 →「选择模拟位置信息应用」里选中「路线工坊」，本应用需要出现在该列表中。";
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
