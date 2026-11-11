package org.localroute.app.map;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 底图图层偏好的唯一存储点。
 *
 * <p>MapLibre 栅格瓦片与高德官方 SDK 共用同一个值，切换引擎后图层保持一致。
 * 新版默认卫星影像；旧版本留下的 {@code tile_source} 键不再读取。
 */
final class MapBaseLayerStore {

    private static final String PREFS = "map";
    private static final String KEY_BASE_LAYER = "base_layer";
    private static final String VALUE_STANDARD = "standard";
    private static final String VALUE_SATELLITE = "satellite";

    private MapBaseLayerStore() {
    }

    static RouteMapHost.BaseLayer load(Context context) {
        String value = prefs(context).getString(KEY_BASE_LAYER, VALUE_SATELLITE);
        return VALUE_STANDARD.equals(value)
                ? RouteMapHost.BaseLayer.STANDARD
                : RouteMapHost.BaseLayer.SATELLITE;
    }

    static void save(Context context, RouteMapHost.BaseLayer layer) {
        prefs(context).edit()
                .putString(KEY_BASE_LAYER, layer == RouteMapHost.BaseLayer.STANDARD
                        ? VALUE_STANDARD : VALUE_SATELLITE)
                .apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
