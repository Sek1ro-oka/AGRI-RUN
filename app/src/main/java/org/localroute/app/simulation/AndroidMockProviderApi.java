package org.localroute.app.simulation;

import android.annotation.SuppressLint;
import android.content.Context;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.util.Log;

import org.localroute.core.RunErrorCode;
import org.localroute.simulation.MockFix;
import org.localroute.simulation.MockProviderApi;
import org.localroute.simulation.MockProviderException;

/**
 * {@link MockProviderApi} 的 Android 实现：只调用系统 {@link LocationManager} 的测试 provider API。
 *
 * <p>注意：{@code addTestProvider} 是否成功取决于「开发者选项 → 选择模拟位置信息应用」是否选中本应用；
 * 未选中时系统抛 {@link SecurityException}，这里统一映射为
 * {@link RunErrorCode#MOCK_APP_NOT_SELECTED}，界面据此给出设置指引。
 */
public final class AndroidMockProviderApi implements MockProviderApi {

    private static final String TAG = "MockProviderApi";

    private final LocationManager locationManager;

    public AndroidMockProviderApi(Context context) {
        this.locationManager = (LocationManager) context.getApplicationContext()
                .getSystemService(Context.LOCATION_SERVICE);
    }

    @Override
    public boolean isProviderSupported(String provider) {
        if (provider == null || locationManager == null) {
            return false;
        }
        try {
            return locationManager.getAllProviders().contains(provider);
        } catch (RuntimeException e) {
            Log.w(TAG, "查询 provider 列表失败：" + provider, e);
            return false;
        }
    }

    @Override
    // 这个重载接收的是 Criteria 常量（ACCURACY_* / POWER_*），而 lint 按新注解
    // ProviderProperties 校验，必然误报；minSdk 29 又不能用 API 31 的新重载，故显式抑制。
    @SuppressLint("WrongConstant")
    public void addTestProvider(String provider) throws MockProviderException {
        requireManager();
        try {
            // 参数含义：requiresNetwork/requiresSatellite/requiresCell/hasMonetaryCost 均为 false，
            // 支持海拔、速度、方向；功耗与精度按 GPS 级别声明。
            locationManager.addTestProvider(provider,
                    false, false, false, false,
                    true, true, true,
                    Criteria.POWER_LOW, Criteria.ACCURACY_FINE);
        } catch (SecurityException e) {
            throw new MockProviderException(RunErrorCode.MOCK_APP_NOT_SELECTED,
                    "系统拒绝了测试 provider 请求：请先在开发者选项里把本应用设为模拟位置信息应用", e);
        } catch (IllegalArgumentException e) {
            throw new MockProviderException(RunErrorCode.PROVIDER_SETUP_FAILED,
                    "系统不接受该测试 provider：" + provider + "（" + e.getMessage() + "）", e);
        } catch (RuntimeException e) {
            throw new MockProviderException(RunErrorCode.PROVIDER_SETUP_FAILED,
                    "注册测试 provider 失败：" + provider + "（" + e.getMessage() + "）", e);
        }
    }

    @Override
    public void setTestProviderEnabled(String provider, boolean enabled) throws MockProviderException {
        requireManager();
        try {
            locationManager.setTestProviderEnabled(provider, enabled);
        } catch (SecurityException e) {
            throw new MockProviderException(RunErrorCode.MOCK_APP_NOT_SELECTED,
                    "系统拒绝了启用测试 provider：请确认本应用仍是模拟位置信息应用", e);
        } catch (RuntimeException e) {
            throw new MockProviderException(RunErrorCode.PROVIDER_SETUP_FAILED,
                    "启用测试 provider 失败：" + provider + "（" + e.getMessage() + "）", e);
        }
    }

    @Override
    public void setTestProviderLocation(String provider, MockFix fix) throws MockProviderException {
        requireManager();
        Location location = new Location(provider);
        location.setLatitude(fix.latitude());
        location.setLongitude(fix.longitude());
        location.setAltitude(fix.altitudeMeters());
        location.setAccuracy((float) fix.accuracyMeters());
        location.setSpeed((float) fix.speedMps());
        location.setBearing((float) fix.bearingDegrees());
        location.setTime(fix.timeMillis());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            location.setElapsedRealtimeNanos(fix.elapsedRealtimeNanos());
        }
        try {
            locationManager.setTestProviderLocation(provider, location);
        } catch (SecurityException e) {
            throw new MockProviderException(RunErrorCode.MOCK_APP_NOT_SELECTED,
                    "系统拒绝了模拟位置提交：请确认本应用在开发者选项中是模拟位置信息应用", e);
        } catch (RuntimeException e) {
            throw new MockProviderException(RunErrorCode.PUBLISH_FAILED,
                    "提交模拟位置失败：" + provider + "（" + e.getMessage() + "）", e);
        }
    }

    @Override
    public void removeTestProvider(String provider) {
        if (locationManager == null) {
            return;
        }
        try {
            locationManager.removeTestProvider(provider);
        } catch (RuntimeException e) {
            // 本来就不存在、或系统已回收：清理阶段不扩散异常（任务书要求停止幂等）。
            Log.d(TAG, "移除测试 provider 时忽略异常：" + provider + " / " + e);
        }
    }

    private void requireManager() throws MockProviderException {
        if (locationManager == null) {
            throw new MockProviderException(RunErrorCode.PROVIDER_SETUP_FAILED, "系统 LocationManager 不可用");
        }
    }
}
