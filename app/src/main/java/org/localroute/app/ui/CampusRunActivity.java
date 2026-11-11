package org.localroute.app.ui;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.google.android.material.bottomsheet.BottomSheetDialog;

import org.localroute.app.BuildConfig;
import org.localroute.app.R;
import org.localroute.app.campus.CampusFormat;
import org.localroute.app.campus.CampusGeo;
import org.localroute.app.campus.CampusPoint;
import org.localroute.app.campus.CampusRoute;
import org.localroute.app.databinding.ActivityCampusRunBinding;
import org.localroute.app.databinding.DialogCampusRoutesBinding;
import org.localroute.app.diagnostics.DiagnosticLog;
import org.localroute.app.diagnostics.SystemLocationReader;
import org.localroute.app.map.AmapRouteMapView;
import org.localroute.app.map.MapBackendPolicy;
import org.localroute.app.map.RouteMapController;
import org.localroute.app.map.RouteMapHost;
import org.localroute.app.run.RunRequest;
import org.localroute.app.run.RunSnapshot;
import org.localroute.app.run.SimulationStateRepo;
import org.localroute.app.service.SimulationService;
import org.localroute.app.settings.SettingsInspector;
import org.localroute.core.CompiledRoute;
import org.localroute.core.EndBehavior;
import org.localroute.core.GeoPoint;
import org.localroute.core.InvalidRouteException;
import org.localroute.core.RouteCompiler;
import org.localroute.core.RunConfig;
import org.localroute.core.RunState;
import org.maplibre.android.MapLibre;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 校园快跑：虚拟定位跑圈。
 *
 * <p>设置页负责运动类型、每公里配速、圈数与两个开关；跑圈地图页负责
 * 开始/暂停/继续/结束、切换目标路线、绘制自定义路线与地图传送。
 * 所有实际定位提交都走 {@link SimulationService}，本页面不自行伪造位置。
 */
public final class CampusRunActivity extends AppCompatActivity {

    private static final String PREFS = "campus_run";
    private static final String KEY_SPORT = "sport";
    private static final String KEY_PACE = "pace_seconds";
    private static final String KEY_LAPS = "laps";
    private static final String KEY_NATURAL = "natural_speed";
    private static final String KEY_VARY = "vary_path";
    private static final String KEY_CUSTOM_POINTS = "custom_points";
    private static final String KEY_ACTIVE_RUN_ID = "active_run_id";
    private static final String KEY_ACTIVE_LAP = "active_lap_meters";
    private static final String KEY_OWNED = "campus_owned";

    private static final String SPORT_RUN = "run";
    private static final String SPORT_WALK = "walk";
    private static final String SPORT_RIDE = "ride";

    private static final int PACE_MIN_SECONDS = 60;
    private static final int PACE_MAX_SECONDS = 1200;
    private static final int DEFAULT_PACE_RUN = 400;
    private static final int DEFAULT_PACE_WALK = 720;
    private static final int DEFAULT_PACE_RIDE = 200;
    private static final int DRAFT_MIN = 3;
    private static final int DRAFT_MAX = 24;
    private static final double CUSTOM_MIN_METERS = 120d;
    private static final double CUSTOM_MAX_METERS = 800d;
    private static final long NATURAL_INTERVAL_MS = 2500L;

    private static final String ROUTE_CUSTOM_ID = "custom";

    /** 权限弹窗期间 Activity 可能重建，用静态字段保存待执行动作（只含纯数据）。 */
    @Nullable
    private static PendingStart pendingStart;
    @Nullable
    private static GeoPoint pendingTeleport;

    private ActivityCampusRunBinding binding;
    private SharedPreferences prefs;
    private ActivityResultLauncher<String[]> permissionLauncher;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private RunSnapshot snapshot = RunSnapshot.idle();
    @Nullable
    private CampusRoute route;
    @Nullable
    private CampusRoute customRoute;
    /** 传送预览点；真实底图直接保存 WGS-84，不再经过设计坐标。 */
    @Nullable
    private GeoPoint teleportTarget;
    /** 空闲时点「定位当前位置」读到的设备真实位置（WGS-84），用于画点与坐标显示。 */
    @Nullable
    private GeoPoint deviceLocation;
    /** 用户是在「定位当前位置」时被要求授权，授权结果回来后继续定位。 */
    private boolean pendingLocate;
    @Nullable
    private CampusRoute pendingRouteSwitch;

    @Nullable
    private RouteMapHost mapHost;
    /** 已经画到底图上的路线对象；换路线时用它判断是否需要重画。 */
    @Nullable
    private CampusRoute renderedRoute;
    private boolean mapReady;
    private boolean pendingFit;

    private String sport = SPORT_RUN;
    private int paceSeconds = DEFAULT_PACE_RUN;
    private int laps = 5;
    private boolean naturalEnabled = true;
    private boolean varyEnabled = true;

    private boolean runScreenVisible;
    private boolean drawMode;
    private final List<CampusPoint> draftPoints = new ArrayList<>();
    private boolean teleportArmed;

    private boolean claimOwnSession;
    private String activeRunId = "";
    private double activeLapMeters = 400d;
    private double baseSpeedKmh = 9d;
    private double naturalPhase1;
    private double naturalPhase2;
    private boolean naturalRunning;
    private boolean stopRequested;
    private String autoPauseNotifiedRunId = "";

