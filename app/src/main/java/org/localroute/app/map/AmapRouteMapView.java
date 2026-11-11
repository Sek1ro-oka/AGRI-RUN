package org.localroute.app.map;

import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;

import com.amap.api.maps.AMap;
import com.amap.api.maps.CameraUpdateFactory;
import com.amap.api.maps.MapView;
import com.amap.api.maps.MapsInitializer;
import com.amap.api.maps.model.BitmapDescriptorFactory;
import com.amap.api.maps.model.LatLng;
import com.amap.api.maps.model.LatLngBounds;
import com.amap.api.maps.model.Marker;
import com.amap.api.maps.model.MarkerOptions;
import com.amap.api.maps.model.Polyline;
import com.amap.api.maps.model.PolylineOptions;

import org.localroute.app.diagnostics.DiagnosticLog;
import org.localroute.core.ChinaShift;
import org.localroute.core.GeoPoint;

import java.util.ArrayList;
import java.util.List;

/**
 * 高德官方 3D SDK 版地图宿主。
 *
 * <p>使用条件（缺一不可）：`local.properties` 里填了 AMAP_KEY、设备 CPU 架构带 arm64-v8a 或
 * armeabi-v7a（高德 SDK 只提供 arm 动态库）。条件不满足时 {@code MainActivity} 会退回
 * {@link RouteMapController}，所以模拟器（x86_64）永远走栅格瓦片那条路。
 *
 * <p>坐标：高德底图是 GCJ-02，引擎内部一律用它；界面与存储层用 WGS-84，
 * 进出本类都要用 {@link ChinaShift} 换算（点击点、相机中心、可见区判断）。
 */
public final class AmapRouteMapView implements RouteMapHost {

    private static final int ROUTE_COLOR = Color.parseColor("#1B5E20");
    private static final float ROUTE_WIDTH = 8f;
    private static final int FIT_PADDING_DP = 80;

    private final MapView mapView;
    private final Context context;
    private final RouteMapController.OnMapClickListener clickListener;
    private final float density;
    private final List<Polyline> routeLines = new ArrayList<>();
    private final List<Marker> vertexMarkers = new ArrayList<>();

    @Nullable
    private AMap map;
    @Nullable
    private Marker startMarker;
    @Nullable
    private Marker runnerMarker;
    @Nullable
    private StatusListener statusListener;
    @Nullable
    private GeoPoint following;
    private boolean followingEnabled;
    private boolean setupDone;
    private String lastStatus = "";

    public AmapRouteMapView(Context context, FrameLayout container,
                            RouteMapController.OnMapClickListener listener) {
        this.context = context.getApplicationContext();
        this.clickListener = listener;
        this.density = context.getResources().getDisplayMetrics().density;
        // 10.x 起有隐私合规开关，不调用会只画底色、不加载瓦片。
        try {
            MapsInitializer.updatePrivacyShow(context, true, true);
            MapsInitializer.updatePrivacyAgree(context, true);
        } catch (Throwable ignored) {
            // 老版本 SDK 没有这两个方法，忽略即可。
        }
        this.mapView = new MapView(context);
        container.addView(mapView, 0, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mapView.onCreate(null);
        // 注意：getMap() 在 onCreate 之后仍可能返回 null（SDK 内部异步初始化），
        // 真机上就曾因此整块地图空白且收不到点击。这里先试一次，失败则等 onResume 再试。
        this.map = mapView.getMap();
        if (this.map != null) {
            setup();
        } else if (DiagnosticLog.isCoordinateLoggingEnabled()) {
            DiagnosticLog.info("高德 MapView.getMap() 暂时为 null，等 onResume 再取一次");
        }
    }

    private void setup() {
        if (setupDone) {
            return;
        }
        if (map == null) {
            notifyStatus("高德 SDK 还没准备好（getMap 返回 null），稍后会自动重试；仍不行请改用瓦片底图");
            return;
        }
        setupDone = true;
        map.getUiSettings().setZoomControlsEnabled(false);
        map.getUiSettings().setScaleControlsEnabled(true);
        map.setOnMapClickListener(latLng -> {
            if (clickListener == null) {
                return;
            }
            GeoPoint wgs = ChinaShift.gcj02ToWgs84(latLng.latitude, latLng.longitude);
            clickListener.onMapClick(wgs);
        });
        // 用户一碰地图就停止跟随，逻辑与 MapLibre 版保持一致。
        map.setOnMapTouchListener((MotionEvent event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                followingEnabled = false;
            }
        });
        notifyStatus(null);
    }

    @Override
    public String engineLabel() {
        return "高德官方 SDK";
    }

    @Override
    public void registerGestureListener() {
        // 触摸监听已在 setup() 里注册，这里保留接口形状。
    }

    @Override
    public void setStatusListener(@Nullable StatusListener listener) {
        this.statusListener = listener;
        if (listener != null && !lastStatus.isEmpty()) {
            listener.onStatusChanged(lastStatus);
        }
    }

    private void notifyStatus(@Nullable String message) {
        lastStatus = message == null ? "" : message;
        if (statusListener != null) {
            statusListener.onStatusChanged(message);
        }
    }

