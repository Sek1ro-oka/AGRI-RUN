package org.localroute.app.run;

import org.localroute.core.RunErrorCode;
import org.localroute.core.RunState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 一次运行对外暴露的完整状态快照（与任务书 RunSnapshot 字段一致）。
 *
 * <p>所有字段不可变；由 {@code SimulationService} 在每个调度周期生成并推送到
 * {@link SimulationStateRepo}，界面只读该快照。
 */
public final class RunSnapshot {

    private final String runId;
    private final RunState state;
    private final double latitude;
    private final double longitude;
    private final double speedMps;
    private final double bearingDegrees;
    private final double lapLengthMeters;
    private final double distanceMeters;
    private final int completedLoops;
    private final int targetLoops;
    private final long activeElapsedMs;
    private final RunErrorCode errorCode;
    private final String errorMessage;
    private final List<String> publishedProviders;
    private final long updatedAtMillis;

    private RunSnapshot(Builder builder) {
        this.runId = builder.runId;
        this.state = builder.state;
        this.latitude = builder.latitude;
        this.longitude = builder.longitude;
        this.speedMps = builder.speedMps;
        this.bearingDegrees = builder.bearingDegrees;
        this.lapLengthMeters = builder.lapLengthMeters;
        this.distanceMeters = builder.distanceMeters;
        this.completedLoops = builder.completedLoops;
        this.targetLoops = builder.targetLoops;
        this.activeElapsedMs = builder.activeElapsedMs;
        this.errorCode = builder.errorCode;
        this.errorMessage = builder.errorMessage;
        this.publishedProviders = Collections.unmodifiableList(new ArrayList<>(builder.publishedProviders));
        this.updatedAtMillis = builder.updatedAtMillis > 0 ? builder.updatedAtMillis : System.currentTimeMillis();
    }

    public static Builder builder(RunState state) {
        return new Builder(state);
    }

    /** 初始（尚未运行）快照。 */
    public static RunSnapshot idle() {
        return builder(RunState.IDLE).build();
    }

    public String runId() {
        return runId;
    }

    public RunState state() {
        return state;
    }

    public double latitude() {
        return latitude;
    }

    public double longitude() {
        return longitude;
    }

    public double speedMps() {
        return speedMps;
    }

    public double speedKmh() {
        return speedMps * 3.6d;
    }

    public double bearingDegrees() {
        return bearingDegrees;
    }

    public double lapLengthMeters() {
        return lapLengthMeters;
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

    public long activeElapsedMs() {
        return activeElapsedMs;
    }

    public RunErrorCode errorCode() {
        return errorCode;
    }

    public String errorMessage() {
        return errorMessage;
    }

    public List<String> publishedProviders() {
        return publishedProviders;
    }

    public long updatedAtMillis() {
        return updatedAtMillis;
    }

    public boolean isActive() {
        return state == RunState.STARTING || state == RunState.RUNNING || state == RunState.PAUSED;
    }

    public boolean hasPosition() {
        return state != RunState.IDLE && state != RunState.READY;
    }

    public String positionText() {
        if (!hasPosition() || Double.isNaN(latitude) || Double.isNaN(longitude)) {
            return "—";
        }
        return String.format(Locale.US, "%.6f, %.6f", latitude, longitude);
    }

    public String distanceText() {
        return formatMeters(distanceMeters);
    }

    public String lapLengthText() {
        return formatMeters(lapLengthMeters);
    }

    public String loopsText() {
        int target = targetLoops <= 0 ? 0 : targetLoops;
        return completedLoops + " / " + target;
    }

    public String elapsedText() {
        return org.localroute.core.ActiveElapsedClock.formatDuration(activeElapsedMs * 1_000_000L);
    }

    public String providersText() {
        return publishedProviders.isEmpty() ? "—" : String.join("、", publishedProviders);
    }

    /** 米 / 公里的人类可读格式。 */
    public static String formatMeters(double meters) {
        if (Double.isNaN(meters) || Double.isInfinite(meters)) {
            return "—";
        }
        if (meters >= 1000d) {
            return String.format(Locale.US, "%.2f km", meters / 1000d);
        }
        return String.format(Locale.US, "%.1f m", meters);
    }

    public static final class Builder {
        private final RunState state;
        private String runId = "";
        private double latitude = Double.NaN;
        private double longitude = Double.NaN;
        private double speedMps;
        private double bearingDegrees;
        private double lapLengthMeters;
        private double distanceMeters;
        private int completedLoops;
        private int targetLoops;
        private long activeElapsedMs;
        private RunErrorCode errorCode;
        private String errorMessage;
        private List<String> publishedProviders = new ArrayList<>();
        private long updatedAtMillis;

        private Builder(RunState state) {
            this.state = state;
        }

        public Builder runId(String value) {
            this.runId = value == null ? "" : value;
            return this;
        }

        public Builder position(double latitude, double longitude) {
            this.latitude = latitude;
            this.longitude = longitude;
            return this;
        }

        public Builder speedMps(double value) {
            this.speedMps = value;
            return this;
        }

        public Builder bearingDegrees(double value) {
            this.bearingDegrees = value;
            return this;
        }

        public Builder lapLengthMeters(double value) {
            this.lapLengthMeters = value;
            return this;
        }

        public Builder distanceMeters(double value) {
            this.distanceMeters = value;
            return this;
        }

        public Builder completedLoops(int value) {
            this.completedLoops = value;
            return this;
        }

        public Builder targetLoops(int value) {
            this.targetLoops = value;
            return this;
        }

        public Builder activeElapsedMs(long value) {
            this.activeElapsedMs = Math.max(0L, value);
            return this;
        }

        public Builder error(RunErrorCode code, String message) {
            this.errorCode = code;
            this.errorMessage = message;
            return this;
        }

        public Builder publishedProviders(List<String> providers) {
            this.publishedProviders = providers == null ? new ArrayList<>() : new ArrayList<>(providers);
            return this;
        }

        public Builder updatedAtMillis(long value) {
            this.updatedAtMillis = value;
            return this;
        }

        public RunSnapshot build() {
            return new RunSnapshot(this);
        }
    }
}
