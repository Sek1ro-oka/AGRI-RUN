package org.localroute.app.ui;

import android.Manifest;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import androidx.appcompat.app.AlertDialog;
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
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.RadioGroup;
import android.widget.RadioButton;
import android.widget.SeekBar;
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
import org.localroute.app.campus.CampusPresets;
import org.localroute.app.campus.CampusRoute;
import org.localroute.app.campus.CampusRouteCodec;
import org.localroute.app.campus.PresetConflicts;
import org.localroute.app.campus.PresetUnlock;
import org.localroute.app.databinding.ActivityCampusRunBinding;
import org.localroute.app.databinding.DialogCampusRoutesBinding;
import org.localroute.app.diagnostics.DiagnosticLog;
import org.localroute.app.diagnostics.SystemLocationReader;
import org.localroute.app.map.AmapRouteMapView;
import org.localroute.app.map.MapBackendPolicy;
import org.localroute.app.map.RouteMapController;
import org.localroute.app.map.RouteMapHost;
import org.localroute.app.overlay.OverlayHost;
import org.localroute.app.overlay.OverlayPanel;
import org.localroute.app.run.RunRequest;
import org.localroute.app.run.RunSnapshot;
import org.localroute.app.run.SimulationStateRepo;
import org.localroute.app.service.SimulationService;
import org.localroute.app.settings.SettingsInspector;
import org.localroute.app.settings.RunInputs;
import org.localroute.app.settings.RunSpeedPreferences;
import org.localroute.app.settings.SpeedPresets;
import org.localroute.app.storage.RouteRepository;
import org.localroute.core.CompiledRoute;
import org.localroute.core.EndBehavior;
import org.localroute.core.GeoMath;
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

/**
 * 校园快跑：虚拟定位跑圈。
 *
 * <p>地图主页负责路线预览、精确参数输入与运行控制；运行设置收纳自然变速、
 * 路线扰动、悬浮窗和环境检查。
 * 所有实际定位提交都走 {@link SimulationService}，本页面不自行伪造位置。
 */
public final class CampusRunActivity extends AppCompatActivity {

    /**
     * 悬浮窗长按结束时带上的标记：直接把本次运动结果显示在跑圈地图上，
     * 不再弹「再来一次 / 返回校园快跑页」。
     */
    public static final String EXTRA_SHOW_RUN_RESULT =
            "org.localroute.app.extra.SHOW_RUN_RESULT";

    private static final String PREFS = "campus_run";
    private static final String KEY_SPORT = "sport";
    private static final String KEY_PACE = "pace_seconds";
    private static final String KEY_LAPS = "laps";
    private static final String KEY_NATURAL = "natural_speed";
    /** 新键：0..1 的扰动程度；旧版的是布尔 {@code vary_path}，首次读取时迁移。 */
    private static final String KEY_VARY_STRENGTH = "vary_strength";
    private static final String KEY_VARY_LEGACY = "vary_path";
    private static final String KEY_CUSTOM_POINTS = "custom_points";
    private static final String KEY_CUSTOM_ROUTES = "custom_routes";
    private static final String KEY_SELECTED_ROUTE = "selected_route_id";
    /** 预设路线几何版本：1 = 0.7.0 初版；2 = 弯道平滑并统一方向。 */
    private static final String KEY_PRESET_REVISION = "preset_revision";
    private static final int PRESET_REVISION = 2;
    /**
     * 预设清单版本：3 = 增加「衣锦」。老用户升级时只补这一条新增预设，
     * 不补回已经删掉的 A操 / B操。
     */
    private static final String KEY_PRESET_CATALOG = "preset_catalog_revision";
    private static final int PRESET_CATALOG_REVISION = 3;
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

    /** 跑者标记在两次快照之间的滑动时长；略小于 500ms 的 tick 间隔，形成连续运动。 */
    private static final long RUNNER_ANIM_MS = 480L;
    /** 单次位置跳变超过该距离时不再滑动，直接落点（传送、回前台、自动暂停恢复等）。 */
    private static final double RUNNER_SNAP_METERS = 30d;

    /** 旧版单路线存储的迁移 ID；新路线使用 {@link RouteRepository#newRouteId()}。 */
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
    private final List<CampusRoute> customRoutes = new ArrayList<>();
    /** 正在重新绘制的路线 ID；为 null 表示在新增路线。 */
    @Nullable
    private String editingRouteId;
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
    /** 传送/定位后要居中的目标；地图或页面未就绪时等就绪回调再移动镜头。 */
    @Nullable
    private GeoPoint pendingCameraFocus;

    private String sport = SPORT_RUN;
    private int paceSeconds = DEFAULT_PACE_RUN;
    private double configuredSpeedKmh = 9d;
    private SpeedPresets speedPresets;
    private int laps = 5;
    private boolean naturalEnabled = true;
    /** 路线扰动程度 0..1，0 表示关闭。 */
    private double varyStrength = 0.5d;
    /** 程序化设置扰动滑条时避免重复持久化。 */
    private boolean varySeekGuard;

    /** 程序化勾选悬浮窗开关时避免再次触发监听。 */
    private boolean overlaySwitchGuard;
    private boolean overlayPermissionPrompted;
    /** 用户想打开悬浮窗但还在等系统授权页返回。 */
    private boolean pendingOverlayEnable;

    private boolean runScreenVisible;
    private boolean drawMode;
    private final List<CampusPoint> draftPoints = new ArrayList<>();
    private boolean teleportArmed;

    /** 激活码导入中遇到重名、等页面可见后逐个询问的预设。 */
    private final List<PresetConflict> pendingPresetConflicts = new ArrayList<>();
    private boolean presetConflictDialogShowing;

    private boolean claimOwnSession;
    private String activeRunId = "";
    private double activeLapMeters = 400d;
    private double baseSpeedKmh = 9d;
    private double naturalPhase1;
    private double naturalPhase2;
    private boolean naturalRunning;
    private boolean stopRequested;
    private String autoPauseNotifiedRunId = "";

    /** 跑者标记的滑动动画与当前显示位置。 */
    @Nullable
    private ValueAnimator runnerAnimator;
    @Nullable
    private GeoPoint runnerShown;
    /** 从悬浮窗长按结束进入：本次终止结果直接显示在地图上，不弹完成弹窗。 */
    private boolean resultRequested;
    /** 当前跑圈页正在展示刚结束的本次结果（即使会话所有权已经释放）。 */
    private boolean showResult;
    /** 已确认"结束跑圈并传送"，等待服务快照确认；成功提示只在确认后出现。 */
    private boolean awaitingTeleportAck;
    private static final long TELEPORT_ACK_TIMEOUT_MS = 6000L;
    private final Runnable teleportAckTimeout = () -> {
        if (!awaitingTeleportAck) {
            return;
        }
        awaitingTeleportAck = false;
        toast(getString(R.string.campus_teleport_no_ack));
    };

