package org.localroute.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** GPX 1.1 导出与解析（转义、容错、跳过非法点、点数上限、XXE 防护）的单元测试。 */
public class GpxCodecTest {

    private static final double COORDINATE_TOLERANCE = 1e-6d;

    private static List<GeoPoint> samplePoints() {
        // 不使用任何真实地点：以 (0,0) 附近的小方框覆盖正负经纬度。
        return Arrays.asList(
                new GeoPoint(30.123456d, 120.654321d),
                new GeoPoint(-0.000001d, 179.999999d),
                new GeoPoint(45.500000d, -73.250000d));
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int index = text.indexOf(needle);
        while (index >= 0) {
            count++;
            index = text.indexOf(needle, index + needle.length());
        }
        return count;
    }

    // ------------------------------------------------------------ 导出

    @Test
    public void exportThenParse_roundTripsWithinOneMillionthDegree() throws Exception {
        List<GeoPoint> points = samplePoints();
        GpxCodec.ParseResult result = GpxCodec.parse(GpxCodec.export(points, "示例方形路线"));

        assertEquals(points.size(), result.points().size());
        assertTrue("自产文件不应有警告", result.warnings().isEmpty());
        assertEquals(0, result.droppedPointCount());
        for (int i = 0; i < points.size(); i++) {
            assertEquals(points.get(i).latitude(), result.points().get(i).latitude(), COORDINATE_TOLERANCE);
            assertEquals(points.get(i).longitude(), result.points().get(i).longitude(), COORDINATE_TOLERANCE);
        }
    }

