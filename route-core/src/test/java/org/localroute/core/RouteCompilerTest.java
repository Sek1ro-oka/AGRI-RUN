package org.localroute.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** 路线编译（去重、闭合、单圈长度、点数上限）的单元测试，对应任务书 T01/T02/T03 的算法部分。 */
public class RouteCompilerTest {

    private static final double METERS_PER_DEGREE_LAT = 111_194.9d;

    private static GeoPoint squarePoint(int index) {
        // 以 (0,0) 为中心、边长约 0.001 度（约 111 米）的正方形，不使用任何真实地点坐标。
        switch (index % 4) {
            case 0:
                return new GeoPoint(0.0000d, 0.0000d);
            case 1:
                return new GeoPoint(0.0000d, 0.0010d);
            case 2:
                return new GeoPoint(0.0010d, 0.0010d);
            default:
                return new GeoPoint(0.0010d, 0.0000d);
        }
    }

    private static List<GeoPoint> square() {
        return Arrays.asList(squarePoint(0), squarePoint(1), squarePoint(2), squarePoint(3));
    }

    @Test
    public void compile_emptyOrSinglePoint_throws() {
        InvalidRouteException empty = assertThrows(InvalidRouteException.class,
                () -> RouteCompiler.compile(Collections.emptyList()));
        assertEquals(RunErrorCode.ROUTE_INVALID, empty.errorCode());

        assertThrows(InvalidRouteException.class,
                () -> RouteCompiler.compile(Collections.singletonList(squarePoint(0))));
    }

