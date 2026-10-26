package org.localroute.core;

import java.util.Objects;

/**
 * 不可变地理坐标点，单位为 WGS-84 十进制度。
 *
 * <p>约束：纬度必须在 [-90, 90]，经度必须在 [-180, 180]，且二者都必须是有限数。
 * 该类是所有几何计算的输入单位，不负责坐标系转换（本项目只使用 WGS-84）。
 */
public final class GeoPoint {

    private final double latitude;
    private final double longitude;

    public GeoPoint(double latitude, double longitude) {
        if (!Double.isFinite(latitude) || !Double.isFinite(longitude)) {
            throw new IllegalArgumentException("经纬度必须是有限数: lat=" + latitude + ", lon=" + longitude);
        }
        if (latitude < -90d || latitude > 90d) {
            throw new IllegalArgumentException("纬度超出范围 [-90, 90]: " + latitude);
        }
        if (longitude < -180d || longitude > 180d) {
            throw new IllegalArgumentException("经度超出范围 [-180, 180]: " + longitude);
        }
        this.latitude = latitude;
        this.longitude = longitude;
    }

    public double latitude() {
        return latitude;
    }

    public double longitude() {
        return longitude;
    }

    /** 与另一点是否在容差（米）内重合。 */
    public boolean isNear(GeoPoint other, double toleranceMeters) {
        return GeoMath.distanceMeters(this, other) <= toleranceMeters;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof GeoPoint)) {
            return false;
        }
        GeoPoint other = (GeoPoint) o;
        return Double.compare(latitude, other.latitude) == 0
                && Double.compare(longitude, other.longitude) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(latitude, longitude);
    }

    /** 便于日志与界面展示，例如 {@code 30.123456,120.123456}。 */
    public String format() {
        return String.format(java.util.Locale.US, "%.6f,%.6f", latitude, longitude);
    }

    @Override
    public String toString() {
        return "GeoPoint(" + format() + ")";
    }
}
