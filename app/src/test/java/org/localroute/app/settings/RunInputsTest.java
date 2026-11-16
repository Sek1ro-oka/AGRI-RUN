package org.localroute.app.settings;

import org.junit.Test;
import static org.junit.Assert.*;

public class RunInputsTest {
    @Test public void paceAndSpeedRepresentTheSameTarget() {
        assertEquals(3600d / 390d, RunInputs.speed("6:30", true), 1e-10);
        assertEquals(12.345, RunInputs.speed(" 12.345 ", false), 1e-10);
        assertEquals(3600d / 390d, RunInputs.speed("6：30", true), 1e-10);
    }
    @Test public void speedAndPaceBoundariesRemainSelectable() {
        assertEquals(0.5, RunInputs.speed("120:00", true), 0);
        assertEquals(60, RunInputs.speed("1:00", true), 0);
        assertEquals("120:00", RunInputs.pace(0.5));
        assertEquals("12.345", RunInputs.speedText(12.345));
    }
    @Test public void malformedAndOutOfRangeValuesAreRejected() {
        for (String raw : new String[]{"", "NaN", "Infinity", "-1", "0", "0.49", "60.01", "1e3"}) {
            assertThrows(raw, IllegalArgumentException.class, () -> RunInputs.speed(raw, false));
        }
        for (String raw : new String[]{"6", "6:60", "-1:00", "0:00", "0:59", "120:01", "6:30:00"}) {
            assertThrows(raw, IllegalArgumentException.class, () -> RunInputs.speed(raw, true));
        }
    }
    @Test public void lapInputAcceptsOnlyWholeCountsInRange() {
        assertEquals(1, RunInputs.laps("1"));
        assertEquals(1000, RunInputs.laps("1000"));
        for (String raw : new String[]{"", "0", "1001", "1.5", "-1", "999999999999999"}) {
            assertThrows(raw, IllegalArgumentException.class, () -> RunInputs.laps(raw));
        }
    }
}
