package org.localroute.app.campus;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** 预设导入的重名判断：忽略首尾空白与大小写，同 id 与空名称不算冲突。 */
public class PresetConflictsTest {

    private static CampusRoute route(String id, String name) {
        List<CampusPoint> points = new ArrayList<>(Arrays.asList(
                new CampusPoint(0, 0),
                new CampusPoint(20, 0),
                new CampusPoint(20, 10),
                new CampusPoint(0, 10)));
        return CampusRoute.custom(id, name, points);
    }

    @Test
    public void sameNameIgnoresCaseAndSurroundingSpaces() {
        assertTrue(PresetConflicts.sameName("衣锦", " 衣锦 "));
        assertTrue(PresetConflicts.sameName("A操", "a操"));
        assertFalse(PresetConflicts.sameName("A操", "B操"));
        assertFalse(PresetConflicts.sameName("", "  "));
        assertFalse(PresetConflicts.sameName(null, "衣锦"));
    }

    @Test
    public void findsOnlyDifferentIdsWithSameName() {
        CampusRoute preset = CampusPresets.routeYijin();
        CampusRoute custom = route("custom-1", " 衣锦 ");
        CampusRoute other = route("custom-2", "A操");
        List<CampusRoute> routes = Arrays.asList(custom, other);

        List<CampusRoute> conflicts = PresetConflicts.findBySameName(routes, preset);
        assertEquals(1, conflicts.size());
        assertEquals("custom-1", conflicts.get(0).id());
        assertTrue(PresetConflicts.contains(conflicts, "custom-1"));
        assertFalse(PresetConflicts.contains(conflicts, "custom-2"));
    }

    @Test
    public void sameIdIsNotAConflict() {
        CampusRoute preset = CampusPresets.routeYijin();
        List<CampusRoute> routes = Arrays.asList(
                CampusRoute.preset(preset.id(), preset.name(), preset.points()));
        assertTrue(PresetConflicts.findBySameName(routes, preset).isEmpty());
    }

    @Test
    public void emptyNamesDoNotConflict() {
        CampusRoute preset = CampusRoute.preset("preset_x", "", CampusPresets.routeA().points());
        List<CampusRoute> routes = Arrays.asList(route("custom-1", ""));
        assertTrue(PresetConflicts.findBySameName(routes, preset).isEmpty());
    }

    @Test
    public void allPresetsHaveUniqueNamesAndSaneLaps() {
        List<CampusRoute> presets = CampusPresets.all();
        assertEquals(3, presets.size());
        for (int i = 0; i < presets.size(); i++) {
            CampusRoute preset = presets.get(i);
            assertTrue("预设名称不能为空", !preset.name().trim().isEmpty());
            assertTrue("预设必须是闭合路线", preset.points().size() >= 100);
            assertTrue("预设圈长异常：" + preset.lapMeters(),
                    preset.lapMeters() > 120d && preset.lapMeters() < 800d);
            for (int j = i + 1; j < presets.size(); j++) {
                assertFalse("预设名称重复：" + preset.name(),
                        PresetConflicts.sameName(preset.name(), presets.get(j).name()));
            }
        }
        assertEquals("preset_yijin", presets.get(2).id());
        assertEquals("衣锦", presets.get(2).name());
        assertTrue("衣锦单圈应接近 432 米：" + presets.get(2).lapMeters(),
                Math.abs(presets.get(2).lapMeters() - 432d) < 5d);
    }
}
