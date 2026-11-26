package org.localroute.app.settings;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class OnboardingStateTest {
    @Test public void packageUpdateIsDistinguishedFromFreshInstall() {
        assertFalse(OnboardingState.isUpgrade(1000L, 1000L));
        assertTrue(OnboardingState.isUpgrade(1000L, 2000L));
        assertTrue(OnboardingState.isUpgrade(0L, 2000L));
    }
}
