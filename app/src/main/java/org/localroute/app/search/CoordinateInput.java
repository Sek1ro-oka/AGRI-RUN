package org.localroute.app.search;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.localroute.core.ChinaShift;
import org.localroute.core.GeoPoint;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把用户粘贴进来的一段文本认成坐标：裸经纬度、度分秒、地图分享链接。
 *
 * <p>应用内部一律 WGS-84，所以高德/腾讯链接（GCJ-02）在识别时就换算掉，
 * 并把「换算过」这件事带回去给界面说明——用户复制的是一个地址，看不出坐标系。
 * 百度分享链接用的是 BD-09，本应用没有该坐标系的换算实现，认不出来就当普通搜索词。
 *
 * <p>认不出来的代价很小（退回原来的地名搜索），认错代价不小（传送去奇怪的位置），
 * 所以宁严勿宽：裸数字要求整段只有两个数，度分秒要求带 N/S/E/W 半球字母。
 */
public final class CoordinateInput {

    /** 一次识别的结果。 */
    public static final class Hit {
        private final GeoPoint point;
        private final boolean converted;

        Hit(GeoPoint point, boolean converted) {
            this.point = point;
            this.converted = converted;
        }

        public GeoPoint point() {
            return point;
        }

        /** true 表示原文是 GCJ-02（高德/腾讯），已换算成 WGS-84。 */
        public boolean converted() {
            return converted;
        }
    }

