package org.localroute.app.backup;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 导入时的合并判定。纯函数，具体数据（本机有哪些条目、备份里有哪些条目）由调用方给出。
 *
 * <p>把判定抽出来的原因：导入是覆盖用户数据的动作，算错就是丢东西，
 * 而这部分逻辑跟 Android 存储无关，必须能在 JVM 用例里逐个边界跑一遍。
 */
public final class BackupMerge {

    public enum Policy {
        /** 备份说了算：备份里没有的本机条目会被删掉。适合课表、设置这类「一份当前状态」的数据。 */
        REPLACE,
        /** 按 id 合并，谁更新时间新用谁。适合路线这类用户会两边都改的收藏数据。 */
        MERGE_NEWER,
        /** 只补缺，本机已有的绝不覆盖。适合用户明确「我不想动现在的东西」。 */
        KEEP_MINE
    }

    public enum Action {
        ADD, REPLACE, KEEP, DROP
    }

    /** 一条可比较的数据：标识 + 更新时间（毫秒）。没有更新时间时传 0，表示「无法比较」。 */
    public static final class Entry {
        private final String id;
        private final long updatedAt;

        public Entry(@NonNull String id, long updatedAt) {
            this.id = id;
            this.updatedAt = updatedAt;
        }

        @NonNull
        public String id() {
            return id;
        }

        public long updatedAt() {
            return updatedAt;
        }
    }

    /** 判定结果：每条数据一个动作，按「先本机后新增」的稳定顺序排列。 */
    public static final class Plan {
        private final List<Entry> entries;
        private final List<Action> actions;

        private Plan(List<Entry> entries, List<Action> actions) {
            this.entries = entries;
            this.actions = actions;
        }

        @NonNull
        public List<Entry> entries() {
            return entries;
        }

        @NonNull
        public List<Action> actions() {
            return actions;
        }

        public int count(Action action) {
            int total = 0;
            for (Action item : actions) {
                if (item == action) {
                    total++;
                }
            }
            return total;
        }

        /** 备份里没有、但本机有且会被删掉的条目数量，用来提醒用户「覆盖会丢 X 条」。 */
        public int dropped() {
            return count(Action.DROP);
        }

        public boolean nothingToDo() {
            return count(Action.ADD) == 0 && count(Action.REPLACE) == 0 && dropped() == 0;
        }
    }

    private BackupMerge() {
    }

    @NonNull
    public static Plan plan(List<Entry> existing, List<Entry> incoming, Policy policy) {
        List<Entry> entries = new ArrayList<>();
        List<Action> actions = new ArrayList<>();
        for (Entry item : existing) {
            Entry replacement = findById(incoming, item.id());
            if (replacement == null) {
                if (policy == Policy.REPLACE) {
                    entries.add(item);
                    actions.add(Action.DROP);
                }
                continue;
            }
            Action action = decide(item, replacement, policy);
            // 条目本身要跟动作一致：KEEP 是保留本机那份，REPLACE 才是用备份那份。
            entries.add(action == Action.REPLACE ? replacement : item);
            actions.add(action);
        }
        Set<String> listed = new LinkedHashSet<>();
        for (Entry item : existing) {
            listed.add(item.id());
        }
        for (Entry item : incoming) {
            if (!listed.contains(item.id())) {
                listed.add(item.id());
                entries.add(item);
                actions.add(Action.ADD);
            }
        }
        return new Plan(entries, actions);
    }

    private static Action decide(Entry existing, Entry incoming, Policy policy) {
        if (policy == Policy.KEEP_MINE) {
            return Action.KEEP;
        }
        if (policy == Policy.REPLACE) {
            return Action.REPLACE;
        }
        // MERGE_NEWER：本机更新或时间无法比较时保留本机，宁可少改也不覆盖用户刚做的编辑。
        return incoming.updatedAt() > existing.updatedAt() ? Action.REPLACE : Action.KEEP;
    }

    private static Entry findById(List<Entry> items, String id) {
        for (Entry item : items) {
            if (item.id().equals(id)) {
                return item;
            }
        }
        return null;
    }
}
