package org.localroute.app.ui;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

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

import org.localroute.app.BuildConfig;
import org.localroute.app.R;
import org.localroute.app.campus.CampusFormat;
import org.localroute.app.campus.CampusGeo;
import org.localroute.app.databinding.ActivityVirtualLocationBinding;
import org.localroute.app.diagnostics.DiagnosticLog;
import org.localroute.app.map.AmapRouteMapView;
import org.localroute.app.map.MapBackendPolicy;
import org.localroute.app.map.RouteMapController;
import org.localroute.app.map.RouteMapHost;
import org.localroute.app.overlay.OverlayHost;
import org.localroute.app.run.RunRequest;
import org.localroute.app.run.RunSnapshot;
import org.localroute.app.run.SimulationStateRepo;
import org.localroute.app.service.SimulationService;
import org.localroute.app.settings.SettingsInspector;
import org.localroute.core.GeoPoint;
import org.localroute.core.RunState;
import org.maplibre.android.MapLibre;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 独立虚拟定位页：只负责地图选点、单点保持与结束，不包含路线、圈数和配速。
 *
 * <p>与校园快跑共用服务里的单点会话；校园跑进行中时本页只展示状态，
 * 不提供开始/结束按钮，避免在另一处悄悄结束跑圈。
 */
public final class VirtualLocationActivity extends AppCompatActivity {

    private ActivityVirtualLocationBinding binding;
    @Nullable
    private RouteMapHost mapHost;

    private RunSnapshot snapshot;
    @Nullable
    private GeoPoint target;
    private boolean initialFocusDone;
    private boolean pendingStart;
    private boolean startRequested;
    private ActivityResultLauncher<String[]> permissionLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // MapLibre 要求在使用 MapView 之前完成初始化。
        MapLibre.getInstance(this);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        binding = ActivityVirtualLocationBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        ViewCompat.setOnApplyWindowInsetsListener(binding.getRoot(), (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });

        permissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(), this::onPermissionResult);
        binding.buttonVirtualBack.setOnClickListener(v -> finish());
        binding.textMapLayer.setOnClickListener(v -> showMapLayerDialog());
        binding.buttonVirtualMain.setOnClickListener(v -> onMainAction());

        snapshot = SimulationStateRepo.get().current();
        if (snapshot.isActive() && snapshot.singlePoint() && snapshot.hasPosition()) {
            target = new GeoPoint(snapshot.latitude(), snapshot.longitude());
        }
        setupMap();
        render();
        SimulationStateRepo.get().snapshot().observe(this, this::onSnapshot);
    }

    // ---------------------------------------------------------------- 地图

    private void setupMap() {
        RouteMapController.OnMapClickListener listener = point -> {
            if (snapshot.isActive() && !snapshot.singlePoint()) {
                toast(getString(R.string.virtual_status_route_active));
                return;
            }
            target = point;
            if (mapHost != null) {
                mapHost.renderRunner(point);
                mapHost.focusOn(point);
            }
            if (snapshot.isActive() && snapshot.singlePoint()) {
                SimulationService.teleportTo(this, point);
                toast(getString(R.string.virtual_teleport_sent));
            }
            render();
        };
        MapBackendPolicy.Decision backend = MapBackendPolicy.choose(
                BuildConfig.AMAP_KEY, Arrays.asList(Build.SUPPORTED_ABIS));
        DiagnosticLog.info("虚拟定位地图引擎：" + backend.reason());
        if (backend.usesAmapSdk()) {
            binding.mapView.setVisibility(View.GONE);
            try {
                mapHost = new AmapRouteMapView(this, binding.mapContainer, listener);
            } catch (Throwable error) {
                DiagnosticLog.warn("高德 SDK 初始化失败，改用栅格瓦片底图：" + error);
                binding.mapView.setVisibility(View.VISIBLE);
            }
        }
        if (mapHost == null) {
            mapHost = RouteMapController.attach(binding.mapView, listener);
        }
        renderMapLayerLabel(mapHost.baseLayer());
        mapHost.registerGestureListener();
        mapHost.setStatusListener(message -> {
            if (message == null || message.isEmpty()) {
                binding.textMapStatus.setVisibility(View.GONE);
                if (!initialFocusDone) {
                    initialFocusDone = true;
                    GeoPoint focus = target != null ? target
                            : new GeoPoint(CampusGeo.ANCHOR_LAT, CampusGeo.ANCHOR_LON);
                    mapHost.focusOn(focus);
                }
            } else {
                binding.textMapStatus.setText(message);
                binding.textMapStatus.setVisibility(View.VISIBLE);
            }
        });
    }

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

    // ---------------------------------------------------------------- 状态与操作

    private void onSnapshot(RunSnapshot next) {
        snapshot = next;
        startRequested = false;
        if (next.isActive() && next.hasPosition()) {
            GeoPoint point = new GeoPoint(next.latitude(), next.longitude());
            if (next.singlePoint()) {
                target = point;
            }
            if (mapHost != null) {
                mapHost.renderRunner(point);
            }
        }
        render();
    }

    private void render() {
        boolean active = snapshot.isActive();
        boolean routeActive = active && !snapshot.singlePoint();
        boolean holdActive = active && snapshot.singlePoint();
        boolean starting = startRequested || snapshot.state() == RunState.STARTING;
        boolean paused = holdActive && snapshot.state() == RunState.PAUSED;
        boolean error = snapshot.state() == RunState.ERROR;

        if (routeActive) {
            binding.textVirtualStatus.setText(R.string.virtual_status_route_active);
        } else if (starting) {
            binding.textVirtualStatus.setText(R.string.virtual_status_starting);
        } else if (error) {
            String message = snapshot.errorMessage();
            binding.textVirtualStatus.setText(getString(R.string.virtual_status_error,
                    message == null || message.isEmpty() ? getString(R.string.placeholder) : message));
        } else if (paused) {
            binding.textVirtualStatus.setText(R.string.virtual_status_paused);
        } else if (holdActive) {
            binding.textVirtualStatus.setText(R.string.virtual_status_holding);
        } else if (target != null) {
            binding.textVirtualStatus.setText(R.string.virtual_status_selected);
        } else {
            binding.textVirtualStatus.setText(R.string.virtual_status_idle);
        }

        binding.textVirtualHint.setText(holdActive
                ? R.string.virtual_hint_move : R.string.virtual_hint);
        binding.textVirtualHint.setVisibility(routeActive ? View.GONE : View.VISIBLE);
        binding.textVirtualTarget.setText(target == null
                ? getString(R.string.virtual_target_none)
                : getString(R.string.virtual_target, CampusFormat.coordinates(
                target.latitude(), target.longitude())));
        binding.textCoordinates.setText(target == null
                ? getString(R.string.placeholder)
                : CampusFormat.coordinates(target.latitude(), target.longitude()));

        if (routeActive) {
            setMainButton(R.string.virtual_route_active_button, false);
        } else if (starting) {
            setMainButton(R.string.virtual_starting, false);
        } else if (holdActive) {
            setMainButton(paused ? R.string.virtual_resume : R.string.virtual_stop, true);
        } else {
            setMainButton(error ? R.string.virtual_retry : R.string.virtual_start, target != null);
        }
    }

    private void setMainButton(int textRes, boolean enabled) {
        binding.buttonVirtualMain.setText(textRes);
        binding.buttonVirtualMain.setEnabled(enabled);
    }

    private void onMainAction() {
        if (snapshot.isActive() && !snapshot.singlePoint()) {
            return;
        }
        if (snapshot.isActive() && snapshot.singlePoint()) {
            if (snapshot.state() == RunState.PAUSED) {
                SimulationService.resume(this);
            } else {
                SimulationService.stop(this);
                toast(getString(R.string.campus_stopping));
            }
            return;
        }
        if (target == null) {
            toast(getString(R.string.virtual_need_target));
            return;
        }
        startHold();
    }

    private void startHold() {
        List<String> needed = neededPermissions();
        if (!needed.isEmpty()) {
            pendingStart = true;
            toast(getString(R.string.campus_permission_needed));
            permissionLauncher.launch(needed.toArray(new String[0]));
            return;
        }
        ensureMockReady(this::beginHold);
    }

    private void beginHold() {
        if (snapshot.isActive()) {
            toast(getString(R.string.campus_toast_existing));
            return;
        }
        GeoPoint point = target;
        if (point == null) {
            toast(getString(R.string.virtual_need_target));
            return;
        }
        startRequested = true;
        render();
        SimulationService.start(this, RunRequest.createSinglePoint(point));
        toast(getString(R.string.campus_toast_starting));
    }

    private void onPermissionResult(java.util.Map<String, Boolean> result) {
        boolean fineGranted = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION);
        boolean wanted = pendingStart;
        pendingStart = false;
        if (!fineGranted) {
            if (wanted) {
                toast(getString(R.string.campus_permission_missing));
            }
            return;
        }
        if (wanted) {
            ensureMockReady(this::beginHold);
        }
    }

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

    private boolean hasPermission(String permission) {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED;
    }

    private void ensureMockReady(Runnable onReady) {
        SettingsInspector.MockProbeResult probe = SettingsInspector.probeMockProvider(this);
        if (probe == SettingsInspector.MockProbeResult.ALLOWED
                || probe == SettingsInspector.MockProbeResult.ALLOWED_AFTER_CLEANUP) {
            onReady.run();
            return;
        }
        String message;
        if (probe == SettingsInspector.MockProbeResult.DENIED) {
            message = getString(R.string.virtual_mock_denied);
        } else if (probe == SettingsInspector.MockProbeResult.PROVIDER_UNSUPPORTED) {
            message = getString(R.string.virtual_mock_unsupported);
        } else {
            message = getString(R.string.virtual_mock_unavailable);
        }
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.virtual_cannot_start)
                .setMessage(message)
                .setPositiveButton(R.string.virtual_settings_check, (dialog, which) ->
                        startActivity(new Intent(this, SettingsCheckActivity.class)))
                .setNegativeButton(R.string.campus_cancel, null)
                .show();
    }

    // ---------------------------------------------------------------- 生命周期

    @Override
    protected void onStart() {
        super.onStart();
        if (mapHost != null) {
            mapHost.onStart();
        }
        // 本页也是定位地图：可见时收起悬浮窗，避免挡住选点。
        OverlayHost.get(this).setRunMapVisible(true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mapHost != null) {
            mapHost.onResume();
        }
        render();
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
        OverlayHost.get(this).setRunMapVisible(false);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (mapHost != null) {
            mapHost.onDestroy();
            mapHost = null;
        }
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (mapHost != null) {
            mapHost.onSaveInstanceState(outState);
        }
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        if (mapHost != null) {
            mapHost.onLowMemory();
        }
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
