package org.localroute.app.map;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.localroute.app.diagnostics.DiagnosticLog;
import org.localroute.core.ChinaShift;
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
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * MapLibre 地图封装：只负责“画路线 + 画当前模拟位置 + 把点击坐标交回界面”。
 *
 * <p>瓦片使用 OpenStreetMap 生态的公开栅格服务，遵守其使用政策：保留署名、按需加载、
 * 不做批量预取；若将来高频使用应换成自建或商业瓦片服务（见交付文档）。
 *
 * <p>0.1.1 起加了三道兜底，避免用户看到“一片黑”却没有任何解释：
 * <ol>
 *   <li>样式里先放一层浅色 {@code background}，瓦片就算全部失败也不会显示成黑屏；</li>
 *   <li>样式 10 秒内没加载成功就通过 {@link StatusListener} 把原因交给界面显示；</li>
 *   <li>提供多个瓦片源，用户可以在界面里切换并重试。</li>
 * </ol>
 */
public final class RouteMapController {

    private static final String SOURCE_LINE = "route-line";
    private static final String SOURCE_VERTICES = "route-vertices";
    private static final String SOURCE_RUNNER = "run-position";
    private static final String LAYER_BACKGROUND = "background";
    private static final String LAYER_RASTER = "osm-raster";
    private static final String LAYER_LINE = "route-line-layer";
    private static final String LAYER_VERTICES = "route-vertices-layer";
    private static final String LAYER_START = "route-start-layer";
    private static final String LAYER_RUNNER = "run-position-layer";

    /** 样式加载的看门狗：超过这个时间还没回调，就认为瓦片/样式加载失败。 */
    private static final long STYLE_TIMEOUT_MS = 10_000L;

    private static final String PREFS = "map";
    private static final String PREF_TILE_SOURCE = "tile_source";

    /** 可选瓦片源。前三个是公开的 OpenStreetMap 栅格服务；「高德」是 GCJ-02 坐标系的国内瓦片。 */
    private static final List<TileSource> TILE_SOURCES = Arrays.asList(
            new TileSource("osm", "OpenStreetMap 官方", CoordSystem.WGS84, 19,
                    "© OpenStreetMap contributors",
                    "https://tile.openstreetmap.org/{z}/{x}/{y}.png"),
            new TileSource("amap", "高德地图（GCJ-02，非官方接口）", CoordSystem.GCJ02, 18,
                    "© 高德地图",
                    "https://webrd01.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x={x}&y={y}&z={z}",
                    "https://webrd02.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x={x}&y={y}&z={z}",
                    "https://webrd03.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x={x}&y={y}&z={z}",
                    "https://webrd04.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x={x}&y={y}&z={z}"),
            new TileSource("osmfr", "OSM France（备用）", CoordSystem.WGS84, 19,
                    "© OpenStreetMap contributors",
                    "https://a.tile.openstreetmap.fr/osmfr/{z}/{x}/{y}.png"),
            new TileSource("osmde", "OSM Germany（备用）", CoordSystem.WGS84, 18,
                    "© OpenStreetMap contributors",
                    "https://tile.openstreetmap.de/{z}/{x}/{y}.png"));

    /** 瓦片底图的坐标系。应用内部一律 WGS-84，只有画/点 GCJ-02 底图时才需要换算。 */
    public enum CoordSystem {
        WGS84,
        GCJ02
    }


    private final MapView mapView;
    @Nullable
    private MapLibreMap map;
    private final List<GeoPoint> currentPoints = new ArrayList<>();

    @Nullable
    private Style style;
    private OnMapClickListener clickListener;
    private boolean followRunner = true;
    private boolean gestureListenerWanted;
    private boolean gestureListenerAttached;
    private int tileSourceIndex;

    @Nullable
    private Runnable styleTimeout;
    @Nullable
    private StatusListener statusListener;
    private String lastStatus = "";

    /** 瓦片源描述。 */
    public static final class TileSource {
        private final String id;
        private final String label;
        private final CoordSystem coords;
        private final int maxZoom;
        private final String attribution;
        private final List<String> tileUrls;

