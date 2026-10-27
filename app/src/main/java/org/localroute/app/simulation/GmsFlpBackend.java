package org.localroute.app.simulation;

import android.annotation.SuppressLint;
import android.content.Context;
import android.location.Location;
import android.os.SystemClock;
import android.util.Log;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.tasks.Task;

import org.localroute.simulation.MockFix;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Google Play services FLP 模拟后端：{@code setMockMode(true)} + {@code setMockLocation(...)}。
 *
 * <p>为什么单独做：framework 里注册成功一个叫 {@code fused} 的测试 provider，并不能证明
 * 使用 {@code FusedLocationProviderClient} 的客户端（含别的进程）也收到了——那是 GMS 自己的
 * 管线。这条后端是另一条标准模拟通道，输出依然带 mock 标记，不改变系统标记。
 *
 * <p>约束（见 docs/09 与官方文档）：设备必须有可用的 Google Play services；本应用仍需被选为
 * 模拟位置信息应用；模拟模式会影响其它使用 FLP 的应用，因此默认关闭、由用户显式开启，并在
 * 停止时恢复。单个在途任务 + generation 守卫，避免迟到回调覆盖新会话状态。
 */
// 调用方（服务/诊断页）在用前已确保 ACCESS_FINE_LOCATION；内部又逐个 catch SecurityException。
@SuppressLint("MissingPermission")
public final class GmsFlpBackend implements LocationOutputBackend {

    public static final String ID = "gms-flp";
    private static final String TAG = "GmsFlpBackend";

    private final Context context;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private FusedLocationProviderClient client;
    private volatile int generation;
    private volatile boolean inFlight;
    private volatile String status = "未启动";
    private volatile int droppedFrames;
    private volatile int failedFrames;

    public GmsFlpBackend(Context context) {
        this.context = context.getApplicationContext();
    }

    /** 设备是否具备可用的 Google Play services（不含是否被选为模拟应用）。 */
    public static boolean isPlayServicesAvailable(Context context) {
        int code = GoogleApiAvailability.getInstance()
                .isGooglePlayServicesAvailable(context.getApplicationContext());
        return code == ConnectionResult.SUCCESS;
    }

    public static String unavailableReason(Context context) {
        int code = GoogleApiAvailability.getInstance()
                .isGooglePlayServicesAvailable(context.getApplicationContext());
        if (code == ConnectionResult.SUCCESS) {
            return "可用";
        }
        return "UNAVAILABLE（Google Play services 状态码 " + code + "）";
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Result start() {
        if (!isPlayServicesAvailable(context)) {
            status = unavailableReason(context);
            return Result.unavailable("设备没有可用的 Google Play services，GMS FLP 后端未启用：" + status);
        }
        client = LocationServices.getFusedLocationProviderClient(context);
        generation++;
        started.set(true);
        inFlight = false;
        droppedFrames = 0;
        failedFrames = 0;
        status = "已进入模拟模式";
        // 进入模拟模式：失败（比如没有被选为模拟位置应用）必须如实报出。
        Task<Void> task = client.setMockMode(true);
        final int startGeneration = generation;
        final boolean[] decided = {false};
        task.addOnCompleteListener(done -> {
            if (startGeneration != generation) {
                return;
            }
            decided[0] = true;
            if (!done.isSuccessful()) {
                status = "进入模拟模式失败：" + describe(done.getException());
                Log.w(TAG, status);
            }
        });
        return Result.ok("已请求进入 GMS 模拟模式（" + ID + "），实际结果见诊断页");
    }

    @Override
    public void publish(MockFix fix) {
        if (!started.get() || client == null) {
            return;
        }
        // 单序列、有限在途：上一帧还没回来就丢掉这一帧，避免积压与倒序完成。
        if (inFlight) {
            droppedFrames++;
            return;
        }
        inFlight = true;
        final int frameGeneration = generation;
        Location location = new Location("gms-flp");
        location.setLatitude(fix.latitude());
        location.setLongitude(fix.longitude());
        location.setAltitude(fix.altitudeMeters());
        location.setAccuracy((float) fix.accuracyMeters());
        location.setSpeed((float) fix.speedMps());
        location.setBearing((float) fix.bearingDegrees());
        location.setTime(fix.timeMillis());
        location.setElapsedRealtimeNanos(fix.elapsedRealtimeNanos() > 0L
                ? fix.elapsedRealtimeNanos() : SystemClock.elapsedRealtimeNanos());

        client.setMockLocation(location).addOnCompleteListener(task -> {
            inFlight = false;
            if (frameGeneration != generation) {
                // 会话已切换/停止：迟到结果只记录，不写状态。
                Log.d(TAG, "迟到的 FLP 结果已忽略（generation 不匹配）");
                return;
            }
            if (!task.isSuccessful()) {
                failedFrames++;
                status = "提交失败：" + describe(task.getException());
            } else {
                status = "运行中（丢弃 " + droppedFrames + " 帧）";
            }
        });
    }

    @Override
    public void stop() {
        int stopGeneration = ++generation;
        started.set(false);
        FusedLocationProviderClient stopping = client;
        client = null;
        inFlight = false;
        if (stopping == null) {
            status = "已停止（未启动过）";
            return;
        }
        stopping.setMockMode(false).addOnCompleteListener(task -> {
            if (stopGeneration != generation) {
                return;
            }
            status = task.isSuccessful()
                    ? "已退出模拟模式（丢弃 " + droppedFrames + " 帧，失败 " + failedFrames + " 帧）"
                    : "退出模拟模式失败：" + describe(task.getException());
        });
    }

    public String statusText() {
        return status;
    }

    public boolean isActive() {
        return started.get();
    }

    private static String describe(Exception error) {
        if (error == null) {
            return "未知原因";
        }
        String message = error.getMessage();
        return error.getClass().getSimpleName() + (message == null ? "" : "：" + message);
    }
}
