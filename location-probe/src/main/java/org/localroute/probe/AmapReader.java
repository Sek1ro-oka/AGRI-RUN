package org.localroute.probe;

import android.content.Context;
import android.util.Log;

import com.amap.api.location.AMapLocation;
import com.amap.api.location.AMapLocationClient;
import com.amap.api.location.AMapLocationClientOption;
import com.amap.api.location.AMapLocationClientOption.AMapLocationMode;
import com.amap.api.location.AMapLocationListener;

/**
 * 高德定位 SDK 读取通道。
 *
 * <p>为什么需要它：目标 App（智慧浙农林 2.51）的跑步模块用的是 {@code AMapLocationClient}
 * 而不是系统 {@code LocationManager} 或 FLP。系统四通道收到帧，只能证明「我们发得对」，
 * 证明不了「高德 SDK 交给运动模块的是有效帧」。这条通道补的就是这一段证据。
 *
 * <p>两条并行通道（同一次会话内）：
 * <ul>
 *   <li>{@link #CHANNEL_PRIMARY}：按目标的接收策略配置 —— 高精度、GPS 优先、`Sport` 场景、
 *       {@code setMockEnable(false)}。它反映目标会看到什么。</li>
 *   <li>{@link #CHANNEL_DEV}：同样配置但 {@code setMockEnable(true)}，仅用于区分「SDK 策略」与
 *       「我们发的内容」两个变量。开发对照通过**不代表**目标通过。</li>
 * </ul>
 *
 * <p>Key 来源：`local.properties` 的 {@code amapKey=…}，经 `buildConfigField` 注入。高德要求 Key 与
 * 包名 + 签名 SHA-1 绑定，所以探针包（{@code org.localroute.probe}）必须单独在高德控制台登记；
 * 没配 Key 时本类不启动，界面显示「未配置」，不会静默变成别的通道。
 */
public final class AmapReader {

    private static final String TAG = "RouteProbeAmap";

    /** 目标策略通道：与目标 App 的 `AMapLocationPurpose.Sport` + `setMockEnable(false)` 一致。 */
    public static final String CHANNEL_PRIMARY = "amap-sdk";
    /** 开发对照通道：其余一致，仅 `setMockEnable(true)`。 */
    public static final String CHANNEL_DEV = "amap-sdk-dev";

    /** 与目标 `LgBasicData.sportGpsInterval` 的构造默认值一致（1 秒）。 */
    private static final long INTERVAL_MS = 1000L;

    public interface Sink {
        void onAmapFrame(String channel, AMapLocation location);

        void onAmapNote(String note);
    }

    private final Sink sink;
    private final AMapLocationClient primaryClient;
    private final AMapLocationClient devClient;
    private final AMapLocationClientOption primaryOption;
    private final AMapLocationClientOption devOption;
    private final String optionSummary;
    private String primaryStatus = "未启动";
    private String devStatus = "未启动";

    private AmapReader(Sink sink, AMapLocationClient primaryClient, AMapLocationClient devClient,
                       AMapLocationClientOption primaryOption, AMapLocationClientOption devOption,
                       String optionSummary) {
        this.sink = sink;
        this.primaryClient = primaryClient;
        this.devClient = devClient;
        this.primaryOption = primaryOption;
        this.devOption = devOption;
        this.optionSummary = optionSummary;
    }

    public static boolean isConfigured() {
        String key = BuildConfig.AMAP_KEY;
        return key != null && !key.trim().isEmpty();
    }

    /** 未配置 Key 时的说明文案（界面直接显示，不吞掉原因）。 */
    public static String missingKeyHint() {
        return "amap-sdk：UNAVAILABLE（未配置高德 Key；需在高德控制台为 "
                + "org.localroute.probe + debug 签名登记 Key，再写进 local.properties 的 amapKey=…）";
    }

