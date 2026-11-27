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
import org.localroute.app.study.StudyTimetableStore;
import org.localroute.app.study.TimetableCourse;
import org.localroute.app.ui.StudyActivity;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 双日课表小组件：左边今天未结束的课，右边下一个有课日；课程左侧是时间线。
 *
 * <p>与「今日课表」分组件的 provider 并列：旧的 2×2 保持原样，本组件只面向 4×2/4×3 宽版，
 * 由用户在桌面组件选择器里自选。所有「今天/下一个有课日」的判断在
 * {@link TimetableWidgetWidePlan} 里，这里只负责翻译成 RemoteViews。
 */
public final class TimetableWidgetWideProvider extends AppWidgetProvider {

    /** 宿主没上报高度时按 4×2 的一行展示。 */
    private static final int DEFAULT_ROWS = 1;

    /** 与完整课表一致的课程色；圆点用它保留课程辨识度。 */
    private static final int[] DOT_COLORS = {
            R.color.study_block_mint_text,
            R.color.study_block_blue_text,
            R.color.study_block_orange_text,
            R.color.study_block_violet_text,
            R.color.study_block_rose_text
    };

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
    public void onUpdate(@NonNull Context context, @NonNull AppWidgetManager manager,
                         @NonNull int[] appWidgetIds) {
        render(context, manager, appWidgetIds);
    }

    @Override
    public void onAppWidgetOptionsChanged(@NonNull Context context,
                                          @NonNull AppWidgetManager manager,
                                          int appWidgetId, @Nullable Bundle newOptions) {
        render(context, manager, new int[] {appWidgetId});
    }

    @Override
    public void onEnabled(@NonNull Context context) {
        TimetableWidgetSync.attach(context);
    }

    @Override
    public void onDisabled(@NonNull Context context) {
        TimetableWidgetSync.detachIfUnused(context);
    }

    public static void updateAll(@NonNull Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(
                new ComponentName(context, TimetableWidgetWideProvider.class));
        render(context, manager, ids);
    }

    public static boolean hasInstances(@NonNull Context context) {
        return AppWidgetManager.getInstance(context).getAppWidgetIds(
                new ComponentName(context, TimetableWidgetWideProvider.class)).length > 0;
    }

    private static void render(@NonNull Context context, @NonNull AppWidgetManager manager,
                               @NonNull int[] appWidgetIds) {
        for (int appWidgetId : appWidgetIds) {
            int rows = rowsFor(manager.getAppWidgetOptions(appWidgetId));
            manager.updateAppWidget(appWidgetId, buildViews(context, rows, appWidgetId));
        }
    }

