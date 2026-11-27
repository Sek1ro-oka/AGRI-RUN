package org.localroute.app.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.os.ParcelFileDescriptor;
import android.view.ViewGroup;
import android.widget.ScrollView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.localroute.app.R;
import org.localroute.app.settings.OnboardingState;
import org.localroute.app.study.CustomHomework;
import org.localroute.app.study.CustomHomeworkStore;
import org.localroute.app.study.SemesterCalendar;
import org.localroute.app.study.StudyTimetableStore;
import org.localroute.app.study.TimetableCourse;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 首页 C+ 的真实布局：固定六节课 + 两条作业、今天/明天分组与底部「还有」出口。
 *
 * <p>种子数据按设备日期生成；在 10:30 的截图上今天应显示已上 1 节、余 3 节，
 * 明天三节（含 19:00 晚课），周五两节则进入底部摘要。
 */
@RunWith(AndroidJUnit4.class)
public class HomeUpcomingUiTest {

    private static final String[] PREFS = {
            StudyTimetableStore.PREFS, "custom_homework", OnboardingState.PREFS
    };

    private Context context;
    private final Map<String, Map<String, ?>> saved = new LinkedHashMap<>();

    @Before
    public void prepare() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        for (String name : PREFS) {
            saved.put(name, new HashMap<>(context.getSharedPreferences(name, 0).getAll()));
            context.getSharedPreferences(name, 0).edit().clear().commit();
        }
        seed();
    }

    @After
    public void restore() {
        for (String name : PREFS) {
            SharedPreferences.Editor editor = context.getSharedPreferences(name, 0).edit();
            editor.clear();
            Map<String, ?> values = saved.get(name);
            if (values != null) {
                for (Map.Entry<String, ?> entry : values.entrySet()) {
                    Object value = entry.getValue();
                    if (value instanceof String) {
                        editor.putString(entry.getKey(), (String) value);
                    } else if (value instanceof Integer) {
                        editor.putInt(entry.getKey(), (Integer) value);
                    } else if (value instanceof Long) {
                        editor.putLong(entry.getKey(), (Long) value);
                    } else if (value instanceof Float) {
                        editor.putFloat(entry.getKey(), (Float) value);
                    } else if (value instanceof Boolean) {
                        editor.putBoolean(entry.getKey(), (Boolean) value);
                    } else if (value instanceof Set) {
                        // 这些偏好里没有集合，占位保证未知类型不被静默写成字符串。
                        throw new AssertionError("unexpected pref type: " + entry.getKey());
                    }
                }
            }
            editor.commit();
        }
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
    public void fixedSixCoursesAndTwoHomeworkFitTheFirstScreen() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            onView(withId(R.id.textHomeDate)).check(matches(withText(R.string.home_upcoming_title)));
            onView(withId(R.id.textHomeworkSection))
                    .check(matches(withText(getString(R.string.home_homework_header, 2))));
            boolean tall = screenHeightDp() >= 800;
            onView(withId(R.id.agendaList)).check((view, error) -> {
                ViewGroup list = (ViewGroup) view;
                int rows = 0;
                for (int i = 0; i < list.getChildCount(); i++) {
                    if (list.getChildAt(i).getId() == R.id.rowUpcomingCourse) {
                        rows++;
                    }
                }
                if (tall) {
                    assertEquals("高屏课程行不是六节", 6, rows);
                } else {
                    assertTrue("短屏课程行应被自适应收起", rows > 0 && rows < 6);
                }
            });
            onView(withId(R.id.homeworkList)).check((view, error) -> {
                ViewGroup list = (ViewGroup) view;
                int rows = 0;
                for (int i = 0; i < list.getChildCount(); i++) {
                    if (list.getChildAt(i).getId() == R.id.rowHomeHomework) {
                        rows++;
                    }
                }
                if (tall) {
                    assertEquals("高屏作业行不是两条", 2, rows);
                } else {
                    assertTrue("短屏作业行应保留", rows >= 1 && rows <= 2);
                }
            });
            onView(withId(R.id.textHomeAgendaCount)).check(matches(isDisplayed()));
            onView(withId(R.id.homeScroll)).check((view, error) -> {
                ScrollView scroll = (ScrollView) view;
                assertTrue("首页内容超出首屏：content=" + scroll.getChildAt(0).getHeight()
                                + " viewport=" + scroll.getHeight(),
                        scroll.getChildAt(0).getHeight() <= scroll.getHeight());
            });
            onView(withId(R.id.cardHomeAgenda)).check(matches(isDisplayed()));
            capture("home-cplus");
        }
    }

    private void seed() {
        LocalDate today = LocalDate.now();
        LocalDate start = SemesterCalendar.mondayOf(today).minusWeeks(10);
        StudyTimetableStore.saveSemesterStart(context, start);
        StudyTimetableStore.saveTotalWeeks(context, 20);

        int todayWeekday = today.getDayOfWeek().getValue();
        int tomorrow = today.plusDays(1).getDayOfWeek().getValue();
        int dayAfter = today.plusDays(2).getDayOfWeek().getValue();
        int dayThree = today.plusDays(3).getDayOfWeek().getValue();
        List<TimetableCourse> courses = new ArrayList<>();
        add(courses, "高等数学", "张老师", "A101", todayWeekday, 1, 2);
        add(courses, "大学英语", "李老师", "B202", todayWeekday, 3, 4);
        add(courses, "数据结构", "王老师", "C303", todayWeekday, 5, 6);
        add(courses, "体育", "赵老师", "操场", todayWeekday, 7, 8);
        add(courses, "线性代数", "刘老师", "A102", tomorrow, 1, 2);
        add(courses, "计算机网络", "陈老师", "C201", tomorrow, 3, 4);
        add(courses, "大学物理", "孙老师", "综合楼201", tomorrow, 9, 10);
        add(courses, "操作系统", "周老师", "A201", dayAfter, 3, 4);
        add(courses, "英语角", "Amy", "图书馆", dayAfter, 5, 6);
        add(courses, "概率论", "吴老师", "B101", dayThree, 1, 2);
        add(courses, "马克思主义", "郑老师", "D202", dayThree, 3, 4);
        StudyTimetableStore.saveCourses(context, courses);

        long firstDeadline = System.currentTimeMillis() + 2L * 60 * 60 * 1000;
        long secondDeadline = System.currentTimeMillis() + 26L * 60 * 60 * 1000;
        CustomHomeworkStore.save(context, java.util.Arrays.asList(
                CustomHomework.create("网络实验报告", firstDeadline),
                CustomHomework.create("高等数学作业", secondDeadline)));

        context.getSharedPreferences(OnboardingState.PREFS, 0).edit()
                .putBoolean("initialized", true)
                .putBoolean("complete", true)
                .commit();
    }

    private static void add(List<TimetableCourse> courses, String name, String teacher,
                            String location, int weekday, int start, int end) {
        courses.add(TimetableCourse.create(name, teacher, location, weekday, start, end, "1-20"));
    }

    private int screenHeightDp() {
        return Math.round(context.getResources().getDisplayMetrics().heightPixels
                / context.getResources().getDisplayMetrics().density);
    }

    private String getString(int res, Object... args) {
        return context.getString(res, args);
    }

    private void capture(String name) throws Exception {
        Bitmap bitmap = InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .takeScreenshot();
        assertNotNull(bitmap);
        boolean dark = (context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        String file = name + (dark ? "-dark" : "-light");
        File directory = context.getExternalFilesDir("review");
        assertNotNull(directory);
        try (FileOutputStream out = new FileOutputStream(new File(directory, file + ".png"))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
        bitmap.recycle();
        shell("mkdir -p /sdcard/Download/agri-run-review");
        shell("cp " + new File(directory, file + ".png").getAbsolutePath()
                + " /sdcard/Download/agri-run-review/" + file + ".png");
    }

    private static void shell(String command) throws Exception {
        try (ParcelFileDescriptor descriptor =
                     InstrumentationRegistry.getInstrumentation().getUiAutomation()
                             .executeShellCommand(command);
             FileInputStream in = new FileInputStream(descriptor.getFileDescriptor())) {
            byte[] buffer = new byte[1024];
            while (in.read(buffer) != -1) {
            }
        }
    }
}