        TileSource(String id, String label, CoordSystem coords, int maxZoom,
                   String attribution, String... tileUrls) {
            this.id = id;
            this.label = label;
            this.coords = coords;
            this.maxZoom = maxZoom;
            this.attribution = attribution;
            this.tileUrls = Arrays.asList(tileUrls);
        }

        public String id() {
            return id;
        }

        public String label() {
            return label;
        }

        public CoordSystem coords() {
            return coords;
        }

        /** 第一个模板（诊断/日志用）。 */
        public String urlTemplate() {
            return tileUrls.get(0);
        }

        public List<String> tileUrls() {
            return tileUrls;
        }

        public String attribution() {
            return attribution;
        }

        public int maxZoom() {
            return maxZoom;
        }
    }

    public interface OnMapClickListener {
        void onMapClick(GeoPoint point);
    }

    /** 地图状态回调：{@code message == null} 表示已就绪。 */
    public interface StatusListener {
        void onStatusChanged(@Nullable String message);
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

    public void onMapReady(@NonNull MapLibreMap mapLibreMap) {
        this.map = mapLibreMap;
        tileSourceIndex = loadTileSourceIndex();

        mapLibreMap.getUiSettings().setAttributionEnabled(true);
        mapLibreMap.getUiSettings().setLogoEnabled(false);
        mapLibreMap.getUiSettings().setCompassEnabled(true);
        mapLibreMap.addOnMapClickListener(latLng -> {
            if (clickListener == null) {
                return false;
            }
            clickListener.onMapClick(fromDisplay(latLng.getLatitude(), latLng.getLongitude()));
            return true;
        });
        // 界面可能在 map 就绪之前就调用了 registerGestureListener，这里补挂一次。
        attachGestureListenerIfReady();
        applyStyle();
    }

    /** 用当前瓦片源重建样式，并挂上看门狗。 */
    private void applyStyle() {
        if (map == null) {
            return;
        }
        style = null;
        final TileSource source = currentTileSource();
        notifyStatus("正在加载 " + source.label() + " 瓦片…");
        DiagnosticLog.info("加载地图样式：" + source.label() + " / " + source.urlTemplate());

        cancelStyleTimeout();
        styleTimeout = () -> {
            if (style == null) {
                String message = String.format(Locale.US,
                        "加载 %s 超过 %d 秒仍未完成（可能是网络无法访问该瓦片服务）",
                        source.label(), STYLE_TIMEOUT_MS / 1000L);
                DiagnosticLog.warn("地图样式加载超时：" + message);
                notifyStatus(message);
            }
        };
        mapView.postDelayed(styleTimeout, STYLE_TIMEOUT_MS);

        map.setStyle(new Style.Builder().fromJson(buildStyleJson(source)), loaded -> {
            cancelStyleTimeout();
            style = loaded;
            addSources(loaded);
            renderRoute(currentPoints);
            DiagnosticLog.info("地图样式已加载：" + source.label());
            notifyStatus(null);
        });
    }

    private void cancelStyleTimeout() {
        if (styleTimeout != null) {
            mapView.removeCallbacks(styleTimeout);
            styleTimeout = null;
        }
    }

    /**
     * 极简栅格样式：一层浅色背景 + 一层栅格瓦片。
     *
     * <p>背景层很关键：瓦片服务器不可达时 MapLibre 默认会露出清屏色（黑），
     * 有了背景层至少能看到“有底图、只是没瓦片”，再配合状态提示就能定位问题。
     */
    private static String buildStyleJson(TileSource source) {
        StringBuilder tiles = new StringBuilder("[");
        for (int i = 0; i < source.tileUrls().size(); i++) {
            if (i > 0) {
                tiles.append(',');
            }
            tiles.append('"').append(source.tileUrls().get(i)).append('"');
        }
        tiles.append(']');
        return "{"
                + "\"version\":8,"
                + "\"name\":\"Raster " + source.id() + "\","
                + "\"sources\":{"
                + "\"osm\":{"
                + "\"type\":\"raster\","
                + "\"tiles\":" + tiles + ","
                + "\"tileSize\":256,"
                + "\"maxzoom\":" + source.maxZoom() + ","
                + "\"attribution\":\"" + source.attribution() + "\""
                + "}},"
                + "\"layers\":["
                + "{\"id\":\"" + LAYER_BACKGROUND + "\",\"type\":\"background\","
                + "\"paint\":{\"background-color\":\"#E9EDF2\"}},"
                + "{\"id\":\"" + LAYER_RASTER + "\",\"type\":\"raster\",\"source\":\"osm\","
                + "\"minzoom\":0,\"maxzoom\":" + (source.maxZoom() + 1) + "}"
                + "]}";
    }