    public static AmapReader create(Context context, Sink sink) throws Exception {
        // 6.x 要求先声明隐私合规，否则 startLocation() 直接失败。
        AMapLocationClient.updatePrivacyShow(context, true, true);
        AMapLocationClient.updatePrivacyAgree(context, true);

        AMapLocationClientOption primaryOption = buildPrimaryOption();
        AMapLocationClientOption devOption = buildDevOption();

        AMapLocationClient primaryClient = new AMapLocationClient(context);
        primaryClient.setLocationOption(primaryOption);
        AMapLocationListener primaryListener = location -> {
            if (location == null) {
                sink.onAmapNote(CHANNEL_PRIMARY + "：回调为 null（SDK 内部丢弃或初始化未完成）");
                return;
            }
            sink.onAmapFrame(CHANNEL_PRIMARY, location);
        };
        primaryClient.setLocationListener(primaryListener);

        AMapLocationClient devClient = new AMapLocationClient(context);
        devClient.setLocationOption(devOption);
        devClient.setLocationListener(location -> {
            if (location == null) {
                sink.onAmapNote(CHANNEL_DEV + "：回调为 null");
                return;
            }
            sink.onAmapFrame(CHANNEL_DEV, location);
        });

        String summary = "高德定位 SDK " + safeVersion(primaryClient)
                + "\n主通道最终选项：" + describeOption(primaryOption)
                + "\n对照通道最终选项：" + describeOption(devOption)
                + "\n" + optionAssertions(primaryOption, devOption);
        return new AmapReader(sink, primaryClient, devClient, primaryOption, devOption, summary);
    }

    /**
     * 主通道：按目标的真实调用顺序装配（R32-02）。
     *
     * <p>`setLocationPurpose(Sport)` 会按场景预设**改写**一批选项（实测 6.4.5 会把 interval 改回 2000、
     * 把 mockEnable 改回 false），所以主通道就照目标的做法来：先设参数、再关 mockEnable、最后套场景；
     * 这样 getter 读出来的值就是目标 App 实际会拿到的值，而不是我们以为设进去的值。
     */
    private static AMapLocationClientOption buildPrimaryOption() {
        AMapLocationClientOption option = baseOption();
        option.setMockEnable(false);
        option.setLocationPurpose(AMapLocationClientOption.AMapLocationPurpose.Sport);
        return option;
    }

    /**
     * 对照通道：**先**套 Sport 场景预设，**最后**才打开 mockEnable。
     *
     * <p>顺序反了就等于没开（0.3.2 的 bug：dev 通道实测仍是 mockEnable=false，对照完全无效）。
     */
    private static AMapLocationClientOption buildDevOption() {
        AMapLocationClientOption option = baseOption();
        option.setLocationPurpose(AMapLocationClientOption.AMapLocationPurpose.Sport);
        option.setMockEnable(true);
        return option;
    }

    /** 两条通道共用的基础参数（与目标 `LzBgLocHelper.startLocation()` 一致）。 */
    private static AMapLocationClientOption baseOption() {
        AMapLocationClientOption option = new AMapLocationClientOption();
        option.setLocationMode(AMapLocationMode.Hight_Accuracy);
        option.setInterval(INTERVAL_MS);
        option.setHttpTimeOut(20_000L);
        option.setGpsFirst(true);
        option.setGpsFirstTimeout(1_000L);
        option.setNeedAddress(true);
        option.setOnceLocation(false);
        option.setSensorEnable(true);
        return option;
    }

    /**
     * 用 getter 自检两条通道的关键差异：主通道应 {@code mockEnable=false}，对照通道应 {@code true}。
     *
     * <p>不抛异常，只返回一句带 ✓/✗ 的结论，写进报告——对照无效比没有对照更危险。
     * 判定规则在 {@link AmapOptionCheck} 里，那份是不依赖 SDK 的纯逻辑，可以在 JVM 单测里跑。
     */
    public static String optionAssertions(AMapLocationClientOption primary,
                                          AMapLocationClientOption dev) {
        return AmapOptionCheck.describe(primary.isMockEnable(), dev.isMockEnable());
    }

    public void start() {
        try {
            primaryClient.startLocation();
            primaryStatus = "订阅中";
            sink.onAmapNote(CHANNEL_PRIMARY + "：已启动（Sport 场景、mockEnable=false，复现目标策略）");
        } catch (Throwable error) {
            primaryStatus = "启动失败（" + error + "）";
            sink.onAmapNote(CHANNEL_PRIMARY + "：" + primaryStatus);
            Log.w(TAG, "primary startLocation failed", error);
        }
        try {
            devClient.startLocation();
            devStatus = "订阅中";
            sink.onAmapNote(CHANNEL_DEV + "：已启动（Sport 场景、mockEnable=true，仅作对照）");
        } catch (Throwable error) {
            devStatus = "启动失败（" + error + "）";
            sink.onAmapNote(CHANNEL_DEV + "：" + devStatus);
            Log.w(TAG, "dev startLocation failed", error);
        }
        sink.onAmapNote(optionSummary);
    }

