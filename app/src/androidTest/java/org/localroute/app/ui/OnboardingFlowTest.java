package org.localroute.app.ui;

import android.content.Context;
import android.content.Intent;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.localroute.app.R;
import org.localroute.app.entry.HomeTarget;
import org.localroute.app.settings.OnboardingState;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;

@RunWith(AndroidJUnit4.class)
public class OnboardingFlowTest {
    private Context context;

    @Before public void prepare() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        context.getSharedPreferences(OnboardingState.PREFS, 0).edit().clear()
                .putBoolean("initialized", true).putBoolean("complete", false).commit();
    }

    @After public void cleanup() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            java.util.Set<android.app.Activity> activities = new java.util.HashSet<>();
            for (androidx.test.runner.lifecycle.Stage stage : androidx.test.runner.lifecycle.Stage.values()) {
                if (stage != androidx.test.runner.lifecycle.Stage.DESTROYED) {
                    activities.addAll(androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
                            .getInstance().getActivitiesInStage(stage));
                }
            }
            for (android.app.Activity activity : activities) activity.finish();
        });
        context.getSharedPreferences(OnboardingState.PREFS, 0).edit().clear().commit();
    }

    @Test public void shortcutWaitsUntilOnboardingIsComplete() {
        Intent intent = new Intent(context, MainActivity.class)
                .putExtra(HomeTarget.EXTRA_TARGET, HomeTarget.RUN.key());
        ActivityScenario.launch(intent);
        onView(withText(R.string.onboarding_permissions_title)).check(matches(isDisplayed()));
        onView(withId(R.id.buttonOnboardingSkip)).perform(click());
        onView(withId(R.id.buttonOnboardingSkip)).perform(click());
        onView(withId(R.id.buttonOnboardingNext)).perform(click());
        onView(withId(R.id.runScreen)).check(matches(isDisplayed()));
    }

    @Test public void cancellingFirstSchoolPickerReturnsToAccounts() {
        context.getSharedPreferences("study_jw", 0).edit().clear().commit();
        OnboardingState.setStep(context, OnboardingState.ACCOUNTS);
        try (ActivityScenario<OnboardingActivity> scenario = ActivityScenario.launch(OnboardingActivity.class)) {
            onView(withText(R.string.onboarding_import)).perform(click());
            onView(withText(R.string.study_jw_school_title)).check(matches(isDisplayed()));
            onView(withText(R.string.campus_cancel)).perform(click());
            onView(withText(R.string.onboarding_accounts_title)).check(matches(isDisplayed()));
            onView(withText(R.string.onboarding_import)).check(matches(isDisplayed()));
        }
    }

    @Test public void skippedStepsSurviveRecreationAndCompletionOpensHome() {
        try (ActivityScenario<OnboardingActivity> scenario = ActivityScenario.launch(OnboardingActivity.class)) {
            onView(withText(R.string.onboarding_permissions_title)).check(matches(isDisplayed()));
            onView(withId(R.id.buttonOnboardingSkip)).perform(click());
            onView(withText(R.string.onboarding_accounts_title)).check(matches(isDisplayed()));
            scenario.recreate();
            onView(withText(R.string.onboarding_accounts_title)).check(matches(isDisplayed()));
            assertEquals(OnboardingState.ACCOUNTS, OnboardingState.step(context));
            onView(withId(R.id.buttonOnboardingSkip)).perform(click());
            onView(withText(R.string.onboarding_finish_title)).check(matches(isDisplayed()));
            onView(withId(R.id.buttonOnboardingNext)).perform(click());
        }
        assertFalse(OnboardingState.shouldShow(context));
        try (ActivityScenario<MainActivity> ignored = ActivityScenario.launch(
                new Intent(context, MainActivity.class))) {
            onView(withId(R.id.textHomeGreeting)).check(matches(isDisplayed()));
        }
    }
}
