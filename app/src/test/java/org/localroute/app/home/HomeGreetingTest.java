package org.localroute.app.home;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** 问候语与插画时段的边界：5 点起早晨，12 点起下午，18 点起晚上。 */
public class HomeGreetingTest {

    @Test public void nightCoversEveningAndSmallHours() {
        assertEquals(HomeGreeting.Phase.NIGHT, HomeGreeting.phaseOf(18));
        assertEquals(HomeGreeting.Phase.NIGHT, HomeGreeting.phaseOf(23));
        assertEquals(HomeGreeting.Phase.NIGHT, HomeGreeting.phaseOf(0));
        assertEquals(HomeGreeting.Phase.NIGHT, HomeGreeting.phaseOf(4));
    }

    @Test public void morningStartsAtFiveAndEndsBeforeNoon() {
        assertEquals(HomeGreeting.Phase.MORNING, HomeGreeting.phaseOf(5));
        assertEquals(HomeGreeting.Phase.MORNING, HomeGreeting.phaseOf(11));
    }

    @Test public void afternoonStartsAtNoonAndEndsBeforeEighteen() {
        assertEquals(HomeGreeting.Phase.DAY, HomeGreeting.phaseOf(12));
        assertEquals(HomeGreeting.Phase.DAY, HomeGreeting.phaseOf(17));
    }
}