    public void stop() {
        try {
            primaryClient.stopLocation();
            primaryClient.onDestroy();
        } catch (Throwable ignored) {
            // 停止阶段不扩散异常。
        }
        try {
            devClient.stopLocation();
            devClient.onDestroy();
        } catch (Throwable ignored) {
            // 同上。
        }
        primaryStatus = "已停止";
        devStatus = "已停止";
    }

    public String primaryStatus() {
        return primaryStatus;
    }

    public String devStatus() {
        return devStatus;
    }

    public String optionSummary() {
        return optionSummary;
    }

    /** 单帧字段：errorCode/errorInfo 是本通道的核心证据。 */
    public static String describe(AMapLocation location) {
        StringBuilder text = new StringBuilder();
        text.append("err=").append(location.getErrorCode())
                .append(" info=").append(nullToDash(location.getErrorInfo()))
                .append(" type=").append(location.getLocationType())
                .append(" gpsAcc=").append(accuracyStatusText(location.getGpsAccuracyStatus()))
                .append(" acc=").append(location.hasAccuracy()
                        ? String.format(java.util.Locale.US, "%.1fm", location.getAccuracy()) : "无")
                .append(" spd=").append(location.hasSpeed()
                        ? String.format(java.util.Locale.US, "%.2fm/s", location.getSpeed()) : "无")
                .append(" 年龄=").append(ageText(location))
                .append(" provider=").append(nullToDash(location.getProvider()))
                .append(" 模拟=").append(isMock(location) ? "是" : "否")
                .append(" 坐标=").append(maskCoordinate(location));
        String detail = location.getLocationDetail();
        if (detail != null && !detail.isEmpty()) {
            text.append(" detail=").append(detail);
        }
        return text.toString();
    }

    private static String describeOption(AMapLocationClientOption option) {
        return "mode=" + option.getLocationMode()
                + " interval=" + option.getInterval() + "ms"
                + " gpsFirst=" + option.isGpsFirst()
                + " gpsFirstTimeout=" + option.getGpsFirstTimeout() + "ms"
                + " mockEnable=" + option.isMockEnable()
                + " sensorEnable=" + option.isSensorEnable()
                + " once=" + option.isOnceLocation()
                + " needAddress=" + option.isNeedAddress()
                + " purpose=" + purposeOf(option);
    }

    /** `getLocationPurpose()` 在不同 SDK 小版本上可见性不一致，用反射保证不因缺方法而崩。 */
    private static String purposeOf(AMapLocationClientOption option) {
        try {
            Object purpose = AMapLocationClientOption.class.getMethod("getLocationPurpose")
                    .invoke(option);
            return purpose == null ? "null" : purpose.toString();
        } catch (Throwable ignored) {
            return "未知（该 SDK 版本无 getLocationPurpose）";
        }
    }

    /** `getVersion()` 是实例方法（不是静态），所以要把 client 传进来；缺方法时给占位文案。 */
    private static String safeVersion(AMapLocationClient client) {
        try {
            String version = client.getVersion();
            return version == null || version.isEmpty() ? "版本未知" : version;
        } catch (Throwable ignored) {
            return "版本未知";
        }
    }

    private static String accuracyStatusText(int status) {
        switch (status) {
            case AMapLocation.GPS_ACCURACY_GOOD:
                return "GOOD";
            case AMapLocation.GPS_ACCURACY_BAD:
                return "BAD";
            default:
                return "UNKNOWN(" + status + ")";
        }
    }

    private static String ageText(AMapLocation location) {
        long nanos = location.getElapsedRealtimeNanos();
        if (nanos <= 0L) {
            return "未知";
        }
        long ageMs = (android.os.SystemClock.elapsedRealtimeNanos() - nanos) / 1_000_000L;
        return String.format(java.util.Locale.US, "%.1fs", ageMs / 1000d);
    }

    public static boolean isMockLocation(AMapLocation location) {
        return isMock(location);
    }

    private static boolean isMock(AMapLocation location) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            return location.isMock();
        }
        return location.isFromMockProvider();
    }

    /** 坐标脱敏：只保留 4 位小数（约 11 米），够定位、不落精确轨迹。 */
    private static String maskCoordinate(AMapLocation location) {
        return String.format(java.util.Locale.US, "%.4f,%.4f",
                location.getLatitude(), location.getLongitude());
    }

    private static String nullToDash(String value) {
        return value == null || value.isEmpty() ? "-" : value;
    }
}
