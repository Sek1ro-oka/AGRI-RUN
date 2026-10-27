package org.localroute.simulation;

import org.localroute.core.GeoMath;
import org.localroute.core.GeoPoint;

import java.util.Locale;

/**
 * 「这一次模拟输出，系统这一层对上了吗」的判定：把提交的坐标和读回的坐标做对比。
 *
 * <p>能证明的只有一件事：这个 provider 的缓存/回读与本次输出一致。它不能证明
 * 其它定位通道（融合、GMS FLP）也收到了，不能证明独立进程在连续接收，更不能
 * 证明目标 App 采用了这些位置。所以这里的文案只描述证据边界，不替第三方下结论。
 *
 * <p>判定逻辑放在纯 Java 模块里，是为了能写单元测试（不依赖 Android）。
 */
public final class LocationVerifier {

    /** 允许的系统回读偏差：定位缓存和取整会带来几米误差。 */
    public static final double DEFAULT_TOLERANCE_METERS = 20.0;

    /** 读回位置超过这个年龄，就不算「有新回调」，只能算旧缓存。 */
    public static final long DEFAULT_STALE_AFTER_MS = 5_000L;

    /** 判定结果。 */
    public enum Verdict {
        /** 系统读回的位置与本次输出一致、够新，且带 mock 标记。 */
        ACCEPTED,
        /** 读回的位置和提交的差得太远。 */
        MISMATCH,
        /** 这个 provider 没有可用位置（没提交过、或被系统丢弃）。 */
        NOT_RECEIVED,
        /** 位置对得上，但读回结果太旧，不能当作正在连续接收。 */
        STALE,
        /** 位置对得上，但系统没把它标记为模拟位置——只记录观测值。 */
        NOT_MARKED_MOCK,
        /** 应用还没有提交过位置。 */
        NO_EXPECTED
    }

    private LocationVerifier() {
    }

    /** 不带时间信息的判定（兼容旧调用方：年龄未知时不判过期）。 */
    public static Verdict evaluate(GeoPoint expected, GeoPoint observed,
                                   boolean markedMock, double toleranceMeters) {
        return evaluate(expected, observed, markedMock, toleranceMeters, -1L, DEFAULT_STALE_AFTER_MS);
    }

    /**
     * 判定一次自检结果。
     *
     * @param expected          应用最后提交给系统的坐标；没有则为 null
     * @param observed          从系统读回的坐标；没有则为 null
     * @param markedMock        系统读回的位置是否带「模拟位置」标记
     * @param toleranceMeters   允许的偏差
     * @param observedAgeMillis 读回位置的年龄（毫秒）；未知传 -1
     * @param staleAfterMillis  超过这个年龄算旧缓存
     */
    public static Verdict evaluate(GeoPoint expected, GeoPoint observed,
                                   boolean markedMock, double toleranceMeters,
                                   long observedAgeMillis, long staleAfterMillis) {
        if (expected == null) {
            return Verdict.NO_EXPECTED;
        }
        if (observed == null) {
            return Verdict.NOT_RECEIVED;
        }
        // 先判新鲜度：旧缓存即使坐标“刚好对得上”，也不能当作正在接收的证据。
        if (observedAgeMillis >= 0 && observedAgeMillis > staleAfterMillis) {
            return Verdict.STALE;
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

    /** 给界面看的一句话结论（不带年龄）。 */
    public static String explain(Verdict verdict, double distanceMeters, String provider) {
        return explain(verdict, distanceMeters, provider, -1L);
    }

    /** 给界面看的一句话结论：只描述观测到的事实和证据边界。 */
    public static String explain(Verdict verdict, double distanceMeters, String provider,
                                 long observedAgeMillis) {
        String age = observedAgeMillis >= 0
                ? String.format(Locale.US, "，%.0f 秒前", observedAgeMillis / 1000d)
                : "";
        switch (verdict) {
            case ACCEPTED:
                return String.format(Locale.US,
                        "%s 的回读与本次模拟输出一致（偏差 %.1f 米%s，带模拟标记）。"
                                + "这只说明该来源的缓存对得上；目标 App 是否在别的通道接收、是否采用这些位置，尚未验证。"
                                + "请运行多通道探针或做目标 App 对照测试。",
                        provider, Math.max(0d, distanceMeters), age);
            case MISMATCH:
                return String.format(Locale.US,
                        "%s 读回的位置和你提交的差了 %.1f 米%s，可能被其它定位源覆盖，或该 provider 不接受测试位置。",
                        provider, Math.max(0d, distanceMeters), age);
            case NOT_RECEIVED:
                return String.format(Locale.US,
                        "%s 还没有可读回的位置：先确认「设置检查」页显示允许模拟位置，再开始一次运行或传送。", provider);
            case STALE:
                return String.format(Locale.US,
                        "%s 读回的是 %.0f 秒前的缓存，只能说明「曾经收到过」，不能当作正在连续接收；"
                                + "请在运行中观察该来源的新回调（多通道探针可以看间隔）。",
                        provider, Math.max(0d, observedAgeMillis) / 1000d);
            case NOT_MARKED_MOCK:
                return String.format(Locale.US,
                        "%s 位置对得上（偏差 %.1f 米%s），但读回结果没有模拟标记。仅记录这一观测值："
                                + "可能是 ROM 差异，也可能读到的是别的定位来源，不能据此推断系统行为。",
                        provider, Math.max(0d, distanceMeters), age);
            case NO_EXPECTED:
            default:
                return "还没有提交过位置：先点「开始」或「传送」，再回来看这一项。";
        }
    }
}
