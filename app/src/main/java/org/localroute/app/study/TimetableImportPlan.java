package org.localroute.app.study;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 分享码导入前的预演：把「会和本机哪门课撞在同一节课」先说出来，再让用户选追加还是替换。
 *
 * <p>冲突判定看三件事同时成立：同一星期几、节次区间有交叠、周次至少有一周重合。
 * 周次重合按 {@link TimetableText#activeInWeek} 逐周试到第 24 周——「1-16」与「1-8双」
 * 在这种写法下确实共用第 2、4、6、8 周，只比首末周会误报。
 *
 * <p>纯 Java，输入是两门课程与两份课表，所有结论都能在 JVM 测试里断言。
 */
public final class TimetableImportPlan {

    /** 周次判定往上试到第几周；超过这个长度的学期极少，再长也只按前 24 周判重合。 */
    private static final int WEEK_PROBE_LIMIT = 24;

    private final List<TimetableCourse> toAdd;
    private final int duplicates;
    private final List<String> conflicts;

    private TimetableImportPlan(List<TimetableCourse> toAdd, int duplicates, List<String> conflicts) {
        this.toAdd = toAdd;
        this.duplicates = duplicates;
        this.conflicts = conflicts;
    }

    /**
     * @param incoming 分享码解出来的课程
     * @param existing 本机当前课表
     */
    @NonNull
    public static TimetableImportPlan of(@NonNull List<TimetableCourse> incoming,
                                        @NonNull List<TimetableCourse> existing) {
        Set<String> knownIds = new HashSet<>();
        for (TimetableCourse course : existing) {
            knownIds.add(course.id());
        }
        List<TimetableCourse> added = new ArrayList<>();
        List<String> clashes = new ArrayList<>();
        int repeated = 0;
        for (TimetableCourse course : incoming) {
            if (knownIds.contains(course.id())) {
                repeated++;
                continue;
            }
            added.add(course);
            for (TimetableCourse other : existing) {
                if (overlaps(course, other)) {
                    clashes.add(conflictLabel(other, course));
                }
            }
        }
        return new TimetableImportPlan(added, repeated, clashes);
    }

    /** 两门课是否可能同时占住一节：星期几相同、节次交叠、周次有重合。 */
    public static boolean overlaps(@NonNull TimetableCourse a, @NonNull TimetableCourse b) {
        return a.weekday() == b.weekday()
                && a.startPeriod() <= b.endPeriod() && b.startPeriod() <= a.endPeriod()
                && weeksCollide(a.weeks(), b.weeks());
    }

    /** 至少有一周双方都有课；写法完全看不懂时按「每周」处理，宁可多提醒一次。 */
    public static boolean weeksCollide(@Nullable String first, @Nullable String second) {
        for (int week = 1; week <= WEEK_PROBE_LIMIT; week++) {
            if (TimetableText.activeInWeek(first, week) && TimetableText.activeInWeek(second, week)) {
                return true;
            }
        }
        return false;
    }

    /** 追加时真正写入的课程（已按 id 去掉本机同一条）。 */
    @NonNull
    public List<TimetableCourse> toAdd() {
        return toAdd;
    }

    /** 本机已有同 id 的课程数：它们不会被第二份分享码覆盖。 */
    public int duplicates() {
        return duplicates;
    }

    @NonNull
    public List<String> conflicts() {
        return conflicts;
    }

    public boolean hasConflicts() {
        return !conflicts.isEmpty();
    }

    /** 追加之后的完整课表。 */
    @NonNull
    public static List<TimetableCourse> appendTo(@NonNull List<TimetableCourse> existing,
                                                 @NonNull List<TimetableCourse> incoming) {
        List<TimetableCourse> merged = new ArrayList<>(existing);
        Set<String> knownIds = new HashSet<>();
        for (TimetableCourse course : existing) {
            knownIds.add(course.id());
        }
        for (TimetableCourse course : incoming) {
            if (!knownIds.contains(course.id()) && course.isValid()) {
                merged.add(course);
            }
        }
        return merged;
    }

    /** 「周一 第1-2节：高数 与 大学英语」这样一行，说清撞在哪。 */
    @NonNull
    public static String conflictLabel(@NonNull TimetableCourse local,
                                       @NonNull TimetableCourse incoming) {
        int from = Math.max(1, Math.max(local.startPeriod(), incoming.startPeriod()));
        int to = Math.min(local.endPeriod(), incoming.endPeriod());
        return local.weekdayLabel() + " 第" + from + (to > from ? "-" + to : "") + "节："
                + name(local) + " 与 " + name(incoming);
    }

    @NonNull
    private static String name(@NonNull TimetableCourse course) {
        String trimmed = course.name().trim();
        return trimmed.isEmpty() ? "未命名课程" : trimmed;
    }
}
