package org.localroute.app.study;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.time.LocalDate;
import java.util.Locale;
import java.util.UUID;

/**
 * 一个学期的身份：内部 id + 用户看得懂的名字。
 *
 * <p>数据本身不挂在这里——课表、作息、开学日期等仍以「键值对快照」的形式按 id 存档
 * （{@link SemesterArchive}），所以改名、换学期都不会碰到课程条目，也就不会出现
 * 「改个名字把课表改坏了」这种代价极高的耦合。
 *
 * <p>纯 Java，可在 JVM 单元测试里断言名字规则与 JSON 容错。
 */
public final class SemesterProfile {

    /** 名字太长在选择器里会被挤掉，限到 20 个字（够写「2026-2027 秋季学期」）。 */
    public static final int MAX_NAME_LENGTH = 20;

    private final String id;
    private final String name;

    public SemesterProfile(@NonNull String id, @NonNull String name) {
        this.id = id;
        this.name = name;
    }

    @NonNull
    public static SemesterProfile create(@NonNull String name) {
        return new SemesterProfile(UUID.randomUUID().toString(), trim(name));
    }

    /**
     * 新用户第一次建学期时，旧数据得先登记成「一个学期」才谈得上切换。
     *
     * <p>名字从已经填过的开学日期推出来（那是用户自己的数据，不是编出来的）；
     * 没填开学日期时按序号排，至少两处不会重名。
     */
    @NonNull
    public static String defaultName(@Nullable LocalDate semesterStart, int existingCount) {
        if (semesterStart == null) {
            return "学期 " + (existingCount + 1);
        }
        String term = semesterStart.getMonthValue() <= 6 ? "春季学期" : "秋季学期";
        return String.format(Locale.US, "%d", semesterStart.getYear()) + " " + term;
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    @NonNull
    public SemesterProfile withName(String raw) {
        return new SemesterProfile(id, trim(raw));
    }

    /** 空名字没有意义：列表里两行都空白就没法分辨要切去哪。 */
    public boolean isValid() {
        return !id.isEmpty() && !name.isEmpty();
    }

    @NonNull
    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("id", id);
        json.put("name", name);
        return json;
    }

    @Nullable
    public static SemesterProfile fromJson(@Nullable JSONObject json) {
        if (json == null) {
            return null;
        }
        SemesterProfile profile = new SemesterProfile(json.optString("id", ""),
                trim(json.optString("name", "")));
        return profile.isValid() ? profile : null;
    }

    @NonNull
    private static String trim(@Nullable String raw) {
        String text = raw == null ? "" : raw.trim();
        return text.length() > MAX_NAME_LENGTH ? text.substring(0, MAX_NAME_LENGTH) : text;
    }

    @Override
    public boolean equals(@Nullable Object other) {
        if (!(other instanceof SemesterProfile)) {
            return false;
        }
        SemesterProfile that = (SemesterProfile) other;
        return that.id.equals(id) && that.name.equals(name);
    }

    @Override
    public int hashCode() {
        return (id + "|" + name).hashCode();
    }
}
