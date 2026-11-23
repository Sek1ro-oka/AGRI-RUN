package org.localroute.app.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 尺度自检：布局只能引用 {@code res/values/dimens.xml} 里的令牌，可点击控件不得低于 48dp。
 *
 * <p>这些规则纯靠读源文件成立，跑不了真机也能拦住「随手写个 13dp」的下一次回潮。
 * 小组件布局（{@code widget_*.xml}）例外：它的可用高度是桌面格子给的，
 * 不跟应用内的尺度缩放。
 */
public class UiScaleTest {

    private static final Pattern ELEMENT = Pattern.compile("<[A-Za-z][\\w.]*[^>]*>", Pattern.DOTALL);
    private static final Pattern ATTRIBUTE = Pattern.compile("([\\w.]+)=\"([^\"]*)\"");
    private static final Pattern DP = Pattern.compile("^(\\d+(?:\\.\\d+)?)dp$");
    private static final Pattern SP = Pattern.compile("^(\\d+(?:\\.\\d+)?)sp$");

    /** 带这些前缀的样式自己保证了触摸高度，布局里就不必再写死尺寸。 */
    private static final List<String> SELF_SIZED_STYLE_PREFIXES =
            java.util.Arrays.asList("@style/Widget.Unloop.", "@style/Widget.Material3.Button");

    private static final Pattern SPACE_ATTR =
            Pattern.compile("android:(padding|layout_margin)(Top|Bottom|Left|Right|Start|End|Horizontal|Vertical)?");

    private static final class Element {
        private final String file;
        private final String tag;
        private final Map<String, String> attrs = new LinkedHashMap<>();

        Element(String file, String raw) {
            this.file = file;
            Matcher name = Pattern.compile("<([A-Za-z][\\w.]*)").matcher(raw);
            tag = name.find() ? name.group(1) : raw;
            Matcher m = ATTRIBUTE.matcher(raw);
            while (m.find()) {
                attrs.put(m.group(1), m.group(2));
            }
        }

        boolean clickable() {
            return "true".equals(attrs.get("android:clickable"))
                    || tag.endsWith("Button") || tag.endsWith("ImageButton")
                    || tag.endsWith("CheckBox") || tag.endsWith("Switch")
                    || tag.endsWith("MaterialCardView");
        }

        boolean selfSized() {
            String style = attrs.get("style");
            if (style == null) {
                return false;
            }
            for (String prefix : SELF_SIZED_STYLE_PREFIXES) {
                if (style.startsWith(prefix)) {
                    return true;
                }
            }
            return false;
        }

        /** 固定尺寸（dp）；wrap_content/match_parent/0dp 这类交给内容或约束，返回 -1。 */
        int fixed(String attr) {
            String value = attrs.get(attr);
            if (value == null) {
                return -1;
            }
            Matcher m = DP.matcher(value);
            if (!m.matches()) {
                return -1;
            }
            double dp = Double.parseDouble(m.group(1));
            return dp == 0 ? -1 : (int) Math.ceil(dp);
        }

        int minHeight() {
            int h = fixed("android:minHeight");
            return h < 0 ? fixed("android:layout_height") : h;
        }

        @Override
        public String toString() {
            return file + " <" + tag + " " + attrs.getOrDefault("android:id", "(无 id)") + ">";
        }
    }

    private static List<Element> layoutElements() throws IOException {
        List<Element> all = new ArrayList<>();
        for (File file : layoutFiles()) {
            if (file.getName().startsWith("widget_")) {
                continue;
            }
            String xml = read(file);
            Matcher m = ELEMENT.matcher(xml);
            while (m.find()) {
                all.add(new Element(file.getName(), m.group()));
            }
        }
        assertFalse("布局解析为空，测试工作目录是 " + dir("res").getAbsolutePath(), all.isEmpty());
        return all;
    }

    private static String read(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static File[] layoutFiles() {
        File dir = dir("res/layout");
        File[] files = dir.listFiles((d, name) -> name.endsWith(".xml"));
        assertTrue("找不到 res/layout：" + dir.getAbsolutePath(), files != null && files.length > 0);
        return files;
    }

    /** 工作目录可能是模块目录，也可能是仓库根，两处都试。 */
    private static File dir(String relativeUnderRes) {
        File fromModule = new File("src/main/" + relativeUnderRes);
        return fromModule.isDirectory() ? fromModule : new File("app/src/main/" + relativeUnderRes);
    }

    private static Map<String, String> dimens() throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        Matcher m = Pattern.compile("<dimen name=\"([^\"]+)\">([^<]+)</dimen>")
                .matcher(read(new File(dir("res/values"), "dimens.xml")));
        while (m.find()) {
            values.put(m.group(1), m.group(2).trim());
        }
        assertFalse("dimens.xml 没解析到令牌", values.isEmpty());
        return values;
    }

    @Test
    public void layoutsDefineNoBareTextSizes() throws IOException {
        for (Element element : layoutElements()) {
            String size = element.attrs.get("android:textSize");
            if (size != null) {
                assertFalse(element + " 直接写了字号 " + size + "，请用 @dimen/text_* 或文字角色样式",
                        SP.matcher(size).matches());
            }
        }
    }

