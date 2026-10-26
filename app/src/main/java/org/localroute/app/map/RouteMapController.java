package org.localroute.app.map;

import android.content.Context;
import android.graphics.Color;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.localroute.core.GeoPoint;
import org.maplibre.android.camera.CameraUpdateFactory;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.geometry.LatLngBounds;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.MapView;
import org.maplibre.android.maps.Style;
import org.maplibre.android.style.layers.CircleLayer;
import org.maplibre.android.style.layers.LineLayer;
import org.maplibre.android.style.layers.Property;
import org.maplibre.android.style.layers.PropertyFactory;
import org.maplibre.android.style.sources.GeoJsonSource;
import org.maplibre.geojson.Feature;
import org.maplibre.geojson.FeatureCollection;
import org.maplibre.geojson.LineString;
import org.maplibre.geojson.Point;

import java.util.ArrayList;
import java.util.List;

/**
 * MapLibre 地图封装：只负责“画路线 + 画当前模拟位置 + 把点击坐标交回界面”。
 *
 * <p>瓦片使用 OpenStreetMap 官方栅格服务，遵守其使用政策：保留署名、按需加载、
 * 不做批量预取；若将来高频使用应换成自建或商业瓦片服务（见交付文档）。
 */
public final class RouteMapController {

    private static final String SOURCE_LINE = "route-line";
    private static final String SOURCE_VERTICES = "route-vertices";
    private static final String SOURCE_RUNNER = "run-position";
    private static final String LAYER_LINE = "route-line-layer";
    private static final String LAYER_VERTICES = "route-vertices-layer";
    private static final String LAYER_START = "route-start-layer";
    private static final String LAYER_RUNNER = "run-position-layer";

    /** 最小可用栅格样式：只依赖 OSM 官方瓦片，避免引入额外样式仓库。 */
    private static final String OSM_RASTER_STYLE = "{"
            + "\"version\":8,"
            + "\"name\":\"OSM Raster\","
            + "\"sources\":{"
            + "\"osm\":{"
            + "\"type\":\"raster\","
            + "\"tiles\":[\"https://tile.openstreetmap.org/{z}/{x}/{y}.png\"],"
            + "\"tileSize\":256,"
            + "\"maxzoom\":19,"
            + "\"attribution\":\"© OpenStreetMap contributors\""
            + "}},"
            + "\"layers\":[{\"id\":\"osm\",\"type\":\"raster\",\"source\":\"osm\",\"minzoom\":0,\"maxzoom\":20}]"
            + "}";

    private final MapView mapView;
    @Nullable
    private MapLibreMap map;
    private final List<GeoPoint> currentPoints = new ArrayList<>();

    @Nullable
    private Style style;
    private OnMapClickListener clickListener;
    private boolean followRunner = true;

    public interface OnMapClickListener {
        void onMapClick(GeoPoint point);
    }

    private RouteMapController(MapView mapView) {
        this.mapView = mapView;
        // MapView 必须先 onCreate，之后 getMapAsync 才会回调；map 字段在回调里赋值。
        mapView.onCreate(null);
        mapView.getMapAsync(this::onMapReady);
    }

    /** 在 Activity.onCreate 中调用（MapView 生命周期由本类转发）。 */
    public static RouteMapController attach(MapView mapView, OnMapClickListener listener) {
        RouteMapController controller = new RouteMapController(mapView);
        controller.clickListener = listener;
        return controller;
    }

    public void onMapReady(@NonNull MapLibreMap mapLibreMap) {
        this.map = mapLibreMap;
        mapLibreMap.setStyle(new Style.Builder().fromJson(OSM_RASTER_STYLE), loaded -> {
            style = loaded;
            addSources(loaded);
            renderRoute(currentPoints);
        });
        mapLibreMap.getUiSettings().setAttributionEnabled(true);
        mapLibreMap.getUiSettings().setLogoEnabled(false);
        mapLibreMap.getUiSettings().setCompassEnabled(true);
        mapLibreMap.addOnMapClickListener(latLng -> {
            if (clickListener == null) {
                return false;
            }
            clickListener.onMapClick(new GeoPoint(latLng.getLatitude(), latLng.getLongitude()));
            return true;
        });
    }

