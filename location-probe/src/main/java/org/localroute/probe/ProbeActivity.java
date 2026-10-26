package org.localroute.probe;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;
import android.text.format.DateFormat;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.localroute.probe.databinding.ActivityProbeBinding;

import java.util.Date;
import java.util.Locale;

/**
 * 定位探针：独立包名（org.localroute.probe）从系统 LocationManager 读取位置。
 *
 * <p>存在意义是“旁证”：主 App 用模拟位置输出坐标后，用这个完全独立的应用读系统位置，
 * 如果这里看到坐标在动、且标记为 mock，就说明模拟位置真的进了系统（任务书第 12 步、T15）。
 *
 * <p>它只做读取与展示：不写任何数据、不读主 App 的存储、不需要任何特殊权限，
 * 也不判断“是谁在提供位置”——第三方来源的真实性应由使用者自行确认。
 */
public class ProbeActivity extends AppCompatActivity implements LocationListener {

    private static final String[] PROVIDERS = {
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
    };
    private static final int REQUEST_LOCATION = 1001;
    /** 与主 App 的默认 tick 对齐，便于观察 500ms 一次的更新。 */
    private static final long MIN_TIME_MS = 500L;
    private static final float MIN_DISTANCE_M = 0f;
    private static final int MAX_LINES = 400;

    private ActivityProbeBinding binding;
    private LocationManager locationManager;
    private boolean listening;
    private int fixCount;
    private Location lastLocation;
    private double travelledMeters;
    private long startedAtElapsedMs;
    private final java.util.ArrayDeque<String> lines = new java.util.ArrayDeque<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityProbeBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        setSupportActionBar(binding.toolbar);

        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        binding.buttonListen.setOnClickListener(v -> startListening());
        binding.buttonStop.setOnClickListener(v -> stopListening(true));
        binding.buttonClear.setOnClickListener(v -> {
            lines.clear();
            binding.textLog.setText("");
            fixCount = 0;
            travelledMeters = 0d;
            lastLocation = null;
            updateStatus(getString(R.string.probe_cleared));
        });

        updateStatus(getString(R.string.probe_idle_hint));
        reportProvidersState();

        // 探针只是旁证工具：已有权限时自动开始监听，省掉一次点击（也便于自动化验证）。
        if (hasLocationPermission()) {
            binding.getRoot().post(this::startListening);
        }
    }

    private void reportProvidersState() {
        appendLine("系统定位开关：" + (isLocationEnabled() ? "开" : "关"));
        for (String provider : PROVIDERS) {
            boolean supported;
            try {
                supported = locationManager.getAllProviders().contains(provider);
            } catch (RuntimeException e) {
                supported = false;
            }
            appendLine(String.format(Locale.US, "provider %s：%s", provider, supported ? "存在" : "不存在"));
        }
        appendLine("提示：先启动主 App 的模拟，再点“开始监听”。");
    }

    private boolean isLocationEnabled() {
        // minSdk 29 ≥ P，因此不需要再判断版本。
        return locationManager.isLocationEnabled();
    }

    private void startListening() {
        if (listening) {
            updateStatus(getString(R.string.probe_already_listening));
            return;
        }
        if (!hasLocationPermission()) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION},
                    REQUEST_LOCATION);
            return;
        }
        if (!isLocationEnabled()) {
            updateStatus(getString(R.string.probe_location_disabled));
            return;
        }

        int attached = 0;
        for (String provider : PROVIDERS) {
            try {
                locationManager.requestLocationUpdates(provider, MIN_TIME_MS, MIN_DISTANCE_M, this,
                        Looper.getMainLooper());
                attached++;
                Location last = locationManager.getLastKnownLocation(provider);
                if (last != null) {
                    onLocationChanged(last);
                }
            } catch (SecurityException e) {
                appendLine("无权监听 " + provider + "：" + e.getMessage());
            } catch (IllegalArgumentException e) {
                appendLine("provider 不可用：" + provider);
            }
        }
        if (attached == 0) {
            updateStatus(getString(R.string.probe_no_provider));
            return;
        }
        listening = true;
        startedAtElapsedMs = SystemClock.elapsedRealtime();
        binding.buttonListen.setEnabled(false);
        updateStatus(getString(R.string.probe_listening));
        appendLine("开始监听 " + attached + " 个 provider（间隔 " + MIN_TIME_MS + " 毫秒）");
    }

    private void stopListening(boolean fromUser) {
        if (!listening) {
            if (fromUser) {
                updateStatus(getString(R.string.probe_not_listening));
            }
            return;
        }
        locationManager.removeUpdates(this);
        listening = false;
        binding.buttonListen.setEnabled(true);
        long listenedMs = SystemClock.elapsedRealtime() - startedAtElapsedMs;
        String summary = getString(R.string.probe_stopped_summary, fixCount,
                String.format(Locale.US, "%.1f", travelledMeters),
                String.format(Locale.US, "%.1f", listenedMs / 1000d));
        updateStatus(summary);
        appendLine(summary);
    }

    @Override
    public void onLocationChanged(@NonNull Location location) {
        fixCount++;
        if (lastLocation != null) {
            travelledMeters += lastLocation.distanceTo(location);
        }
        lastLocation = location;

        String provider = location.getProvider() == null ? "?" : location.getProvider();
        String mockFlag = isMock(location) ? "模拟位置" : "未标记模拟";
        String line = String.format(Locale.US,
                "%s  %s  lat=%.6f lon=%.6f  acc=%.1fm  spd=%.2fm/s  brg=%.0f°  %s",
                DateFormat.format("HH:mm:ss", new Date(location.getTime())),
                provider,
                location.getLatitude(),
                location.getLongitude(),
                location.hasAccuracy() ? location.getAccuracy() : -1f,
                location.hasSpeed() ? location.getSpeed() : -1f,
                location.hasBearing() ? location.getBearing() : -1f,
                mockFlag);
        appendLine(line);

        String status = getString(R.string.probe_status_counters, fixCount,
                String.format(Locale.US, "%.1f", travelledMeters), mockFlag);
        updateStatus(status);
    }

    private boolean isMock(Location location) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return location.isMock();
        }
        return location.isFromMockProvider();
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
        // 仅为兼容 minSdk 29 保留；新版系统不再回调。
    }

    @Override
    public void onProviderEnabled(@NonNull String provider) {
        appendLine("provider 已启用：" + provider);
    }

    @Override
    public void onProviderDisabled(@NonNull String provider) {
        appendLine("provider 已禁用：" + provider);
    }

    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_LOCATION) {
            return;
        }
        if (hasLocationPermission()) {
            startListening();
        } else {
            updateStatus(getString(R.string.probe_permission_missing));
        }
    }

    private void updateStatus(String text) {
        binding.textStatus.setText(text);
    }

    private void appendLine(String line) {
        boolean trimming = lines.size() >= MAX_LINES;
        if (trimming) {
            lines.removeFirst();
        }
        lines.addLast(line);
        if (trimming) {
            binding.textLog.setText(String.join("\n", lines));
        } else {
            if (lines.size() > 1) {
                binding.textLog.append("\n");
            }
            binding.textLog.append(line);
        }
        binding.scroll.post(() -> binding.scroll.fullScroll(android.view.View.FOCUS_DOWN));
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 回到前台自动恢复监听（onStop 时会停掉）。
        if (!listening && hasLocationPermission()) {
            startListening();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        // 界面不可见时停止监听，避免后台耗电；数据已在界面上留痕。
        stopListening(false);
    }
}
