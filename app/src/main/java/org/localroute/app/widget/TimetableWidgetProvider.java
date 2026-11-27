package org.localroute.app.widget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.RemoteViews;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import org.localroute.app.R;
import org.localroute.app.entry.HomeTarget;
import org.localroute.app.study.StudyTimetableStore;
import org.localroute.app.study.TimetableCourse;
import org.localroute.app.ui.StudyActivity;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 桌面课表小组件：今天几节课、正在上哪节、下一节还有多久。
 *
 * <p>组件只能使用 {@link RemoteViews}，所以这里只负责把 {@link TimetableWidgetPlan} 的状态翻译成
 * 文案与行；所有「今天有什么课」的判断都留在纯 Java 的 plan 里，由 JVM 用例覆盖。
 *
 * <p>尺寸自适应：宿主上报的可视高度换算成行数（{@link TimetableWidgetPlan#maxRows(int)}），
 * 一个组件从 2×2 拉到 4×3 都能用，不必为每种尺寸各写一个 provider。
 *
 * <p>刷新时机：系统按 {@code updatePeriodMillis} 定时广播；课表、作息、开学日期一被写入，
 * {@link TimetableWidgetSync} 立刻主动刷新；跨天时由 {@code android.intent.action.DATE_CHANGED}
 * 触发。清单里只声明 {@code APPWIDGET_UPDATE}，其余组件广播由系统按同一声明自动路由。
 */
public final class TimetableWidgetProvider extends AppWidgetProvider {

    /** 宿主没上报高度时按这个行数展示（约 2×2）。 */
    private static final int DEFAULT_ROWS = 3;

    /** 跨天、改时钟、改时区都要重算「今天有什么课」；组件自己的定时刷新最密也只有半小时。 */
    @Override
    public void onReceive(@NonNull Context context, @NonNull Intent intent) {
        super.onReceive(context, intent);
        String action = intent.getAction();
        if (Intent.ACTION_DATE_CHANGED.equals(action)
                || Intent.ACTION_TIME_CHANGED.equals(action)
                || Intent.ACTION_TIMEZONE_CHANGED.equals(action)) {
            updateAll(context);
        }
    }

    @Override
    public void onUpdate(@NonNull Context context, @NonNull AppWidgetManager appWidgetManager,
                         @NonNull int[] appWidgetIds) {
        render(context, appWidgetManager, appWidgetIds);
    }

    @Override
    public void onAppWidgetOptionsChanged(@NonNull Context context,
                                          @NonNull AppWidgetManager appWidgetManager,
                                          int appWidgetId, @Nullable Bundle newOptions) {
        render(context, appWidgetManager, new int[] {appWidgetId});
    }

    @Override
    public void onEnabled(@NonNull Context context) {
        // 第一个实例落地时把「数据变了就刷新」的监听挂上，之后所有写入都不用各自通知组件。
        TimetableWidgetSync.attach(context);
    }

    @Override
    public void onDisabled(@NonNull Context context) {
        // 最后一个实例被删掉，就不再需要为课表写入重画组件。
        TimetableWidgetSync.detachIfUnused(context);
    }

    /** 应用内主动刷新所有实例：改完课表、换完作息、跨天都要走这里。 */
    public static void updateAll(@NonNull Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(
                new ComponentName(context, TimetableWidgetProvider.class));
        render(context, manager, ids);
    }

    /** 桌面上有没有小组件，用于设置页提示。 */
    public static boolean hasInstances(@NonNull Context context) {
        return AppWidgetManager.getInstance(context).getAppWidgetIds(
                new ComponentName(context, TimetableWidgetProvider.class)).length > 0;
    }

    private static void render(@NonNull Context context, @NonNull AppWidgetManager manager,
                               @NonNull int[] appWidgetIds) {
        for (int appWidgetId : appWidgetIds) {
            int rows = rowsFor(manager.getAppWidgetOptions(appWidgetId));
            manager.updateAppWidget(appWidgetId,
                    buildViews(context, rows, appWidgetId));
        }
    }

    /** 用宿主上报的可视最小高度折算行数，保证缩到最小时列出的内容也真的看得见。 */
    private static int rowsFor(@Nullable Bundle options) {
        int heightDp = options == null ? 0
                : options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0);
        return heightDp <= 0 ? DEFAULT_ROWS : TimetableWidgetPlan.maxRows(heightDp);
    }

    private static RemoteViews buildViews(@NonNull Context context, int maxRows, int appWidgetId) {
        List<TimetableCourse> courses = StudyTimetableStore.loadCourses(context);
        LocalTime now = LocalTime.now();
        TimetableWidgetPlan plan = TimetableWidgetPlan.of(courses,
                StudyTimetableStore.loadSchedule(context),
                StudyTimetableStore.loadSemesterStart(context),
                StudyTimetableStore.loadTotalWeeks(context),
                LocalDate.now(), now.getHour() * 60 + now.getMinute(),
                StudyTimetableStore.loadAdjustments(context));

        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_timetable);
        views.setTextViewText(R.id.widgetTimetableTitle, context.getString(
                R.string.widget_timetable_title, weekLabel(context, plan)));
        views.setTextViewText(R.id.widgetTimetableStatus, statusText(context, plan));
        views.setTextViewText(R.id.widgetTimetableFooter, footerText(context, plan));
        views.removeAllViews(R.id.widgetTimetableRows);

        int currentIndex = plan.currentIndex();
        List<TimetableWidgetPlan.Slot> rows = plan.rows(maxRows);
        for (int i = 0; i < rows.size(); i++) {
            TimetableWidgetPlan.Slot slot = rows.get(i);
            boolean current = currentIndex >= 0 && slot == plan.current();
            views.addView(R.id.widgetTimetableRows, rowViews(context, slot, current));
        }
        views.setOnClickPendingIntent(R.id.widgetTimetableRoot, openTimetable(context, appWidgetId));
        return views;
    }

    /**
     * 一行一节课。
     *
     * <p>时间与教室拼在同一条文本里，而不是排成三列：2×2 时可用宽度只有 110dp 左右，
     * 分列后课程名会被挤到只剩两三个字，一整行 ellipsize 反而读得完。
     */
    private static RemoteViews rowViews(@NonNull Context context,
                                        TimetableWidgetPlan.Slot slot, boolean current) {
        RemoteViews row = new RemoteViews(context.getPackageName(),
                R.layout.widget_timetable_row);
        TimetableCourse course = slot.course();
        String name = course.name().isEmpty()
                ? context.getString(R.string.widget_timetable_unnamed_course) : course.name();
        String line = course.location().isEmpty()
                ? slot.timeLabel() + " " + name
                : context.getString(R.string.widget_timetable_row_with_place,
                        slot.timeLabel(), name, course.location());
        row.setTextViewText(R.id.widgetTimetableRow, line);
        row.setTextColor(R.id.widgetTimetableRow, ContextCompat.getColor(context,
                current ? R.color.campus_mint_dark : R.color.campus_ink));
        return row;
    }

    private static String weekLabel(@NonNull Context context, TimetableWidgetPlan plan) {
        if (!plan.weekKnown()) {
            return context.getString(R.string.widget_timetable_week_unknown);
        }
        if (plan.teachingWeek() < 1) {
            return context.getString(R.string.widget_timetable_week_off);
        }
        return context.getString(R.string.widget_timetable_week, plan.teachingWeek());
    }

    /**
     * 状态行只说「现在」这一件事：在上课就报剩余，在等就报倒计时和开课时间，上完或没课就报下一次。
     * 课程名与教室留给下面的行，两行挤在一起会都读不清。
     */
    private static String statusText(@NonNull Context context, TimetableWidgetPlan plan) {
        switch (plan.status()) {
            case NOT_IMPORTED:
                return context.getString(R.string.widget_timetable_status_empty);
            case IN_CLASS:
                return context.getString(R.string.widget_timetable_status_in_class,
                        TimetableWidgetPlan.durationLabel(plan.minutesRemaining()));
            case UPCOMING:
                return context.getString(R.string.widget_timetable_status_upcoming,
                        TimetableWidgetPlan.durationLabel(plan.minutesToNext()),
                        whenLabel(context, plan));
            case TODAY_DONE:
                return statusAboutNext(context, plan, R.string.widget_timetable_status_done);
            case TODAY_CLEAR:
            default:
                return statusAboutNext(context, plan, plan.closedToday()
                        ? R.string.widget_timetable_status_holiday
                        : R.string.widget_timetable_status_clear);
        }
    }

    /** 「今天没有课 · 下次 …」这一类文案；近期内真的没课时换成「未来 N 天也没有」。 */
    private static String statusAboutNext(@NonNull Context context, TimetableWidgetPlan plan,
                                     int formatRes) {
        if (plan.next() == null) {
            return context.getString(R.string.widget_timetable_next_none,
                    TimetableWidgetPlan.HORIZON_DAYS);
        }
        return context.getString(formatRes, whenLabel(context, plan));
    }

    /** 下一节落在哪天：今天/明天用口语说法，更远用「周四 14:00」这类星期说法。 */
    private static String whenLabel(@NonNull Context context, TimetableWidgetPlan.Slot slot) {
        long days = ChronoUnit.DAYS.between(LocalDate.now(), slot.day());
        if (days == 0) {
            return context.getString(R.string.widget_timetable_when_today, slot.timeLabel());
        }
        if (days == 1) {
            return context.getString(R.string.widget_timetable_when_tomorrow, slot.timeLabel());
        }
        return context.getString(R.string.widget_timetable_when_weekday,
                slot.course().weekdayLabel(), slot.timeLabel());
    }

    private static String whenLabel(@NonNull Context context, TimetableWidgetPlan plan) {
        return whenLabel(context, plan.next());
    }

    /** 底部一行留给「为什么是这个结果」和「点进去能干什么」。 */
    private static String footerText(@NonNull Context context, TimetableWidgetPlan plan) {
        if (plan.status() == TimetableWidgetPlan.Status.NOT_IMPORTED) {
            return context.getString(R.string.widget_timetable_footer_import);
        }
        if (!plan.weekKnown()) {
            return context.getString(R.string.widget_timetable_footer_week_unknown);
        }
        if (plan.teachingWeek() < 1) {
            return context.getString(R.string.widget_timetable_footer_week_off);
        }
        TimetableWidgetPlan.Slot next = plan.next();
        if (plan.status() == TimetableWidgetPlan.Status.IN_CLASS && next != null) {
            return context.getString(R.string.widget_timetable_footer_next,
                    whenLabel(context, next), next.course().name());
        }
        return context.getString(R.string.widget_timetable_footer_open);
    }

    /**
     * 点组件打开课表页。
     *
     * <p>与 {@link HomeTarget#TIMETABLE} 用同一个 extra 和同一张页面：小组件的 PendingIntent 由本应用
     * 自己创建，所以能直接跳到不导出的课表页，不必像桌面长按图标那样先绕主页一次。
     */
    private static PendingIntent openTimetable(@NonNull Context context, int appWidgetId) {
        Intent intent = new Intent(context, StudyActivity.class)
                .putExtra(StudyActivity.EXTRA_TIMETABLE, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(context, appWidgetId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
