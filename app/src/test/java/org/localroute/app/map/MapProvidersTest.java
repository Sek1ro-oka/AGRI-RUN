package org.localroute.app.map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MapProvidersTest {

    @Test
    public void blankOrNullKeyCountsAsMissing() {
        assertFalse(MapProviders.hasKey(null));
        assertFalse(MapProviders.hasKey(""));
        assertFalse(MapProviders.hasKey("   "));
        assertEquals(MapProviders.SdkStatus.KEY_MISSING, MapProviders.status(null));
    }

    @Test
    public void presentKeyStillReportsSdkPending() {
        assertTrue(MapProviders.hasKey("abcdef0123456789"));
        assertEquals(MapProviders.SdkStatus.KEY_PRESENT_SDK_PENDING,
                MapProviders.status("abcdef0123456789"));
    }

    @Test
    public void keyIsMaskedInLogsAndNotes() {
        assertEquals("abcd…6789", MapProviders.maskKey("abcdef0123456789"));
        assertEquals("（未配置）", MapProviders.maskKey("  "));
        assertEquals("****", MapProviders.maskKey("short"));
    }

    @Test
    public void noteNeverClaimsOfficialSdkIsActive() {
        String missing = MapProviders.note(null);
        String present = MapProviders.note("abcdef0123456789");
        assertTrue(missing.contains("未配置高德 Key"));
        assertTrue(missing.contains("栅格瓦片"));
        assertTrue(present.contains("待接入"));
        assertTrue(present.contains("栅格瓦片"));
        assertFalse(present.contains("abcdef0123456789"));
    }
}