    private static int rowsFor(@Nullable Bundle options) {
        int heightDp = options == null ? 0
                : options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0);
        return heightDp <= 0 ? DEFAULT_ROWS : TimetableWidgetWidePlan.maxRows(heightDp);
    }

    /** 包内可见，供仪器测试直接渲染 RemoteViews。 */
    @NonNull
    static RemoteViews buildViews(@NonNull Context context, int maxRows, int appWidgetId) {
        List<TimetableCourse> courses = StudyTimetableStore.loadCourses(context);
        LocalTime now = LocalTime.now();
        TimetableWidgetWidePlan plan = TimetableWidgetWidePlan.of(courses,
                StudyTimetableStore.loadSchedule(context),
                StudyTimetableStore.loadSemesterStart(context),
                StudyTimetableStore.loadTotalWeeks(context),
                LocalDate.now(), now.getHour() * 60 + now.getMinute(),
                StudyTimetableStore.loadAdjustments(context));

        RemoteViews views = new RemoteViews(context.getPackageName(),
                R.layout.widget_timetable_wide);
        views.setOnClickPendingIntent(R.id.widgetWideRoot, openTimetable(context, appWidgetId));
        if (plan.notImported()) {
            views.setViewVisibility(R.id.widgetWideColumns, android.view.View.GONE);
            views.setViewVisibility(R.id.widgetWideEmpty, android.view.View.VISIBLE);
            views.setTextViewText(R.id.widgetWideEmpty,
                    context.getString(R.string.widget_timetable_wide_not_imported));
            return views;
        }

        views.setViewVisibility(R.id.widgetWideColumns, android.view.View.VISIBLE);
        views.setViewVisibility(R.id.widgetWideEmpty, android.view.View.GONE);
        bindToday(context, views, plan, maxRows);
        bindNextDay(context, views, plan, maxRows);
        return views;
    }

    private static void bindToday(@NonNull Context context, @NonNull RemoteViews views,
                                  @NonNull TimetableWidgetWidePlan plan, int maxRows) {
        views.setTextViewText(R.id.widgetWideDate,
                String.valueOf(plan.today().getDayOfMonth()));
        views.setTextViewText(R.id.widgetWideWeek, weekText(context, plan));
        views.setTextViewText(R.id.widgetWideWeekday,
                context.getString(R.string.widget_timetable_wide_weekday,
                        weekdayLabel(context, plan.today())));

        int statusRes;
        boolean active = false;
        if (plan.weekKnown() && plan.teachingWeek() < 1) {
            statusRes = R.string.widget_timetable_wide_today_empty;
        } else if (plan.todayClosed()) {
            statusRes = R.string.widget_timetable_wide_today_holiday;
        } else if (plan.todayTotal() == 0) {
            statusRes = R.string.widget_timetable_wide_today_empty;
        } else if (plan.todayUpcoming().isEmpty()) {
            views.setTextViewText(R.id.widgetWideStatus,
                    context.getString(R.string.widget_timetable_wide_today_done,
                            plan.todayFinished()));
            views.setTextColor(R.id.widgetWideStatus, ContextCompat.getColor(context,
                    R.color.campus_muted));
            addTodayRows(context, views, plan, maxRows);
            return;
        } else if (plan.todayFinished() > 0) {
            views.setTextViewText(R.id.widgetWideStatus,
                    context.getString(R.string.widget_timetable_wide_today_counts,
                            plan.todayFinished(), plan.todayUpcoming().size()));
            active = true;
            statusRes = 0;
        } else {
            views.setTextViewText(R.id.widgetWideStatus,
                    context.getString(R.string.widget_timetable_wide_today_remaining,
                            plan.todayUpcoming().size()));
            active = true;
            statusRes = 0;
        }
        if (statusRes != 0) {
            views.setTextViewText(R.id.widgetWideStatus, context.getString(statusRes));
        }
        views.setTextColor(R.id.widgetWideStatus, ContextCompat.getColor(context,
                active ? R.color.campus_mint_dark : R.color.campus_muted));
        addTodayRows(context, views, plan, maxRows);
    }

    private static void addTodayRows(@NonNull Context context, @NonNull RemoteViews views,
                                     @NonNull TimetableWidgetWidePlan plan, int maxRows) {
        views.removeAllViews(R.id.widgetWideLeftRows);
        List<TimetableWidgetWidePlan.Slot> rows = plan.todayRows(maxRows);
        for (TimetableWidgetWidePlan.Slot slot : rows) {
            views.addView(R.id.widgetWideLeftRows, rowViews(context, slot));
        }
        int hidden = plan.todayHidden(maxRows);
        if (hidden > 0) {
            views.setViewVisibility(R.id.widgetWideLeftFoot, android.view.View.VISIBLE);
            views.setTextViewText(R.id.widgetWideLeftFoot,
                    context.getString(R.string.widget_timetable_wide_other, hidden));
        } else {
            views.setViewVisibility(R.id.widgetWideLeftFoot, android.view.View.GONE);
        }
    }

    private static void bindNextDay(@NonNull Context context, @NonNull RemoteViews views,
                                    @NonNull TimetableWidgetWidePlan plan, int maxRows) {
        TimetableWidgetWidePlan.Day next = plan.nextDay();
        if (next == null) {
            views.setTextViewText(R.id.widgetWideNextDate, context.getString(
                    R.string.widget_timetable_wide_no_next,
                    TimetableWidgetWidePlan.HORIZON_DAYS));
            views.setTextViewText(R.id.widgetWideNextCount, "");
            views.removeAllViews(R.id.widgetWideRightRows);
            views.setViewVisibility(R.id.widgetWideRightFoot, android.view.View.GONE);
            return;
        }
        views.setTextViewText(R.id.widgetWideNextDate,
                nextDayLabel(context, plan.today(), next.date()));
        views.setTextViewText(R.id.widgetWideNextCount,
                context.getString(R.string.widget_timetable_wide_next_count, next.total()));
        views.removeAllViews(R.id.widgetWideRightRows);
        for (TimetableWidgetWidePlan.Slot slot : plan.nextRows(next, maxRows)) {
            views.addView(R.id.widgetWideRightRows, rowViews(context, slot));
        }
        int hidden = plan.nextHidden(next, maxRows);
        views.setViewVisibility(R.id.widgetWideRightFoot, android.view.View.VISIBLE);
        views.setTextViewText(R.id.widgetWideRightFoot, hidden > 0
                ? context.getString(R.string.widget_timetable_wide_other, hidden)
                : context.getString(R.string.widget_timetable_wide_open));
    }

    @NonNull
    private static RemoteViews rowViews(@NonNull Context context,
                                        @NonNull TimetableWidgetWidePlan.Slot slot) {
        RemoteViews row = new RemoteViews(context.getPackageName(),
                R.layout.widget_timetable_wide_row);
        row.setTextViewText(R.id.widgetWideRowStart, slot.startLabel());
        row.setTextViewText(R.id.widgetWideRowEnd, slot.endLabel());
        String name = slot.course().name().isEmpty()
                ? context.getString(R.string.widget_timetable_unnamed_course)
                : slot.course().name();
        row.setTextViewText(R.id.widgetWideRowName, name);
        String place = slot.course().location();
        row.setTextViewText(R.id.widgetWideRowPlace, place);
        row.setViewVisibility(R.id.widgetWideRowPlace,
                place.isEmpty() ? android.view.View.GONE : android.view.View.VISIBLE);
        int dotColor = ContextCompat.getColor(context,
                DOT_COLORS[Math.floorMod(slot.course().id().hashCode(), DOT_COLORS.length)]);
        row.setTextColor(R.id.widgetWideRowDot, dotColor);
        int strong = ContextCompat.getColor(context,
                slot.live() ? R.color.campus_mint_dark : R.color.campus_ink);
        row.setTextColor(R.id.widgetWideRowStart, strong);
        row.setTextColor(R.id.widgetWideRowName, strong);
        return row;
    }

    @NonNull
    private static String weekText(@NonNull Context context,
                                   @NonNull TimetableWidgetWidePlan plan) {
        if (!plan.weekKnown()) {
            return context.getString(R.string.widget_timetable_wide_week_unknown);
        }
        if (plan.teachingWeek() < 1) {
            return context.getString(R.string.widget_timetable_wide_week_off);
        }
        return context.getString(R.string.widget_timetable_wide_week, plan.teachingWeek());
    }

    @NonNull
    private static String weekdayLabel(@NonNull Context context, @NonNull LocalDate date) {
        String[] labels = context.getResources().getStringArray(R.array.home_weekday_labels);
        return labels[date.getDayOfWeek().getValue() - 1];
    }

    @NonNull
    private static String nextDayLabel(@NonNull Context context, @NonNull LocalDate today,
                                       @NonNull LocalDate next) {
        String weekday = weekdayLabel(context, next);
        long days = ChronoUnit.DAYS.between(today, next);
        if (days == 1) {
            return context.getString(R.string.widget_timetable_wide_next_tomorrow,
                    weekday, next.getDayOfMonth());
        }
        if (next.getYear() == today.getYear()) {
            return context.getString(R.string.widget_timetable_wide_next_date,
                    next.getMonthValue(), next.getDayOfMonth(), weekday);
        }
        return context.getString(R.string.widget_timetable_wide_next_date_year,
                next.getYear(), next.getMonthValue(), next.getDayOfMonth(), weekday);
    }

    private static PendingIntent openTimetable(@NonNull Context context, int appWidgetId) {
        Intent intent = new Intent(context, StudyActivity.class)
                .putExtra(StudyActivity.EXTRA_TIMETABLE, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(context, appWidgetId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
