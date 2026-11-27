package org.localroute.app.update;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.localroute.app.R;
import org.localroute.app.ui.UpdateDialogs;
import org.localroute.app.ui.UpdatesActivity;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 启动提醒弹窗：复用更新页的完整对话框，展示即记为已提醒，且不会自己开始下载。
 */
@RunWith(AndroidJUnit4.class)
public class UpdateDialogTest {

    private Context context;
    private SharedPreferences prefs;
    private final Map<String, Object> saved = new HashMap<>();

    @Before
    public void prepare() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        prefs = context.getSharedPreferences("app_update", 0);
        Object prompted = prefs.getAll().get("prompted_version");
        if (prompted != null) {
            saved.put("prompted_version", prompted);
        }
        prefs.edit().remove("prompted_version").commit();
    }

    @After
    public void restore() {
        SharedPreferences.Editor editor = prefs.edit();
        Object prompted = saved.get("prompted_version");
        if (prompted == null) {
            editor.remove("prompted_version");
        } else {
            editor.putString("prompted_version", (String) prompted);
        }
        editor.commit();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            java.util.Set<android.app.Activity> activities = new java.util.HashSet<>();
            for (androidx.test.runner.lifecycle.Stage stage
                    : androidx.test.runner.lifecycle.Stage.values()) {
                if (stage != androidx.test.runner.lifecycle.Stage.DESTROYED) {
                    activities.addAll(androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
                            .getInstance().getActivitiesInStage(stage));
                }
            }
            for (android.app.Activity activity : activities) activity.finish();
        });
    }

    @Test
    public void autoDialogMarksVersionOnceAndDoesNotDownload() throws Exception {
        UpdateChecker.Release release = release99();
        String title = context.getString(R.string.home_update_dialog_title, release.versionName());
        long downloadBefore = UpdateManager.pendingDownloadId(context);
        try (ActivityScenario<UpdatesActivity> scenario =
                     ActivityScenario.launch(UpdatesActivity.class)) {
            scenario.onActivity(activity ->
                    UpdateDialogs.showFound(activity, release, true, () -> { }));

            onView(withText(title)).inRoot(isDialog()).check(matches(isDisplayed()));
            onView(withText(R.string.home_update_ignore)).inRoot(isDialog())
                    .check(matches(isDisplayed()));
            onView(withText(R.string.home_update_download)).inRoot(isDialog())
                    .check(matches(isDisplayed()));

            onView(withText(R.string.home_update_ignore)).inRoot(isDialog()).perform(click());

            assertEquals("99.0.0", UpdateManager.promptedVersion(context));
            assertEquals(downloadBefore, UpdateManager.pendingDownloadId(context));
        }
    }

    @Test
    public void downloadButtonRunsTheDownloadAction() throws Exception {
        UpdateChecker.Release release = release99();
        AtomicBoolean clicked = new AtomicBoolean();
        try (ActivityScenario<UpdatesActivity> scenario =
                     ActivityScenario.launch(UpdatesActivity.class)) {
            scenario.onActivity(activity ->
                    UpdateDialogs.showFound(activity, release, true, () -> clicked.set(true)));

            onView(withText(R.string.home_update_download)).inRoot(isDialog()).perform(click());

            assertTrue(clicked.get());
        }
    }

    private static UpdateChecker.Release release99() throws Exception {
        return UpdateChecker.parseRelease("{\n"
                + "  \"tag_name\": \"v99.0.0\",\n"
                + "  \"name\": \"UNLOOP 99.0.0\",\n"
                + "  \"body\": \"测试更新说明\",\n"
                + "  \"html_url\": \"https://example.com/release\",\n"
                + "  \"assets\": [{\n"
                + "    \"name\": \"app-release.apk\",\n"
                + "    \"content_type\": \"application/vnd.android.package-archive\",\n"
                + "    \"browser_download_url\": \"https://example.com/app.apk\"\n"
                + "  }]\n"
                + "}");
    }
}
