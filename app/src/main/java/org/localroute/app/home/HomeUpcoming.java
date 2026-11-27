package org.localroute.app.home;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.localroute.app.study.DayAdjustment;
import org.localroute.app.study.StudySchedule;
import org.localroute.app.study.TimetableCourse;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 首页「接下来」：从今天起按时间顺序收集接下来的课程，最多 N 节，按天分组。
 *
 * <p>与按天翻页的时间轴不同，这里回答的是「接下来要上什么」：今天的课从下一节开始，
 * 已结束的课不占行；课少时自然顺延到明天、后天；固定上限保证首页不会被课表无限撑高。
 * 具体某天有什么课仍由 {@link HomeAgenda} 和 {@link org.localroute.app.study.TimetableDay}
 * 决定，调课、停课、节次钟点都不在这里重算。
 *
 * <p>纯 Java，措辞无关：只交日期与数量，中文标签由界面层映射，可在 JVM 用例里逐条断言。
 */
public final class HomeUpcoming {

    /** 首页默认最多显示六节课。 */
    public static final int DEFAULT_LIMIT = 6;

    /** 没有开学日期时不能按周次无限循环，只看从今天起的一周。 */
    private static final int NO_SEMESTER_HORIZON_DAYS = 7;

    /** 一个自然日里的展示组；没有课的普通日子不会出现在列表里，放假日会留一个空组。 */
    public static final class DayGroup {
        private final LocalDate date;
        private final boolean closed;
        private final int total;
        private final int finished;
        private final int remaining;
        private final List<HomeAgenda.Item> items;

        private DayGroup(LocalDate date, boolean closed, int total, int finished, int remaining,
                         List<HomeAgenda.Item> items) {
            this.date = date;
            this.closed = closed;
            this.total = total;
            this.finished = finished;
            this.remaining = remaining;
            this.items = Collections.unmodifiableList(items);
        }

        @NonNull public LocalDate date() {
            return date;
        }

        /** 整日停课：这天没有行，但标题要写「放假」。 */
        public boolean closed() {
            return closed;
        }

        /** 当天课程总数，含今天已结束的课；放假日为 0。 */
        public int total() {
            return total;
        }

        /** 今天已结束、因而不占行的课程数；未来日期为 0。 */
        public int finished() {
            return finished;
        }

        /** 当天还没上的课程总数（含因上限暂未展示的）；标题上的「余 N 节」用它。 */
        public int remaining() {
            return remaining;
        }

        /** 组内实际展示的课程；今天不含已结束的课。 */
        @NonNull public List<HomeAgenda.Item> items() {
            return items;
        }
    }

    /** 一次「接下来」的结果。 */
    public static final class Feed {
        private final List<DayGroup> groups;
        private final int shown;
        @Nullable private final LocalDate nextHiddenDate;
        private final int nextHiddenCount;
        private final int todayTotal;
        private final int todayFinished;
        private final boolean todayClosed;
        private final boolean notImported;

        private Feed(List<DayGroup> groups, int shown,
                     @Nullable LocalDate nextHiddenDate, int nextHiddenCount,
                     int todayTotal, int todayFinished, boolean todayClosed,
                     boolean notImported) {
            this.groups = Collections.unmodifiableList(groups);
            this.shown = shown;
            this.nextHiddenDate = nextHiddenDate;
            this.nextHiddenCount = nextHiddenCount;
            this.todayTotal = todayTotal;
            this.todayFinished = todayFinished;
            this.todayClosed = todayClosed;
            this.notImported = notImported;
        }

        /** 有课程要展示的日组；放假日可能以空组出现。 */
        @NonNull public List<DayGroup> groups() {
            return groups;
        }

        /** 实际展示的课程行数（不超过上限）。 */
        public int shown() {
            return shown;
        }

        /** 上限之外的第一天；这一天就是底部「还有 N 节」指的那天。 */
        @Nullable public LocalDate nextHiddenDate() {
            return nextHiddenDate;
        }

        /** 上限之外、且与 {@link #nextHiddenDate()} 同一天的课程数。 */
        public int nextHiddenCount() {
            return nextHiddenCount;
        }

        /** 今天课程总数，含已结束的课；用于问候语里的「今天 N 节课」。 */
        public int todayTotal() {
            return todayTotal;
        }

        /** 今天已结束的课程数；用来解释为什么列表从下一节开始。 */
        public int todayFinished() {
            return todayFinished;
        }

