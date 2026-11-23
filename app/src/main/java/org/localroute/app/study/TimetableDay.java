package org.localroute.app.study;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 「某一天实际上什么课」的唯一算法：先按周次挑出常规课，再套上这天的调课与节假日。
 *
 * <p>桌面小组件、课前提醒、课表页的「今天」都要按同一套规则回答这个问题，所以集中在这里；
 * 各自再算自己的节次钟点。返回的课程已经把新星期几、新节次写好，调用方不必再判周次。
 *
 * <p>纯 Java，输入是课表 + 日期 + 例外清单，可在 JVM 测试里逐条断言。
 */
public final class TimetableDay {

    private TimetableDay() {
    }

    /**
     * @param semesterStart 开学第 1 周内的某天；为 {@code null} 时不知道周次，
     *                      只按星期几给课（与课表页原有行为一致），调课仍然生效
     */
    @NonNull
    public static List<TimetableCourse> classesOn(@NonNull List<TimetableCourse> courses,
                                                 @NonNull LocalDate day,
                                                 @Nullable LocalDate semesterStart,
                                                 int totalWeeks,
                                                 @NonNull List<DayAdjustment> adjustments) {
        List<DayAdjustment> today = onDay(day, adjustments);
        for (DayAdjustment adjustment : today) {
            if (adjustment.kind() == DayAdjustment.Kind.HOLIDAY) {
                // 停课最大：即使同一天还挂着补课记录，也按放假处理。
                return new ArrayList<>();
            }
        }
        int weekday = day.getDayOfWeek().getValue();
        int week = semesterStart == null ? 0
                : SemesterCalendar.teachingWeek(semesterStart, day, totalWeeks);
        List<TimetableCourse> result = new ArrayList<>();
        for (TimetableCourse course : courses) {
            if (course.weekday() != weekday || !activeInWeek(course, semesterStart, week)) {
                continue;
            }
            DayAdjustment movedSameDay = movedWithinDay(today, course.id(), day);
            if (movedSameDay != null) {
                // 只是换节次或换教室，课还在：写成挪后的样子，别让它凭空消失。
                result.add(moved(course, day, weeksFor(semesterStart, week), movedSameDay));
                continue;
            }
            if (removedById(today, course.id())) {
                continue;
            }
            result.add(course);
        }
        addMovedIn(courses, day, semesterStart, week, adjustments, result);
        addMakeup(courses, day, semesterStart, week, today, result);
        return result;
    }

    /**
     * 某一整周（周一到周日）实际要上的课，调课与节假日按具体日期套进去。
     *
     * <p>课表网格画的是「这一周」，但调课永远落在某一天，所以逐天走 {@link #classesOn}
     * 再把结果摊平：改到别天的课会从原格消失、出现在新格，整周下来不会重也不会漏
     * （同一门课在同一周里最多出现一次，因为课程本身每周只上一次）。
     *
     * @param monday 这一周的周一，一般由开学日期推出来
     */
    @NonNull
    public static List<TimetableCourse> classesInWeek(@NonNull List<TimetableCourse> courses,
                                                      @NonNull LocalDate monday,
                                                      @Nullable LocalDate semesterStart,
                                                      int totalWeeks,
                                                      @NonNull List<DayAdjustment> adjustments) {
        LocalDate start = SemesterCalendar.mondayOf(monday);
        List<TimetableCourse> result = new ArrayList<>();
        for (int offset = 0; offset < 7; offset++) {
            for (TimetableCourse course : classesOn(courses, start.plusDays(offset),
                    semesterStart, totalWeeks, adjustments)) {
                if (!containsId(result, course.id())) {
                    result.add(course);
                }
            }
        }
        return result;
    }

    /** 这天是否被整日停课：课表页要据此把「今天没课」说成「今天放假」。 */
    public static boolean isClosed(@NonNull LocalDate day,
                                   @NonNull List<DayAdjustment> adjustments) {
        for (DayAdjustment adjustment : onDay(day, adjustments)) {
            if (adjustment.kind() == DayAdjustment.Kind.HOLIDAY) {
                return true;
            }
        }
        return false;
    }

    @NonNull
    public static List<DayAdjustment> onDay(@NonNull LocalDate day,
                                            @NonNull List<DayAdjustment> adjustments) {
        List<DayAdjustment> hits = new ArrayList<>();
        for (DayAdjustment adjustment : adjustments) {
            if (adjustment.date().equals(day)) {
                hits.add(adjustment);
            }
        }
        return hits;
    }

    /**
     * 记下这条例外：同一天同一门课的旧记录被覆盖（整日停课/补课按日期覆盖）。
     * 返回新列表，不改入参。
     */
    @NonNull
    public static List<DayAdjustment> replacing(@NonNull List<DayAdjustment> current,
                                                @NonNull DayAdjustment fresh) {
        List<DayAdjustment> next = new ArrayList<>();
        for (DayAdjustment old : current) {
            if (!old.slot().equals(fresh.slot())) {
                next.add(old);
            }
        }
        next.add(fresh);
        return next;
    }