    private final Runnable naturalTicker = new Runnable() {
        @Override
        public void run() {
            if (!naturalRunning) {
                return;
            }
            RunSnapshot current = SimulationStateRepo.get().current();
            if (naturalEnabled && claimOwnSession && current.state() == RunState.RUNNING
                    && !current.singlePoint()) {
                baseSpeedKmh = RunSpeedPreferences.target(CampusRunActivity.this, baseSpeedKmh);
                double elapsed = current.activeElapsedMs();
                double factor = 1d
                        + 0.035d * Math.sin(elapsed / 38000d + naturalPhase1)
                        + 0.015d * Math.sin(elapsed / 9500d + naturalPhase2);
                double speed = Math.max(SpeedPresets.MIN_KMH,
                        Math.min(SpeedPresets.MAX_KMH, baseSpeedKmh * factor));
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
        controller.setAppearanceLightStatusBars(getResources().getBoolean(R.bool.fm_light_bars));
        controller.setAppearanceLightNavigationBars(getResources().getBoolean(R.bool.fm_light_bars));

        permissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(), this::onPermissionResult);

        wireViews();
        setupMap();
        renderSport();
        renderSetupValues();

        RunSnapshot current = SimulationStateRepo.get().current();
        snapshot = current;
        resultRequested = getIntent().getBooleanExtra(EXTRA_SHOW_RUN_RESULT, false);
        // 用掉即清：之后重建或再次进入本页时不再重复按「结束结果」处理。
        getIntent().removeExtra(EXTRA_SHOW_RUN_RESULT);
        boolean resumed = restoreActiveSession(current);
        if (resumed) {
            showRunScreen();
        } else if (resultRequested && isTerminal(current.state())) {
            applyResultSnapshot(current);
        } else {
            resultRequested = false;
            showRunScreen();
        }
        SimulationStateRepo.get().snapshot().observe(this, this::onSnapshot);
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                handleBack();
            }
        });
    }

    /**
     * 悬浮窗长按结束会直接打开本页：把已终止的快照当作「本次结果」显示在地图上，
     * 不再弹「再来一次 / 返回校园快跑页」。
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (!intent.getBooleanExtra(EXTRA_SHOW_RUN_RESULT, false)) {
            snapshot = SimulationStateRepo.get().current();
            restoreActiveSession(snapshot);
            showRunScreen();
            return;
        }
        intent.removeExtra(EXTRA_SHOW_RUN_RESULT);
        resultRequested = true;
        RunSnapshot current = SimulationStateRepo.get().current();
        snapshot = current;
        if (isTerminal(current.state())) {
            applyResultSnapshot(current);
        } else if (current.isActive()) {
            // 停止命令还在服务队列里：先显示跑圈页，终止快照到达后按结果处理。
            showRunScreen();
        } else {
            resultRequested = false;
            showSetupScreen();
        }
    }

    /** 把刚结束的本次结果留在跑圈地图上，释放会话所有权但保留数字显示。 */
    private void applyResultSnapshot(RunSnapshot result) {
        claimOwnSession = false;
        activeRunId = "";
        showResult = true;
        resultRequested = false;
        prefs.edit()
                .putBoolean(KEY_OWNED, false)
                .remove(KEY_ACTIVE_RUN_ID)
                .apply();
        showRunScreen();
    }

    // ---------------------------------------------------------------- 设置与恢复

    private void loadSettings() {
        sport = prefs.getString(KEY_SPORT, SPORT_RUN);
        if (!SPORT_WALK.equals(sport) && !SPORT_RIDE.equals(sport)) {
            sport = SPORT_RUN;
        }
        paceSeconds = clamp(prefs.getInt(KEY_PACE, defaultPaceSeconds(sport)),
                PACE_MIN_SECONDS, PACE_MAX_SECONDS);
        speedPresets = RunSpeedPreferences.presets(this);
        if (!prefs.getBoolean("speed_presets_migrated", false)) {
            if (prefs.contains(KEY_PACE)) RunSpeedPreferences.migrate(this, sportIndex(), paceSeconds);
            prefs.edit().putBoolean("speed_presets_migrated", true).apply();
        }
        configuredSpeedKmh = speedPresets.get(sportIndex());
        paceSeconds = (int) Math.round(3600d / configuredSpeedKmh);
        laps = clamp(prefs.getInt(KEY_LAPS, RunConfig.DEFAULT_LOOPS),
                RunConfig.MIN_LOOPS, RunConfig.MAX_LOOPS);
        naturalEnabled = prefs.getBoolean(KEY_NATURAL, true);
        loadVaryStrength();
        loadRoutes();
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
                baseSpeedKmh = RunSpeedPreferences.target(this, current.speedKmh());
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

    /** 读取扰动程度；旧版的「路径浮动」布尔开关迁移成 0.5 / 0。 */
    private void loadVaryStrength() {
        if (prefs.contains(KEY_VARY_STRENGTH)) {
            varyStrength = clamp(prefs.getFloat(KEY_VARY_STRENGTH, 0.5f), 0f, 1f);
            return;
        }
        boolean legacyOn = prefs.getBoolean(KEY_VARY_LEGACY, true);
        varyStrength = legacyOn ? 0.5d : 0d;
        prefs.edit()
                .putFloat(KEY_VARY_STRENGTH, (float) varyStrength)
                .remove(KEY_VARY_LEGACY)
                .apply();
    }

    /**
     * 读取全部自定义路线，并恢复上次选中的路线。
     *
     * <p>旧版本只保存一条 {@code custom_points}；首次升级时把它转成新格式，
     * 之后不再写旧键。单条数据损坏只跳过该条，不影响其它路线。
     */
    private void loadRoutes() {
        customRoutes.clear();
        customRoutes.addAll(CampusRouteCodec.decode(prefs.getString(KEY_CUSTOM_ROUTES, "")));
        if (customRoutes.isEmpty()) {
            CampusRoute legacy = loadLegacyRoute();
            if (legacy != null) {
                customRoutes.add(legacy);
                saveRoutes();
                prefs.edit().remove(KEY_CUSTOM_POINTS).apply();
            }
        }
        PresetUnlock.migrate(prefs);
        applyPresetActivation();
        String selectedId = prefs.getString(KEY_SELECTED_ROUTE, "");
        route = null;
        for (CampusRoute candidate : customRoutes) {
            if (candidate.id().equals(selectedId)) {
                route = candidate;
                break;
            }
        }
        if (route == null && !customRoutes.isEmpty()) {
            route = customRoutes.get(0);
        }
    }

    /**
     * 落实激活码效果：已激活时确保全部预设都在列表中，按 id 去重。
     * 每次输入激活码都会重置“已补回”标记，再次进入本页时补回被删掉的预设；
     * 已存在的预设不会重复添加，也不覆盖用户改过的名称与几何。
     * 升级到 0.8.2 的老用户只补新增的「衣锦」，不补回自己删掉的 A操 / B操。
     * 未激活时移除早期调试版自动写入的预设。
     *
     * @return 路线列表是否发生变化
     */
    private boolean applyPresetActivation() {
        boolean changed = false;
        if (prefs.getBoolean(PresetUnlock.KEY_UNLOCKED, false)) {
            boolean presetsAdded = prefs.getBoolean(PresetUnlock.KEY_PRESETS_ADDED, false);
            int catalog = prefs.getInt(KEY_PRESET_CATALOG, 0);
            List<CampusRoute> presets = new ArrayList<>();
            if (!presetsAdded) {
                presets.addAll(CampusPresets.all());
            } else if (catalog < PRESET_CATALOG_REVISION) {
                presets.add(CampusPresets.routeYijin());
            }
            if (!presets.isEmpty()) {
                // 只有真正导入时才重置待询问队列，避免页面恢复时把刚排好的弹窗清掉。
                pendingPresetConflicts.clear();
                changed |= importPresetRoutes(presets);
            }
            if (!presetsAdded) {
                prefs.edit().putBoolean(PresetUnlock.KEY_PRESETS_ADDED, true).apply();
            }
            if (catalog < PRESET_CATALOG_REVISION) {
                prefs.edit().putInt(KEY_PRESET_CATALOG, PRESET_CATALOG_REVISION).apply();
            }
        } else {
            pendingPresetConflicts.clear();
            for (int i = customRoutes.size() - 1; i >= 0; i--) {
                if (customRoutes.get(i).isPreset()) {
                    customRoutes.remove(i);
                    changed = true;
                }
            }
        }
        changed |= refreshPresetGeometryIfNeeded();
        if (changed) {
            saveRoutes();
        }
        return changed;
    }

    /**
     * 把缺失的预设插入列表最前面。遇到同名路线时：
     * 开启询问则先记下来，等页面可见后弹窗；关闭询问则按上次动作跳过或替换。
     */
    private boolean importPresetRoutes(List<CampusRoute> presets) {
        boolean changed = false;
        for (int i = presets.size() - 1; i >= 0; i--) {
            CampusRoute preset = presets.get(i);
            if (indexOfRoute(preset.id()) >= 0) {
                continue;
            }
            List<CampusRoute> conflicts = PresetConflicts.findBySameName(customRoutes, preset);
            if (!conflicts.isEmpty()) {
                if (PresetUnlock.isConflictAskEnabled(prefs)) {
                    // 逆序插入待询问列表，弹窗顺序与预设顺序一致。
                    pendingPresetConflicts.add(0, new PresetConflict(preset, conflicts));
                } else if (PresetUnlock.ACTION_REPLACE.equals(PresetUnlock.conflictAction(prefs))
                        && !isRunActiveNow()) {
                    changed |= replacePresetConflict(preset, conflicts);
                }
                continue;
            }
            customRoutes.add(0, preset);
            changed = true;
        }
        return changed;
    }

    /** 用预设替换全部同名路线，位置沿用最后一个被替换条目的位置。 */
    private boolean replacePresetConflict(CampusRoute preset, List<CampusRoute> conflicts) {
        if (indexOfRoute(preset.id()) >= 0) {
            return false;
        }
        int insertAt = customRoutes.size();
        for (int i = customRoutes.size() - 1; i >= 0; i--) {
            if (PresetConflicts.contains(conflicts, customRoutes.get(i).id())) {
                insertAt = i;
                customRoutes.remove(i);
            }
        }
        customRoutes.add(Math.min(insertAt, customRoutes.size()), preset);
        return true;
    }

    /** 当前是否已有跑圈会话；导入只在空闲时改路线，避免动到正在使用的路线。 */
    private boolean isRunActiveNow() {
        return snapshot.isActive() || SimulationStateRepo.get().current().isActive();
    }

    /**
     * 页面空闲后逐个弹重名询问。激活码入口在别处，导入发生在进入本页时，
     * 那时视图还没 inflate，所以统一等到可见后再弹。
     */
    private void maybeShowPresetConflicts() {
        if (presetConflictDialogShowing || pendingPresetConflicts.isEmpty()
                || runScreenVisible || isRunActiveNow()
                || isFinishing() || isDestroyed()) {
            return;
        }
        showNextPresetConflict();
    }

    private void showNextPresetConflict() {
        if (presetConflictDialogShowing || pendingPresetConflicts.isEmpty()
                || runScreenVisible || isRunActiveNow()
                || isFinishing() || isDestroyed()) {
            return;
        }
        presetConflictDialogShowing = true;
        PresetConflict conflict = pendingPresetConflicts.remove(0);
        View content = getLayoutInflater().inflate(R.layout.dialog_campus_preset_conflict, null);
        TextView message = content.findViewById(R.id.textPresetConflictMessage);
        CheckBox dontAsk = content.findViewById(R.id.checkPresetConflictDontAsk);
        message.setText(getString(R.string.campus_preset_conflict_message, conflict.preset.name()));

        final boolean[] handled = {false};
        AlertDialog dialog = new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.campus_preset_conflict_title)
                .setView(content)
                .setPositiveButton(R.string.campus_preset_conflict_replace, (d, which) -> {
                    handled[0] = true;
                    resolvePresetConflict(conflict, true, dontAsk.isChecked());
                })
                .setNegativeButton(R.string.campus_preset_conflict_skip, (d, which) -> {
                    handled[0] = true;
                    resolvePresetConflict(conflict, false, dontAsk.isChecked());
                })
                .setOnCancelListener(d -> {
                    if (!handled[0]) {
                        handled[0] = true;
                        // 返回键等同本次跳过，但不算「以后不再出现」。
                        resolvePresetConflict(conflict, false, false);
                    }
                })
                .create();
        dialog.setOnDismissListener(d -> {
            presetConflictDialogShowing = false;
            binding.getRoot().post(this::showNextPresetConflict);
        });
        dialog.show();
    }

    private void resolvePresetConflict(PresetConflict conflict, boolean replace,
                                       boolean dontAskAgain) {
        PresetUnlock.rememberConflictChoice(prefs, replace, dontAskAgain);
        if (!replace) {
            return;
        }
        List<CampusRoute> current = PresetConflicts.findBySameName(customRoutes, conflict.preset);
        if (current.isEmpty()) {
            if (indexOfRoute(conflict.preset.id()) < 0) {
                customRoutes.add(0, conflict.preset);
                saveRoutes();
            }
            return;
        }
        boolean selectedReplaced = route != null
                && PresetConflicts.contains(current, route.id());
        if (!replacePresetConflict(conflict.preset, current)) {
            return;
        }
        saveRoutes();
        if (selectedReplaced) {
            applyRoute(conflict.preset);
        } else if (runScreenVisible) {
            updateRunUi();
        }
        toast(getString(R.string.campus_preset_conflict_replaced, conflict.preset.name()));
    }

    /**
     * 升级已保存的预设路线几何：0.7.0 的预设点是初版采样，0.7.1 平滑了弯道并统一了方向。
     * 只替换仍标记为预设的条目并保留用户改过的名称；重绘过的预设已变成自定义路线，
     * 不会被覆盖，删除过的预设也不会被补回。
     */
    private boolean refreshPresetGeometryIfNeeded() {
        if (prefs.getInt(KEY_PRESET_REVISION, 1) >= PRESET_REVISION
                || !prefs.getBoolean(PresetUnlock.KEY_UNLOCKED, false)
                || !prefs.getBoolean(PresetUnlock.KEY_PRESETS_ADDED, false)) {
            return false;
        }
        boolean changed = false;
        for (int i = 0; i < customRoutes.size(); i++) {
            CampusRoute existing = customRoutes.get(i);
            if (!existing.isPreset()) {
                continue;
            }
            CampusRoute updated = null;
            if (CampusPresets.ID_A.equals(existing.id())) {
                updated = CampusPresets.routeA();
            } else if (CampusPresets.ID_B.equals(existing.id())) {
                updated = CampusPresets.routeB();
            }
            if (updated != null) {
                customRoutes.set(i,
                        CampusRoute.preset(updated.id(), existing.name(), updated.points()));
                changed = true;
            }
        }
        prefs.edit().putInt(KEY_PRESET_REVISION, PRESET_REVISION).apply();
        return changed;
    }

    /**
     * 从其它页面（设置里激活预设）回到本页时刷新路线列表；
     * 正在跑圈或绘制时保留当前状态。
     */
    private void refreshPresetRoutesIfIdle() {
        if (drawMode || snapshot.isActive()) {
            return;
        }
        if (!applyPresetActivation()) {
            return;
        }
        if (route == null || indexOfRoute(route.id()) < 0) {
            route = customRoutes.isEmpty() ? null : customRoutes.get(0);
            persistSelectedRoute(route);
        } else {
            route = customRoutes.get(indexOfRoute(route.id()));
        }
        renderedRoute = null;
        if (runScreenVisible) {
            syncMapRoute(true);
            requestMapFit();
        }
        renderSetupValues();
    }

    /** 解析旧版单条路线；格式不合法或长度越界时返回 null。 */
    @Nullable
    private CampusRoute loadLegacyRoute() {
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
        CampusRoute custom = CampusRoute.custom(ROUTE_CUSTOM_ID,
                getString(R.string.campus_custom_name), points);
        if (custom.lapMeters() < CUSTOM_MIN_METERS || custom.lapMeters() > CUSTOM_MAX_METERS) {
            return null;
        }
        return custom;
    }

    private void saveRoutes() {
        prefs.edit()
                .putString(KEY_CUSTOM_ROUTES, CampusRouteCodec.encode(customRoutes))
                .apply();
    }

    private void persistSelectedRoute(@Nullable CampusRoute selected) {
        prefs.edit()
                .putString(KEY_SELECTED_ROUTE, selected == null ? "" : selected.id())
                .apply();
    }

    // ---------------------------------------------------------------- 控件

    private void wireViews() {
        binding.buttonSetupBack.setOnClickListener(v -> showRunScreen());
        binding.buttonRunBack.setOnClickListener(v -> handleBack());
        binding.buttonRunSport.setOnClickListener(this::showSportMenu);

        binding.textPaceValue.setOnClickListener(v -> showPaceDialog());
        binding.textLaps.setOnClickListener(v -> showLapsDialog());
        binding.buttonAdvanced.setOnClickListener(v -> showSetupScreen());
        binding.switchNatural.setOnCheckedChangeListener((button, checked) -> {
            naturalEnabled = checked;
            prefs.edit().putBoolean(KEY_NATURAL, checked).apply();
        });
        binding.seekVary.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar seekBar, int progress, boolean fromUser) {
                varyStrength = progress / 100d;
                renderVaryValue();
                if (fromUser && !varySeekGuard) {
                    prefs.edit().putFloat(KEY_VARY_STRENGTH, (float) varyStrength).apply();
                }
            }

            @Override
            public void onStartTrackingTouch(android.widget.SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(android.widget.SeekBar seekBar) {
            }
        });
        binding.switchCampusOverlay.setOnCheckedChangeListener((button, checked) -> {
            if (overlaySwitchGuard) {
                return;
            }
            if (checked) {
                requestCampusOverlay();
            } else {
                pendingOverlayEnable = false;
                OverlayHost.get(this).setEnabled(false);
                toast(getString(R.string.settings_overlay_disable_toast));
                refreshCampusOverlay();
            }
        });
        refreshCampusOverlay();
        binding.rowAppSettings.setOnClickListener(v ->
                startActivity(new Intent(this, SettingsCheckActivity.class)));
        binding.buttonGoal.setOnClickListener(v -> showRouteSheet());
        binding.buttonGo.setOnClickListener(v -> showRunScreen());
        binding.buttonRunMain.setOnClickListener(v -> onMainControl());
        FinmoriMotion.press(binding.buttonGoal);
        FinmoriMotion.press(binding.buttonRunMain);
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
            showRunScreen();
        });
        binding.buttonDrawDone.setOnClickListener(v -> finishDrawMode());

        binding.buttonDoneAgain.setOnClickListener(v -> {
            binding.doneOverlay.setVisibility(View.GONE);
            startRouteFlow();
        });
        binding.buttonDoneBack.setOnClickListener(v -> {
            binding.doneOverlay.setVisibility(View.GONE);
            showRunScreen();
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
        binding.textMapLayer.setOnClickListener(v -> showMapLayerDialog());
        renderMapLayerLabel(mapHost.baseLayer());
        mapHost.registerGestureListener();
        mapHost.setStatusListener(message -> {
            if (message == null || message.isEmpty()) {
                binding.textMapStatus.setVisibility(View.GONE);
                mapReady = true;
                if (pendingCameraFocus != null) {
                    applyPendingCameraFocus();
                } else if (pendingFit && runScreenVisible) {
                    pendingFit = false;
                    fitMapToRoute();
                }
            } else {
                binding.textMapStatus.setText(message);
                binding.textMapStatus.setVisibility(View.VISIBLE);
            }
        });
    }

    /** 地图左上角图层按钮：在「当前地图」与「卫星地图」之间二选一。 */
    private void showMapLayerDialog() {
        if (mapHost == null) {
            return;
        }
        CharSequence[] labels = new CharSequence[]{
                getString(R.string.campus_map_layer_standard),
                getString(R.string.campus_map_layer_satellite)};
        int checked = mapHost.baseLayer() == RouteMapHost.BaseLayer.SATELLITE ? 1 : 0;
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.campus_map_layer_title)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    RouteMapHost.BaseLayer layer = which == 1
                            ? RouteMapHost.BaseLayer.SATELLITE
                            : RouteMapHost.BaseLayer.STANDARD;
                    mapHost.setBaseLayer(layer);
                    renderMapLayerLabel(layer);
                    dialog.dismiss();
                    toast(getString(R.string.campus_map_layer_switched, labels[which]));
                })
                .setNegativeButton(R.string.campus_cancel, null)
                .show();
    }

    private void renderMapLayerLabel(RouteMapHost.BaseLayer layer) {
        boolean satellite = layer != RouteMapHost.BaseLayer.STANDARD;
        int label = satellite
                ? R.string.campus_map_layer_satellite
                : R.string.campus_map_layer_standard;
        binding.textMapLayer.setText(label);
        binding.textMapLayer.setContentDescription(
                getString(R.string.campus_map_layer_action, getString(label)));
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
        // 整条路线取景与「传送到某点」互斥：后发的操作覆盖前一个，避免刚传送就被拉回全线。
        pendingCameraFocus = null;
        pendingFit = true;
        if (mapReady && runScreenVisible) {
            pendingFit = false;
            fitMapToRoute();
        }
    }

    /**
     * 把镜头移到指定位置并保持居中。
     *
     * <p>地图页刚显示或底图还没就绪时先记住目标，等就绪回调再移动；期间不再按整条路线取景，
     * 否则传送/定位的结果会被随后的 fitRoute 覆盖。
     */
    private void focusMapOn(@NonNull GeoPoint point) {
        pendingCameraFocus = point;
        pendingFit = false;
        if (!runScreenVisible || mapHost == null) {
            return;
        }
        if (mapReady) {
            binding.mapContainer.post(this::applyPendingCameraFocus);
        }
    }

    private void applyPendingCameraFocus() {
        if (pendingCameraFocus == null || mapHost == null || !mapReady
                || !runScreenVisible || isFinishing() || isDestroyed()) {
            return;
        }
        GeoPoint point = pendingCameraFocus;
        pendingCameraFocus = null;
        mapHost.focusOn(point);
        // 手动定位/传送后重新打开跟随：之后模拟位置怎么移动，地图都会追上。
        // 用户再次拖动地图时由地图实现自动关闭。
        mapHost.setFollowRunner(true);
    }

    /**
     * 当前虚拟定位的落点；只认本页发起且快照坐标有效的运行。
     *
     * <p>从悬浮屏或其他页面切回跑圈地图时用它对准跑者，而不是把镜头拉回整条路线。
     */
    @Nullable
    private GeoPoint simulatedPosition() {
        if (!claimOwnSession || !snapshot.isActive() || !snapshot.hasPosition()
                || !Double.isFinite(snapshot.latitude())
                || !Double.isFinite(snapshot.longitude())) {
            return null;
        }
        return new GeoPoint(snapshot.latitude(), snapshot.longitude());
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
        RunSpeedPreferences.selectSport(this, sportIndex());
        configuredSpeedKmh = speedPresets.get(sportIndex());
        paceSeconds = (int) Math.round(3600d / configuredSpeedKmh);
        prefs.edit().putString(KEY_SPORT, sport).putInt(KEY_PACE, paceSeconds).apply();
        renderSport(); renderPaceValue(); updateRunUi();
    }

    private void renderSport() {
        String label = sportLabel();
        binding.buttonRunSport.setText(label);
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

    private int sportIndex() {
        return SPORT_WALK.equals(sport) ? 0 : SPORT_RIDE.equals(sport) ? 2 : 1;
    }

    private void renderPlanSummary() {
        if (route == null) {
            binding.textPlanSummary.setVisibility(View.GONE);
        } else {
            binding.textPlanSummary.setVisibility(View.VISIBLE);
            int target = snapshot.isActive() && claimOwnSession ? snapshot.targetLoops() : laps;
            if (target <= 0) target = laps;
            double meters = route.lapMeters() * target;
            long millis = (long) (meters / configuredSpeedKmh * 3600d);
            binding.textPlanSummary.setText(getString(R.string.run_plan_summary, target,
                    CampusFormat.kilometers(meters), CampusFormat.time(millis)));
        }
        if (!snapshot.isActive() && !showResult) binding.textLapInfo.setText(R.string.run_choose_route);
    }

    private LinearLayout inputContainer() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(8), dp(24), dp(4));
        return box;
    }

    private void showLapsDialog() {
        if (snapshot.isActive()) { toast(getString(R.string.run_laps_locked)); return; }
        LinearLayout box = inputContainer();
        EditText input = new EditText(this);
        input.setId(R.id.inputRunValue);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setSingleLine(true); input.setText(String.valueOf(laps)); input.selectAll();
        input.setContentDescription(getString(R.string.run_laps_edit));
        LinearLayout stepper = new LinearLayout(this);
        stepper.setGravity(Gravity.CENTER_VERTICAL);
        android.widget.Button minus = new android.widget.Button(this);
        minus.setId(R.id.buttonLapMinus); minus.setText("−");
        minus.setContentDescription(getString(R.string.campus_laps_minus));
        android.widget.Button plus = new android.widget.Button(this);
        plus.setId(R.id.buttonLapPlus); plus.setText("+");
        plus.setContentDescription(getString(R.string.campus_laps_plus));
        stepper.addView(minus, new LinearLayout.LayoutParams(dp(56), dp(48)));
        stepper.addView(input, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        stepper.addView(plus, new LinearLayout.LayoutParams(dp(56), dp(48)));
        minus.setOnClickListener(v -> stepLapInput(input, -1));
        plus.setOnClickListener(v -> stepLapInput(input, 1));
        box.addView(stepper);
        AlertDialog dialog = new com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setTitle(R.string.run_laps_edit)
                .setView(box).setNegativeButton(R.string.campus_cancel, null)
                .setPositiveButton(R.string.campus_save, null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (snapshot.isActive()) { dialog.dismiss(); return; }
            try {
                laps = RunInputs.laps(input.getText().toString());
                prefs.edit().putInt(KEY_LAPS, laps).apply();
                renderSetupValues(); updateRunUi(); dialog.dismiss();
            } catch (IllegalArgumentException error) { input.setError(error.getMessage()); }
        }));
        dialog.show();
    }

    private void stepLapInput(EditText input, int delta) {
        try {
            int value = RunInputs.laps(input.getText().toString());
            input.setText(String.valueOf(clamp(value + delta, RunConfig.MIN_LOOPS, RunConfig.MAX_LOOPS)));
        } catch (IllegalArgumentException error) { input.setError(error.getMessage()); }
    }

    private void showPaceDialog() {
        LinearLayout box = inputContainer();
        TextView hint = new TextView(this);
        hint.setText(R.string.run_pace_hint); hint.setTextSize(13); box.addView(hint);
        RadioGroup modes = new RadioGroup(this);
        RadioButton pace = new RadioButton(this); pace.setId(R.id.inputModePace);
        pace.setText(R.string.run_pace_mode); modes.addView(pace);
        RadioButton speed = new RadioButton(this); speed.setId(R.id.inputModeSpeed);
        speed.setText(R.string.run_speed_mode); modes.addView(speed);
        modes.check(pace.getId()); box.addView(modes);
        EditText input = new EditText(this); input.setId(R.id.inputRunValue);
        input.setSingleLine(true); input.setInputType(InputType.TYPE_CLASS_DATETIME | InputType.TYPE_DATETIME_VARIATION_TIME);
        input.setText(RunInputs.pace(configuredSpeedKmh)); input.selectAll();
        input.setContentDescription(getString(R.string.run_pace_edit)); box.addView(input);
        SeekBar slider = new SeekBar(this); slider.setId(R.id.seekRunSpeed); slider.setMax(595);
        slider.setProgress((int) Math.round((configuredSpeedKmh - 0.5d) * 10));
        box.addView(slider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        final boolean[] wasPace = {true};
        final double[] draftSpeed = {configuredSpeedKmh};
        modes.setOnCheckedChangeListener((group, id) -> {
            String displayed = wasPace[0] ? RunInputs.pace(draftSpeed[0]) : RunInputs.speedText(draftSpeed[0]);
            try {
                if (!displayed.equals(input.getText().toString().trim())) {
                    draftSpeed[0] = RunInputs.speed(input.getText().toString(), wasPace[0]);
                }
            }
            catch (IllegalArgumentException ignored) { }
            wasPace[0] = id == pace.getId();
            input.setInputType(wasPace[0] ? InputType.TYPE_CLASS_DATETIME | InputType.TYPE_DATETIME_VARIATION_TIME
                    : InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
            input.setText(wasPace[0] ? RunInputs.pace(draftSpeed[0]) : RunInputs.speedText(draftSpeed[0])); input.selectAll();
        });
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser) {
                    double value = 0.5d + progress / 10d;
                    draftSpeed[0] = value;
                    input.setText(wasPace[0] ? RunInputs.pace(value) : RunInputs.speedText(value));
                }
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });
        AlertDialog dialog = new com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setTitle(R.string.run_pace_edit)
                .setView(box).setNegativeButton(R.string.campus_cancel, null)
                .setPositiveButton(snapshot.isActive() ? R.string.run_apply_now : R.string.campus_save, null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                String displayed = wasPace[0] ? RunInputs.pace(draftSpeed[0]) : RunInputs.speedText(draftSpeed[0]);
                double kmh = input.getText().toString().trim().equals(displayed)
                        ? draftSpeed[0] : RunInputs.speed(input.getText().toString(), wasPace[0]);
                configuredSpeedKmh = kmh; paceSeconds = (int) Math.round(3600d / kmh);
                speedPresets.set(sportIndex(), kmh);
                prefs.edit().putInt(KEY_PACE, paceSeconds).apply();
                if (claimOwnSession && snapshot.isActive() && !snapshot.singlePoint()) {
                    baseSpeedKmh = kmh; RunSpeedPreferences.setTarget(this, kmh);
                    SimulationService.setSpeedKmh(this, kmh);
                }
                renderPaceValue(); renderPlanSummary(); dialog.dismiss();
            } catch (IllegalArgumentException error) { input.setError(error.getMessage()); }
        }));
        dialog.show();
    }

    private void renderPaceValue() {
        binding.textPaceValue.setText(getString(R.string.run_speed_value, RunInputs.pace(configuredSpeedKmh)));
        binding.textPaceValue.setContentDescription(getString(R.string.run_pace_edit) + " "
                + RunInputs.speedText(configuredSpeedKmh) + " km/h");
    }

    private void renderSetupValues() {
        renderPaceValue();
        binding.textLaps.setText(getString(R.string.run_laps_value, laps));
        if (runScreenVisible) renderPlanSummary();
        binding.switchNatural.setChecked(naturalEnabled);
        varySeekGuard = true;
        binding.seekVary.setProgress((int) Math.round(varyStrength * 100d));
        varySeekGuard = false;
        renderVaryValue();
        updateBackDescriptions();
    }

    /** 地图返回上级，运行设置返回地图。 */
    private void updateBackDescriptions() {
        binding.buttonSetupBack.setContentDescription(getString(R.string.run_return_map));
        binding.buttonRunBack.setContentDescription(getString(R.string.campus_back));
    }

    /** 扰动滑条的档位文案与百分比。 */
    private void renderVaryValue() {
        int percent = (int) Math.round(varyStrength * 100d);
        String level;
        if (percent <= 0) {
            level = getString(R.string.campus_vary_level_off);
        } else if (percent <= 25) {
            level = getString(R.string.campus_vary_level_low);
        } else if (percent <= 55) {
            level = getString(R.string.campus_vary_level_medium);
        } else if (percent <= 80) {
            level = getString(R.string.campus_vary_level_high);
        } else {
            level = getString(R.string.campus_vary_level_max);
        }
        binding.textVaryValue.setText(getString(R.string.campus_vary_value, level, percent));
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
        OverlayHost.get(this).setRunMapVisible(false);
        binding.setupScreen.setVisibility(View.VISIBLE);
        binding.runScreen.setVisibility(View.GONE);
        binding.doneOverlay.setVisibility(View.GONE);
        renderSetupValues();
    }

    private void showRunScreen() {
        runScreenVisible = true;
        // 回到跑圈地图：收起悬浮窗，避免盖住地图和底部按钮。
        OverlayHost.get(this).setRunMapVisible(true);
        binding.setupScreen.setVisibility(View.GONE);
        binding.runScreen.setVisibility(View.VISIBLE);
        binding.doneOverlay.setVisibility(View.GONE);
        binding.root.setBackgroundColor(getColor(R.color.fm_background));
        if (drawMode) {
            renderMapDraft();
        } else {
            syncMapRoute(false);
        }
        // 在排帧前就记下是否有「传送到某点」的镜头目标：目标即使已被更早的一帧应用并清空，
        // 也不要再补一次整线取景把镜头拉回去。
        final boolean focusRequested = pendingCameraFocus != null;
        // 运行中回到地图（含从悬浮屏、其他页面切回）：直接对着当前模拟位置，
        // 避免「虚拟定位已经跑到别处，地图还停在原位置」。
        final GeoPoint simulatedHere = focusRequested ? null : simulatedPosition();
        binding.mapContainer.post(() -> {
            if (!runScreenVisible) {
                return;
            }
            if (focusRequested) {
                // 传送/定位刚发生：以目标点为中心，不要再按整条路线取景。
                applyPendingCameraFocus();
            } else if (simulatedHere != null) {
                focusMapOn(simulatedHere);
                applyPendingCameraFocus();
            } else {
                requestMapFit();
            }
        });
        if (!drawMode) {
            binding.drawBar.setVisibility(View.GONE);
        }
        binding.textTeleportHint.setVisibility(teleportArmed ? View.VISIBLE : View.GONE);
        updateRunUi();
    }

    private void handleBack() {
        if (binding.doneOverlay.getVisibility() == View.VISIBLE) {
            binding.doneOverlay.setVisibility(View.GONE); showRunScreen(); return;
        }
        if (drawMode) { exitDrawMode(); showRunScreen(); return; }
        if (!runScreenVisible) { showRunScreen(); return; }
        if (claimOwnSession && snapshot.isActive()) {
            startActivity(new Intent(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP));
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
        if (route == null) { showRouteSheet(); return; }
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
        double speedKmh = configuredSpeedKmh;
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
            if (promptOverlayPermissionIfNeeded(() -> startRouteNow(planRoute, speedKmh, loops))) {
                return;
            }
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
        showResult = false;
        resultRequested = false;

        CompiledRoute compiled;
        long varySeed = System.nanoTime();
        try {
            compiled = RouteCompiler.compile(route.buildGeoPoints(), EndBehavior.LOOP);
        } catch (InvalidRouteException e) {
            toast(e.getMessage() == null ? getString(R.string.campus_route_invalid) : e.getMessage());
            return;
        }
        RunConfig config = new RunConfig(speedKmh, loops);
        RunRequest request = RunRequest.create(route.name(), compiled, config,
                varyStrength, varySeed);

        activeLapMeters = compiled.lapLengthMeters();
        baseSpeedKmh = speedKmh;
        RunSpeedPreferences.setTarget(this, speedKmh);
        RunSpeedPreferences.selectSport(this, sportIndex());
        naturalPhase1 = Math.random() * Math.PI * 2d;
        naturalPhase2 = Math.random() * Math.PI * 2d;
        teleportTarget = null;
        deviceLocation = null;
        activeRunId = request.runId();
        claimOwnSession = true;
        awaitingTeleportAck = false;
        handler.removeCallbacks(teleportAckTimeout);
        stopRequested = false;
        autoPauseNotifiedRunId = "";
        prefs.edit()
                .putBoolean(KEY_OWNED, true)
                .putString(KEY_ACTIVE_RUN_ID, activeRunId)
                .putFloat(KEY_ACTIVE_LAP, (float) activeLapMeters)
                .apply();

        SimulationService.start(this, request);
        showRunScreen();
        showRunnerNow(null);
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
            focusMapOn(new GeoPoint(snapshot.latitude(), snapshot.longitude()));
            toast(getString(R.string.campus_locate_done));
            return;
        }
        if (teleportTarget != null) {
            focusMapOn(teleportTarget);
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
        showRunnerNow(point);
        focusMapOn(point);
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
        focusMapOn(start);
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

        if (snapshot.isActive()) {
            if (!claimOwnSession) {
                toast(getString(R.string.campus_toast_existing));
                return;
            }
            if (snapshot.singlePoint()) {
                sendTeleport(target, false);
                return;
            }
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.campus_teleport_confirm_title)
                    .setMessage(getString(R.string.campus_teleport_confirm_message,
                            target.format()))
                    .setPositiveButton(R.string.campus_teleport_confirm_action,
                            (dialog, which) -> sendTeleport(target, true))
                    .setNegativeButton(R.string.campus_cancel, null)
                    .show();
            return;
        }

        sendTeleport(target, false);
    }

    /** 真正下发传送；expectRouteSwitch 表示当前是跑圈，需等服务确认已切换成单点。 */
    private void sendTeleport(GeoPoint target, boolean expectRouteSwitch) {
        teleportTarget = target;
        if (mapHost != null) {
            showRunnerNow(target);
        }
        focusMapOn(target);

        if (snapshot.isActive()) {
            if (expectRouteSwitch) {
                awaitingTeleportAck = true;
                handler.removeCallbacks(teleportAckTimeout);
                handler.postDelayed(teleportAckTimeout, TELEPORT_ACK_TIMEOUT_MS);
                SimulationService.teleportTo(this, target);
                toast(getString(R.string.campus_teleport_sending));
            } else {
                SimulationService.teleportTo(this, target);
                toast(getString(R.string.campus_teleport_started_short));
            }
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
        showResult = false;
        resultRequested = false;
        activeLapMeters = 0d;
        activeRunId = request.runId();
        claimOwnSession = true;
        awaitingTeleportAck = false;
        handler.removeCallbacks(teleportAckTimeout);
        stopRequested = false;
        autoPauseNotifiedRunId = "";
        prefs.edit()
                .putBoolean(KEY_OWNED, true)
                .putString(KEY_ACTIVE_RUN_ID, activeRunId)
                .putFloat(KEY_ACTIVE_LAP, 0f)
                .apply();
        SimulationService.start(this, request);
        // 先登记镜头目标再切页：showRunScreen 的取景 post 看到待定目标就只居中到传送点，
        // 不会再按整条路线取景把镜头拉回去。
        focusMapOn(target);
        showRunScreen();
        if (mapHost != null) {
            showRunnerNow(target);
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
        if (promptOverlayPermissionIfNeeded(onReady)) {
            return;
        }
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
                    + "「Legion」后重试。";
        } else if (probe == SettingsInspector.MockProbeResult.PROVIDER_UNSUPPORTED) {
            message = "当前设备不支持添加测试 provider，无法伪造位置。";
        } else {
            message = "无法访问定位服务，请确认系统定位开关已打开。";
        }
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("还不能开始校园快跑")
                .setMessage(message)
                .setPositiveButton("去设置检查", (dialog, which) ->
                        startActivity(new Intent(this, SettingsCheckActivity.class)))
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 悬浮窗开关已开但系统权限缺失时，开始跑圈前提示一次，可跳授权页。
     * 只影响悬浮窗，不阻断跑圈本身；返回 true 表示已弹窗、等待用户选择。
     */
    private boolean promptOverlayPermissionIfNeeded(Runnable proceed) {
        if (overlayPermissionPrompted
                || !OverlayHost.get(this).isEnabled()
                || OverlayPanel.canDrawOverlays(this)) {
            return false;
        }
        overlayPermissionPrompted = true;
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.campus_overlay_permission_title)
                .setMessage(R.string.campus_overlay_permission_message)
                .setCancelable(false)
                .setPositiveButton(R.string.campus_overlay_permission_grant,
                        (dialog, which) -> OverlayPanel.requestPermission(this))
                .setNegativeButton(R.string.campus_overlay_permission_skip,
                        (dialog, which) -> proceed.run())
                .show();
        return true;
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
        }
        handleTeleportAck(next, ours);
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
        handler.removeCallbacks(teleportAckTimeout);
        awaitingTeleportAck = false;
        boolean wasSinglePoint = next.singlePoint() || previous.singlePoint();
        showResult = !wasSinglePoint;
        boolean showResultNow = resultRequested;
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
            showRunScreen();
            return;
        }

        if (showResultNow) {
            showResult = true;
            resultRequested = false;
            showRunScreen();
            int messageRes = next.state() == RunState.COMPLETED
                    ? R.string.campus_done_completed
                    : next.state() == RunState.ERROR
                    ? R.string.campus_done_error
                    : R.string.campus_done_stopped;
            toast(getString(messageRes));
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
            showRunScreen();
            toast(getString(R.string.campus_hold_stopped));
            return;
        }
        showDoneOverlay(next, R.string.campus_done_stopped, null);
    }

    private boolean isTerminal(RunState state) {
        return state == RunState.COMPLETED || state == RunState.STOPPED || state == RunState.ERROR;
    }

    /** 跑圈传送的确认结果只认服务快照：单点运行中才算成功，错误/终止都按失败反馈。 */
    private void handleTeleportAck(RunSnapshot next, boolean ours) {
        if (!awaitingTeleportAck || !ours) {
            return;
        }
        if (next.state() == RunState.RUNNING && next.singlePoint()) {
            awaitingTeleportAck = false;
            handler.removeCallbacks(teleportAckTimeout);
            toast(getString(R.string.campus_teleport_route_ended));
            return;
        }
        if (isTerminal(next.state())) {
            awaitingTeleportAck = false;
            handler.removeCallbacks(teleportAckTimeout);
            String detail = next.errorMessage();
            toast(detail == null || detail.isEmpty()
                    ? getString(R.string.campus_teleport_failed)
                    : getString(R.string.campus_teleport_failed_detail, detail));
        }
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
        boolean ours = claimOwnSession || showResult;
        boolean active = ours && snapshot.isActive();
        boolean single = active && snapshot.singlePoint();
        double lapMeters = ours && activeLapMeters > 0d ? activeLapMeters
                : route == null ? 0d : route.lapMeters();

        // 只有本页面的任务才显示已有数据，避免上一次跑完的数字残留到下一次 GO 的预览里。
        double distance = (ours && !single) ? snapshot.distanceMeters() : 0d;
        binding.textDistance.setText(CampusFormat.kilometers(distance));
        boolean showElapsed = active || (ours && isTerminal(snapshot.state()));
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
                    GeoPoint running = new GeoPoint(snapshot.latitude(), snapshot.longitude());
                    animateRunnerTo(running, snapshot.state() == RunState.RUNNING);
                    // 跑圈/单点保持/摇杆移动时让镜头跟着模拟位置走；用户拖动地图后
                    // 地图实现会关闭跟随，点「定位当前位置」或再传送即可恢复。
                    boolean moving = snapshot.state() == RunState.RUNNING
                            || snapshot.state() == RunState.PAUSED;
                    if (runScreenVisible && moving) {
                        mapHost.followRunner(running);
                    }
                } else if (!active) {
                    showRunnerNow(teleportTarget != null ? teleportTarget : deviceLocation);
                }
            }
        }

        boolean starting = ours && snapshot.state() == RunState.STARTING;
        binding.buttonRunMain.setEnabled(!drawMode && !starting);
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
            binding.buttonRunMain.setText(getString(route == null ? R.string.run_route_select : R.string.run_start));
            tintMainButton(R.color.campus_mint);
        }

        boolean showEnd = active && !single;
        binding.buttonEndRun.setVisibility(showEnd ? View.VISIBLE : View.GONE);
        binding.buttonGoal.setEnabled(!drawMode);
        binding.buttonTeleport.setEnabled(!drawMode);
        binding.buttonGoal.setAlpha(drawMode ? 0.45f : 1f);
        binding.buttonTeleport.setAlpha(drawMode ? 0.45f : 1f);
        binding.buttonRunSport.setEnabled(!active);
        binding.runStats.setVisibility(active || showResult ? View.VISIBLE : View.GONE);
        binding.buttonAdvanced.setEnabled(!drawMode);
        binding.textPaceValue.setEnabled(!drawMode && !starting && !single);
        binding.textLaps.setEnabled(!drawMode && !active);
        binding.selectedRoutePreview.setRoute(route);
        int status = drawMode ? R.string.run_state_drawing : starting ? R.string.run_state_starting
                : single ? R.string.run_state_hold : active && snapshot.state() == RunState.PAUSED
                ? R.string.run_state_paused : active ? R.string.run_state_running
                : showResult && snapshot.state() == RunState.ERROR ? R.string.run_state_error
                : showResult ? R.string.run_state_done : R.string.run_status_idle;
        if (!getString(status).contentEquals(binding.textRunStatus.getText())) binding.textRunStatus.setText(status);
        if (active && !single) configuredSpeedKmh = RunSpeedPreferences.target(this, configuredSpeedKmh);
        if (active && !single) {
            int selected = RunSpeedPreferences.selectedSport(this, sportIndex());
            String nextSport = selected == 0 ? SPORT_WALK : selected == 2 ? SPORT_RIDE : SPORT_RUN;
            if (!sport.equals(nextSport)) {
                sport = nextSport;
                prefs.edit().putString(KEY_SPORT, sport).apply();
                renderSport();
            }
        }
        renderPaceValue(); renderPlanSummary();
        updateBackDescriptions();
    }

    private void tintMainButton(int colorRes) {
        binding.buttonRunMain.setBackgroundTintList(
                ColorStateList.valueOf(getColor(colorRes)));
    }

    /**
     * 把跑者标记滑到新位置：500ms 一次的快照之间用球面插值补帧，
     * 地图上的跑者就会连续前进，而不是每半秒跳一下。
     *
     * @param smooth 只在 RUNNING 时补帧；暂停、结束、传送等场景直接落点
     */
    private void animateRunnerTo(@NonNull GeoPoint target, boolean smooth) {
        if (mapHost == null) {
            return;
        }
        GeoPoint from = runnerShown;
        if (!smooth || from == null
                || GeoMath.distanceMeters(from, target) > RUNNER_SNAP_METERS) {
            showRunnerNow(target);
            return;
        }
        if (GeoMath.distanceMeters(from, target) < 0.05d) {
            return;
        }
        cancelRunnerAnimation();
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(RUNNER_ANIM_MS);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(animation -> {
            float fraction = (float) animation.getAnimatedValue();
            GeoPoint point = GeoMath.interpolate(from, target, fraction);
            runnerShown = point;
            if (mapHost != null) {
                mapHost.renderRunner(point);
            }
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (runnerAnimator != animation) {
                    return;
                }
                runnerAnimator = null;
                runnerShown = target;
                if (mapHost != null) {
                    mapHost.renderRunner(target);
                }
            }
        });
        runnerAnimator = animator;
        animator.start();
    }

    /** 立即显示跑者位置（或清除），并停止正在进行的滑动动画。 */
    private void showRunnerNow(@Nullable GeoPoint point) {
        cancelRunnerAnimation();
        runnerShown = point;
        if (mapHost != null) {
            mapHost.renderRunner(point);
        }
    }

    private void cancelRunnerAnimation() {
        if (runnerAnimator != null) {
            ValueAnimator animator = runnerAnimator;
            runnerAnimator = null;
            animator.cancel();
        }
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
        boolean empty = customRoutes.isEmpty();
        sheet.routeScroll.setVisibility(empty ? View.GONE : View.VISIBLE);
        sheet.textNoRoutes.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (!empty) {
            for (CampusRoute candidate : customRoutes) {
                addRouteRow(sheet.routeList, candidate, dialog);
            }
            // 路线多时列表内部滚动，底部的「新增路线」「关闭」始终露在外面。
            ViewGroup.LayoutParams scrollParams = sheet.routeScroll.getLayoutParams();
            int available = getResources().getDisplayMetrics().heightPixels;
            WindowInsetsCompat windowInsets = ViewCompat.getRootWindowInsets(binding.getRoot());
            if (windowInsets != null) {
                Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
                available -= bars.top + bars.bottom;
            }
            int maxListHeight = Math.max(dp(100), Math.min(dp(380), available - dp(260)));
            scrollParams.height = Math.min(customRoutes.size() * dp(100), maxListHeight);
            sheet.routeScroll.setLayoutParams(scrollParams);
        }
        sheet.buttonDrawRoute.setOnClickListener(v -> {
            dialog.dismiss();
            if (snapshot.isActive()) {
                toast(getString(R.string.campus_draw_stop_first));
                return;
            }
            enterDrawMode(null);
        });
        sheet.buttonCloseRoutes.setOnClickListener(v -> dialog.dismiss());
        WindowInsetsCompat activityInsets = ViewCompat.getRootWindowInsets(binding.getRoot());
        final Insets activityBars = activityInsets == null ? Insets.NONE
                : activityInsets.getInsets(WindowInsetsCompat.Type.systemBars());
        sheet.getRoot().setPadding(dp(20) + activityBars.left, dp(18),
                dp(20) + activityBars.right, dp(12) + activityBars.bottom);
        ViewCompat.setOnApplyWindowInsetsListener(sheet.getRoot(), (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(dp(20) + Math.max(bars.left, activityBars.left), dp(18),
                    dp(20) + Math.max(bars.right, activityBars.right),
                    dp(12) + Math.max(bars.bottom, activityBars.bottom));
            return insets;
        });
        dialog.setContentView(sheet.getRoot());
        dialog.getBehavior().setSkipCollapsed(true);
        dialog.getBehavior().setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED);
        dialog.show();
        ViewCompat.requestApplyInsets(sheet.getRoot());
    }

    private void addRouteRow(LinearLayout list, CampusRoute candidate, BottomSheetDialog dialog) {
        boolean selected = route != null && route.id().equals(candidate.id());

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.HORIZONTAL);
        content.setGravity(Gravity.CENTER_VERTICAL);
        content.setPadding(dp(6), dp(14), dp(6), dp(14));
        // 内容层必须是完全不透明的，收起时才能盖住下面的操作按钮。
        android.graphics.drawable.GradientDrawable base = new android.graphics.drawable.GradientDrawable();
        base.setColor(getColor(R.color.campus_row_bg));
        content.setBackground(new android.graphics.drawable.RippleDrawable(
                ColorStateList.valueOf(0x1F000000), base, null));

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
        RoutePreviewView preview = new RoutePreviewView(this);
        preview.setRoute(candidate);
        preview.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(dp(72), dp(64));
        previewParams.setMarginEnd(dp(12));
        content.addView(preview, previewParams);
        content.addView(texts);

        TextView check = new TextView(this);
        check.setText(selected ? "✓" : "");
        check.setContentDescription(selected ? getString(R.string.campus_row_check) : null);
        check.setTextColor(getColor(R.color.campus_mint_dark));
        check.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f);
        check.setTypeface(check.getTypeface(), android.graphics.Typeface.BOLD);
        check.setPadding(dp(10), 0, dp(4), 0);
        content.addView(check);

        content.setOnClickListener(v -> {
            dialog.dismiss();
            selectRoute(candidate);
        });

        SwipeActionRow swipe = new SwipeActionRow(this);
        swipe.setContent(content);
        // 先添加「修改」、后添加「删除」：删除贴在最右侧，修改紧挨着它。
        swipe.addAction(getString(R.string.campus_route_edit),
                getColor(R.color.campus_mint_dark),
                () -> showRouteEditDialog(candidate, dialog));
        swipe.addAction(getString(R.string.campus_route_delete),
                getColor(R.color.campus_danger),
                () -> deleteRoute(candidate, dialog));

        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.topMargin = dp(2);
        list.addView(swipe, rowParams);
    }

    private void showRouteEditDialog(CampusRoute candidate, BottomSheetDialog sheet) {
        sheet.dismiss();
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.campus_route_edit_title) + " · " + candidate.name())
                .setItems(new CharSequence[]{
                        getString(R.string.campus_route_edit_rename),
                        getString(R.string.campus_route_edit_redraw)}, (dialog, which) -> {
                    if (which == 0) {
                        promptRenameRoute(candidate);
                    } else {
                        startRedrawRoute(candidate);
                    }
                })
                .setNegativeButton(R.string.campus_cancel, null)
                .show();
    }

    private void promptRenameRoute(CampusRoute candidate) {
        EditText input = routeNameInput(candidate.name());
        AlertDialog dialog = new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.campus_route_rename_title)
                .setView(input)
                .setPositiveButton(R.string.campus_save, null)
                .setNegativeButton(R.string.campus_cancel, null)
                .create();
        dialog.setOnShowListener(ignored ->
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) {
                        toast(getString(R.string.campus_route_name_empty));
                        return;
                    }
                    int index = indexOfRoute(candidate.id());
                    if (index < 0) {
                        dialog.dismiss();
                        return;
                    }
                    CampusRoute renamed = candidate.isPreset()
                            ? CampusRoute.preset(candidate.id(), name, candidate.points())
                            : CampusRoute.custom(candidate.id(), name, candidate.waypointsForSave());
                    customRoutes.set(index, renamed);
                    saveRoutes();
                    if (route != null && route.id().equals(renamed.id())) {
                        applyRoute(renamed);
                    } else {
                        renderSetupValues();
                    }
                    dialog.dismiss();
                    toast(getString(R.string.campus_route_renamed, name));
                    showRouteSheet();
                }));
        dialog.show();
    }

    private EditText routeNameInput(String initial) {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setSingleLine(true);
        input.setHint(R.string.campus_route_name_hint);
        input.setText(initial);
        input.setSelection(input.getText().length());
        return input;
    }

    private void deleteRoute(CampusRoute candidate, BottomSheetDialog dialog) {
        boolean selected = route != null && route.id().equals(candidate.id());
        if (selected && claimOwnSession && snapshot.isActive() && !snapshot.singlePoint()) {
            toast(getString(R.string.campus_route_delete_running));
            return;
        }
        int index = indexOfRoute(candidate.id());
        if (index < 0) {
            dialog.dismiss();
            return;
        }
        customRoutes.remove(index);
        saveRoutes();
        if (selected) {
            applyRoute(customRoutes.isEmpty() ? null : customRoutes.get(0));
        } else {
            renderSetupValues();
        }
        dialog.dismiss();
        toast(getString(R.string.campus_route_deleted, candidate.name()));
        showRouteSheet();
    }

    private int indexOfRoute(String id) {
        for (int i = 0; i < customRoutes.size(); i++) {
            if (customRoutes.get(i).id().equals(id)) {
                return i;
            }
        }
        return -1;
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
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
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
        showRunScreen();
    }

    private void applyRoute(@Nullable CampusRoute candidate) {
        route = candidate;
        persistSelectedRoute(candidate);
        teleportTarget = null;
        activeLapMeters = candidate == null ? 0d : candidate.lapMeters();
        renderedRoute = null;
        showResult = false;
        if (mapHost != null) {
            showRunnerNow(null);
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

    /**
     * 进入绘制模式。{@code editTarget} 为 null 时新增路线，否则载入该路线的航点重新绘制；
     * 两种模式都只在点「完成」并命名后才写回存储。
     */
    private void enterDrawMode(@Nullable CampusRoute editTarget) {
        if (snapshot.isActive()) {
            toast(getString(R.string.campus_draw_stop_first));
            return;
        }
        if (!runScreenVisible) {
            showRunScreen();
        }
        editingRouteId = editTarget == null ? null : editTarget.id();
        drawMode = true;
        showResult = false;
        resultRequested = false;
        teleportArmed = false;
        draftPoints.clear();
        if (editTarget != null) {
            draftPoints.addAll(editTarget.waypointsForSave());
        }
        binding.textTeleportHint.setVisibility(View.GONE);
        binding.drawBar.setVisibility(View.VISIBLE);
        updateDrawBanner();
        renderedRoute = null;
        renderMapDraft();
        showRunnerNow(null);
        requestMapFit();
        updateRunUi();
    }

    private void startRedrawRoute(CampusRoute candidate) {
        enterDrawMode(candidate);
    }

    private void exitDrawMode() {
        drawMode = false;
        editingRouteId = null;
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
        binding.textDrawBanner.setText(getString(editingRouteId == null
                ? R.string.campus_draw_count : R.string.campus_draw_count_edit,
                draftPoints.size()));
    }

    private void finishDrawMode() {
        if (draftPoints.size() < DRAFT_MIN) {
            toast(getString(R.string.campus_draw_too_few));
            return;
        }
        String id = editingRouteId != null ? editingRouteId : RouteRepository.newRouteId();
        String suggestedName;
        if (editingRouteId != null) {
            int index = indexOfRoute(editingRouteId);
            suggestedName = index >= 0 ? customRoutes.get(index).name()
                    : getString(R.string.campus_custom_name);
        } else {
            suggestedName = getString(R.string.campus_route_name_default, customRoutes.size() + 1);
        }
        CampusRoute candidate = CampusRoute.custom(id, suggestedName, new ArrayList<>(draftPoints));
        if (candidate.lapMeters() < CUSTOM_MIN_METERS) {
            toast(getString(R.string.campus_draw_too_short));
            return;
        }
        if (candidate.lapMeters() > CUSTOM_MAX_METERS) {
            toast(getString(R.string.campus_draw_too_long));
            return;
        }
        promptRouteName(candidate, suggestedName);
    }

    /** 绘制完成后先命名再保存；取消命名则留在绘制状态，可以继续调整。 */
    private void promptRouteName(CampusRoute candidate, String suggestedName) {
        EditText input = routeNameInput(suggestedName);
        AlertDialog dialog = new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.campus_route_name_title)
                .setView(input)
                .setPositiveButton(R.string.campus_save, null)
                .setNegativeButton(R.string.campus_cancel, null)
                .create();
        dialog.setOnShowListener(ignored ->
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) {
                        toast(getString(R.string.campus_route_name_empty));
                        return;
                    }
                    CampusRoute saved = CampusRoute.custom(
                            candidate.id(), name, candidate.waypointsForSave());
                    int index = indexOfRoute(saved.id());
                    if (index >= 0) {
                        customRoutes.set(index, saved);
                    } else {
                        customRoutes.add(saved);
                    }
                    saveRoutes();
                    dialog.dismiss();
                    exitDrawMode();
                    applyRoute(saved);
                    showRunScreen();
                    toast(getString(R.string.campus_route_name_saved,
                            saved.name(), CampusFormat.lapMeters(saved.lapMeters())));
                }));
        dialog.show();
    }

    // ---------------------------------------------------------------- 收尾

    @Override
    protected void onStart() {
        super.onStart();
        if (mapHost != null) {
            mapHost.onStart();
        }
        // 从后台或别的页面回来：按当前页面上报，跑圈地图可见时立即收起悬浮窗。
        OverlayHost.get(this).setRunMapVisible(runScreenVisible);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mapHost != null) {
            mapHost.onResume();
        }
        refreshPresetRoutesIfIdle();
        if (runScreenVisible) updateRunUi();
        maybeShowPresetConflicts();
        if (pendingOverlayEnable) {
            if (OverlayPanel.canDrawOverlays(this)) {
                enableCampusOverlayNow();
            } else {
                pendingOverlayEnable = false;
                toast(getString(R.string.settings_overlay_denied_toast));
            }
        }
        if (!snapshot.isActive()) configuredSpeedKmh = speedPresets.get(sportIndex());
        renderPaceValue();
        if (runScreenVisible) updateRunUi();
        refreshCampusOverlay();
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
        // 页面已经不可见：运行中的任务允许悬浮窗接管控制。
        OverlayHost.get(this).setRunMapVisible(false);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        naturalRunning = false;
        handler.removeCallbacks(naturalTicker);
        handler.removeCallbacks(teleportAckTimeout);
        awaitingTeleportAck = false;
        cancelRunnerAnimation();
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

    // ---------------------------------------------------------------- 悬浮窗开关

    private void requestCampusOverlay() {
        if (!OverlayPanel.canDrawOverlays(this)) {
            pendingOverlayEnable = true;
            setCampusOverlaySwitch(false);
            OverlayPanel.requestPermission(this);
            toast(getString(R.string.settings_overlay_need_permission_toast));
            return;
        }
        enableCampusOverlayNow();
    }

    private void enableCampusOverlayNow() {
        pendingOverlayEnable = false;
        OverlayHost.get(this).setEnabled(true);
        toast(getString(R.string.settings_overlay_enable_toast));
        refreshCampusOverlay();
    }

    private void refreshCampusOverlay() {
        boolean enabled = OverlayHost.get(this).isEnabled();
        boolean allowed = OverlayPanel.canDrawOverlays(this);
        setCampusOverlaySwitch(enabled);
        int statusRes;
        if (!enabled) {
            statusRes = R.string.settings_overlay_status_off;
        } else if (allowed) {
            statusRes = R.string.settings_overlay_status_on;
        } else {
            statusRes = R.string.settings_overlay_status_on_missing;
        }
        binding.textCampusOverlayStatus.setText(statusRes);
        if (enabled) {
            // 权限可能刚在系统页里授予：让面板按当前开关重新渲染一次。
            OverlayHost.get(this).refresh();
        }
    }

    private void setCampusOverlaySwitch(boolean checked) {
        overlaySwitchGuard = true;
        binding.switchCampusOverlay.setChecked(checked);
        overlaySwitchGuard = false;
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
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

    /** 一条待询问的预设与它导入时撞名的现有路线（只用于展示与定位）。 */
    private static final class PresetConflict {
        final CampusRoute preset;
        final List<CampusRoute> existing;

        PresetConflict(CampusRoute preset, List<CampusRoute> existing) {
            this.preset = preset;
            this.existing = new ArrayList<>(existing);
        }
    }
}
