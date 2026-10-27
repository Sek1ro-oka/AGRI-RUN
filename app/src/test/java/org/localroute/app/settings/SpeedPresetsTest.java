package org.localroute.app.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

public class SpeedPresetsTest {

    private static final class MapStore implements SpeedPresets.Store {
        final Map<String, String> values = new HashMap<>();

        @Override
        public String get(String key, String fallback) {
            return values.containsKey(key) ? values.get(key) : fallback;
        }

        @Override
        public void put(String key, String value) {
            values.put(key, value);
        }
    }

    private final MapStore store = new MapStore();
    private final SpeedPresets presets = new SpeedPresets(store);

    @Test
    public void defaultsAreWalkFiveRunNineRideFifteen() {
        assertEquals(5.0, presets.walk(), 1e-9);
        assertEquals(9.0, presets.run(), 1e-9);
        assertEquals(15.0, presets.ride(), 1e-9);
    }

    @Test
    public void savedValuesSurviveNewInstance() {
        presets.setWalk(4.5);
        presets.setRun(10.5);
        presets.setRide(18.0);
        SpeedPresets reloaded = new SpeedPresets(store);
        assertEquals(4.5, reloaded.walk(), 1e-9);
        assertEquals(10.5, reloaded.run(), 1e-9);
        assertEquals(18.0, reloaded.ride(), 1e-9);
    }

    @Test
    public void indexAccessMatchesNamedAccessors() {
        presets.set(2, 20.0);
        assertEquals(20.0, presets.ride(), 1e-9);
        assertEquals(5.0, presets.get(0), 1e-9);
    }

    @Test
    public void unknownIndexIsRejected() {
        try {
            presets.set(3, 9.0);
            fail("越界序号应当抛异常");
        } catch (IllegalArgumentException expected) {
            assertEquals("未知配速序号: 3", expected.getMessage());
        }
    }

    @Test
    public void outOfRangeValuesAreClampedToAllowedBounds() {
        presets.setWalk(0.1);
        presets.setRun(99.0);
        assertEquals(0.5, presets.walk(), 1e-9);
        assertEquals(60.0, presets.run(), 1e-9);
    }

    @Test
    public void nanFallsBackToDefaultInsteadOfPoisoningStore() {
        assertEquals(9.0, SpeedPresets.clamp(Double.NaN, 9.0), 1e-9);
        assertEquals(15.0, SpeedPresets.clamp(Double.POSITIVE_INFINITY, 15.0), 1e-9);
    }

    @Test
    public void garbageInPreferencesFallsBackToDefault() {
        store.put("speed_preset_run", "not-a-number");
        assertEquals(9.0, presets.run(), 1e-9);
    }

    @Test
    public void formatKeepsOneDecimal() {
        assertEquals("9.0", SpeedPresets.format(9.0));
        assertEquals("12.5", SpeedPresets.format(12.5));
        assertEquals("60.0", SpeedPresets.format(61.0));
    }
}
