package org.localroute.app.study;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 「这台手机到底会为什么事响」的一张总表。
 *
 * <p>提醒散在两处（课前提醒、作业截止提醒），每一处只显示自己的下一次；此外还有两件事不会
 * 发通知却会改上课安排：调课与节假日、按月推断的作息换季。用户真正想问的是一句话——
 * 「刚才那节为什么没提醒我」：是没开开关、没设开学日期、课表是空的，还是那天被停掉了。
 * 集中列一次就能一眼看完。
 *
 * <p>纯 Java：输入是各功能已经算好的数字与时间点，输出按「越可能马上响的越靠前」排好，
 * 文案由界面按 {@link Kind} 与 {@link Status} 取字符串资源。所以排序、缺席、关闭这些
 * 规则都能用 JVM 用例钉住，界面只剩拼字符串。
 */
public final class ReminderCenter {

    /** 中心里会出现的每一类。新增提醒功能就加一项，界面按这个枚举取文案。 */
    public enum Kind {
        /** 课前提醒（闹钟）。 */
        CLASS,
        /** 作业截止提醒（闹钟）。 */
        HOMEWORK,
        /** 调课与节假日：不会响，但会改上课安排。 */
        ADJUSTMENT,
        /** 作息换季：不会响，但所有钟点都会跟着变。 */
        SEASON,
        /** 版本更新：本应用不自动检查，只在手动点的时候才说。 */
        UPDATE
    }

    /** 界面取文案用的情形，避免在 Activity 里散一堆 if。 */
    public enum Status {
        /** 没开：不会通知。 */
        OFF,
        /** 会响，并且算出了下一次。 */
        WILL_FIRE,
        /** 未来有调课/节假日安排。 */
        SCHEDULE_AHEAD,
        /** 还没到下一次换季。 */
        WILL_CHANGE,
        /** 作息被手动钉住了，不会自己变。 */
        PINNED_SEASON,
        /** 这件事压根没有开关，只在用户自己点的时候才发生（更新检查）。 */
        MANUAL_ONLY,
        /** 开着但近期没有可提醒的内容。 */
        NOTHING_PENDING
    }

    /**
     * 一条汇总结果。
     *
     * @param enabled      这件事会不会主动起作用（通知或改安排）
     * @param nextAt       下一次响的钟点；不响或近期没待办时为 {@code null}
     * @param nextDate     最近一个有安排的日子（调课、换季）；不适用时为 {@code null}
     * @param pendingCount 相关条数：几门课、几条未交作业、几条例外，用来解释「为什么没有下一次」
     */
    public static final class Entry {

        @NonNull
        public final Kind kind;
        public final boolean enabled;
        @Nullable
        public final LocalDateTime nextAt;
        @Nullable
        public final LocalDate nextDate;
        public final int pendingCount;

        Entry(@NonNull Kind kind, boolean enabled, @Nullable LocalDateTime nextAt,
              @Nullable LocalDate nextDate, int pendingCount) {
            this.kind = kind;
            this.enabled = enabled;
            this.nextAt = nextAt;
            this.nextDate = nextDate;
            this.pendingCount = pendingCount;
        }
    }

    /**
     * 各功能喂进来的状态：只有数据、没有行为。
     *
     * <p>{@code nextClassReminder} 必须由调用方按开关与作息算好；没设开学日期时传 {@code null}
     * 并把 {@code semesterStartKnown} 置 false——那样即使开关开着，这一项也只说「先设开学日期」。
     */
    public static final class State {

        public boolean classEnabled;
        public boolean semesterStartKnown;
        @Nullable
        public LocalDateTime nextClassReminder;
        public int courseCount;
        public boolean homeworkEnabled;
        @Nullable
        public LocalDateTime nextHomeworkReminder;
        public int openHomeworkCount;
        public int adjustmentCount;
        @Nullable
        public LocalDate nextAdjustmentDate;
        /** 最近那条例外已经写成人话（由 {@link DayAdjustmentLabel} 生成），中心只负责排进去。 */
        @Nullable
        public String nextAdjustmentLabel;
        public boolean seasonManuallyPinned;
        @Nullable
        public LocalDate nextSeasonChange;

        /**
         * 课前提醒能不能真的响：开关开着还不够，没有开学日期就不知道周次，
         * 计划器算不出任何一天。
         */
        public boolean classCanFire() {
            return classEnabled && semesterStartKnown;
        }
    }

