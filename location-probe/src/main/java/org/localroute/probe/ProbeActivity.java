package org.localroute.probe;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.pm.PackageManager;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

import org.localroute.probe.databinding.ActivityProbeBinding;
import org.localroute.simulation.ChannelStats;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 独立的多通道接收探针：只通过公开定位接口读取，不读主应用的内部状态。
 *
 * <p>通道：gps / network / framework fused（平台融合）/ Criteria 选中的 provider / Google Play
 * services FLP（设备具备时）。每条通道单独统计，缺 GMS、缺权限、来源不存在都显式说明，
 * 不会悄悄换成 GPS 然后标成「FLP 通过」。
 */
// 权限在 startListening() 里检查；每条订阅/读取都单独 try/catch SecurityException。
@SuppressLint("MissingPermission")
public class ProbeActivity extends AppCompatActivity {

    private static final String TAG = "RouteProbe";
    private static final int REQUEST_CODE = 1001;
    private static final long MIN_TIME_MS = 500L;
    private static final float MIN_DISTANCE_M = 0f;
    private static final int MAX_LINES = 600;

    private static final String CHANNEL_GPS = "gps";
    private static final String CHANNEL_NETWORK = "network";
    private static final String CHANNEL_FUSED = "fused(framework)";
    private static final String CHANNEL_CRITERIA = "criteria";
    private static final String CHANNEL_GMS = "gms-flp";

    private ActivityProbeBinding binding;
    private LocationManager locationManager;
    private FusedLocationProviderClient fusedClient;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, ChannelStats> stats = new LinkedHashMap<>();
    private final List<String> lines = new ArrayList<>();

    private boolean listening;
    private long listenStartedNanos;
    private long lastFrameNanos;
    private String criteriaProvider = "";
    private String gmsStatus = "未启动";

    private final LocationListener listener = new LocationListener() {
        @Override
        public void onLocationChanged(@NonNull Location location) {
            handleLocation(channelOf(location.getProvider()), location);
        }

        @Override
        public void onProviderEnabled(@NonNull String provider) {
            append("provider 已启用：" + provider);
        }

        @Override
        public void onProviderDisabled(@NonNull String provider) {
            append("provider 已禁用：" + provider);
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
            append("provider 状态变化：" + provider + " status=" + status);
        }
    };

