package org.localroute.app.study;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.localroute.app.backup.BackupValues;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 除当前学期以外，其余学期的「键值快照」集合。
 *
 * <p>存的是键值对而不是重新设计一套课表结构：当前学期照旧写在 {@code study_timetable} 的
 * 原有键上（所有读取路径一行都不用改），切换时把这份键值写进归档、把目标学期的键值还原回去。
 * 于是「多学期」只是同一套存储在不同名字之间倒手，旧数据不会因为换了结构而读不出来。
 *
 * <p>键值打包复用备份功能那套带类型标记的写法（{@link BackupValues}），int／long／boolean
 * 还原后仍是原类型，不会出现「开学日期存成 int 后越界」。
 *
 * <p>纯 Java，不可变；只在 JVM 单元测试里就能把整套行为断完。
 */
public final class SemesterArchive {

    /** 归档里最多留多少个学期：一学期一个，够用到毕业，再多说明是随手乱建的。 */
    public static final int MAX_SEMESTERS = 12;

    private static final String KEY_ID = "id";
    private static final String KEY_NAME = "name";
    private static final String KEY_VALUES = "values";

    private final List<SemesterProfile> profiles;
    private final Map<String, Map<String, Object>> snapshots;

    private SemesterArchive(List<SemesterProfile> profiles,
                            Map<String, Map<String, Object>> snapshots) {
        this.profiles = profiles;
        this.snapshots = snapshots;
    }

    @NonNull
    public static SemesterArchive empty() {
        return new SemesterArchive(new ArrayList<>(), new LinkedHashMap<>());
    }

    @NonNull
    public List<SemesterProfile> profiles() {
        return Collections.unmodifiableList(profiles);
    }

    public boolean contains(@Nullable String id) {
        if (id == null) {
            return false;
        }
        for (SemesterProfile profile : profiles) {
            if (profile.id().equals(id)) {
                return true;
            }
        }
        return false;
    }

    /** 这学期的键值快照；没有存过就是空表（新学期正是空表，等于一份干净课表）。 */
    @NonNull
    public Map<String, Object> values(@Nullable String id) {
        Map<String, Object> values = id == null ? null : snapshots.get(id);
        return values == null ? Collections.emptyMap() : values;
    }

    @NonNull
    public SemesterProfile profile(@Nullable String id) {
        for (SemesterProfile profile : profiles) {
            if (profile.id().equals(id)) {
                return profile;
            }
        }
        return new SemesterProfile("", "未知学期");
    }

    /** 追加一个学期；超过 {@link #MAX_SEMESTERS} 时不接纳，调用方据此提示用户。 */
    @NonNull
    public SemesterArchive withProfile(@NonNull SemesterProfile profile) {
        if (contains(profile.id()) || profiles.size() >= MAX_SEMESTERS) {
            return this;
        }
        List<SemesterProfile> next = new ArrayList<>(profiles);
        next.add(profile);
        return new SemesterArchive(next, copy(snapshots));
    }

    @NonNull
    public SemesterArchive withRenamed(@NonNull String id, @NonNull String name) {
        List<SemesterProfile> next = new ArrayList<>();
        for (SemesterProfile profile : profiles) {
            next.add(profile.id().equals(id) ? profile.withName(name) : profile);
        }
        return new SemesterArchive(next, copy(snapshots));
    }

    /** 把这份键值当成「这学期的最新状态」存下来（覆盖旧快照）。 */
    @NonNull
    public SemesterArchive withValues(@NonNull String id,
                                      @NonNull Map<String, Object> values) {
        Map<String, Map<String, Object>> next = copy(snapshots);
        next.put(id, new LinkedHashMap<>(values));
        return new SemesterArchive(profiles, next);
    }

    /**
     * 快照已经还原到本机偏好文件里了，归档再留一份就成了两个真数据源——
     * 下一次切走时会拿旧的那份覆盖掉用户在新学期里改的东西，所以必须清掉。
     */
    @NonNull
    public SemesterArchive withoutValues(@NonNull String id) {
        Map<String, Map<String, Object>> next = copy(snapshots);
        next.remove(id);
        return new SemesterArchive(profiles, next);
    }

