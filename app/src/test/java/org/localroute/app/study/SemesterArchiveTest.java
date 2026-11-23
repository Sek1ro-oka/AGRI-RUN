package org.localroute.app.study;

import org.junit.Test;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/** 学期归档：类型保真、单一数据源、坏数据不炸。 */
public class SemesterArchiveTest {

    /** 2026-09-07 是第 1 周的周一。 */
    private static final LocalDate MONDAY = LocalDate.of(2026, 9, 7);

    private static Map<String, Object> values(String coursesJson, Long start, Integer total) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (coursesJson != null) {
            values.put("courses", coursesJson);
        }
        if (start != null) {
            values.put("semester_start", start);
        }
        if (total != null) {
            values.put("total_weeks", total);
        }
        return values;
    }

    private static String twoCourses() {
        return TimetableCodec.encode(java.util.Arrays.asList(
                new TimetableCourse("a", "高等数学", null, null, 1, 1, 2, "1-16"),
                new TimetableCourse("b", "大学英语", null, null, 2, 3, 4, "1-16")));
    }

    @Test
    public void emptyArchiveKnowsNothing() {
        SemesterArchive archive = SemesterArchive.empty();
        assertTrue(archive.profiles().isEmpty());
        assertTrue(archive.values("nope").isEmpty());
        assertFalse(archive.contains("nope"));
        assertEquals(0, archive.courseCount("nope"));
        assertEquals("空课表", archive.summary("nope", MONDAY));
    }

    @Test
    public void profilesKeepCreationOrder() {
        SemesterProfile first = SemesterProfile.create("2026 秋");
        SemesterProfile second = SemesterProfile.create("2027 春");
        List<SemesterProfile> profiles = SemesterArchive.empty()
                .withProfile(first).withProfile(second).profiles();
        assertEquals(2, profiles.size());
        assertEquals(first.id(), profiles.get(0).id());
        assertEquals(second.id(), profiles.get(1).id());
    }

    @Test
    public void everyValueTypeSurvivesTheRoundTrip() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("a_string", "课表");
        values.put("an_int", 20);
        values.put("a_long", Long.MIN_VALUE + 7);
        values.put("a_float", 1.5f);
        values.put("a_bool", Boolean.TRUE);
        SemesterProfile profile = SemesterProfile.create("2026 秋");
        SemesterArchive back = SemesterArchive.decode(SemesterArchive.encode(
                SemesterArchive.empty().withProfile(profile).withValues(profile.id(), values)));
        Map<String, Object> restored = back.values(profile.id());
        assertEquals("课表", restored.get("a_string"));
        assertEquals(20, restored.get("an_int"));
        assertEquals(Long.MIN_VALUE + 7, restored.get("a_long"));
        assertEquals(1.5f, (Float) restored.get("a_float"), 0f);
        assertEquals(Boolean.TRUE, restored.get("a_bool"));
    }

    @Test
    public void courseCountAndWeekComeFromTheSnapshot() {
        SemesterProfile profile = SemesterProfile.create("2026 秋");
        SemesterArchive archive = SemesterArchive.empty().withProfile(profile)
                .withValues(profile.id(), values(twoCourses(), MONDAY.toEpochDay(), 20));
        assertEquals(2, archive.courseCount(profile.id()));
        assertEquals(1, archive.currentWeek(profile.id(), MONDAY));
        assertEquals(3, archive.currentWeek(profile.id(), MONDAY.plusWeeks(2)));
        assertEquals(0, archive.currentWeek(profile.id(), MONDAY.minusWeeks(1)));
        assertEquals("2 门课 · 第 3 周", archive.summary(profile.id(), MONDAY.plusWeeks(2)));
    }

    @Test
    public void summaryFallsBackWhenTheStartIsMissing() {
        SemesterProfile profile = SemesterProfile.create("2026 秋");
        SemesterArchive archive = SemesterArchive.empty().withProfile(profile)
                .withValues(profile.id(), values(twoCourses(), null, 20));
        assertEquals(0, archive.currentWeek(profile.id(), MONDAY));
        assertEquals("2 门课 · 未设开学日期", archive.summary(profile.id(), MONDAY));
    }

    @Test
    public void aSnapshotIsOverwrittenNotStacked() {
        SemesterProfile profile = SemesterProfile.create("2026 秋");
        SemesterArchive archive = SemesterArchive.empty().withProfile(profile)
                .withValues(profile.id(), values(twoCourses(), null, null))
                .withValues(profile.id(), values("[]", null, null));
        assertEquals(0, archive.courseCount(profile.id()));
    }

    @Test
    public void takingASnapshotOutDoesNotDeleteTheSemester() {
        SemesterProfile profile = SemesterProfile.create("2026 秋");
        SemesterArchive archive = SemesterArchive.empty().withProfile(profile)
                .withValues(profile.id(), values(twoCourses(), null, null)).withoutValues(profile.id());
        assertTrue(archive.contains(profile.id()));
        assertTrue(archive.values(profile.id()).isEmpty());
    }

    @Test
    public void deletingASemesterTakesItsSnapshotToo() {
        SemesterProfile kept = SemesterProfile.create("2026 秋");
        SemesterProfile gone = SemesterProfile.create("2027 春");
        SemesterArchive archive = SemesterArchive.empty()
                .withProfile(kept).withValues(kept.id(), values(twoCourses(), null, null))
                .withProfile(gone).withValues(gone.id(), values(twoCourses(), null, null))
                .without(gone.id());
        assertEquals(1, archive.profiles().size());
        assertTrue(archive.values(gone.id()).isEmpty());
        assertEquals(2, archive.courseCount(kept.id()));
    }

    @Test
    public void renamingKeepsTheDataWhereItWas() {
        SemesterProfile profile = SemesterProfile.create("旧名");
        SemesterArchive archive = SemesterArchive.empty().withProfile(profile)
                .withValues(profile.id(), values(twoCourses(), null, null))
                .withRenamed(profile.id(), "新名");
        assertEquals("新名", archive.profile(profile.id()).name());
        assertEquals(2, archive.courseCount(profile.id()));
    }

    @Test
    public void theCapRefusesOneMoreSemester() {
        SemesterArchive archive = SemesterArchive.empty();
        for (int i = 0; i < SemesterArchive.MAX_SEMESTERS + 3; i++) {
            archive = archive.withProfile(SemesterProfile.create("学期" + i));
        }
        assertEquals(SemesterArchive.MAX_SEMESTERS, archive.profiles().size());
    }

    @Test
    public void methodsDoNotMutateTheReceiver() {
        SemesterArchive original = SemesterArchive.empty();
        SemesterProfile profile = SemesterProfile.create("2026 秋");
        SemesterArchive added = original.withProfile(profile)
                .withValues(profile.id(), values(twoCourses(), null, null));
        assertTrue(original.profiles().isEmpty());
        assertEquals(1, added.profiles().size());
    }

    @Test
    public void corruptArchivesDecodeToNothingInsteadOfThrowing() {
        assertTrue(SemesterArchive.decode(null).profiles().isEmpty());
        assertTrue(SemesterArchive.decode("").profiles().isEmpty());
        assertTrue(SemesterArchive.decode("{不是数组}").profiles().isEmpty());
        // 缺 id 或名字空白的那条被丢掉，旁边的正常条目照收。
        String mixed = "[{\"name\":\"没id\",\"values\":{}},"
                + "{\"id\":\"x\",\"name\":\"\",\"values\":{}},"
                + "{\"id\":\"y\",\"name\":\"2026 秋\",\"values\":{\"courses\":[]}}]";
        SemesterArchive archive = SemesterArchive.decode(mixed);
        assertEquals(1, archive.profiles().size());
        assertEquals("y", archive.profiles().get(0).id());
    }

    @Test
    public void duplicateIdsOnDiskCollapseToOneEntry() {
        SemesterProfile profile = SemesterProfile.create("2026 秋");
        String doubled = "[{\"id\":\"" + profile.id() + "\",\"name\":\"2026 秋\",\"values\":{}},"
                + "{\"id\":\"" + profile.id() + "\",\"name\":\"重了一份\",\"values\":{}}]";
        SemesterArchive archive = SemesterArchive.decode(doubled);
        assertEquals(1, archive.profiles().size());
        assertNotEquals("重了一份", archive.profile(profile.id()).name());
    }

    @Test
    public void unknownSemesterProfilesAreReportedNotCrashed() {
        SemesterArchive archive = SemesterArchive.empty();
        assertEquals("未知学期", archive.profile("nope").name());
        assertEquals("", archive.profile("nope").id());
    }
}
