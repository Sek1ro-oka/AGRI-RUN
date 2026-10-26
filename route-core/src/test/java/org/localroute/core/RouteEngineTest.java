package org.localroute.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

/** 按距离推进的进度计算、暂停不计时、调度间隔无关性（任务书 T04/T05/T07/T10/T11/T12）。 */
public class RouteEngineTest {

    private static final double NANOS_PER_SECOND = 1_000_000_000d;

    private static CompiledRoute squareRoute() throws InvalidRouteException {
        return RouteCompiler.compile(Arrays.asList(
                new GeoPoint(0.0000d, 0.0000d),
                new GeoPoint(0.0000d, 0.0010d),
                new GeoPoint(0.0010d, 0.0010d),
                new GeoPoint(0.0010d, 0.0000d)));
    }

    private static long nanosForSeconds(double seconds) {
        return Math.round(seconds * NANOS_PER_SECOND);
    }

    @Test
    public void singleLoop_finishesExactlyAtLapEnd() throws Exception {
        CompiledRoute route = squareRoute();
        RunConfig config = new RunConfig(9d, 1);
        double lap = route.lapLengthMeters();
        long finishNanos = nanosForSeconds(lap / config.speedMetersPerSecond());

        RouteEngine.Progress almost = RouteEngine.progressAt(route, config,
                finishNanos - nanosForSeconds(1.0d));
        assertFalse("距离终点 1 秒时不应完成", almost.finished());
        assertEquals(0, almost.completedLoops());
        assertTrue(almost.distanceMeters() < lap);

        RouteEngine.Progress done = RouteEngine.progressAt(route, config, finishNanos);
        assertTrue("跑满一圈应完成", done.finished());
        assertEquals(1, done.completedLoops());
        assertEquals(1, done.targetLoops());
        assertEquals(lap, done.distanceMeters(), 1e-6d);
        assertEquals("完成时位置必须精确回到起点", route.vertex(0).latitude(), done.latitude(), 1e-12d);
        assertEquals(route.vertex(0).longitude(), done.longitude(), 1e-12d);
    }

    @Test
    public void fiveLoops_completeOnlyAfterFifthLap_andNeverOverRun() throws Exception {
        CompiledRoute route = squareRoute();
        RunConfig config = new RunConfig(9d, 5);
        double lap = route.lapLengthMeters();
        double speed = config.speedMetersPerSecond();

        // 第 4 圈跑满后再多 1 秒：仍是 4 圈，且未完成。
        RouteEngine.Progress fourLaps = RouteEngine.progressAt(route, config,
                nanosForSeconds(4d * lap / speed + 1.0d));
        assertEquals(4, fourLaps.completedLoops());
        assertFalse(fourLaps.finished());

        RouteEngine.Progress fourAndHalf = RouteEngine.progressAt(route, config,
                nanosForSeconds(4.5d * lap / speed));
        assertEquals("未跑满第 5 圈不应多算", 4, fourAndHalf.completedLoops());
        assertFalse(fourAndHalf.finished());

        RouteEngine.Progress fiveLaps = RouteEngine.progressAt(route, config,
                nanosForSeconds(5d * lap / speed));
        assertTrue(fiveLaps.finished());
        assertEquals(5, fiveLaps.completedLoops());
        assertEquals(5d * lap, fiveLaps.distanceMeters(), 1e-6d);

        // 再多等很久也不会超过目标距离（不多走、不重启）。
        RouteEngine.Progress longAfter = RouteEngine.progressAt(route, config,
                nanosForSeconds(60d * lap / speed));
        assertEquals(5d * lap, longAfter.distanceMeters(), 1e-6d);
        assertEquals(5, longAfter.completedLoops());
        assertEquals(route.vertex(0).latitude(), longAfter.latitude(), 1e-12d);
    }

    @Test
    public void loopCounting_isFloorOfDistanceOverLap() throws Exception {
        CompiledRoute route = squareRoute();
        RunConfig config = new RunConfig(20d, 10);
        double lap = route.lapLengthMeters();
        double speed = config.speedMetersPerSecond();

        for (double distanceRatio = 0.1d; distanceRatio < 6d; distanceRatio += 0.37d) {
            RouteEngine.Progress progress = RouteEngine.progressAt(route, config,
                    nanosForSeconds(distanceRatio * lap / speed));
            int expected = (int) Math.floor(distanceRatio + 1e-9d);
            assertEquals("比例 " + distanceRatio, expected, progress.completedLoops());
        }
    }

    @Test
    public void pausedTime_isNotCounted() throws Exception {
        CompiledRoute route = squareRoute();
        RunConfig config = new RunConfig(9d, 5);
        ActiveElapsedClock clock = new ActiveElapsedClock();

        clock.start(0L);
        long afterFirstLeg = clock.elapsedNanos(nanosForSeconds(10d));
        assertEquals(nanosForSeconds(10d), afterFirstLeg);

        clock.pause(nanosForSeconds(10d));
        // 暂停 30 秒：这 30 秒不应计入有效运行时间。
        assertEquals(nanosForSeconds(10d), clock.elapsedNanos(nanosForSeconds(40d)));

        clock.start(nanosForSeconds(40d));
        long total = clock.elapsedNanos(nanosForSeconds(50d));
        assertEquals(nanosForSeconds(20d), total);

        RouteEngine.Progress progress = RouteEngine.progressAt(route, config, total);
        assertEquals(config.speedMetersPerSecond() * 20d, progress.distanceMeters(), 1e-6d);

        clock.pause(nanosForSeconds(50d));
        clock.pause(nanosForSeconds(60d));
        assertEquals("重复 pause 不应重复累计", nanosForSeconds(20d), clock.elapsedNanos(nanosForSeconds(60d)));
    }

