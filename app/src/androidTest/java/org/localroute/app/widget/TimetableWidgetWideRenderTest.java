package org.localroute.app.widget;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.ParcelFileDescriptor;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.RemoteViews;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.localroute.app.R;
import org.localroute.app.study.SemesterCalendar;
import org.localroute.app.study.StudyTimetableStore;
import org.localroute.app.study.TimetableCourse;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 双日课表小组件的真实 RemoteViews 渲染：4×2/4×3、浅色/深色。
 *
 * <p>不是启动桌面，而是把 provider 产出的 RemoteViews 真正 inflate、测量、绘制成图；
 * 能抓到 RemoteViews 不支持的属性/布局，也给 PR 留下组件实拍。
 */
@RunWith(AndroidJUnit4.class)
public class TimetableWidgetWideRenderTest {

    private static final String PREFS = StudyTimetableStore.PREFS;
    private Context context;
    private Map<String, ?> saved;

    @Before
    public void prepare() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        saved = new HashMap<>(context.getSharedPreferences(PREFS, 0).getAll());
        context.getSharedPreferences(PREFS, 0).edit().clear().commit();
        seed();
    }

    @After
    public void restore() {
        SharedPreferences.Editor editor = context.getSharedPreferences(PREFS, 0).edit();
        editor.clear();
        for (Map.Entry<String, ?> entry : saved.entrySet()) {
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
                throw new AssertionError("unexpected pref type: " + entry.getKey());
            }
        }
        editor.commit();
    }

    @Test
    public void rendersWideWidgetsAtBothThemesAndSizes() throws Exception {
        renderAndCapture("widget-wide-4x3-light", false, 2, 330, 190);
        renderAndCapture("widget-wide-4x3-dark", true, 2, 330, 190);
        renderAndCapture("widget-wide-4x2-light", false, 1, 330, 120);
        renderAndCapture("widget-wide-4x2-dark", true, 1, 330, 120);
    }

    private void renderAndCapture(String name, boolean dark, int maxRows,
                                  int widthDp, int heightDp) throws Exception {
        Context renderContext = dark ? darkContext(context) : context;
        RemoteViews views = TimetableWidgetWideProvider.buildViews(renderContext, maxRows, 1);
        assertNotNull(views);
        FrameLayout parent = new FrameLayout(renderContext);
        View view = views.apply(renderContext, parent);
        assertNotNull(view);
        int width = Math.round(widthDp * renderContext.getResources().getDisplayMetrics().density);
        int height = Math.round(heightDp * renderContext.getResources().getDisplayMetrics().density);
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, height);

        ViewGroup left = view.findViewById(R.id.widgetWideLeftRows);
        ViewGroup right = view.findViewById(R.id.widgetWideRightRows);
        assertNotNull(left);
        assertNotNull(right);
        assertTrue("左栏行数超过尺寸上限", left.getChildCount() <= maxRows);
        assertEquals("右栏行数不符合尺寸", Math.min(maxRows, 2), right.getChildCount());

        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(dark ? Color.rgb(13, 20, 17) : Color.rgb(245, 246, 250));
        view.draw(canvas);
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

    private static Context darkContext(Context base) {
        Configuration config = new Configuration(base.getResources().getConfiguration());
        config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                | Configuration.UI_MODE_NIGHT_YES;
        return base.createConfigurationContext(config);
    }

    private void seed() {
        LocalDate today = LocalDate.now();
        StudyTimetableStore.saveSemesterStart(context,
                SemesterCalendar.mondayOf(today).minusWeeks(10));
        StudyTimetableStore.saveTotalWeeks(context, 20);
        int todayWeekday = today.getDayOfWeek().getValue();
        int laterWeekday = today.plusDays(3).getDayOfWeek().getValue();
        int lastWeekday = today.plusDays(5).getDayOfWeek().getValue();

        List<TimetableCourse> courses = new ArrayList<>();
        add(courses, "高等数学", "A101", todayWeekday, 1, 2);
        add(courses, "大学英语", "B202", todayWeekday, 3, 4);
        add(courses, "数据结构", "C303", todayWeekday, 5, 6);
        add(courses, "体育", "操场", todayWeekday, 7, 8);
        add(courses, "操作系统A", "教3楼302", laterWeekday, 3, 4);
        add(courses, "移动互联网应用开发技术", "学10楼309", laterWeekday, 5, 6);
        add(courses, "概率论", "B101", lastWeekday, 1, 2);
        add(courses, "马克思主义", "D202", lastWeekday, 3, 4);
        StudyTimetableStore.saveCourses(context, courses);
    }

    private static void add(List<TimetableCourse> courses, String name, String place,
                            int weekday, int start, int end) {
        courses.add(TimetableCourse.create(name, "", place, weekday, start, end, "1-20"));
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
