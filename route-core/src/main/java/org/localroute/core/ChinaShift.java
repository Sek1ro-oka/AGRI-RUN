package org.localroute.core;

/**
 * WGS-84 与 GCJ-02（国测局加密坐标，高德/腾讯等国内瓦片使用的坐标系）之间的换算。
 *
 * <p>为什么要这个类：本应用内部一律用 WGS-84 存路线、提交模拟位置；但高德一类瓦片是 GCJ-02 的，
 * 直接把 WGS-84 画上去会整体偏移几百米。所以「画到高德底图上」要把点转成 GCJ-02，
 * 「在高德底图上点一下」要把 GCJ-02 转回 WGS-84，界面拿到的永远是 WGS-84。
 *
 * <p>算法是公开的 GCJ-02 近似算法（克拉索夫斯基椭球参数 a=6378245.0、ee=0.00669342162296594323），
 * 常见于各类开源实现；它不保证与国家保密算法逐米一致，但足以让底图与点位对齐到几十米内。
 * 中国境外不做偏移（{@link #outOfChina(double, double)} 为真时原样返回）。
 */
public final class ChinaShift {

    /** 克拉索夫斯基椭球长半轴（GCJ-02 算法使用的参数）。 */
    private static final double A = 6378245.0;
    private static final double EE = 0.00669342162296594323;
    private static final double PI = Math.PI;

    private ChinaShift() {
    }

    /** 粗略判断是否在中国境外：境外不做 GCJ-02 偏移。 */
    public static boolean outOfChina(double latitude, double longitude) {
        return longitude < 72.004 || longitude > 137.8347
                || latitude < 0.8293 || latitude > 55.8271;
    }

    /** WGS-84 → GCJ-02。 */
    public static GeoPoint wgs84ToGcj02(double latitude, double longitude) {
        if (outOfChina(latitude, longitude)) {
            return new GeoPoint(latitude, longitude);
        }
        double dLat = transformLatitude(longitude - 105.0, latitude - 35.0);
        double dLon = transformLongitude(longitude - 105.0, latitude - 35.0);
        double radLat = latitude / 180.0 * PI;
        double magic = Math.sin(radLat);
        magic = 1 - EE * magic * magic;
        double sqrtMagic = Math.sqrt(magic);
        dLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * PI);
        dLon = (dLon * 180.0) / (A / sqrtMagic * Math.cos(radLat) * PI);
        return new GeoPoint(latitude + dLat, longitude + dLon);
    }

    /**
     * GCJ-02 → WGS-84。
     *
     * <p>正向公式没有解析逆，这里用不动点迭代：反复用 wgs→gcj 的差去修正 wgs，
     * 一般 3 次就收敛到厘米级，这里做 5 次留余量。
     */
    public static GeoPoint gcj02ToWgs84(double latitude, double longitude) {
        if (outOfChina(latitude, longitude)) {
            return new GeoPoint(latitude, longitude);
        }
        double wgsLat = latitude;
        double wgsLon = longitude;
        for (int i = 0; i < 5; i++) {
            GeoPoint roundTrip = wgs84ToGcj02(wgsLat, wgsLon);
            wgsLat += latitude - roundTrip.latitude();
            wgsLon += longitude - roundTrip.longitude();
        }
        return new GeoPoint(wgsLat, wgsLon);
    }

    private static double transformLatitude(double x, double y) {
        double ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y
                + 0.2 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * PI) + 20.0 * Math.sin(2.0 * x * PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(y * PI) + 40.0 * Math.sin(y / 3.0 * PI)) * 2.0 / 3.0;
        ret += (160.0 * Math.sin(y / 12.0 * PI) + 320 * Math.sin(y * PI / 30.0)) * 2.0 / 3.0;
        return ret;
    }

    private static double transformLongitude(double x, double y) {
        double ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y
                + 0.1 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * PI) + 20.0 * Math.sin(2.0 * x * PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(x * PI) + 40.0 * Math.sin(x / 3.0 * PI)) * 2.0 / 3.0;
        ret += (150.0 * Math.sin(x / 12.0 * PI) + 300.0 * Math.sin(x / 30.0 * PI)) * 2.0 / 3.0;
        return ret;
    }
}
