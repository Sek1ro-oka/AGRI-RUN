package org.localroute.app.map;

import android.os.Bundle;

import androidx.annotation.Nullable;

import org.localroute.core.GeoPoint;

import java.util.List;

/**
 * 地图宿主抽象：把「画路线、画当前位置、取景、手势、生命周期」这五件事与具体地图引擎分开。
 *
 * <p>目前有两个实现：{@link RouteMapController} 用 MapLibre + 栅格瓦片（默认兜底，任何设备都能跑），
 * {@code AmapRouteMapView} 用高德官方 3D SDK（只有带 key 且架构匹配时才启用）。
 * 界面层只依赖本接口，所以换引擎不需要改 {@code MainActivity} 的业务逻辑。
 */
public interface RouteMapHost {

    /** 地图状态文案（加载中/失败原因），null 表示恢复正常。 */
    interface StatusListener {
        void onStatusChanged(@Nullable String message);
    }

    void registerGestureListener();

    void setStatusListener(@Nullable StatusListener listener);

    void renderRoute(List<GeoPoint> points, boolean closed);

    void fitRoute(List<GeoPoint> points);

    void renderRunner(@Nullable GeoPoint position);

    void followRunner(@Nullable GeoPoint position);

    @Nullable
    GeoPoint cameraCenter();

    boolean isVisible(GeoPoint point);

    void onStart();

    void onResume();

    void onPause();

    void onStop();

    void onDestroy();

    void onLowMemory();

    void onSaveInstanceState(Bundle outState);

    /** 供界面显示：当前引擎名称，例如「高德官方 SDK」或「MapLibre + 高德瓦片」。 */
    String engineLabel();
}