    private final LocationCallback gmsCallback = new LocationCallback() {
        @Override
        public void onLocationResult(@NonNull LocationResult result) {
            for (Location location : result.getLocations()) {
                handleLocation(CHANNEL_GMS, location);
            }
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityProbeBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        setSupportActionBar(binding.toolbar);

        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        binding.textStatus.setText(R.string.probe_idle_hint);
        binding.buttonListen.setOnClickListener(v -> {
            if (listening) {
                append(getString(R.string.probe_already_listening));
                return;
            }
            startListening();
        });
        binding.buttonStop.setOnClickListener(v -> stopListening());
        binding.buttonClear.setOnClickListener(v -> {
            lines.clear();
            binding.textLog.setText(R.string.probe_cleared);
        });
        binding.buttonReport.setOnClickListener(v -> showReport());
        updateCounters();
    }

    private void startListening() {
        if (!hasFinePermission()) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION}, REQUEST_CODE);
            return;
        }
        if (locationManager == null || !locationManager.isLocationEnabled()) {
            append(getString(R.string.probe_location_disabled));
            return;
        }

        stats.clear();
        listenStartedNanos = SystemClock.elapsedRealtimeNanos();
        lastFrameNanos = 0L;
        listening = true;
        append("开始监听：" + new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()));

        // 1) gps / network / framework fused：都是 LocationManager 的 provider。
        List<String> subscribed = new ArrayList<>();
        for (String provider : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, "fused"}) {
            String channel = channelOf(provider);
            boolean exists;
            try {
                exists = locationManager.isProviderEnabled(provider);
            } catch (RuntimeException error) {
                exists = false;
            }
            if (!exists) {
                append("通道 " + channel + "：UNAVAILABLE（provider 不存在或未启用）");
                continue;
            }
            try {
                locationManager.requestLocationUpdates(provider, MIN_TIME_MS, MIN_DISTANCE_M, listener,
                        Looper.getMainLooper());
                subscribed.add(provider);
                append("通道 " + channel + "：已订阅");
            } catch (SecurityException error) {
                append("通道 " + channel + "：PERMISSION_DENIED（" + error.getMessage() + "）");
            } catch (RuntimeException error) {
                append("通道 " + channel + "：UNAVAILABLE（" + error + "）");
            }
            Location cached = null;
            try {
                cached = locationManager.getLastKnownLocation(provider);
            } catch (SecurityException ignored) {
                // 权限在调用之间被撤回。
            }
            if (cached != null) {
                append("通道 " + channel + "：缓存位置 " + describe(cached) + "（last-known，非连续回调）");
            }
        }

        // 2) Criteria：复现「按条件选择来源」的客户端，并记录它实际选中了谁。
        Criteria criteria = new Criteria();
        criteria.setAccuracy(Criteria.ACCURACY_FINE);
        criteria.setAltitudeRequired(false);
        criteria.setBearingRequired(true);
        criteria.setSpeedRequired(true);
        criteria.setCostAllowed(true);
        criteria.setPowerRequirement(Criteria.POWER_LOW);
        criteriaProvider = locationManager.getBestProvider(criteria, true) == null
                ? "" : locationManager.getBestProvider(criteria, true);
        if (criteriaProvider.isEmpty()) {
            append("通道 " + CHANNEL_CRITERIA + "：UNAVAILABLE（没有满足条件的 provider）");
        } else if (subscribed.contains(criteriaProvider)) {
            append("通道 " + CHANNEL_CRITERIA + "：Criteria 选中 " + criteriaProvider + "（已在上面的订阅里）");
        } else {
            try {
                locationManager.requestLocationUpdates(criteriaProvider, MIN_TIME_MS, MIN_DISTANCE_M, listener,
                        Looper.getMainLooper());
                append("通道 " + CHANNEL_CRITERIA + "：Criteria 选中 " + criteriaProvider + "，已订阅");
            } catch (RuntimeException error) {
                append("通道 " + CHANNEL_CRITERIA + "：订阅失败（" + error + "）");
            }
        }

        // 3) Google Play services FLP：与 framework provider 是两条不同的通道，不能互相替代。
        int availability = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(this);
        if (availability != ConnectionResult.SUCCESS) {
            gmsStatus = "UNAVAILABLE（Google Play services 状态码 " + availability + "）";
            append("通道 " + CHANNEL_GMS + "：" + gmsStatus);
        } else {
            fusedClient = LocationServices.getFusedLocationProviderClient(this);
            LocationRequest request = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, MIN_TIME_MS)
                    .setMinUpdateIntervalMillis(MIN_TIME_MS / 2)
                    .setMaxUpdateDelayMillis(0L)
                    .build();
            fusedClient.requestLocationUpdates(request, gmsCallback, Looper.getMainLooper())
                    .addOnSuccessListener(unused -> {
                        gmsStatus = "订阅中";
                        append("通道 " + CHANNEL_GMS + "：已订阅（官方 FLP 模拟接口之外的常规读取）");
                    })
                    .addOnFailureListener(error -> {
                        gmsStatus = "订阅失败（" + error + "）";
                        append("通道 " + CHANNEL_GMS + "：" + gmsStatus);
                    });
            fusedClient.getLastLocation()
                    .addOnSuccessListener(location -> {
                        if (location != null) {
                            append("通道 " + CHANNEL_GMS + "：缓存位置 " + describe(location)
                                    + "（last-known，非连续回调）");
                        }
                    })
                    .addOnFailureListener(error ->
                            append("通道 " + CHANNEL_GMS + "：读取缓存失败（" + error + "）"));
        }

        binding.textStatus.setText(R.string.probe_listening);
        append(getString(R.string.probe_listening));
        updateCounters();
    }

    private void stopListening() {
        if (!listening) {
            append(getString(R.string.probe_not_listening));
            return;
        }
        try {
            locationManager.removeUpdates(listener);
        } catch (RuntimeException ignored) {
            // provider 已经不可用。
        }
        if (fusedClient != null) {
            fusedClient.removeLocationUpdates(gmsCallback);
            fusedClient = null;
        }
        listening = false;
        long seconds = Math.max(0L, (SystemClock.elapsedRealtimeNanos() - listenStartedNanos) / 1_000_000_000L);
        append(getString(R.string.probe_stopped_summary, totalFrames(), formatMeters(totalDistance()), seconds));
        append("各通道汇总：");
        for (ChannelStats channel : stats.values()) {
            append("  · " + channel.summary());
        }
        binding.textStatus.setText(getString(R.string.probe_status_counters,
                totalFrames(), formatMeters(totalDistance()),
                listening ? getString(R.string.probe_listening) : "已停止"));
    }

    private void handleLocation(String channel, Location location) {
        long now = SystemClock.elapsedRealtimeNanos();
        long latencyMs = listenStartedNanos == 0L ? -1L : (now - listenStartedNanos) / 1_000_000L;
        long frameGapMs = lastFrameNanos == 0L ? -1L : (now - lastFrameNanos) / 1_000_000L;
        lastFrameNanos = now;

        ChannelStats channelStats = stats.get(channel);
        if (channelStats == null) {
            channelStats = new ChannelStats(channel);
            stats.put(channel, channelStats);
        }
        channelStats.addFrame(now, location.getLatitude(), location.getLongitude(),
                location.hasSpeed(), location.hasSpeed() ? location.getSpeed() : 0d,
                isMock(location), latencyMs);

        String line = String.format(Locale.US, "%s %s 间隔=%s %s",
                new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()),
                channel, frameGapMs < 0 ? "首帧" : frameGapMs + "ms", describe(location));
        append(line);
        updateCounters();
    }

    private String describe(Location location) {
        long ageMs = location.getElapsedRealtimeNanos() == 0L
                ? -1L : (SystemClock.elapsedRealtimeNanos() - location.getElapsedRealtimeNanos()) / 1_000_000L;
        return String.format(Locale.US,
                "%.6f,%.6f acc=%s 年龄=%s%s%s %s",
                location.getLatitude(), location.getLongitude(),
                location.hasAccuracy() ? String.format(Locale.US, "%.1fm", location.getAccuracy()) : "无",
                ageMs < 0 ? "未知" : String.format(Locale.US, "%.1fs", ageMs / 1000d),
                location.hasSpeed() ? String.format(Locale.US, " spd=%.2fm/s", location.getSpeed()) : " 无速度",
                location.hasBearing() ? String.format(Locale.US, " brg=%.0f°", location.getBearing()) : " 无航向",
                isMock(location) ? "模拟位置" : "未标记模拟");
    }

    private String channelOf(String provider) {
        if (LocationManager.GPS_PROVIDER.equals(provider)) {
            return CHANNEL_GPS;
        }
        if (LocationManager.NETWORK_PROVIDER.equals(provider)) {
            return CHANNEL_NETWORK;
        }
        if ("fused".equals(provider)) {
            return CHANNEL_FUSED;
        }
        if (provider != null && provider.equals(criteriaProvider)) {
            return CHANNEL_CRITERIA;
        }
        return provider == null ? "unknown" : provider;
    }

    private boolean isMock(Location location) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            return location.isMock();
        }
        return location.isFromMockProvider();
    }

    private void showReport() {
        StringBuilder report = new StringBuilder();
        report.append("多通道接收报告\n");
        report.append("GMS FLP 状态：").append(gmsStatus).append('\n');
        report.append("Criteria 选中：").append(criteriaProvider.isEmpty() ? "无" : criteriaProvider).append('\n');
        for (ChannelStats channel : stats.values()) {
            report.append(channel.summary()).append('\n');
        }
        if (stats.isEmpty()) {
            report.append("还没有收到任何通道的位置：先点「开始监听」，再在主应用里开始运行。\n");
        }
        append("—— 报告 ——");
        for (String line : report.toString().split("\n")) {
            append(line);
        }
        android.content.ClipboardManager clipboard =
                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("probe-report", report.toString()));
            append("（报告已复制到剪贴板，方便贴进问题记录）");
        }
    }

    private void updateCounters() {
        binding.textStatus.setText(getString(R.string.probe_status_counters,
                totalFrames(), formatMeters(totalDistance()),
                listening ? getString(R.string.probe_listening) : "未监听"));
    }

    private int totalFrames() {
        int total = 0;
        for (ChannelStats channel : stats.values()) {
            total += channel.totalFrames();
        }
        return total;
    }

    private double totalDistance() {
        double total = 0d;
        for (ChannelStats channel : stats.values()) {
            total = Math.max(total, channel.travelledMeters());
        }
        return total;
    }

    private static String formatMeters(double meters) {
        return String.format(Locale.US, "%.1f", meters);
    }

    private void append(String line) {
        lines.add(line);
        while (lines.size() > MAX_LINES) {
            lines.remove(0);
        }
        binding.textLog.setText(android.text.TextUtils.join("\n", lines));
        binding.scroll.post(() -> binding.scroll.fullScroll(View.FOCUS_DOWN));
    }

    private boolean hasFinePermission() {
        return ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_CODE) {
            return;
        }
        boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (granted) {
            startListening();
        } else {
            append(getString(R.string.probe_permission_missing));
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (listening) {
            stopListening();
        }
    }
}
