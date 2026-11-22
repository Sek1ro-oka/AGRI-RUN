package org.localroute.app.study;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import org.localroute.app.R;
import org.localroute.app.ui.StudyActivity;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * 课前提醒的广播入口：闹钟触发时补发窗口内全部到期提醒并续排下一个闹钟；
 * 开机、时间/时区变化后按当前设置重排。
 */
public class ClassReminderReceiver extends BroadcastReceiver {

    public static final String ACTION_REMINDER = "org.localroute.app.action.CLASS_REMINDER";
    public static final String EXTRA_TRIGGER_MILLIS = "trigger_millis";

    private static final String CHANNEL_ID = "class_reminder";
    private static final int NOTIFICATION_ID_BASE = 30000;

    @Override
    public void onReceive(@NonNull Context context, @NonNull Intent intent) {
        String action = intent.getAction();
        if (ACTION_REMINDER.equals(action)) {
            handleFired(context, intent.getLongExtra(EXTRA_TRIGGER_MILLIS, 0L));
        } else {
            // BOOT_COMPLETED / TIME_SET / TIMEZONE_CHANGED / MY_PACKAGE_REPLACED：
            // 重启或改表后闹钟会丢失，按存储的设置整体重排。
            ClassReminderScheduler.update(context);
        }
    }

    private void handleFired(@NonNull Context context, long triggerMillis) {
        Context app = context.getApplicationContext();
        if (!StudyTimetableStore.loadClassReminderEnabled(app)) {
            return;
        }
        LocalDateTime trigger = triggerMillis > 0
                ? LocalDateTime.ofInstant(Instant.ofEpochMilli(triggerMillis),
                        ZoneId.systemDefault())
                : LocalDateTime.now();
        LocalDateTime windowLeft = trigger.minusSeconds(
                ClassReminderScheduler.FIRE_WINDOW_BEFORE_SECONDS);
        LocalDateTime windowRight = trigger.plusMinutes(
                ClassReminderScheduler.FIRE_WINDOW_AFTER_MINUTES);
        List<ClassReminderPlanner.Reminder> due = ClassReminderPlanner.dueBetween(
                StudyTimetableStore.loadCourses(app),
                StudyTimetableStore.loadSchedule(app),
                StudyTimetableStore.loadSemesterStart(app),
                StudyTimetableStore.loadTotalWeeks(app),
                windowLeft, windowRight,
                StudyTimetableStore.loadClassReminderLeadMinutes(app));
        ensureChannel(app);
        NotificationManagerCompat manager = NotificationManagerCompat.from(app);
        for (ClassReminderPlanner.Reminder reminder : due) {
            notify(manager, app, reminder);
        }
        ClassReminderScheduler.update(app, windowRight);
    }

    private void notify(@NonNull NotificationManagerCompat manager, @NonNull Context context,
                        @NonNull ClassReminderPlanner.Reminder reminder) {
        int minutesToClass = (int) Math.max(0, java.time.Duration
                .between(reminder.remindAt,
                        reminder.day.atStartOfDay()
                                .plusMinutes(reminder.startMinutesOfDay))
                .toMinutes());
        String title = context.getString(R.string.study_reminder_notification_title,
                minutesToClass, reminder.course.name());
        String body = context.getString(R.string.study_reminder_notification_body,
                StudySchedule.formatMinutes(reminder.startMinutesOfDay),
                reminder.course.location().isEmpty()
                        ? context.getString(R.string.study_reminder_no_location)
                        : reminder.course.location(),
                reminder.course.periodLabel());
        Intent open = new Intent(context, StudyActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(context,
                notificationId(reminder), open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder builder =
                new NotificationCompat.Builder(context, CHANNEL_ID)
                        .setSmallIcon(R.drawable.ic_study_clock)
                        .setContentTitle(title)
                        .setContentText(body)
                        .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setCategory(NotificationCompat.CATEGORY_REMINDER)
                        .setAutoCancel(true)
                        .setContentIntent(contentIntent);
        try {
            manager.notify(notificationId(reminder), builder.build());
        } catch (SecurityException ignored) {
            // 未授予通知权限时静默跳过；权限检查入口在应用设置页。
        }
    }

    private int notificationId(@NonNull ClassReminderPlanner.Reminder reminder) {
        return NOTIFICATION_ID_BASE
                + Math.abs(reminder.course.id().hashCode() % 1000);
    }

    private void ensureChannel(@NonNull Context context) {
        NotificationManager manager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                context.getString(R.string.study_reminder_channel_name),
                NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription(context.getString(R.string.study_reminder_channel_desc));
        manager.createNotificationChannel(channel);
    }
}
