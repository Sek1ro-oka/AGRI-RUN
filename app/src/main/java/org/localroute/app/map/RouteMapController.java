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

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
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
public final class RouteMapController implements RouteMapHost {

    private static final String SOURCE_LINE = "route-line";
    private static final String SOURCE_VERTICES = "route-vertices";
    private static final String SOURCE_START = "route-start";
    private static final String SOURCE_RUNNER = "run-position";
    private static final String LAYER_BACKGROUND = "background";
    private static final String LAYER_RASTER = "osm-raster";
    private static final String LAYER_LINE = "route-line-layer";
    private static final String LAYER_VERTICES = "route-vertices-layer";
    private static final String LAYER_START = "route-start-layer";
    private static final String LAYER_RUNNER = "run-position-layer";

    /** 样式加载的看门狗：超过这个时间还没回调，就认为瓦片/样式加载失败。 */
    private static final long STYLE_TIMEOUT_MS = 10_000L;

    /** 瓦片探测的超时：只取一张 0 级瓦片，不需要等太久。 */
    private static final int TILE_PROBE_TIMEOUT_MS = 4_000;

    /** 探测瓦片时带的 UA：与 OSM 使用政策中「可识别应用」的要求保持一致。 */
    private static final String USER_AGENT = "RouteWorkshop/0.1.3 (Android; local route simulation)";

    private static final String PREFS = "map";
    private static final String PREF_TILE_SOURCE = "tile_source";

