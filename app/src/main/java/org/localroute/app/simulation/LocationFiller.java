package org.localroute.app.simulation;

import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.SystemClock;

import org.localroute.simulation.FixQualityProfile;
import org.localroute.simulation.MockFix;

/**
 * 把 {@link MockFix} 一次性填进 {@link android.location.Location}。
 *
 * <p>framework 测试 provider 与 GMS FLP 两条后端以前各写一份同样的填充代码，字段容易走偏；
 * 现在只在这里填，两条通道输出的字段保证一致。
 *
 * <p>除位置/精度/速度/航向/时间戳外，还声明：
 * <ul>
 *   <li>垂直精度、速度精度、航向精度（API 26+，minSdk 29 恒可用）——
 *       让目标 App 的 {@code hasSpeedAccuracy()} / {@code hasBearingAccuracy()} 不再为假；</li>
 *   <li>elapsed realtime 不确定度（API 29+）；</li>
 *   <li>extras：{@code satellites} / {@code meanCn0} / {@code maxCn0} ——
 *       部分 App 用卫星数判断“是不是真 GPS 定位”，缺字段时可能不收里程。</li>
 * </ul>
 *
 * <p>network 来源的定位本来就没有卫星，所以只在非 network 来源上写卫星相关 extras，
 * 免得给目标 App 一个自相矛盾的字段组合。
 */
public final class LocationFiller {

    /** elapsed realtime 不确定度：1 毫秒，表示时间戳基本准确。 */
    private static final long ELAPSED_UNCERTAINTY_NANOS = 1_000_000L;

    private LocationFiller() {
    }

    public static void fill(Location location, MockFix fix) {
        FixQualityProfile quality = fix.quality();
        location.setLatitude(fix.latitude());
        location.setLongitude(fix.longitude());
        location.setAltitude(fix.altitudeMeters());
        location.setAccuracy((float) fix.accuracyMeters());
        location.setVerticalAccuracyMeters((float) quality.verticalAccuracyMeters());
        location.setSpeed((float) fix.speedMps());
        location.setSpeedAccuracyMetersPerSecond((float) quality.speedAccuracyMps());
        location.setBearing((float) fix.bearingDegrees());
        location.setBearingAccuracyDegrees((float) quality.bearingAccuracyDegrees());
        location.setTime(fix.timeMillis());
        location.setElapsedRealtimeNanos(fix.elapsedRealtimeNanos() > 0L
                ? fix.elapsedRealtimeNanos() : SystemClock.elapsedRealtimeNanos());
        location.setElapsedRealtimeUncertaintyNanos(ELAPSED_UNCERTAINTY_NANOS);
        if (!LocationManager.NETWORK_PROVIDER.equals(location.getProvider())) {
            Bundle extras = new Bundle();
            extras.putInt("satellites", quality.satellites());
            extras.putDouble("meanCn0", quality.meanCn0DbHz());
            extras.putDouble("maxCn0", quality.maxCn0DbHz());
            location.setExtras(extras);
        }
    }
}
