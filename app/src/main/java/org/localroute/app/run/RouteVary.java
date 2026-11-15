package org.localroute.app.run;

import org.localroute.core.GeoPoint;

import java.util.List;
import java.util.Random;

/**
 * 运行期的路线扰动：在基准路线的法向叠加一条随距离变化的偏移。
 *
 * <p>设计目标：
 * <ul>
 *   <li><b>每圈不重合</b>：波形按累计距离连续推进，三条波长（151 m / 57 m / 23 m）与常见跑道圈长
 *       不成整数倍，所以同一位置在下一圈会落在不同的横向位置；</li>
 *   <li><b>不出跑道</b>：偏移只朝路线外侧（远离环心）一个方向，幅度上限
 *       {@link #MAX_OFFSET_METERS}（6 m），仍落在标准跑道的可用宽度内；</li>
 *   <li><b>平滑</b>：起终点附近 8 m 内淡入淡出，圈与圈交界处回到基准线，
 *       不会出现横向跳变。</li>
 * </ul>
 *
 * <p>该类不依赖 Android，可在 JVM 单元测试中直接验证。
 */
public final class RouteVary {

    /**
     * 扰动幅度上限（米）。最大档接近参考轨迹的摆动幅度；
     * 标准 8 道跑道可用宽度约 8—10 m，内侧起跑的路线仍能留在红色跑道内。
     */
    public static final double MAX_OFFSET_METERS = 6d;

    /** 主波长（米），与 400 m 级跑道圈长不成整数倍。 */
    private static final double WAVE_LENGTH_LONG = 151d;

    /** 次波长（米），叠加后让相邻圈差异更明显。 */
    private static final double WAVE_LENGTH_SHORT = 57d;

    /** 细节波长（米），让最大档看起来更像真实轨迹的不规则摆动。 */
    private static final double WAVE_LENGTH_FINE = 23d;

    /** 起终点附近的淡入淡出长度（米）。 */
    private static final double EDGE_FADE_METERS = 8d;

    private static final double METERS_PER_DEGREE_LAT = 111320d;

    private final double strength;
    private final double phaseLong;
    private final double phaseShort;
    private final double phaseFine;
    private final double centerLatitude;
    private final double centerLongitude;
    private final double lapMeters;

    private RouteVary(double strength, double phaseLong, double phaseShort, double phaseFine,
                      double centerLatitude, double centerLongitude, double lapMeters) {
        this.strength = strength;
        this.phaseLong = phaseLong;
        this.phaseShort = phaseShort;
        this.phaseFine = phaseFine;
        this.centerLatitude = centerLatitude;
        this.centerLongitude = centerLongitude;
        this.lapMeters = lapMeters;
    }

    /**
     * 为一次跑圈建立扰动模型。
     *
     * @param vertices   编译后路线的顶点（WGS-84），用于估算环心
     * @param lapMeters  单圈长度
     * @param strength   扰动程度 0..1，0 表示不扰动
     * @param seed       本次跑圈的随机种子，决定两条波形的相位
     */
    public static RouteVary create(List<GeoPoint> vertices, double lapMeters,
                                   double strength, long seed) {
        double clamped = Math.max(0d, Math.min(1d, strength));
        double centerLat = 0d;
        double centerLon = 0d;
        if (vertices != null && !vertices.isEmpty()) {
            for (GeoPoint vertex : vertices) {
                centerLat += vertex.latitude();
                centerLon += vertex.longitude();
            }
            centerLat /= vertices.size();
            centerLon /= vertices.size();
        }
        Random random = new Random(seed);
        double phaseLong = random.nextDouble() * Math.PI * 2d;
        double phaseShort = random.nextDouble() * Math.PI * 2d;
        double phaseFine = random.nextDouble() * Math.PI * 2d;
        return new RouteVary(clamped, phaseLong, phaseShort, phaseFine,
                centerLat, centerLon, Math.max(1e-6d, lapMeters));
    }

    public double strength() {
        return strength;
    }

    public boolean enabled() {
        return strength > 1e-6d;
    }

    /**
     * 计算扰动后的位置。
     *
     * @param base             基准路线上的位置
     * @param bearingDegrees   该位置的行进方位角
     * @param distanceMeters   本次运行累计距离（跨圈连续）
     * @return 横向偏移后的位置；扰动关闭或偏移量为 0 时返回原对象
     */
    public GeoPoint apply(GeoPoint base, double bearingDegrees, double distanceMeters) {
        if (!enabled() || base == null || !Double.isFinite(distanceMeters)) {
            return base;
        }
        double along = distanceMeters % lapMeters;
        if (along < 0d) {
            along += lapMeters;
        }
        // 在每圈起点/终点把扰动收回基准线，避免圈间交界处跳变。
        double fade = Math.min(1d, along / EDGE_FADE_METERS)
                * Math.min(1d, (lapMeters - along) / EDGE_FADE_METERS);
        if (fade <= 1e-9d) {
            return base;
        }
        // 波形按累计距离推进而不是按圈内进度，否则每一圈都会重复同一偏移。
        // 三层波长叠加：长波决定大范围摆动，短波制造不均匀的鼓包，细波让最大档更接近真实轨迹。
        double wave = 0.5d + 0.5d * (
                0.50d * Math.sin(2d * Math.PI * distanceMeters / WAVE_LENGTH_LONG + phaseLong)
                        + 0.30d * Math.sin(2d * Math.PI * distanceMeters / WAVE_LENGTH_SHORT + phaseShort)
                        + 0.20d * Math.sin(2d * Math.PI * distanceMeters / WAVE_LENGTH_FINE + phaseFine));
        double offsetMeters = MAX_OFFSET_METERS * strength * fade * wave;
        if (offsetMeters <= 1e-6d) {
            return base;
        }

        double bearing = Math.toRadians(bearingDegrees);
        // 行进方向的左法向：(-cosθ, sinθ)，方向分量按（东，北）排列。
        double normalEast = -Math.cos(bearing);
        double normalNorth = Math.sin(bearing);
        double latitudeRadians = Math.toRadians(base.latitude());
        double metersPerDegreeLon = METERS_PER_DEGREE_LAT * Math.cos(latitudeRadians);
        double toOutsideEast = (base.longitude() - centerLongitude) * metersPerDegreeLon;
        double toOutsideNorth = (base.latitude() - centerLatitude) * METERS_PER_DEGREE_LAT;
        if (normalEast * toOutsideEast + normalNorth * toOutsideNorth < 0d) {
            // 另一侧才是环外：翻转法向，保证扰动只向跑道外侧偏移。
            normalEast = -normalEast;
            normalNorth = -normalNorth;
        }
        double latitude = base.latitude() + normalNorth * offsetMeters / METERS_PER_DEGREE_LAT;
        double longitude = base.longitude() + normalEast * offsetMeters / metersPerDegreeLon;
        return new GeoPoint(latitude, longitude);
    }
}
