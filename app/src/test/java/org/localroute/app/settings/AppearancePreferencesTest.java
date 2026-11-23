package org.localroute.app.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import androidx.appcompat.app.AppCompatDelegate;

import org.junit.Test;

public class AppearancePreferencesTest {

    @Test
    public void defaultIsFollowSystem() {
        assertEquals(AppearancePreferences.MODE_FOLLOW_SYSTEM, AppearancePreferences.DEFAULT_MODE);
        assertEquals(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
                AppearancePreferences.nightModeFor(AppearancePreferences.DEFAULT_MODE));
    }

    @Test
    public void mapsEachModeToItsNightConstant() {
        assertEquals(AppCompatDelegate.MODE_NIGHT_NO,
                AppearancePreferences.nightModeFor(AppearancePreferences.MODE_LIGHT));
        assertEquals(AppCompatDelegate.MODE_NIGHT_YES,
                AppearancePreferences.nightModeFor(AppearancePreferences.MODE_DARK));
    }

    @Test
    public void unknownModeFallsBackToSystemDefault() {
        assertEquals(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, AppearancePreferences.nightModeFor(77));
        assertEquals(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, AppearancePreferences.nightModeFor(-3));
        assertFalse(AppearancePreferences.isKnown(77));
        assertTrue(AppearancePreferences.isKnown(AppearancePreferences.MODE_DARK));
    }

    @Test
    public void choiceListCoversEveryModeOnceInDisplayOrder() {
        int[] modes = AppearancePreferences.MODES;
        assertEquals(3, modes.length);
        assertEquals(AppearancePreferences.MODE_FOLLOW_SYSTEM, modes[0]);
        assertNotEquals(modes[0], modes[1]);
        assertNotEquals(modes[1], modes[2]);
        assertNotEquals(modes[0], modes[2]);
        for (int mode : modes) {
            assertTrue(AppearancePreferences.isKnown(mode));
            assertNotEquals(0, AppearancePreferences.labelFor(mode));
        }
        assertNotEquals(AppearancePreferences.labelFor(AppearancePreferences.MODE_LIGHT),
                AppearancePreferences.labelFor(AppearancePreferences.MODE_DARK));
    }
}