    @Override
    public void renderRoute(List<GeoPoint> points, boolean closed) {
        renderRoute(points, closed, true);
    }

    @Override
    public void renderRoute(List<GeoPoint> points, boolean closed, boolean showVertices) {
        clearRoute();
        if (map == null || points == null || points.isEmpty()) {
            return;
        }
        List<LatLng> line = new ArrayList<>(closed && points.size() > 1 ? points.size() + 1 : points.size());
        for (GeoPoint point : points) {
            line.add(toDisplay(point));
        }
        if (closed && points.size() > 1) {
            line.add(toDisplay(points.get(0)));
        }
        Polyline polyline = map.addPolyline(new PolylineOptions()
                .addAll(line)
                .color(ROUTE_COLOR)
                .width(ROUTE_WIDTH * density / 3f));
        if (polyline != null) {
            routeLines.add(polyline);
        }
        // 绘制/编辑阶段每个航点都用与起点相同的橙色图标；完成后只留起点。
        for (int i = 0; i < points.size(); i++) {
            if (i > 0 && !showVertices) {
                break;
            }
            Marker marker = map.addMarker(new MarkerOptions()
                    .position(toDisplay(points.get(i)))
                    .anchor(0.5f, 0.5f)
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_ORANGE)));
            if (marker == null) {
                continue;
            }
            vertexMarkers.add(marker);
            if (i == 0) {
                startMarker = marker;
            }
        }
    }

    private void clearRoute() {
        for (Polyline line : routeLines) {
            line.remove();
        }
        routeLines.clear();
        for (Marker marker : vertexMarkers) {
            marker.remove();
        }
        vertexMarkers.clear();
        startMarker = null;
    }

    @Override
    public void fitRoute(List<GeoPoint> points) {
        if (map == null || points == null || points.isEmpty()) {
            return;
        }
        if (points.size() == 1) {
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(toDisplay(points.get(0)), 16f));
            return;
        }
        LatLngBounds.Builder builder = new LatLngBounds.Builder();
        for (GeoPoint point : points) {
            builder.include(toDisplay(point));
        }
        try {
            map.animateCamera(CameraUpdateFactory.newLatLngBounds(builder.build(), dp(FIT_PADDING_DP)));
        } catch (RuntimeException ignored) {
            // 两点过近时高德会抛异常，退化成直接缩放。
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(toDisplay(points.get(0)), 16f));
        }
    }

    @Override
    public void focusOn(GeoPoint point) {
        if (map == null) {
            return;
        }
        followingEnabled = false;
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(toDisplay(point), 16f));
    }
    @Override
    public void renderRunner(@Nullable GeoPoint position) {
        if (map == null) {
            return;
        }
        if (position == null) {
            if (runnerMarker != null) {
                runnerMarker.remove();
                runnerMarker = null;
            }
            return;
        }
        LatLng latLng = toDisplay(position);
        if (runnerMarker == null) {
            runnerMarker = map.addMarker(new MarkerOptions()
                    .position(latLng)
                    .anchor(0.5f, 0.5f)
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)));
        } else {
            runnerMarker.setPosition(latLng);
        }
    }

    @Override
    public void followRunner(@Nullable GeoPoint position) {
        following = position;
        if (position == null) {
            followingEnabled = false;
            return;
        }
        if (map == null) {
            return;
        }
        followingEnabled = true;
        map.animateCamera(CameraUpdateFactory.newLatLng(toDisplay(position)));
    }

    @Nullable
    @Override
    public GeoPoint cameraCenter() {
        if (map == null) {
            return null;
        }
        LatLng target = map.getCameraPosition().target;
        if (target == null) {
            return null;
        }
        return ChinaShift.gcj02ToWgs84(target.latitude, target.longitude);
    }

    @Override
    public boolean isVisible(GeoPoint point) {
        if (map == null) {
            return false;
        }
        LatLngBounds bounds = map.getProjection().getVisibleRegion().latLngBounds;
        return bounds != null && bounds.contains(toDisplay(point));
    }

    private LatLng toDisplay(GeoPoint point) {
        GeoPoint gcj = ChinaShift.wgs84ToGcj02(point.latitude(), point.longitude());
        return new LatLng(gcj.latitude(), gcj.longitude());
    }

    private int dp(int dp) {
        return Math.round(dp * density);
    }

    @Override
    public void onStart() {
        // 高德 MapView 没有 onStart/onStop，开头结尾的可见性由 onResume/onPause 覆盖。
    }

    @Override
    public void onResume() {
        mapView.onResume();
        if (!setupDone) {
            // getMap 可能在构造时为 null，onResume 之后才有值；补上一次 setup。
            map = mapView.getMap();
            if (map != null) {
                setup();
            }
        }
    }

    @Override
    public void onPause() {
        mapView.onPause();
    }

    @Override
    public void onStop() {
        // 同上：高德 SDK 只暴露 onCreate/onResume/onPause/onDestroy/onSaveInstanceState。
    }

    @Override
    public void onDestroy() {
        mapView.onDestroy();
    }

    @Override
    public void onLowMemory() {
        // 高德 MapView 没有暴露 onLowMemory，交给系统回收图片缓存即可。
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        mapView.onSaveInstanceState(outState);
    }
}
