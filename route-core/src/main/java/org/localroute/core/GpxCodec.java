package org.localroute.core;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

/**
 * GPX 1.1 的极简编解码：只关心轨迹点，离线、不引入任何第三方依赖。
 *
 * <p>导出固定输出 WGS-84 十进制度、6 位小数，结构为
 * {@code <gpx version="1.1" creator="Route Workshop"><trk><name/><trkseg><trkpt/></trkseg></trk></gpx>}。
 *
 * <p>解析对输入比较宽容（要导入的是别人给的 GPX，不是自己刚导出的）：
 * <ul>
 *   <li>认可 {@code trkpt}（轨迹点）、{@code rtept}（路线点）、{@code wpt}（航点），可按文档顺序混用；</li>
 *   <li>属性顺序任意，单引号与双引号都接受，允许换行与缩进；</li>
 *   <li>经纬度非法或缺失的点跳过并记入 {@link ParseResult#warnings()}，不因一个坏点整份失败；</li>
 *   <li>点数超过 {@link RouteCompiler#MAX_POINTS} 时截断并给出警告。</li>
 * </ul>
 *
 * <p>安全：含 {@code <!DOCTYPE} 声明的文档一律不做 DOM 解析（只走正则扫描），
 * 良构文档的 DOM 解析也关闭 DTD 与外部实体（XXE 防护），任何加固失败都退回正则路径，
 * 因此不存在“带着默认配置解析用户文件”的分支。
 *
 * <p>单圈长度是否够 1 米不在这里判断：那是 {@link RouteCompiler} 的职责。
 */
public final class GpxCodec {

    /** 导出时写入 {@code creator} 属性的应用名。 */
    public static final String CREATOR = "Route Workshop";
    /** GPX 1.1 命名空间。 */
    public static final String NAMESPACE_GPX_11 = "http://www.topografix.com/GPX/1/1";

    /** 认可的轨迹点标签名（小写比较）。 */
    private static final String[] POINT_ELEMENT_NAMES = {"trkpt", "rtept", "wpt"};

    /** 匹配一个轨迹点标签的开头；第 2 组是标签名之后的属性串。 */
    private static final Pattern POINT_TAG =
            Pattern.compile("<\\s*(trkpt|rtept|wpt)\\b([^>]*)>", Pattern.CASE_INSENSITIVE);

    /** 匹配 {@code lat=}/{@code lon=}；第 2 组是双引号内容，第 3 组是单引号内容。 */
    private static final Pattern COORDINATE_ATTRIBUTE =
            Pattern.compile("(?:^|\\s)(lat|lon)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')", Pattern.CASE_INSENSITIVE);

    /** 用来判断是否含 DTD 声明：含声明就绕开 DOM 解析器。 */
    private static final Pattern DOCTYPE_DECLARATION =
            Pattern.compile("<!DOCTYPE", Pattern.CASE_INSENSITIVE);

    private GpxCodec() {
    }

    // ---------------------------------------------------------------- 导出

