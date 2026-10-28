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
    private long listenEndedNanos;
    private long startWallClockMs;
    private long endWallClockMs;
    private long lastFrameNanos;
    private String criteriaProvider = "";
    private String gmsStatus = "未启动";
    private AmapReader amapReader;
    private String amapStatus = "未启动";

    /** 高德 SDK 通道的回调：单帧字段走 {@link AmapReader#describe}，统计仍进同一个 ChannelStats。 */
    private final AmapReader.Sink amapSink = new AmapReader.Sink() {
        @Override
        public void onAmapFrame(String channel, com.amap.api.location.AMapLocation location) {
            handleAmapFrame(channel, location);
        }

        @Override
        public void onAmapNote(String note) {
            append(note);
        }
    };

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
        startWallClockMs = System.currentTimeMillis();
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

        // 6) 高德 SDK 通道：目标 App 的跑步模块走的就是这条，系统四通道证明不了它收到什么。
        startAmapReader();

        binding.textStatus.setText(R.string.probe_listening);
        append(getString(R.string.probe_listening));
        updateCounters();
    }

    private void startAmapReader() {
        if (!AmapReader.isConfigured()) {
            amapStatus = "UNAVAILABLE（未配置 Key）";
            append(AmapReader.missingKeyHint());
            return;
        }
        try {
            amapReader = AmapReader.create(this, amapSink);
            amapReader.start();
            amapStatus = amapReader.primaryStatus();
        } catch (Throwable error) {
            amapStatus = "启动失败（" + error + "）";
            append("amap-sdk：" + amapStatus);
            amapReader = null;
        }
    }

    /**
     * 高德通道的单帧。这里只做统计 + 打印，不做任何「过滤后当成功」的改写：
     * errorCode 非 0 的帧也照样计数，因为「SDK 明确拒收」本身就是结论。
     */
    private void handleAmapFrame(String channel, com.amap.api.location.AMapLocation location) {
        long now = SystemClock.elapsedRealtimeNanos();
        long latencyMs = listenStartedNanos == 0L ? -1L : (now - listenStartedNanos) / 1_000_000L;
        ChannelStats channelStats = stats.get(channel);
        if (channelStats == null) {
            channelStats = new ChannelStats(channel);
            stats.put(channel, channelStats);
        }
        if (location.getErrorCode() != 0) {
            // 错误回调只记「回调次数」，不进位置与距离（R32-05）：
            // 否则「SDK 拒收 27 帧」会被算成「移动了 595 米」，结论正好反了。
            channelStats.addErrorCallback();
            append(String.format(Locale.US, "%s %s %s",
                    new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()),
                    channel, AmapReader.describe(location)));
            updateCounters();
            return;
        }
        channelStats.addFrame(now, location.getLatitude(), location.getLongitude(),
                location.hasSpeed(), location.hasSpeed() ? location.getSpeed() : 0d,
                AmapReader.isMockLocation(location), latencyMs);
        append(String.format(Locale.US, "%s %s %s",
                new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()),
                channel, AmapReader.describe(location)));
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
        if (amapReader != null) {
            amapReader.stop();
            amapStatus = amapReader.primaryStatus();
            amapReader = null;
        }
        listening = false;
        listenEndedNanos = SystemClock.elapsedRealtimeNanos();
        endWallClockMs = System.currentTimeMillis();
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
        // 卫星数与载噪比来自 extras：目标 App 常拿它判断“是不是真 GPS 定位”，
        // 所以这里必须显示出来，才能证明这些字段穿过 framework 之后还在。
        String satellites = " 卫星未知";
        android.os.Bundle extras = location.getExtras();
        if (extras != null && extras.containsKey("satellites")) {
            satellites = String.format(Locale.US, " 卫星=%d", extras.getInt("satellites"));
        }
        String speedAccuracy = location.hasSpeedAccuracy()
                ? String.format(Locale.US, " spdAcc=%.2f", location.getSpeedAccuracyMetersPerSecond())
                : "";
        return String.format(Locale.US,
                "%.6f,%.6f acc=%s 年龄=%s%s%s%s%s %s",
                location.getLatitude(), location.getLongitude(),
                location.hasAccuracy() ? String.format(Locale.US, "%.1fm", location.getAccuracy()) : "无",
                ageMs < 0 ? "未知" : String.format(Locale.US, "%.1fs", ageMs / 1000d),
                location.hasSpeed() ? String.format(Locale.US, " spd=%.2fm/s", location.getSpeed()) : " 无速度",
                location.hasBearing() ? String.format(Locale.US, " brg=%.0f°", location.getBearing()) : " 无航向",
                speedAccuracy,
                satellites,
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
        long nowNanos = SystemClock.elapsedRealtimeNanos();
        boolean transfer = binding.checkTransfer.isChecked();
        StringBuilder report = new StringBuilder();
        report.append("多通道接收报告\n");
        report.append("统计会话：").append(transfer
                ? "传送测试（只点 A/B/C）：位移一栏是「位置跳变累计」，不是运动距离"
                : "跑步路线：位移与均速可当运动量参考").append('\n');
        report.append("起止时间：").append(sessionTimes()).append('\n');
        report.append("GMS FLP 状态：").append(gmsStatus);
        ChannelStats gms = stats.get(CHANNEL_GMS);
        if (gms != null) {
            report.append("（最后回调 ").append(ageText(gms.ageSinceLastFrameMillis(nowNanos))).append("）");
        }
        report.append('\n');
        report.append("Criteria 选中：").append(criteriaProvider.isEmpty() ? "无" : criteriaProvider).append('\n');
        report.append("高德 SDK 通道：").append(amapStatus);
        ChannelStats amapPrimary = stats.get(AmapReader.CHANNEL_PRIMARY);
        if (amapPrimary != null) {
            report.append("（最后回调 ").append(ageText(amapPrimary.ageSinceLastFrameMillis(nowNanos))).append("）");
        }
        report.append('\n');
        if (amapReader != null) {
            report.append("高德对照通道：").append(amapReader.devStatus()).append('\n');
            report.append(amapReader.optionSummary()).append('\n');
        }
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

    /** 起止时间：开始监听至今（或到停止为止）的时长 + 墙钟时间。 */
    private String sessionTimes() {
        if (listenStartedNanos == 0L) {
            return "未开始";
        }
        long endNanos = listening ? SystemClock.elapsedRealtimeNanos() : listenEndedNanos;
        long seconds = Math.max(0L, (endNanos - listenStartedNanos) / 1_000_000_000L);
        SimpleDateFormat format = new SimpleDateFormat("HH:mm:ss", Locale.US);
        return format.format(new Date(startWallClockMs))
                + " → " + (listening ? "现在" : format.format(new Date(endWallClockMs)))
                + "（" + seconds + " 秒" + (listening ? "，仍在监听" : "，已停止") + "）";
    }

    private String ageText(long ageMillis) {
        return ageMillis < 0 ? "无" : String.format(Locale.US, "%.1f 秒前", ageMillis / 1000d);
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

    /**
     * 追加一行日志。
     *
     * <p>同步 + 折掉行内换行：不同通道的回调可能来自不同线程，一行里混进 {@code \n} 会让
     * 两条记录挤在一起，看起来像「两个定位来源混在一行」（用户反馈，0.3.2 修）。
     */
    private synchronized void append(String line) {
        String safe = line == null ? "" : line.replace('\r', ' ').replace('\n', ' ');
        lines.add(safe);
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
        // 故意不在这里停订阅：要看“目标 App 在前台时收到什么”，本页必须退到后台还在听。
        // 之前每次切走就 stopListening()，等于只能用分屏取证（记为 N-10）。
        if (listening) {
            append("已切到后台继续接收；回到本页可看结果，点「停止」或退出本页才停止");
        }
    }

    @Override
    protected void onDestroy() {
        if (listening) {
            stopListening();
        }
        super.onDestroy();
    }
}