    @Test
    public void export_writesStandardGpx11HeaderAndOneTrkptPerPoint() {
        String xml = GpxCodec.export(samplePoints(), "abc");

        assertTrue(xml.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"));
        assertTrue(xml.contains("<gpx version=\"1.1\""));
        assertTrue(xml.contains("creator=\"" + GpxCodec.CREATOR + "\""));
        assertTrue(xml.contains("xmlns=\"" + GpxCodec.NAMESPACE_GPX_11 + "\""));
        assertTrue(xml.contains("<trk>"));
        assertTrue(xml.contains("<name>abc</name>"));
        assertTrue(xml.contains("<trkseg>"));
        assertEquals("每个点一个 trkpt", 3, countOccurrences(xml, "<trkpt "));
        assertTrue(xml.contains("lat=\"30.123456\" lon=\"120.654321\""));
        assertTrue("坐标必须是 6 位小数", xml.contains("lat=\"-0.000001\" lon=\"179.999999\""));
        assertTrue(xml.lastIndexOf("</gpx>") > xml.indexOf("</trkseg>"));
    }

    @Test
    public void export_escapesXmlSpecialCharactersInName() throws Exception {
        String name = "工地 & 河边 <A> \"B\" 'C'";
        String xml = GpxCodec.export(Collections.singletonList(new GeoPoint(30d, 120d)), name);

        assertTrue(xml.contains("工地 &amp; 河边 &lt;A&gt; &quot;B&quot; &apos;C&apos;"));
        assertFalse("原始尖括号不应出现在输出里", xml.contains("<A>"));
        // 转义后的名字仍然是一份良构 GPX，能被自己读回来。
        assertEquals(1, GpxCodec.parse(xml).points().size());
    }

    @Test
    public void export_nullOrEmptyInput_stillProducesWellFormedButEmptyTrack() {
        String xml = GpxCodec.export(null, null);

        assertTrue(xml.contains("<name>未命名路线</name>"));
        assertTrue(xml.contains("<trkseg>"));
        assertTrue(xml.endsWith("</gpx>\n"));
        assertEquals(0, countOccurrences(xml, "<trkpt "));
        // 没有点的 GPX 不算可用输入：交给调用方提示，而不是返回空列表。
        assertThrows(InvalidRouteException.class, () -> GpxCodec.parse(xml));
    }

    // ------------------------------------------------------------ 解析：宽容度

    @Test
    public void parse_acceptsMixedTrkptRteptAndWptInDocumentOrder() throws Exception {
        String xml = "<gpx version=\"1.1\">"
                + "<wpt lat=\"1.1\" lon=\"2.1\"/>"
                + "<rte><rtept lat=\"1.2\" lon=\"2.2\"/></rte>"
                + "<trk><trkseg><trkpt lat=\"1.3\" lon=\"2.3\"/></trkseg></trk>"
                + "</gpx>";

        GpxCodec.ParseResult result = GpxCodec.parse(xml);

        assertEquals(3, result.points().size());
        assertEquals(1.1d, result.points().get(0).latitude(), 1e-9d);
        assertEquals(1.2d, result.points().get(1).latitude(), 1e-9d);
        assertEquals(1.3d, result.points().get(2).latitude(), 1e-9d);
        assertTrue(result.warnings().isEmpty());
    }

    @Test
    public void parse_acceptsSingleQuotesNewlinesAndReversedAttributes() throws Exception {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<gpx version='1.1'>\n"
                + "  <trk>\n"
                + "    <trkseg>\n"
                + "      <trkpt\n"
                + "          lon = '120.500000'\n"
                + "          lat = '30.500000'\n"
                + "      />\n"
                + "      <trkpt lat='30.750000' lon='120.750000'/>\n"
                + "    </trkseg>\n"
                + "  </trk>\n"
                + "</gpx>\n";

        GpxCodec.ParseResult result = GpxCodec.parse(xml);

        assertEquals(2, result.points().size());
        assertEquals(30.5d, result.points().get(0).latitude(), 1e-9d);
        assertEquals(120.5d, result.points().get(0).longitude(), 1e-9d);
        assertEquals(30.75d, result.points().get(1).latitude(), 1e-9d);
    }

    @Test
    public void parse_acceptsNamespacePrefixedElements() throws Exception {
        String xml = "<gpx:gpx xmlns:gpx=\"" + GpxCodec.NAMESPACE_GPX_11 + "\" version=\"1.1\">"
                + "<gpx:trk><gpx:trkseg>"
                + "<gpx:trkpt lat=\"30.25\" lon=\"120.25\"/>"
                + "</gpx:trkseg></gpx:trk></gpx:gpx>";

        GpxCodec.ParseResult result = GpxCodec.parse(xml);

        assertEquals(1, result.points().size());
        assertEquals(30.25d, result.points().get(0).latitude(), 1e-9d);
    }

    @Test
    public void parse_toleratesMalformedXmlByFallingBackToPlainScan() throws Exception {
        // 少一个闭合标签：DOM 会拒绝，正则扫描仍应把两个点捞出来。
        String xml = "<gpx><trk><trkseg>"
                + "<trkpt lat='30.1' lon='120.1'>"
                + "<trkpt lat=\"30.2\" lon=\"120.2\"/>"
                + "</trkseg>";

        GpxCodec.ParseResult result = GpxCodec.parse(xml);

        assertEquals(2, result.points().size());
        assertEquals(30.2d, result.points().get(1).latitude(), 1e-9d);
    }

    @Test
    public void parse_ignoresUtf8Bom() throws Exception {
        String xml = "\uFEFF" + GpxCodec.export(samplePoints(), "带 BOM 的文件");

        assertEquals(3, GpxCodec.parse(xml).points().size());
    }

    // ------------------------------------------------------------ 解析：跳过与截断

    @Test
    public void parse_skipsInvalidPointsAndReportsEachOne() throws Exception {
        String xml = "<gpx><trk><trkseg>"
                + "<trkpt lat='95.0' lon='120.0'/>"   // 纬度越界
                + "<trkpt lat='abc' lon='120.0'/>"    // 非数字
                + "<trkpt lon='120.0'/>"              // 缺 lat
                + "<trkpt lat='NaN' lon='120.0'/>"    // NaN
                + "<trkpt lat='30.5' lon='120.5'/>"   // 唯一合法点
                + "</trkseg></trk></gpx>";

        GpxCodec.ParseResult result = GpxCodec.parse(xml);

        assertEquals(1, result.points().size());
        assertEquals(30.5d, result.points().get(0).latitude(), 1e-9d);
        assertEquals(4, result.droppedPointCount());
        assertEquals(4, result.warnings().size());
        assertTrue(result.warnings().get(0).contains("第 1 个点"));
        assertTrue(result.warnings().get(0).contains("已跳过"));
        assertTrue(result.warnings().get(1).contains("第 2 个点"));
        assertTrue(result.warnings().get(2).contains("缺少 lat 或 lon"));
        assertTrue(result.warnings().get(3).contains("第 4 个点"));
    }

    @Test
    public void parse_truncatesToMaxPointsAndWarns() throws Exception {
        StringBuilder xml = new StringBuilder("<gpx><trk><trkseg>");
        for (int i = 0; i < RouteCompiler.MAX_POINTS + 1; i++) {
            xml.append("<trkpt lat=\"").append(String.format(Locale.US, "%.6f", 0.0001d * (i % 70)))
                    .append("\" lon=\"").append(String.format(Locale.US, "%.6f", 0.0001d * (i / 70)))
                    .append("\"/>");
        }
        xml.append("</trkseg></trk></gpx>");

        GpxCodec.ParseResult result = GpxCodec.parse(xml.toString());

        assertEquals(RouteCompiler.MAX_POINTS, result.points().size());
        assertEquals(1, result.droppedPointCount());
        assertEquals(1, result.warnings().size());
        assertTrue(result.warnings().get(0).contains("上限"));
        assertTrue(result.warnings().get(0).contains(String.valueOf(RouteCompiler.MAX_POINTS)));
    }

    @Test
    public void parse_withoutAnyUsablePoint_throwsRouteInvalid() {
        List<String> inputs = new ArrayList<>();
        inputs.add("");
        inputs.add("   \n\t ");
        inputs.add("<gpx version=\"1.1\"><trk><name>只有名字没有点</name></trk></gpx>");
        inputs.add("<gpx><trk><trkseg><trkpt lat='95.0' lon='120.0'/></trkseg></trk></gpx>");
        for (String input : inputs) {
            InvalidRouteException exception = assertThrows(InvalidRouteException.class,
                    () -> GpxCodec.parse(input));
            assertEquals(RunErrorCode.ROUTE_INVALID, exception.errorCode());
            assertEquals("GPX 里没有可用的轨迹点", exception.getMessage());
        }
        // null 也不允许直接 NPE 抛给界面。
        assertThrows(InvalidRouteException.class, () -> GpxCodec.parse(null));
    }

    // ------------------------------------------------------------ 安全：XXE / DTD

    @Test
    public void parse_documentWithDoctype_isReadWithoutResolvingEntities() throws Exception {
        // 带 DTD 声明与外部实体的 GPX：不应崩，也不应去读 file:/// 上的东西。
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<!DOCTYPE gpx [<!ENTITY xxe SYSTEM \"file:///C:/Windows/win.ini\">]>\n"
                + "<gpx version=\"1.1\" creator=\"other\"><trk>"
                + "<name>&xxe;</name>"
                + "<trkseg>"
                + "<trkpt lat=\"30.1\" lon=\"120.1\"/>"
                + "<trkpt lat=\"30.2\" lon=\"120.2\"/>"
                + "</trkseg></trk></gpx>";

        GpxCodec.ParseResult result = GpxCodec.parse(xml);

        assertEquals(2, result.points().size());
        assertEquals(30.1d, result.points().get(0).latitude(), 1e-9d);
        assertTrue(result.warnings().isEmpty());
    }

    @Test
    public void parse_entityOnlyDocument_throwsInsteadOfLeakingFileContent() {
        String xml = "<!DOCTYPE gpx [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>"
                + "<gpx version=\"1.1\"><trk><name>&xxe;</name></trk></gpx>";

        InvalidRouteException exception = assertThrows(InvalidRouteException.class,
                () -> GpxCodec.parse(xml));

        assertEquals(RunErrorCode.ROUTE_INVALID, exception.errorCode());
        assertNotNull(exception.getMessage());
        assertFalse("绝不能把实体内容带出来", exception.getMessage().contains("root:"));
    }

    // ------------------------------------------------------------ 容错：不往标准错误流打字

    @Test
    public void parse_malformedXml_fallsBackToRegexWithoutNoiseOnStandardError() throws Exception {
        // 开头有杂字符 → 不是良构 XML，DOM 必然失败；此时必须安静地退到正则，不能把
        // 「[Fatal Error] ...」打进 stderr（那是 JDK 默认错误处理器的行为）。
        String xml = "u{FEFF}这不是 XML <gpx><trk><trkseg>"
                + "<trkpt lat=\"30.5\" lon=\"120.5\"/></trkseg></trk></gpx>";

        PrintStream originalErr = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        GpxCodec.ParseResult result;
        try {
            System.setErr(new PrintStream(captured, true, "UTF-8"));
            result = GpxCodec.parse(xml);
        } finally {
            System.setErr(originalErr);
        }

        assertEquals(1, result.points().size());
        assertEquals("解析失败不应写标准错误流", "", captured.toString("UTF-8").trim());
    }

    @Test
    public void multiSegmentFileOnlyImportsTheFirstSegment() throws Exception {
        String xml = "<?xml version=\"1.0\"?><gpx version=\"1.1\"><trk><trkseg>"
                + "<trkpt lat=\"30.0\" lon=\"120.0\"/><trkpt lat=\"30.0\" lon=\"120.001\"/>"
                + "</trkseg><trkseg><trkpt lat=\"31.0\" lon=\"121.0\"/>"
                + "<trkpt lat=\"31.0\" lon=\"121.001\"/></trkseg></trk></gpx>";
        GpxCodec.ParseResult result = GpxCodec.parse(xml);
        assertEquals("只导入第一段", 2, result.points().size());
        assertTrue(result.points().get(0).isNear(new GeoPoint(30.0d, 120.0d), 0.01d));
        boolean warned = false;
        for (String warning : result.warnings()) {
            if (warning.contains("只导入第一段")) {
                warned = true;
            }
        }
        assertTrue("应提示多段只取第一段：" + result.warnings(), warned);
    }}