    @Test
    public void compile_allPointsIdentical_throws() {
        List<GeoPoint> points = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            points.add(squarePoint(0));
        }
        assertThrows(InvalidRouteException.class, () -> RouteCompiler.compile(points));
    }

    @Test
    public void compile_square_lapLengthIsPerimeterIncludingClosingEdge() throws Exception {
        CompiledRoute route = RouteCompiler.compile(square());
        List<GeoPoint> points = square();
        double perimeter = 0d;
        for (int i = 0; i < points.size(); i++) {
            perimeter += GeoMath.distanceMeters(points.get(i), points.get((i + 1) % points.size()));
        }
        assertEquals(perimeter, route.lapLengthMeters(), 0.05d);
        assertEquals(4, route.vertexCount());
        // 单圈长度的 1/4 处应该落在第二条边上（右上角附近）。
        GeoPoint quarter = route.pointAt(route.lapLengthMeters() / 4d);
        assertTrue(quarter.isNear(squarePoint(1), 1.0d));
    }

    @Test
    public void compile_removesConsecutiveDuplicates() throws Exception {
        List<GeoPoint> points = new ArrayList<>();
        points.add(squarePoint(0));
        points.add(new GeoPoint(squarePoint(0).latitude() + 1e-9d, squarePoint(0).longitude()));
        points.add(squarePoint(1));
        points.add(squarePoint(1));
        points.add(squarePoint(2));
        points.add(squarePoint(3));
        CompiledRoute route = RouteCompiler.compile(points);
        assertEquals(4, route.vertexCount());
        assertEquals(RouteCompiler.compile(square()).lapLengthMeters(), route.lapLengthMeters(), 1e-6d);
    }

    @Test
    public void compile_removesDuplicatedClosingPoint() throws Exception {
        List<GeoPoint> closedInput = new ArrayList<>(square());
        closedInput.add(squarePoint(0));
        CompiledRoute route = RouteCompiler.compile(closedInput);
        assertEquals("终点与起点重合时应清理重复终点", 4, route.vertexCount());
        assertEquals(RouteCompiler.compile(square()).lapLengthMeters(), route.lapLengthMeters(), 1e-6d);
    }

    @Test
    public void compile_twoPoints_lapIsTwiceTheStraightDistance() throws Exception {
        GeoPoint a = new GeoPoint(0d, 0d);
        GeoPoint b = new GeoPoint(0d, 0.001d);
        CompiledRoute route = RouteCompiler.compile(Arrays.asList(a, b));
        double oneWay = GeoMath.distanceMeters(a, b);
        assertEquals(2d * oneWay, route.lapLengthMeters(), 1e-6d);
    }

    @Test
    public void compile_lapShorterThanOneMeter_throws() {
        GeoPoint a = new GeoPoint(0d, 0d);
        // 0.000003 度经度约 0.33 米，一来一回约 0.67 米 < 1 米。
        GeoPoint b = new GeoPoint(0d, 0.000003d);
        InvalidRouteException exception = assertThrows(InvalidRouteException.class,
                () -> RouteCompiler.compile(Arrays.asList(a, b)));
        assertEquals(RunErrorCode.ROUTE_INVALID, exception.errorCode());
    }

    @Test
    public void compile_moreThanMaxPoints_throws() {
        List<GeoPoint> points = new ArrayList<>();
        for (int i = 0; i < RouteCompiler.MAX_POINTS + 1; i++) {
            points.add(new GeoPoint(0.0001d * (i % 10), 0.0001d * (i / 10)));
        }
        assertThrows(InvalidRouteException.class, () -> RouteCompiler.compile(points));
    }

    @Test
    public void compile_maxPoints_isAccepted() throws Exception {
        List<GeoPoint> points = new ArrayList<>();
        // 5000 个点在极小的经纬度网格上交替，保证尺寸足够但点数正好等于上限。
        for (int i = 0; i < RouteCompiler.MAX_POINTS; i++) {
            points.add(new GeoPoint(0.0001d * (i % 70), 0.0001d * (i / 70)));
        }
        CompiledRoute route = RouteCompiler.compile(points);
        assertEquals(RouteCompiler.MAX_POINTS, route.vertexCount());
        assertTrue(route.lapLengthMeters() > 1.0d);
    }

    @Test
    public void compile_rejectsNullPoint() {
        assertThrows(InvalidRouteException.class,
                () -> RouteCompiler.compile(Arrays.asList(squarePoint(0), null, squarePoint(1))));
    }

    @Test
    public void prefixDistances_areMonotonicAndStartAtZero() throws Exception {
        CompiledRoute route = RouteCompiler.compile(square());
        assertEquals(0d, route.prefixDistance(0), 1e-9d);
        for (int i = 1; i < route.vertexCount(); i++) {
            assertTrue(route.prefixDistance(i) > route.prefixDistance(i - 1));
        }
        assertTrue(route.prefixDistance(route.vertexCount() - 1) < route.lapLengthMeters());
    }

    @Test
    public void pointAt_wrapsAroundLap() throws Exception {
        CompiledRoute route = RouteCompiler.compile(square());
        double lap = route.lapLengthMeters();
        assertEquals(route.pointAt(0d), route.pointAt(lap));
        assertEquals(route.pointAt(10d), route.pointAt(lap + 10d));
        assertEquals(route.pointAt(10d), route.pointAt(10d - lap));
        assertEquals(METERS_PER_DEGREE_LAT * 0.001d, GeoMath.distanceMeters(
                route.pointAt(0d), route.pointAt(lap / 4d)), 1.0d);
    }

    // ------------------------------------------------------------ 不闭合（原路返回）

    @Test
    public void compileOpen_threePoints_lapIsTwiceTheOneWayPath() throws Exception {
        List<GeoPoint> points = Arrays.asList(squarePoint(0), squarePoint(1), squarePoint(2));
        CompiledRoute route = RouteCompiler.compile(points, false);

        assertFalse("不闭合路线应保留标记", route.closed());
        assertEquals("3 个点 + 回程 1 个折返点", 4, route.vertexCount());
        assertEquals(3, route.pathPointCount());
        double oneWay = GeoMath.distanceMeters(points.get(0), points.get(1))
                + GeoMath.distanceMeters(points.get(1), points.get(2));
        assertEquals(2d * oneWay, route.lapLengthMeters(), 0.05d);
        // 走到单程长度时位于最后一个点，随后原路折返，走满一圈回到起点。
        assertTrue(route.pointAt(oneWay).isNear(points.get(2), 1.0d));
        assertTrue(route.pointAt(route.lapLengthMeters() - 1e-6d).isNear(points.get(0), 1.0d));
    }

    @Test
    public void compileOpen_square_doesNotCloseThePathDirectly() throws Exception {
        List<GeoPoint> square = square();
        CompiledRoute opened = RouteCompiler.compile(square, false);
        CompiledRoute closed = RouteCompiler.compile(square, true);

        double oneWay = 0d;
        for (int i = 0; i < square.size() - 1; i++) {
            oneWay += GeoMath.distanceMeters(square.get(i), square.get(i + 1));
        }
        assertEquals(2d * oneWay, opened.lapLengthMeters(), 0.05d);
        assertEquals("4 个点 + 回程 2 个折返点，末段由闭合边回到起点", 6, opened.vertexCount());
        assertTrue("往返一圈一定比直接闭合更长",
                opened.lapLengthMeters() > closed.lapLengthMeters());
    }

    @Test
    public void compileOpen_tooShort_throws() {
        GeoPoint a = new GeoPoint(0d, 0d);
        GeoPoint b = new GeoPoint(0d, 0.000003d);
        InvalidRouteException exception = assertThrows(InvalidRouteException.class,
                () -> RouteCompiler.compile(Arrays.asList(a, b), false));
        assertEquals(RunErrorCode.ROUTE_INVALID, exception.errorCode());
    }

    @Test
    public void compileOpen_singlePoint_throws() {
        assertThrows(InvalidRouteException.class,
                () -> RouteCompiler.compile(Collections.singletonList(squarePoint(0)), false));
    }

    @Test
    public void compileOpen_twoPoints_lapIsTwiceTheStraightDistance() throws Exception {
        GeoPoint a = new GeoPoint(0d, 0d);
        GeoPoint b = new GeoPoint(0d, 0.001d);
        CompiledRoute route = RouteCompiler.compile(Arrays.asList(a, b), false);
        assertFalse(route.closed());
        assertEquals(2, route.vertexCount());
        assertEquals(2d * GeoMath.distanceMeters(a, b), route.lapLengthMeters(), 1e-6d);
    }

    @Test
    public void compileStopAtEnd_square_lapIsTheOneWayPathWithoutClosingEdge() throws Exception {
        List<GeoPoint> square = square();
        CompiledRoute stopAtEnd = RouteCompiler.compile(square, EndBehavior.STOP_AT_END);
        CompiledRoute loop = RouteCompiler.compile(square, EndBehavior.LOOP);

        assertEquals(EndBehavior.STOP_AT_END, stopAtEnd.endBehavior());
        assertFalse("到终点停止不应回跳起点", stopAtEnd.wraps());
        assertEquals("单程顶点数应等于用户点数", 4, stopAtEnd.vertexCount());
        assertEquals("pathPointCount 是用户路径点数", 4, stopAtEnd.pathPointCount());
        assertTrue("单程一定比闭合一圈短", stopAtEnd.lapLengthMeters() < loop.lapLengthMeters());
        assertTrue(stopAtEnd.pointAt(0d).isNear(square.get(0), 0.01d));
        assertTrue("最后一米应停在终点",
                stopAtEnd.pointAt(stopAtEnd.lapLengthMeters() - 1e-6d)
                        .isNear(square.get(3), 1e-3d));
    }

    @Test
    public void compileStopAtEnd_doesNotWrapWhenAskedBeyondTheEnd() throws Exception {
        CompiledRoute route = RouteCompiler.compile(square(), EndBehavior.STOP_AT_END);
        GeoPoint beyond = route.pointAt(route.lapLengthMeters() + 50d);
        assertTrue("超出单程应夹在终点，而不是绕回起点", beyond.isNear(square().get(3), 1e-3d));
    }

    @Test
    public void compileShuttle_keepsOutAndBackPath() throws Exception {
        CompiledRoute route = RouteCompiler.compile(square(), EndBehavior.SHUTTLE);
        assertTrue(route.wraps());
        assertEquals(6, route.vertexCount());
        assertTrue(route.pointAt(route.lapLengthMeters() - 1e-6d).isNear(square().get(0), 1e-3d));
    }
    @Test
    public void pathLengthMeters_isAlwaysTheUsersOneWayPath() throws Exception {
        List<GeoPoint> square = square();
        CompiledRoute loop = RouteCompiler.compile(square, EndBehavior.LOOP);
        CompiledRoute shuttle = RouteCompiler.compile(square, EndBehavior.SHUTTLE);
        CompiledRoute stop = RouteCompiler.compile(square, EndBehavior.STOP_AT_END);

        double oneWay = stop.lapLengthMeters();
        assertEquals("到终点停止：单程就是全程", oneWay, stop.pathLengthMeters(), 1e-9d);
        assertEquals("原路往返：单程是往返的一半", oneWay, shuttle.pathLengthMeters(), 0.01d);
        assertEquals("往返一圈 = 两倍单程", shuttle.lapLengthMeters(), shuttle.pathLengthMeters() * 2d, 0.01d);
        assertTrue("闭合：单圈还要多一段闭合边", loop.lapLengthMeters() >= loop.pathLengthMeters());
    }}
