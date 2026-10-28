package org.localroute.simulation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FixQualityProfileTest {

    @Test
    public void staticProfileUsesCoarserAccuracyThanMoving() {
        FixQualityProfile still = FixQualityProfile.forSpeed(0d);
        FixQualityProfile moving = FixQualityProfile.forSpeed(4.17d);

        assertFalse(still.moving());
        assertTrue(moving.moving());
        assertEquals(FixQualityProfile.ACCURACY_STATIC_METERS, still.accuracyMeters(), 1e-9);
        assertEquals(FixQualityProfile.ACCURACY_MOVING_METERS, moving.accuracyMeters(), 1e-9);
        assertTrue("垂直精度应比水平精度差",
                moving.verticalAccuracyMeters() > moving.accuracyMeters());
    }

    @Test
    public void speedAccuracyNeverFallsBelowFloorAndGrowsWithSpeed() {
        FixQualityProfile slow = FixQualityProfile.forSpeed(1d);
        FixQualityProfile fast = FixQualityProfile.forSpeed(10d);

        assertTrue(slow.speedAccuracyMps() >= 0.15d - 1e-9);
        assertTrue("速度越快，速度精度应越差", fast.speedAccuracyMps() > slow.speedAccuracyMps());
    }

    @Test
    public void thresholdBelowIsStaticAboveIsMoving() {
        assertFalse(FixQualityProfile.forSpeed(FixQualityProfile.MOVING_SPEED_THRESHOLD_MPS)
                .moving());
        assertTrue(FixQualityProfile.forSpeed(FixQualityProfile.MOVING_SPEED_THRESHOLD_MPS + 0.01d)
                .moving());
    }

    @Test
    public void satelliteFieldsArePlausibleNumbers() {
        FixQualityProfile moving = FixQualityProfile.forSpeed(2.5d);
        assertTrue(moving.satellites() > 0);
        assertTrue(moving.maxCn0DbHz() > moving.meanCn0DbHz());
        assertTrue(moving.summary(), moving.summary().contains("卫星"));
    }

    @Test
    public void mockFixWithoutProfileDerivesOneFromSpeed() {
        MockFix fix = new MockFix(39.9d, 116.4d, 10d, 3d, 2.5d, 90d, 1_700_000_000_000L,
                12_345_000_000L);

        assertTrue(fix.quality().moving());
        assertEquals(3d, fix.quality().accuracyMeters(), 1e-9);
        assertTrue(fix.quality().satellites() > 0);
    }

    @Test
    public void explicitProfileWinsOverRawAccuracyArgument() {
        FixQualityProfile still = FixQualityProfile.forSpeed(0d);
        MockFix fix = new MockFix(39.9d, 116.4d, 10d, 99d, 0d, 0d, 1_700_000_000_000L,
                12_345_000_000L, still);

        assertEquals(FixQualityProfile.ACCURACY_STATIC_METERS, fix.accuracyMeters(), 1e-9);
        assertNotEquals(99d, fix.accuracyMeters(), 1e-9);
    }
}
