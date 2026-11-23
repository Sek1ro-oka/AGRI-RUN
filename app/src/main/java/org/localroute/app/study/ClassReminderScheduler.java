package org.localroute.app.study;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 课前提醒的闹钟调度：任何时刻只保留「下一个」提醒闹钟，触发后由
 * {@link ClassReminderReceiver} 补发到期通知并滚动续排。
 *
 * <p>Android 12+ 无精确闹钟授权时退回 {@code setAndAllowWhileIdle}，
 * 提醒可能晚几分钟，但不会被 Doze 无限推迟。
 */
public final class ClassReminderScheduler {

    /** 闹钟早触发时，receiver 仍会补发 trigger ± 该窗口内的全部到期提醒。 */
    static final long FIRE_WINDOW_BEFORE_SECONDS = 120;
    static final long FIRE_WINDOW_AFTER_MINUTES = 10;

    /** 只排一个闹钟，request code 固定，改设置即覆盖旧闹钟。 */
    private static final int ALARM_REQUEST_CODE = 0x524E;

    private ClassReminderScheduler() {
    }

    /** 按当前设置重排闹钟；关闭开关或近期无课（含未来）时取消。 */
    public static void update(@NonNull Context context) {
        update(context, null);
    }

    /**
     * @param skipRemindersBefore 已被本次触发处理掉的提醒上界（不含）；
     *                            普通重排传 null
     */
    public static void update(@NonNull Context context,
                              @Nullable LocalDateTime skipRemindersBefore) {
        Context app = context.getApplicationContext();
        AlarmManager manager = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        if (manager == null) {
            return;
        }
        PendingIntent cancelHandle = alarmIntent(app, 0L);
        if (!StudyTimetableStore.loadClassReminderEnabled(app)) {
            manager.cancel(cancelHandle);
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        ClassReminderPlanner.Reminder next = null;
        for (ClassReminderPlanner.Reminder reminder : ClassReminderPlanner.upcoming(
                StudyTimetableStore.loadCourses(app),
                StudyTimetableStore.loadSchedule(app),
                StudyTimetableStore.loadSemesterStart(app),
                StudyTimetableStore.loadTotalWeeks(app),
                now,
                StudyTimetableStore.loadClassReminderLeadMinutes(app),
                ClassReminderPlanner.DEFAULT_HORIZON_DAYS,
                StudyTimetableStore.loadAdjustments(app))) {
            if (skipRemindersBefore == null
                    || !reminder.remindAt.isBefore(skipRemindersBefore)) {
                next = reminder;
                break;
            }
        }
        if (next == null) {
            manager.cancel(cancelHandle);
            return;
        }
        long triggerMillis = next.remindAt.atZone(ZoneId.systemDefault())
                .toInstant().toEpochMilli();
        PendingIntent pendingIntent = alarmIntent(app, triggerMillis);
        if (canScheduleExact(manager)) {
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMillis,
                    pendingIntent);
        } else {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMillis,
                    pendingIntent);
        }
    }

    @NonNull
    public static PendingIntent alarmIntent(@NonNull Context context, long triggerMillis) {
        Intent intent = new Intent(context, ClassReminderReceiver.class);
        intent.setAction(ClassReminderReceiver.ACTION_REMINDER);
        intent.putExtra(ClassReminderReceiver.EXTRA_TRIGGER_MILLIS, triggerMillis);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(context, ALARM_REQUEST_CODE, intent, flags);
    }

    private static boolean canScheduleExact(@NonNull AlarmManager manager) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms();
    }
}
