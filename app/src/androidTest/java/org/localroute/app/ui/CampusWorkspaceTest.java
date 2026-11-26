package org.localroute.app.ui;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.SystemClock;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.localroute.app.R;
import org.localroute.app.campus.PresetUnlock;
import org.localroute.app.run.SimulationStateRepo;
import org.localroute.app.service.SimulationService;
import org.localroute.app.settings.RunSpeedPreferences;
import org.localroute.core.RunState;
import java.io.File;
import java.io.FileOutputStream;
import java.util.function.BooleanSupplier;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.Espresso.pressBack;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.assertion.ViewAssertions.*;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class CampusWorkspaceTest {
    private Context context;
    @Test public void bottomNavReachesAllFourDestinations() throws Exception {
        // 不用 try-with-resources：本用例一路切到别的 Activity，被跟踪的实例会离开前台，
        // ActivityScenario.close() 那时移不到 DESTROYED 会抛 NPE。收尾统一交给 @After。
        ActivityScenario.launch(MainActivity.class);
        onView(withId(R.id.textNavHome)).check(matches(withText(R.string.fm_home)));
        onView(withId(R.id.textNavFunctions)).check(matches(withText(R.string.fm_functions)));
        onView(withId(R.id.textNavProfile)).check(matches(withText(R.string.fm_profile)));
        onView(withId(R.id.textNavSettings))
                .check(matches(withText(R.string.home_app_settings_title)));
        onView(withId(R.id.imageHomeHero)).check(matches(isDisplayed()));
        onView(withId(R.id.cardHomeAgenda)).check(matches(isDisplayed()));
        // 自适应折叠的承诺：首屏不用滚动就能看完，账户卡也在内。
        onView(withId(R.id.homeScroll)).check((view, error) -> {
            android.widget.ScrollView scroll = (android.widget.ScrollView) view;
            assertTrue("首页内容超出首屏，折叠没生效：content="
                            + scroll.getChildAt(0).getHeight() + " viewport=" + scroll.getHeight(),
                    scroll.getChildAt(0).getHeight() <= scroll.getHeight());
        });
        // 运行中与环境引导两张动态卡搬去了功能页，首页不该再有。
        onView(withId(R.id.cardActiveRun)).check(doesNotExist());
        onView(withId(R.id.cardSetupGuide)).check(doesNotExist());
        capture("01-home-redesign");

        onView(withId(R.id.navFunctions)).perform(click());
        awaitActivity(FunctionsActivity.class);
        onView(withId(R.id.cardCampusRun)).check(matches(isDisplayed()));
        onView(withId(R.id.cardTimetable)).perform(scrollTo()).check(matches(isDisplayed()));

        onView(withId(R.id.navProfile)).perform(click());
        awaitActivity(ProfileActivity.class);
        onView(withId(R.id.textProfileName)).check(matches(isDisplayed()));
        onView(withId(R.id.buttonProfileAccount)).check(matches(isDisplayed()));
        capture("02-profile");

        onView(withId(R.id.navSettings)).perform(click());
        awaitActivity(AppSettingsActivity.class);
        onView(withId(R.id.rowActivation)).check(matches(isDisplayed()));

        onView(withId(R.id.navHome)).perform(click());
        awaitActivity(MainActivity.class);
        onView(withId(R.id.textHomeGreeting)).check(matches(isDisplayed()));
    }
    @Test public void functionsTimetableEntryOpensWeekView() {
        try (ActivityScenario<FunctionsActivity> functions =
                     ActivityScenario.launch(FunctionsActivity.class)) {
            onView(withId(R.id.cardTimetable)).perform(scrollTo(), click());
            onView(withId(R.id.timetableScreen)).check(matches(isDisplayed()));
            onView(withId(R.id.tabTimetable)).check(matches(isSelected()));
            onView(withId(R.id.textStudyTitle)).check(matches(withText(R.string.fm_timetable)));
        }
    }
    @Before public void prepare() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        android.app.UiAutomation automation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        automation.grantRuntimePermission(context.getPackageName(), android.Manifest.permission.ACCESS_COARSE_LOCATION);
        automation.grantRuntimePermission(context.getPackageName(), android.Manifest.permission.ACCESS_FINE_LOCATION);
        automation.grantRuntimePermission(context.getPackageName(), android.Manifest.permission.POST_NOTIFICATIONS);
        shell("appops set " + context.getPackageName() + " android:mock_location allow");
        shell("appops set " + context.getPackageName() + " SYSTEM_ALERT_WINDOW allow");
        shell("settings put secure location_mode 3");
        context.getSharedPreferences("campus_run", 0).edit().clear().commit();
        context.getSharedPreferences("ui", 0).edit().clear().commit();
        PresetUnlock.activate(context.getSharedPreferences("campus_run", 0));
    }
    @After public void stopSession() {
        if (SimulationStateRepo.get().current().isActive()) {
            SimulationService.stop(context);
            await(() -> !SimulationStateRepo.get().current().isActive());
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                org.localroute.app.overlay.OverlayHost.get(context).setEnabled(false));
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
    }
    @Test public void mapOpensDirectlyAndChosenPresetSurvivesRecreation() throws Exception {
        try (ActivityScenario<CampusRunActivity> scenario = ActivityScenario.launch(CampusRunActivity.class)) {
            onView(withId(R.id.runScreen)).check(matches(isDisplayed()));
            onView(withId(R.id.setupScreen)).check(matches(withEffectiveVisibility(Visibility.GONE)));
            onView(withId(R.id.buttonGoal)).perform(click());
            onView(withText("A操")).check(matches(isDisplayed()));
            onView(withText("B操")).check(matches(isDisplayed()));
            onView(withId(R.id.buttonCloseRoutes)).check(matches(isCompletelyDisplayed()));
            final int[] safeBottom = {0};
            scenario.onActivity(activity -> {
                int navigation = androidx.core.view.ViewCompat.getRootWindowInsets(activity.getWindow().getDecorView())
                        .getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars()).bottom;
                android.view.View decor = activity.getWindow().getDecorView();
                int[] location = new int[2]; decor.getLocationOnScreen(location);
                safeBottom[0] = location[1] + decor.getHeight() - navigation;
            });
            onView(withId(R.id.buttonCloseRoutes)).check((view, error) -> {
                assertNull(error);
                int[] location = new int[2]; view.getLocationOnScreen(location);
                assertTrue("Route close button bottom " + (location[1] + view.getHeight())
                        + " exceeds safe bottom " + safeBottom[0], location[1] + view.getHeight() <= safeBottom[0]);
            });
            capture("01-route-previews");
            onView(withText("B操")).perform(click());
            onView(withId(R.id.textRouteInfo)).check(matches(withText(containsString("B操"))));
            onView(withId(R.id.mapContainer)).check(matches(isDisplayed()));
            capture("02-selected-route");
            scenario.recreate();
            onView(withId(R.id.textRouteInfo)).check(matches(withText(containsString("B操"))));
            onView(withId(R.id.buttonGoal)).perform(click());
            onView(withId(R.id.buttonDrawRoute)).perform(click());
            onView(withId(R.id.drawBar)).check(matches(isDisplayed()));
            onView(withId(R.id.buttonAdvanced)).check(matches(not(isEnabled())));
            onView(withId(R.id.buttonDrawCancel)).perform(click());
            onView(withId(R.id.textRouteInfo)).check(matches(withText(containsString("B操"))));
        }
    }
    @Test public void preciseInputsValidateAndRememberEachSport() throws Exception {
        try (ActivityScenario<CampusRunActivity> scenario = ActivityScenario.launch(CampusRunActivity.class)) {
            onView(withId(R.id.textPaceValue)).perform(click());
            onView(withId(R.id.inputModeSpeed)).perform(click());
            onView(withId(R.id.inputRunValue)).perform(replaceText("12.345"), closeSoftKeyboard());
            onView(withText("保存")).perform(click());
            assertEquals(12.345, RunSpeedPreferences.presets(context).run(), 1e-9);
            onView(withId(R.id.buttonRunSport)).perform(click());
            onView(withText("步行")).perform(click());
            onView(withId(R.id.textPaceValue)).perform(click());
            onView(withId(R.id.inputRunValue)).perform(replaceText("10:00"), closeSoftKeyboard());
            onView(withText("保存")).perform(click());
            assertEquals(6d, RunSpeedPreferences.presets(context).walk(), 1e-9);
            onView(withId(R.id.buttonRunSport)).perform(click());
            onView(withText("跑步")).perform(click());
            onView(withId(R.id.textPaceValue)).perform(click());
            onView(withId(R.id.inputModeSpeed)).perform(click());
            onView(withId(R.id.inputRunValue)).check(matches(withText("12.345")));
            onView(withId(R.id.inputModePace)).perform(click());
            onView(withText("保存")).perform(click());
            assertEquals(12.345, RunSpeedPreferences.presets(context).run(), 1e-9);
            onView(withId(R.id.textLaps)).perform(click());
            onView(withId(R.id.inputRunValue)).perform(replaceText("1001"), closeSoftKeyboard());
            onView(withText("保存")).perform(click());
            onView(withId(R.id.inputRunValue)).check(matches(hasErrorText("圈数应为 1–1000 的整数")));
            onView(withId(R.id.inputRunValue)).perform(replaceText("37"), closeSoftKeyboard());
            onView(withId(R.id.buttonLapPlus)).perform(click());
            onView(withId(R.id.inputRunValue)).check(matches(withText("38")));
            onView(withText("保存")).perform(click());
            scenario.recreate();
            onView(withId(R.id.textLaps)).check(matches(withText("38 圈")));
            capture("03-precise-plan");
        }
    }
    @Test public void advancedSettingsAndUpdaterAreReachableWithoutChangingTask() throws Exception {
        try (ActivityScenario<CampusRunActivity> scenario = ActivityScenario.launch(CampusRunActivity.class)) {
            onView(withId(R.id.buttonAdvanced)).perform(click());
            onView(withId(R.id.switchNatural)).check(matches(isDisplayed()));
            onView(withId(R.id.buttonSetupBack)).perform(click());
            onView(withId(R.id.runScreen)).check(matches(isDisplayed()));
        }
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            onView(withId(R.id.cardUpdate)).check(doesNotExist());
            onView(withId(R.id.navSettings)).perform(click());
            onView(withId(R.id.rowUpdate)).perform(scrollTo(), click());
            onView(withId(R.id.cardUpdate)).check(matches(isDisplayed()));
            capture("04-settings-update");
            pressBack();
            // 设置页多了底部导航，可视区比旧版矮 140px，返回后列表停在下沿；
            // 像用户那样滚回去再断言，验的仍是「返回后还在设置页且激活行可达」。
            onView(withId(R.id.rowActivation)).perform(scrollTo()).check(matches(isDisplayed()));
        }
    }
    @Test public void activationRefreshesPresetsOnAnExistingMap() {
        context.getSharedPreferences("campus_run", 0).edit().clear().commit();
        try (ActivityScenario<CampusRunActivity> scenario = ActivityScenario.launch(CampusRunActivity.class)) {
            onView(withId(R.id.textRouteInfo)).check(matches(withText("未选择路线")));
            scenario.onActivity(activity -> activity.startActivity(new Intent(activity, AppSettingsActivity.class)));
            awaitActivity(AppSettingsActivity.class);
            onView(withId(R.id.rowActivation)).perform(click());
            onView(isAssignableFrom(android.widget.EditText.class)).perform(replaceText("ZNLU"), closeSoftKeyboard());
            onView(withText("激活")).perform(click());
            pressBack();
            awaitActivity(CampusRunActivity.class);
            onView(withId(R.id.runScreen)).check(matches(isDisplayed()));
            onView(withId(R.id.textRouteInfo)).check(matches(withText(containsString("A操"))));
        }
    }
    @Test public void runningControlsFunctionsShortcutAndExternalCommandsStayInSync() throws Exception {
        try (ActivityScenario<FunctionsActivity> functions =
                     ActivityScenario.launch(FunctionsActivity.class)) {
            onView(withId(R.id.cardCampusRun)).perform(scrollTo(), click());
            onView(withId(R.id.buttonRunMain)).perform(click());
            await(() -> SimulationStateRepo.get().current().state() == RunState.RUNNING);
            String runId = SimulationStateRepo.get().current().runId();
            onView(withId(R.id.textLaps)).check(matches(not(isEnabled())));
            onView(withId(R.id.textPaceValue)).perform(click());
            onView(withId(R.id.inputModeSpeed)).perform(click());
            onView(withId(R.id.inputRunValue)).perform(replaceText("12.5"), closeSoftKeyboard());
            onView(withText("保存并立即调整")).perform(click());
            await(() -> Math.abs(SimulationStateRepo.get().current().speedKmh() - 12.5) < 0.8);
            onView(withId(R.id.buttonRunMain)).perform(click());
            await(() -> SimulationStateRepo.get().current().state() == RunState.PAUSED);
            pressBack();
            onView(withId(R.id.cardActiveRun)).check(matches(isDisplayed()));
            onView(withId(R.id.textActiveRunTitle))
                    .check(matches(withText(containsString(context.getString(R.string.run_state_paused)))));
            capture("05-functions-paused-task");
            onView(withId(R.id.cardActiveRun)).perform(click());
            assertEquals(runId, SimulationStateRepo.get().current().runId());
            onView(withId(R.id.buttonRunMain)).perform(click());
            await(() -> SimulationStateRepo.get().current().state() == RunState.RUNNING);
            // Notification and overlay use these same service commands.
            SimulationService.pause(context);
            await(() -> SimulationStateRepo.get().current().state() == RunState.PAUSED);
            onView(withId(R.id.buttonRunMain)).check(matches(withText("继续")));
            SimulationService.resume(context);
            await(() -> SimulationStateRepo.get().current().state() == RunState.RUNNING);
            onView(withId(R.id.buttonRunMain)).check(matches(withText("暂停")));
            onView(withId(R.id.mapContainer)).check(matches(isCompletelyDisplayed()));
            capture("06-running-map");
            onView(withId(R.id.buttonEndRun)).perform(click());
            await(() -> !SimulationStateRepo.get().current().isActive());
            onView(withId(R.id.doneOverlay)).check(matches(isDisplayed()));
            onView(withId(R.id.buttonDoneBack)).perform(click());
            onView(withId(R.id.runScreen)).check(matches(isDisplayed()));
        }
    }
    private static void await(BooleanSupplier condition) {
        long deadline = SystemClock.elapsedRealtime() + 12000;
        while (!condition.getAsBoolean() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100);
        assertTrue("Timed out waiting for service state: " + SimulationStateRepo.get().current().state()
                + " " + SimulationStateRepo.get().current().errorMessage(), condition.getAsBoolean());
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }
    @Test public void overlayPresetRemainsTheNaturalSpeedTarget() throws Exception {
        try (ActivityScenario<FunctionsActivity> scenario =
                     ActivityScenario.launch(FunctionsActivity.class)) {
            onView(withId(R.id.cardCampusRun)).perform(scrollTo(), click());
            onView(withId(R.id.buttonRunMain)).perform(click());
            await(() -> SimulationStateRepo.get().current().state() == RunState.RUNNING);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                    org.localroute.app.overlay.OverlayHost.get(context).setEnabled(true));
            onView(withId(R.id.buttonAdvanced)).perform(click());
            android.app.UiAutomation automation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
            android.accessibilityservice.AccessibilityServiceInfo info = automation.getServiceInfo();
            info.flags |= android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            automation.setServiceInfo(info);
            String ride = context.getString(R.string.overlay_gear_ride);
            await(() -> {
                for (android.view.accessibility.AccessibilityWindowInfo window : automation.getWindows()) {
                    if (clickDescription(window.getRoot(), ride)) return true;
                }
                return false;
            });
            await(() -> Math.abs(SimulationStateRepo.get().current().speedKmh() - 15d) < 0.8);
            long until = SystemClock.elapsedRealtime() + 5500;
            while (SystemClock.elapsedRealtime() < until) {
                double speed = SimulationStateRepo.get().current().speedKmh();
                assertTrue("Natural speed reverted to old target: " + speed, speed >= 14.2 && speed <= 15.8);
                SystemClock.sleep(200);
            }
            onView(withId(R.id.buttonSetupBack)).perform(click());
            onView(withId(R.id.buttonRunSport)).check(matches(withText("骑行")));
            onView(withId(R.id.textPaceValue)).check(matches(withText("配速 4:00/km")));
            onView(withId(R.id.mapContainer)).check(matches(isCompletelyDisplayed()));
            capture("07-overlay-speed-synced");
            pressBack();
            onView(withId(R.id.cardActiveRun)).check(matches(isDisplayed()));
            // Let one update settle, then require a later natural-speed update while the map is stopped.
            SystemClock.sleep(3000);
            double previousSpeed = SimulationStateRepo.get().current().speedKmh();
            await(() -> Math.abs(SimulationStateRepo.get().current().speedKmh() - previousSpeed) > 1e-6);
            // The separate floating window can cover the center of the home card.
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                    org.localroute.app.overlay.OverlayHost.get(context).setEnabled(false));
            onView(withId(R.id.cardActiveRun)).perform(click());
            onView(withId(R.id.runScreen)).check(matches(isDisplayed()));
            onView(withId(R.id.buttonRunSport)).check(matches(withText("骑行")));
        }
    }
    private static boolean clickDescription(android.view.accessibility.AccessibilityNodeInfo node, String prefix) {
        if (node == null) return false;
        CharSequence description = node.getContentDescription();
        if (description != null && description.toString().startsWith(prefix)
                && node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)) return true;
        for (int i = 0; i < node.getChildCount(); i++) if (clickDescription(node.getChild(i), prefix)) return true;
        return false;
    }
    private static void awaitActivity(Class<?> type) {
        await(() -> {
            java.util.concurrent.atomic.AtomicBoolean resumed = new java.util.concurrent.atomic.AtomicBoolean();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                for (android.app.Activity activity : androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
                        .getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)) {
                    if (type.isInstance(activity)) resumed.set(true);
                }
            });
            return resumed.get();
        });
    }
    private void capture(String name) throws Exception {
        Bitmap bitmap = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull(bitmap);
        File directory = context.getExternalFilesDir("review");
        assertNotNull(directory);
        try (FileOutputStream out = new FileOutputStream(new File(directory, name + ".png"))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
        bitmap.recycle();
        shell("mkdir -p /sdcard/Download/agri-run-review");
        shell("cp " + new File(directory, name + ".png").getAbsolutePath()
                + " /sdcard/Download/agri-run-review/" + name + ".png");
    }
    private static void shell(String command) throws Exception {
        try (java.io.InputStream in = new android.os.ParcelFileDescriptor.AutoCloseInputStream(
                InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(command))) {
            byte[] buffer = new byte[1024];
            while (in.read(buffer) != -1) { }
        }
    }
}
