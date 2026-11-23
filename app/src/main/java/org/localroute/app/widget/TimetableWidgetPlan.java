package org.localroute.app.widget;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.localroute.app.study.DayAdjustment;
import org.localroute.app.study.SemesterCalendar;
import org.localroute.app.study.StudySchedule;
import org.localroute.app.study.TimetableCourse;
import org.localroute.app.study.TimetableDay;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 桌面小组件要展示的「今天有什么课、下一节还剩多久」。
 *
 * <p>纯 Java，不依赖 Android 与 RemoteViews，可在 JVM 单元测试里逐条断言状态机：
 * 未导入课表、今天没课、正在上课、等下一节、今天上完，这五种覆盖了用户看到小组件时的全部处境。
 * 「下一节」会向未来找 {@link #HORIZON_DAYS} 天，今天上完后组件仍然能回答「下次什么时候有课」，
 * 而不是变成一句没有信息量的「今天没课」。
 *
 * <p>周次过滤依赖开学日期：没设置时不做周次过滤并把 {@link #weekKnown()} 标成 false，
 * 组件据此提示「按全部周次显示」，避免因为缺一个设置项而整块空白。
 */
public final class TimetableWidgetPlan {

    /** 今天上完课后，往后最多找几天，超出就当作「近期没课」。 */
    public static final int HORIZON_DAYS = 7;

    /** 小组件高度换算成可显示的课程行数时，标题与状态行占掉的 dp。 */
    private static final int CHROME_DP = 66;
    /** 一行课程约占的 dp。 */
    private static final int ROW_DP = 22;
    public static final int MIN_ROWS = 2;
    public static final int MAX_ROWS = 8;

    public enum Status {
        /** 一条课程都没有：先去导入或手动添加。 */
        NOT_IMPORTED,
        /** 今天（或本教学周的这一天）没有课。 */
        TODAY_CLEAR,
        /** 正在上课。 */
        IN_CLASS,
        /** 今天后面还有课，正在等下一节。 */
        UPCOMING,
        /** 今天的课已经全部上完。 */
        TODAY_DONE
    }

    /** 一节课在某天的具体时间段。 */
    public static final class Slot {

        private final TimetableCourse course;
        private final LocalDate day;
        private final int startMinutes;
        private final int endMinutes;

        Slot(TimetableCourse course, LocalDate day, int startMinutes, int endMinutes) {
            this.course = course;
            this.day = day;
            this.startMinutes = startMinutes;
            this.endMinutes = endMinutes;
        }

        @NonNull
        public TimetableCourse course() {
            return course;
        }

        @NonNull
        public LocalDate day() {
            return day;
        }

        public int startMinutes() {
            return startMinutes;
        }

        public int endMinutes() {
            return endMinutes;
        }

        /** 中性时间区间文本，如 {@code 08:00-09:40}；措辞无关，可直接放进 JVM 断言。 */
        @NonNull
        public String timeLabel() {
            return StudySchedule.formatMinutes(startMinutes) + "-" + StudySchedule.formatMinutes(endMinutes);
        }
    }

    private final Status status;
    private final int teachingWeek;
    private final boolean weekKnown;
    private final List<Slot> todaySlots;
    private final int currentIndex;
    private final Slot next;
    private final long minutesToNext;
    private final int nowMinutes;
    private final boolean closedToday;

    private TimetableWidgetPlan(Status status, int teachingWeek, boolean weekKnown,
                               List<Slot> todaySlots, int currentIndex, Slot next,
                               long minutesToNext, int nowMinutes, boolean closedToday) {
        this.status = status;
        this.teachingWeek = teachingWeek;
        this.weekKnown = weekKnown;
        this.todaySlots = Collections.unmodifiableList(todaySlots);
        this.currentIndex = currentIndex;
        this.next = next;
        this.minutesToNext = minutesToNext;
        this.nowMinutes = nowMinutes;
        this.closedToday = closedToday;
    }

    /**
     * 算出组件内容。
     *
     * @param courses       本机课表里的全部课程
     * @param schedule      当前生效的作息（冬令时/夏令时）
     * @param semesterStart 开学第 1 周内的某天；null 表示没设置
     * @param totalWeeks    学期总周数
     * @param today         今天
     * @param nowMinutes    现在从 0 点起的分钟数
     * @param adjustments   调课与节假日：这天的例外清单，与课表页、课前提醒共用同一套算法
     */
    @NonNull
    public static TimetableWidgetPlan of(@NonNull List<TimetableCourse> courses,
                                         @NonNull StudySchedule schedule,
                                         @Nullable LocalDate semesterStart,
                                         int totalWeeks,
                                         @NonNull LocalDate today,
                                         int nowMinutes,
                                         @NonNull List<DayAdjustment> adjustments) {
        if (courses.isEmpty()) {
            return new TimetableWidgetPlan(Status.NOT_IMPORTED, 0, semesterStart != null,
                    new ArrayList<>(), -1, null, -1, nowMinutes, false);
        }
        int week = SemesterCalendar.teachingWeek(semesterStart, today, totalWeeks);
        boolean closed = TimetableDay.isClosed(today, adjustments);
        List<Slot> slots = slotsOn(schedule, courses, semesterStart, totalWeeks, today,
                adjustments);
        slots.sort(Comparator.comparingInt((Slot slot) -> slot.startMinutes)
                .thenComparing(slot -> slot.course().name()));

        Slot current = null;
        int currentIndex = -1;
        Slot next = null;
        for (int i = 0; i < slots.size(); i++) {
            Slot slot = slots.get(i);
            if (current == null && slot.startMinutes() <= nowMinutes
                    && nowMinutes < slot.endMinutes()) {
                current = slot;
                currentIndex = i;
                continue;
            }
            if (slot.startMinutes() > nowMinutes) {
                next = slot;
                break;
            }
        }
        if (current == null && next == null) {
            // 今天的课要么全上完了，要么本来就一节没有：两种情况都要往前找出「下一节」。
            Slot upcoming = firstSlotAfter(schedule, courses, semesterStart, totalWeeks, today,
                    adjustments);
            Status status = slots.isEmpty() ? Status.TODAY_CLEAR : Status.TODAY_DONE;
            return new TimetableWidgetPlan(status, week, semesterStart != null, slots, -1,
                    upcoming, minutesUntil(today, nowMinutes, upcoming), nowMinutes, closed);
        }
        if (current != null) {
            Slot upcoming = next != null ? next
                    : firstSlotAfter(schedule, courses, semesterStart, totalWeeks, today,
                    adjustments);
            return new TimetableWidgetPlan(Status.IN_CLASS, week, semesterStart != null, slots,
                    currentIndex, upcoming, minutesUntil(today, nowMinutes, upcoming), nowMinutes,
                    closed);
        }
        return new TimetableWidgetPlan(Status.UPCOMING, week, semesterStart != null, slots, -1,
                next, minutesUntil(today, nowMinutes, next), nowMinutes, closed);
    }

    /** 正在上的这节课还剩几分钟；不在上课时为 -1。 */
    public long minutesRemaining() {
        Slot current = current();
        return current == null ? -1 : Math.max(0, current.endMinutes() - nowMinutes);
    }

    /** 当前这一节占今天列表的下标；不在上课时为 -1。 */
    public int currentIndex() {
        return currentIndex;
    }

    /** 正在上的这节课；不在上课时为 null。 */
    @Nullable
    public Slot current() {
        return currentIndex < 0 ? null : todaySlots.get(currentIndex);
    }

    /** 接下来要上的这节课（可能在明天或更晚）；近期内没课时为 null。 */
    @Nullable
    public Slot next() {
        return next;
    }

    /** 距离 {@link #next()} 还有几分钟；没有下一节时为 -1。 */
    public long minutesToNext() {
        return minutesToNext;
    }

    @NonNull
    public Status status() {
        return status;
    }

    /** 今天第几教学周；未设置开学日期或不在学期内时为 0。 */
    public int teachingWeek() {
        return teachingWeek;
    }

    /** 能不能确定周次，即用户是否设置过开学日期。 */
    public boolean weekKnown() {
        return weekKnown;
    }

    /** 今天是不是被整日停课（节假日、运动会）：组件据此说「今天放假」而不是「今天没课」。 */
    public boolean closedToday() {
        return closedToday;
    }

    @NonNull
    public List<Slot> todaySlots() {
        return todaySlots;
    }

    /**
     * 今天要列出的课程行，最多 {@code limit} 行。
     *
     * <p>正在上的那节只有在按时间顺序会被截掉时才提到第一行：小尺寸组件（2 行）里如果只列
     * 上午头两节，正在上的下午课就消失了，用户看不出「现在在上课」；大尺寸里保持时间顺序更好读。
     */
    @NonNull
    public List<Slot> rows(int limit) {
        if (limit <= 0 || todaySlots.isEmpty()) {
            return Collections.emptyList();
        }
        List<Slot> ordered = new ArrayList<>(todaySlots);
        if (currentIndex >= limit) {
            ordered.add(0, ordered.remove(currentIndex));
        }
        return Collections.unmodifiableList(
                ordered.subList(0, Math.min(limit, ordered.size())));
    }

    /**
     * 小组件高度换算成课程行数：去掉标题与状态行占的空间，按每行 {@value #ROW_DP} dp 折算，
     * 收进 [{@value #MIN_ROWS}, {@value #MAX_ROWS}]，让 2×2 到 4×3 都能自适应。
     */
    public static int maxRows(int heightDp) {
        int rows = (heightDp - CHROME_DP) / ROW_DP;
        return Math.max(MIN_ROWS, Math.min(MAX_ROWS, rows));
    }

    /**
     * 分钟数 → 组件上的口语时长：不足 1 小时说分钟，不足 1 天说小时（零头为 0 就不说分），
     * 更久说天。倒计时只精确到分钟，凑不到 1 分钟的说法统一按「不到 1 分钟」，
     * 免得出现「还有 0 分钟」这种读起来像坏了的字样。
     */
    @NonNull
    public static String durationLabel(long minutes) {
        if (minutes <= 0) {
            return "不到 1 分钟";
        }
        if (minutes < 60) {
            return minutes + " 分钟";
        }
        long hours = minutes / 60;
        long rest = minutes % 60;
        if (hours < 24) {
            return rest == 0 ? hours + " 小时" : hours + " 小时 " + rest + " 分";
        }
        long days = hours / 24;
        long leftHours = hours % 24;
        return leftHours == 0 ? days + " 天" : days + " 天 " + leftHours + " 小时";
    }

    /**
     * 这天要显示的课程段。
     *
     * <p>「这天到底有什么课」交给 {@link TimetableDay}，与课前提醒、课表页共用同一套调课规则；
     * 节次超出作息的脏数据在这里被自然跳过。
     */
    private static List<Slot> slotsOn(StudySchedule schedule, List<TimetableCourse> courses,
                                      @Nullable LocalDate semesterStart, int totalWeeks,
                                      LocalDate day, List<DayAdjustment> adjustments) {
        List<Slot> slots = new ArrayList<>();
        for (TimetableCourse course : TimetableDay.classesOn(courses, day, semesterStart,
                totalWeeks, adjustments)) {
            if (course.startPeriod() > schedule.totalPeriods()) {
                continue;
            }
            int start = schedule.periodStartMinutes(course.startPeriod());
            int end = schedule.periodEndMinutes(Math.min(course.endPeriod(),
                    schedule.totalPeriods()));
            slots.add(new Slot(course, day, start, Math.max(start, end)));
        }
        return slots;
    }

    /** 从今天往后找第一节有课的时间；{@link #HORIZON_DAYS} 天内没有就返回 null。 */
    @Nullable
    private static Slot firstSlotAfter(StudySchedule schedule, List<TimetableCourse> courses,
                                       @Nullable LocalDate semesterStart, int totalWeeks,
                                       LocalDate today, List<DayAdjustment> adjustments) {
        for (int offset = 1; offset <= HORIZON_DAYS; offset++) {
            LocalDate day = today.plusDays(offset);
            List<Slot> slots = slotsOn(schedule, courses, semesterStart, totalWeeks, day,
                    adjustments);
            if (slots.isEmpty()) {
                continue;
            }
            slots.sort(Comparator.comparingInt(Slot::startMinutes));
            return slots.get(0);
        }
        return null;
    }

    /** 距离那节课还有几分钟；没有下一节时为 -1。 */
    private static long minutesUntil(LocalDate today, int nowMinutes, @Nullable Slot slot) {
        if (slot == null) {
            return -1;
        }
        long dayDiff = ChronoUnit.DAYS.between(today, slot.day());
        return dayDiff * 24L * 60L + slot.startMinutes() - nowMinutes;
    }
}