    /**
     * 可选瓦片源。默认用「高德」：国内网络通常能直连，而 tile.openstreetmap.org
     * 在国内经常 TLS 握手失败（实测 SSLHandshakeException），用户会只看到一张空白底图。
     *
     * <p>高德这组地址是非官方公开接口、且用 GCJ-02 坐标系：前者可能随时失效（失效时可切回 OSM），
     * 后者由 {@link ChinaShift} 在显示层换算，存储与计算始终是 WGS-84。
     */
    private static final List<TileSource> TILE_SOURCES = Arrays.asList(
            new TileSource("amap", "高德地图（GCJ-02，非官方接口）", CoordSystem.GCJ02, 18,
                    "© 高德地图",
                    "https://webrd01.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=7&x={x}&y={y}&z={z}",
                    "https://webrd02.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=7&x={x}&y={y}&z={z}",
                    "https://webrd03.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=7&x={x}&y={y}&z={z}",
                    "https://webrd04.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=7&x={x}&y={y}&z={z}"),
            // 腾讯地图（审查 D）：同样是国内可用、GCJ-02 的非官方瓦片接口，作为高德的备选。
            // 行列号按标准 XYZ 使用；若某天出现错位，切回高德即可，不影响存储的 WGS-84 数据。
            new TileSource("tencent", "腾讯地图（GCJ-02，非官方接口）", CoordSystem.GCJ02, 18,
                    "© 腾讯地图",
                    "https://rt0.map.gtimg.com/tile?z={z}&x={x}&y={y}&styleid=1&version=1",
                    "https://rt1.map.gtimg.com/tile?z={z}&x={x}&y={y}&styleid=1&version=1",
                    "https://rt2.map.gtimg.com/tile?z={z}&x={x}&y={y}&styleid=1&version=1",
                    "https://rt3.map.gtimg.com/tile?z={z}&x={x}&y={y}&styleid=1&version=1"),
            new TileSource("osm", "OpenStreetMap 官方", CoordSystem.WGS84, 19,
                    "© OpenStreetMap contributors",
                    "https://tile.openstreetmap.org/{z}/{x}/{y}.png"),
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

    /**
     * 编辑态显示状态缓存（point 列表、是否闭合、最新 runner）。
     *
     * <p>这三个值必须由本类自己保存：样式重建（切瓦片源）时 MapLibre 会把所有
     * source/layer 丢掉，只有从缓存重放才能保证「切源不丢路线」。
     */
    private List<GeoPoint> cachedPoints = new ArrayList<>();
    private boolean cachedClosed;
    private boolean cachedVertices = true;
    @Nullable
    private GeoPoint cachedRunner;

    /**
     * 样式代次：每次 applyStyle 自增。异步回调（样式加载、瓦片探测）回来时若代次已变，
     * 说明用户又切了一次源，旧结果必须丢弃，否则旧源的失败会覆盖新源的状态。
     */
    private int styleGeneration;

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
    private RouteMapHost.StatusListener statusListener;
    private String lastStatus = "";
    /** 最近一次瓦片探测失败的原因；为空表示上一次探测是通的。 */
    @Nullable
    private String tileWarning;

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

    /** 地图状态回调：{@code message == null} 表示已就绪；沿用 {@link RouteMapHost.StatusListener}。 */
    public interface StatusListener extends RouteMapHost.StatusListener {
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

    @Override
    public void setStatusListener(@Nullable RouteMapHost.StatusListener listener) {
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
        tileWarning = null;
        final int generation = ++styleGeneration;
        final TileSource source = currentTileSource();
        notifyStatus("正在加载 " + source.label() + " 瓦片…");
        DiagnosticLog.info("加载地图样式：" + source.label() + " / " + source.urlTemplate());

        cancelStyleTimeout();
        styleTimeout = () -> {
            if (style == null && generation == styleGeneration) {
                String message = String.format(Locale.US,
                        "加载 %s 超过 %d 秒仍未完成（可能是网络无法访问该瓦片服务）",
                        source.label(), STYLE_TIMEOUT_MS / 1000L);
                DiagnosticLog.warn("地图样式加载超时：" + message);
                notifyStatus(message);
            }
        };
        mapView.postDelayed(styleTimeout, STYLE_TIMEOUT_MS);

        map.setStyle(new Style.Builder().fromJson(buildStyleJson(source)), loaded -> {
            if (generation != styleGeneration) {
                // 用户已经切到别的源：这次回调是过期的，直接丢弃。
                DiagnosticLog.info("忽略过期的样式回调：" + source.label());
                return;
            }
            cancelStyleTimeout();
            style = loaded;
            addSources(loaded);
            restoreOverlays();
            DiagnosticLog.info("地图样式已加载：" + source.label());
            notifyStatus(null);
            // 样式加载成功不代表瓦片能下载：样式是一段本地 JSON，瓦片才是网络请求。
            // 这里单独探一次瓦片，失败就把原因留在界面上，而不是让用户看着空白地图猜。
            probeTiles(source, generation);
        });
    }

    /**
     * 探一次瓦片是否能下载（取第 0 级瓦片，最省流量），失败时把原因写进状态行。
     *
     * <p>用独立的短连接而不是 MapLibre 内部回调：MapLibre 没有暴露“所有瓦片都失败”的事件，
     * 而这里只需要回答一个用户问题——“底图为什么是空的”。
     */
    private void probeTiles(@NonNull TileSource source, int generation) {
        final String url = source.tileUrls().get(0)
                .replace("{z}", "0").replace("{x}", "0").replace("{y}", "0");
        Thread thread = new Thread(() -> {
            boolean ok = false;
            String reason = null;
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setConnectTimeout(TILE_PROBE_TIMEOUT_MS);
                connection.setReadTimeout(TILE_PROBE_TIMEOUT_MS);
                connection.setInstanceFollowRedirects(true);
                connection.setRequestProperty("User-Agent", USER_AGENT);
                int code = connection.getResponseCode();
                ok = code >= 200 && code < 400;
                if (!ok) {
                    reason = "HTTP " + code;
                }
            } catch (IOException | RuntimeException e) {
                reason = e.getClass().getSimpleName();
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
            final boolean reachable = ok;
            final String failure = reason;
            mapView.post(() -> {
                if (generation != styleGeneration) {
                    // 探测期间用户又切源了：这个结果属于旧源，不能覆盖新源的状态。
                    return;
                }
                if (!reachable) {
                    tileWarning = String.format(Locale.US,
                            "%s 的瓦片下载失败（%s），可在菜单「地图瓦片源」换一个或点「重新加载」",
                            source.label(), failure);
                    DiagnosticLog.warn("瓦片探测失败：" + tileWarning);
                    notifyStatus(tileWarning);
                } else if (tileWarning != null) {
                    tileWarning = null;
                    notifyStatus(null);
                }
            });
        }, "tile-probe");
        thread.setDaemon(true);
        thread.start();
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

    /**
     * 切换瓦片源并立即重试。
     *
     * <p>同一个源也允许重试（返回 true）：用户在看到“瓦片下载失败”后第一反应就是再点一次，
     * 早期版本因为「索引没变直接 return false」被当成没反应（R-008）。
     */
    public boolean selectTileSource(int index) {
        tileSourceIndex = clampIndex(index);
        saveTileSourceIndex(tileSourceIndex);
        applyStyle();
        return true;
    }

    /** 用当前源重新加载样式（菜单「重新加载地图」用）。 */
    public void reloadStyle() {
        applyStyle();
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
        loaded.addSource(new GeoJsonSource(SOURCE_START));
        loaded.addSource(new GeoJsonSource(SOURCE_RUNNER));
        loaded.addLayer(new LineLayer(LAYER_LINE, SOURCE_LINE)
                .withProperties(
                        PropertyFactory.lineColor(0xFF1B5E20),
                        PropertyFactory.lineWidth(5f),
                        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND)));
        // 选点阶段每个航点都画成与起点相同的橙色圆点，用户能一眼看到自己点下的每一处；
        // 路线完成后由界面传 showVertices=false，只保留起点。起点与节点同色同大小，
        // 不再出现「起点橙色、其余蓝色」的割裂感。
        loaded.addLayer(new CircleLayer(LAYER_VERTICES, SOURCE_VERTICES)
                .withProperties(
                        PropertyFactory.circleColor(0xFFE65100),
                        PropertyFactory.circleRadius(8f),
                        PropertyFactory.circleStrokeWidth(2f),
                        PropertyFactory.circleStrokeColor(0xFFFFFFFF)));
        // 起点用独立单点源：早期版本让它复用整条顶点源且没有过滤条件，
        // 结果所有节点都被画成橙色起点（R-007）。
        loaded.addLayer(new CircleLayer(LAYER_START, SOURCE_START)
                .withProperties(
                        PropertyFactory.circleColor(0xFFE65100),
                        PropertyFactory.circleRadius(8f),
                        PropertyFactory.circleStrokeWidth(2f),
                        PropertyFactory.circleStrokeColor(0xFFFFFFFF)));
        loaded.addLayer(new CircleLayer(LAYER_RUNNER, SOURCE_RUNNER)
                .withProperties(
                        PropertyFactory.circleColor(0xFFD32F2F),
                        PropertyFactory.circleRadius(9f),
                        PropertyFactory.circleStrokeWidth(3f),
                        PropertyFactory.circleStrokeColor(0xFFFFFFFF)));
    }

    /**
     * 样式重建后从缓存重放所有图层（路线、节点、起点、runner）。
     *
     * <p>MapLibre 换样式会清空 source/layer，这条路径必须能独立把显示恢复出来，
     * 不能依赖调用方再调一次 renderRoute。
     */
    private void restoreOverlays() {
        drawRoute();
        drawRunner();
    }

    /**
     * 更新路线折线（{@code closed} 为真时首尾相连，为假时保持开口）与节点。
     *
     * <p>兼容旧签名：默认按闭合画（GPX/示例导入前的老调用方）。
     */
    public void renderRoute(@NonNull List<GeoPoint> points) {
        renderRoute(points, true);
    }

    public void renderRoute(@NonNull List<GeoPoint> points, boolean closed) {
        renderRoute(points, closed, true);
    }

    @Override
    public void renderRoute(@NonNull List<GeoPoint> points, boolean closed, boolean showVertices) {
        // 先复制再缓存：调用方（编辑器 ViewModel）传的是可变列表，稍后增删节点时
        // 如果直接持有同一引用，缓存会跟着变，切源重放就会拿到错误的历史状态。
        cachedPoints = new ArrayList<>(points);
        cachedClosed = closed;
        cachedVertices = showVertices;
        drawRoute();
    }

    /** 按缓存重画路线图层（样式重建后也走这里）。 */
    private void drawRoute() {
        if (style == null) {
            return;
        }
        List<GeoPoint> points = cachedPoints;
        List<Point> ring = new ArrayList<>(points.size() + 1);
        List<Feature> lineFeatures = new ArrayList<>();
        if (points.size() >= 2) {
            for (GeoPoint point : points) {
                ring.add(toDisplayPoint(point));
            }
            if (cachedClosed) {
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
        if (cachedVertices) {
            for (int i = 1; i < points.size(); i++) {
                // 起点单独用 SOURCE_START 画，节点层从第 2 个点开始，避免两层叠色。
                vertexFeatures.add(Feature.fromGeometry(toDisplayPoint(points.get(i))));
            }
        }
        setGeoJson(SOURCE_VERTICES, FeatureCollection.fromFeatures(vertexFeatures));

        List<Feature> startFeatures = new ArrayList<>();
        if (!points.isEmpty()) {
            startFeatures.add(Feature.fromGeometry(toDisplayPoint(points.get(0))));
        }
        setGeoJson(SOURCE_START, FeatureCollection.fromFeatures(startFeatures));
    }

    /** 更新当前模拟位置；{@code position == null} 时清除。 */
    @Override
    public void focusOn(@NonNull GeoPoint point) {
        if (map == null) {
            return;
        }
        followRunner = false;
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(toDisplay(point), 16f));
    }
    public void renderRunner(@Nullable GeoPoint position) {
        cachedRunner = position;
        drawRunner();
    }

    private void drawRunner() {
        if (style == null) {
            return;
        }
        if (cachedRunner == null) {
            setGeoJson(SOURCE_RUNNER, FeatureCollection.fromFeatures(new Feature[0]));
            return;
        }
        setGeoJson(SOURCE_RUNNER, FeatureCollection.fromFeatures(new Feature[]{
                Feature.fromGeometry(toDisplayPoint(cachedRunner))}));
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
        fitRoute(points, true);
    }

    private void fitRoute(@NonNull List<GeoPoint> points, boolean mayDefer) {
        if (map == null || points.isEmpty()) {
            return;
        }
        // 单点没有“边界”可算：newLatLngBounds 对单点不会放大，用户会觉得“点了没反应”，
        // 所以这里直接给一个适合看路的层级。
        if (points.size() == 1) {
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(toDisplay(points.get(0)), 16f));
            return;
        }
        int width = mapView.getWidth();
        int height = mapView.getHeight();
        if ((width <= 0 || height <= 0) && mayDefer) {
            // 刚切到地图页时视图还没量好尺寸，等一帧再按实际视口取景。
            mapView.post(() -> fitRoute(points, false));
            return;
        }
        double minLat = Double.MAX_VALUE;
        double maxLat = -Double.MAX_VALUE;
        double minLon = Double.MAX_VALUE;
        double maxLon = -Double.MAX_VALUE;
        for (GeoPoint point : points) {
            minLat = Math.min(minLat, point.latitude());
            maxLat = Math.max(maxLat, point.latitude());
            minLon = Math.min(minLon, point.longitude());
            maxLon = Math.max(maxLon, point.longitude());
        }
        double centerLat = (minLat + maxLat) / 2d;
        double centerLon = (minLon + maxLon) / 2d;
        if (width <= 0 || height <= 0) {
            // 视图仍然没有尺寸，退回 MapLibre 的边界算法。
            LatLngBounds.Builder builder = new LatLngBounds.Builder();
            for (GeoPoint point : points) {
                builder.include(toDisplay(point));
            }
            try {
                map.animateCamera(CameraUpdateFactory.newLatLngBounds(builder.build(), 96));
            } catch (RuntimeException ignored) {
                map.animateCamera(CameraUpdateFactory.newLatLngZoom(toDisplay(points.get(0)), 17f));
            }
            return;
        }
        double spanLatMeters = (maxLat - minLat) * 111320d;
        double spanLonMeters = (maxLon - minLon) * 111320d
                * Math.cos(Math.toRadians(centerLat));
        float density = mapView.getResources().getDisplayMetrics().density;
        int padding = (int) (40f * density);
        double availableWidth = Math.max(1d, width - padding * 2d);
        double availableHeight = Math.max(1d, height - padding * 2d);
        // MapLibre 的缩放层级以 dp 计（512 像素瓦片），所以要按 dp 视口反算米/像素。
        double metersPerDp = Math.max(spanLatMeters / availableHeight,
                spanLonMeters / availableWidth) * density;
        double zoom = Math.log(78271.51696d * Math.cos(Math.toRadians(centerLat)) / metersPerDp)
                / Math.log(2d);
        // 校园跑圈只有几百米：按边界算出的层级会把底图放到没有街道参照的程度，
        // 因此把取景层级限制在能看清校园道路的范围内。
        double clampedZoom = Math.max(3d, Math.min(17d, zoom));
        DiagnosticLog.info("取景：视图 " + width + "×" + height
                + "，路线跨度 " + Math.round(spanLatMeters) + "×" + Math.round(spanLonMeters)
                + " 米，zoom " + zoom + " → " + clampedZoom);
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(
                toDisplay(new GeoPoint(centerLat, centerLon)), (float) clampedZoom));
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

    /** 该点是否落在当前可见范围内；地图没就绪时按「不可见」处理，让调用方重新取景。 */
    public boolean isVisible(@NonNull GeoPoint point) {
        if (map == null) {
            return false;
        }
        LatLngBounds visible = map.getProjection().getVisibleRegion().latLngBounds;
        return visible.contains(toDisplay(point));
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

    @Override
    public String engineLabel() {
        return "MapLibre + 高德瓦片";
    }

    @Override
    public void onLowMemory() {
        mapView.onLowMemory();
    }

    public Context context() {
        return mapView.getContext();
    }
}
