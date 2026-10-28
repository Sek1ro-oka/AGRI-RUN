package org.localroute.simulation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** {@link PublishCadence}：实际发送间隔的统计口径。 */
public final class PublishCadenceTest {

    private static final long MS = 1_000_000L;

    @Test
    public void firstTickOnlySetsBaseline() {
        PublishCadence cadence = new PublishCadence();
        cadence.onTick(5_000L * MS);
        assertFalse(cadence.hasSamples());
        assertEquals(0, cadence.sampleCount());
    }

    @Test
    public void medianGapIsMiddleSample() {
        PublishCadence cadence = new PublishCadence();
        cadence.onTick(0L);
        cadence.onTick(1_000L * MS);
        cadence.onTick(3_000L * MS);
        cadence.onTick(6_000L * MS);
        assertEquals(3, cadence.sampleCount());
        // 间隔依次是 1000 / 2000 / 3000 ms，排序后中位是 2000。
        assertEquals(2_000d, cadence.medianGapMillis(), 1e-9);
        assertEquals(3_000d, cadence.maxGapMillis(), 1e-9);
    }

    @Test
    public void deviationSignFollowsSlowerScheduling() {
        PublishCadence cadence = new PublishCadence();
        cadence.onTick(0L);
        cadence.onTick(600L * MS);
        cadence.onTick(1_200L * MS);
        // 标称 500 ms、实际 600 ms → 慢了 20%。
        assertEquals(0.2d, cadence.medianDeviation(500d), 1e-9);
    }

    @Test
    public void summaryMentionsNominalAndActual() {
        PublishCadence cadence = new PublishCadence();
        cadence.onTick(0L);
        cadence.onTick(500L * MS);
        String summary = cadence.summary(500d);
        assertTrue(summary.contains("标称 500 ms"));
        assertTrue(summary.contains("中位 500 ms"));
        assertTrue(summary.contains("样本 1 帧"));
    }

    @Test
    public void emptyCadenceSaysNoSamples() {
        PublishCadence cadence = new PublishCadence();
        assertTrue(cadence.summary(500d).contains("样本不足"));
        assertTrue(Double.isNaN(cadence.medianGapMillis()));
    }

    @Test
    public void resetDropsSamplesAndBaseline() {
        PublishCadence cadence = new PublishCadence(8);
        cadence.onTick(0L);
        cadence.onTick(1_000L * MS);
        cadence.reset();
        assertFalse(cadence.hasSamples());
        // 重置后第一帧仍然只当基准，不会拿旧时间戳算出一个巨大的间隔。
        cadence.onTick(50_000L * MS);
        assertFalse(cadence.hasSamples());
        cadence.onTick(50_500L * MS);
        assertEquals(500d, cadence.medianGapMillis(), 1e-9);
    }
}
