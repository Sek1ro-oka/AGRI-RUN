package org.localroute.app.backup;

import androidx.annotation.NonNull;

import org.localroute.app.R;
import org.localroute.app.run.RunHistoryStore;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 备份内容清单：哪些段落可备份、每段包含哪些存储键、导入时怎么处理。
 *
 * <p>这里是白名单而不是黑名单：新增的 SharedPreferences 键默认不进备份，
 * 免得哪天存了口令或 cookie 就被顺手带出去。学习通登录态（study_auth 的
 * cookie/uid）、下载缓存（app_update）因此被排除，见 {@link #excludedPrefs()}。
 */
public final class BackupCatalog {

    public static final String SECTION_ROUTES = "routes";
    public static final String SECTION_ROUTE_FILES = "route_files";
    public static final String SECTION_MAP_POINTS = "map_points";
    public static final String SECTION_TIMETABLE = "timetable";
    public static final String SECTION_HOMEWORK = "homework";
    public static final String SECTION_SCHOOL = "school";
    public static final String SECTION_RUN_SETTINGS = "run_settings";
    public static final String SECTION_HOMEWORK_CACHE = "homework_cache";
    public static final String SECTION_RUN_HISTORY = "run_history";

    /** 一个存储键：属于哪个 SharedPreferences 文件、键名、值类型（见 {@link BackupValues}）。 */
    public static final class KeyRef {
        private final String prefs;
        private final String key;
        private final String type;

        KeyRef(String prefs, String key, String type) {
            this.prefs = prefs;
            this.key = key;
            this.type = type;
        }

        @NonNull
        public String prefs() {
            return prefs;
        }

        @NonNull
        public String key() {
            return key;
        }

        @NonNull
        public String type() {
            return type;
        }
    }

    /** 一段备份数据。 */
    public static final class Section {
        private final String id;
        private final int schema;
        private final int label;
        private final boolean defaultSelected;
        private final boolean keyed;
        private final List<KeyRef> keys;

        Section(String id, int schema, int label, boolean defaultSelected, boolean keyed,
                List<KeyRef> keys) {
            this.id = id;
            this.schema = schema;
            this.label = label;
            this.defaultSelected = defaultSelected;
            this.keyed = keyed;
            this.keys = Collections.unmodifiableList(keys);
        }

        @NonNull
        public String id() {
            return id;
        }

        public int schema() {
            return schema;
        }

        public int label() {
            return label;
        }

        /** 默认是否勾选。作业缓存能重新同步，默认不选。 */
        public boolean defaultSelected() {
            return defaultSelected;
        }

        /** true 表示这段是按条合并的数据（自建路线），其余段落按「本机一份状态」整体替换。 */
        public boolean keyed() {
            return keyed;
        }

        @NonNull
        public List<KeyRef> keys() {
            return keys;
        }
    }

    private static final List<Section> SECTIONS = Arrays.asList(
            new Section(SECTION_ROUTES, 1, R.string.backup_kind_routes, true, true,
                    Arrays.asList(new KeyRef("campus_run", "custom_routes", BackupValues.TYPE_STRING))),
            new Section(SECTION_ROUTE_FILES, 1, R.string.backup_kind_route_files, true, true,
                    Collections.<KeyRef>emptyList()),
            new Section(SECTION_MAP_POINTS, 1, R.string.backup_kind_map_points, true, true,
                    Arrays.asList(new KeyRef("map", "favorites", BackupValues.TYPE_STRING))),
            new Section(SECTION_TIMETABLE, 1, R.string.backup_kind_timetable, true, false,
                    Arrays.asList(
                            new KeyRef("study_timetable", "courses", BackupValues.TYPE_STRING),
                            new KeyRef("study_timetable", "season", BackupValues.TYPE_INT),
                            new KeyRef("study_timetable", "schedule_winter", BackupValues.TYPE_STRING),
                            new KeyRef("study_timetable", "schedule_summer", BackupValues.TYPE_STRING),
                            new KeyRef("study_timetable", "total_weeks", BackupValues.TYPE_INT),
                            new KeyRef("study_timetable", "current_week", BackupValues.TYPE_INT),
                            new KeyRef("study_timetable", "semester_start", BackupValues.TYPE_LONG),
                            new KeyRef("study_timetable", "day_adjustments",
                                    BackupValues.TYPE_STRING),
                            // 归档与当前指针跟课表同进同出，否则换机后只剩一个学期、其余凭空消失。
                            new KeyRef("study_semesters", "active_semester",
                                    BackupValues.TYPE_STRING),
                            new KeyRef("study_semesters", "archive", BackupValues.TYPE_STRING),
                            new KeyRef("study_timetable", "class_reminder_enabled", BackupValues.TYPE_BOOLEAN),
                            new KeyRef("study_timetable", "class_reminder_lead_minutes", BackupValues.TYPE_INT))),
            new Section(SECTION_HOMEWORK, 1, R.string.backup_kind_homework, true, false,
                    Arrays.asList(
                            new KeyRef("custom_homework", "items", BackupValues.TYPE_STRING),
                            new KeyRef("custom_homework", "homework_reminder_enabled",
                                    BackupValues.TYPE_BOOLEAN),
                            // 全局提前量跟着作业走，但它存在课表同一份偏好文件里。
                            new KeyRef("study_timetable", "homework_reminder_lead_minutes",
                                    BackupValues.TYPE_INT))),
            new Section(SECTION_SCHOOL, 1, R.string.backup_kind_school, true, false,
                    Arrays.asList(
                            new KeyRef("study_jw", "school_name", BackupValues.TYPE_STRING),
                            new KeyRef("study_jw", "school_login_url", BackupValues.TYPE_STRING))),
            new Section(SECTION_RUN_SETTINGS, 1, R.string.backup_kind_run_settings, true, false,
                    concat(
                            Arrays.asList(
                                    new KeyRef("campus_run", "sport", BackupValues.TYPE_STRING),
                                    new KeyRef("campus_run", "pace_seconds", BackupValues.TYPE_INT),
                                    new KeyRef("campus_run", "laps", BackupValues.TYPE_INT),
                                    new KeyRef("campus_run", "natural_speed", BackupValues.TYPE_BOOLEAN),
                                    new KeyRef("campus_run", "vary_strength", BackupValues.TYPE_FLOAT),
                                    new KeyRef("campus_run", "vary_path", BackupValues.TYPE_BOOLEAN),
                                    new KeyRef("campus_run", "selected_route_id", BackupValues.TYPE_STRING),
                                    new KeyRef("campus_run", "preset_revision", BackupValues.TYPE_INT),
                                    new KeyRef("campus_run", "preset_catalog_revision", BackupValues.TYPE_INT),
                                    new KeyRef("campus_run", "campus_owned", BackupValues.TYPE_BOOLEAN),
                                    new KeyRef("campus_run", "speed_presets_migrated", BackupValues.TYPE_BOOLEAN),
                                    new KeyRef("campus_run", "presets_unlocked", BackupValues.TYPE_BOOLEAN),
                                    new KeyRef("campus_run", "presets_added", BackupValues.TYPE_BOOLEAN),
                                    new KeyRef("campus_run", "preset_conflict_ask", BackupValues.TYPE_BOOLEAN),
                                    new KeyRef("campus_run", "preset_conflict_action", BackupValues.TYPE_STRING),
                                    new KeyRef("campus_run", "custom_points", BackupValues.TYPE_STRING)),
                            Arrays.asList(
                                    new KeyRef("ui", "night_mode", BackupValues.TYPE_INT),
                                    new KeyRef("ui", "overlay_enabled", BackupValues.TYPE_BOOLEAN),
                                    new KeyRef("ui", "overlay_x", BackupValues.TYPE_INT),
                                    new KeyRef("ui", "overlay_y", BackupValues.TYPE_INT),
                                    new KeyRef("ui", "overlay_gear", BackupValues.TYPE_INT),
                                    new KeyRef("ui", "overlay_scale", BackupValues.TYPE_FLOAT),
                                    new KeyRef("ui", "speed_preset_walk", BackupValues.TYPE_STRING),
                                    new KeyRef("ui", "speed_preset_run", BackupValues.TYPE_STRING),
                                    new KeyRef("ui", "speed_preset_ride", BackupValues.TYPE_STRING),
                                    new KeyRef("ui", "run_target_speed", BackupValues.TYPE_STRING),
                                    new KeyRef("ui", "gms_flp_enabled", BackupValues.TYPE_BOOLEAN)),
                            Arrays.asList(
                                    new KeyRef("map", "base_layer", BackupValues.TYPE_STRING),
                                    new KeyRef("diagnostics", "log_coordinates", BackupValues.TYPE_BOOLEAN)))),
            new Section(SECTION_HOMEWORK_CACHE, 1, R.string.backup_kind_homework_cache, false, false,
                    Arrays.asList(new KeyRef("study_homework_cache", "cache", BackupValues.TYPE_STRING))),
            // 跑步记录是纯本机历史，换机/重装后统计会归零，因此默认勾选进备份。
            new Section(SECTION_RUN_HISTORY, 1, R.string.backup_kind_run_history, true, false,
                    Arrays.asList(new KeyRef(RunHistoryStore.PREFS, RunHistoryStore.KEY_RECORDS,
                            BackupValues.TYPE_STRING))));

    /** 明确不进备份的存储文件，用于文档与自检用例。 */
    @NonNull
    public static List<String> excludedPrefs() {
        // study_auth 是学习通登录态（cookie/uid），app_update 是下载临时状态。
        return Arrays.asList("study_auth", "app_update");
    }

    /** 运行期状态键：恢复后不能假装「上次还在跑」，否则会与真实服务状态打架。 */
    @NonNull
    public static List<String> excludedKeys() {
        return Arrays.asList("active_run_id", "active_lap_meters", "period_minutes");
    }

    @NonNull
    public static List<Section> sections() {
        return SECTIONS;
    }

    @NonNull
    public static List<String> knownIds() {
        List<String> ids = new ArrayList<>();
        for (Section section : SECTIONS) {
            ids.add(section.id());
        }
        return ids;
    }

    public static Section byId(String id) {
        for (Section section : SECTIONS) {
            if (section.id().equals(id)) {
                return section;
            }
        }
        return null;
    }

    /** 该段导入时的默认合并策略：自建路线只补缺，其余整段替换。 */
    @NonNull
    public static BackupMerge.Policy policyOf(@NonNull String id) {
        Section section = byId(id);
        if (section == null || !section.keyed()) {
            return BackupMerge.Policy.REPLACE;
        }
        return BackupMerge.Policy.KEEP_MINE;
    }

    @SafeVarargs
    private static List<KeyRef> concat(List<KeyRef>... groups) {
        List<KeyRef> all = new ArrayList<>();
        for (List<KeyRef> group : groups) {
            all.addAll(group);
        }
        return all;
    }

    private BackupCatalog() {
    }
}