    private static final String NUMBER = "[-+]?\\d{1,3}(?:\\.\\d+)?";
    /** 两个数之间的分隔：逗号（含全角）、分号、斜杠、空白、换行。 */
    private static final String SEP = "\\s*[,，;；/\\s]\\s*";
    /** 裸「纬度,经度」，允许带第三个数（分享链接里的缩放级）。 */
    private static final Pattern BARE_PAIR = Pattern.compile(
            "^\\s*(" + NUMBER + ")" + SEP + "(" + NUMBER + ")"
                    + "(?:" + SEP + "(?:\\d{1,3}(?:\\.\\d+)?z?))?\\s*$",
            Pattern.CASE_INSENSITIVE);
    /** 键值形式，兼顾 Google 的 {@code q=纬度,经度} 与高德的 {@code lat=}&{@code lng=}。 */
    private static final Pattern LAT_KEY = Pattern.compile(
            "(?:^|[?&#;\\s\"'/])lat(?:itude)?0?\\s*[=：:]\\s*(" + NUMBER + ")",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern LNG_KEY = Pattern.compile(
            "(?:^|[?&#;\\s\"'/])(?:lng|lon|long|longitude)0?\\s*[=：:]\\s*(" + NUMBER + ")",
            Pattern.CASE_INSENSITIVE);
    /** 只有「某个键=一对数」时才走成对取值，避免 {@code query=西湖} 这类参数名干扰。 */
    private static final Pattern PAIRED_KEY = Pattern.compile(
            "[?&#;](?:q|ll|query|co|coordinate|destination|daddr|saddr)=",
            Pattern.CASE_INSENSITIVE);
    /**
     * 高德的 {@code position=经度,纬度}、{@code center=经度,纬度}：
     * 顺序与「纬度在前」的通用写法相反，只认这几个键名，不猜。
     */
    private static final Pattern LON_FIRST_KEY = Pattern.compile(
            "[?&#;](?:position|center|coords?|marker)=?",
            Pattern.CASE_INSENSITIVE);
    /** Google 链接里的 {@code @纬度,经度,缩放z}。 */
    private static final Pattern AT_PAIR = Pattern.compile(
            "@\\s*(" + NUMBER + ")\\s*[,，]\\s*(" + NUMBER + ")");
    private static final Pattern DMS = Pattern.compile(
            "(\\d{1,3})[°ºd]\\s*(\\d{1,2})['’′m]\\s*(\\d{1,2}(?:\\.\\d+)?)?(?:[\"″s])?\\s*([NSEWnsew])");
    /** 用 GCJ-02 出坐标的站点；命中就要换算。 */
    private static final Pattern GCJ_HOST = Pattern.compile(
            "amap\\.com|gaode\\.com|ditu\\.qq\\.com|map\\.qq\\.com|api\\.map\\.qq\\.com",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CHINESE = Pattern.compile("[\\u4e00-\\u9fa5]");
    private static final Pattern ANY_NUMBER = Pattern.compile(NUMBER);

    private CoordinateInput() {
    }

    /**
     * 识别一段文本。认不出来返回 {@code null}，调用方按普通搜索词处理。
     */
    @Nullable
    public static Hit parse(@Nullable String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.length() < 3) {
            return null;
        }
        boolean link = isLink(text);
        if (!link && looksLikeQueryWords(text)) {
            return null;
        }
        if (GCJ_HOST.matcher(text).find()) {
            return fromGcjText(text);
        }
        Hit plain = plainPair(text);
        if (plain != null) {
            return plain;
        }
        Hit dms = fromDms(text);
        if (dms != null) {
            return dms;
        }
        return fromUrl(text);
    }

    /**
     * 把识别结果扮成一条搜索结果，好让既有的结果列表原样渲染。
     *
     * <p>名称与说明由界面给（要读文案资源），这里只负责放坐标。
     */
    public static PhotonSearch.Place toPlace(@NonNull Hit hit, @NonNull String name,
                                             @NonNull String detail) {
        return new PhotonSearch.Place(name, detail,
                hit.point().latitude(), hit.point().longitude());
    }

    /** 带域名的分享链接里出现中文很正常（点位名称），不能按搜索词挡掉。 */
    private static boolean isLink(String text) {
        return text.contains("://") || text.startsWith("www.") || text.contains("google.com/maps")
                || GCJ_HOST.matcher(text).find();
    }

    /** 纯中文/字母的地名搜索词不该进坐标判定，先挡掉，省得「30」这种串被误认。 */
    private static boolean looksLikeQueryWords(String text) {
        if (CHINESE.matcher(text).find()) {
            return !DMS.matcher(text).find();
        }
        return !text.contains(",") && !text.contains("，") && !text.contains("@")
                && !text.contains("°") && !text.contains("=") && !text.contains("/")
                && !ANY_NUMBER.matcher(text).find();
    }

    private static Hit plainPair(String text) {
        Matcher pair = BARE_PAIR.matcher(text);
        if (!pair.matches()) {
            return null;
        }
        return build(pair.group(1), pair.group(2), false);
    }

    /** 高德/腾讯链接：优先取 {@code lat}/{@code lng} 参数，再退到 {@code @纬度,经度} 与裸数字。 */
    private static Hit fromGcjText(String text) {
        Hit keyed = fromKeyedPair(text, true);
        if (keyed != null) {
            return keyed;
        }
        Hit lonFirst = fromLonFirstKey(text);
        if (lonFirst != null) {
            return lonFirst;
        }
        Hit at = firstAt(text, true);
        if (at != null) {
            return at;
        }
        Matcher pair = BARE_PAIR.matcher(text);
        return pair.matches() ? build(pair.group(1), pair.group(2), true) : null;
    }

    /** 经度在前的那几种高德参数。 */
    private static Hit fromLonFirstKey(String text) {
        Matcher key = LON_FIRST_KEY.matcher(text);
        if (!key.find()) {
            return null;
        }
        Matcher rest = Pattern.compile("(" + NUMBER + ")\\s*(?:[,，]|%2C)\\s*(" + NUMBER + ")")
                .matcher(text.substring(key.end()));
        return rest.find() ? build(rest.group(2), rest.group(1), true) : null;
    }

    private static Hit fromUrl(String text) {
        if (!text.contains("://") && !text.contains("maps") && !text.contains("@")
                && !text.contains("=")) {
            return null;
        }
        Hit at = firstAt(text, false);
        if (at != null) {
            return at;
        }
        Hit keyed = fromKeyedPair(text, false);
        if (keyed != null) {
            return keyed;
        }
        Matcher paired = PAIRED_KEY.matcher(text);
        if (!paired.find()) {
            return null;
        }
        // 逗号在链接里可能是 %2C（Google 的 data 参数就这么写）。
        Matcher rest = Pattern.compile("(" + NUMBER + ")\\s*(?:[,，]|%2C)\\s*(" + NUMBER + ")")
                .matcher(text.substring(paired.end()));
        return rest.find() ? build(rest.group(1), rest.group(2), false) : null;
    }

    private static Hit fromKeyedPair(String text, boolean gcj) {
        Matcher lat = LAT_KEY.matcher(text);
        Matcher lng = LNG_KEY.matcher(text);
        if (!lat.find() || !lng.find()) {
            return null;
        }
        return build(lat.group(1), lng.group(1), gcj);
    }

    private static Hit firstAt(String text, boolean gcj) {
        Matcher at = AT_PAIR.matcher(text);
        return at.find() ? build(at.group(1), at.group(2), gcj) : null;
    }

    /** 度分秒：要求半球字母，且至少凑出一个 N/S 与一个 E/W。 */
    private static Hit fromDms(String text) {
        Matcher dms = DMS.matcher(text);
        Double latitude = null;
        Double longitude = null;
        while (dms.find()) {
            double degrees = Double.parseDouble(dms.group(1))
                    + Double.parseDouble(dms.group(2)) / 60d
                    + (dms.group(3) == null ? 0d : Double.parseDouble(dms.group(3)) / 3600d);
            char hemisphere = Character.toUpperCase(dms.group(4).charAt(0));
            if (hemisphere == 'S' || hemisphere == 'W') {
                degrees = -degrees;
            }
            if (hemisphere == 'N' || hemisphere == 'S') {
                latitude = degrees;
            } else {
                longitude = degrees;
            }
        }
        if (latitude == null || longitude == null) {
            return null;
        }
        return point(latitude, longitude, false);
    }

    private static Hit build(String latitude, String longitude, boolean alreadyGcj) {
        try {
            return point(Double.parseDouble(latitude), Double.parseDouble(longitude), alreadyGcj);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 数值成形的地方，也是唯一做换算的地方。
     *
     * <p>范围在这里判，因为 {@link GeoPoint} 的构造对越界直接抛异常；
     * {@code alreadyGcj} 为真时先转 WGS-84，境外坐标 {@link ChinaShift} 不做偏移，
     * 所以对海外的点这一步是恒等的、也就算不上换算。
     */
    private static Hit point(double latitude, double longitude, boolean alreadyGcj) {
        if (!plausible(latitude, longitude)) {
            return null;
        }
        if (!alreadyGcj || ChinaShift.outOfChina(latitude, longitude)) {
            return new Hit(new GeoPoint(latitude, longitude), false);
        }
        GeoPoint wgs = ChinaShift.gcj02ToWgs84(latitude, longitude);
        if (!plausible(wgs.latitude(), wgs.longitude())) {
            return null;
        }
        return new Hit(new GeoPoint(wgs.latitude(), wgs.longitude()), true);
    }

    private static boolean plausible(double latitude, double longitude) {
        return !Double.isNaN(latitude) && !Double.isNaN(longitude)
                && !Double.isInfinite(latitude) && !Double.isInfinite(longitude)
                && Math.abs(latitude) <= 90d && Math.abs(longitude) <= 180d;
    }
}
