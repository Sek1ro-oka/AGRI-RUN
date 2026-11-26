package org.localroute.app.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.localroute.app.R;
import org.localroute.app.study.JwSemesterInfo;
import org.localroute.app.study.StudyTimetableStore;
import org.localroute.app.study.TimetableCourse;

import java.io.File;
import java.io.FileOutputStream;
import java.time.LocalDate;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.doesNotExist;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotNull;

/**
 * 教务导入没读到学期日期时的补填流程。
 *
 * <p>预览先说明「导入完成后要选日期」；确认后课程照常写入，再弹一次补填。
 * 点「稍后设置」必须清掉旧学期的日期，避免新学期静默沿用旧值；选日期则写入所选锚点。
 */
@RunWith(AndroidJUnit4.class)
public class StudyImportMissingStartTest {

    /** StudyTimetableStore 的偏好键（三个包内常量 + 一个私有常量），改名时这里要同步。 */
    private static final String[] TOUCHED = {
            "courses", "semester_start", "total_weeks", "current_week"
    };

    private Context context;
    private SharedPreferences prefs;
    private final Map<String, Object> saved = new HashMap<>();

    @Before
    public void prepare() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        prefs = context.getSharedPreferences(StudyTimetableStore.PREFS, 0);
        Map<String, ?> all = prefs.getAll();
        for (String key : TOUCHED) {
            if (all.containsKey(key)) saved.put(key, all.get(key));
        }
        prefs.edit().remove("courses").remove("semester_start")
                .remove("total_weeks").remove("current_week").commit();
    }

    @After
    public void restore() {
        SharedPreferences.Editor editor = prefs.edit();
        for (String key : TOUCHED) {
            Object value = saved.get(key);
            if (value == null) {
                editor.remove(key);
            } else if (value instanceof String) {
                editor.putString(key, (String) value);
            } else if (value instanceof Integer) {
                editor.putInt(key, (Integer) value);
            } else if (value instanceof Long) {
                editor.putLong(key, (Long) value);
            } else if (value instanceof Float) {
                editor.putFloat(key, (Float) value);
            } else if (value instanceof Boolean) {
                editor.putBoolean(key, (Boolean) value);
            }
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
    public void missingStartPromptsAfterImportAndLaterClearsOldDate() throws Exception {
        List<TimetableCourse> courses = Collections.singletonList(TimetableCourse.create(
                "高等数学", "张老师", "A101", 1, 1, 2, "1-16"));
        JwSemesterInfo noAnchor = JwSemesterInfo.read(
                new JSONObject("{\"xnm\":\"2026-2027\",\"xqm\":\"3\"}"));
        try (ActivityScenario<StudySettingsActivity> scenario =
                     ActivityScenario.launch(StudySettingsActivity.class)) {
            scenario.onActivity(activity -> {
                StudyTimetableStore.saveSemesterStart(activity, LocalDate.of(2026, 3, 2));
                StudyDialogs.showImportPreview(activity, courses, "测试来源",
                        Collections.emptyList(), noAnchor, null);
            });
            onView(withId(R.id.textImportSummary)).inRoot(isDialog())
                    .check(matches(withText(containsString("教务未提供学期日期"))));
            capture("11-import-preview-missing-start");

            onView(withText(R.string.study_import_confirm)).inRoot(isDialog()).perform(click());

            onView(withText(R.string.study_semester_start_title)).inRoot(isDialog())
                    .check(matches(isDisplayed()));
            onView(withText(containsString("2026-03-02"))).inRoot(isDialog())
                    .check(matches(isDisplayed()));
            capture("12-import-missing-start-prompt");

            onView(withText(R.string.study_import_start_later)).inRoot(isDialog()).perform(click());

            assertNull(StudyTimetableStore.loadSemesterStart(context));
            assertFalse(StudyTimetableStore.loadCourses(context).isEmpty());
        }
    }

    @Test
    public void missingStartPickedDateIsSaved() throws Exception {
        List<TimetableCourse> courses = Collections.singletonList(TimetableCourse.create(
                "大学英语", "李老师", "B202", 3, 3, 4, "1-16"));
        JwSemesterInfo noAnchor = JwSemesterInfo.read(
                new JSONObject("{\"xnm\":\"2026-2027\",\"xqm\":\"3\"}"));
        try (ActivityScenario<StudySettingsActivity> scenario =
                     ActivityScenario.launch(StudySettingsActivity.class)) {
            scenario.onActivity(activity -> StudyDialogs.showImportPreview(activity, courses,
                    "测试来源", Collections.emptyList(), noAnchor, null));
            onView(withText(R.string.study_import_confirm)).inRoot(isDialog()).perform(click());
            onView(withText(R.string.study_import_start_choose)).inRoot(isDialog()).perform(click());
            // 日期选择器默认停在今天，直接确认即可写入锚点
            onView(withText(android.R.string.ok)).inRoot(isDialog()).perform(click());

            assertEquals(LocalDate.now(), StudyTimetableStore.loadSemesterStart(context));
            assertFalse(StudyTimetableStore.loadCourses(context).isEmpty());
        }
    }

    /** 往学期课表：预览说明不动本机日期，导入后也不能弹补填框。 */
    @Test
    public void otherTermImportKeepsSemesterStartWithoutPrompt() throws Exception {
        List<TimetableCourse> courses = Collections.singletonList(TimetableCourse.create(
                "线性代数", "王老师", "C303", 2, 5, 6, "1-16"));
        JwSemesterInfo pastTerm = JwSemesterInfo.read(
                new JSONObject("{\"xnm\":\"2019-2020\",\"xqm\":\"2\",\"xnWeeks\":\"9\"}"));
        LocalDate existing = LocalDate.of(2026, 9, 1);
        try (ActivityScenario<StudySettingsActivity> scenario =
                     ActivityScenario.launch(StudySettingsActivity.class)) {
            scenario.onActivity(activity -> {
                StudyTimetableStore.saveSemesterStart(activity, existing);
                StudyDialogs.showImportPreview(activity, courses, "测试来源",
                        Collections.emptyList(), pastTerm, null);
            });
            onView(withId(R.id.textImportSummary)).inRoot(isDialog())
                    .check(matches(withText(containsString("属于其他学期"))));

            onView(withText(R.string.study_import_confirm)).inRoot(isDialog()).perform(click());

            // 「稍后设置」只在补填弹窗里出现；不在对话框根里查找，避免没有根时抛无匹配异常
            onView(withText(R.string.study_import_start_later)).check(doesNotExist());
            assertEquals(existing, StudyTimetableStore.loadSemesterStart(context));
            assertFalse(StudyTimetableStore.loadCourses(context).isEmpty());
        }
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
        try (android.os.ParcelFileDescriptor descriptor =
                     InstrumentationRegistry.getInstrumentation().getUiAutomation()
                             .executeShellCommand(command);
             java.io.FileInputStream in = new java.io.FileInputStream(descriptor.getFileDescriptor())) {
            byte[] buffer = new byte[1024];
            while (in.read(buffer) != -1) { }
        }
    }
}
