package org.localroute.app.run;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.localroute.core.CompiledRoute;
import org.localroute.core.GeoPoint;
import org.localroute.core.InvalidRouteException;
import org.localroute.core.RouteCompiler;
import org.localroute.core.RunConfig;

import java.util.Arrays;
import java.util.List;

/** 运行请求的两种模式（0.1.1 新增单点位置）。 */
public class RunRequestTest {

    private static List<GeoPoint> smallSquare() {
        return Arrays.asList(
                new GeoPoint(0.0000d, 0.0000d),
                new GeoPoint(0.0000d, 0.0002d),
                new GeoPoint(0.0002d, 0.0002d),
                new GeoPoint(0.0002d, 0.0000d));
    }

    @Test
    public void routeRequestCarriesRouteAndConfig() throws InvalidRouteException {
        CompiledRoute route = RouteCompiler.compile(smallSquare());
        RunConfig config = new RunConfig(9d, 5);
        RunRequest request = RunRequest.create("测试路线", route, config);

        assertFalse(request.isSinglePoint());
        assertNull(request.singlePoint());
        assertNotNull(request.runId());
        assertEquals(route, request.route());
        assertEquals("测试路线", request.routeName());
        assertEquals(config, request.config());
    }

    @Test
    public void singlePointRequestHasPointAndNoRoute() {
        GeoPoint point = new GeoPoint(30.5d, 114.3d);
        RunRequest request = RunRequest.createSinglePoint(point);

        assertTrue(request.isSinglePoint());
        assertEquals(point, request.singlePoint());
        assertNull("单点模式不应带路线", request.route());
        assertEquals("单点位置", request.routeName());
        assertEquals(1, request.config().loopCount());
        assertEquals(RunConfig.DEFAULT_SPEED_KMH, request.config().speedKmh(), 1e-9d);
    }

    @Test
    public void singlePointRequestRejectsNull() {
        assertThrows(IllegalArgumentException.class, () -> RunRequest.createSinglePoint(null));
    }

    @Test
    public void eachRequestGetsItsOwnId() {
        GeoPoint point = new GeoPoint(1d, 2d);
        assertFalse(RunRequest.createSinglePoint(point).runId()
                .equals(RunRequest.createSinglePoint(point).runId()));
    }
}
