package org.localroute.app.campus;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 激活码只认 ZNLU；空白与大小写容错，其它内容一律不通过。 */
public class PresetUnlockTest {

    @Test
    public void exactCodeMatches() {
        assertTrue(PresetUnlock.matches("ZNLU"));
    }

    @Test
    public void surroundingSpacesAndCaseAreTolerated() {
        assertTrue(PresetUnlock.matches("  znlu "));
    }

    @Test
    public void otherInputsAreRejected() {
        assertFalse(PresetUnlock.matches(null));
        assertFalse(PresetUnlock.matches(""));
        assertFalse(PresetUnlock.matches("ZNL"));
        assertFalse(PresetUnlock.matches("ZNLU2"));
        assertFalse(PresetUnlock.matches("ABC"));
    }
}
