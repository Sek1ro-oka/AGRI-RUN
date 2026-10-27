package org.localroute.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link DistancePhase} 的单元测试：重点证明「改速不跳变」（审查 R-009）。
 */
public class DistancePhaseTest {

    private static final long SECOND = 1_000_000_000L;

    @Test
    public void distanceGrowsWithSpeed() {
        DistancePhase phase = new DistancePhase(36d, 0L); // 36 km/h = 10 m/s
        assertEquals(0d, phase.distanceAt(0L), 1e-9);
        assertEquals(10d, phase.distanceAt(SECOND), 1e-6);
        assertEquals(50d, phase.distanceAt(5 * SECOND), 1e-6);
    }

    @Test
    public void changeSpeedKeepsDistanceContinuous() {
        DistancePhase phase = new DistancePhase(36d, 0L);
        double before = phase.distanceAt(4 * SECOND); // 40 m
        double travelled = phase.changeSpeed(18d, 4 * SECOND); // 18 km/h = 5 m/s
        assertEquals(before, travelled, 1e-6);
        // 改速瞬间距离不变，再走 2 秒才增加 10 米。
        assertEquals(40d, phase.distanceAt(4 * SECOND), 1e-6);
        assertEquals(50d, phase.distanceAt(6 * SECOND), 1e-6);
    }

    @Test
    public void changeSpeedDoesNotRescaleHistory() {
        DistancePhase phase = new DistancePhase(3.6d, 0L); // 1 m/s
        phase.distanceAt(10 * SECOND);
        phase.changeSpeed(36d, 10 * SECOND); // 10 m/s
        // 若按新速度重算历史会得到 100 m，这里必须是 10 + 10×1 = 20 m。
        assertEquals(20d, phase.distanceAt(11 * SECOND), 1e-6);
    }

    @Test
    public void timeGoingBackwardsDoesNotReduceDistance() {
        DistancePhase phase = new DistancePhase(36d, 5 * SECOND);
        assertEquals(0d, phase.distanceAt(0L), 1e-9);
        assertEquals(10d, phase.distanceAt(6 * SECOND), 1e-6);
    }

    @Test
    public void restartAtResetsOffset() {
        DistancePhase phase = new DistancePhase(36d, 0L);
        phase.distanceAt(2 * SECOND);
        phase.restartAt(7 * SECOND, 3.6d);
        assertEquals(0d, phase.offsetMeters(), 1e-9);
        assertEquals(0d, phase.distanceAt(7 * SECOND), 1e-9);
        assertEquals(1d, phase.distanceAt(8 * SECOND), 1e-6);
        assertEquals(3.6d, phase.speedKmh(), 1e-9);
    }

    @Test
    public void negativeSpeedRejected() {
        assertThrows(IllegalArgumentException.class, () -> new DistancePhase(-1d));
        assertThrows(IllegalArgumentException.class, () -> new DistancePhase(Double.NaN));
    }

    @Test
    public void speedMetersPerSecondUsesKmhConversion() {
        DistancePhase phase = new DistancePhase(9d, 0L);
        assertEquals(2.5d, phase.speedMetersPerSecond(), 1e-9);
    }

    @Test
    public void runEngineUsesDistanceSoLapsStayConsistentAcrossSpeedChange() throws Exception {
        // 一条 100 米的往返路线（单程 50 米）。
        CompiledRoute route = RouteCompiler.compile(java.util.Arrays.asList(
                new GeoPoint(0d, 0d),
                new GeoPoint(0d, 50d / 111_194.9d)), true);
        int loops = 3;
        DistancePhase phase = new DistancePhase(36d, 0L); // 10 m/s
        double atTwoSeconds = phase.distanceAt(2 * SECOND);
        RouteEngine.Progress first = RouteEngine.progressForDistance(
                route, loops, atTwoSeconds, phase.speedMetersPerSecond());
        assertTrue(first.distanceMeters() > 19.9 && first.distanceMeters() < 20.1);

        double travelled = phase.changeSpeed(7.2d, 2 * SECOND); // 2 m/s
        RouteEngine.Progress afterChange = RouteEngine.progressForDistance(
                route, loops, phase.distanceAt(2 * SECOND), phase.speedMetersPerSecond());
        assertEquals(first.distanceMeters(), afterChange.distanceMeters(), 1e-6);
        assertEquals(travelled, afterChange.distanceMeters(), 1e-6);
        assertEquals(0, afterChange.completedLoops());

        RouteEngine.Progress later = RouteEngine.progressForDistance(
                route, loops, phase.distanceAt(12 * SECOND), phase.speedMetersPerSecond());
        assertEquals(20d + 20d, later.distanceMeters(), 1e-6);
        assertEquals(0, later.completedLoops()); // 40 m 还不到一圈（100 m）
    }
}
