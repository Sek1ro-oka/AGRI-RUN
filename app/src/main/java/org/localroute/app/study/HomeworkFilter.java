package org.localroute.app.study;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 作业列表的「近期」视图。
 *
 * <p>有截止时间的作业：未截止的按截止时间从早到晚排在前面，近 7 天内已截止的排在
 * 最后；截止超过 7 天的旧作业不再显示。
 *
 * <p>没有可用截止时间的作业只保留「待完成」：学习通允许作业不设截止时间，这类作业
 * 不做丢弃，按课程名、标题排在最后，时间栏显示「未标注截止时间」。无时间的已完成、
 * 已提交与已截止条目不再列出，避免列表随学期无限变长。
 */
public final class HomeworkFilter {

    public static final long RECENT_WINDOW_MS = 7L * 24L * 60L * 60L * 1000L;

    private HomeworkFilter() {
    }

    @NonNull
    public static List<HomeworkItem> recent(@NonNull List<HomeworkItem> items, long nowMillis) {
        List<HomeworkItem> upcoming = new ArrayList<>();
        List<HomeworkItem> recentlyExpired = new ArrayList<>();
        List<HomeworkItem> undated = new ArrayList<>();
        for (HomeworkItem item : items) {
            long deadline = item.deadlineEpochMillis();
            if (deadline > nowMillis) {
                upcoming.add(item);
            } else if (deadline > 0L) {
                if (deadline >= nowMillis - RECENT_WINDOW_MS) {
                    recentlyExpired.add(item);
                }
            } else if (item.status() == HomeworkItem.Status.PENDING) {
                undated.add(item);
            }
        }
        sortByDeadline(upcoming);
        sortByDeadline(recentlyExpired);
        undated.sort((left, right) -> {
            int course = left.courseName().compareTo(right.courseName());
            return course != 0 ? course : left.title().compareTo(right.title());
        });
        upcoming.addAll(recentlyExpired);
        upcoming.addAll(undated);
        return upcoming;
    }

    /**
     * 用上次缓存补回这次列表里丢失的截止时间。
     *
     * <p>作业截止后，列表页会移除倒计时；靠上一次抓到的绝对时间，才能在截止后的
     * 7 天内继续把它排在底部。
     */
    @NonNull
    public static List<HomeworkItem> mergeCachedDeadlines(
            @NonNull List<HomeworkItem> fresh,
            @NonNull List<HomeworkItem> cached) {
        Map<String, HomeworkItem> previous = new HashMap<>();
        for (HomeworkItem item : cached) {
            if (item.deadlineEpochMillis() > 0L) {
                previous.put(item.key(), item);
            }
        }
        List<HomeworkItem> merged = new ArrayList<>(fresh.size());
        for (HomeworkItem item : fresh) {
            if (item.deadlineEpochMillis() > 0L) {
                merged.add(item);
                continue;
            }
            HomeworkItem old = previous.get(item.key());
            merged.add(old == null ? item
                    : item.withDeadline(old.deadlineEpochMillis(), old.deadlineRaw()));
        }
        return merged;
    }

    /**
     * 风控导致只读到部分课程时，把这次没读到的缓存作业补回列表，
     * 避免一次半途中断把上一次的完整结果清空。
     */
    @NonNull
    public static List<HomeworkItem> mergeUnread(@NonNull List<HomeworkItem> fresh,
                                                 @NonNull List<HomeworkItem> cached) {
        Map<String, HomeworkItem> merged = new LinkedHashMap<>();
        for (HomeworkItem item : fresh) {
            merged.put(item.key(), item);
        }
        for (HomeworkItem item : cached) {
            merged.putIfAbsent(item.key(), item);
        }
        return new ArrayList<>(merged.values());
    }

    private static void sortByDeadline(List<HomeworkItem> items) {
        items.sort((left, right) -> {
            int deadline = Long.compare(left.deadlineEpochMillis(), right.deadlineEpochMillis());
            if (deadline != 0) {
                return deadline;
            }
            int course = left.courseName().compareTo(right.courseName());
            return course != 0 ? course : left.title().compareTo(right.title());
        });
    }
}
