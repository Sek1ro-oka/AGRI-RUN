package org.localroute.simulation;

import java.util.Locale;

/**
 * 一帧模拟位置要带的“精度画像”：水平精度、垂直精度、速度精度、航向精度、卫星数与载噪比。
 *
 * <p>为什么需要这一层：不同目标 App 读的字段不一样。只填 lat/lon/acc/speed/bearing 时，
 * 有的 App 会因为 {@code hasSpeedAccuracy()} 为假、{@code extras} 里没有 {@code satellites}，
 * 或者“每帧位移小于声明精度”而把整段轨迹判成定位抖动 —— 地图会动，但里程不累计。
 *
 * <p>这些数值是按速度推算的<b>模拟参数</b>，不是传感器实测值；它们的作用是让输出字段完整、
 * 可比对，不构成“填了这些字段目标 App 就一定会累计里程”的保证。
 */
public final class FixQualityProfile {

    /** 移动中的水平精度（米）。 */
    public static final double ACCURACY_MOVING_METERS = 3d;
    /** 静止（单点发送、暂停）的水平精度（米）。 */
    public static final double ACCURACY_STATIC_METERS = 5d;
    /** 固定海拔（米）。 */
    public static final double ALTITUDE_METERS = 10d;
    /** 速度低于这个值就按“静止”处理（米/秒）。 */
    public static final double MOVING_SPEED_THRESHOLD_MPS = 0.05d;

    private static final int SATELLITES_MOVING = 12;
    private static final int SATELLITES_STATIC = 9;
    private static final double MEAN_CN0_MOVING = 32.5d;
    private static final double MEAN_CN0_STATIC = 24.0d;
    private static final double MAX_CN0_MOVING = 41.0d;
    private static final double MAX_CN0_STATIC = 33.0d;
    /** 垂直精度通常是水平精度的 1.5~2 倍。 */
    private static final double VERTICAL_FACTOR = 2d;
    /** 速度精度下限（米/秒）：再准的消费级 GPS 也给不出更小值。 */
    private static final double SPEED_ACCURACY_FLOOR_MPS = 0.15d;
    /** 速度精度 = 速度的 5%，体现“越快越难测准”。 */
    private static final double SPEED_ACCURACY_RATIO = 0.05d;
    /** 静止时的速度精度（米/秒）。 */
    private static final double SPEED_ACCURACY_STATIC_MPS = 0.3d;

    private final boolean moving;
    private final double accuracyMeters;
    private final double verticalAccuracyMeters;
    private final double speedAccuracyMps;
    private final double bearingAccuracyDegrees;
    private final int satellites;
    private final double meanCn0DbHz;
    private final double maxCn0DbHz;

    private FixQualityProfile(boolean moving, double accuracyMeters, double verticalAccuracyMeters,
            double speedAccuracyMps, double bearingAccuracyDegrees, int satellites,
            double meanCn0DbHz, double maxCn0DbHz) {
        this.moving = moving;
        this.accuracyMeters = accuracyMeters;
        this.verticalAccuracyMeters = verticalAccuracyMeters;
        this.speedAccuracyMps = speedAccuracyMps;
        this.bearingAccuracyDegrees = bearingAccuracyDegrees;
        this.satellites = satellites;
        this.meanCn0DbHz = meanCn0DbHz;
        this.maxCn0DbHz = maxCn0DbHz;
    }

    /** 按当前速度推算一帧该带的精度画像。 */
    public static FixQualityProfile forSpeed(double speedMps) {
        boolean moving = speedMps > MOVING_SPEED_THRESHOLD_MPS;
        double accuracy = moving ? ACCURACY_MOVING_METERS : ACCURACY_STATIC_METERS;
        double speedAccuracy = moving
                ? Math.max(SPEED_ACCURACY_FLOOR_MPS, speedMps * SPEED_ACCURACY_RATIO)
                : SPEED_ACCURACY_STATIC_MPS;
        return new FixQualityProfile(moving, accuracy, accuracy * VERTICAL_FACTOR, speedAccuracy,
                moving ? 5d : 45d,
                moving ? SATELLITES_MOVING : SATELLITES_STATIC,
                moving ? MEAN_CN0_MOVING : MEAN_CN0_STATIC,
                moving ? MAX_CN0_MOVING : MAX_CN0_STATIC);
    }

    public boolean moving() {
        return moving;
    }

    public double accuracyMeters() {
        return accuracyMeters;
    }

    public double verticalAccuracyMeters() {
        return verticalAccuracyMeters;
    }

    public double speedAccuracyMps() {
        return speedAccuracyMps;
    }

    public double bearingAccuracyDegrees() {
        return bearingAccuracyDegrees;
    }

    public int satellites() {
        return satellites;
    }

    public double meanCn0DbHz() {
        return meanCn0DbHz;
    }

    public double maxCn0DbHz() {
        return maxCn0DbHz;
    }

    /** 诊断页一行：说明这一帧实际声明了哪些字段。 */
    public String summary() {
        return String.format(Locale.US,
                "水平精度 %.1f 米，垂直精度 %.1f 米，速度精度 %.2f 米/秒，航向精度 %.0f°，"
                        + "卫星 %d 颗，平均载噪比 %.1f dB-Hz，最高 %.1f dB-Hz",
                accuracyMeters, verticalAccuracyMeters, speedAccuracyMps, bearingAccuracyDegrees,
                satellites, meanCn0DbHz, maxCn0DbHz);
    }

    @Override
    public String toString() {
        return "FixQualityProfile{" + (moving ? "moving" : "static") + " " + summary() + "}";
    }
}
