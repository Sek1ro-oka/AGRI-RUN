package org.localroute.app.home;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.localroute.app.study.DayAdjustment;
import org.localroute.app.study.StudySchedule;
import org.localroute.app.study.TimetableCourse;
import org.localroute.app.study.TimetableDay;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 首页某一天的课程：按真实节次钟点排，已结束的默认收起。
 *
 * <p>课程与调课沿用 {@link TimetableDay}，钟点沿用 {@link StudySchedule}，与课表页、桌面小组件、
 * 课前提醒同一套算法，这里不再重写一遍。作业不在这条轴上——它有自己的跨天清单，见
 * {@link HomeworkDue}。
 *
 * <p>纯 Java，措辞无关：状态用枚举表达，中文标签由界面层映射，可在 JVM 用例里逐条断言。
 */
public final class HomeAgenda {

    /** 一节课相对「现在」的位置。 */
    public enum ClassState {
        DONE, LIVE, UPCOMING
    }

    /** 时间轴上的一行课程。 */
    public static final class Item {
        private final int minutes;
        private final String timeLabel;
        private final String title;
        private final String subtitle;
        private final ClassState classState;

        private Item(int minutes, String timeLabel, String title, String subtitle,
                     ClassState classState) {
            this.minutes = minutes;
            this.timeLabel = timeLabel;
            this.title = title;
            this.subtitle = subtitle;
            this.classState = classState;
        }

        /** 当天 0 点起的分钟数。 */
        public int minutes() {
            return minutes;
        }

        /** {@code HH:mm}。 */
        @NonNull public String timeLabel() {
            return timeLabel;
        }

        @NonNull public String title() {
            return title;
        }

        @NonNull public String subtitle() {
            return subtitle;
        }

        @NonNull public ClassState classState() {
            return classState;
        }

        public boolean finished() {
            return classState == ClassState.DONE;
        }
    }

    /** 某一天的课程内容。 */
    public static final class Day {
        private final List<Item> items;
        private final boolean closed;
        private final boolean notImported;
        private final int courseCount;
        private final int hiddenFinished;

        private Day(List<Item> items, boolean closed, boolean notImported,
                    int courseCount, int hiddenFinished) {
            this.items = items;
            this.closed = closed;
            this.notImported = notImported;
            this.courseCount = courseCount;
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

        /** 当天课程总数，不受折叠影响。 */
        public int courseCount() {
            return courseCount;
        }

        /** 今天被收起的已结束课程数。 */
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
                         int nowMinutes) {
        List<Item> items = new ArrayList<>();
        int courseCount = 0;
        int hiddenFinished = 0;
        // 只有「今天」需要收起已结束的课；翻到过去日期时全部保留，否则历史全是空白。
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
            Item item = new Item(start, StudySchedule.formatMinutes(start),
                    course.name(), placeAndTeacher(course),
                    classState(day, today, nowMinutes, start, end));
            courseCount++;
            if (hideFinished && item.finished()) {
                hiddenFinished++;
                continue;
            }
            items.add(item);
        }

        items.sort(Comparator.comparingInt(Item::minutes).thenComparing(Item::title));
        return new Day(items, TimetableDay.isClosed(day, adjustments), courses.isEmpty(),
                courseCount, hiddenFinished);
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