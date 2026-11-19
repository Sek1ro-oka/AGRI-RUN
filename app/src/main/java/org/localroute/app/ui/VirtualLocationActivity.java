package org.localroute.app.ui;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.LinearLayout;
import android.widget.TextView;
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
import org.localroute.app.diagnostics.SystemLocationReader;
import org.localroute.app.map.AmapRouteMapView;
import org.localroute.app.map.MapBackendPolicy;
import org.localroute.app.map.RouteMapController;
import org.localroute.app.map.RouteMapHost;
import org.localroute.app.overlay.OverlayHost;
import org.localroute.app.run.RunRequest;
import org.localroute.app.run.RunSnapshot;
import org.localroute.app.run.SimulationStateRepo;
import org.localroute.app.search.PhotonSearch;
import org.localroute.app.service.SimulationService;
import org.localroute.app.settings.SettingsInspector;
import org.localroute.core.GeoMath;
import org.localroute.core.GeoPoint;
import org.localroute.core.RunState;
import org.maplibre.android.MapLibre;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 独立虚拟定位页：只负责地图选点、地点搜索、单点保持与结束，不包含路线、圈数和配速。
 *
 * <p>与校园快跑共用服务里的单点会话；校园跑进行中时本页只展示状态，
 * 不提供开始/结束按钮，避免在另一处悄悄结束跑圈。
 */
public final class VirtualLocationActivity extends AppCompatActivity {

    private static final long SEARCH_DEBOUNCE_MS = 350L;
    private static final long LOCATE_TIMEOUT_MS = 8_000L;

    private ActivityVirtualLocationBinding binding;
    @Nullable
    private RouteMapHost mapHost;

    private RunSnapshot snapshot;
    @Nullable
    private GeoPoint target;
    private boolean initialFocusDone;
    private boolean pendingStart;
    private boolean pendingLocate;
    private boolean startRequested;
    private ActivityResultLauncher<String[]> permissionLauncher;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService searchExecutor = Executors.newSingleThreadExecutor();
    private final Runnable searchRunnable =
            () -> executeSearch(binding.inputPlaceSearch.getText().toString().trim());
    private int searchGeneration;
    private List<PhotonSearch.Place> lastSearchResults = Collections.emptyList();

