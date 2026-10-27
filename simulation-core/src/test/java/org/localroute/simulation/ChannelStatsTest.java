package org.localroute.simulation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ChannelStatsTest {

    private static final long MS = 1_000_000L;

    @Test
    public void emptyChannelReportsZeroFrames() {
        ChannelStats stats = new ChannelStats("gps");
        assertEquals(0, stats.totalFrames());
        assertTrue(stats.summary(), stats.summary().contains("0 个位置"));
    }

    @Test
    public void intervalsUseMedianP95AndMax() {
        ChannelStats stats = new ChannelStats("gps");
        long[] arrivals = {0, 500 * MS, 1000 * MS, 1500 * MS, 5000 * MS};
        for (long arrival : arrivals) {
            stats.addFrame(arrival, 39.9, 116.4, true, 2.5, true, arrival / MS);
        }
        assertEquals(4, stats.intervalsMillis().size());
        assertEquals(500L, stats.medianIntervalMillis());
        assertEquals(500L, stats.p95IntervalMillis());
        assertEquals(3500L, stats.maxIntervalMillis());
    }

    @Test
    public void travelledDistanceFollowsConsecutiveFrames() {
        ChannelStats stats = new ChannelStats("gps");
        // 纬度每帧 +0.0001 度，约 11.1 米。
        stats.addFrame(0, 39.9000, 116.4000, true, 2.5, true, 0);
        stats.addFrame(500 * MS, 39.9001, 116.4000, true, 2.5, true, 500);
        stats.addFrame(1000 * MS, 39.9002, 116.4000, true, 2.5, true, 1000);
        assertTrue(stats.summary(), stats.travelledMeters() > 21d && stats.travelledMeters() < 23d);
    }

    @Test
    public void speedToDisplacementRatioIsNearOneForConsistentRun() {
        ChannelStats stats = new ChannelStats("gps");
        // 速度 11.1 m/s、每 1000ms 一帧、每帧约 0.0001 度（约 11.1 米）。
        for (int i = 0; i < 4; i++) {
            stats.addFrame(i * 1000 * MS, 39.9000 + i * 0.0001, 116.4000, true, 11.1, true, i * 1000L);
        }
        double ratio = stats.speedToDisplacementRatio();
        assertTrue("ratio=" + ratio, ratio > 0.9 && ratio < 1.1);
    }

    @Test
    public void speedWithoutSamplesHasNoRatio() {
        ChannelStats stats = new ChannelStats("fused");
        stats.addFrame(0, 39.9, 116.4, false, 0, true, 0);
        assertEquals(-1d, stats.speedToDisplacementRatio(), 1e-9);
        assertTrue(stats.summary(), stats.summary().contains("位移速度比 无"));
    }

    @Test
    public void mockFramesAndTaskFailuresAreCounted() {
        ChannelStats stats = new ChannelStats("gms-flp");
        stats.taskStarted();
        stats.taskFinished(false);
        stats.addFrame(0, 39.9, 116.4, true, 0, false, 0);
        stats.addFrame(500 * MS, 39.9001, 116.4, true, 0, true, 500);
        assertEquals(1, stats.mockFrames());
        assertEquals(1, stats.failedTasks());
        assertEquals(0, stats.inFlight());
    }
}
