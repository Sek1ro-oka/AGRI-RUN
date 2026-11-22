package org.localroute.app.study;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 作业截止提醒的计划逻辑：每条自建作业只有一个提醒点
 * （截止时间 − 提前分钟数），落在未来时间窗内的才会被安排。
 *
 * <p>纯 Java，不依赖 Android，可在 JVM 单元测试中直接验证。
 */
public final class HomeworkReminderPlanner {

    /** 一次向后安排的日历天数；闹钟触发后由接收器滚动续排。 */
    public static final int DEFAULT_HORIZON_DAYS = 30;

    private HomeworkReminderPlanner() {
    }

    /** 一条作业截止提醒：提醒时刻与对应作业。 */
    public static final class Reminder {

        @NonNull
        public final LocalDateTime remindAt;
        @NonNull
        public final CustomHomework homework;

        Reminder(@NonNull LocalDateTime remindAt, @NonNull CustomHomework homework) {
            this.remindAt = remindAt;
            this.homework = homework;
        }
    }

    @NonNull
    public static LocalDateTime ofEpochMillis(long epochMillis) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis),
                ZoneId.systemDefault());
    }

    /** {@code now} 之后 horizonDays 天内的全部提醒点，按时间升序。 */
    @NonNull
    public static List<Reminder> upcoming(@NonNull List<CustomHomework> items,
                                          @NonNull LocalDateTime now,
                                          int horizonDays) {
        List<Reminder> result = new ArrayList<>();
        if (horizonDays < 1) {
            return result;
        }
        LocalDateTime limit = now.plusDays(horizonDays);
        for (CustomHomework item : items) {
            long millis = item.remindEpochMillis();
            if (millis <= 0) {
                continue;
            }
            LocalDateTime remindAt = ofEpochMillis(millis);
            if (remindAt.isAfter(now) && remindAt.isBefore(limit)) {
                result.add(new Reminder(remindAt, item));
            }
        }
        result.sort(Comparator.comparing(reminder -> reminder.remindAt));
        return result;
    }

    /** 下一个提醒点；没有时返回 null。 */
    @Nullable
    public static Reminder next(@NonNull List<CustomHomework> items,
                                @NonNull LocalDateTime now,
                                int horizonDays) {
        List<Reminder> upcoming = upcoming(items, now, horizonDays);
        return upcoming.isEmpty() ? null : upcoming.get(0);
    }

    /** 提醒时刻落在 [from, to) 区间内的提醒点，用于闹钟触发后补发全部到期项。 */
    @NonNull
    public static List<Reminder> dueBetween(@NonNull List<CustomHomework> items,
                                            @NonNull LocalDateTime from,
                                            @NonNull LocalDateTime to) {
        List<Reminder> due = new ArrayList<>();
        for (CustomHomework item : items) {
            long millis = item.remindEpochMillis();
            if (millis <= 0) {
                continue;
            }
            LocalDateTime remindAt = ofEpochMillis(millis);
            if (!remindAt.isBefore(from) && remindAt.isBefore(to)) {
                due.add(new Reminder(remindAt, item));
            }
        }
        due.sort(Comparator.comparing(reminder -> reminder.remindAt));
        return due;
    }
}