    @Nullable
    private GeoPoint cachedDeviceLocation;
    private int locateGeneration;
    @Nullable
    private LocationManager liveLocationManager;
    @Nullable
    private LocationListener liveLocationListener;

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
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            view.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
            return insets;
        });

        permissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(), this::onPermissionResult);
        binding.buttonVirtualBack.setOnClickListener(v -> finish());
        binding.textMapLayer.setOnClickListener(v -> showMapLayerDialog());
        binding.buttonVirtualMain.setOnClickListener(v -> onMainAction());
        binding.buttonLocateNow.setOnClickListener(v -> locateNow());
        binding.inputPlaceSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                scheduleSearch();
            }
        });
        binding.inputPlaceSearch.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                mainHandler.removeCallbacks(searchRunnable);
                executeSearch(binding.inputPlaceSearch.getText().toString().trim());
                hideKeyboard();
                return true;
            }
            return false;
        });
        binding.inputPlaceSearch.setOnFocusChangeListener((view, hasFocus) -> {
            if (hasFocus && !lastSearchResults.isEmpty()) {
                renderSearchResults(lastSearchResults);
            }
        });

        snapshot = SimulationStateRepo.get().current();
        if (snapshot.isActive() && snapshot.singlePoint() && snapshot.hasPosition()) {
            target = new GeoPoint(snapshot.latitude(), snapshot.longitude());
        }
        if (hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            GeoPoint cached = readCachedRealLocation();
            if (cached != null) {
                cachedDeviceLocation = cached;
            }
        }
        setupMap();
        render();
        SimulationStateRepo.get().snapshot().observe(this, this::onSnapshot);
    }

    // ---------------------------------------------------------------- 地图

    private void setupMap() {
        RouteMapController.OnMapClickListener listener = point -> {
            hideSearchResults();
            hideKeyboard();
            boolean moved = applyPoint(point);
            if (moved && snapshot.isActive() && snapshot.singlePoint()) {
                toast(getString(R.string.virtual_teleport_sent));
            }
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

    /** 选点统一入口：跑圈中拒绝，单点保持中直接移动，空闲时只预览。 */
    private boolean applyPoint(GeoPoint point) {
        if (snapshot.isActive() && !snapshot.singlePoint()) {
            toast(getString(R.string.virtual_status_route_active));
            return false;
        }
        target = point;
        if (mapHost != null) {
            mapHost.renderRunner(point);
            mapHost.focusOn(point);
        }
        if (snapshot.isActive() && snapshot.singlePoint()) {
            SimulationService.teleportTo(this, point);
        }
        render();
        return true;
    }

    // ---------------------------------------------------------------- 地点搜索

    private void scheduleSearch() {
        mainHandler.removeCallbacks(searchRunnable);
        String query = binding.inputPlaceSearch.getText().toString().trim();
        if (query.length() < 2) {
            ++searchGeneration;
            lastSearchResults = Collections.emptyList();
            hideSearchResults();
            return;
        }
        mainHandler.postDelayed(searchRunnable, SEARCH_DEBOUNCE_MS);
    }

    private void executeSearch(String query) {
        if (query.length() < 2) {
            return;
        }
        final int generation = ++searchGeneration;
        showSearchStatus(getString(R.string.virtual_search_searching));
        searchExecutor.execute(() -> {
            List<PhotonSearch.Place> results;
            boolean failed = false;
            try {
                results = PhotonSearch.search(query);
            } catch (Exception error) {
                DiagnosticLog.warn("地点搜索失败：" + error);
                results = Collections.emptyList();
                failed = true;
            }
            List<PhotonSearch.Place> parsed = results;
            boolean error = failed;
            mainHandler.post(() -> {
                if (isFinishing() || isDestroyed() || generation != searchGeneration) {
                    return;
                }
                if (error) {
                    showSearchStatus(getString(R.string.virtual_search_failed));
                    return;
                }
                lastSearchResults = parsed;
                if (parsed.isEmpty()) {
                    showSearchStatus(getString(R.string.virtual_search_empty));
                    return;
                }
                renderSearchResults(parsed);
            });
        });
    }

    private void renderSearchResults(List<PhotonSearch.Place> places) {
        binding.cardSearchResults.setVisibility(View.VISIBLE);
        binding.textSearchStatus.setVisibility(View.GONE);
        LinearLayout container = binding.searchResultsContainer;
        container.removeAllViews();
        GeoPoint origin = cachedDeviceLocation != null ? cachedDeviceLocation
                : mapHost != null ? mapHost.cameraCenter() : null;
        for (int i = 0; i < places.size(); i++) {
            PhotonSearch.Place place = places.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(8), dp(8), dp(8), dp(8));
            setSelectableBackground(row);

            TextView name = new TextView(this);
            name.setText(place.name());
            name.setTextColor(ContextCompat.getColor(this, R.color.campus_ink));
            name.setTextSize(14f);
            name.setTypeface(null, android.graphics.Typeface.BOLD);
            row.addView(name);

            String detail = place.address();
            if (origin != null) {
                double distance = GeoMath.distanceMeters(origin, place.point());
                String distanceText = getString(R.string.virtual_distance,
                        CampusFormat.lapMeters(distance));
                detail = detail.isEmpty() ? distanceText : detail + " · " + distanceText;
            }
            if (!detail.isEmpty()) {
                TextView address = new TextView(this);
                address.setText(detail);
                address.setTextColor(ContextCompat.getColor(this, R.color.campus_muted));
                address.setTextSize(11f);
                address.setPadding(0, dp(2), 0, 0);
                row.addView(address);
            }
            row.setOnClickListener(view -> selectSearchResult(place));
            container.addView(row);
            if (i < places.size() - 1) {
                View divider = new View(this);
                divider.setBackgroundColor(ContextCompat.getColor(this, R.color.fm_border));
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
                params.setMarginStart(dp(8));
                container.addView(divider, params);
            }
        }
    }

    private void selectSearchResult(PhotonSearch.Place place) {
        boolean movingHold = snapshot.isActive() && snapshot.singlePoint();
        if (applyPoint(place.point()) && movingHold) {
            toast(getString(R.string.virtual_teleport_sent));
        }
        hideSearchResults();
        hideKeyboard();
        binding.inputPlaceSearch.clearFocus();
    }

    private void showSearchStatus(String message) {
        binding.cardSearchResults.setVisibility(View.VISIBLE);
        binding.textSearchStatus.setVisibility(View.VISIBLE);
        binding.textSearchStatus.setText(message);
        binding.searchResultsContainer.removeAllViews();
    }

    private void hideSearchResults() {
        binding.cardSearchResults.setVisibility(View.GONE);
        binding.textSearchStatus.setVisibility(View.GONE);
        binding.searchResultsContainer.removeAllViews();
    }

    // ---------------------------------------------------------------- 回到实际位置

    private void locateNow() {
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            pendingLocate = true;
            toast(getString(R.string.campus_locate_permission));
            permissionLauncher.launch(new String[]{Manifest.permission.ACCESS_FINE_LOCATION});
            return;
        }
        LocationManager manager = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (manager == null || !manager.isLocationEnabled()) {
            GeoPoint cached = cachedDeviceLocation;
            if (cached != null && snapshot.isActive() && snapshot.singlePoint()
                    && applyPoint(cached)) {
                toast(getString(R.string.virtual_locate_cached));
            } else {
                toast(getString(R.string.campus_locate_off));
            }
            return;
        }
        GeoPoint cached = readCachedRealLocation();
        if (cached != null) {
            cachedDeviceLocation = cached;
            if (applyPoint(cached)) {
                toast(getString(snapshot.isActive() && snapshot.singlePoint()
                        ? R.string.virtual_locate_moved : R.string.campus_locate_device));
            }
            return;
        }
        if (snapshot.isActive() && snapshot.singlePoint()) {
            if (cachedDeviceLocation != null && applyPoint(cachedDeviceLocation)) {
                toast(getString(R.string.virtual_locate_cached));
            } else {
                toast(getString(R.string.virtual_locate_unavailable));
            }
            return;
        }
        requestLiveLocation(manager);
    }

    private void requestLiveLocation(LocationManager manager) {
        final int generation = ++locateGeneration;
        toast(getString(R.string.campus_locate_searching));
        mainHandler.postDelayed(() -> onLocateTimeout(generation), LOCATE_TIMEOUT_MS);
        String provider = manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                ? LocationManager.GPS_PROVIDER : LocationManager.NETWORK_PROVIDER;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                manager.getCurrentLocation(provider, null, getMainExecutor(),
                        location -> onLiveLocation(generation, location));
            } catch (SecurityException | IllegalArgumentException error) {
                DiagnosticLog.warn("回到实际位置失败：" + error);
                onLocateTimeout(generation);
            }
            return;
        }
        LocationListener listener = new LocationListener() {
            @Override
            public void onLocationChanged(@NonNull Location location) {
                onLiveLocation(generation, location);
            }

            @Override
            public void onStatusChanged(String provider, int status, Bundle extras) {
            }

            @Override
            public void onProviderEnabled(@NonNull String provider) {
            }

            @Override
            public void onProviderDisabled(@NonNull String provider) {
                onLocateTimeout(generation);
            }
        };
        try {
            manager.requestSingleUpdate(provider, listener, getMainLooper());
            liveLocationManager = manager;
            liveLocationListener = listener;
        } catch (SecurityException | IllegalArgumentException error) {
            DiagnosticLog.warn("回到实际位置失败：" + error);
            onLocateTimeout(generation);
        }
    }

    private void onLiveLocation(int generation, @Nullable Location location) {
        if (generation != locateGeneration) {
            return;
        }
        locateGeneration++;
        clearLiveLocation();
        if (location == null || isMockLocation(location)) {
            onLocateFailed();
            return;
        }
        cachedDeviceLocation = new GeoPoint(location.getLatitude(), location.getLongitude());
        boolean hold = snapshot.isActive() && snapshot.singlePoint();
        if (applyPoint(cachedDeviceLocation)) {
            toast(getString(hold ? R.string.virtual_locate_moved : R.string.campus_locate_device));
            hideSearchResults();
        }
    }

    private void onLocateTimeout(int generation) {
        if (generation != locateGeneration) {
            return;
        }
        locateGeneration++;
        clearLiveLocation();
        onLocateFailed();
    }

    private void onLocateFailed() {
        if (snapshot.isActive() && snapshot.singlePoint()) {
            if (cachedDeviceLocation != null && applyPoint(cachedDeviceLocation)) {
                toast(getString(R.string.virtual_locate_cached));
            } else {
                toast(getString(R.string.virtual_locate_unavailable));
            }
        } else {
            toast(getString(R.string.virtual_locate_failed));
        }
    }

    @Nullable
    private GeoPoint readCachedRealLocation() {
        SystemLocationReader.Probe best = null;
        for (SystemLocationReader.Probe probe : SystemLocationReader.readAll(this)) {
            if (!probe.hasFix() || probe.markedMock()) {
                continue;
            }
            if (best == null || probe.ageMillis() < best.ageMillis()) {
                best = probe;
            }
        }
        return best == null ? null : best.point();
    }

    private boolean isMockLocation(Location location) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return location.isMock();
        }
        return location.isFromMockProvider();
    }

    private void clearLiveLocation() {
        if (liveLocationManager != null && liveLocationListener != null) {
            try {
                liveLocationManager.removeUpdates(liveLocationListener);
            } catch (RuntimeException ignored) {
            }
        }
        liveLocationManager = null;
        liveLocationListener = null;
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
        if (hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            GeoPoint cached = readCachedRealLocation();
            if (cached != null) {
                cachedDeviceLocation = cached;
            }
        }
        startRequested = true;
        render();
        SimulationService.start(this, RunRequest.createSinglePoint(point));
        toast(getString(R.string.campus_toast_starting));
    }

    private void onPermissionResult(java.util.Map<String, Boolean> result) {
        boolean fineGranted = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION);
        boolean wantedStart = pendingStart;
        boolean wantedLocate = pendingLocate;
        pendingStart = false;
        pendingLocate = false;
        if (!fineGranted) {
            if (wantedStart) {
                toast(getString(R.string.campus_permission_missing));
            }
            if (wantedLocate) {
                toast(getString(R.string.campus_locate_permission));
            }
            return;
        }
        if (wantedStart) {
            ensureMockReady(this::beginHold);
        }
        if (wantedLocate) {
            locateNow();
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

    // ---------------------------------------------------------------- 小工具与生命周期

    private void setSelectableBackground(View view) {
        TypedValue value = new TypedValue();
        if (getTheme().resolveAttribute(android.R.attr.selectableItemBackground, value, true)) {
            view.setBackgroundResource(value.resourceId);
        }
    }

    private void hideKeyboard() {
        InputMethodManager manager = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (manager != null) {
            manager.hideSoftInputFromWindow(binding.inputPlaceSearch.getWindowToken(), 0);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

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
        ++locateGeneration;
        ++searchGeneration;
        mainHandler.removeCallbacksAndMessages(null);
        clearLiveLocation();
        searchExecutor.shutdownNow();
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