    /** 供界面/诊断页展示的瓦片源列表。 */
    public static List<TileSource> tileSources() {
        return new ArrayList<>(TILE_SOURCES);
    }

    public TileSource currentTileSource() {
        return TILE_SOURCES.get(clampIndex(tileSourceIndex));
    }

    public int currentTileSourceIndex() {
        return clampIndex(tileSourceIndex);
    }

    private boolean usesGcj02() {
        return currentTileSource().coords() == CoordSystem.GCJ02;
    }

    /** 应用内部坐标（WGS-84）→ 当前底图坐标；高德一类 GCJ-02 底图需要先偏移。 */
    private LatLng toDisplay(@NonNull GeoPoint point) {
        if (usesGcj02()) {
            GeoPoint shifted = ChinaShift.wgs84ToGcj02(point.latitude(), point.longitude());
            return new LatLng(shifted.latitude(), shifted.longitude());
        }
        return new LatLng(point.latitude(), point.longitude());
    }

    /** 底图坐标 → 应用内部 WGS-84；用户在高德底图上点选的位置要转回来再存。 */
    private GeoPoint fromDisplay(double latitude, double longitude) {
        if (usesGcj02()) {
            GeoPoint wgs = ChinaShift.gcj02ToWgs84(latitude, longitude);
            return new GeoPoint(wgs.latitude(), wgs.longitude());
        }
        return new GeoPoint(latitude, longitude);
    }

    private Point toDisplayPoint(@NonNull GeoPoint point) {
        LatLng display = toDisplay(point);
        return Point.fromLngLat(display.getLongitude(), display.getLatitude());
    }

    /** 切换瓦片源并立即重试；返回是否真的切换了。 */
    public boolean selectTileSource(int index) {
        int clamped = clampIndex(index);
        if (clamped == clampIndex(tileSourceIndex)) {
            return false;
        }
        tileSourceIndex = clamped;
        saveTileSourceIndex(clamped);
        applyStyle();
        return true;
    }

    private static int clampIndex(int index) {
        if (index < 0 || index >= TILE_SOURCES.size()) {
            return 0;
        }
        return index;
    }

    private int loadTileSourceIndex() {
        SharedPreferences prefs = mapView.getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String id = prefs.getString(PREF_TILE_SOURCE, TILE_SOURCES.get(0).id());
        for (int i = 0; i < TILE_SOURCES.size(); i++) {
            if (TILE_SOURCES.get(i).id().equals(id)) {
                return i;
            }
        }
        return 0;
    }

    private void saveTileSourceIndex(int index) {
        mapView.getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(PREF_TILE_SOURCE, TILE_SOURCES.get(index).id()).apply();
    }

    private void addSources(@NonNull Style loaded) {
        loaded.addSource(new GeoJsonSource(SOURCE_LINE));
        loaded.addSource(new GeoJsonSource(SOURCE_VERTICES));
        loaded.addSource(new GeoJsonSource(SOURCE_RUNNER));
        loaded.addLayer(new LineLayer(LAYER_LINE, SOURCE_LINE)
                .withProperties(
                        PropertyFactory.lineColor(0xFF1B5E20),
                        PropertyFactory.lineWidth(5f),
                        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND)));
        loaded.addLayer(new CircleLayer(LAYER_VERTICES, SOURCE_VERTICES)
                .withProperties(
                        PropertyFactory.circleColor(0xFF0D47A1),
                        PropertyFactory.circleRadius(5f),
                        PropertyFactory.circleStrokeWidth(2f),
                        PropertyFactory.circleStrokeColor(0xFFFFFFFF)));
        loaded.addLayer(new CircleLayer(LAYER_START, SOURCE_VERTICES)
                .withProperties(
                        PropertyFactory.circleColor(0xFFE65100),
                        PropertyFactory.circleRadius(8f)));
        loaded.addLayer(new CircleLayer(LAYER_RUNNER, SOURCE_RUNNER)
                .withProperties(
                        PropertyFactory.circleColor(0xFFD32F2F),
                        PropertyFactory.circleRadius(9f),
                        PropertyFactory.circleStrokeWidth(3f),
                        PropertyFactory.circleStrokeColor(0xFFFFFFFF)));
    }

