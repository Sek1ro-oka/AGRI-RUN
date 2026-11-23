package org.localroute.app.study;

import org.json.JSONException;
import org.junit.Test;

import java.time.LocalDate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** 学期身份：名字规则与 JSON 容错。 */
public class SemesterProfileTest {

    @Test
    public void defaultNameReadsTheTermOffTheStartDate() {
        assertEquals("2026 秋季学期", SemesterProfile.defaultName(
                LocalDate.of(2026, 9, 7), 0));
        assertEquals("2027 春季学期", SemesterProfile.defaultName(
                LocalDate.of(2027, 2, 22), 1));
    }

    /**
     * 没填开学日期时只能按序号排名字，但序号必须跟着已有学期数走：
     * 归档里已经有两份数据时再冒出「学期 1」，列表里就有两行分不清谁是谁。
     */
    @Test
    public void unnamedSemestersAreNumberedPastTheExistingOnes() {
        assertEquals("学期 1", SemesterProfile.defaultName(null, 0));
        assertEquals("学期 3", SemesterProfile.defaultName(null, 2));
    }

    @Test
    public void namesAreTrimmedAndBounded() {
        assertEquals("短名字", SemesterProfile.create("  短名字  \n").name());
        StringBuilder longName = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            longName.append('很');
        }
        assertEquals(SemesterProfile.MAX_NAME_LENGTH,
                SemesterProfile.create(longName.toString()).name().length());
    }

    @Test
    public void blankNamesMakeAnInvalidProfile() {
        assertFalse(SemesterProfile.create("   ").isValid());
        assertFalse(new SemesterProfile("", "有名字").isValid());
        assertTrue(SemesterProfile.create("2026 秋").isValid());
    }

    @Test
    public void jsonRoundTripKeepsIdentity() throws JSONException {
        SemesterProfile profile = SemesterProfile.create("2026 秋");
        SemesterProfile back = SemesterProfile.fromJson(profile.toJson());
        assertEquals(profile, back);
        assertEquals(profile.id(), back.id());
    }

    @Test
    public void jsonRefusesMissingIdentity() throws JSONException {
        assertNull(SemesterProfile.fromJson(null));
        SemesterProfile noId = SemesterProfile.fromJson(
                new org.json.JSONObject().put("name", "没 id"));
        assertNull(noId);
        SemesterProfile noName = SemesterProfile.fromJson(
                new org.json.JSONObject().put("id", "abc").put("name", "  "));
        assertNull(noName);
    }

    @Test
    public void renamingKeepsTheIdSoSnapshotsStillMatch() {
        SemesterProfile before = SemesterProfile.create("旧名");
        SemesterProfile after = before.withName("新名");
        assertEquals(before.id(), after.id());
        assertEquals("新名", after.name());
        assertNotEquals(before, after);
        assertEquals(before.hashCode(), before.withName("旧名").hashCode());
    }

    @Test
    public void eachProfileGetsItsOwnId() {
        assertNotEquals(SemesterProfile.create("同名").id(), SemesterProfile.create("同名").id());
    }

    @Test
    public void trimmingIsAppliedOnRenameToo() {
        assertEquals("就这", SemesterProfile.create("x").withName("  就这  ").name());
    }
}