    private ReminderCenter() {
    }

    @NonNull
    public static List<Entry> entries(@NonNull State state) {
        List<Entry> entries = new ArrayList<>();
        entries.add(new Entry(Kind.CLASS, state.classCanFire(),
                state.classCanFire() ? state.nextClassReminder : null, null, state.courseCount));
        entries.add(new Entry(Kind.HOMEWORK, state.homeworkEnabled,
                state.homeworkEnabled ? state.nextHomeworkReminder : null, null,
                state.openHomeworkCount));
        entries.add(new Entry(Kind.ADJUSTMENT, state.adjustmentCount > 0, null,
                state.nextAdjustmentDate, state.adjustmentCount));
        entries.add(new Entry(Kind.SEASON, !state.seasonManuallyPinned, null,
                state.nextSeasonChange, 0));
        // 更新检查没有后台调度，这一项永远是「不会主动响」，写出来是为了不让人以为它会。
        entries.add(new Entry(Kind.UPDATE, false, null, null, 0));
        entries.sort(Comparator.comparingInt(ReminderCenter::rank)
                .thenComparing(ReminderCenter::sortMoment)
                .thenComparingInt(entry -> entry.kind.ordinal()));
        return entries;
    }

    @NonNull
    public static Status status(@NonNull Entry entry) {
        if (entry.nextAt != null && entry.enabled) {
            return Status.WILL_FIRE;
        }
        switch (entry.kind) {
            case SEASON:
                // 钉住了就是钉住了：没有「下一次」可说。
                return entry.enabled ? Status.WILL_CHANGE : Status.PINNED_SEASON;
            case UPDATE:
                return Status.MANUAL_ONLY;
            case ADJUSTMENT:
                // 调课没有开关，所以它永远不该说「没开」，只该说「近期没有安排」。
                return entry.nextDate != null ? Status.SCHEDULE_AHEAD : Status.NOTHING_PENDING;
            default:
                return entry.enabled ? Status.NOTHING_PENDING : Status.OFF;
        }
    }

    /** 会主动发通知的有几项（课前＋作业），写进行副标题用。 */
    public static int firingCount(@NonNull State state) {
        return (state.classCanFire() ? 1 : 0) + (state.homeworkEnabled ? 1 : 0);
    }

    /**
     * 时间点写成一句能读的中文：{@code 11月25日 07:50}。
     *
     * <p>不用 {@code DateTimeFormatter} 的 locale 模式，是因为各 ROM 的中文月份格式不统一
     * （有的写「11月25日 上午7:50」），提醒中心要跨 ROM 都读得一样。
     */
    @NonNull
    public static String momentLabel(@Nullable LocalDateTime moment) {
        if (moment == null) {
            return "";
        }
        return String.format(java.util.Locale.CHINA, "%d月%d日 %s",
                moment.getMonthValue(), moment.getDayOfMonth(),
                StudySchedule.formatMinutes(moment.getHour() * 60 + moment.getMinute()));
    }

    /** 日子写成 {@code 12月1日}；跨年时补上年份，免得 1 月被当成本年。 */
    @NonNull
    public static String dateLabel(@Nullable LocalDate date, @NonNull LocalDate today) {
        if (date == null) {
            return "";
        }
        String label = String.format(java.util.Locale.CHINA, "%d月%d日",
                date.getMonthValue(), date.getDayOfMonth());
        return date.getYear() == today.getYear() ? label : date.getYear() + "年" + label;
    }

    /** 已经算出下一次钟点的有几项。 */
    public static int scheduledCount(@NonNull List<Entry> entries) {
        int count = 0;
        for (Entry entry : entries) {
            if (entry.nextAt != null) {
                count++;
            }
        }
        return count;
    }

    /**
     * 有下一次钟点的排最前（越近越前），其余按固定次序 {@code CLASS → HOMEWORK →
     * ADJUSTMENT → SEASON → UPDATE} 跟着走。次序稳定，界面上的说明文字才对得上。
     */
    private static int rank(@NonNull Entry entry) {
        return entry.nextAt != null ? 0 : 1;
    }

    @NonNull
    private static LocalDateTime sortMoment(@NonNull Entry entry) {
        return entry.nextAt != null ? entry.nextAt : LocalDateTime.MAX;
    }
}
