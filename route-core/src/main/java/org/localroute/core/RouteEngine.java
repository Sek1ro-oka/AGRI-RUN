package org.localroute.core;

/**
 * 把“有效运行时间”换算成路线进度的纯函数。
 *
 * <p>进度只用距离表达：{@code distance = speedMps * activeElapsedSeconds}，与调度间隔、回调频率无关。
 * 因此 250 ms / 500 ms / 1000 ms 的调度在同一时刻得到同一位置（任务书 T10），暂停时间也不计入（T07）。
 */
public final class RouteEngine {

    private RouteEngine() {
    }

    /** 本次运行的目标总距离 = 单圈长度 × 圈数。 */
    public static double targetDistanceMeters(CompiledRoute route, RunConfig config) {
        return route.lapLengthMeters() * config.loopCount();
    }

    /** 根据有效运行时间计算当前进度。 */
    public static Progress progressAt(CompiledRoute route, RunConfig config, long activeElapsedNanos) {
        double lap = route.lapLengthMeters();
        double target = targetDistanceMeters(route, config);
        double elapsedSeconds = Math.max(0L, activeElapsedNanos) / 1_000_000_000d;
        double rawDistance = config.speedMetersPerSecond() * elapsedSeconds;

        if (rawDistance >= target - 1e-6d) {
            // 到达终点：位置精确回到起点，圈数等于目标圈数，不多走。
            return new Progress(
                    route.vertex(0).latitude(),
                    route.vertex(0).longitude(),
                    route.bearingAt(lap - 1e-6d),
                    target,
                    config.loopCount(),
                    config.loopCount(),
                    lap,
                    config.speedMetersPerSecond(),
                    true);
        }

        double distance = rawDistance;
        int completedLoops = (int) Math.floor(distance / lap + 1e-9d);
        if (completedLoops > config.loopCount()) {
            completedLoops = config.loopCount();
        }
        GeoPoint point = route.pointAt(distance);
        double bearing = route.bearingAt(distance);
        return new Progress(
                point.latitude(),
                point.longitude(),
                bearing,
                distance,
                completedLoops,
                config.loopCount(),
                lap,
                config.speedMetersPerSecond(),
                false);
    }

    /** 一次进度快照（不可变）。 */
    public static final class Progress {
        private final double latitude;
        private final double longitude;
        private final double bearingDegrees;
        private final double distanceMeters;
        private final int completedLoops;
        private final int targetLoops;
        private final double lapLengthMeters;
        private final double speedMps;
        private final boolean finished;

        Progress(double latitude, double longitude, double bearingDegrees, double distanceMeters,
                int completedLoops, int targetLoops, double lapLengthMeters, double speedMps, boolean finished) {
            this.latitude = latitude;
            this.longitude = longitude;
            this.bearingDegrees = bearingDegrees;
            this.distanceMeters = distanceMeters;
            this.completedLoops = completedLoops;
            this.targetLoops = targetLoops;
            this.lapLengthMeters = lapLengthMeters;
            this.speedMps = speedMps;
            this.finished = finished;
        }

        public double latitude() {
            return latitude;
        }

        public double longitude() {
            return longitude;
        }

        public double bearingDegrees() {
            return bearingDegrees;
        }

        public double distanceMeters() {
            return distanceMeters;
        }

        public int completedLoops() {
            return completedLoops;
        }

        public int targetLoops() {
            return targetLoops;
        }

        public double lapLengthMeters() {
            return lapLengthMeters;
        }

        public double speedMps() {
            return speedMps;
        }

        public boolean finished() {
            return finished;
        }

        public String positionText() {
            return String.format(java.util.Locale.US, "%.6f,%.6f", latitude, longitude);
        }
    }
}
