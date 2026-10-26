package org.localroute.app.ui;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.MenuItem;
import android.widget.EditText;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModelProvider;

import org.localroute.app.R;
import org.localroute.app.databinding.ActivityMainBinding;
import org.localroute.app.diagnostics.DiagnosticLog;
import org.localroute.app.map.RouteMapController;
import org.localroute.app.model.RouteDefinition;
import org.localroute.app.model.RouteJsonCodec;
import org.localroute.app.model.RouteSummary;
import org.localroute.app.run.RunRequest;
import org.localroute.app.run.RunSnapshot;
import org.localroute.app.run.SimulationStateRepo;
import org.localroute.app.service.SimulationService;
import org.localroute.app.settings.SettingsInspector;
import org.localroute.app.storage.RouteRepository;
import org.localroute.core.CompiledRoute;
import org.localroute.core.GeoPoint;
import org.localroute.core.InvalidRouteException;
import org.localroute.core.RouteCompiler;
import org.localroute.core.RunConfig;
import org.localroute.core.RunState;
import org.maplibre.android.MapLibre;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 主界面：地图选点、路线编辑、参数输入、运行控制与状态展示。
 *
 * <p>本类不持有运行状态：开始/暂停/继续/停止都只是给 {@link SimulationService} 发命令，
 * 真正的状态由服务写入 {@link SimulationStateRepo}，界面只订阅并渲染。
 */