        public boolean todayClosed() {
            return todayClosed;
        }

        /** 本机一条课程都没有：界面要引导去导入，而不是说「接下来没有课」。 */
        public boolean notImported() {
            return notImported;
        }

        /** 没有可展示的课程：可能是课都上完了、放假或学期结束。 */
        public boolean empty() {
            return shown == 0;
        }
    }

    private static final class DayInfo {
        final LocalDate date;
        final HomeAgenda.Day agenda;

        DayInfo(LocalDate date, HomeAgenda.Day agenda) {
            this.date = date;
            this.agenda = agenda;
        }
    }

    private static final class Event {
        final LocalDate date;
        final HomeAgenda.Item item;

        Event(LocalDate date, HomeAgenda.Item item) {
            this.date = date;
            this.item = item;
        }
    }

    private HomeUpcoming() {
    }

    /**
     * @param limit 最多展示几节课；小于 1 时按 1 处理
     */
    @NonNull
    public static Feed of(@NonNull List<TimetableCourse> courses,
                          @NonNull StudySchedule schedule,
                          @Nullable LocalDate semesterStart,
                          int totalWeeks,
                          @NonNull List<DayAdjustment> adjustments,
                          @NonNull LocalDate today,
                          int nowMinutes,
                          int limit) {
        int max = Math.max(1, limit);
        HomeAgenda.Day todayAgenda = HomeAgenda.of(courses, schedule, semesterStart, totalWeeks,
                adjustments, today, today, nowMinutes);
        if (todayAgenda.notImported()) {
            return new Feed(Collections.emptyList(), 0, null, 0,
                    0, 0, false, true);
        }

        LocalDate end = semesterStart == null
                ? today.plusDays(NO_SEMESTER_HORIZON_DAYS - 1L)
                : semesterStart.plusDays(Math.max(1, totalWeeks) * 7L - 1);
        if (end.isBefore(today)) {
            end = today;
        }

        Map<LocalDate, DayInfo> dayInfos = new LinkedHashMap<>();
        List<Event> events = new ArrayList<>();
        for (LocalDate day = today; !day.isAfter(end); day = day.plusDays(1)) {
            HomeAgenda.Day agenda = day.equals(today) ? todayAgenda
                    : HomeAgenda.of(courses, schedule, semesterStart, totalWeeks, adjustments,
                    day, today, nowMinutes);
            dayInfos.put(day, new DayInfo(day, agenda));
            if (agenda.closed()) {
                continue;
            }
            for (HomeAgenda.Item item : agenda.items()) {
                events.add(new Event(day, item));
            }
        }

        int shownCount = Math.min(max, events.size());
        LocalDate nextHiddenDate = null;
        int nextHiddenCount = 0;
        if (events.size() > shownCount) {
            nextHiddenDate = events.get(shownCount).date;
            for (int i = shownCount; i < events.size(); i++) {
                if (!events.get(i).date.equals(nextHiddenDate)) {
                    break;
                }
                nextHiddenCount++;
            }
        }

        List<DayGroup> groups = new ArrayList<>();
        if (shownCount > 0) {
            Map<LocalDate, List<HomeAgenda.Item>> shownByDate = new LinkedHashMap<>();
            for (int i = 0; i < shownCount; i++) {
                Event event = events.get(i);
                shownByDate.computeIfAbsent(event.date, ignored -> new ArrayList<>())
                        .add(event.item);
            }
            LocalDate lastShown = events.get(shownCount - 1).date;
            for (DayInfo info : dayInfos.values()) {
                if (info.date.isAfter(lastShown)) {
                    break;
                }
                if (info.agenda.closed()) {
                    groups.add(new DayGroup(info.date, true, 0, 0, 0,
                            Collections.emptyList()));
                    continue;
                }
                List<HomeAgenda.Item> items = shownByDate.get(info.date);
                if (items == null || items.isEmpty()) {
                    continue;
                }
                groups.add(new DayGroup(info.date, false, info.agenda.courseCount(),
                        info.agenda.hiddenFinished(), info.agenda.items().size(), items));
            }
        }

        return new Feed(groups, shownCount, nextHiddenDate, nextHiddenCount,
                todayAgenda.courseCount(), todayAgenda.hiddenFinished(), todayAgenda.closed(),
                false);
    }
}