    /**
     * 更新路线折线（{@code closed} 为真时首尾相连，为假时保持开口）与节点。
     */
    public void renderRoute(@NonNull List<GeoPoint> points) {
        renderRoute(points, true);
    }

    public void renderRoute(@NonNull List<GeoPoint> points, boolean closed) {
        currentPoints.clear();
        currentPoints.addAll(points);
        if (style == null) {
            return;
        }
        List<Point> ring = new ArrayList<>(points.size() + 1);
        List<Feature> lineFeatures = new ArrayList<>();
        if (points.size() >= 2) {
            for (GeoPoint point : points) {
                ring.add(toDisplayPoint(point));
            }
            if (closed) {
                ring.add(toDisplayPoint(points.get(0)));
            }
            lineFeatures.add(Feature.fromGeometry(LineString.fromLngLats(ring)));
        } else if (points.size() == 1) {
            // 只有一个节点时画不出折线，至少用一个极小线段占位，避免图层看起来“什么都没有”。
            Point only = toDisplayPoint(points.get(0));
            ring.add(only);
            ring.add(only);
            lineFeatures.add(Feature.fromGeometry(LineString.fromLngLats(ring)));
        }
        setGeoJson(SOURCE_LINE, FeatureCollection.fromFeatures(lineFeatures));

        List<Feature> vertexFeatures = new ArrayList<>();
        for (GeoPoint point : points) {
            vertexFeatures.add(Feature.fromGeometry(toDisplayPoint(point)));
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
                Feature.fromGeometry(toDisplayPoint(position))}));
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
            builder.include(toDisplay(point));
        }
        try {
            map.animateCamera(CameraUpdateFactory.newLatLngBounds(builder.build(), 96));
        } catch (RuntimeException ignored) {
            // 单点路线无法计算边界：直接居中。
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(toDisplay(points.get(0)), 17f));
        }
    }

    /** 当前地图中心（WGS-84）；地图还没就绪时返回 null（用于「发送单点位置」预填）。 */
    @Nullable
    public GeoPoint cameraCenter() {
        if (map == null) {
            return null;
        }
        LatLng target = map.getCameraPosition().target;
        return fromDisplay(target.getLatitude(), target.getLongitude());
    }

    /** 跟随当前模拟位置；用户手动拖动地图后自动关闭。 */
    public void followRunner(@NonNull GeoPoint position) {
        if (map == null || !followRunner) {
            return;
        }
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(toDisplay(position),
                Math.max(16f, map.getCameraPosition().zoom)));
    }

    public void setFollowRunner(boolean follow) {
        this.followRunner = follow;
    }

    public boolean isFollowRunner() {
        return followRunner;
    }

    /** 用户手势造成的相机移动应停止跟随；程序移动不算。 */
    public void registerGestureListener() {
        gestureListenerWanted = true;
        attachGestureListenerIfReady();
    }

    /**
     * 地图对象是异步就绪的：界面在 attach 之后立刻调用 registerGestureListener 时 map 还是 null，
     * 所以这里记住“想要监听”，等 onMapReady 里再补挂，避免跟随永远关不掉、运行中地图无法拖动。
     */
    private void attachGestureListenerIfReady() {
        if (!gestureListenerWanted || gestureListenerAttached || map == null) {
            return;
        }
        map.addOnCameraMoveStartedListener(reason -> {
            if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                followRunner = false;
            }
        });
        gestureListenerAttached = true;
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
        cancelStyleTimeout();
        mapView.onDestroy();
    }

    public void onLowMemory() {
        mapView.onLowMemory();
    }

    public Context context() {
        return mapView.getContext();
    }
}
