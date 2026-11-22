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

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * 作业截止提醒的广播入口：闹钟触发时补发窗口内全部到期提醒并续排下一个闹钟；
 * 开机、时间/时区变化或覆盖安装后按存储的作业重新排期。
 */
public class HomeworkReminderReceiver extends BroadcastReceiver {

    public static final String ACTION_REMINDER = "org.localroute.app.action.HOMEWORK_REMINDER";
    public static final String EXTRA_TRIGGER_MILLIS = "homework_trigger_millis";

    private static final String CHANNEL_ID = "homework_reminder";
    private static final int NOTIFICATION_ID_BASE = 31000;

    @Override
    public void onReceive(@NonNull Context context, @NonNull Intent intent) {
        String action = intent.getAction();
        if (ACTION_REMINDER.equals(action)) {
            handleFired(context, intent.getLongExtra(EXTRA_TRIGGER_MILLIS, 0L));
        } else {
            // BOOT_COMPLETED / TIME_SET / TIMEZONE_CHANGED / MY_PACKAGE_REPLACED：
            // 重启或改表后闹钟会丢失，按存储的作业整体重排。
            HomeworkReminderScheduler.update(context);
        }
    }

    private void handleFired(@NonNull Context context, long triggerMillis) {
        Context app = context.getApplicationContext();
        if (!CustomHomeworkStore.loadReminderEnabled(app)) {
            return;
        }
        LocalDateTime trigger = triggerMillis > 0
                ? LocalDateTime.ofInstant(Instant.ofEpochMilli(triggerMillis),
                        ZoneId.systemDefault())
                : LocalDateTime.now();
        LocalDateTime windowLeft = trigger.minusSeconds(
                HomeworkReminderScheduler.FIRE_WINDOW_BEFORE_SECONDS);
        LocalDateTime windowRight = trigger.plusMinutes(
                HomeworkReminderScheduler.FIRE_WINDOW_AFTER_MINUTES);
        List<HomeworkReminderPlanner.Reminder> due = HomeworkReminderPlanner.dueBetween(
                CustomHomeworkStore.load(app), windowLeft, windowRight);
        ensureChannel(app);
        NotificationManagerCompat manager = NotificationManagerCompat.from(app);
        for (HomeworkReminderPlanner.Reminder reminder : due) {
            notify(manager, app, reminder);
        }
        HomeworkReminderScheduler.update(app, windowRight);
    }

    private void notify(@NonNull NotificationManagerCompat manager, @NonNull Context context,
                        @NonNull HomeworkReminderPlanner.Reminder reminder) {
        int minutesToDeadline = (int) Math.max(0, Duration
                .between(reminder.remindAt,
                        HomeworkReminderPlanner.ofEpochMillis(
                                reminder.homework.deadlineEpochMillis()))
                .toMinutes());
        String title = context.getString(R.string.study_homework_reminder_notification_title,
                minutesToDeadline, reminder.homework.title());
        String body = context.getString(R.string.study_homework_reminder_notification_body,
                reminder.homework.title(), reminder.homework.formatDeadline());
        Intent open = new Intent(context, StudyActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(context,
                notificationId(reminder), open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder builder =
                new NotificationCompat.Builder(context, CHANNEL_ID)
                        .setSmallIcon(R.drawable.ic_study_homework)
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

    private int notificationId(@NonNull HomeworkReminderPlanner.Reminder reminder) {
        return NOTIFICATION_ID_BASE
                + Math.abs(reminder.homework.id().hashCode() % 1000);
    }

    private void ensureChannel(@NonNull Context context) {
        NotificationManager manager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                context.getString(R.string.study_homework_reminder_channel_name),
                NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription(
                context.getString(R.string.study_homework_reminder_channel_desc));
        manager.createNotificationChannel(channel);
    }
}
