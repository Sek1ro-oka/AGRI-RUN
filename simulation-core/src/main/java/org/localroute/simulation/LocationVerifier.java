package org.localroute.simulation;

import org.localroute.core.GeoMath;
import org.localroute.core.GeoPoint;

import java.util.Locale;

/**
 * 「虚拟定位到底生效了吗」的判定：把我们提交的坐标，和系统读回来的坐标做对比。
 *
 * <p>这是本应用能拿到的唯一硬证据——提交成功只说明调用没抛异常，只有再从
 * {@code LocationManager.getLastKnownLocation(...)} 读回同一个点，才算系统真的收下了。
 * 判定逻辑放在这里（而不是 Activity 里）是为了能写单元测试。
 */
public final class LocationVerifier {

    /** 允许的系统回读偏差：定位缓存和取整会带来几米误差。 */
    public static final double DEFAULT_TOLERANCE_METERS = 20.0;

    /** 判定结果。 */
    public enum Verdict {
        /** 系统读回的位置与提交的一致，且带 mock 标记。 */
        ACCEPTED,
        /** 读回的位置和提交的差得太远。 */
        MISMATCH,
        /** 这个 provider 没有可用位置（没提交过、或被系统丢弃）。 */
        NOT_RECEIVED,
        /** 位置对得上，但系统没把它标记为模拟位置。 */
        NOT_MARKED_MOCK,
        /** 应用还没有提交过位置。 */
        NO_EXPECTED
    }

    private LocationVerifier() {
    }

    /**
     * 判定一次自检结果。
     *
     * @param expected          应用最后提交给系统的坐标；没有则为 null
     * @param observed          从系统读回的坐标；没有则为 null
     * @param markedMock        系统读回的位置是否带「模拟位置」标记
     * @param toleranceMeters   允许的偏差
     */
    public static Verdict evaluate(GeoPoint expected, GeoPoint observed,
                                   boolean markedMock, double toleranceMeters) {
        if (expected == null) {
            return Verdict.NO_EXPECTED;
        }
        if (observed == null) {
            return Verdict.NOT_RECEIVED;
        }
        double distance = GeoMath.distanceMeters(expected, observed);
        if (distance > toleranceMeters) {
            return Verdict.MISMATCH;
        }
        if (!markedMock) {
            return Verdict.NOT_MARKED_MOCK;
        }
        return Verdict.ACCEPTED;
    }

    /** 偏差米数；缺少任一侧坐标时返回 -1。 */
    public static double distanceMeters(GeoPoint expected, GeoPoint observed) {
        if (expected == null || observed == null) {
            return -1d;
        }
        return GeoMath.distanceMeters(expected, observed);
    }

    /** 给界面看的一句话结论，说明「接下来该怀疑谁」。 */
    public static String explain(Verdict verdict, double distanceMeters, String provider) {
        switch (verdict) {
            case ACCEPTED:
                return String.format(Locale.US,
                        "%s 已收下你提交的位置（偏差 %.1f 米，且标记为模拟位置）。"
                                + "如果目标 App 仍然不动，问题在目标 App 自己过滤模拟位置，不在本应用。",
                        provider, Math.max(0d, distanceMeters));
            case MISMATCH:
                return String.format(Locale.US,
                        "%s 读回的位置和你提交的差了 %.1f 米，可能被其它定位源覆盖，或该 provider 不接受测试位置。",
                        provider, Math.max(0d, distanceMeters));
            case NOT_RECEIVED:
                return String.format(Locale.US,
                        "%s 还没有可读回的位置：先确认「设置检查」页显示允许模拟位置，再开始一次运行或传送。", provider);
            case NOT_MARKED_MOCK:
                return String.format(Locale.US,
                        "%s 位置对得上（偏差 %.1f 米），但没有模拟位置标记——部分系统会把测试位置当真实位置。",
                        provider, Math.max(0d, distanceMeters));
            case NO_EXPECTED:
            default:
                return "还没有提交过位置：先点「开始」或「传送」，再回来看这一项。";
        }
    }
}
