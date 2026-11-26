package org.localroute.app.home;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.localroute.app.study.DayAdjustment;
import org.localroute.app.study.HomeworkItem;
import org.localroute.app.study.StudySchedule;
import org.localroute.app.study.TimetableCourse;
import org.localroute.app.study.TimetableDay;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 首页某一天的日程：课程按真实节次钟点排，作业按真实截止时刻插进同一条时间轴。
 *
 * <p>课程与调课沿用 {@link TimetableDay}，钟点沿用 {@link StudySchedule}，与课表页、桌面小组件、
 * 课前提醒同一套算法，这里不再重写一遍。
 *
 * <p>作业没有截止时刻（{@code deadlineEpochMillis == 0}）时不编造时间：{@link Item#timeLabel()}
 * 为 {@code null}，排在全部有时刻的条目之后，且只挂在「今天」那一栏——没有日期的东西不能归到别天。
 *
 * <p>纯 Java，措辞无关：状态用枚举表达，中文标签由界面层映射，可在 JVM 用例里逐条断言。
 */
public final class HomeAgenda {

    /** 一节课相对「现在」的位置。 */
    public enum ClassState {
        DONE, LIVE, UPCOMING
    }

    public enum Kind {
        COURSE, HOMEWORK
    }

    /** 一条待办：由界面层从学习通缓存与自建作业统一转过来。 */
    public static final class Task {
        private final String title;
        private final String subtitle;
        private final long deadlineMillis;
        private final HomeworkItem.Status status;

        public Task(@NonNull String title, @NonNull String subtitle,
                    long deadlineMillis, @NonNull HomeworkItem.Status status) {
            this.title = title;
            this.subtitle = subtitle;
            this.deadlineMillis = deadlineMillis;
            this.status = status;
        }

        @NonNull public String title() {
            return title;
        }

        @NonNull public String subtitle() {
            return subtitle;
        }

        /** 0 表示没解析到截止时刻。 */
        public long deadlineMillis() {
            return deadlineMillis;
        }

        @NonNull public HomeworkItem.Status status() {
            return status;
        }
    }

    /** 时间轴上的一行。 */
    public static final class Item {
        private final Kind kind;
        private final int minutes;
        private final String timeLabel;
        private final String title;
        private final String subtitle;
        private final ClassState classState;
        private final HomeworkItem.Status taskStatus;

        private Item(Kind kind, int minutes, @Nullable String timeLabel, String title,
                     String subtitle, @Nullable ClassState classState,
                     @Nullable HomeworkItem.Status taskStatus) {
            this.kind = kind;
            this.minutes = minutes;
            this.timeLabel = timeLabel;
            this.title = title;
            this.subtitle = subtitle;
            this.classState = classState;
            this.taskStatus = taskStatus;
        }

        @NonNull public Kind kind() {
            return kind;
        }

        /** 当天 0 点起的分钟数；没有时刻时为 -1。 */
        public int minutes() {
            return minutes;
        }

        /** {@code HH:mm}；没有真实时刻时为 {@code null}，界面据此显示「未标注」。 */
        @Nullable public String timeLabel() {
            return timeLabel;
        }

        @NonNull public String title() {
            return title;
        }

        @NonNull public String subtitle() {
            return subtitle;
        }

        /** 课程状态；作业为 {@code null}。 */
        @Nullable public ClassState classState() {
            return classState;
        }

        /** 作业状态；课程为 {@code null}。 */
        @Nullable public HomeworkItem.Status taskStatus() {
            return taskStatus;
        }

        /** 课上完了、作业交完或过期了，都算已结束。 */
        public boolean finished() {
            return kind == Kind.COURSE
                    ? classState == ClassState.DONE
                    : taskStatus != HomeworkItem.Status.PENDING;
        }
    }

    /** 某一天的完整内容。 */
    public static final class Day {
        private final List<Item> items;
        private final boolean closed;
        private final boolean notImported;
        private final int courseCount;
        private final int taskCount;
        private final int hiddenFinished;

        private Day(List<Item> items, boolean closed, boolean notImported,
                    int courseCount, int taskCount, int hiddenFinished) {
            this.items = items;
            this.closed = closed;
            this.notImported = notImported;
            this.courseCount = courseCount;
            this.taskCount = taskCount;
            this.hiddenFinished = hiddenFinished;
        }

        @NonNull public List<Item> items() {
            return items;
        }

        /** 整日停课（节假日、运动会）：界面要说「放假」而不是「没课」。 */
        public boolean closed() {
            return closed;
        }

        /** 本机一条课程都没有：界面要引导去导入，而不是说「今天没课」。 */
        public boolean notImported() {
            return notImported;
        }

        public int courseCount() {
            return courseCount;
        }

        public int taskCount() {
            return taskCount;
        }

        /** 今天被隐藏掉的已结束条目数；{@link #courseCount()} 等仍是当天总数。 */
        public int hiddenFinished() {
            return hiddenFinished;
        }
    }

    private HomeAgenda() {
    }

    @NonNull
    public static Day of(@NonNull List<TimetableCourse> courses,
                         @NonNull StudySchedule schedule,
                         @Nullable LocalDate semesterStart,
                         int totalWeeks,
                         @NonNull List<DayAdjustment> adjustments,
                         @NonNull LocalDate day,
                         @NonNull LocalDate today,
                         int nowMinutes,
                         @NonNull List<Task> tasks) {
        List<Item> timed = new ArrayList<>();
        List<Item> undated = new ArrayList<>();
        int courseCount = 0;
        int taskCount = 0;
        int hiddenFinished = 0;
        // 只有「今天」需要收起已结束的条目；翻到过去日期时全部保留，否则历史全是空白。
        boolean hideFinished = day.equals(today);

        for (TimetableCourse course : TimetableDay.classesOn(courses, day, semesterStart,
                totalWeeks, adjustments)) {
            // 与小组件、课前提醒同一道守卫：超出当前作息总节数的课不算钟点，否则分钟数会外推过
            // 24 点，被 formatMinutes 绕成凌晨的假时间。
            if (course.startPeriod() > schedule.totalPeriods()) {
                continue;
            }
            int start = schedule.periodStartMinutes(course.startPeriod());
            int end = schedule.periodEndMinutes(
                    Math.min(course.endPeriod(), schedule.totalPeriods()));
            end = Math.max(start, end);
            Item item = new Item(Kind.COURSE, start, StudySchedule.formatMinutes(start),
                    course.name(), placeAndTeacher(course),
                    classState(day, today, nowMinutes, start, end), null);
            courseCount++;
            if (hideFinished && item.finished()) {
                hiddenFinished++;
                continue;
            }
            timed.add(item);
        }

        ZoneId zone = ZoneId.systemDefault();
        for (Task task : tasks) {
            if (task.deadlineMillis() <= 0L) {
                // 没有时刻也没有日期：只挂在今天，且不编时间。已交的不必再占首页位置。
                if (day.equals(today) && task.status() == HomeworkItem.Status.PENDING) {
                    undated.add(new Item(Kind.HOMEWORK, -1, null, task.title(), task.subtitle(),
                            null, task.status()));
                    taskCount++;
                }
                continue;
            }
            Instant instant = Instant.ofEpochMilli(task.deadlineMillis());
            if (!instant.atZone(zone).toLocalDate().equals(day)) {
                continue;
            }
            int minutes = instant.atZone(zone).getHour() * 60 + instant.atZone(zone).getMinute();
            Item item = new Item(Kind.HOMEWORK, minutes, StudySchedule.formatMinutes(minutes),
                    task.title(), task.subtitle(), null, task.status());
            taskCount++;
            if (hideFinished && item.finished()) {
                hiddenFinished++;
                continue;
            }
            timed.add(item);
        }

        timed.sort(Comparator.comparingInt((Item item) -> item.minutes)
                .thenComparing(item -> item.title));
        List<Item> items = new ArrayList<>(timed);
        items.addAll(undated);
        return new Day(items, TimetableDay.isClosed(day, adjustments), courses.isEmpty(),
                courseCount, taskCount, hiddenFinished);
    }

    @NonNull
    private static ClassState classState(LocalDate day, LocalDate today, int nowMinutes,
                                         int start, int end) {
        if (day.isBefore(today)) {
            return ClassState.DONE;
        }
        if (day.isAfter(today)) {
            return ClassState.UPCOMING;
        }
        if (nowMinutes < start) {
            return ClassState.UPCOMING;
        }
        return nowMinutes < end ? ClassState.LIVE : ClassState.DONE;
    }

    @NonNull
    private static String placeAndTeacher(@NonNull TimetableCourse course) {
        boolean hasPlace = !course.location().isEmpty();
        boolean hasTeacher = !course.teacher().isEmpty();
        if (hasPlace && hasTeacher) {
            return course.location() + " · " + course.teacher();
        }
        return hasPlace ? course.location() : course.teacher();
    }
}