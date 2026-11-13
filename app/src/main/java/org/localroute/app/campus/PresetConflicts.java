package org.localroute.app.campus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 预设路线与现有路线的重名判断。
 *
 * <p>激活码补回预设时，用户可能已经有一条同名自定义路线。这里只做纯数据判断：
 * 名称去掉首尾空白、忽略大小写后相同即视为重名；空名称不算重名，
 * 同一个 id 的路线（预设本身）也不会和自己冲突，便于 JVM 单测。
 */
public final class PresetConflicts {

    private PresetConflicts() {
    }

    /** 名称去掉首尾空白并忽略大小写后是否相同；空名称不与任何路线重名。 */
    public static boolean sameName(String first, String second) {
        String a = normalize(first);
        String b = normalize(second);
        return !a.isEmpty() && a.equals(b);
    }

    /** 找出与 {@code preset} 重名、但 id 不同的现有路线，保持原有顺序。 */
    public static List<CampusRoute> findBySameName(List<CampusRoute> routes, CampusRoute preset) {
        List<CampusRoute> conflicts = new ArrayList<>();
        if (routes == null || preset == null) {
            return conflicts;
        }
        for (CampusRoute candidate : routes) {
            if (candidate == null || candidate.id().equals(preset.id())) {
                continue;
            }
            if (sameName(candidate.name(), preset.name())) {
                conflicts.add(candidate);
            }
        }
        return Collections.unmodifiableList(conflicts);
    }

    /** 冲突集合里是否包含指定 id 的路线。 */
    public static boolean contains(List<CampusRoute> conflicts, String routeId) {
        if (conflicts == null || routeId == null) {
            return false;
        }
        for (CampusRoute candidate : conflicts) {
            if (candidate.id().equals(routeId)) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }
}
