package org.localroute.app.service;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.LocationManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

import org.localroute.app.R;
import org.localroute.app.diagnostics.DiagnosticLog;
import org.localroute.app.run.PendingRunRegistry;
import org.localroute.app.run.RunRequest;
import org.localroute.app.run.RunSnapshot;
import org.localroute.app.run.SimulationStateRepo;
import org.localroute.app.simulation.AndroidMockProviderApi;
import org.localroute.app.ui.MainActivity;
import org.localroute.core.CompiledRoute;
import org.localroute.core.LoopStateMachine;
import org.localroute.core.RouteEngine;
import org.localroute.core.RunConfig;
import org.localroute.core.RunErrorCode;
import org.localroute.core.RunState;
import org.localroute.core.TaskModeSwitch;
import org.localroute.simulation.MockFix;
import org.localroute.app.simulation.GmsFlpBackend;
import org.localroute.app.simulation.LocationOutputBackend;
import org.localroute.simulation.MockLocationSession;
import org.localroute.simulation.MockProviderException;
import org.localroute.simulation.ProviderSetupResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 前台模拟位置服务：唯一的“运行任务执行者”。
 *
 * <p>设计要点（任务书第 9、11 步）：
 * <ul>
 *   <li>{@code foregroundServiceType="location"}，通知栏固定提供暂停/继续/停止；</li>
 *   <li>独立 {@link HandlerThread} 按 {@link RunConfig#tickIntervalMs()} 调度，默认 500 ms；</li>
 *   <li>进度只由“有效运行时间”决定，因此调度被延迟时距离仍然连续（不补发、不跳变）；</li>
 *   <li>持有有期限的 PARTIAL WakeLock，锁屏后仍持续提交位置；</li>
 *   <li>{@code START_NOT_STICKY}：进程被杀后不会自动复活一个“看不见的运行”；</li>
 *   <li>停止/完成/出错都走同一个幂等清理流程，只移除本次创建的测试 provider。</li>
 * </ul>
 */
public class SimulationService extends Service {

    public static final String ACTION_START = "org.localroute.app.action.START";
    public static final String ACTION_PAUSE = "org.localroute.app.action.PAUSE";
    public static final String ACTION_RESUME = "org.localroute.app.action.RESUME";
    public static final String ACTION_STOP = "org.localroute.app.action.STOP";
    /** 运行中改速（主界面、悬浮窗、通知都走这一条命令）。 */
    public static final String ACTION_SET_SPEED = "org.localroute.app.action.SET_SPEED";
    public static final String EXTRA_SPEED_KMH = "org.localroute.app.extra.SPEED_KMH";

    /** 运行中改传送目标（悬浮条 A/B/C 用）：不新建任务、不重建会话，只换坐标。 */
    public static final String ACTION_TELEPORT = "org.localroute.app.action.TELEPORT";
    public static final String EXTRA_TELEPORT_LAT = "org.localroute.app.extra.TELEPORT_LAT";
    public static final String EXTRA_TELEPORT_LON = "org.localroute.app.extra.TELEPORT_LON";

    /**
     * 悬浮摇杆手动驱动：单点模式下按方向与速度持续推进，而不是每 200 ms 传送一小步。
     *
     * <p>速度写 0 表示松开摇杆，位置停在原地。方向用单位化的北/东分量表示，服务端负责
     * 换算航向并带进每一帧定位，目标 App 才能看出“人在走”而不是“点在地图上抖”。
     */
    public static final String ACTION_DRIVE = "org.localroute.app.action.DRIVE";
    public static final String EXTRA_DRIVE_NORTH = "org.localroute.app.extra.DRIVE_NORTH";
    public static final String EXTRA_DRIVE_EAST = "org.localroute.app.extra.DRIVE_EAST";
    public static final String EXTRA_DRIVE_SPEED_KMH = "org.localroute.app.extra.DRIVE_SPEED_KMH";

    private static final double METERS_PER_DEGREE_LAT = 111320d;
    /** 超过这个时间没有新的摇杆命令就自动松手（悬浮条被收起、进程被杀都不会让角色一直走）。 */
    private static final long DRIVE_TIMEOUT_MS = 2000L;
    /** 单帧位移上限：只在调度异常时兜底，正常 500 ms × 最高速远小于这个值。 */
    private static final double DRIVE_MAX_STEP_METERS = 30d;

    private static final String CHANNEL_ID = "simulation";
    private static final int NOTIFICATION_ID = 1001;
    private static final long WAKELOCK_TIMEOUT_MS = 90L * 60L * 1000L;
    /** 任务书 T11：两次 tick 的实际间隔超过这个值，就认为调度被阻塞，自动暂停而不是硬补一大段路线。 */
    private static final org.localroute.core.TickGapPolicy TICK_GAP_POLICY =
            org.localroute.core.TickGapPolicy.defaults();
    private static final double FIX_ALTITUDE_METERS = org.localroute.simulation.FixQualityProfile.ALTITUDE_METERS;
    /**
     * 提交位置的精度取值由 {@link org.localroute.simulation.FixQualityProfile} 统一决定（移动 3 米、静止 5 米）；
     * 这里不再保留同名常量，避免“精度”出现第二处定义。
     */

    private final LoopStateMachine stateMachine = new LoopStateMachine();
    private final List<String> publishedProviders = new ArrayList<>();
    private final SimulationStateRepo repo = SimulationStateRepo.get();

    private HandlerThread handlerThread;
    private Handler handler;
    /**
     * 同一时刻最多一条待执行 tick（R32-03）。开始 / 暂停 / 传送 / 模式切换 / 停止都走它，
     * 避免 {@code postDelayed} 叠加出多个发布循环。
     */
    private org.localroute.simulation.TickScheduler tickScheduler;
    private PowerManager.WakeLock wakeLock;

    private RunRequest request;
    private CompiledRoute route;
    private RunConfig config;
    private String runId = "";
    private String routeName = "";
    private MockLocationSession session;
    /** GMS FLP 模拟后端；未启用或不可用时为 null。 */
    private GmsFlpBackend gmsBackend;
    private org.localroute.core.ActiveElapsedClock clock;
    private RouteEngine.Progress lastProgress;
    /** 单点模式要固定发送的坐标；路线模式为 null。 */
    private org.localroute.core.GeoPoint singlePoint;
    /** 悬浮摇杆手动驱动：单位方向（北/东）与当前速度；速度为 0 表示松手。 */
    private double driveNorth;
    private double driveEast;
    private double driveSpeedKmh;
    /** 最后一次收到驱动的单调时钟；界面消失时用它自动松手，避免人物一直走。 */
    private long lastDriveCommandNanos;

    /** 每次传送目标变化 +1；在途 tick 用它判断自己算的是不是旧点位。 */
    private int teleportGeneration;
    private long lastElapsedMs;
    private long lastNotificationAt;
    private long lastTickNanos;
    /**
     * 分段累计距离：改速时冻结已走距离再换速度，保证位置连续（审查 R-009）。
     * 具体算法在 {@link org.localroute.core.DistancePhase} 里，可单测。
     */
    private org.localroute.core.DistancePhase phase =
            new org.localroute.core.DistancePhase(RunConfig.DEFAULT_SPEED_KMH);
    private volatile boolean stopped;
    /** 每次运行只记一条“发布字段”日志，避免每帧刷屏。 */
    private boolean loggedFixProfile;
    /** 实际发送间隔记录：用来回答「标称 500 ms 到底按多快发的」（docs/13 第三步）。 */
    private final org.localroute.simulation.PublishCadence cadence =
            new org.localroute.simulation.PublishCadence();

    private final Runnable tickRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                doTick();
            } catch (RuntimeException e) {
                failAndStop(RunErrorCode.SCHEDULER_INTERRUPTED, "调度线程异常：" + e);
            }
        }
    };

    // ---------------------------------------------------------------- 生命周期

    @Override
    public void onCreate() {
        super.onCreate();
        DiagnosticLog.init(this);
        handlerThread = new HandlerThread("route-simulation");
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper());
        tickScheduler = new org.localroute.simulation.TickScheduler(
                new org.localroute.simulation.TickScheduler.Queue() {
                    @Override
                    public boolean postDelayed(Runnable runnable, long delayMillis) {
                        return handler.postDelayed(runnable, delayMillis);
                    }

                    @Override
                    public void removeCallbacks(Runnable runnable) {
                        handler.removeCallbacks(runnable);
                    }
                }, tickRunnable);
        createNotificationChannel();
        DiagnosticLog.info("SimulationService 已创建");
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (action == null) {
            DiagnosticLog.warn("收到空动作，服务退出");
            stopAndPublishStoppedIfActive("未收到有效的运行命令");
            return START_NOT_STICKY;
        }

        if (ACTION_START.equals(action)) {
            // 进入前台必须在 5 秒内完成：先在主线程挂一条“准备中”的通知，真正的启动流程放到
            // 服务自己的 Handler 线程执行，避免与 tick / 停止命令同时改状态字段。
            ensureForeground(RunState.STARTING, "正在准备模拟位置…");
            post(this::handleStartSafely);
        } else if (ACTION_PAUSE.equals(action)) {
            post(this::handlePause);
        } else if (ACTION_RESUME.equals(action)) {
            post(this::handleResume);
        } else if (ACTION_STOP.equals(action)) {
            post(this::handleStop);
        } else if (ACTION_SET_SPEED.equals(action)) {
            final double kmh = intent.getDoubleExtra(EXTRA_SPEED_KMH, Double.NaN);
            post(() -> handleSetSpeed(kmh));
        } else if (ACTION_TELEPORT.equals(action)) {
            final double lat = intent.getDoubleExtra(EXTRA_TELEPORT_LAT, Double.NaN);
            final double lon = intent.getDoubleExtra(EXTRA_TELEPORT_LON, Double.NaN);
            post(() -> handleTeleport(lat, lon));
        } else if (ACTION_DRIVE.equals(action)) {
            final double north = intent.getDoubleExtra(EXTRA_DRIVE_NORTH, 0d);
            final double east = intent.getDoubleExtra(EXTRA_DRIVE_EAST, 0d);
            final double speedKmh = intent.getDoubleExtra(EXTRA_DRIVE_SPEED_KMH, 0d);
            post(() -> handleDrive(north, east, speedKmh));
        } else {
            DiagnosticLog.warn("未知的服务动作：" + action);
            stopAndPublishStoppedIfActive("收到未知的运行命令：" + action);
        }
        return START_NOT_STICKY;
    }

    /** 启动流程的兜底：任何未预料的异常都要变成可见的错误状态，不能把进程崩掉。 */
    private void handleStartSafely() {
        try {
            handleStart();
        } catch (RuntimeException e) {
            DiagnosticLog.error("启动模拟位置时出现未预料的异常：" + e, null);
            failAndStop(RunErrorCode.PROVIDER_SETUP_FAILED, "启动模拟位置失败：" + e);
        }
    }

    /**
     * 没有运行任务时的退出路径：如果界面还停在运行态（服务被系统提前销毁、收到非预期命令），
     * 先补一条「已停止」快照，避免界面永远显示运行中、停止按钮又不起作用。
     */
    private void stopAndPublishStoppedIfActive(String reason) {
        RunSnapshot current = repo.current();
        if (current != null && current.isActive()) {
            repo.publish(baseSnapshot(RunState.STOPPED).error(null, reason).build());
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // 从最近任务里划掉应用：立即停止，避免留下无人可停的模拟位置（任务书第 9 步）。
        DiagnosticLog.warn("任务被移除，停止模拟位置");
        post(() -> {
            if (request != null) {
                try {
                    stateMachine.stop();
                } catch (RuntimeException ignored) {
                    // 状态不允许时忽略，清理照旧。
                }
                if (stateMachine.state() == RunState.STOPPED) {
                    publishSnapshot(RunState.STOPPED, null);
                }
            }
            cleanupEverything();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        });
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        DiagnosticLog.info("SimulationService 即将销毁");
        cleanupEverything();
        if (handlerThread != null) {
            handlerThread.quitSafely();
            handlerThread = null;
        }
        super.onDestroy();
    }

    // ---------------------------------------------------------------- 启动流程

    /** 供界面调用：写入请求并启动前台服务。 */
    public static void start(Context context, RunRequest request) {
        PendingRunRegistry.stage(request);
        Intent intent = new Intent(context, SimulationService.class).setAction(ACTION_START);
        ContextCompat.startForegroundService(context, intent);
    }

    public static void pause(Context context) {
        sendCommand(context, ACTION_PAUSE);
    }

    public static void resume(Context context) {
        sendCommand(context, ACTION_RESUME);
    }

    public static void stop(Context context) {
        sendCommand(context, ACTION_STOP);
    }

    /**
     * 运行中改速（km/h）。主界面、悬浮窗、通知快捷操作都调这里，保证只有一条改速路径。
     *
     * <p>重复点同一个速度不会打断当前分段（服务端比较后直接忽略），所以可以放心连点。
     */
    public static void setSpeedKmh(Context context, double speedKmh) {
        Intent intent = new Intent(context, SimulationService.class)
                .setAction(ACTION_SET_SPEED)
                .putExtra(EXTRA_SPEED_KMH, speedKmh);
        try {
            context.startService(intent);
        } catch (RuntimeException e) {
            DiagnosticLog.error("发送改速命令失败", e);
        }
    }

    /**
     * 悬浮条快捷传送入口：没在跑就新起一个单点任务（前台服务启动路径，冷态也有效）；
     * 正在跑就把目标坐标换掉，会话不变。
     */
    public static void teleportTo(Context context, org.localroute.core.GeoPoint point) {
        RunSnapshot current = SimulationStateRepo.get().current();
        if (current == null || !current.isActive()) {
            // 冷态：从后台直接 startService 会被 Android 8+ 拒（后台启动限制），
            // 必须走 startForegroundService，也就是标准的「开始一次单点任务」路径。
            DiagnosticLog.info("悬浮条传送：当前没有运行任务，改为启动单点任务 " + point.format());
            start(context, RunRequest.createSinglePoint(point));
            return;
        }
        Intent intent = new Intent(context, SimulationService.class)
                .setAction(ACTION_TELEPORT)
                .putExtra(EXTRA_TELEPORT_LAT, point.latitude())
                .putExtra(EXTRA_TELEPORT_LON, point.longitude());
        try {
            context.startService(intent);
        } catch (RuntimeException e) {
            DiagnosticLog.error("发送传送命令失败", e);
        }
    }

    /**
     * 悬浮摇杆手动驱动：没有任务时先以 {@code anchor} 起一个单点任务，再下发方向/速度。
     *
     * <p>方向和速度由服务端在 500 ms 的调度节拍里推进位置，目标 App 能看到连续位移、非零速度
     * 与稳定航向；松开摇杆调用 {@link #releaseDrive(Context)} 让速度归零。
     */
    public static void drive(Context context, org.localroute.core.GeoPoint anchor,
                             double north, double east, double speedKmh) {
        RunSnapshot current = SimulationStateRepo.get().current();
        if (current == null || !current.isActive()) {
            if (anchor == null) {
                return;
            }
            DiagnosticLog.info("悬浮摇杆：当前没有运行任务，从 " + anchor.format() + " 开始手动移动");
            start(context, RunRequest.createSinglePoint(anchor));
        }
        Intent intent = new Intent(context, SimulationService.class)
                .setAction(ACTION_DRIVE)
                .putExtra(EXTRA_DRIVE_NORTH, north)
                .putExtra(EXTRA_DRIVE_EAST, east)
                .putExtra(EXTRA_DRIVE_SPEED_KMH, speedKmh);
        try {
            context.startService(intent);
        } catch (RuntimeException e) {
            DiagnosticLog.error("发送摇杆驱动命令失败", e);
        }
    }

    /** 松开摇杆：只对正在运行的任务下发“速度归零”，不会新建或结束任务。 */
    public static void releaseDrive(Context context) {
        RunSnapshot current = SimulationStateRepo.get().current();
        if (current == null || !current.isActive()) {
            return;
        }
        Intent intent = new Intent(context, SimulationService.class)
                .setAction(ACTION_DRIVE)
                .putExtra(EXTRA_DRIVE_SPEED_KMH, 0d);
        try {
            context.startService(intent);
        } catch (RuntimeException e) {
            DiagnosticLog.error("发送摇杆停止命令失败", e);
        }
    }

    private void handleTeleport(double latitude, double longitude) {
        org.localroute.core.GeoPoint point;
        try {
            point = new org.localroute.core.GeoPoint(latitude, longitude);
        } catch (RuntimeException e) {
            DiagnosticLog.warn("传送坐标非法：" + latitude + "," + longitude);
            return;
        }
        clearDrive();
        if (request == null) {
            // 服务收到了传送命令但还没有任务：按单点模式新起一个（沿用既有的权限/模拟应用检查）。
            start(this, RunRequest.createSinglePoint(point));
            return;
        }
        if (singlePoint == null) {
            // 路线运行中点 A/B/C：就地切换成单点任务。不做「忽略」，也不重建 provider 会话，
            // 这样地图上的位置立刻过去，用户能马上看到效果（N-02）。
            switchRouteTaskToSinglePoint(point);
            return;
        }
        singlePoint = point;
        teleportGeneration++;
        DiagnosticLog.info("传送目标改为 " + point.format() + "（会话不变）");
        try {
            publishFix(fixForPoint(point, SystemClock.elapsedRealtimeNanos()));
        } catch (MockProviderException e) {
            failAndStop(e.errorCode() == null ? RunErrorCode.PUBLISH_FAILED : e.errorCode(),
                    e.getMessage());
            return;
        }
        RunState current = stateMachine.state();
        publishSnapshot(current == RunState.PAUSED ? RunState.PAUSED : RunState.RUNNING, null);
        updateNotification(current == RunState.PAUSED ? RunState.PAUSED : RunState.RUNNING, null,
                false);
        scheduleNextTick();
    }

    /**
     * 悬浮摇杆方向/速度。速度 <= 0 等于松手：位置不再推进，下一帧按静止质量发布。
     *
     * <p>只有单点模式接受手动驱动；路线跑圈有自己的速度与几何，摇杆此时在界面上是锁定的，
     * 万一有迟到命令也直接忽略，不能把跑圈改成手动。
     */
    private void handleDrive(double north, double east, double speedKmh) {
        if (!Double.isFinite(north) || !Double.isFinite(east) || !Double.isFinite(speedKmh)) {
            DiagnosticLog.warn("忽略摇杆驱动：参数非法 " + north + "," + east + "," + speedKmh);
            return;
        }
        double magnitude = Math.hypot(north, east);
        if (speedKmh <= 0.01d || magnitude < 1e-4d) {
            clearDrive();
            if (request != null && singlePoint != null) {
                RunState state = stateMachine.state();
                if (state == RunState.RUNNING || state == RunState.PAUSED) {
                    // 立刻刷新快照，让界面与通知里的速度不用等下一帧。
                    publishSnapshot(state, null);
                }
            }
            return;
        }
        if (request == null || singlePoint == null) {
            DiagnosticLog.warn("忽略摇杆驱动：当前没有单点任务");
            return;
        }
        driveNorth = north / magnitude;
        driveEast = east / magnitude;
        driveSpeedKmh = Math.min(RunConfig.MAX_SPEED_KMH, speedKmh);
        lastDriveCommandNanos = SystemClock.elapsedRealtimeNanos();
        RunState state = stateMachine.state();
        if (state == RunState.RUNNING) {
            publishSnapshot(state, null);
        }
    }

    /** 松开摇杆：方向与速度归零，下一帧按静止质量发布，位置停在原地。 */
    private void clearDrive() {
        driveNorth = 0d;
        driveEast = 0d;
        driveSpeedKmh = 0d;
        lastDriveCommandNanos = 0L;
    }

    /** 摇杆方向（北/东分量）换算成罗盘航向：0=正北，90=正东。 */
    private double driveBearingDegrees() {
        return (Math.toDegrees(Math.atan2(driveEast, driveNorth)) + 360d) % 360d;
    }

    /**
     * 路线任务运行中收到传送命令：停止路线推进，改用同一个定位会话发单点坐标。
     *
     * <p>为什么不是「先 stop 再 start」：那样会拆掉 provider 会话、触发通知重建，中间还会有
     * 一次 STOPPED 快照，别的 App 可能把这段当成「运动结束」。就地改模式只换数据来源。
     */
    private void switchRouteTaskToSinglePoint(org.localroute.core.GeoPoint point) {
        long now = SystemClock.elapsedRealtimeNanos();
        clearDrive();
        RunState before = stateMachine.state();
        TaskModeSwitch.StateAction action = TaskModeSwitch.stateActionFor(before);
        DiagnosticLog.info("传送命令：路线任务切换为单点 " + point.format()
                + "（" + TaskModeSwitch.describe(before) + "）");
        if (action == TaskModeSwitch.StateAction.UNSUPPORTED) {
            // 正常路径不会走到这里（没有任务时 handleTeleport 的冷态分支已经接管），
            // 兜底按「运行中」处理，不再让状态机抛异常把整个任务清掉。
            DiagnosticLog.warn("当前状态 " + before + " 不在就地切换的预期范围，按运行中处理");
        }
        request = RunRequest.createSinglePoint(point);
        routeName = request.routeName();
        runId = request.runId();
        route = null;
        config = request.config();
        singlePoint = point;
        phase = new org.localroute.core.DistancePhase(0d, 0L);
        teleportGeneration++;
        lastProgress = null;
        lastElapsedMs = 0L;
        lastTickNanos = now;
        cadence.reset();
        clock.reset();
        if (TaskModeSwitch.clockActionFor(before) == TaskModeSwitch.ClockAction.RESTART) {
            clock.start(now);
        }
        try {
            publishFix(fixForPoint(point, now));
        } catch (MockProviderException e) {
            failAndStop(e.errorCode() == null ? RunErrorCode.PUBLISH_FAILED : e.errorCode(),
                    e.getMessage());
            return;
        }
        // 关键修复（R32-01）：只有 STARTING 才需要 providerReady。RUNNING 下调用它等于
        // 「RUNNING→RUNNING」，状态机里没有这条边，会抛 IllegalRunStateException 并被
        // runGuarded 当成失败清理——表现就是传送一帧后自己停住。
        if (TaskModeSwitch.requiresProviderReady(before)) {
            stateMachine.providerReady();
        }
        RunState published = TaskModeSwitch.publishedState(before);
        publishSnapshot(published, null);
        updateNotification(published, null, false);
        scheduleNextTick();
    }

    private static void sendCommand(Context context, String action) {
        Intent intent = new Intent(context, SimulationService.class).setAction(action);
        try {
            context.startService(intent);
        } catch (RuntimeException e) {
            DiagnosticLog.error("发送服务命令失败：" + action, e);
        }
    }

    private void handleStart() {
        RunRequest pending = PendingRunRegistry.take();
        if (request != null) {
            if (pending != null && pending.route() != null && singlePoint != null) {
                // 悬浮摇杆/传送留下的单点任务：用户从跑圈页发起了新的路线任务，
                // 就地结束单点并接着启动跑圈，避免「已有任务」把入口卡死。
                DiagnosticLog.info("收到新的跑圈任务：先结束当前单点任务");
                resetSinglePointForRestart();
            } else {
                // 重复点击“开始”：已有运行任务，直接忽略，不再创建第二个任务。
                DiagnosticLog.warn("已有运行任务，忽略重复的开始请求");
                return;
            }
        }
        if (pending == null) {
            DiagnosticLog.warn("没有待执行的运行请求，服务退出");
            stopAndPublishStoppedIfActive("没有待执行的运行请求");
            return;
        }

        request = pending;
        route = pending.route();
        config = pending.config();
        singlePoint = pending.singlePoint();
        runId = pending.runId();
        routeName = pending.routeName();
        clock = new org.localroute.core.ActiveElapsedClock();
        lastProgress = null;
        lastElapsedMs = 0L;
        phase = new org.localroute.core.DistancePhase(pending.config().speedKmh(), 0L);
        stopped = false;
        loggedFixProfile = false;
        cadence.reset();

        ensureReadyState();
        stateMachine.startRun();
        publishSnapshot(RunState.STARTING, null);

        if (!hasLocationPermission()) {
            failAndStop(RunErrorCode.LOCATION_PERMISSION_MISSING,
                    "缺少「精确位置」权限，无法向系统提交模拟位置");
            return;
        }
        if (!isLocationEnabled()) {
            failAndStop(RunErrorCode.LOCATION_DISABLED, "系统定位开关已关闭，请先在系统设置里打开定位");
            return;
        }

        session = new MockLocationSession(new AndroidMockProviderApi(this));
        ProviderSetupResult result = session.start();
        if (!result.success()) {
            failAndStop(result.errorCode() == null ? RunErrorCode.PROVIDER_SETUP_FAILED : result.errorCode(),
                    result.message() == null ? "创建测试 provider 失败" : result.message());
            return;
        }
        publishedProviders.clear();
        publishedProviders.addAll(result.publishedProviders());
        DiagnosticLog.info("测试 provider 已创建：" + publishedProviders
                + (result.skippedProviders().isEmpty() ? "" : "，跳过：" + result.skippedProviders()));

        if (singlePoint != null) {
            startSinglePointRun();
            return;
        }

        RouteEngine.Progress first = RouteEngine.progressAt(route, config, 0L);
        try {
            startGmsBackendIfEnabled();
            publishFix(fixFor(first, 0d, SystemClock.elapsedRealtimeNanos()));
            DiagnosticLog.fix("首次提交 " + first.positionText());
        } catch (MockProviderException e) {
            failAndStop(e.errorCode() == null ? RunErrorCode.PUBLISH_FAILED : e.errorCode(), e.getMessage());
            return;
        }

        long now = SystemClock.elapsedRealtimeNanos();
        clock.reset();
        clock.start(now);
        lastProgress = first;
        lastElapsedMs = 0L;
        lastTickNanos = now;
        stateMachine.providerReady();
        acquireWakeLock();
        publishSnapshot(RunState.RUNNING, first);
        updateNotification(RunState.RUNNING, first, false);
        scheduleNextTick();
    }

    /**
     * 在同一个前台服务里结束当前单点任务，把状态机放回 READY，供紧随其后的跑圈启动复用。
     *
     * <p>不调用 {@code stopSelf()}、不移除前台通知，避免系统在两次任务之间把服务收走。
     */
    private void resetSinglePointForRestart() {
        long now = SystemClock.elapsedRealtimeNanos();
        lastElapsedMs = clock == null ? lastElapsedMs : clock.elapsedNanos(now) / 1_000_000L;
        try {
            stateMachine.stop();
        } catch (RuntimeException e) {
            DiagnosticLog.warn("切换跑圈前停止单点任务异常（已忽略）：" + e.getMessage());
        }
        // 刻意不发布 STOPPED：调用方已经把这次切换当作「同一个任务换模式」，提前发终态会让
        // 跑圈页把旧单点的结束误判成自己路线结束，从而清掉归属并弹出结束提示。
        cleanupEverything();
        try {
            stateMachine.backToReady();
        } catch (RuntimeException e) {
            DiagnosticLog.warn("切换跑圈前恢复就绪状态异常（已忽略）：" + e.getMessage());
        }
        stopped = false;
    }

    /**
     * 单点模式启动：只提交一次目标坐标，成功后进入 RUNNING，之后每个 tick 重新提交同一坐标。
     *
     * <p>没有路线、没有单圈、没有圈数；速度固定 0，有效用时照常累计。
     */
    private void startSinglePointRun() {
        long now = SystemClock.elapsedRealtimeNanos();
        try {
            startGmsBackendIfEnabled();
            publishFix(fixForPoint(singlePoint, now));
            DiagnosticLog.fix("单点位置首次提交 " + singlePoint.format());
        } catch (MockProviderException e) {
            failAndStop(e.errorCode() == null ? RunErrorCode.PUBLISH_FAILED : e.errorCode(), e.getMessage());
            return;
        }
        clock.reset();
        clock.start(now);
        lastProgress = null;
        lastElapsedMs = 0L;
        lastTickNanos = now;
        stateMachine.providerReady();
        acquireWakeLock();
        publishSnapshot(RunState.RUNNING, null);
        updateNotification(RunState.RUNNING, null, false);
        scheduleNextTick();
    }

    // ---------------------------------------------------------------- 调度循环
    private void doTick() {
        if (stopped || request == null) {
            return;
        }
        RunState state = stateMachine.state();
        if (state != RunState.RUNNING && state != RunState.PAUSED) {
            return;
        }
        if (!hasLocationPermission()) {
            failAndStop(RunErrorCode.LOCATION_PERMISSION_MISSING, "定位权限已被撤回，运行停止");
            return;
        }
        if (!isLocationEnabled()) {
            failAndStop(RunErrorCode.LOCATION_DISABLED, "系统定位开关已关闭，运行停止");
            return;
        }
        if (!renewWakeLockIfNeeded()) {
            DiagnosticLog.warn("WakeLock 续期失败，继续运行");
        }

        long now = SystemClock.elapsedRealtimeNanos();
        if (state == RunState.RUNNING && TICK_GAP_POLICY.shouldPause(lastTickNanos, now)) {
            // T11：调度被阻塞（后台被冻结、系统卡顿等）。只把有效时间累计到上一次成功 tick，
            // 然后自动暂停，绝不一口气补掉这几秒——否则位置会突然跳出一大段。
            autoPauseForSchedulerGap(now - lastTickNanos);
            return;
        }
        long elapsed = clock.elapsedNanos(now);
        boolean paused = state == RunState.PAUSED;

        if (singlePoint != null) {
            // 悬停时位置固定；摇杆驱动时按速度推进，目标 App 才能看到连续位移与非零速度。
            if (driveSpeedKmh > 0.01d && lastDriveCommandNanos != 0L
                    && now - lastDriveCommandNanos > DRIVE_TIMEOUT_MS * 1_000_000L) {
                // 悬浮条收起或界面被系统回收后不会再有驱动命令：自动松手，人物不能一直走。
                DiagnosticLog.info("摇杆命令超时，自动停止手动移动");
                clearDrive();
            }
            boolean driving = !paused && driveSpeedKmh > 0.01d;
            if (driving) {
                long dtNanos = Math.max(0L, now - lastTickNanos);
                double stepMeters = Math.min(DRIVE_MAX_STEP_METERS,
                        driveSpeedKmh / 3.6d * dtNanos / 1_000_000_000d);
                if (stepMeters > 0.005d) {
                    double bearing = driveBearingDegrees();
                    double north = Math.cos(Math.toRadians(bearing)) * stepMeters;
                    double east = Math.sin(Math.toRadians(bearing)) * stepMeters;
                    double metersPerDegreeLon = Math.max(1d, METERS_PER_DEGREE_LAT
                            * Math.cos(Math.toRadians(singlePoint.latitude())));
                    singlePoint = new org.localroute.core.GeoPoint(
                            singlePoint.latitude() + north / METERS_PER_DEGREE_LAT,
                            singlePoint.longitude() + east / metersPerDegreeLon);
                }
            }
            final int generation = teleportGeneration;
            final org.localroute.core.GeoPoint target = singlePoint;
            final double fixSpeedMps = driving ? driveSpeedKmh / 3.6d : 0d;
            final double fixBearing = driving ? driveBearingDegrees() : 0d;
            try {
                publishFix(fixForPoint(target, now, fixSpeedMps, fixBearing));
            } catch (MockProviderException e) {
                failAndStop(e.errorCode() == null ? RunErrorCode.PUBLISH_FAILED : e.errorCode(), e.getMessage());
                return;
            }
            if (generation != teleportGeneration) {
                // 算这一帧的时候目标已经被改掉了：丢掉它，绝不用旧点位覆盖新传送。
                lastTickNanos = now;
                scheduleNextTick();
                return;
            }
            DiagnosticLog.fix(String.format(Locale.US, "单点 %s 速度=%.1fkm/h 用时=%dms",
                    singlePoint.format(), fixSpeedMps * 3.6d, elapsed / 1_000_000L));
            recordCadence(now);
            lastElapsedMs = elapsed / 1_000_000L;
            lastTickNanos = now;
            publishSnapshot(paused ? RunState.PAUSED : RunState.RUNNING, null);
            updateNotification(paused ? RunState.PAUSED : RunState.RUNNING, null, false);
            scheduleNextTick();
            return;
        }

        // 距离只由「分段累计」决定：暂停时有效时间不增长，所以位置自然停住；
        // 改速时只换段，不重算历史（R-009）。
        double speedMps = phase.speedMetersPerSecond();
        double distance = phase.distanceAt(elapsed);
        RouteEngine.Progress progress = RouteEngine.progressForDistance(
                route, config.loopCount(), distance, speedMps);
        double speed = paused ? 0d : speedMps;

        try {
            publishFix(fixFor(progress, speed, now));
        } catch (MockProviderException e) {
            failAndStop(e.errorCode() == null ? RunErrorCode.PUBLISH_FAILED : e.errorCode(), e.getMessage());
            return;
        }
        DiagnosticLog.fix(String.format(Locale.US, "%s 圈=%d/%d 距离=%.1fm 用时=%dms",
                progress.positionText(), progress.completedLoops(), progress.targetLoops(),
                progress.distanceMeters(), elapsed / 1_000_000L));

        lastProgress = progress;
        lastElapsedMs = elapsed / 1_000_000L;
        lastTickNanos = now;
        recordCadence(now);
        publishSnapshot(paused ? RunState.PAUSED : RunState.RUNNING, progress);
        updateNotification(paused ? RunState.PAUSED : RunState.RUNNING, progress, false);

        if (!paused && progress.finished()) {
            completeRun(progress);
            return;
        }
        scheduleNextTick();
    }

    private void scheduleNextTick() {
        if (stopped) {
            return;
        }
        long interval = config == null ? 500L : config.tickIntervalMs();
        // 唯一的排程入口（R32-03）：已有待执行 tick 时不会重复投递，
        // 因此连续点 A/B/C 不会叠出多条发布循环。
        boolean posted = tickScheduler != null && tickScheduler.schedule(interval);
        if (tickScheduler == null || (!posted && !tickScheduler.pending())) {
            failAndStop(RunErrorCode.SCHEDULER_INTERRUPTED, "调度线程已不可用，模拟位置停止");
        }
    }

    private void handlePause() {
        if (request == null || stateMachine.state() != RunState.RUNNING) {
            DiagnosticLog.warn("忽略暂停请求：当前状态 " + stateMachine.state());
            return;
        }
        long now = SystemClock.elapsedRealtimeNanos();
        clock.pause(now);
        stateMachine.pause();
        lastElapsedMs = clock.elapsedNanos(now) / 1_000_000L;
        publishSnapshot(RunState.PAUSED, lastProgress);
        updateNotification(RunState.PAUSED, lastProgress, false);
        DiagnosticLog.info("已暂停，有效用时 " + lastElapsedMs + "ms");
    }

    /**
     * T11：调度间隔超过 5 秒时自动暂停。
     *
     * <p>有效时间只累计到<b>上一次成功 tick 的时刻</b>，因此恢复后从暂停点继续，不会补算这段空白。
     * 状态仍是 PAUSED（可继续），但快照里带上 {@link RunErrorCode#SCHEDULER_INTERRUPTED} 与原因，
     * 界面与诊断日志都能看出“这不是用户点的暂停”。
     */
    private void autoPauseForSchedulerGap(long gapNanos) {
        long pausedAt = lastTickNanos;
        clock.pause(pausedAt);
        try {
            stateMachine.pause();
        } catch (RuntimeException e) {
            DiagnosticLog.warn("自动暂停时状态异常（已忽略）：" + e.getMessage());
        }
        lastElapsedMs = clock.elapsedNanos(pausedAt) / 1_000_000L;
        String reason = TICK_GAP_POLICY.reasonForGap(gapNanos);
        DiagnosticLog.warn(reason);
        repo.publish(baseSnapshot(RunState.PAUSED)
                .error(RunErrorCode.SCHEDULER_INTERRUPTED, reason)
                .build());
        updateNotification(RunState.PAUSED, lastProgress, false, reason);
        scheduleNextTick();
    }

    /**
     * 运行中改速：把当前距离冻结进 offset，再以新速度开一个新分段。
     *
     * <p>速度非法、任务不存在、单点模式（速度恒 0）或已在目标速度上，都只是记录日志后返回，
     * 不改变运行状态——改速永远不应该让一次运行失败。
     */
    private void handleSetSpeed(double speedKmh) {
        if (request == null) {
            DiagnosticLog.warn("忽略改速请求：没有正在运行的任务");
            return;
        }
        if (singlePoint != null) {
            DiagnosticLog.warn("忽略改速请求：单点模式速度固定为 0");
            return;
        }
        RunState state = stateMachine.state();
        if (state != RunState.RUNNING && state != RunState.PAUSED) {
            DiagnosticLog.warn("忽略改速请求：当前状态 " + state);
            return;
        }
        if (!Double.isFinite(speedKmh) || speedKmh < RunConfig.MIN_SPEED_KMH
                || speedKmh > RunConfig.MAX_SPEED_KMH) {
            DiagnosticLog.warn("忽略改速请求：速度非法 " + speedKmh);
            return;
        }
        if (Math.abs(speedKmh - phase.speedKmh()) < 1e-9d) {
            return;
        }
        long now = SystemClock.elapsedRealtimeNanos();
        long elapsed = clock.elapsedNanos(now);
        double previous = phase.speedKmh();
        double travelled = phase.changeSpeed(speedKmh, elapsed);
        config = config.withSpeedKmh(speedKmh);
        DiagnosticLog.info(String.format(Locale.US, "改速 %.1f → %.1f km/h（已走 %.1f 米）",
                previous, speedKmh, travelled));
        // 立刻刷新一次快照与通知，让界面上的速度不用等下一个 tick。
        // 新分段刚起步（本段用时为 0），位置仍停在冻结点，不跳变。
        RouteEngine.Progress progress = RouteEngine.progressForDistance(
                route, config.loopCount(), phase.distanceAt(elapsed), phase.speedMetersPerSecond());
        lastProgress = progress;
        publishSnapshot(state, progress);
        updateNotification(state, progress, false);
    }

    private void handleResume() {
        if (request == null || stateMachine.state() != RunState.PAUSED) {
            DiagnosticLog.warn("忽略继续请求：当前状态 " + stateMachine.state());
            return;
        }
        long now = SystemClock.elapsedRealtimeNanos();
        clock.start(now);
        lastTickNanos = now;
        stateMachine.resume();
        publishSnapshot(RunState.RUNNING, lastProgress);
        updateNotification(RunState.RUNNING, lastProgress, false);
        DiagnosticLog.info("已继续运行");
    }

    private void handleStop() {
        if (request == null) {
            // 重复停止：没有任何任务时直接退出，不报错（幂等）。
            stopAndPublishStoppedIfActive("没有正在运行的任务");
            return;
        }
        RunState state = stateMachine.state();
        boolean wasActive = state == RunState.RUNNING || state == RunState.PAUSED
                || state == RunState.STARTING;
        if (wasActive) {
            try {
                stateMachine.stop();
            } catch (RuntimeException e) {
                DiagnosticLog.warn("停止时状态异常（已忽略）：" + e.getMessage());
            }
        }
        lastElapsedMs = clock == null ? lastElapsedMs : clock.elapsedNanos(SystemClock.elapsedRealtimeNanos()) / 1_000_000L;
        if (wasActive) {
            // 只有真正打断运行才把快照改成「已停止」；已完成/失败的状态不该被停止覆盖掉。
            publishSnapshot(RunState.STOPPED, lastProgress);
        } else if (state == RunState.COMPLETED || state == RunState.ERROR) {
            DiagnosticLog.info("停止请求到达时任务已是终态 " + state + "，保留该状态");
        }
        cleanupEverything();
        updateNotification(RunState.STOPPED, lastProgress, true);
        stopForeground(STOP_FOREGROUND_DETACH);
        stopSelf();
        DiagnosticLog.info("运行已停止");
    }

    private void completeRun(RouteEngine.Progress progress) {
        DiagnosticLog.info("已完成目标圈数，位置回到起点：" + progress.positionText());
        try {
            stateMachine.complete();
        } catch (RuntimeException e) {
            DiagnosticLog.warn("完成时状态异常（已忽略）：" + e.getMessage());
        }
        publishSnapshot(RunState.COMPLETED, progress);
        cleanupEverything();
        updateNotification(RunState.COMPLETED, progress, true);
        stopForeground(STOP_FOREGROUND_DETACH);
        stopSelf();
    }

    private void failAndStop(RunErrorCode code, String message) {
        DiagnosticLog.error("运行失败：" + code + " / " + message, null);
        PendingRunRegistry.clear();
        try {
            if (stateMachine.isActive()) {
                stateMachine.fail(code, message);
            }
        } catch (RuntimeException e) {
            DiagnosticLog.warn("标记失败状态时异常（已忽略）：" + e.getMessage());
        }
        RunSnapshot snapshot = baseSnapshot(RunState.ERROR)
                .error(code, message)
                .build();
        repo.publish(snapshot);
        cleanupEverything();
        notifyError(code, message);
        stopForeground(STOP_FOREGROUND_DETACH);
        stopSelf();
    }

    // ---------------------------------------------------------------- 清理

    /** 幂等清理：停调度、移除测试 provider、释放 WakeLock、清零计时。 */
    /** 当前是否允许启用 GMS FLP 后端（默认关闭，避免影响其它使用 FLP 的应用）。 */
    private boolean gmsFlpEnabled() {
        return getSharedPreferences("ui", MODE_PRIVATE).getBoolean("gms_flp_enabled", false);
    }

    /** 一条帧同时送所有已启用的输出后端：framework 测试 provider 与 GMS FLP。 */
    private void publishFix(MockFix fix) throws MockProviderException {
        logFirstFixProfile(fix);
        if (session != null) {
            session.publish(fix);
        }
        if (gmsBackend != null) {
            gmsBackend.publish(fix);
        }
    }

    /**
     * 记录实际发送间隔，每 20 帧往诊断日志写一行。
     *
     * <p>「时快时慢」要么是调度被系统拖慢，要么是目标 App 自己滤波；这一行让前一种可以
     * 被直接排除：标称 500 ms 而中位 800 ms，问题在我们这边。
     */
    private void recordCadence(long nowNanos) {
        cadence.onTick(nowNanos);
        int samples = cadence.sampleCount();
        if (samples > 0 && samples % 20 == 0) {
            DiagnosticLog.info(cadence.summary(config == null
                    ? RunConfig.DEFAULT_TICK_INTERVAL_MS : config.tickIntervalMs()));
        }
    }

    /**
     * 首帧把“实际声明了哪些字段”写进诊断日志。
     *
     * <p>目标 App 不计里程时，第一个要排除的就是“我们发的字段它根本不认”；第二个是每帧位移
     * 太小被当成抖动。注意：这只是可疑条件，不是判定——目标 App 的门槛以它自己的日志为准。
     */
    private void logFirstFixProfile(MockFix fix) {
        if (loggedFixProfile) {
            return;
        }
        loggedFixProfile = true;
        DiagnosticLog.info("发布字段（每帧一致）：" + fix.quality().summary());
        long intervalMs = config == null ? RunConfig.DEFAULT_TICK_INTERVAL_MS : config.tickIntervalMs();
        double tickSeconds = intervalMs / 1000d;
        double stepMeters = fix.speedMps() * tickSeconds;
        if (fix.speedMps() > org.localroute.simulation.FixQualityProfile.MOVING_SPEED_THRESHOLD_MPS
                && stepMeters < fix.accuracyMeters()) {
            // R32-07：这里只记录观测事实与实验变量，不再给「提高速度 / 拉长间隔」这种结论式建议——
            // 是否被目标 App 当抖动丢掉，要靠目标 App 自己的日志判定。
            DiagnosticLog.warn(String.format(java.util.Locale.US,
                    "观测：每帧位移 %.1f 米 < 声明精度 %.1f 米（速度 %.1f km/h，发送间隔 %d ms）。"
                            + "这只是观测值，不是结论；若要验证是否被当作抖动丢弃，请一次只改一个变量"
                            + "（只改速度，或只改发送间隔），目标 App 版本与路线保持不变",
                    stepMeters, fix.accuracyMeters(), fix.speedMps() * 3.6d, intervalMs));
        }
    }

    /** 启动 GMS FLP 后端；不可用时只记原因，不影响 framework 通道。 */
    private void startGmsBackendIfEnabled() {
        if (!gmsFlpEnabled()) {
            DiagnosticLog.info("GMS FLP 后端：未启用（可在诊断页打开）");
            return;
        }
        gmsBackend = new GmsFlpBackend(this);
        LocationOutputBackend.Result result = gmsBackend.start();
        DiagnosticLog.info("GMS FLP 后端：" + result.message());
        if (result.success()) {
            publishedProviders.add(GmsFlpBackend.ID);
        } else {
            DiagnosticLog.warn("GMS FLP 后端不可用：" + result.message());
        }
    }
    private void cleanupEverything() {
        stopped = true;
        if (tickScheduler != null) {
            tickScheduler.cancel();
        }
        if (handler != null) {
            handler.removeCallbacks(tickRunnable);
        }
        if (session != null) {
            session.stop();
            session = null;
        }
        if (gmsBackend != null) {
            gmsBackend.stop();
            DiagnosticLog.info("GMS FLP 后端已停止：" + gmsBackend.statusText());
            gmsBackend = null;
        }
        releaseWakeLock();
        if (clock != null) {
            clock.pause(SystemClock.elapsedRealtimeNanos());
        }
        request = null;
        route = null;
        config = null;
        singlePoint = null;
        clearDrive();
    }

    // ---------------------------------------------------------------- 辅助

    private void ensureReadyState() {
        RunState state = stateMachine.state();
        if (state == RunState.IDLE) {
            stateMachine.markReady();
        } else if (state != RunState.READY) {
            stateMachine.backToReady();
        }
    }

    private MockFix fixFor(RouteEngine.Progress progress, double speedMps, long elapsedRealtimeNanos) {
        org.localroute.simulation.FixQualityProfile quality =
                org.localroute.simulation.FixQualityProfile.forSpeed(speedMps);
        return new MockFix(progress.latitude(), progress.longitude(),
                FIX_ALTITUDE_METERS, quality.accuracyMeters(), speedMps, progress.bearingDegrees(),
                System.currentTimeMillis(), elapsedRealtimeNanos, quality);
    }

    private MockFix fixForPoint(org.localroute.core.GeoPoint point, long elapsedRealtimeNanos) {
        return fixForPoint(point, elapsedRealtimeNanos, 0d, 0d);
    }

    /** 单点帧：手动移动时带上速度与航向，静止时按 0 速度发布。 */
    private MockFix fixForPoint(org.localroute.core.GeoPoint point, long elapsedRealtimeNanos,
                                double speedMps, double bearingDegrees) {
        org.localroute.simulation.FixQualityProfile quality =
                org.localroute.simulation.FixQualityProfile.forSpeed(speedMps);
        return new MockFix(point.latitude(), point.longitude(),
                FIX_ALTITUDE_METERS, quality.accuracyMeters(), speedMps, bearingDegrees,
                System.currentTimeMillis(), elapsedRealtimeNanos, quality);
    }

    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean isLocationEnabled() {
        LocationManager manager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (manager == null) {
            return false;
        }
        try {
            return manager.isLocationEnabled();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void acquireWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            return;
        }
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (powerManager == null) {
            return;
        }
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RouteWorkshop:simulation");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire(WAKELOCK_TIMEOUT_MS);
        DiagnosticLog.info("已获取 PARTIAL WakeLock（90 分钟上限）");
    }

    private boolean renewWakeLockIfNeeded() {
        if (wakeLock == null) {
            acquireWakeLock();
            return wakeLock != null && wakeLock.isHeld();
        }
        if (!wakeLock.isHeld()) {
            wakeLock.acquire(WAKELOCK_TIMEOUT_MS);
            DiagnosticLog.info("WakeLock 已续期");
        }
        return true;
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            DiagnosticLog.info("WakeLock 已释放");
        }
        wakeLock = null;
    }

    private RunSnapshot.Builder baseSnapshot(RunState state) {
        RunSnapshot.Builder builder = RunSnapshot.builder(state)
                .runId(runId)
                .targetLoops(config == null ? 0 : config.loopCount())
                .activeElapsedMs(lastElapsedMs)
                .publishedProviders(publishedProviders);
        if (singlePoint != null) {
            // 单点模式没有路线几何：单圈、距离、圈数都固定 0，界面用 singlePoint 标记走另一套文案。
            boolean driving = state == RunState.RUNNING && driveSpeedKmh > 0.01d;
            builder.singlePoint(true)
                    .position(singlePoint.latitude(), singlePoint.longitude())
                    .bearingDegrees(driving ? driveBearingDegrees() : 0d)
                    .speedMps(driving ? driveSpeedKmh / 3.6d : 0d)
                    .lapLengthMeters(0d)
                    .distanceMeters(0d)
                    .completedLoops(0)
                    .targetLoops(0);
            return builder;
        }
        if (route != null) {
            builder.lapLengthMeters(route.lapLengthMeters());
        }
        if (lastProgress != null) {
            builder.position(lastProgress.latitude(), lastProgress.longitude())
                    .bearingDegrees(lastProgress.bearingDegrees())
                    .distanceMeters(lastProgress.distanceMeters())
                    .completedLoops(lastProgress.completedLoops())
                    .speedMps(state == RunState.PAUSED ? 0d : lastProgress.speedMps());
        }
        return builder;
    }

    private void publishSnapshot(RunState state, @Nullable RouteEngine.Progress progress) {
        if (progress != null) {
            lastProgress = progress;
        }
        repo.publish(baseSnapshot(state).build());
    }

    // ---------------------------------------------------------------- 通知

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "路线模拟",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("显示模拟位置运行状态，并提供暂停/继续/停止");
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    private void ensureForeground(RunState state, String text) {
        Notification notification = buildNotification(state, text, false);
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                        ? ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                        : 0);
    }

    private void updateNotification(RunState state, @Nullable RouteEngine.Progress progress, boolean finalState) {
        updateNotification(state, progress, finalState, null);
    }

    private void updateNotification(RunState state, @Nullable RouteEngine.Progress progress, boolean finalState,
                                    @Nullable String note) {
        // 用单调时钟节流：用户改系统时间或自动校时回拨时，挂钟时间会让通知长时间不刷新。
        long now = SystemClock.elapsedRealtime();
        if (!finalState && now - lastNotificationAt < 1000L && note == null) {
            return;
        }
        lastNotificationAt = now;
        String text;
        if (progress == null && singlePoint != null) {
            text = String.format(Locale.US, "%s单点 %s · %s",
                    stateText(state), singlePoint.format(),
                    org.localroute.core.ActiveElapsedClock.formatDuration(lastElapsedMs * 1_000_000L));
        } else if (progress == null) {
            text = state == RunState.PAUSED ? "已暂停" : "正在准备模拟位置…";
        } else {
            text = String.format(Locale.US, "%s · 圈 %d/%d · %s · %s",
                    stateText(state), progress.completedLoops(), progress.targetLoops(),
                    RunSnapshot.formatMeters(progress.distanceMeters()),
                    org.localroute.core.ActiveElapsedClock.formatDuration(lastElapsedMs * 1_000_000L));
        }
        if (note != null) {
            text = note + " · " + text;
        }
        Notification notification = buildNotification(state, text, finalState);
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        // 服务在 ACTION_START 时已经 startForeground；后续只是更新同一条通知，
        // 用 notify() 更新即可，不必每 500 ms 重新进入前台（startForeground 更贵且会重置超时计时）。
        manager.notify(NOTIFICATION_ID, notification);
    }

    private void notifyError(RunErrorCode code, String message) {
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_route_notify)
                .setContentTitle("青禾校园：模拟位置已停止")
                .setContentText(code == null ? message : code.name() + " · " + message)
                .setStyle(new NotificationCompat.BigTextStyle()
                        .bigText((code == null ? "" : code.defaultMessage() + "\n") + message))
                .setOngoing(false)
                .setAutoCancel(true)
                .setContentIntent(openAppIntent())
                .build();
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, notification);
        }
    }

    private Notification buildNotification(RunState state, String text, boolean finalState) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_route_notify)
                .setContentTitle("青禾校园 · " + stateText(state))
                .setContentText(text)
                .setOnlyAlertOnce(true)
                .setContentIntent(openAppIntent())
                .setOngoing(!finalState)
                .setAutoCancel(finalState)
                .setPriority(NotificationCompat.PRIORITY_LOW);

        if (!finalState) {
            if (state == RunState.RUNNING) {
                builder.addAction(0, "暂停", serviceIntent(ACTION_PAUSE, 1));
            } else if (state == RunState.PAUSED) {
                builder.addAction(0, "继续", serviceIntent(ACTION_RESUME, 2));
            }
            builder.addAction(0, "停止", serviceIntent(ACTION_STOP, 3));
        }
        return builder.build();
    }

    private PendingIntent openAppIntent() {
        Intent intent = new Intent(this, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private PendingIntent serviceIntent(String action, int requestCode) {
        Intent intent = new Intent(this, SimulationService.class).setAction(action);
        return PendingIntent.getService(this, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static String stateText(RunState state) {
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

    private void post(Runnable runnable) {
        Handler target = handler;
        if (target == null) {
            runGuarded(runnable);
            return;
        }
        target.post(() -> runGuarded(runnable));
    }

    /** 所有服务命令都在 Handler 线程串行执行，并且不把异常抛到线程外面。 */
    private void runGuarded(Runnable runnable) {
        try {
            runnable.run();
        } catch (RuntimeException e) {
            DiagnosticLog.error("执行服务命令时异常：" + e, null);
            failAndStop(RunErrorCode.SCHEDULER_INTERRUPTED, "服务内部错误：" + e);
        }
    }
}
