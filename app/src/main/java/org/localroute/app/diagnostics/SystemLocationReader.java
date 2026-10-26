package org.localroute.app.diagnostics;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.SystemClock;

import androidx.core.content.ContextCompat;

import org.localroute.core.GeoPoint;

import java.util.ArrayList;
import java.util.List;

/**
 * 从系统读回「我现在到底在哪」——用来验证模拟位置是否真的生效。
 *
 * <p>只读 {@link LocationManager#getLastKnownLocation(String)}，不申请新权限、不启动定位监听：
 * 目的只是把应用提交出去的坐标和系统当前认定位置做对比（判定逻辑见
 * {@link org.localroute.simulation.LocationVerifier}）。
 */
public final class SystemLocationReader {

    /** 依次尝试的 provider：gps 是必测项，network / fused 在多数机型上是附加项。 */
    public static final String[] PROVIDERS = {"gps", LocationManager.NETWORK_PROVIDER, "fused"};

    private SystemLocationReader() {
    }

    /** 单个 provider 的读回结果。 */
    public static final class Probe {
        public final String provider;
        public final boolean available;
        public final Location location;
        public final String note;

        Probe(String provider, boolean available, Location location, String note) {
            this.provider = provider;
            this.available = available;
            this.location = location;
            this.note = note;
        }

        public boolean hasFix() {
            return location != null;
        }

        /** 位置时间距今多少毫秒；没有位置时返回 -1。 */
        public long ageMillis() {
            if (location == null) {
                return -1L;
            }
            return Math.max(0L, SystemClock.elapsedRealtimeNanos() / 1_000_000L
                    - location.getElapsedRealtimeNanos() / 1_000_000L);
        }

        public boolean markedMock() {
            if (location == null) {
                return false;
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                return location.isMock();
            }
            return location.isFromMockProvider();
        }

        public GeoPoint point() {
            return location == null ? null : new GeoPoint(location.getLatitude(), location.getLongitude());
        }
    }

    public static boolean hasFinePermission(Context context) {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** 读回所有候选 provider 的最近位置；没有定位权限时返回空列表。 */
    public static List<Probe> readAll(Context context) {
        List<Probe> probes = new ArrayList<>(PROVIDERS.length);
        if (!hasFinePermission(context)) {
            return probes;
        }
        LocationManager manager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (manager == null) {
            return probes;
        }
        for (String provider : PROVIDERS) {
            boolean available;
            try {
                available = manager.getProvider(provider) != null;
            } catch (RuntimeException e) {
                available = false;
            }
            if (!available) {
                probes.add(new Probe(provider, false, null, "本机没有这个 provider"));
                continue;
            }
            Location location = null;
            try {
                location = manager.getLastKnownLocation(provider);
            } catch (SecurityException e) {
                probes.add(new Probe(provider, true, null, "没有定位权限"));
                continue;
            } catch (RuntimeException e) {
                probes.add(new Probe(provider, true, null, "读取失败：" + e.getClass().getSimpleName()));
                continue;
            }
            probes.add(new Probe(provider, true, location,
                    location == null ? "系统没有缓存位置" : "已读回"));
        }
        return probes;
    }
}
