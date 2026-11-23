package org.localroute.app.backup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

import java.util.List;

public class BackupCodecTest {

    private static BackupBundle sample() {
        BackupBundle bundle = new BackupBundle(1_764_000_000_000L, "0.10.0", 40);
        try {
            bundle.put(new BackupSection("routes", 2, 1, new JSONObject().put("r1", "甲")));
            bundle.put(new BackupSection("appearance", 1, 1, new JSONObject().put("night_mode", 2)));
        } catch (JSONException e) {
            throw new AssertionError(e);
        }
        return bundle;
    }

    @Test
    public void roundTrip_keepsMetaAndSections() throws JSONException {
        BackupCodec.DecodeResult result = BackupCodec.decode(BackupCodec.encode(sample()));

        assertTrue(result.error(), result.ok());
        assertTrue(result.warnings().isEmpty());
        BackupBundle restored = result.bundle();
        assertNotNull(restored);
        assertEquals(1_764_000_000_000L, restored.createdAtMillis());
        assertEquals("0.10.0", restored.appVersionName());
        assertEquals(40, restored.appVersionCode());
        assertEquals(List.of("routes", "appearance"), restored.names());
        assertEquals(2, restored.get("routes").schema());
        assertEquals("甲", restored.get("routes").payload().optString("r1"));
    }

    @Test
    public void encode_isHumanReadableAndSelfIdentifying() throws JSONException {
        String text = BackupCodec.encode(sample());

        assertTrue(text.contains(BackupBundle.FORMAT_ID));
        // 缩进输出，用户可以直接用文本编辑器看出内容。
        assertTrue(text.contains("\n  \"format\""));
        // 去掉格式标记后必须认不出来，不能当成备份硬读。
        assertFalse(BackupCodec.decode(
                text.replace("\"format\": \"unloop.backup\",", "")).ok());
    }

    @Test
    public void decode_rejectsForeignAndBrokenFiles() {
        assertTrue(BackupCodec.decode(null).error().contains("空"));
        assertTrue(BackupCodec.decode("   ").error().contains("空"));
        assertTrue(BackupCodec.decode("[1,2,3]").error().contains("JSON"));
        assertTrue(BackupCodec.decode("{\"format\":\"other.app\"}").error().contains("other.app"));
        assertTrue(BackupCodec.decode("{}").error().contains(BackupBundle.FORMAT_ID));
    }

    @Test
    public void decode_refusesNewerFormatVersion() {
        String text = "{\"format\":\"unloop.backup\",\"formatVersion\":"
                + (BackupBundle.FORMAT_VERSION + 1) + ",\"sections\":[]}";

        BackupCodec.DecodeResult result = BackupCodec.decode(text);

        assertFalse(result.ok());
        assertTrue(result.error().contains("升级"));
    }

    @Test
    public void decode_skipsBrokenSectionButKeepsGoodOnes() {
        String text = "{\"format\":\"unloop.backup\",\"formatVersion\":1,\"sections\":["
                + "{\"name\":\"routes\",\"data\":{\"a\":1}},"
                + "{\"name\":\"timetable\",\"data\":\"坏掉的字符串\"},"
                + "{\"data\":{\"noName\":1}}]}";

        BackupCodec.DecodeResult result = BackupCodec.decode(text);
        List<String> warnings = result.warnings();

        assertTrue(result.ok());
        assertTrue(result.bundle().has("routes"));
        assertFalse(result.bundle().has("timetable"));
        assertEquals(2, warnings.size());
        assertTrue(warnings.get(0).contains("timetable"));
        assertTrue(warnings.get(1).contains("名称"));
    }

    @Test
    public void decode_missingSections_isEmptyWithWarning() {
        BackupCodec.DecodeResult result = BackupCodec.decode("{\"format\":\"unloop.backup\"}");

        assertTrue(result.ok());
        assertTrue(result.bundle().names().isEmpty());
        assertEquals(1, result.warnings().size());
    }

    @Test
    public void decode_brokenTimestamp_doesNotFailWholeFile() {
        String text = "{\"format\":\"unloop.backup\",\"createdAt\":\"昨天\","
                + "\"sections\":[{\"name\":\"routes\",\"data\":{}}]}";

        BackupCodec.DecodeResult result = BackupCodec.decode(text);

        assertNull(result.error());
        assertEquals(0L, result.bundle().createdAtMillis());
        assertTrue(result.bundle().has("routes"));
    }

    @Test
    public void section_clampsNegativeCount() {
        BackupSection section = new BackupSection("routes", 1, -5, new JSONObject());

        assertEquals(0, section.count());
    }

    @Test(expected = IllegalArgumentException.class)
    public void section_rejectsEmptyName() {
        new BackupSection("", 1, 0, new JSONObject());
    }

    @Test
    public void unknownSections_areReportedForPreservation() {
        BackupBundle bundle = sample();

        assertEquals(List.of(), bundle.unknownNames(List.of("routes", "appearance")));
        assertEquals(List.of("appearance"), bundle.unknownNames(List.of("routes")));
    }
}
