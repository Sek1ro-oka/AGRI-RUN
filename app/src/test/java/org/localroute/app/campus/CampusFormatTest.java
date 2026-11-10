package org.localroute.app.campus;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** 配速 / 时间 / 距离格式。 */
public class CampusFormatTest {

    @Test
    public void formatsElapsedTime() {
        assertEquals("00:00:00", CampusFormat.time(0L));
        assertEquals("00:04:53", CampusFormat.time(293_000L));
        assertEquals("01:02:03", CampusFormat.time(3_723_000L));
    }

    @Test
    public void formatsPace() {
        assertEquals("6'40\"", CampusFormat.pace(400d));
        assertEquals("12'00\"", CampusFormat.pace(720d));
        assertEquals("1'05\"", CampusFormat.pace(65d));
        assertEquals("--'--\"", CampusFormat.pace(0d));
        assertEquals("6'40\"/km", CampusFormat.paceWithUnit(400d));
    }

    @Test
    public void roundsSecondsCarryIntoMinutes() {
        assertEquals("1'00\"", CampusFormat.pace(59.6d));
    }

    @Test
    public void formatsDistance() {
        assertEquals("0.00", CampusFormat.kilometers(0d));
        assertEquals("0.40", CampusFormat.kilometers(400d));
        assertEquals("2.00", CampusFormat.kilometers(2000d));
        assertEquals("400 m", CampusFormat.lapMeters(400.2d));
        assertEquals("1.2 km", CampusFormat.lapMeters(1200d));
    }

    @Test
    public void averagePaceUsesElapsedAndDistance() {
        // 400 米用 160 秒 → 6'40"/km。
        assertEquals(400d, CampusFormat.averagePaceSeconds(160_000L, 400d), 0.01d);
        assertEquals(0d, CampusFormat.averagePaceSeconds(10_000L, 0d), 0d);
    }

    @Test
    public void formatsCoordinates() {
        assertEquals("30.25610°N 119.72360°E", CampusFormat.coordinates(30.2561d, 119.7236d));
    }
}