    @Test
    public void layoutsDefineNoBareSpacing() throws IOException {
        for (Element element : layoutElements()) {
            for (Map.Entry<String, String> attr : element.attrs.entrySet()) {
                if (!SPACE_ATTR.matcher(attr.getKey()).matches()) {
                    continue;
                }
                Matcher value = DP.matcher(attr.getValue());
                boolean literal = value.matches() && Double.parseDouble(value.group(1)) > 0;
                assertFalse(element + " 的 " + attr.getKey() + " 写成了 " + attr.getValue()
                        + "，请用 @dimen/space_*", literal);
            }
        }
    }

    @Test
    public void clickableControlsReachTheTouchFloor() throws IOException {
        for (Element element : layoutElements()) {
            if (!element.clickable()) {
                continue;
            }
            int width = element.fixed("android:layout_width");
            int height = element.minHeight();
            assertTrue(element + " 宽 " + width + "dp，点不准的按钮等于让人多点几次",
                    width < 0 || width >= 48 || element.selfSized());
            assertTrue(element + " 高 " + height + "dp，点不准的按钮等于让人多点几次",
                    height < 0 || height >= 48 || element.selfSized());
        }
    }

    @Test
    public void iconButtonsKeepATouchSizedBox() throws IOException {
        boolean seen = false;
        for (Element element : layoutElements()) {
            if (!element.tag.endsWith("ImageButton")) {
                continue;
            }
            seen = true;
            assertTrue(element + " 的图标盒不足 48dp",
                    element.minHeight() < 0 || element.minHeight() >= 48 || element.selfSized());
        }
        assertTrue("布局里一个 ImageButton 都没有，解析八成错了", seen);
    }

    @Test
    public void everyDimenReferenceExists() throws IOException {
        Map<String, String> defined = dimens();
        List<String> missing = new ArrayList<>();
        for (File file : layoutFiles()) {
            Matcher m = Pattern.compile("@dimen/([A-Za-z0-9_]+)").matcher(read(file));
            while (m.find() && !defined.containsKey(m.group(1))) {
                missing.add(file.getName() + " -> " + m.group(1));
            }
        }
        assertTrue("引用了未定义的尺度令牌：" + missing, missing.isEmpty());
    }

    @Test
    public void textLadderIsMonotonicAndReadable() {
        Map<String, String> defined = new LinkedHashMap<>();
        try {
            defined.putAll(dimens());
        } catch (IOException e) {
            throw new AssertionError("读不到 dimens.xml: " + e, e);
        }
        int previous = 0;
        int steps = 0;
        for (Map.Entry<String, String> entry : defined.entrySet()) {
            Matcher m = SP.matcher(entry.getValue());
            if (!entry.getKey().startsWith("text_") || !m.matches()) {
                continue;
            }
            int sp = Integer.parseInt(m.group(1));
            assertTrue("最小字号只有 " + sp + "sp，读不动的说明等于没写", sp >= 11);
            assertTrue("字号阶梯必须逐级变大：" + entry.getKey() + " = " + sp, sp > previous);
            previous = sp;
            steps++;
        }
        assertTrue("字号阶梯解析到的档数不对：" + steps, steps >= 8);
    }

    @Test
    public void touchFloorIsTheStandard48() throws IOException {
        assertTrue(dimens().get("touch_min").startsWith("48"));
    }

    /** 令牌一旦没人用就成了下一个 finmori_dimens：看着像尺度，其实是死代码。 */
    @Test
    public void everyScaleTokenIsUsed() throws IOException {
        StringBuilder haystack = new StringBuilder();
        for (File file : textFiles(dir(""))) {
            haystack.append(read(file));
        }
        String joined = haystack.toString();
        assertTrue("读不到源码，测不了引用", joined.length() > 1000);
        List<String> dead = new ArrayList<>();
        for (String name : dimens().keySet()) {
            if (!joined.contains("@dimen/" + name) && !joined.contains("R.dimen." + name)) {
                dead.add(name);
            }
        }
        assertTrue("dimens.xml 里没人用的令牌：" + dead, dead.isEmpty());
    }

    private static List<File> textFiles(File root) {
        List<File> files = new ArrayList<>();
        File[] children = root.listFiles();
        if (children == null) {
            return files;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                files.addAll(textFiles(child));
            } else if (child.getName().endsWith(".xml") || child.getName().endsWith(".java")) {
                files.add(child);
            }
        }
        return files;
    }

    @Test
    public void buttonStylesCarryTheTouchFloor() throws IOException {
        File values = new File(dir("res/values"), "styles.xml");
        String xml = read(values);
        assertTrue("Widget.Unloop.Button 必须自带 48dp 下限",
                xml.contains("name=\"Widget.Unloop.Button\""));
        assertTrue("按钮样式要保证触摸下限",
                xml.contains("android:minHeight\">@dimen/touch_min"));
    }
}
