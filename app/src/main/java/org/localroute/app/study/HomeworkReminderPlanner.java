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
 * （截止时间 − 全局提前分钟数），落在未来时间窗内的才会被安排。
 *
 * <p>提前量是全局设置，与课前提醒同一套做法：改一次设置即影响全部作业，
 * 用户不必逐条作业重复填。
 *
 * <p>纯 Java，不依赖 Android，可在 JVM 单元测试中直接验证。
 */
public final class HomeworkReminderPlanner {

    public static final int MIN_LEAD_MINUTES = 1;
    /** 最多提前 7 天提醒；再早的需求属于日历而不是本功能。 */
    public static final int MAX_LEAD_MINUTES = 7 * 24 * 60;
    public static final int DEFAULT_LEAD_MINUTES = 60;

    /** 一次向后安排的日历天数；闹钟触发后由接收器滚动续排。 */
    public static final int DEFAULT_HORIZON_DAYS = 30;

    private HomeworkReminderPlanner() {
    }

    /** 提前分钟数收进合法范围。 */
    public static int clampLead(int minutes) {
        return Math.max(MIN_LEAD_MINUTES, Math.min(MAX_LEAD_MINUTES, minutes));
    }

    /** 提前量的人话描述，只给时长本身：30 分钟 / 2 小时 / 1 天。 */
    @NonNull
    public static String leadLabel(int minutes) {
        int lead = clampLead(minutes);
        if (lead >= 60 && lead % 60 == 0) {
            int hours = lead / 60;
            return hours >= 24 && hours % 24 == 0 ? (hours / 24) + " 天" : hours + " 小时";
        }
        return lead + " 分钟";
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
                                          int leadMinutes,
                                          int horizonDays) {
        List<Reminder> result = new ArrayList<>();
        if (horizonDays < 1) {
            return result;
        }
        int lead = clampLead(leadMinutes);
        LocalDateTime limit = now.plusDays(horizonDays);
        for (CustomHomework item : items) {
            long millis = item.remindEpochMillis(lead);
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
                                int leadMinutes,
                                int horizonDays) {
        List<Reminder> upcoming = upcoming(items, now, leadMinutes, horizonDays);
        return upcoming.isEmpty() ? null : upcoming.get(0);
    }

    /** 提醒时刻落在 [from, to) 区间内的提醒点，用于闹钟触发后补发全部到期项。 */
    @NonNull
    public static List<Reminder> dueBetween(@NonNull List<CustomHomework> items,
                                            @NonNull LocalDateTime from,
                                            @NonNull LocalDateTime to,
                                            int leadMinutes) {
        List<Reminder> due = new ArrayList<>();
        int lead = clampLead(leadMinutes);
        for (CustomHomework item : items) {
            long millis = item.remindEpochMillis(lead);
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
