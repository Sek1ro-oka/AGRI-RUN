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
 * 作业截止提醒的闹钟调度：与课前提醒一样任何时刻只保留「下一个」提醒闹钟，
 * 触发后由 {@link HomeworkReminderReceiver} 补发到期通知并滚动续排。
 *
 * <p>Android 12+ 无精确闹钟授权时退回 {@code setAndAllowWhileIdle}，
 * 提醒可能晚几分钟，但不会被 Doze 无限推迟。
 */
public final class HomeworkReminderScheduler {

    /** 闹钟早触发时，receiver 仍会补发 trigger ± 该窗口内的全部到期提醒。 */
    static final long FIRE_WINDOW_BEFORE_SECONDS = 120;
    static final long FIRE_WINDOW_AFTER_MINUTES = 10;

    /** 只排一个闹钟，request code 固定，改设置或改作业即覆盖旧闹钟。 */
    private static final int ALARM_REQUEST_CODE = 0x4857;

    private HomeworkReminderScheduler() {
    }

    /** 按当前存储重排闹钟；关闭开关或近期无提醒点时取消。 */
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
        if (!CustomHomeworkStore.loadReminderEnabled(app)) {
            manager.cancel(cancelHandle);
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        int leadMinutes = StudyTimetableStore.loadHomeworkReminderLeadMinutes(app);
        HomeworkReminderPlanner.Reminder next = null;
        for (HomeworkReminderPlanner.Reminder reminder : HomeworkReminderPlanner.upcoming(
                CustomHomeworkStore.load(app), now, leadMinutes,
                HomeworkReminderPlanner.DEFAULT_HORIZON_DAYS)) {
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
        Intent intent = new Intent(context, HomeworkReminderReceiver.class);
        intent.setAction(HomeworkReminderReceiver.ACTION_REMINDER);
        intent.putExtra(HomeworkReminderReceiver.EXTRA_TRIGGER_MILLIS, triggerMillis);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(context, ALARM_REQUEST_CODE, intent, flags);
    }

    private static boolean canScheduleExact(@NonNull AlarmManager manager) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms();
    }
}