    private void addSources(@NonNull Style loaded) {
        loaded.addSource(new GeoJsonSource(SOURCE_LINE));
        loaded.addSource(new GeoJsonSource(SOURCE_VERTICES));
        loaded.addSource(new GeoJsonSource(SOURCE_RUNNER));
        loaded.addLayer(new LineLayer(LAYER_LINE, SOURCE_LINE)
                .withProperties(
                        PropertyFactory.lineColor(Color.parseColor("#1B5E20")),
                        PropertyFactory.lineWidth(5f),
                        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND)));
        loaded.addLayer(new CircleLayer(LAYER_VERTICES, SOURCE_VERTICES)
                .withProperties(
                        PropertyFactory.circleColor(Color.parseColor("#0D47A1")),
                        PropertyFactory.circleRadius(5f),
                        PropertyFactory.circleStrokeWidth(2f),
                        PropertyFactory.circleStrokeColor(Color.WHITE)));
        loaded.addLayer(new CircleLayer(LAYER_START, SOURCE_VERTICES)
                .withProperties(
                        PropertyFactory.circleColor(Color.parseColor("#E65100")),
                        PropertyFactory.circleRadius(8f)));
        loaded.addLayer(new CircleLayer(LAYER_RUNNER, SOURCE_RUNNER)
                .withProperties(
                        PropertyFactory.circleColor(Color.parseColor("#D32F2F")),
                        PropertyFactory.circleRadius(9f),
                        PropertyFactory.circleStrokeWidth(3f),
                        PropertyFactory.circleStrokeColor(Color.WHITE)));
    }

    /** 更新路线折线（自动闭合）与节点。 */
    public void renderRoute(@NonNull List<GeoPoint> points) {
        currentPoints.clear();
        currentPoints.addAll(points);
        if (style == null) {
            return;
        }
        List<Point> ring = new ArrayList<>(points.size() + 1);
        List<Feature> lineFeatures = new ArrayList<>();
        if (points.size() >= 2) {
            for (GeoPoint point : points) {
                ring.add(Point.fromLngLat(point.longitude(), point.latitude()));
            }
            ring.add(Point.fromLngLat(points.get(0).longitude(), points.get(0).latitude()));
            lineFeatures.add(Feature.fromGeometry(LineString.fromLngLats(ring)));
        }
        setGeoJson(SOURCE_LINE, FeatureCollection.fromFeatures(lineFeatures));

        List<Feature> vertexFeatures = new ArrayList<>();
        for (GeoPoint point : points) {
            vertexFeatures.add(Feature.fromGeometry(
                    Point.fromLngLat(point.longitude(), point.latitude())));
        }
        setGeoJson(SOURCE_VERTICES, FeatureCollection.fromFeatures(vertexFeatures));
    }

    /** 更新当前模拟位置；{@code position == null} 时清除。 */
    public void renderRunner(@Nullable GeoPoint position) {
        if (style == null) {
            return;
        }
        if (position == null) {
            setGeoJson(SOURCE_RUNNER, FeatureCollection.fromFeatures(new Feature[0]));
            return;
        }
        setGeoJson(SOURCE_RUNNER, FeatureCollection.fromFeatures(new Feature[]{
                Feature.fromGeometry(Point.fromLngLat(position.longitude(), position.latitude()))}));
    }

    private void setGeoJson(String sourceId, FeatureCollection collection) {
        if (style == null) {
            return;
        }
        GeoJsonSource source = style.getSourceAs(sourceId);
        if (source != null) {
            source.setGeoJson(collection);
        }
    }

    /** 视野包住整条路线。 */
    public void fitRoute(@NonNull List<GeoPoint> points) {
        if (map == null || points.isEmpty()) {
            return;
        }
        LatLngBounds.Builder builder = new LatLngBounds.Builder();
        for (GeoPoint point : points) {
            builder.include(new LatLng(point.latitude(), point.longitude()));
        }
        try {
            map.animateCamera(CameraUpdateFactory.newLatLngBounds(builder.build(), 96));
        } catch (RuntimeException ignored) {
            // 单点路线无法计算边界：直接居中。
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(
                    new LatLng(points.get(0).latitude(), points.get(0).longitude()), 17f));
        }
    }

    /** 跟随当前模拟位置；用户手动拖动地图后自动关闭。 */
    public void followRunner(@NonNull GeoPoint position) {
        if (map == null || !followRunner) {
            return;
        }
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(
                new LatLng(position.latitude(), position.longitude()), Math.max(16f, map.getCameraPosition().zoom)));
    }

    public void setFollowRunner(boolean follow) {
        this.followRunner = follow;
    }

    public boolean isFollowRunner() {
        return followRunner;
    }

    /** 用户手势造成的相机移动应停止跟随；程序移动不算。 */
    public void registerGestureListener() {
        if (map == null) {
            return;
        }
        map.addOnCameraMoveStartedListener(reason -> {
            if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                followRunner = false;
            }
        });
    }

    public void onSaveInstanceState(@NonNull android.os.Bundle bundle) {
        mapView.onSaveInstanceState(bundle);
    }

    public void onStart() {
        mapView.onStart();
    }

    public void onResume() {
        mapView.onResume();
    }

    public void onPause() {
        mapView.onPause();
    }

    public void onStop() {
        mapView.onStop();
    }

    public void onDestroy() {
        mapView.onDestroy();
    }

    public void onLowMemory() {
        mapView.onLowMemory();
    }

    public Context context() {
        return mapView.getContext();
    }
}