    @Test
    public void progress_isIndependentOfTickInterval() throws Exception {
        CompiledRoute route = squareRoute();
        double speedKmh = 9d;
        double lap = route.lapLengthMeters();
        double targetSeconds = 3.3d * lap / new RunConfig(speedKmh, 5).speedMetersPerSecond();

        RouteEngine.Progress reference = null;
        for (int tickMs : new int[] {250, 500, 1000}) {
            RunConfig config = new RunConfig(speedKmh, 5, tickMs);
            // 模拟“每个 tick 计算一次”，最后一次采样正好落在 targetSeconds。
            RouteEngine.Progress last = null;
            long tickNanos = tickMs * 1_000_000L;
            long total = nanosForSeconds(targetSeconds);
            // 先按真实 tick 推进，最后一步强制对齐到目标时刻。
            long elapsed = 0L;
            while (elapsed + tickNanos < total) {
                last = RouteEngine.progressAt(route, config, elapsed);
                elapsed += tickNanos;
            }
            last = RouteEngine.progressAt(route, config, total);
            if (reference == null) {
                reference = last;
            } else {
                assertEquals("tick=" + tickMs + " 的位置应与参考一致",
                        reference.latitude(), last.latitude(), 1e-12d);
                assertEquals(reference.longitude(), last.longitude(), 1e-12d);
                assertEquals(reference.distanceMeters(), last.distanceMeters(), 1e-9d);
                assertEquals(reference.completedLoops(), last.completedLoops());
            }
            assertTrue(last != null);
        }
    }

    @Test
    public void lateTickAfterGap_keepsDistanceContinuous() throws Exception {
        CompiledRoute route = squareRoute();
        RunConfig config = new RunConfig(9d, 100);
        double speed = config.speedMetersPerSecond();

        RouteEngine.Progress before = RouteEngine.progressAt(route, config, nanosForSeconds(10d));
        // 调度被卡住 6 秒后恢复：位置应按真实有效时长继续，而不是回退或跳圈。
        RouteEngine.Progress after = RouteEngine.progressAt(route, config, nanosForSeconds(16d));
        assertEquals(before.distanceMeters() + speed * 6d, after.distanceMeters(), 1e-6d);
        assertTrue(after.distanceMeters() > before.distanceMeters());
    }

    @Test
    public void antimeridianRoute_interpolatesAlongShortPath() throws Exception {
        CompiledRoute route = RouteCompiler.compile(Arrays.asList(
                new GeoPoint(0d, 179.99d),
                new GeoPoint(0d, -179.99d)));
        double lap = route.lapLengthMeters();
        // 每边约 2226 米。
        assertEquals(4_452d, lap, 40d);

        RouteEngine.Progress quarter = RouteEngine.progressAt(route, new RunConfig(9d, 1),
                nanosForSeconds(lap * 0.25d / 2.5d));
        assertTrue("跨 ±180° 的插值应停在日期变更线附近，实际 lon=" + quarter.longitude(),
                Math.abs(quarter.longitude()) > 179.9d);
        assertEquals(0d, quarter.latitude(), 1e-6d);
    }

    @Test
    public void zeroElapsed_reportsStartPoint() throws Exception {
        CompiledRoute route = squareRoute();
        RouteEngine.Progress progress = RouteEngine.progressAt(route, new RunConfig(9d, 5), 0L);
        assertEquals(route.vertex(0).latitude(), progress.latitude(), 1e-12d);
        assertEquals(route.vertex(0).longitude(), progress.longitude(), 1e-12d);
        assertEquals(0d, progress.distanceMeters(), 1e-12d);
        assertEquals(0, progress.completedLoops());
        assertFalse(progress.finished());
    }

    @Test
    public void negativeElapsed_isClampedToZero() throws Exception {
        CompiledRoute route = squareRoute();
        RouteEngine.Progress progress = RouteEngine.progressAt(route, new RunConfig(9d, 1), -5_000_000_000L);
        assertEquals(0d, progress.distanceMeters(), 1e-12d);
    }

    @Test
    public void runConfig_validatesBounds() {
        assertThrows(IllegalArgumentException.class, () -> new RunConfig(0.4d, 5));
        assertThrows(IllegalArgumentException.class, () -> new RunConfig(61d, 5));
        assertThrows(IllegalArgumentException.class, () -> new RunConfig(9d, 0));
        assertThrows(IllegalArgumentException.class, () -> new RunConfig(9d, 1001));
        assertThrows(IllegalArgumentException.class, () -> new RunConfig(9d, 5, 50));
        assertThrows(IllegalArgumentException.class, () -> new RunConfig(Double.NaN, 5));
        assertEquals(2.5d, new RunConfig(9d, 5).speedMetersPerSecond(), 1e-12d);
        assertEquals(5, RunConfig.defaults().loopCount());
        assertEquals(9d, RunConfig.defaults().speedKmh(), 1e-12d);
        assertEquals(500, RunConfig.defaults().tickIntervalMs());
    }

    @Test
    public void formatDuration_handlesMinutesAndHours() {
        assertEquals("00:00", ActiveElapsedClock.formatDuration(0L));
        assertEquals("01:05", ActiveElapsedClock.formatDuration(65_000_000_000L));
        assertEquals("01:00:00", ActiveElapsedClock.formatDuration(3_600_000_000_000L));
        assertEquals("02:03:04", ActiveElapsedClock.formatDuration(7_384_000_000_000L));
    }
}