    /**
     * 导出为标准 GPX 1.1 文本。
     *
     * @param points 轨迹点；{@code null} 或空表示一条空轨迹（仍是合法 GPX）
     * @param name   轨迹名，会做 XML 转义；空白名用「未命名路线」
     */
    public static String export(List<GeoPoint> points, String name) {
        String trackName = name == null || name.trim().isEmpty() ? "未命名路线" : name.trim();
        int capacity = 160 + (points == null ? 0 : points.size() * 64);
        StringBuilder xml = new StringBuilder(capacity);
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<gpx version=\"1.1\" creator=\"").append(CREATOR)
                .append("\" xmlns=\"").append(NAMESPACE_GPX_11).append("\">\n");
        xml.append("  <trk>\n");
        xml.append("    <name>").append(escapeXml(trackName)).append("</name>\n");
        xml.append("    <trkseg>\n");
        if (points != null) {
            for (GeoPoint point : points) {
                if (point == null) {
                    continue;
                }
                xml.append("      <trkpt lat=\"").append(formatCoordinate(point.latitude()))
                        .append("\" lon=\"").append(formatCoordinate(point.longitude()))
                        .append("\"/>\n");
            }
        }
        xml.append("    </trkseg>\n");
        xml.append("  </trk>\n");
        xml.append("</gpx>\n");
        return xml.toString();
    }

    /** 6 位小数，固定用 {@link Locale#US} 输出小数点，避免某些区域设置写出逗号。 */
    private static String formatCoordinate(double value) {
        return String.format(Locale.US, "%.6f", value);
    }

    /** 转义 XML 的 5 个特殊字符，供元素文本与属性值共用。 */
    static String escapeXml(String raw) {
        StringBuilder out = new StringBuilder(raw.length() + 16);
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            switch (ch) {
                case '&':
                    out.append("&amp;");
                    break;
                case '<':
                    out.append("&lt;");
                    break;
                case '>':
                    out.append("&gt;");
                    break;
                case '"':
                    out.append("&quot;");
                    break;
                case '\'':
                    out.append("&apos;");
                    break;
                default:
                    out.append(ch);
                    break;
            }
        }
        return out.toString();
    }

    // ---------------------------------------------------------------- 解析

    /**
     * 解析 GPX 文本，取出全部轨迹点。
     *
     * @return 点、警告与被丢弃点数
     * @throws InvalidRouteException 一个可用点都没有时抛出，错误码为 {@link RunErrorCode#ROUTE_INVALID}
     */
    public static ParseResult parse(String xml) throws InvalidRouteException {
        String text = stripBom(xml);
        if (text == null || text.trim().isEmpty()) {
            throw noPoints();
        }

        List<RawPoint> rawPoints = readPoints(text);
        List<String> warnings = new ArrayList<>();
        int segments = countSegments(text);
        if (segments > 1) {
            warnings.add("文件含 " + segments + " 段轨迹，为避免段间被连成直线，只导入第一段：" + segments + " 段中其余段请另存为单独文件");
        }
        List<GeoPoint> points = new ArrayList<>(Math.min(rawPoints.size(), RouteCompiler.MAX_POINTS));
        int dropped = 0;
        boolean truncated = false;
        for (RawPoint raw : rawPoints) {
            if (points.size() >= RouteCompiler.MAX_POINTS) {
                // 超过上限后不再逐点校验，否则一份超大文件会生成几十万条警告。
                truncated = true;
                dropped++;
                continue;
            }
            if (raw.latitudeText == null || raw.latitudeText.trim().isEmpty()
                    || raw.longitudeText == null || raw.longitudeText.trim().isEmpty()) {
                warnings.add("第 " + raw.ordinal + " 个点缺少 lat 或 lon 属性，已跳过");
                dropped++;
                continue;
            }
            GeoPoint point = toGeoPoint(raw);
            if (point == null) {
                warnings.add("第 " + raw.ordinal + " 个点坐标非法，已跳过："
                        + raw.latitudeText + "," + raw.longitudeText);
                dropped++;
                continue;
            }
            points.add(point);
        }
        if (truncated) {
            warnings.add("点数超过上限 " + RouteCompiler.MAX_POINTS + "（共 " + rawPoints.size()
                    + " 个），超出部分已忽略");
        }
        if (points.isEmpty()) {
            throw noPoints();
        }
        return new ParseResult(points, warnings, dropped);
    }

    /** 把一组经纬度字符串转成 {@link GeoPoint}；缺失、非数字、越界、NaN 都返回 {@code null}。 */
    private static GeoPoint toGeoPoint(RawPoint raw) {
        try {
            double latitude = Double.parseDouble(raw.latitudeText.trim());
            double longitude = Double.parseDouble(raw.longitudeText.trim());
            return new GeoPoint(latitude, longitude);
        } catch (IllegalArgumentException e) {
            // NumberFormatException（非数字）与 GeoPoint 的范围校验异常都是它的子类。
            return null;
        }
    }

    /** 去掉 UTF-8 BOM（Windows 上导出的文件常带），否则 DOM 会在 prolog 处报错。 */
    private static String stripBom(String xml) {
        if (xml != null && !xml.isEmpty() && xml.charAt(0) == '\uFEFF') {
            return xml.substring(1);
        }
        return xml;
    }

    private static InvalidRouteException noPoints() {
        return new InvalidRouteException(RunErrorCode.ROUTE_INVALID, "GPX 里没有可用的轨迹点");
    }

    /**
     * 先试 DOM（能正确处理命名空间、实体、注释），失败再退回正则扫描。
     *
     * <p>含 DTD 声明的文档直接跳过 DOM：这样即使某个平台的解析器不认识
     * {@code disallow-doctype-decl}，也不会有解析器看到 DTD。
     */
    private static List<RawPoint> readPoints(String xml) {
        if (!DOCTYPE_DECLARATION.matcher(xml).find()) {
            List<RawPoint> fromDom = readPointsWithDom(xml);
            if (fromDom != null) {
                return fromDom;
            }
        }
        return readPointsWithRegex(xml);
    }

    /**
     * 用加固过的 DOM 解析器读点。
     *
     * @return 点列表；解析器无法加固或文档不是良构 XML 时返回 {@code null}（调用方退回正则）
     */
    private static List<RawPoint> readPointsWithDom(String xml) {
        DocumentBuilderFactory factory = hardenedFactory();
        if (factory == null) {
            return null;
        }
        try {
            DocumentBuilder builder = factory.newDocumentBuilder();
            // 即便加固特性部分失效，也不让解析器去取任何外部资源。
            builder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
            // 默认错误处理器会把「[Fatal Error] ...」直接打到 stderr；这里换成不打印的实现，
            // 致命错误照常抛出（由下方 catch 退到正则），可恢复错误直接忽略，正好符合容错要求。
            builder.setErrorHandler(new DefaultHandler());
            Document document = builder.parse(new InputSource(new StringReader(xml)));
            // 多段轨迹（多个 trkseg）只取第一段：GPX 里段与段之间没有连线语义，
            // 拼在一起会在末点与下一段首点之间画出一条不存在的长直线（审查 G / R-0xx）。
            List<RawPoint> rawPoints = new ArrayList<>();
            collectPoints(document.getDocumentElement(), rawPoints, new int[] {0});
            return rawPoints;
        } catch (ParserConfigurationException | SAXException | IOException | RuntimeException e) {
            // 容错：不是良构 XML（含带 DTD 被拒）时交给正则，能取多少取多少。
            return null;
        }
    }

    /**
     * 按文档顺序收集轨迹点，但只保留第一段 trkseg：段与段之间没有连线语义，
     * 拼在一起会画出一条不存在的长直线（审查 G）。
     */
    private static void collectPoints(Element parent, List<RawPoint> out, int[] segmentIndex) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            Element element = (Element) child;
            String tag = element.getTagName();
            if ("trkseg".equals(tag) || "trkseg".equalsIgnoreCase(tag)) {
                segmentIndex[0]++;
                if (segmentIndex[0] > 1) {
                    continue;
                }
            }
            if (isPointElement(element)) {
                out.add(new RawPoint(out.size() + 1,
                        element.getAttribute("lat"), element.getAttribute("lon")));
                continue;
            }
            collectPoints(element, out, segmentIndex);
        }
    }

    /** 统计 trkseg 段数，用于提示“只导入第一段”。 */
    static int countSegments(String xml) {
        if (xml == null) {
            return 0;
        }
        int count = 0;
        int index = xml.indexOf("<trkseg");
        while (index >= 0) {
            count++;
            index = xml.indexOf("<trkseg", index + 1);
        }
        return count;
    }

    /**
     * 创建关闭了 DTD 与外部实体的解析器工厂。
     *
     * @return 加固成功的工厂；关键特性不受支持时返回 {@code null}，调用方必须改走正则
     */
    private static DocumentBuilderFactory hardenedFactory() {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        try {
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        } catch (ParserConfigurationException e) {
            // 有些平台（包括部分 Android 内置解析器）不认识这些特性名：宁可不解析，也不带着默认配置上。
            return null;
        }
        setFeatureQuietly(factory, "http://xml.org/sax/features/external-general-entities", false);
        setFeatureQuietly(factory, "http://xml.org/sax/features/external-parameter-entities", false);
        setFeatureQuietly(factory, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        try {
            factory.setXIncludeAware(false);
        } catch (UnsupportedOperationException e) {
            // 不支持 XInclude 的实现本身就不会处理它，忽略即可。
        }
        try {
            factory.setExpandEntityReferences(false);
        } catch (UnsupportedOperationException e) {
            // 同上：disallow-doctype-decl 已经挡住了 DTD。
        }
        factory.setNamespaceAware(true);
        factory.setValidating(false);
        return factory;
    }

    /** 设不上的特性就跳过：{@code disallow-doctype-decl} 已经承担了主要防线。 */
    private static void setFeatureQuietly(DocumentBuilderFactory factory, String feature, boolean value) {
        try {
            factory.setFeature(feature, value);
        } catch (ParserConfigurationException | RuntimeException e) {
            // 各平台支持的 SAX 特性名不一致，这里不把它当失败。
        }
    }

    private static boolean isPointElement(Element element) {
        String name = element.getLocalName();
        if (name == null) {
            name = element.getNodeName();
        }
        int colon = name.indexOf(':');
        if (colon >= 0) {
            // 允许 <gpx:trkpt> 这种带前缀的写法。
            name = name.substring(colon + 1);
        }
        for (String candidate : POINT_ELEMENT_NAMES) {
            if (candidate.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    /** 纯文本扫描：不认识命名空间与实体，但也不会解析 DTD，用于兜底。 */
    private static List<RawPoint> readPointsWithRegex(String xml) {
        List<RawPoint> rawPoints = new ArrayList<>();
        Matcher tag = POINT_TAG.matcher(xml);
        while (tag.find()) {
            String attributes = tag.group(2);
            String latitude = null;
            String longitude = null;
            Matcher attribute = COORDINATE_ATTRIBUTE.matcher(attributes);
            while (attribute.find()) {
                String key = attribute.group(1).toLowerCase(Locale.US);
                String value = attribute.group(2) != null ? attribute.group(2) : attribute.group(3);
                if ("lat".equals(key)) {
                    if (latitude == null) {
                        latitude = value;
                    }
                } else if (longitude == null) {
                    longitude = value;
                }
            }
            rawPoints.add(new RawPoint(rawPoints.size() + 1, latitude, longitude));
        }
        return rawPoints;
    }

    // ---------------------------------------------------------------- 结果类型

    /** 从文档里读到的原始经纬度文本（还没校验），供 DOM 与正则两条路径共用。 */
    private static final class RawPoint {

        /** 在候选点里的序号，从 1 开始，只用于给人看的警告文案。 */
        private final int ordinal;
        private final String latitudeText;
        private final String longitudeText;

        private RawPoint(int ordinal, String latitudeText, String longitudeText) {
            this.ordinal = ordinal;
            this.latitudeText = latitudeText;
            this.longitudeText = longitudeText;
        }
    }

    /** 解析结果：可用点、警告文案与被丢弃的点数。不可变。 */
    public static final class ParseResult {

        private final List<GeoPoint> points;
        private final List<String> warnings;
        private final int droppedPointCount;

        private ParseResult(List<GeoPoint> points, List<String> warnings, int droppedPointCount) {
            this.points = Collections.unmodifiableList(new ArrayList<>(points));
            this.warnings = Collections.unmodifiableList(new ArrayList<>(warnings));
            this.droppedPointCount = droppedPointCount;
        }

        /** 解析出来的可用点，按文件中的先后顺序。 */
        public List<GeoPoint> points() {
            return points;
        }

        /** 每一条被跳过的原因（缺属性、坐标非法、超出上限），可直接展示给用户。 */
        public List<String> warnings() {
            return warnings;
        }

        /** 被丢弃的点数（含因超过上限被截断的部分）。 */
        public int droppedPointCount() {
            return droppedPointCount;
        }
    }
}
