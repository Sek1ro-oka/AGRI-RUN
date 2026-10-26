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
import org.localroute.simulation.MockFix;
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

    private static final String CHANNEL_ID = "simulation";
    private static final int NOTIFICATION_ID = 1001;
    private static final long WAKELOCK_TIMEOUT_MS = 90L * 60L * 1000L;
    /** 任务书 T11：两次 tick 的实际间隔超过这个值，就认为调度被阻塞，自动暂停而不是硬补一大段路线。 */
    private static final long MAX_TICK_GAP_NANOS = 5_000_000_000L;
    private static final double FIX_ALTITUDE_METERS = 10d;
    private static final double FIX_ACCURACY_METERS = 3d;

    private final LoopStateMachine stateMachine = new LoopStateMachine();
    private final List<String> publishedProviders = new ArrayList<>();
    private final SimulationStateRepo repo = SimulationStateRepo.get();

    private HandlerThread handlerThread;
    private Handler handler;
    private PowerManager.WakeLock wakeLock;

    private RunRequest request;
    private CompiledRoute route;
    private RunConfig config;
    private String runId = "";
    private String routeName = "";
    private MockLocationSession session;
    private org.localroute.core.ActiveElapsedClock clock;
    private RouteEngine.Progress lastProgress;
    private long lastElapsedMs;
    private long lastNotificationAt;
    private long lastTickNanos;
    private boolean stopped;

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
        createNotificationChannel();
        DiagnosticLog.info("SimulationService 已创建");
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (action == null) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        // 进入前台必须在 5 秒内完成；先挂一条“准备中”的通知，再执行真正的启动流程。
        ensureForeground(RunState.STARTING, "正在准备模拟位置…");

        if (ACTION_START.equals(action)) {
            handleStart();
        } else if (ACTION_PAUSE.equals(action)) {
            post(this::handlePause);
        } else if (ACTION_RESUME.equals(action)) {
            post(this::handleResume);
        } else if (ACTION_STOP.equals(action)) {
            post(this::handleStop);
        } else {
            DiagnosticLog.warn("未知的服务动作：" + action);
            stopSelf(startId);
        }
        return START_NOT_STICKY;
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
                publishSnapshot(RunState.STOPPED, null);
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

    private static void sendCommand(Context context, String action) {
        Intent intent = new Intent(context, SimulationService.class).setAction(action);
        try {
            context.startService(intent);
        } catch (RuntimeException e) {
            DiagnosticLog.error("发送服务命令失败：" + action, e);
        }
    }

    private void handleStart() {
        if (request != null) {
            // 重复点击“开始”：已有运行任务，直接忽略，不再创建第二个任务。
            DiagnosticLog.warn("已有运行任务，忽略重复的开始请求");
            return;
        }
        RunRequest pending = PendingRunRegistry.take();
        if (pending == null) {
            DiagnosticLog.warn("没有待执行的运行请求，服务退出");
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return;
        }

        request = pending;
        route = pending.route();
        config = pending.config();
        runId = pending.runId();
        routeName = pending.routeName();
        clock = new org.localroute.core.ActiveElapsedClock();
        lastProgress = null;
        lastElapsedMs = 0L;
        stopped = false;

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

        RouteEngine.Progress first = RouteEngine.progressAt(route, config, 0L);
        try {
            session.publish(fixFor(first, 0d, SystemClock.elapsedRealtimeNanos()));
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
        if (state == RunState.RUNNING && lastTickNanos > 0L
                && now - lastTickNanos > MAX_TICK_GAP_NANOS) {
            // T11：调度被阻塞（后台被冻结、系统卡顿等）。只把有效时间累计到上一次成功 tick，
            // 然后自动暂停，绝不一口气补掉这几秒——否则位置会突然跳出一大段。
            autoPauseForSchedulerGap(now - lastTickNanos);
            return;
        }
        long elapsed = clock.elapsedNanos(now);
        RouteEngine.Progress progress = RouteEngine.progressAt(route, config, elapsed);
        boolean paused = state == RunState.PAUSED;
        double speed = paused ? 0d : progress.speedMps();

        try {
            session.publish(fixFor(progress, speed, now));
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
        boolean posted = handler.postDelayed(tickRunnable, interval);
        if (!posted) {
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
        String reason = String.format(Locale.US,
                "调度间隔 %.1f 秒（上限 5 秒），已自动暂停；点「继续」从暂停点恢复",
                gapNanos / 1_000_000_000d);
        DiagnosticLog.warn(reason);
        repo.publish(baseSnapshot(RunState.PAUSED)
                .error(RunErrorCode.SCHEDULER_INTERRUPTED, reason)
                .build());
        updateNotification(RunState.PAUSED, lastProgress, false, reason);
        scheduleNextTick();
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
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return;
        }
        RunState state = stateMachine.state();
        if (state == RunState.RUNNING || state == RunState.PAUSED || state == RunState.STARTING) {
            try {
                stateMachine.stop();
            } catch (RuntimeException e) {
                DiagnosticLog.warn("停止时状态异常（已忽略）：" + e.getMessage());
            }
        }
        lastElapsedMs = clock == null ? lastElapsedMs : clock.elapsedNanos(SystemClock.elapsedRealtimeNanos()) / 1_000_000L;
        publishSnapshot(RunState.STOPPED, lastProgress);
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
    private void cleanupEverything() {
        stopped = true;
        if (handler != null) {
            handler.removeCallbacks(tickRunnable);
        }
        if (session != null) {
            session.stop();
            session = null;
        }
        releaseWakeLock();
        if (clock != null) {
            clock.pause(SystemClock.elapsedRealtimeNanos());
        }
        request = null;
        route = null;
        config = null;
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
        return new MockFix(progress.latitude(), progress.longitude(),
                FIX_ALTITUDE_METERS, FIX_ACCURACY_METERS, speedMps, progress.bearingDegrees(),
                System.currentTimeMillis(), elapsedRealtimeNanos);
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
        long now = System.currentTimeMillis();
        if (!finalState && now - lastNotificationAt < 1000L && note == null) {
            return;
        }
        lastNotificationAt = now;
        String text;
        if (progress == null) {
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
        if (!finalState) {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification,
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                            ? ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                            : 0);
        } else {
            manager.notify(NOTIFICATION_ID, notification);
        }
    }

    private void notifyError(RunErrorCode code, String message) {
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_route_notify)
                .setContentTitle("路线工坊：模拟位置已停止")
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
                .setContentTitle("路线工坊 · " + stateText(state))
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
        if (handler == null) {
            runnable.run();
            return;
        }
        handler.post(runnable);
    }
}
