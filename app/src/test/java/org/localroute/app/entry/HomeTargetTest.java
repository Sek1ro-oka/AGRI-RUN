package org.localroute.app.entry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 快捷入口的目标认法。这里最容易出错的是「串写错」：桌面菜单点了没反应，或跳到一个不相干的页面。
 */
public class HomeTargetTest {

    /**
     * 每个 {@code <shortcut>} 块里 {@code shortcutId} 在前、{@code <extra>} 在后，惰匹配正好把它们
     * 配成一对。用正则而不是 XML 解析，是为了不依赖解析器的命名空间处理，也方便把失败信息写成一行。
     */
    private static final Pattern SHORTCUT = Pattern.compile(
            "android:shortcutId=\"([a-z_]+)\""
                    + "[\\s\\S]*?<extra\\s+android:name=\"home_target\"\\s+android:value=\"([a-z_]+)\"");

    @Test
    public void everyKeyResolvesToItsOwnTarget() {
        for (HomeTarget target : HomeTarget.values()) {
            assertEquals(target, HomeTarget.from(target.key()));
        }
    }

    @Test
    public void anythingElseLeavesTheUserOnHome() {
        assertNull(HomeTarget.from(null));
        assertNull(HomeTarget.from(""));
        assertNull(HomeTarget.from("run "));
        // 大小写不同就是不同：宁可停在主页，也不要猜成 RUN。
        assertNull(HomeTarget.from("RUN"));
        assertNull(HomeTarget.from("Run"));
        assertNull(HomeTarget.from("settings"));
        assertNull(HomeTarget.from("org.localroute.app.ui.CampusRunActivity"));
    }

    @Test
    public void keysAreUnique() {
        Set<String> keys = new HashSet<>();
        for (HomeTarget target : HomeTarget.values()) {
            assertTrue("重复的 key: " + target.key(), keys.add(target.key()));
        }
    }

    /**
     * 清单外的这份 XML 与枚举必须逐条对上：改了 {@code HomeTarget} 忘了改 XML，
     * 桌面长按就只会把用户送回主页，而且看不出哪里坏了。
     */
    @Test
    public void shortcutsXmlDeclaresExactlyTheseTargets() throws IOException {
        File file = shortcutsXml();
        assertTrue("找不到 shortcuts.xml，测试工作目录是 " + file.getAbsolutePath(), file.isFile());
        String xml = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        Matcher matcher = SHORTCUT.matcher(xml);
        Set<String> declared = new LinkedHashSet<>();
        int blocks = 0;
        while (matcher.find()) {
            blocks++;
            assertEquals("shortcutId 与 extra 的值不一致: " + matcher.group(1) + " / "
                    + matcher.group(2), matcher.group(1), matcher.group(2));
            assertNotNull("XML 里有枚举认不出的目标: " + matcher.group(2),
                    HomeTarget.from(matcher.group(2)));
            declared.add(matcher.group(2));
        }
        assertEquals("每个目标都要有快捷入口", HomeTarget.values().length, blocks);
        assertEquals(HomeTarget.values().length, declared.size());
        for (HomeTarget target : HomeTarget.values()) {
            assertTrue("枚举有、XML 没有: " + target.key(), declared.contains(target.key()));
        }
    }

    private static File shortcutsXml() {
        File fromModule = new File("src/main/res/xml/shortcuts.xml");
        return fromModule.isFile() ? fromModule : new File("app/src/main/res/xml/shortcuts.xml");
    }
}