    /** 删掉整个学期，连它的快照一起。 */
    @NonNull
    public SemesterArchive without(@NonNull String id) {
        List<SemesterProfile> nextProfiles = new ArrayList<>();
        for (SemesterProfile profile : profiles) {
            if (!profile.id().equals(id)) {
                nextProfiles.add(profile);
            }
        }
        Map<String, Map<String, Object>> nextValues = copy(snapshots);
        nextValues.remove(id);
        return new SemesterArchive(nextProfiles, nextValues);
    }

    /** 这学期有几门课；读不出来当作没有，绝不在这里报错打断列表。 */
    public int courseCount(@NonNull String id) {
        Object raw = values(id).get(StudyTimetableStore.KEY_COURSES);
        return raw instanceof String ? TimetableCodec.decode((String) raw).size() : 0;
    }

    /** 这学期第几周；没设开学日期或已经不在学期内时为 0。 */
    public int currentWeek(@NonNull String id, @NonNull LocalDate today) {
        Object start = values(id).get(StudyTimetableStore.KEY_SEMESTER_START);
        Object total = values(id).get(StudyTimetableStore.KEY_TOTAL_WEEKS);
        if (!(start instanceof Long) || !(total instanceof Integer)) {
            return 0;
        }
        return SemesterCalendar.teachingWeek(LocalDate.ofEpochDay((Long) start), today,
                (Integer) total);
    }

    /** 列表里跟在名字后面的那句：「12 门课 · 第 3 周」「空课表」「6 门课 · 未设开学日期」。 */
    @NonNull
    public String summary(@NonNull String id, @NonNull LocalDate today) {
        int courses = courseCount(id);
        if (courses == 0) {
            return "空课表";
        }
        int week = currentWeek(id, today);
        return week > 0 ? courses + " 门课 · 第 " + week + " 周" : courses + " 门课 · 未设开学日期";
    }

    @NonNull
    public static String encode(@NonNull SemesterArchive archive) {
        JSONArray array = new JSONArray();
        for (SemesterProfile profile : archive.profiles) {
            try {
                JSONObject item = profile.toJson();
                item.put(KEY_VALUES, BackupValues.pack(archive.snapshots.get(profile.id())));
                array.put(item);
            } catch (JSONException ignored) {
                // 只写字符串、整数与已经打好的包，正常数据不会走到这里。
            }
        }
        return array.toString();
    }

    /** 整体坏了当作「还没有第二个学期」，坏一条丢一条：切不了学期比丢掉归档更可预期。 */
    @NonNull
    public static SemesterArchive decode(@Nullable String json) {
        List<SemesterProfile> profiles = new ArrayList<>();
        Map<String, Map<String, Object>> snapshots = new LinkedHashMap<>();
        if (json == null || json.isEmpty()) {
            return new SemesterArchive(profiles, snapshots);
        }
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length() && profiles.size() < MAX_SEMESTERS; i++) {
                JSONObject item = array.optJSONObject(i);
                SemesterProfile profile = SemesterProfile.fromJson(item);
                if (profile == null || snapshots.containsKey(profile.id())) {
                    continue;
                }
                profiles.add(profile);
                snapshots.put(profile.id(),
                        BackupValues.unpack(item == null ? null : item.optJSONObject(
                                KEY_VALUES)));
            }
        } catch (JSONException ignored) {
            return new SemesterArchive(new ArrayList<>(), new LinkedHashMap<>());
        }
        return new SemesterArchive(profiles, snapshots);
    }

    private static Map<String, Map<String, Object>> copy(
            Map<String, Map<String, Object>> source) {
        Map<String, Map<String, Object>> next = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> entry : source.entrySet()) {
            next.put(entry.getKey(), new LinkedHashMap<>(entry.getValue()));
        }
        return next;
    }
}