    @NonNull
    public static List<DayAdjustment> removing(@NonNull List<DayAdjustment> current,
                                               @NonNull DayAdjustment stale) {
        List<DayAdjustment> next = new ArrayList<>();
        for (DayAdjustment old : current) {
            if (!old.equals(stale)) {
                next.add(old);
            }
        }
        return next;
    }

    /** 改到别处的课，从它的目标日看回来。 */
    private static void addMovedIn(List<TimetableCourse> courses, LocalDate day,
                                   @Nullable LocalDate semesterStart, int week,
                                   List<DayAdjustment> adjustments,
                                   List<TimetableCourse> result) {
        for (DayAdjustment adjustment : adjustments) {
            if (adjustment.kind() != DayAdjustment.Kind.MOVE) {
                continue;
            }
            LocalDate target = adjustment.targetDate();
            if (target == null || !target.equals(day) || adjustment.date().equals(day)) {
                continue; // 没挪走（同一天的改节次）在常规课里已经处理
            }
            TimetableCourse source = findById(courses, adjustment.courseId());
            // 源日与目标日同一周，周次直接沿用这天的；源那天没这门课就不凭空造一节。
            if (source == null || source.weekday()
                    != adjustment.date().getDayOfWeek().getValue()
                    || !activeInWeek(source, semesterStart, week)) {
                continue;
            }
            result.add(moved(source, day, weeksFor(semesterStart, week), adjustment));
        }
    }

    /** 同一天内的改节次：课还在，只是换了第几节。 */
    private static TimetableCourse moved(TimetableCourse source, LocalDate day, String weeks,
                                         DayAdjustment adjustment) {
        return new TimetableCourse(source.id(), source.name(), source.teacher(),
                source.location(), day.getDayOfWeek().getValue(),
                adjustment.toStart(), adjustment.toEnd(), weeks);
    }

    /** 挪过去的这天算第几周；没设置开学日期就沿用课程自己的周次写法。 */
    private static String weeksFor(@Nullable LocalDate semesterStart, int week) {
        return semesterStart == null ? null : String.valueOf(week);
    }

    /** 调休补课：这天按另一个星期几的课表上。 */
    private static void addMakeup(List<TimetableCourse> courses, LocalDate day,
                                  @Nullable LocalDate semesterStart, int week,
                                  List<DayAdjustment> today, List<TimetableCourse> result) {
        for (DayAdjustment adjustment : today) {
            if (adjustment.kind() != DayAdjustment.Kind.MAKEUP) {
                continue;
            }
            for (TimetableCourse course : courses) {
                if (course.weekday() != adjustment.refWeekday()
                        || !activeInWeek(course, semesterStart, week)
                        || containsId(result, course.id())) {
                    continue;
                }
                result.add(new TimetableCourse(course.id(), course.name(), course.teacher(),
                        course.location(), day.getDayOfWeek().getValue(),
                        course.startPeriod(), course.endPeriod(), course.weeks()));
            }
        }
    }

    private static boolean activeInWeek(TimetableCourse course, @Nullable LocalDate semesterStart,
                                        int week) {
        if (semesterStart == null) {
            return true; // 没开学日期就不知道周次，此时不按周次筛，行为与课表页一致
        }
        // 周次 0 表示这天在学期外（开学前或早已考完），一节都不该有。
        return week >= 1 && TimetableText.activeInWeek(course.weeks(), week);
    }

    @Nullable
    private static TimetableCourse findById(List<TimetableCourse> courses, String id) {
        for (TimetableCourse course : courses) {
            if (course.id().equals(id)) {
                return course;
            }
        }
        return null;
    }

    private static boolean containsId(List<TimetableCourse> courses, String id) {
        for (TimetableCourse course : courses) {
            if (course.id().equals(id)) {
                return true;
            }
        }
        return false;
    }

    /** 这天原本的课被改到同一周的另一天：这天不该再有它，目标日那边会补回来。 */
    @Nullable
    private static DayAdjustment movedWithinDay(List<DayAdjustment> today, String id,
                                                LocalDate day) {
        for (DayAdjustment adjustment : today) {
            if (adjustment.kind() == DayAdjustment.Kind.MOVE
                    && adjustment.courseId().equals(id)
                    && day.equals(adjustment.targetDate())) {
                return adjustment;
            }
        }
        return null;
    }

    private static boolean removedById(List<DayAdjustment> today, String id) {
        for (DayAdjustment adjustment : today) {
            if (adjustment.courseId().equals(id)
                    && (adjustment.kind() == DayAdjustment.Kind.CANCEL
                    || adjustment.kind() == DayAdjustment.Kind.MOVE)) {
                return true;
            }
        }
        return false;
    }
}
