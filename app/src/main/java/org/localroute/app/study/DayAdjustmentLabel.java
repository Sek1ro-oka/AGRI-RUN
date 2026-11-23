package org.localroute.app.study;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

/**
 * 调课与节假日记录的中文说法，供设置页列表与提示共用。
 *
 * <p>放在纯 Java 里而不是拼在界面代码里，是为了让「一行例外到底写成什么样」也能被 JVM 用例
 * 钉住：课程被删掉、日期跨年、改课找不到原课这些边界都在这儿判掉。
 */
public final class DayAdjustmentLabel {

    private static final String[] WEEKDAYS = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};

    private DayAdjustmentLabel() {
    }

    /** 1=周一 … 7=周日；越界返回空串，调用方按「没写星期」处理。 */
    @NonNull
    public static String weekdayName(int weekday) {
        return weekday >= 1 && weekday <= 7 ? WEEKDAYS[weekday - 1] : "";
    }

    /** 列表里的一天：{@code MM-dd}，跨年时补上年份，免得 01-05 被当成今年的。 */
    @NonNull
    public static String dateLabel(@NonNull LocalDate date, @NonNull LocalDate today) {
        return date.getYear() == today.getYear()
                ? String.format(Locale.US, "%02d-%02d", date.getMonthValue(), date.getDayOfMonth())
                : date.toString();
    }

    /**
     * 一条例外的完整说法，例如「12-24 周四 放假」或「12-24 周四 《高等数学》改到周五 第3-4节」。
     *
     * @param courses 本机课表，用来把课程 id 换回课程名；课程删了就写「已删除的课程」
     */
    @NonNull
    public static String label(@NonNull DayAdjustment adjustment,
                               @Nullable List<TimetableCourse> courses,
                               @NonNull LocalDate today) {
        StringBuilder text = new StringBuilder();
        text.append(dateLabel(adjustment.date(), today))
                .append(' ').append(weekdayName(adjustment.date().getDayOfWeek().getValue()));
        switch (adjustment.kind()) {
            case MAKEUP:
                text.append(" 按").append(weekdayName(adjustment.refWeekday())).append("上课");
                break;
            case CANCEL:
                text.append(" 停上").append(courseName(courses, adjustment.courseId()));
                break;
            case MOVE:
                text.append(' ').append(courseName(courses, adjustment.courseId()))
                        .append("改到").append(weekdayName(adjustment.toWeekday()))
                        .append(' ').append(periodLabel(adjustment.toStart(), adjustment.toEnd()));
                break;
            default:
                text.append(" 放假");
                break;
        }
        return text.toString();
    }

    /** 四种例外在添加菜单里的名字。 */
    @NonNull
    public static String kindLabel(@NonNull DayAdjustment.Kind kind) {
        switch (kind) {
            case MAKEUP:
                return "补课（按某天课表上）";
            case CANCEL:
                return "停课（某门课不上）";
            case MOVE:
                return "改课（换天或换节次）";
            default:
                return "放假（全天不上）";
        }
    }

    /** 节次说法：{@code 第3节} 或 {@code 第3-4节}；选择器与整句说明都用它。 */
    @NonNull
    public static String periodLabel(int start, int end) {
        return start == end ? "第" + start + "节" : "第" + start + "-" + end + "节";
    }

    @NonNull
    private static String courseName(@Nullable List<TimetableCourse> courses, String id) {
        if (courses != null) {
            for (TimetableCourse course : courses) {
                if (course.id().equals(id)) {
                    return "《" + course.name() + "》";
                }
            }
        }
        return "《已删除的课程》";
    }
}
