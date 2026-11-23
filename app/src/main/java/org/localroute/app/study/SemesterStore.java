package org.localroute.app.study;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 多学期的本机存储：当前学期照旧写在 {@code study_timetable} 上，其余学期按 id 归档在
 * {@code study_semesters} 里。
 *
 * <p>之所以敢让「当前学期」继续用老键，是因为所有读取路径（课表页、小组件、课前提醒、导出）
 * 一行都不用改就天然只看当前学期；切换动作则是纯粹的倒手——把现在这份键值存进归档，
 * 再把目标那份还原回来。旧版本升上来的用户属于「还没有第二个学期」，数据一个字都不动。
 *
 * <p>这里不碰闹钟也不碰小组件重绘：还原会写偏好文件、监听器本来就管刷新，
 * 剩下的重排由界面在切完之后统一调用，免得课表包反过来依赖组件包。
 */
public final class SemesterStore {

    /** 归档单独一份偏好文件：动课表不会碰它，动它也不会让课表监听器误以为课表变了。 */
    public static final String PREFS = "study_semesters";
    private static final String KEY_ACTIVE = "active_semester";
    private static final String KEY_ARCHIVE = "archive";

    private SemesterStore() {
    }

    @NonNull
    public static List<SemesterProfile> profiles(@NonNull Context context) {
        return archive(context).profiles();
    }

    /** 当前学期；为 {@code null} 表示还没分过学期，本机那份数据就是唯一的一学期。 */
    @Nullable
    public static SemesterProfile active(@NonNull Context context) {
        return find(context, activeId(context));
    }

    @Nullable
    public static String activeId(@NonNull Context context) {
        String id = prefs(context).getString(KEY_ACTIVE, null);
        return id == null || id.isEmpty() ? null : id;
    }

    @Nullable
    public static SemesterProfile find(@NonNull Context context, @Nullable String id) {
        if (id == null) {
            return null;
        }
        for (SemesterProfile profile : profiles(context)) {
            if (profile.id().equals(id)) {
                return profile;
            }
        }
        return null;
    }

    /** 装得下几个学期；满了就不该再让用户点「新建」。 */
    public static boolean canCreate(@NonNull Context context) {
        return profiles(context).size() < SemesterArchive.MAX_SEMESTERS;
    }

    /**
     * 新建一个空学期。
     *
     * <p>第一次新建时，本机原来那份数据先登记成学期（名字从开学日期推）并归档，
     * 否则它没有身份、切回来时无从恢复。新学期不带任何键值，等于一份干净课表。
     *
     * @return 新学期的 id
     */
    @NonNull
    public static SemesterProfile create(@NonNull Context context, @NonNull String name) {
        SemesterArchive archive = archive(context);
        String currentId = activeId(context);
        if (currentId == null) {
            // 原来那份数据还没有身份：先按开学日期给它一个名字并归档，否则切回来无从恢复。
            SemesterProfile implicit = SemesterProfile.create(SemesterProfile.defaultName(
                    StudyTimetableStore.loadSemesterStart(context), archive.profiles().size()));
            archive = archive.withProfile(implicit)
                    .withValues(implicit.id(), StudyTimetableStore.snapshotValues(context));
            currentId = implicit.id();
        } else {
            archive = archive.withValues(currentId,
                    StudyTimetableStore.snapshotValues(context));
        }
        SemesterProfile created = SemesterProfile.create(name);
        save(context, archive.withProfile(created), currentId);
        return created;
    }

    /**
     * 切到另一个学期：现在这份先存进归档，目标那份还原到本机。
     *
     * <p>已经在这学期时只补一次快照就返回，否则「存了再取回来」中间那一步会把数据清干净。
     * 归档里没有这个 id 也直接返回——凭空切到一个不存在的学期，等于把课表清空，不该默默发生。
     * 目标的快照取走后就从归档里删掉，同一份数据不留两个真来源。
     */
    public static void switchTo(@NonNull Context context, @NonNull String id) {
        SemesterArchive archive = archive(context);
        String currentId = activeId(context);
        if (currentId == null) {
            SemesterProfile registered = SemesterProfile.create(SemesterProfile.defaultName(
                    StudyTimetableStore.loadSemesterStart(context), archive.profiles().size()));
            archive = archive.withProfile(registered);
            currentId = registered.id();
        }
        if (id.equals(currentId)) {
            save(context, archive.withValues(currentId,
                    StudyTimetableStore.snapshotValues(context)), currentId);
            return;
        }
        if (!archive.contains(id)) {
            return;
        }
        archive = archive.withValues(currentId, StudyTimetableStore.snapshotValues(context));
        Map<String, Object> target = archive.values(id);
        archive = archive.withoutValues(id);
        StudyTimetableStore.restoreValues(context, target);
        save(context, archive, id);
    }

    @NonNull
    public static SemesterArchive rename(@NonNull Context context, @NonNull String id,
                                         @NonNull String name) {
        SemesterArchive archive = archive(context).withRenamed(id, name);
        save(context, archive, activeId(context));
        return archive;
    }

    /** 当前学期不能删：删了本机这份数据就没有归属了。 */
    public static boolean delete(@NonNull Context context, @NonNull String id) {
        if (id.equals(activeId(context))) {
            return false;
        }
        SemesterArchive archive = archive(context).without(id);
        save(context, archive, activeId(context));
        return true;
    }

    /** 这学期有几门课：当前学期看本机，其它看归档。 */
    public static int courseCount(@NonNull Context context, @NonNull String id) {
        if (id.equals(activeId(context))) {
            return StudyTimetableStore.loadCourses(context).size();
        }
        return archive(context).courseCount(id);
    }

    @NonNull
    public static String summary(@NonNull Context context, @NonNull String id,
                                 @NonNull LocalDate today) {
        SemesterArchive archive = archive(context);
        if (id.equals(activeId(context))) {
            Map<String, Object> live = StudyTimetableStore.snapshotValues(context);
            return archive.withValues(id, live).summary(id, today);
        }
        return archive.summary(id, today);
    }

    /** 归档里没登记过、但本机已经有数据的那个学期：只在第一次分学期时才需要补上身份。 */
    @NonNull
    private static SemesterArchive archive(@NonNull Context context) {
        return SemesterArchive.decode(prefs(context).getString(KEY_ARCHIVE, "[]"));
    }

    private static void save(@NonNull Context context, @NonNull SemesterArchive archive,
                             @Nullable String activeId) {
        SharedPreferences.Editor editor = prefs(context).edit()
                .putString(KEY_ARCHIVE, SemesterArchive.encode(archive));
        if (activeId == null) {
            editor.remove(KEY_ACTIVE);
        } else {
            editor.putString(KEY_ACTIVE, activeId);
        }
        editor.apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
