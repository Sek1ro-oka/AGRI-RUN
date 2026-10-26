package org.localroute.core;

/**
 * 球面几何工具集。全部使用 WGS-84 十进制度 + 米，地球半径取 IUGG 平均半径 6371008.8 米。
 *
 * <p>这里的计算只服务于“沿折线跑多少米”这一类需求，不做投影变换、不做坐标纠偏。
 * 所有跨 ±180° 经线的场景都通过三维单位向量（球面线性插值）处理，避免直接对经度做线性插值。
 */
public final class GeoMath {

    /** IUGG 平均地球半径（米）。 */
    public static final double EARTH_RADIUS_METERS = 6371008.8d;

    private GeoMath() {
    }

    /** 两点间大圆距离（Haversine，米）。 */
    public static double distanceMeters(GeoPoint from, GeoPoint to) {
        double lat1 = Math.toRadians(from.latitude());
        double lat2 = Math.toRadians(to.latitude());
        double dLat = lat2 - lat1;
        double dLon = Math.toRadians(to.longitude() - from.longitude());
        double sinLat = Math.sin(dLat / 2d);
        double sinLon = Math.sin(dLon / 2d);
        double h = sinLat * sinLat + Math.cos(lat1) * Math.cos(lat2) * sinLon * sinLon;
        double clamped = Math.min(1d, Math.sqrt(Math.max(0d, h)));
        return 2d * EARTH_RADIUS_METERS * Math.asin(clamped);
    }

    /** 从 {@code from} 指向 {@code to} 的初始方位角，度，范围 [0, 360)。 */
    public static double initialBearingDegrees(GeoPoint from, GeoPoint to) {
        double lat1 = Math.toRadians(from.latitude());
        double lat2 = Math.toRadians(to.latitude());
        double dLon = Math.toRadians(to.longitude() - from.longitude());
        double y = Math.sin(dLon) * Math.cos(lat2);
        double x = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLon);
        if (x == 0d && y == 0d) {
            return 0d;
        }
        double degrees = Math.toDegrees(Math.atan2(y, x));
        return (degrees + 360d) % 360d;
    }

    /**
     * 在 {@code from} 与 {@code to} 之间按比例做球面线性插值。
     *
     * @param fraction 0 表示起点、1 表示终点；超出 [0,1] 会被夹取
     */
    public static GeoPoint interpolate(GeoPoint from, GeoPoint to, double fraction) {
        double t = Math.max(0d, Math.min(1d, fraction));
        if (t == 0d) {
            return from;
        }
        if (t == 1d) {
            return to;
        }
        double[] v1 = toUnitVector(from);
        double[] v2 = toUnitVector(to);
        double dot = clamp(v1[0] * v2[0] + v1[1] * v2[1] + v1[2] * v2[2], -1d, 1d);
        double omega = Math.acos(dot);
        if (omega < 1e-12d) {
            // 两点几乎重合，直接返回起点，避免 0/0。
            return from;
        }
        double sinOmega = Math.sin(omega);
        if (Math.abs(sinOmega) < 1e-12d) {
            // 对跖点：大圆不唯一，退化到线性经度插值会误导，这里直接返回起点。
            return from;
        }
        double a = Math.sin((1d - t) * omega) / sinOmega;
        double b = Math.sin(t * omega) / sinOmega;
        double x = a * v1[0] + b * v2[0];
        double y = a * v1[1] + b * v2[1];
        double z = a * v1[2] + b * v2[2];
        return fromUnitVector(x, y, z);
    }

    /** 经度归一化到 [-180, 180]。 */
    public static double normalizeLongitude(double longitude) {
        double value = longitude;
        while (value > 180d) {
            value -= 360d;
        }
        while (value < -180d) {
            value += 360d;
        }
        return value;
    }

    /** 纬度夹取到 [-90, 90]。 */
    public static double clampLatitude(double latitude) {
        return Math.max(-90d, Math.min(90d, latitude));
    }

    /** 把角度归一化到 [-180, 180)，用于计算经度差。 */
    public static double normalizeDeltaLongitude(double deltaLongitude) {
        double value = deltaLongitude;
        while (value >= 180d) {
            value -= 360d;
        }
        while (value < -180d) {
            value += 360d;
        }
        return value;
    }

    private static double[] toUnitVector(GeoPoint point) {
        double lat = Math.toRadians(point.latitude());
        double lon = Math.toRadians(point.longitude());
        double cosLat = Math.cos(lat);
        return new double[] {cosLat * Math.cos(lon), cosLat * Math.sin(lon), Math.sin(lat)};
    }

    private static GeoPoint fromUnitVector(double x, double y, double z) {
        double norm = Math.sqrt(x * x + y * y + z * z);
        if (norm <= 0d) {
            throw new IllegalStateException("插值得到零向量");
        }
        double nx = x / norm;
        double ny = y / norm;
        double nz = z / norm;
        double lat = Math.toDegrees(Math.asin(clamp(nz, -1d, 1d)));
        double lon = Math.toDegrees(Math.atan2(ny, nx));
        return new GeoPoint(clampLatitude(lat), normalizeLongitude(lon));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