    private final Runnable naturalTicker = new Runnable() {
        @Override
        public void run() {
            if (!naturalRunning) {
                return;
            }
            RunSnapshot current = snapshot;
            if (naturalEnabled && claimOwnSession && current.state() == RunState.RUNNING
                    && !current.singlePoint()) {
                double elapsed = current.activeElapsedMs();
                double factor = 1d
                        + 0.035d * Math.sin(elapsed / 38000d + naturalPhase1)
                        + 0.015d * Math.sin(elapsed / 9500d + naturalPhase2);
                double speed = Math.max(0.6d, Math.min(59.5d, baseSpeedKmh * factor));
                SimulationService.setSpeedKmh(CampusRunActivity.this, speed);
                handler.postDelayed(this, NATURAL_INTERVAL_MS);
                return;
            }
            naturalRunning = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // MapLibre 要求在使用 MapView 之前完成初始化。
        MapLibre.getInstance(this);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        loadSettings();

        binding = ActivityCampusRunBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        ViewCompat.setOnApplyWindowInsetsListener(binding.getRoot(), (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        controller.setAppearanceLightStatusBars(true);
        controller.setAppearanceLightNavigationBars(true);

        permissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(), this::onPermissionResult);

        wireViews();
        setupMap();
        renderSport();
        renderSetupValues();

        RunSnapshot current = SimulationStateRepo.get().current();
        snapshot = current;
        boolean resumed = restoreActiveSession(current);
        if (resumed) {
            showRunScreen();
        } else {
            showSetupScreen();
        }
        SimulationStateRepo.get().snapshot().observe(this, this::onSnapshot);
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                handleBack();
            }
        });
    }

    // ---------------------------------------------------------------- 设置与恢复

    private void loadSettings() {
        sport = prefs.getString(KEY_SPORT, SPORT_RUN);
        if (!SPORT_WALK.equals(sport) && !SPORT_RIDE.equals(sport)) {
            sport = SPORT_RUN;
        }
        paceSeconds = clamp(prefs.getInt(KEY_PACE, defaultPaceSeconds(sport)),
                PACE_MIN_SECONDS, PACE_MAX_SECONDS);
        laps = clamp(prefs.getInt(KEY_LAPS, RunConfig.DEFAULT_LOOPS),
                RunConfig.MIN_LOOPS, RunConfig.MAX_LOOPS);
        naturalEnabled = prefs.getBoolean(KEY_NATURAL, true);
        varyEnabled = prefs.getBoolean(KEY_VARY, true);
        customRoute = loadCustomRoute();
        // 目前没有内置路线，只有用户绘制并保存的自定义路线。
        route = customRoute;
        activeRunId = prefs.getString(KEY_ACTIVE_RUN_ID, "");
        activeLapMeters = prefs.getFloat(KEY_ACTIVE_LAP,
                route == null ? 0f : (float) route.lapMeters());
        teleportTarget = null;
    }

    private boolean restoreActiveSession(RunSnapshot current) {
        boolean owned = prefs.getBoolean(KEY_OWNED, false);
        if (current.isActive()) {
            boolean matchId = !activeRunId.isEmpty() && activeRunId.equals(current.runId());
            if (owned && (matchId || current.singlePoint())) {
                claimOwnSession = true;
                activeRunId = current.runId();
                if (current.singlePoint() && activeLapMeters <= 0d) {
                    activeLapMeters = 0d;
                }
                return true;
            }
            return false;
        }
        prefs.edit().putBoolean(KEY_OWNED, false).remove(KEY_ACTIVE_RUN_ID).apply();
        claimOwnSession = false;
        return false;
    }

    @Nullable
    private CampusRoute loadCustomRoute() {
        String raw = prefs.getString(KEY_CUSTOM_POINTS, "");
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        List<CampusPoint> points = new ArrayList<>();
        try {
            for (String piece : raw.split(";")) {
                String trimmed = piece.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                String[] xy = trimmed.split(",");
                if (xy.length != 2) {
                    return null;
                }
                points.add(new CampusPoint(Double.parseDouble(xy[0]), Double.parseDouble(xy[1])));
            }
        } catch (RuntimeException e) {
            return null;
        }
        if (points.size() < DRAFT_MIN) {
            return null;
        }
        CampusRoute custom = CampusRoute.custom(ROUTE_CUSTOM_ID, getString(R.string.campus_custom_name), points);
        if (custom.lapMeters() < CUSTOM_MIN_METERS || custom.lapMeters() > CUSTOM_MAX_METERS) {
            return null;
        }
        return custom;
    }

    private void saveCustomRoute(CampusRoute custom) {
        List<CampusPoint> points = custom.waypointsForSave();
        StringBuilder builder = new StringBuilder();
        for (CampusPoint point : points) {
            if (builder.length() > 0) {
                builder.append(';');
            }
            builder.append(String.format(Locale.US, "%.2f,%.2f", point.x, point.y));
        }
        prefs.edit().putString(KEY_CUSTOM_POINTS, builder.toString()).apply();
    }

    // ---------------------------------------------------------------- 控件

    private void wireViews() {
        binding.buttonSetupBack.setOnClickListener(v -> finish());
        binding.buttonRunBack.setOnClickListener(v -> handleBack());
        binding.buttonSport.setOnClickListener(this::showSportMenu);
        binding.buttonRunSport.setOnClickListener(this::showSportMenu);

        binding.seekPace.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar seekBar, int progress, boolean fromUser) {
                paceSeconds = PACE_MIN_SECONDS + progress;
                renderPaceValue();
                if (fromUser) {
                    prefs.edit().putInt(KEY_PACE, paceSeconds).apply();
                }
            }

            @Override
            public void onStartTrackingTouch(android.widget.SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(android.widget.SeekBar seekBar) {
            }
        });
        binding.buttonLapMinus.setOnClickListener(v -> changeLaps(-1));
        binding.buttonLapPlus.setOnClickListener(v -> changeLaps(1));
        binding.switchNatural.setOnCheckedChangeListener((button, checked) -> {
            naturalEnabled = checked;
            prefs.edit().putBoolean(KEY_NATURAL, checked).apply();
        });
        binding.switchVary.setOnCheckedChangeListener((button, checked) -> {
            varyEnabled = checked;
            prefs.edit().putBoolean(KEY_VARY, checked).apply();
        });
        binding.rowRoute.setOnClickListener(v -> showRouteSheet());
        binding.buttonGoal.setOnClickListener(v -> showRouteSheet());
        binding.buttonGo.setOnClickListener(v -> onGoClicked());
        binding.buttonRunMain.setOnClickListener(v -> onMainControl());
        binding.buttonEndRun.setOnClickListener(v -> requestStop());
        binding.buttonTeleport.setOnClickListener(this::showMapActionsMenu);

        binding.buttonDrawUndo.setOnClickListener(v -> {
            if (!draftPoints.isEmpty()) {
                draftPoints.remove(draftPoints.size() - 1);
                renderMapDraft();
                updateDrawBanner();
            }
        });
        binding.buttonDrawCancel.setOnClickListener(v -> {
            exitDrawMode();
            showSetupScreen();
        });
        binding.buttonDrawDone.setOnClickListener(v -> finishDrawMode());

        binding.buttonDoneAgain.setOnClickListener(v -> {
            binding.doneOverlay.setVisibility(View.GONE);
            startRouteFlow();
        });
        binding.buttonDoneBack.setOnClickListener(v -> {
            binding.doneOverlay.setVisibility(View.GONE);
            showSetupScreen();
        });
    }

    // ---------------------------------------------------------------- 真实地图

    private void setupMap() {
        RouteMapController.OnMapClickListener listener = point -> {
            if (drawMode) {
                // 自定义路线仍按固定比例存成设计坐标，底图显示的是真实地点。
                addDraftPoint(CampusGeo.toDesign(point, CampusRoute.CUSTOM_METERS_PER_UNIT));
            } else if (teleportArmed) {
                performTeleport(point);
            }
        };
        MapBackendPolicy.Decision backend = MapBackendPolicy.choose(
                BuildConfig.AMAP_KEY, Arrays.asList(Build.SUPPORTED_ABIS));
        DiagnosticLog.info("校园快跑地图引擎：" + backend.reason());
        boolean amapSdk = false;
        if (backend.usesAmapSdk()) {
            // SDK 地图会加在同一个 FrameLayout 里；MapLibre 视图必须藏起来，否则会盖住并吃掉触摸。
            binding.mapView.setVisibility(View.GONE);
            try {
                mapHost = new AmapRouteMapView(this, binding.mapContainer, listener);
                amapSdk = true;
            } catch (Throwable error) {
                DiagnosticLog.warn("高德 SDK 初始化失败，改用栅格瓦片底图：" + error);
                binding.mapView.setVisibility(View.VISIBLE);
            }
        }
        if (mapHost == null) {
            mapHost = RouteMapController.attach(binding.mapView, listener);
        }
        binding.textMapMode.setText(getString(amapSdk
                ? R.string.campus_map_mode_amap : R.string.campus_map_mode_tiles));
        mapHost.registerGestureListener();
        mapHost.setStatusListener(message -> {
            if (message == null || message.isEmpty()) {
                binding.textMapStatus.setVisibility(View.GONE);
                mapReady = true;
                if (pendingFit && runScreenVisible) {
                    pendingFit = false;
                    fitMapToRoute();
                }
            } else {
                binding.textMapStatus.setText(message);
                binding.textMapStatus.setVisibility(View.VISIBLE);
            }
        });
    }

    /** 把当前路线画到底图；同一路线对象不重复提交（跑圈状态每秒都在刷新）。 */
    private void syncMapRoute(boolean force) {
        if (mapHost == null) {
            return;
        }
        if (route == null) {
            renderedRoute = null;
            mapHost.renderRoute(Collections.emptyList(), false);
            return;
        }
        if (!force && route == renderedRoute) {
            return;
        }
        renderedRoute = route;
        // 自定义路线有上百个平滑采样点，只画线，避免节点把线盖成串珠。
        mapHost.renderRoute(route.geoPoints(), true, false);
    }

    /** 画自定义路线时只显示已点下的航点；还没点时保留当前路线作参照。 */
    private void renderMapDraft() {
        if (mapHost == null) {
            return;
        }
        if (draftPoints.isEmpty()) {
            renderedRoute = null;
            syncMapRoute(false);
            return;
        }
        renderedRoute = null;
        mapHost.renderRoute(
                CampusGeo.toGeoList(draftPoints, CampusRoute.CUSTOM_METERS_PER_UNIT),
                draftPoints.size() >= 3, true);
    }

    /** 请求把镜头框住当前路线；地图或页面还没就绪时，等就绪回调再取景。 */
    private void requestMapFit() {
        if (mapHost == null) {
            return;
        }
        pendingFit = true;
        if (mapReady && runScreenVisible) {
            pendingFit = false;
            fitMapToRoute();
        }
    }

    private void fitMapToRoute() {
        if (mapHost == null) {
            return;
        }
        if (route == null) {
            // 还没有路线时至少把镜头放到校园基准点，方便开始绘制。
            mapHost.focusOn(new GeoPoint(CampusGeo.ANCHOR_LAT, CampusGeo.ANCHOR_LON));
            return;
        }
        mapHost.fitRoute(route.geoPoints());
    }

    private void showSportMenu(View anchor) {
        if (claimOwnSession && snapshot.isActive()) {
            toast(getString(R.string.campus_sport_locked));
            return;
        }
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenu().add(0, 1, 0, getString(R.string.campus_sport_run));
        popup.getMenu().add(0, 2, 1, getString(R.string.campus_sport_walk));
        popup.getMenu().add(0, 3, 2, getString(R.string.campus_sport_ride));
        popup.setOnMenuItemClickListener(item -> {
            String next = item.getItemId() == 2 ? SPORT_WALK
                    : item.getItemId() == 3 ? SPORT_RIDE : SPORT_RUN;
            changeSport(next);
            return true;
        });
        popup.show();
    }

    private void changeSport(String next) {
        sport = next;
        paceSeconds = defaultPaceSeconds(next);
        prefs.edit()
                .putString(KEY_SPORT, sport)
                .putInt(KEY_PACE, paceSeconds)
                .apply();
        renderSport();
        renderPaceValue();
        toast(getString(R.string.campus_sport_changed, sportLabel(), CampusFormat.pace(paceSeconds)));
    }

    private void changeLaps(int delta) {
        laps = clamp(laps + delta, RunConfig.MIN_LOOPS, RunConfig.MAX_LOOPS);
        binding.textLaps.setText(String.valueOf(laps));
        prefs.edit().putInt(KEY_LAPS, laps).apply();
    }

    private void renderSport() {
        String label = sportLabel();
        binding.buttonSport.setText(label);
        binding.buttonRunSport.setText(label);
        binding.textGoSport.setText(label);
    }

    private String sportLabel() {
        if (SPORT_WALK.equals(sport)) {
            return getString(R.string.campus_sport_walk);
        }
        if (SPORT_RIDE.equals(sport)) {
            return getString(R.string.campus_sport_ride);
        }
        return getString(R.string.campus_sport_run);
    }

    private void renderPaceValue() {
        binding.textPaceValue.setText(getString(R.string.campus_pace_value, CampusFormat.pace(paceSeconds)));
        int progress = paceSeconds - PACE_MIN_SECONDS;
        if (binding.seekPace.getProgress() != progress) {
            binding.seekPace.setProgress(progress);
        }
    }

    private void renderSetupValues() {
        renderPaceValue();
        binding.textLaps.setText(String.valueOf(laps));
        binding.switchNatural.setChecked(naturalEnabled);
        binding.switchVary.setChecked(varyEnabled);
        binding.textRouteSummary.setText(routeSummary(route));
    }

    private String routeSummary(@Nullable CampusRoute value) {
        if (value == null) {
            return getString(R.string.campus_route_none);
        }
        return getString(R.string.campus_route_custom_summary, CampusFormat.lapMeters(value.lapMeters()));
    }

    private int defaultPaceSeconds(String sport) {
        if (SPORT_WALK.equals(sport)) {
            return DEFAULT_PACE_WALK;
        }
        if (SPORT_RIDE.equals(sport)) {
            return DEFAULT_PACE_RIDE;
        }
        return DEFAULT_PACE_RUN;
    }

    // ---------------------------------------------------------------- 页面切换

    private void showSetupScreen() {
        runScreenVisible = false;
        drawMode = false;
        teleportArmed = false;
        draftPoints.clear();
        binding.setupScreen.setVisibility(View.VISIBLE);
        binding.runScreen.setVisibility(View.GONE);
        binding.doneOverlay.setVisibility(View.GONE);
        binding.drawBar.setVisibility(View.GONE);
        binding.textTeleportHint.setVisibility(View.GONE);
        binding.root.setBackgroundColor(getColor(R.color.campus_setup_bg));
        teleportTarget = null;
        deviceLocation = null;
        renderedRoute = null;
        pendingFit = false;
        if (mapHost != null) {
            mapHost.renderRunner(null);
        }
        renderSetupValues();
    }

    private void showRunScreen() {
        runScreenVisible = true;
        binding.setupScreen.setVisibility(View.GONE);
        binding.runScreen.setVisibility(View.VISIBLE);
        binding.doneOverlay.setVisibility(View.GONE);
        binding.root.setBackgroundColor(Color.WHITE);
        if (drawMode) {
            renderMapDraft();
        } else {
            syncMapRoute(false);
        }
        binding.mapContainer.post(() -> {
            if (runScreenVisible) {
                requestMapFit();
            }
        });
        if (!drawMode) {
            binding.drawBar.setVisibility(View.GONE);
        }
        binding.textTeleportHint.setVisibility(teleportArmed ? View.VISIBLE : View.GONE);
        updateRunUi();
    }

    private void onGoClicked() {
        if (snapshot.isActive() && !snapshot.singlePoint()) {
            if (claimOwnSession) {
                showRunScreen();
            } else {
                toast(getString(R.string.campus_toast_existing));
            }
            return;
        }
        // 悬浮摇杆留下的单点保持不算跑圈任务：照常进入跑圈页，开始后由服务端就地替换。
        showRunScreen();
        toast(getString(route == null
                ? R.string.campus_route_required
                : R.string.campus_click_start_hint));
    }

    private void handleBack() {
        if (binding.doneOverlay.getVisibility() == View.VISIBLE) {
            binding.doneOverlay.setVisibility(View.GONE);
            showSetupScreen();
            return;
        }
        if (drawMode) {
            exitDrawMode();
            showSetupScreen();
            return;
        }
        if (runScreenVisible) {
            if (claimOwnSession && snapshot.isActive()) {
                moveTaskToBack(true);
                toast(getString(R.string.campus_background_running));
                return;
            }
            showSetupScreen();
            return;
        }
        finish();
    }

    // ---------------------------------------------------------------- 开始 / 控制

    private void onMainControl() {
        if (drawMode) {
            return;
        }
        RunState state = snapshot.state();
        if (claimOwnSession) {
            if (state == RunState.STARTING) {
                return;
            }
            if (state == RunState.RUNNING) {
                if (snapshot.singlePoint()) {
                    requestStop();
                } else {
                    SimulationService.pause(this);
                }
                return;
            }
            if (state == RunState.PAUSED) {
                SimulationService.resume(this);
                return;
            }
        }
        if (snapshot.isActive() && !snapshot.singlePoint()) {
            toast(getString(R.string.campus_toast_existing));
            return;
        }
        startRouteFlow();
    }

    private void startRouteFlow() {
        binding.doneOverlay.setVisibility(View.GONE);
        if (snapshot.isActive() && !snapshot.singlePoint()) {
            if (claimOwnSession) {
                showRunScreen();
            } else {
                toast(getString(R.string.campus_toast_existing));
            }
            return;
        }
        CampusRoute planRoute = route;
        if (planRoute == null) {
            toast(getString(R.string.campus_route_required));
            return;
        }
        double speedKmh = 3600d / paceSeconds;
        requestPermissionsThenStart(planRoute, speedKmh, laps);
    }

    private void requestPermissionsThenStart(CampusRoute planRoute, double speedKmh, int loops) {
        List<String> needed = neededPermissions();
        if (needed.isEmpty()) {
            ensureMockReadyForRoute(planRoute, speedKmh, loops);
            return;
        }
        pendingStart = new PendingStart(planRoute, speedKmh, loops);
        toast(getString(R.string.campus_permission_needed));
        permissionLauncher.launch(needed.toArray(new String[0]));
    }

    /**
     * 开始跑圈前的模拟位置检查。
     *
     * <p>已有悬浮摇杆的单点会话时不能做探测：探测会临时移除 gps 测试 provider，把正在使用它的
     * 单点会话直接打失败。此时直接启动，服务端会先结束单点并沿用新的 provider 会话开始跑圈。
     */
    private void ensureMockReadyForRoute(CampusRoute planRoute, double speedKmh, int loops) {
        if (snapshot.isActive() && snapshot.singlePoint()) {
            DiagnosticLog.info("已有单点会话：跳过模拟位置探测，直接切换到跑圈");
            startRouteNow(planRoute, speedKmh, loops);
            return;
        }
        ensureMockReady(() -> startRouteNow(planRoute, speedKmh, loops));
    }

    private void startRouteNow(CampusRoute planRoute, double speedKmh, int loops) {
        if (snapshot.isActive() && !snapshot.singlePoint()) {
            toast(getString(R.string.campus_toast_existing));
            return;
        }
        route = planRoute;

        CompiledRoute compiled;
        try {
            long seed = System.nanoTime();
            compiled = RouteCompiler.compile(
                    route.buildGeoPoints(varyEnabled, seed), EndBehavior.LOOP);
        } catch (InvalidRouteException e) {
            toast(e.getMessage() == null ? getString(R.string.campus_route_invalid) : e.getMessage());
            return;
        }
        RunConfig config = new RunConfig(speedKmh, loops);
        RunRequest request = RunRequest.create(route.name(), compiled, config);

        activeLapMeters = compiled.lapLengthMeters();
        baseSpeedKmh = speedKmh;
        naturalPhase1 = Math.random() * Math.PI * 2d;
        naturalPhase2 = Math.random() * Math.PI * 2d;
        teleportTarget = null;
        deviceLocation = null;
        activeRunId = request.runId();
        claimOwnSession = true;
        stopRequested = false;
        autoPauseNotifiedRunId = "";
        prefs.edit()
                .putBoolean(KEY_OWNED, true)
                .putString(KEY_ACTIVE_RUN_ID, activeRunId)
                .putFloat(KEY_ACTIVE_LAP, (float) activeLapMeters)
                .apply();

        SimulationService.start(this, request);
        showRunScreen();
        if (mapHost != null) {
            mapHost.renderRunner(null);
        }
        binding.buttonRunMain.setText(getString(R.string.campus_starting));
        binding.buttonRunMain.setEnabled(false);
        toast(getString(R.string.campus_toast_starting));
    }

    private void requestStop() {
        if (!claimOwnSession || !snapshot.isActive()) {
            return;
        }
        stopRequested = true;
        SimulationService.stop(this);
        toast(getString(R.string.campus_stopping));
    }

    // ---------------------------------------------------------------- 传送 / 定位

    /** 右下角按钮：先选择「传送」或「定位当前位置」。 */
    private void showMapActionsMenu(View anchor) {
        if (drawMode) {
            toast(getString(R.string.campus_draw_finish_first));
            return;
        }
        if (teleportArmed) {
            // 传送已待命时再点一次就是取消，不再弹菜单。
            teleportArmed = false;
            binding.textTeleportHint.setVisibility(View.GONE);
            return;
        }
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenu().add(0, 1, 0, getString(R.string.campus_menu_teleport));
        popup.getMenu().add(0, 2, 1, getString(R.string.campus_menu_locate));
        popup.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) {
                onTeleportPressed();
            } else if (item.getItemId() == 2) {
                locateCurrentPosition();
            }
            return true;
        });
        popup.show();
    }

    /**
     * 定位当前位置。
     *
     * <p>跑圈/单点保持中，App 的「当前位置」就是跑者或传送点，先把镜头移回去；
     * 空闲时改为读取设备真实定位（与旧版「定位到当前位置」一致），读不到才退回路线起点。
     */
    private void locateCurrentPosition() {
        if (mapHost == null) {
            return;
        }
        if (claimOwnSession && snapshot.hasPosition()
                && Double.isFinite(snapshot.latitude()) && Double.isFinite(snapshot.longitude())) {
            mapHost.focusOn(new GeoPoint(snapshot.latitude(), snapshot.longitude()));
            toast(getString(R.string.campus_locate_done));
            return;
        }
        if (teleportTarget != null) {
            mapHost.focusOn(teleportTarget);
            toast(getString(R.string.campus_locate_done));
            return;
        }
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            pendingLocate = true;
            toast(getString(R.string.campus_locate_permission));
            permissionLauncher.launch(new String[]{Manifest.permission.ACCESS_FINE_LOCATION});
            return;
        }
        locateByDevice();
    }

    /** 先读系统缓存的最近位置；没有就请求一次实时定位，仍拿不到再退回路线起点。 */
    @SuppressWarnings("deprecation")
    private void locateByDevice() {
        if (mapHost == null) {
            return;
        }
        LocationManager manager = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (manager == null || !manager.isLocationEnabled()) {
            toast(getString(R.string.campus_locate_off));
            locateFallback();
            return;
        }
        SystemLocationReader.Probe best = null;
        for (SystemLocationReader.Probe probe : SystemLocationReader.readAll(this)) {
            // 跳过模拟来源：本应用自己跑圈时提交的假位置不能被当成「设备当前位置」。
            if (!probe.hasFix() || probe.markedMock()) {
                continue;
            }
            if (best == null || probe.ageMillis() < best.ageMillis()) {
                best = probe;
            }
        }
        if (best != null) {
            showDeviceLocated(best.point(), best.provider);
            return;
        }
        toast(getString(R.string.campus_locate_searching));
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                manager.getCurrentLocation(LocationManager.GPS_PROVIDER, null, getMainExecutor(),
                        location -> {
                            if (location == null) {
                                locateFallback();
                            } else {
                                showDeviceLocated(new GeoPoint(location.getLatitude(),
                                        location.getLongitude()), location.getProvider());
                            }
                        });
            } else {
                // Android 10 没有 getCurrentLocation，用一次单次请求代替。
                manager.requestSingleUpdate(LocationManager.GPS_PROVIDER,
                        new LocationListener() {
                            @Override
                            public void onLocationChanged(@NonNull Location location) {
                                showDeviceLocated(new GeoPoint(location.getLatitude(),
                                        location.getLongitude()), location.getProvider());
                            }

                            @Override
                            public void onStatusChanged(String provider, int status, Bundle extras) {
                            }

                            @Override
                            public void onProviderEnabled(@NonNull String provider) {
                            }

                            @Override
                            public void onProviderDisabled(@NonNull String provider) {
                                locateFallback();
                            }
                        }, getMainLooper());
            }
        } catch (SecurityException | IllegalArgumentException error) {
            DiagnosticLog.warn("定位：请求设备位置失败 " + error);
            locateFallback();
        }
    }

    private void showDeviceLocated(GeoPoint point, String provider) {
        if (mapHost == null) {
            return;
        }
        deviceLocation = point;
        mapHost.renderRunner(point);
        mapHost.focusOn(point);
        binding.textCoordinates.setText(CampusFormat.coordinates(point.latitude(), point.longitude()));
        toast(getString(R.string.campus_locate_device));
        DiagnosticLog.info("定位到设备当前位置：" + point.format() + " via " + provider);
    }

    /** 设备定位不可用时退回路线起点，让「定位」至少回到本页可解释的位置。 */
    private void locateFallback() {
        GeoPoint start = route == null ? null : route.startGeo();
        if (start == null || mapHost == null) {
            toast(getString(R.string.campus_locate_missing));
            return;
        }
        deviceLocation = null;
        mapHost.focusOn(start);
        toast(getString(R.string.campus_locate_fallback));
    }

    private void onTeleportPressed() {
        if (drawMode) {
            toast(getString(R.string.campus_draw_finish_first));
            return;
        }
        if (teleportArmed) {
            teleportArmed = false;
            binding.textTeleportHint.setVisibility(View.GONE);
            return;
        }
        if (snapshot.isActive() && !claimOwnSession) {
            toast(getString(R.string.campus_toast_existing));
            return;
        }
        teleportArmed = true;
        binding.textTeleportHint.setVisibility(View.VISIBLE);
        toast(getString(R.string.campus_teleport_hint));
    }

    private void performTeleport(GeoPoint target) {
        teleportArmed = false;
        binding.textTeleportHint.setVisibility(View.GONE);
        teleportTarget = target;
        if (mapHost != null) {
            mapHost.renderRunner(target);
            mapHost.focusOn(target);
        }

        if (snapshot.isActive()) {
            if (!claimOwnSession) {
                toast(getString(R.string.campus_toast_existing));
                return;
            }
            boolean routeMode = !snapshot.singlePoint();
            SimulationService.teleportTo(this, target);
            toast(getString(routeMode ? R.string.campus_teleport_route_ended
                    : R.string.campus_teleport_started_short));
            return;
        }

        List<String> needed = neededPermissions();
        if (needed.isEmpty()) {
            ensureMockReady(() -> beginSinglePoint(target));
            return;
        }
        pendingTeleport = target;
        toast(getString(R.string.campus_permission_needed));
        permissionLauncher.launch(needed.toArray(new String[0]));
    }

    private void beginSinglePoint(GeoPoint target) {
        if (snapshot.isActive()) {
            toast(getString(R.string.campus_toast_existing));
            return;
        }
        RunRequest request = RunRequest.createSinglePoint(target);
        teleportTarget = target;
        deviceLocation = null;
        activeLapMeters = 0d;
        activeRunId = request.runId();
        claimOwnSession = true;
        stopRequested = false;
        autoPauseNotifiedRunId = "";
        prefs.edit()
                .putBoolean(KEY_OWNED, true)
                .putString(KEY_ACTIVE_RUN_ID, activeRunId)
                .putFloat(KEY_ACTIVE_LAP, 0f)
                .apply();
        SimulationService.start(this, request);
        showRunScreen();
        if (mapHost != null) {
            mapHost.renderRunner(target);
            mapHost.focusOn(target);
        }
        toast(getString(R.string.campus_teleport_hold_started));
    }

    // ---------------------------------------------------------------- 权限 / 模拟位置检查

    private List<String> neededPermissions() {
        List<String> needed = new ArrayList<>();
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && !hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        return needed;
    }

    private void onPermissionResult(java.util.Map<String, Boolean> result) {
        boolean fineGranted = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION);
        GeoPoint teleport = pendingTeleport;
        PendingStart start = pendingStart;
        boolean locate = pendingLocate;
        pendingTeleport = null;
        pendingStart = null;
        pendingLocate = false;
        if (!fineGranted) {
            toast(getString(locate ? R.string.campus_locate_permission
                    : R.string.campus_permission_missing));
            if (locate) {
                locateFallback();
            }
            return;
        }
        if (teleport != null) {
            ensureMockReady(() -> beginSinglePoint(teleport));
            return;
        }
        if (start != null) {
            ensureMockReadyForRoute(start.route, start.speedKmh, start.loops);
            return;
        }
        if (locate) {
            locateByDevice();
            return;
        }
        toast(getString(R.string.campus_toast_pending));
    }

    private boolean hasPermission(String permission) {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED;
    }

    private void ensureMockReady(Runnable onReady) {
        SettingsInspector.MockProbeResult probe = SettingsInspector.probeMockProvider(this);
        if (probe == SettingsInspector.MockProbeResult.ALLOWED) {
            onReady.run();
            return;
        }
        if (probe == SettingsInspector.MockProbeResult.ALLOWED_AFTER_CLEANUP) {
            onReady.run();
            return;
        }
        String message;
        if (probe == SettingsInspector.MockProbeResult.DENIED) {
            message = "系统拒绝了模拟位置请求：请到「开发者选项 → 选择模拟位置信息应用」里选中"
                    + "「路线工坊」后重试。";
        } else if (probe == SettingsInspector.MockProbeResult.PROVIDER_UNSUPPORTED) {
            message = "当前设备不支持添加测试 provider，无法伪造位置。";
        } else {
            message = "无法访问定位服务，请确认系统定位开关已打开。";
        }
        new AlertDialog.Builder(this)
                .setTitle("还不能开始校园快跑")
                .setMessage(message)
                .setPositiveButton("去设置检查", (dialog, which) ->
                        startActivity(new Intent(this, SettingsCheckActivity.class)))
                .setNegativeButton("取消", null)
                .show();
    }

    // ---------------------------------------------------------------- 状态订阅

    private void onSnapshot(RunSnapshot next) {
        RunSnapshot previous = snapshot;
        snapshot = next;
        boolean ours = claimOwnSession || (!activeRunId.isEmpty() && activeRunId.equals(next.runId()));
        if (ours && next.isActive()) {
            claimOwnSession = true;
            if (!next.runId().equals(activeRunId)) {
                activeRunId = next.runId();
                prefs.edit()
                        .putBoolean(KEY_OWNED, true)
                        .putString(KEY_ACTIVE_RUN_ID, activeRunId)
                        .apply();
            }
            if (!runScreenVisible) {
                showRunScreen();
            }
        }
        if (ours && next.state() == RunState.PAUSED && next.errorCode() != null
                && !next.runId().equals(autoPauseNotifiedRunId)) {
            autoPauseNotifiedRunId = next.runId();
            if (next.errorMessage() != null && !next.errorMessage().isEmpty()) {
                toast(next.errorMessage());
            }
        }
        if (runScreenVisible) {
            updateRunUi();
        }
        syncNaturalTicker();
        if (ours && isTerminal(next.state()) && !isTerminal(previous.state())) {
            handleTerminal(previous, next);
        }
    }

    private void handleTerminal(RunSnapshot previous, RunSnapshot next) {
        naturalRunning = false;
        handler.removeCallbacks(naturalTicker);
        boolean wasSinglePoint = next.singlePoint() || previous.singlePoint();
        claimOwnSession = false;
        activeRunId = "";
        prefs.edit()
                .putBoolean(KEY_OWNED, false)
                .remove(KEY_ACTIVE_RUN_ID)
                .apply();

        if (pendingRouteSwitch != null) {
            CampusRoute target = pendingRouteSwitch;
            pendingRouteSwitch = null;
            applyRoute(target);
            showSetupScreen();
            return;
        }

        if (next.state() == RunState.COMPLETED) {
            showDoneOverlay(next, R.string.campus_done_completed, null);
            return;
        }
        if (next.state() == RunState.ERROR) {
            showDoneOverlay(next, R.string.campus_done_error, next.errorMessage());
            return;
        }
        if (wasSinglePoint) {
            showSetupScreen();
            toast(getString(R.string.campus_hold_stopped));
            return;
        }
        showDoneOverlay(next, R.string.campus_done_stopped, null);
    }

    private boolean isTerminal(RunState state) {
        return state == RunState.COMPLETED || state == RunState.STOPPED || state == RunState.ERROR;
    }

    private void syncNaturalTicker() {
        boolean should = naturalEnabled && claimOwnSession
                && snapshot.state() == RunState.RUNNING && !snapshot.singlePoint();
        if (should && !naturalRunning) {
            naturalRunning = true;
            handler.postDelayed(naturalTicker, 900L);
        } else if (!should && naturalRunning) {
            naturalRunning = false;
            handler.removeCallbacks(naturalTicker);
        }
    }

    // ---------------------------------------------------------------- 跑圈页 UI

    private void updateRunUi() {
        boolean ours = claimOwnSession;
        boolean active = ours && snapshot.isActive();
        boolean single = active && snapshot.singlePoint();
        double lapMeters = ours && activeLapMeters > 0d ? activeLapMeters
                : route == null ? 0d : route.lapMeters();

        // 只有本页面的任务才显示已有数据，避免上一次跑完的数字残留到下一次 GO 的预览里。
        double distance = (ours && !single) ? snapshot.distanceMeters() : 0d;
        binding.textDistance.setText(CampusFormat.kilometers(distance));
        boolean showElapsed = active || (ours && snapshot.state() == RunState.COMPLETED);
        binding.textRunTime.setText(CampusFormat.time(showElapsed ? snapshot.activeElapsedMs() : 0L));

        double pace = 0d;
        if (!single && ours) {
            if (snapshot.state() == RunState.RUNNING && snapshot.speedKmh() > 0.1d) {
                pace = 3600d / snapshot.speedKmh();
            } else {
                pace = CampusFormat.averagePaceSeconds(snapshot.activeElapsedMs(), snapshot.distanceMeters());
            }
        }
        boolean paceValid = pace > 0d;
        binding.textPace.setText(paceValid ? CampusFormat.pace(pace) : "—");
        binding.textPaceUnit.setVisibility(paceValid ? View.VISIBLE : View.GONE);

        if (route != null) {
            if (single) {
                binding.textRouteInfo.setText(getString(R.string.campus_single_route_info, route.name()));
                binding.textLapInfo.setText(getString(R.string.campus_single_lap));
            } else {
                binding.textRouteInfo.setText(getString(R.string.campus_route_info,
                        route.name(), CampusFormat.lapMeters(lapMeters)));
                int target = snapshot.targetLoops() > 0 ? snapshot.targetLoops() : laps;
                if (ours && snapshot.state() == RunState.COMPLETED) {
                    binding.textLapInfo.setText(
                            getString(R.string.campus_lap_info_completed, snapshot.completedLoops()));
                } else if (ours && snapshot.state() == RunState.STARTING) {
                    binding.textLapInfo.setText(getString(R.string.campus_starting));
                } else {
                    int current = Math.min(snapshot.completedLoops() + 1, target);
                    binding.textLapInfo.setText(getString(R.string.campus_lap_info, current, target));
                }
            }
        } else {
            binding.textRouteInfo.setText(getString(R.string.campus_route_none));
            binding.textLapInfo.setText(getString(R.string.placeholder));
        }

        if (snapshot.hasPosition() && ours
                && Double.isFinite(snapshot.latitude()) && Double.isFinite(snapshot.longitude())) {
            binding.textCoordinates.setText(
                    CampusFormat.coordinates(snapshot.latitude(), snapshot.longitude()));
        } else if (!active && deviceLocation != null) {
            binding.textCoordinates.setText(
                    CampusFormat.coordinates(deviceLocation.latitude(), deviceLocation.longitude()));
        } else if (route != null) {
            GeoPoint start = route.startGeo();
            binding.textCoordinates.setText(CampusFormat.coordinates(start.latitude(), start.longitude()));
        } else {
            binding.textCoordinates.setText(getString(R.string.placeholder));
        }

        if (mapHost != null) {
            if (!drawMode) {
                syncMapRoute(false);
                // STARTING 阶段的快照还没有经纬度（NaN），必须先检查有限性再换算。
                if (ours && snapshot.hasPosition()
                        && Double.isFinite(snapshot.latitude()) && Double.isFinite(snapshot.longitude())) {
                    mapHost.renderRunner(new GeoPoint(snapshot.latitude(), snapshot.longitude()));
                } else if (!active) {
                    mapHost.renderRunner(teleportTarget != null ? teleportTarget : deviceLocation);
                }
            }
        }

        boolean starting = ours && snapshot.state() == RunState.STARTING;
        binding.buttonRunMain.setEnabled(!drawMode && !starting && (route != null || active));
        if (starting) {
            binding.buttonRunMain.setText(getString(R.string.campus_starting));
            tintMainButton(R.color.campus_track);
        } else if (single) {
            binding.buttonRunMain.setText(getString(R.string.campus_action_stop));
            tintMainButton(R.color.campus_orange);
        } else if (ours && snapshot.state() == RunState.RUNNING) {
            binding.buttonRunMain.setText(getString(R.string.campus_action_pause));
            tintMainButton(R.color.campus_orange);
        } else if (ours && snapshot.state() == RunState.PAUSED) {
            binding.buttonRunMain.setText(getString(R.string.campus_action_resume));
            tintMainButton(R.color.campus_mint);
        } else {
            binding.buttonRunMain.setText(getString(R.string.campus_action_start));
            tintMainButton(R.color.campus_mint);
        }

        boolean showEnd = active && !single;
        binding.buttonEndRun.setVisibility(showEnd ? View.VISIBLE : View.GONE);
        binding.buttonGoal.setEnabled(!drawMode);
        binding.buttonTeleport.setEnabled(!drawMode);
        binding.buttonGoal.setAlpha(drawMode ? 0.45f : 1f);
        binding.buttonTeleport.setAlpha(drawMode ? 0.45f : 1f);
        binding.buttonRunSport.setEnabled(!active);
    }

    private void tintMainButton(int colorRes) {
        binding.buttonRunMain.setBackgroundTintList(
                ColorStateList.valueOf(getColor(colorRes)));
    }

    private void showDoneOverlay(RunSnapshot result, int titleRes, @Nullable String message) {
        binding.doneOverlay.setVisibility(View.VISIBLE);
        binding.textDoneTitle.setText(getString(titleRes));
        if (message == null || message.isEmpty()) {
            binding.textDoneMessage.setVisibility(View.GONE);
        } else {
            binding.textDoneMessage.setText(message);
            binding.textDoneMessage.setVisibility(View.VISIBLE);
        }
        binding.textDoneDistance.setText(
                getString(R.string.campus_done_distance_value, CampusFormat.kilometers(result.distanceMeters())));
        binding.textDoneTime.setText(CampusFormat.time(result.activeElapsedMs()));
        double avgPace = CampusFormat.averagePaceSeconds(
                result.activeElapsedMs(), result.distanceMeters());
        binding.textDonePace.setText(avgPace > 0d ? CampusFormat.paceWithUnit(avgPace) : "—");
    }

    // ---------------------------------------------------------------- 路线选择与绘制

    private void showRouteSheet() {
        if (drawMode) {
            toast(getString(R.string.campus_draw_finish_first));
            return;
        }
        DialogCampusRoutesBinding sheet = DialogCampusRoutesBinding.inflate(getLayoutInflater());
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        sheet.routeList.removeAllViews();
        sheet.textNoRoutes.setVisibility(customRoute == null ? View.VISIBLE : View.GONE);
        if (customRoute != null) {
            addRouteRow(sheet.routeList, customRoute, dialog);
        }
        sheet.buttonDrawRoute.setOnClickListener(v -> {
            dialog.dismiss();
            if (snapshot.isActive()) {
                toast(getString(R.string.campus_draw_stop_first));
                return;
            }
            if (customRoute != null) {
                new AlertDialog.Builder(this)
                        .setTitle(R.string.campus_draw_overwrite_title)
                        .setMessage(R.string.campus_draw_overwrite_message)
                        .setPositiveButton(R.string.campus_draw_overwrite_confirm,
                                (d, w) -> enterDrawMode())
                        .setNegativeButton(R.string.campus_cancel, null)
                        .show();
                return;
            }
            enterDrawMode();
        });
        sheet.buttonCloseRoutes.setOnClickListener(v -> dialog.dismiss());
        dialog.setContentView(sheet.getRoot());
        dialog.show();
    }

    private void addRouteRow(LinearLayout list, CampusRoute candidate, BottomSheetDialog dialog) {
        boolean selected = route != null && route.id().equals(candidate.id());
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(6), dp(14), dp(6), dp(14));
        TypedValue ripple = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
        row.setBackgroundResource(ripple.resourceId);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textsParams =
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        texts.setLayoutParams(textsParams);

        TextView title = new TextView(this);
        title.setText(candidate.name());
        title.setTextColor(getColor(R.color.campus_ink));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        texts.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText(getString(R.string.campus_route_lap_only,
                CampusFormat.lapMeters(candidate.lapMeters())));
        subtitle.setTextColor(getColor(R.color.campus_muted));
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        texts.addView(subtitle);
        row.addView(texts);

        TextView check = new TextView(this);
        check.setText(selected ? "✓" : "");
        check.setContentDescription(selected ? getString(R.string.campus_row_check) : null);
        check.setTextColor(getColor(R.color.campus_mint_dark));
        check.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f);
        check.setTypeface(check.getTypeface(), android.graphics.Typeface.BOLD);
        check.setPadding(dp(10), 0, dp(4), 0);
        row.addView(check);

        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.topMargin = dp(2);
        row.setLayoutParams(rowParams);
        row.setOnClickListener(v -> {
            dialog.dismiss();
            selectRoute(candidate);
        });
        list.addView(row);
    }

    private void selectRoute(CampusRoute candidate) {
        if (route != null && route.id().equals(candidate.id())) {
            return;
        }
        if (snapshot.isActive()) {
            if (!claimOwnSession) {
                toast(getString(R.string.campus_toast_existing));
                return;
            }
            String currentName = route == null ? getString(R.string.placeholder) : route.name();
            new AlertDialog.Builder(this)
                    .setTitle(R.string.campus_sheet_switch_title)
                    .setMessage(getString(R.string.campus_sheet_switch_message,
                            currentName, candidate.name()))
                    .setPositiveButton(R.string.campus_sheet_switch_confirm, (dialog, which) -> {
                        pendingRouteSwitch = candidate;
                        stopRequested = false;
                        SimulationService.stop(this);
                        toast(getString(R.string.campus_stopping));
                    })
                    .setNegativeButton(R.string.campus_cancel, null)
                    .show();
            return;
        }
        applyRoute(candidate);
    }

    private void applyRoute(CampusRoute candidate) {
        route = candidate;
        teleportTarget = null;
        activeLapMeters = candidate.lapMeters();
        binding.textRouteSummary.setText(routeSummary(candidate));
        renderedRoute = null;
        if (mapHost != null) {
            mapHost.renderRunner(null);
            if (drawMode) {
                renderMapDraft();
            } else {
                syncMapRoute(true);
            }
        }
        if (runScreenVisible) {
            requestMapFit();
            updateRunUi();
        }
    }

    private void enterDrawMode() {
        if (snapshot.isActive()) {
            toast(getString(R.string.campus_draw_stop_first));
            return;
        }
        if (!runScreenVisible) {
            showRunScreen();
        }
        drawMode = true;
        teleportArmed = false;
        draftPoints.clear();
        binding.textTeleportHint.setVisibility(View.GONE);
        binding.drawBar.setVisibility(View.VISIBLE);
        updateDrawBanner();
        renderedRoute = null;
        renderMapDraft();
        if (mapHost != null) {
            mapHost.renderRunner(null);
        }
        requestMapFit();
        updateRunUi();
    }

    private void exitDrawMode() {
        drawMode = false;
        draftPoints.clear();
        binding.drawBar.setVisibility(View.GONE);
        renderedRoute = null;
        syncMapRoute(true);
        updateRunUi();
    }

    private void addDraftPoint(CampusPoint point) {
        if (draftPoints.size() >= DRAFT_MAX) {
            toast(getString(R.string.campus_draw_too_many));
            return;
        }
        draftPoints.add(point);
        renderMapDraft();
        updateDrawBanner();
    }

    private void updateDrawBanner() {
        binding.textDrawBanner.setText(getString(R.string.campus_draw_count, draftPoints.size()));
    }

    private void finishDrawMode() {
        if (draftPoints.size() < DRAFT_MIN) {
            toast(getString(R.string.campus_draw_too_few));
            return;
        }
        CampusRoute custom = CampusRoute.custom(ROUTE_CUSTOM_ID,
                getString(R.string.campus_custom_name), new ArrayList<>(draftPoints));
        if (custom.lapMeters() < CUSTOM_MIN_METERS) {
            toast(getString(R.string.campus_draw_too_short));
            return;
        }
        if (custom.lapMeters() > CUSTOM_MAX_METERS) {
            toast(getString(R.string.campus_draw_too_long));
            return;
        }
        customRoute = custom;
        saveCustomRoute(custom);
        exitDrawMode();
        applyRoute(custom);
        showSetupScreen();
        toast(getString(R.string.campus_draw_saved, CampusFormat.lapMeters(custom.lapMeters())));
    }

    // ---------------------------------------------------------------- 收尾

    @Override
    protected void onStart() {
        super.onStart();
        if (mapHost != null) {
            mapHost.onStart();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mapHost != null) {
            mapHost.onResume();
        }
        if (runScreenVisible) {
            updateRunUi();
        }
    }

    @Override
    protected void onPause() {
        if (mapHost != null) {
            mapHost.onPause();
        }
        super.onPause();
    }

    @Override
    protected void onStop() {
        if (mapHost != null) {
            mapHost.onStop();
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        naturalRunning = false;
        handler.removeCallbacks(naturalTicker);
        if (mapHost != null) {
            mapHost.onDestroy();
            mapHost = null;
        }
        super.onDestroy();
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        if (mapHost != null) {
            mapHost.onLowMemory();
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (mapHost != null) {
            mapHost.onSaveInstanceState(outState);
        }
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class PendingStart {
        final CampusRoute route;
        final double speedKmh;
        final int loops;

        PendingStart(CampusRoute route, double speedKmh, int loops) {
            this.route = route;
            this.speedKmh = speedKmh;
            this.loops = loops;
        }
    }
}
