package org.localroute.app.ui;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MenuItem;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import org.localroute.app.R;
import org.localroute.app.databinding.ActivityDiagnosticsBinding;
import org.localroute.app.simulation.GmsFlpBackend;
import org.localroute.app.diagnostics.DiagnosticLog;
import org.localroute.app.diagnostics.SystemLocationReader;
import org.localroute.app.model.RouteSummary;
import org.localroute.app.run.RunSnapshot;
import org.localroute.app.run.SimulationStateRepo;
import org.localroute.app.storage.RouteRepository;
import org.localroute.core.GeoPoint;
import org.localroute.simulation.LocationVerifier;

import java.io.File;
import java.util.List;
import java.util.Locale;

/**
 * 诊断信息页：包名/版本/API、运行状态、provider 支持矩阵、路线库与最近日志。
 *
 * <p>坐标日志默认关闭，只有在这里显式打开才会记录位置细节。
 */
public class DiagnosticsActivity extends AppCompatActivity {

    private ActivityDiagnosticsBinding binding;
    private RouteRepository repository;

    /** 自检每 3 秒刷新一次：运行中看得到「系统读回的位置」跟着动，才说明模拟位置真的在生效。 */
    private final Handler selfCheckHandler = new Handler(Looper.getMainLooper());
    private final Runnable selfCheckTick = new Runnable() {
        @Override
        public void run() {
            updateSelfCheck();
            selfCheckHandler.postDelayed(this, 3_000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityDiagnosticsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        setSupportActionBar(binding.toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        repository = new RouteRepository(new File(getFilesDir(), "routes"));

        binding.switchGmsFlp.setChecked(getSharedPreferences("ui", MODE_PRIVATE).getBoolean("gms_flp_enabled", false));
        binding.switchGmsFlp.setOnCheckedChangeListener((button, checked) -> {
            getSharedPreferences("ui", MODE_PRIVATE).edit().putBoolean("gms_flp_enabled", checked).apply();
            DiagnosticLog.info("GMS FLP 后端开关：" + (checked ? "开启（下次运行生效）" : "关闭"));
            refreshGmsStatus();
        });
        binding.switchCoordinates.setChecked(DiagnosticLog.isCoordinateLoggingEnabled());
        binding.switchCoordinates.setOnCheckedChangeListener((button, checked) -> {
            DiagnosticLog.setCoordinateLoggingEnabled(this, checked);
            refreshReport();
        });
        binding.buttonCopy.setOnClickListener(v -> copyReport());
        binding.buttonClearLog.setOnClickListener(v -> {
            DiagnosticLog.clearRecent();
            refreshReport();
            toast("日志已清空");
        });

        refreshReport();
        refreshGmsStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshReport();
        selfCheckHandler.removeCallbacks(selfCheckTick);
        selfCheckHandler.post(selfCheckTick);
    }

    @Override
    protected void onPause() {
        selfCheckHandler.removeCallbacks(selfCheckTick);
        super.onPause();
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /** 记录 GMS FLP 后端的开关与可用性：第三方客户端是否走 FLP，靠这里先判断。 */
    private void refreshGmsStatus() {
        boolean enabled = getSharedPreferences("ui", MODE_PRIVATE)
                .getBoolean("gms_flp_enabled", false);
        boolean available = GmsFlpBackend.isPlayServicesAvailable(this);
        binding.textGmsFlp.setText(getString(R.string.gms_flp_status,
                enabled ? "已开启" : "已关闭",
                available ? "可用" : "不可用",
                GmsFlpBackend.unavailableReason(this)));
    }
    private void refreshReport() {
        StringBuilder builder = new StringBuilder();

        builder.append("【应用】\n");
        builder.append("包名：").append(getPackageName()).append('\n');
        builder.append("版本：").append(versionText()).append('\n');
        builder.append("坐标日志：").append(DiagnosticLog.isCoordinateLoggingEnabled() ? "开启" : "关闭（默认）").append('\n');

        builder.append("\n【系统】\n");
        builder.append("设备：").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        builder.append("Android：").append(Build.VERSION.RELEASE)
                .append("（API ").append(Build.VERSION.SDK_INT).append("）\n");
        builder.append("构建指纹：").append(Build.FINGERPRINT).append('\n');

        builder.append("\n【权限】\n");
        builder.append("精确位置：").append(permissionText(Manifest.permission.ACCESS_FINE_LOCATION)).append('\n');
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            builder.append("通知：").append(permissionText(Manifest.permission.POST_NOTIFICATIONS)).append('\n');
        }

        builder.append("\n【定位服务】\n");
        LocationManager manager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (manager == null) {
            builder.append("LocationManager 不可用\n");
        } else {
            builder.append("系统定位开关：").append(booleanText(safeLocationEnabled(manager))).append('\n');
            builder.append("provider 支持：gps=").append(booleanText(manager.getProvider(LocationManager.GPS_PROVIDER) != null))
                    .append(" / network=").append(booleanText(manager.getProvider(LocationManager.NETWORK_PROVIDER) != null))
                    .append(" / fused=").append(booleanText(manager.getProvider("fused") != null)).append('\n');
            builder.append("提示：模拟位置权限需在「设置检查」页手动探测\n");
        }

        builder.append("\n【运行状态】\n");
        RunSnapshot snapshot = SimulationStateRepo.get().current();
        builder.append("状态：").append(snapshot.state().name()).append('\n');
        builder.append("runId：").append(snapshot.runId().isEmpty() ? "—" : snapshot.runId()).append('\n');
        builder.append("位置：").append(snapshot.positionText()).append('\n');
        builder.append("单圈/累计：").append(snapshot.lapLengthText()).append(" / ").append(snapshot.distanceText()).append('\n');
        builder.append("圈数：").append(snapshot.loopsText()).append('\n');
        builder.append("有效用时：").append(snapshot.elapsedText()).append('\n');
        builder.append("已提交给：").append(snapshot.providersText()).append('\n');
        if (snapshot.errorCode() != null) {
            builder.append("错误：").append(snapshot.errorCode().name())
                    .append(" · ").append(snapshot.errorMessage()).append('\n');
        }

        builder.append("\n【路线库】\n");
        builder.append("目录：").append(repository.routesDir().getAbsolutePath()).append('\n');
        List<RouteSummary> summaries;
        try {
            summaries = repository.list();
        } catch (RuntimeException e) {
            summaries = java.util.Collections.emptyList();
        }
        builder.append("已保存：").append(summaries.size()).append(" 条\n");
        int limit = Math.min(5, summaries.size());
        for (int i = 0; i < limit; i++) {
            RouteSummary summary = summaries.get(i);
            builder.append("  · ").append(summary.displayName())
                    .append("（").append(summary.pointCount()).append(" 点）\n");
        }

        builder.append("\n【最近日志】\n");
        List<String> lines = DiagnosticLog.recentLines();
        int start = Math.max(0, lines.size() - 120);
        if (lines.isEmpty()) {
            builder.append("（暂无）\n");
        } else {
            for (int i = start; i < lines.size(); i++) {
                builder.append(lines.get(i)).append('\n');
            }
        }

        binding.textReport.setText(builder.toString());
        updateSelfCheck();
    }

    /** 「系统到底收没收到」——把应用提交的坐标和系统读回的坐标放一起对比。 */
    private void updateSelfCheck() {
        RunSnapshot snapshot = SimulationStateRepo.get().current();
        if (!snapshot.hasPosition()) {
            binding.textSelfCheck.setText(R.string.self_check_no_data);
            return;
        }
        GeoPoint expected = new GeoPoint(snapshot.latitude(), snapshot.longitude());
        StringBuilder builder = new StringBuilder();
        builder.append(getString(R.string.self_check_expected,
                expected.format(), snapshot.providersText())).append('\n');

        List<SystemLocationReader.Probe> probes = SystemLocationReader.readAll(this);
        if (probes.isEmpty()) {
            builder.append("没有定位权限，无法读回系统位置；请在系统设置里授予「精确位置」。\n");
            binding.textSelfCheck.setText(builder.toString());
            return;
        }
        for (SystemLocationReader.Probe probe : probes) {
            if (!probe.hasFix()) {
                LocationVerifier.Verdict verdict = LocationVerifier.evaluate(
                        expected, null, false, LocationVerifier.DEFAULT_TOLERANCE_METERS);
                builder.append(probe.provider).append("：").append(probe.note).append('\n')
                        .append("  结论：")
                        .append(LocationVerifier.explain(verdict, -1d, probe.provider))
                        .append('\n');
                continue;
            }
            GeoPoint observed = probe.point();
            double distance = LocationVerifier.distanceMeters(expected, observed);
            LocationVerifier.Verdict verdict = LocationVerifier.evaluate(
                    expected, observed, probe.markedMock(), LocationVerifier.DEFAULT_TOLERANCE_METERS,
                    probe.ageMillis(), LocationVerifier.DEFAULT_STALE_AFTER_MS);
            builder.append(String.format(Locale.US, "%s：%s · 模拟标记 %s · %.1f 秒前\n",
                    probe.provider, observed.format(), probe.markedMock() ? "是" : "否",
                    probe.ageMillis() / 1000d));
            builder.append("  结论：")
                    .append(LocationVerifier.explain(verdict, distance, probe.provider, probe.ageMillis()))
                    .append('\n');
        }
        binding.textSelfCheck.setText(builder.toString());
    }

    private String versionText() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return info.versionName + "（versionCode " + info.getLongVersionCode() + "）";
        } catch (PackageManager.NameNotFoundException e) {
            return "未知";
        }
    }

    private String permissionText(String permission) {
        boolean granted = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED;
        return granted ? "已授权" : "未授权";
    }

    private boolean safeLocationEnabled(LocationManager manager) {
        try {
            return manager.isLocationEnabled();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private String booleanText(boolean value) {
        return value ? "是" : "否";
    }

    private void copyReport() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) {
            return;
        }
        clipboard.setPrimaryClip(ClipData.newPlainText("Legion诊断", binding.textReport.getText()));
        toast("已复制到剪贴板");
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