public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;
    private RouteRepository repository;
    private RouteEditorViewModel viewModel;
    private RouteMapController mapController;
    private ActivityResultLauncher<String[]> permissionLauncher;

    private RunSnapshot snapshot = RunSnapshot.idle();
    private CompiledRoute pendingRoute;
    private RunConfig pendingConfig;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DiagnosticLog.init(getApplicationContext());
        // MapLibre 要求在使用 MapView 之前完成初始化。
        MapLibre.getInstance(this);

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        repository = new RouteRepository(new File(getFilesDir(), "routes"));
        viewModel = new ViewModelProvider(this).get(RouteEditorViewModel.class);
        permissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(), this::onPermissionResult);

        mapController = RouteMapController.attach(binding.mapView, point -> {
            if (viewModel.addPoint(point)) {
                refreshDraft(false);
            }
        });
        mapController.registerGestureListener();

        binding.toolbar.setOnMenuItemClickListener(this::onMenuItemClick);
        binding.buttonUndo.setOnClickListener(v -> {
            if (viewModel.undo() != null) {
                refreshDraft(false);
            } else {
                toast("没有可撤销的节点");
            }
        });
        binding.buttonClear.setOnClickListener(v -> confirmClear());
        binding.buttonManual.setOnClickListener(v -> showManualInputDialog());
        binding.buttonStart.setOnClickListener(v -> onStartClicked());
        binding.buttonPause.setOnClickListener(v -> {
            if (snapshot.state() == RunState.RUNNING) {
                SimulationService.pause(this);
            }
        });
        binding.buttonResume.setOnClickListener(v -> {
            if (snapshot.state() == RunState.PAUSED) {
                SimulationService.resume(this);
            }
        });
        binding.buttonStop.setOnClickListener(v -> {
            if (snapshot.isActive()) {
                SimulationService.stop(this);
            } else {
                toast("当前没有运行中的任务");
            }
        });

        if (savedInstanceState == null) {
            binding.inputSpeed.setText(String.format(Locale.US, "%.1f", RunConfig.DEFAULT_SPEED_KMH));
            binding.inputLoops.setText(String.valueOf(RunConfig.DEFAULT_LOOPS));
        }

        SimulationStateRepo.get().snapshot().observe(this, next -> {
            snapshot = next;
            render();
        });

        refreshDraft(true);
        warnAboutRepositoryProblems();
    }

    // ---------------------------------------------------------------- 生命周期

    @Override
    protected void onStart() {
        super.onStart();
        mapController.onStart();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mapController.onResume();
        refreshDraft(true);
    }

    @Override
    protected void onPause() {
        mapController.onPause();
        super.onPause();
    }

    @Override
    protected void onStop() {
        mapController.onStop();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        mapController.onDestroy();
        super.onDestroy();
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        mapController.onLowMemory();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        mapController.onSaveInstanceState(outState);
    }

    // ---------------------------------------------------------------- 渲染

    private void refreshDraft(boolean fit) {
        List<GeoPoint> points = viewModel.points();
        mapController.renderRoute(points);
        if (fit) {
            mapController.fitRoute(points);
        }
        render();
    }

    private void render() {
        RunState state = snapshot.state();
        binding.textState.setText(stateText(state));
        binding.textState.setTextColor(ContextCompat.getColor(this, stateColor(state)));
        binding.textElapsed.setText(snapshot.activeElapsedMs() > 0 ? snapshot.elapsedText() : "—");
        binding.textSpeed.setText(snapshot.speedMps() > 0
                ? String.format(Locale.US, "%.1f km/h", snapshot.speedKmh())
                : "—");
        binding.textDistance.setText(snapshot.distanceMeters() > 0 ? snapshot.distanceText() : "—");
        binding.textLoops.setText(snapshot.targetLoops() > 0 ? snapshot.loopsText() : "—");
        binding.textPosition.setText(snapshot.positionText());
        binding.textProviders.setText(snapshot.providersText());

        double lap = snapshot.lapLengthMeters();
        if (lap <= 0) {
            CompiledRoute draftRoute = viewModel.compileOrNull();
            lap = draftRoute == null ? 0d : draftRoute.lapLengthMeters();
        }
        binding.textLap.setText(lap > 0 ? RunSnapshot.formatMeters(lap) : "—");

        boolean active = snapshot.isActive();
        binding.buttonStart.setEnabled(!active);
        binding.buttonPause.setEnabled(state == RunState.RUNNING);
        binding.buttonResume.setEnabled(state == RunState.PAUSED);
        binding.buttonStop.setEnabled(active);
        binding.buttonUndo.setEnabled(viewModel.canUndo());
        binding.buttonClear.setEnabled(!viewModel.points().isEmpty());

        updateRunnerMarker();
        binding.textHint.setText(hintText(state));
    }

    private void updateRunnerMarker() {
        if (snapshot.hasPosition() && !Double.isNaN(snapshot.latitude())
                && !Double.isNaN(snapshot.longitude())) {
            GeoPoint position = new GeoPoint(snapshot.latitude(), snapshot.longitude());
            mapController.renderRunner(position);
            if (snapshot.state() == RunState.RUNNING) {
                mapController.followRunner(position);
            }
        } else {
            mapController.renderRunner(null);
        }
    }

    private String hintText(RunState state) {
        if (state == RunState.ERROR) {
            String code = snapshot.errorCode() == null ? "" : snapshot.errorCode().name() + " · ";
            String message = snapshot.errorMessage() == null ? "未知错误" : snapshot.errorMessage();
            return code + message;
        }
        if (state == RunState.PAUSED && snapshot.errorMessage() != null) {
            return "已自动暂停：" + snapshot.errorMessage();
        }
        if (state == RunState.PAUSED) {
            return "已暂停：仍按节奏提交 0 速度的同一位置";
        }
        if (state == RunState.COMPLETED) {
            return "已完成 " + snapshot.targetLoops() + " 圈，位置已回到起点";
        }
        if (state == RunState.STOPPED) {
            return "已停止；再次开始会在起点重新跑";
        }
        return viewModel.validationMessage();
    }

    private int stateColor(RunState state) {
        if (state == RunState.ERROR) {
            return R.color.state_error;
        }
        if (state == RunState.PAUSED) {
            return R.color.state_paused;
        }
        if (state == RunState.RUNNING || state == RunState.COMPLETED) {
            return R.color.state_running;
        }
        return R.color.brand_primary;
    }

    private String stateText(RunState state) {
        switch (state) {
            case IDLE:
                return "空闲";
            case READY:
                return "就绪";
            case STARTING:
                return "启动中";
            case RUNNING:
                return "运行中";
            case PAUSED:
                return "已暂停";
            case COMPLETED:
                return "已完成";
            case STOPPED:
                return "已停止";
            case ERROR:
                return "出错";
            default:
                return state.name();
        }
    }

    // ---------------------------------------------------------------- 开始运行

    private void onStartClicked() {
        if (snapshot.isActive()) {
            toast("已有运行任务；请先停止，重复点击不会创建第二个任务");
            return;
        }
        List<GeoPoint> points = viewModel.points();
        if (points.size() < 2) {
            toast("至少需要 2 个不同的节点才能运行");
            return;
        }
        CompiledRoute route;
        try {
            route = RouteCompiler.compile(points);
        } catch (InvalidRouteException e) {
            toast(e.getMessage() == null ? "路线无效" : e.getMessage());
            return;
        }
        RunConfig config;
        try {
            config = readConfigFromInputs();
        } catch (IllegalArgumentException e) {
            toast(e.getMessage() == null ? "参数不合法" : e.getMessage());
            return;
        }
        requestPermissionsThenStart(route, config);
    }

    private RunConfig readConfigFromInputs() {
        String speedText = binding.inputSpeed.getText().toString().trim();
        String loopsText = binding.inputLoops.getText().toString().trim();
        double speed;
        int loops;
        try {
            speed = Double.parseDouble(speedText);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("速度必须是数字（0.5—60 km/h）");
        }
        try {
            loops = Integer.parseInt(loopsText);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("圈数必须是整数（1—1000）");
        }
        return new RunConfig(speed, loops);
    }

    private void requestPermissionsThenStart(CompiledRoute route, RunConfig config) {
        List<String> needed = new ArrayList<>();
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && !hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (needed.isEmpty()) {
            startWithSetupCheck(route, config);
            return;
        }
        pendingRoute = route;
        pendingConfig = config;
        toast("需要定位权限才能提交模拟位置");
        permissionLauncher.launch(needed.toArray(new String[0]));
    }

    private void onPermissionResult(java.util.Map<String, Boolean> result) {
        CompiledRoute route = pendingRoute;
        RunConfig config = pendingConfig;
        pendingRoute = null;
        pendingConfig = null;
        boolean fineGranted = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION);
        if (!fineGranted) {
            toast("缺少「精确位置」权限，无法运行");
            return;
        }
        if (route != null && config != null) {
            startWithSetupCheck(route, config);
        }
    }

    private void startWithSetupCheck(CompiledRoute route, RunConfig config) {
        SettingsInspector.MockProbeResult probe = SettingsInspector.probeMockProvider(this);
        if (probe == SettingsInspector.MockProbeResult.ALLOWED) {
            launchRun(route, config);
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
                .setTitle("还不能开始运行")
                .setMessage(message)
                .setPositiveButton("去设置检查", (d, w) ->
                        startActivity(new Intent(this, SettingsCheckActivity.class)))
                .setNegativeButton("取消", null)
                .show();
    }

    private void launchRun(CompiledRoute route, RunConfig config) {
        RunRequest request = RunRequest.create(viewModel.routeName(), route, config);
        SimulationService.start(this, request);
        DiagnosticLog.info("界面请求开始运行：" + request.runId());
        binding.textHint.setText("正在启动模拟位置…");
    }

    private boolean hasPermission(String permission) {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED;
    }

    // ---------------------------------------------------------------- 路线编辑

    private void confirmClear() {
        if (viewModel.points().isEmpty()) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("清空路线")
                .setMessage("将删除当前草稿的全部节点，确定继续？")
                .setPositiveButton("清空", (d, w) -> {
                    viewModel.clearPoints();
                    refreshDraft(true);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showManualInputDialog() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint("纬度, 经度（WGS-84，纬度在前），例如 30.123456, 120.123456");
        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        input.setPadding(padding, padding, padding, padding);
        new AlertDialog.Builder(this)
                .setTitle("手动输入经纬度")
                .setView(input)
                .setPositiveButton("添加", (d, w) -> {
                    GeoPoint point = parseCoordinate(input.getText().toString());
                    if (point == null) {
                        toast("格式不对：请输入「纬度, 经度」，如 30.123456, 120.123456");
                        return;
                    }
                    if (viewModel.addPoint(point)) {
                        refreshDraft(false);
                    } else {
                        toast("与上一个节点重合，或已达 5000 个节点上限");
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private GeoPoint parseCoordinate(String raw) {
        if (raw == null) {
            return null;
        }
        String[] parts = raw.trim().split("[,，\\s]+");
        if (parts.length != 2) {
            return null;
        }
        try {
            double latitude = Double.parseDouble(parts[0]);
            double longitude = Double.parseDouble(parts[1]);
            return new GeoPoint(latitude, longitude);
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- 菜单

    private boolean onMenuItemClick(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_save) {
            saveRoute();
            return true;
        }
        if (id == R.id.action_load) {
            showLoadDialog();
            return true;
        }
        if (id == R.id.action_rename) {
            showRenameDialog();
            return true;
        }
        if (id == R.id.action_delete) {
            confirmDelete();
            return true;
        }
        if (id == R.id.action_settings_check) {
            startActivity(new Intent(this, SettingsCheckActivity.class));
            return true;
        }
        if (id == R.id.action_diagnostics) {
            startActivity(new Intent(this, DiagnosticsActivity.class));
            return true;
        }
        return false;
    }

    private void saveRoute() {
        if (viewModel.points().isEmpty()) {
            toast("路线还是空的，先添加节点");
            return;
        }
        if (viewModel.routeName().isEmpty()) {
            promptName("保存路线", defaultRouteName(), name -> {
                viewModel.rename(name);
                doSave();
            });
            return;
        }
        doSave();
    }

    private void doSave() {
        RouteDefinition route = viewModel.toDefinition();
        try {
            repository.save(route);
            viewModel.markSaved();
            toast("已保存：" + route.displayName());
            DiagnosticLog.info("路线已保存：" + route.id());
        } catch (IOException | RuntimeException e) {
            DiagnosticLog.error("保存路线失败", e);
            toast("保存失败：" + e.getMessage());
        }
    }

    private String defaultRouteName() {
        return "路线 " + new java.text.SimpleDateFormat("MM-dd HH:mm", Locale.US)
                .format(new java.util.Date());
    }

    private void showLoadDialog() {
        List<RouteSummary> summaries = repository.list();
        if (summaries.isEmpty()) {
            toast("还没有保存的路线");
            return;
        }
        String[] labels = new String[summaries.size()];
        for (int i = 0; i < summaries.size(); i++) {
            RouteSummary summary = summaries.get(i);
            labels[i] = String.format(Locale.US, "%s（%d 点）", summary.displayName(), summary.pointCount());
        }
        new AlertDialog.Builder(this)
                .setTitle("加载路线")
                .setItems(labels, (d, which) -> loadRoute(summaries.get(which)))
                .setNegativeButton("取消", null)
                .show();
    }

    private void loadRoute(RouteSummary summary) {
        try {
            RouteDefinition route = repository.load(summary.id());
            viewModel.load(route);
            refreshDraft(true);
            toast("已加载：" + route.displayName());
        } catch (IOException | RouteJsonCodec.RouteFormatException | RuntimeException e) {
            DiagnosticLog.error("加载路线失败：" + summary.id(), e);
            toast("加载失败：" + e.getMessage());
        }
    }

    private void showRenameDialog() {
        promptName("重命名路线", viewModel.routeName().isEmpty() ? defaultRouteName() : viewModel.routeName(),
                name -> {
                    viewModel.rename(name);
                    if (viewModel.isPersisted()) {
                        doSave();
                    }
                    render();
                });
    }

    private void confirmDelete() {
        if (!viewModel.isPersisted()) {
            new AlertDialog.Builder(this)
                    .setTitle("删除草稿")
                    .setMessage("当前草稿还没有保存，直接丢弃？")
                    .setPositiveButton("丢弃", (d, w) -> {
                        viewModel.newDraft();
                        refreshDraft(true);
                    })
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("删除路线")
                .setMessage("将删除「" + viewModel.displayName() + "」的本地文件，无法恢复。")
                .setPositiveButton("删除", (d, w) -> {
                    boolean deleted = repository.delete(viewModel.routeId());
                    viewModel.markDeleted();
                    viewModel.newDraft();
                    refreshDraft(true);
                    toast(deleted ? "已删除" : "文件已不存在");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private interface NameConsumer {
        void accept(String name);
    }

    private void promptName(String title, String initial, NameConsumer consumer) {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setText(initial);
        input.setSelectAllOnFocus(true);
        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        input.setPadding(padding, padding, padding, padding);
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(input)
                .setPositiveButton("确定", (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) {
                        toast("名称不能为空");
                        return;
                    }
                    consumer.accept(name);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void warnAboutRepositoryProblems() {
        List<String> warnings = repository.drainWarnings();
        if (warnings.isEmpty()) {
            return;
        }
        DiagnosticLog.warn("路线库存在问题：" + warnings.size() + " 个文件");
        new AlertDialog.Builder(this)
                .setTitle("路线文件有问题")
                .setMessage("有 " + warnings.size() + " 个文件无法解析，已移到 corrupt/ 目录备份（不会覆盖原文件）。\n\n"
                        + String.join("\n", warnings))
                .setPositiveButton("知道了", null)
                .show();
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
