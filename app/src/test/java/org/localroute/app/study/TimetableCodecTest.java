package org.localroute.app.study;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** 课表 JSON 编解码与课程有效性。 */
public class TimetableCodecTest {

    @Test
    public void roundTripsCourses() {
        List<TimetableCourse> courses = new ArrayList<>();
        courses.add(TimetableCourse.create("高等数学", "张老师", "教一101",
                1, 1, 2, "1-16"));
        courses.add(TimetableCourse.create("大学英语", "李老师", "",
                3, 3, 4, "1-8"));

        String json = TimetableCodec.encode(courses);
        List<TimetableCourse> decoded = TimetableCodec.decode(json);
        assertEquals(2, decoded.size());
        TimetableCourse math = decoded.get(0);
        assertEquals("高等数学", math.name());
        assertEquals("张老师", math.teacher());
        assertEquals("教一101", math.location());
        assertEquals(1, math.weekday());
        assertEquals(1, math.startPeriod());
        assertEquals(2, math.endPeriod());
        assertEquals("1-16", math.weeks());
        assertEquals("周一", math.weekdayLabel());
        assertEquals("第1-2节", math.periodLabel());
    }

    @Test
    public void skipsInvalidCourses() {
        List<TimetableCourse> courses = new ArrayList<>();
        courses.add(TimetableCourse.create("", "", "", 1, 1, 2, "1-16"));
        courses.add(TimetableCourse.create("体育", "", "", 8, 1, 2, "1-16"));
        courses.add(TimetableCourse.create("物理", "", "", 2, 5, 4, "1-16"));
        assertEquals("[]", TimetableCodec.encode(courses));

        TimetableCourse valid = TimetableCourse.create("物理", "", "", 2, 4, 5, "1-16");
        assertTrue(valid.isValid());
        List<TimetableCourse> decoded = TimetableCodec.decode(
                TimetableCodec.encode(List.of(valid)));
        assertEquals(1, decoded.size());
        assertFalse(decoded.get(0).name().isEmpty());
    }

    @Test
    public void toleratesCorruptStorage() {
        assertTrue(TimetableCodec.decode(null).isEmpty());
        assertTrue(TimetableCodec.decode("").isEmpty());
        assertTrue(TimetableCodec.decode("not-json").isEmpty());
        assertTrue(TimetableCodec.decode("[{\"name\":\"\"}]").isEmpty());
        assertNull(TimetableCourse.fromJson(null));
    }
}
