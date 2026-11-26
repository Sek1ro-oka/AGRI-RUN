package org.localroute.app.home;

import androidx.annotation.NonNull;

import org.localroute.app.study.HomeworkItem;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 待交作业清单：跨天按截止时间升序，只留待交，「未标注截止」的排最后。
 *
 * <p>与首页课程时间轴分开：课程是当天的时刻表，作业是「接下来要交什么」，两者视角不同。
 *
 * <p>纯 Java，措辞无关：只给「第几天截止 + HH:mm」这种中性值，中文标签由界面层映射，
 * 便于 JVM 逐条断言。
 */
public final class HomeworkDue {

    /** 默认看多远：一周。 */
    public static final int HORIZON_DAYS = 7;

    /** 一条待办：由界面层从学习通缓存与自建作业统一转过来。 */
    public static final class Task {
        private final String title;
        private final String course;
        private final long deadlineMillis;
        private final HomeworkItem.Status status;

        public Task(@NonNull String title, @NonNull String course,
                    long deadlineMillis, @NonNull HomeworkItem.Status status) {
            this.title = title;
            this.course = course;
            this.deadlineMillis = deadlineMillis;
            this.status = status;
        }

        @NonNull public String title() {
            return title;
        }

        @NonNull public String course() {
            return course;
        }

        /** 0 表示没解析到截止时刻。 */
        public long deadlineMillis() {
            return deadlineMillis;
        }

        @NonNull public HomeworkItem.Status status() {
            return status;
        }
    }

    /** 一条待交作业。 */
    public static final class Item {
        private final String title;
        private final String course;
        private final int dayOffset;
        private final String timeLabel;
        private final boolean undated;

        private Item(String title, String course, int dayOffset, String timeLabel,
                     boolean undated) {
            this.title = title;
            this.course = course;
            this.dayOffset = dayOffset;
            this.timeLabel = timeLabel;
            this.undated = undated;
        }

        @NonNull public String title() {
            return title;
        }

        @NonNull public String course() {
            return course;
        }

        /** 0 是今天，1 是明天；{@link #undated()} 为真时无意义。 */
        public int dayOffset() {
            return dayOffset;
        }

        /** {@code HH:mm}；没有时刻时为 {@code null}。 */
        public String timeLabel() {
            return timeLabel;
        }

        /** 没解析到截止时刻：排在最后，且不编时间。 */
        public boolean undated() {
            return undated;
        }
    }

    private HomeworkDue() {
    }

    /**
     * @param tasks    学习通缓存与自建作业合并后的待办
     * @param today    今天
     * @param horizon  往后看几天（含今天）
     */
    @NonNull
    public static List<Item> upcoming(@NonNull List<Task> tasks,
                                      @NonNull LocalDate today,
                                      int horizon) {
        ZoneId zone = ZoneId.systemDefault();
        List<Item> dated = new ArrayList<>();
        List<Item> undated = new ArrayList<>();
        for (Task task : tasks) {
            // 已交、已完成、已截止的都不该再占待交清单。
            if (task.status() != HomeworkItem.Status.PENDING) {
                continue;
            }
            if (task.deadlineMillis() <= 0L) {
                undated.add(new Item(task.title(), task.course(), -1, null, true));
                continue;
            }
            Instant instant = Instant.ofEpochMilli(task.deadlineMillis());
            LocalDate day = instant.atZone(zone).toLocalDate();
            long offset = day.toEpochDay() - today.toEpochDay();
            if (offset < 0 || offset >= horizon) {
                continue;
            }
            int minutes = instant.atZone(zone).getHour() * 60 + instant.atZone(zone).getMinute();
            dated.add(new Item(task.title(), task.course(), (int) offset,
                    String.format(java.util.Locale.US, "%02d:%02d", minutes / 60, minutes % 60),
                    false));
        }
        dated.sort(Comparator.comparingInt((Item item) -> item.dayOffset)
                .thenComparing(item -> item.timeLabel)
                .thenComparing(item -> item.title));
        dated.addAll(undated);
        return dated;
    }
}