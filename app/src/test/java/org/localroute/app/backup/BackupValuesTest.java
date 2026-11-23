package org.localroute.app.backup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

public class BackupValuesTest {

    @Test
    public void roundTrip_keepsTheDeclaredType() throws JSONException {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("flag", true);
        values.put("count", 7);
        values.put("stamp", 9_000_000_000L);
        values.put("ratio", 0.25f);
        values.put("text", "操场");

        Map<String, Object> restored = BackupValues.unpack(BackupValues.pack(values));

        assertEquals(values.keySet(), restored.keySet());
        assertSame(Boolean.class, restored.get("flag").getClass());
        assertSame(Integer.class, restored.get("count").getClass());
        assertSame(Long.class, restored.get("stamp").getClass());
        assertSame(Float.class, restored.get("ratio").getClass());
        assertSame(String.class, restored.get("text").getClass());
        assertEquals(7, restored.get("count"));
        assertEquals(0.25f, (Float) restored.get("ratio"), 0f);
    }

    @Test
    public void doubleIsStoredAsFloatAndNullValuesAreSkipped() throws JSONException {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("speed", 3.5d);
        values.put("gone", null);

        JSONObject packed = BackupValues.pack(values);
        Map<String, Object> restored = BackupValues.unpack(packed);

        assertEquals(1, restored.size());
        assertSame(Float.class, restored.get("speed").getClass());
        assertEquals(3.5f, (Float) restored.get("speed"), 0f);
        assertEquals(BackupValues.TYPE_FLOAT, packed.getJSONObject("speed").getString("t"));
    }

    /** JSON 里 3 和 3.0 是同一种数字，类型标记丢了就会在写回时抛 ClassCastException。 */
    @Test
    public void missingOrUnknownTypeTag_isSkippedInsteadOfGuessed() throws JSONException {
        JSONObject root = new JSONObject();
        root.put("guessed", new JSONObject().put("t", "?").put("v", 1));
        root.put("noTag", new JSONObject().put("v", 1));
        root.put("noValue", new JSONObject().put("t", BackupValues.TYPE_INT));
        root.put("nullValue", new JSONObject().put("t", BackupValues.TYPE_STRING)
                .put("v", JSONObject.NULL));

        Map<String, Object> restored = BackupValues.unpack(root);

        assertEquals(1, restored.size());
        assertEquals("", restored.get("nullValue"));
    }

    @Test
    public void unpackHandlesNullRoot() {
        assertTrue(BackupValues.unpack(null).isEmpty());
    }

    @Test
    public void readers_fallBackInsteadOfThrowing() {
        assertEquals(12L, BackupValues.asLong(" 12 ", 0L));
        assertEquals(-1L, BackupValues.asLong("abc", -1L));
        assertEquals(7L, BackupValues.asLong(7.9, -1L));
        assertEquals(0.5f, BackupValues.asFloat("0.5", 0f), 0f);
        assertEquals(1f, BackupValues.asFloat("not-a-number", 1f), 0f);
        assertTrue(BackupValues.asBoolean("true", false));
        assertFalse(BackupValues.asBoolean("yes", true));
        assertEquals("", BackupValues.asString(null, ""));
    }
}
