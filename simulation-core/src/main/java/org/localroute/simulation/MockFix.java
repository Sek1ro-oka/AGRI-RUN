package org.localroute.simulation;

/**
 * 一个可提交给系统测试 provider 的完整位置修正（platform-neutral）。
 *
 * <p>每次提交都会构造“完整”位置：时间、开机后纳秒时间、精度、速度、方位都显式给出，
 * 避免某些系统版本把缺失字段当成 0 而判为无效定位（任务书第 8 步）。
 */
public final class MockFix {

    private final double latitude;
    private final double longitude;
    private final double altitudeMeters;
    private final double accuracyMeters;
    private final double speedMps;
    private final double bearingDegrees;
    private final long timeMillis;
    private final long elapsedRealtimeNanos;

    public MockFix(double latitude, double longitude, double altitudeMeters, double accuracyMeters,
            double speedMps, double bearingDegrees, long timeMillis, long elapsedRealtimeNanos) {
        this.latitude = latitude;
        this.longitude = longitude;
        this.altitudeMeters = altitudeMeters;
        this.accuracyMeters = accuracyMeters;
        this.speedMps = speedMps;
        this.bearingDegrees = bearingDegrees;
        this.timeMillis = timeMillis;
        this.elapsedRealtimeNanos = elapsedRealtimeNanos;
    }

    public double latitude() {
        return latitude;
    }

    public double longitude() {
        return longitude;
    }

    public double altitudeMeters() {
        return altitudeMeters;
    }

    public double accuracyMeters() {
        return accuracyMeters;
    }

    public double speedMps() {
        return speedMps;
    }

    public double bearingDegrees() {
        return bearingDegrees;
    }

    public long timeMillis() {
        return timeMillis;
    }

    public long elapsedRealtimeNanos() {
        return elapsedRealtimeNanos;
    }

    @Override
    public String toString() {
        return String.format(java.util.Locale.US,
                "MockFix{%.6f,%.6f alt=%.1f acc=%.1f v=%.2fm/s bearing=%.1f t=%d ert=%d}",
                latitude, longitude, altitudeMeters, accuracyMeters, speedMps, bearingDegrees,
                timeMillis, elapsedRealtimeNanos);
    }
}
