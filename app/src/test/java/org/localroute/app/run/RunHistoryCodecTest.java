package org.localroute.app.run;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 历史记录的编解码：坏数据只能丢条目，不能把整份历史读没了。
 */
public class RunHistoryCodecTest {

    private static RunRecord sample() {
        return new RunRecord("run-7", "北五操场", 1_766_100_000_000L, 1_500_000L,
                3400.5d, 8, 10, 425d, false);
    }

    @Test
    public void roundTripKeepsEveryField() {
        RunRecord source = sample();
        List<RunRecord> decoded = RunHistoryCodec.decode(
                RunHistoryCodec.encode(new ArrayList<>(Arrays.asList(source))));
        assertEquals(1, decoded.size());
        RunRecord back = decoded.get(0);
        assertEquals(source.id(), back.id());
        assertEquals(source.routeName(), back.routeName());
        assertEquals(source.endedAtMillis(), back.endedAtMillis());
        assertEquals(source.activeMillis(), back.activeMillis());
        assertEquals(source.distanceMeters(), back.distanceMeters(), 0.0001);
        assertEquals(source.loops(), back.loops());
        assertEquals(source.targetLoops(), back.targetLoops());
        assertEquals(source.lapMeters(), back.lapMeters(), 0.0001);
        assertEquals(source.finishedByGoal(), back.finishedByGoal());
    }

    @Test
    public void emptyAndBrokenInputDecodeToEmptyList() {
        assertTrue(RunHistoryCodec.decode(null).isEmpty());
        assertTrue(RunHistoryCodec.decode("").isEmpty());
        assertTrue(RunHistoryCodec.decode("{ 这不是 JSON").isEmpty());
        assertEquals("[]", RunHistoryCodec.encode(new ArrayList<RunRecord>()));
    }

    @Test
    public void brokenEntriesAreSkippedAroundGoodOnes() {
        String json = "[{\"ended_at\":0,\"meters\":800},"
                + "{\"ended_at\":1766100000000,\"meters\":0},"
                + "{\"ended_at\":1766100000000,\"meters\":\"很多\"},"
                + "null,"
                + "{\"id\":\"ok\",\"ended_at\":1766100000000,\"meters\":1200,\"active_ms\":600000}]";
        List<RunRecord> records = RunHistoryCodec.decode(json);
        assertEquals(1, records.size());
        assertEquals("ok", records.get(0).id());
        assertEquals(1200d, records.get(0).distanceMeters(), 0.0001);
    }

    @Test
    public void missingOptionalFieldsFallBackInsteadOfThrowing() {
        List<RunRecord> records = RunHistoryCodec.decode(
                "[{\"ended_at\":1766100000000,\"meters\":1000}]");
        assertEquals(1, records.size());
        RunRecord record = records.get(0);
        assertEquals("", record.id());
        assertEquals("", record.routeName());
        assertEquals(0, record.loops());
        assertFalse(record.finishedByGoal());
        assertEquals(0, record.targetLoops());
    }

    @Test
    public void negativeValuesAreClamped() {
        RunRecord record = new RunRecord("x", "y", 1L, -5_000L, -20d, -3, -4, -400d, true);
        assertEquals(0L, record.activeMillis());
        assertEquals(0d, record.distanceMeters(), 0.0001);
        assertEquals(0, record.loops());
        assertEquals(0, record.targetLoops());
        assertEquals(0d, record.lapMeters(), 0.0001);
    }

    @Test
    public void paceNeedsBothDistanceAndTime() {
        // 1500 秒 / 3.4005 公里 ≈ 441 秒每公里，即 7'21"。
        assertEquals(441, sample().paceSecondsPerKm());
        assertEquals(-1, new RunRecord("a", "b", 1L, 0L, 1000d, 0, 0, 0d, false).paceSecondsPerKm());
        assertEquals(-1, new RunRecord("a", "b", 1L, 600_000L, 0d, 0, 0, 0d, false).paceSecondsPerKm());
    }
}
