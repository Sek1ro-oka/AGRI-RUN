package org.localroute.simulation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.localroute.core.GeoMath;
import org.localroute.core.GeoPoint;

/** {@link LocationVerifier} 的判定规则测试（不需要真机）。 */
public class LocationVerifierTest {

    private static final GeoPoint HERE = new GeoPoint(39.9087, 116.3975);

    private static GeoPoint offsetMeters(double meters) {
        // 纬度 1 度 ≈ 111194.9 米，向东偏移更直观，这里用纬度近似足够精确。
        return new GeoPoint(HERE.latitude() + meters / 111194.9, HERE.longitude());
    }

    @Test
    public void evaluate_acceptsMatchingMockLocation() {
        LocationVerifier.Verdict verdict = LocationVerifier.evaluate(
                HERE, offsetMeters(3), true, LocationVerifier.DEFAULT_TOLERANCE_METERS);
        assertEquals(LocationVerifier.Verdict.ACCEPTED, verdict);
    }

    @Test
    public void evaluate_reportsMismatchWhenFarAway() {
        LocationVerifier.Verdict verdict = LocationVerifier.evaluate(
                HERE, offsetMeters(500), true, LocationVerifier.DEFAULT_TOLERANCE_METERS);
        assertEquals(LocationVerifier.Verdict.MISMATCH, verdict);
    }

    @Test
    public void evaluate_reportsNotMarkedMock() {
        LocationVerifier.Verdict verdict = LocationVerifier.evaluate(HERE, HERE, false, 20d);
        assertEquals(LocationVerifier.Verdict.NOT_MARKED_MOCK, verdict);
    }

    @Test
    public void evaluate_reportsNotReceivedWhenSystemHasNoFix() {
        LocationVerifier.Verdict verdict = LocationVerifier.evaluate(HERE, null, false, 20d);
        assertEquals(LocationVerifier.Verdict.NOT_RECEIVED, verdict);
    }

    @Test
    public void evaluate_reportsNoExpectedWhenNothingPublished() {
        LocationVerifier.Verdict verdict = LocationVerifier.evaluate(null, HERE, true, 20d);
        assertEquals(LocationVerifier.Verdict.NO_EXPECTED, verdict);
    }

    @Test
    public void distanceMeters_isMinusOneWithoutBothSides() {
        assertEquals(-1d, LocationVerifier.distanceMeters(null, HERE), 1e-9);
        assertEquals(-1d, LocationVerifier.distanceMeters(HERE, null), 1e-9);
        assertTrue(LocationVerifier.distanceMeters(HERE, offsetMeters(10)) > 9.0);
    }

    @Test
    public void explain_acceptedBlamesTargetApp() {
        String text = LocationVerifier.explain(
                LocationVerifier.Verdict.ACCEPTED, 1.5, "gps");
        assertTrue(text, text.contains("gps"));
        assertTrue(text, text.contains("已收下"));
        assertTrue(text, text.contains("目标 App"));
    }

    @Test
    public void explain_noExpectedTellsUserToStartFirst() {
        String text = LocationVerifier.explain(LocationVerifier.Verdict.NO_EXPECTED, -1, "gps");
        assertTrue(text, text.contains("还没有提交过位置"));
    }
}
