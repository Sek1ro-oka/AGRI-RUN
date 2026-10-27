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

    /** 本次运行实际使用的行程次数：开口单程固定 1 次，其余用用户设置的圈数/次数。 */
    public static int effectiveRepeatCount(CompiledRoute route, int requested) {
        return route.endBehavior().isSingleRun() ? 1 : requested;
    }

    /** 本次运行的目标总距离 = 单圈长度 × 圈数（开口单程就是全程）。 */
    public static double targetDistanceMeters(CompiledRoute route, RunConfig config) {
        return route.lapLengthMeters() * effectiveRepeatCount(route, config.loopCount());
    }

    /** 根据有效运行时间计算当前进度。 */
    public static Progress progressAt(CompiledRoute route, RunConfig config, long activeElapsedNanos) {
        double elapsedSeconds = Math.max(0L, activeElapsedNanos) / 1_000_000_000d;
        double distance = config.speedMetersPerSecond() * elapsedSeconds;
        return progressForDistance(route, effectiveRepeatCount(route, config.loopCount()), distance,
                config.speedMetersPerSecond());
    }

    /**
     * 按「已走距离」计算进度（运行中改速用）。
     *
     * <p>改速时累计距离必须连续：调用方用 {@code 改速前距离 + 新速度 × 改速后经过时间} 得到新的距离，
     * 再交给本方法，位置就不会因为速度变化而跳变（审查 R-009）。
     */
    public static Progress progressForDistance(CompiledRoute route, int loopCount, double distanceMeters,
                                               double speedMps) {
        loopCount = effectiveRepeatCount(route, loopCount);
        double lap = route.lapLengthMeters();
        double target = lap * loopCount;
        double distance = Math.max(0d, distanceMeters);

        if (distance >= target - 1e-6d) {
            // 到达终点：闭合/往返精确回到起点，开口单程停在最后一个节点；不多走。
            boolean single = route.endBehavior().isSingleRun();
            GeoPoint end = single ? route.vertex(route.vertexCount() - 1) : route.vertex(0);
            return new Progress(
                    end.latitude(),
                    end.longitude(),
                    route.bearingAt(lap - 1e-6d),
                    target,
                    loopCount,
                    loopCount,
                    lap,
                    speedMps,
                    true);
        }

        int completedLoops = (int) Math.floor(distance / lap + 1e-9d);
        if (completedLoops > loopCount) {
            completedLoops = loopCount;
        }
        GeoPoint point = route.pointAt(distance);
        double bearing = route.bearingAt(distance);
        return new Progress(
                point.latitude(),
                point.longitude(),
                bearing,
                distance,
                completedLoops,
                loopCount,
                lap,
                speedMps,
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
