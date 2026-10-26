package org.localroute.simulation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.localroute.core.RunErrorCode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 模拟位置会话的单元测试：provider 创建顺序、失败回滚、提交、幂等停止。 */
public class MockLocationSessionTest {

    /** 记录调用的假实现，用于在 JVM 上验证而不触碰 Android。 */
    private static final class FakeProviderApi implements MockProviderApi {

        final List<String> events = new ArrayList<>();
        final Set<String> supported = new HashSet<>(Arrays.asList("gps", "network", "fused"));
        final Set<String> failAdd = new HashSet<>();
        final Set<String> failEnable = new HashSet<>();
        final Map<String, MockFix> lastPublished = new LinkedHashMap<>();
        final List<String> created = new ArrayList<>();
        boolean failPublish;
        boolean removeThrows;
        RunErrorCode addErrorCode = RunErrorCode.PROVIDER_SETUP_FAILED;

        @Override
        public boolean isProviderSupported(String provider) {
            return supported.contains(provider);
        }

        @Override
        public void addTestProvider(String provider) throws MockProviderException {
            events.add("add:" + provider);
            if (failAdd.contains(provider)) {
                throw new MockProviderException(addErrorCode, "add failed: " + provider);
            }
            created.add(provider);
        }

        @Override
        public void setTestProviderEnabled(String provider, boolean enabled) throws MockProviderException {
            events.add("enable:" + provider + "=" + enabled);
            if (enabled && failEnable.contains(provider)) {
                throw new MockProviderException(RunErrorCode.PROVIDER_SETUP_FAILED, "enable failed");
            }
        }

        @Override
        public void setTestProviderLocation(String provider, MockFix fix) throws MockProviderException {
            events.add("publish:" + provider);
            if (failPublish) {
                throw new MockProviderException(RunErrorCode.PUBLISH_FAILED, "publish failed");
            }
            lastPublished.put(provider, fix);
        }

        @Override
        public void removeTestProvider(String provider) {
            events.add("remove:" + provider);
            created.remove(provider);
            if (removeThrows) {
                throw new IllegalStateException("remove boom");
            }
        }
    }

    private static MockFix fix() {
        return new MockFix(0.0005d, 0.0005d, 0d, 5d, 2.5d, 45d, 1_700_000_000_000L, 12_345_000_000L);
    }

    @Test
    public void start_createsGpsThenNetworkThenFused() {
        FakeProviderApi api = new FakeProviderApi();
        MockLocationSession session = new MockLocationSession(api);
        ProviderSetupResult result = session.start();

        assertTrue(result.success());
        assertEquals(Arrays.asList("gps", "network", "fused"), result.publishedProviders());
        assertEquals(Arrays.asList("add:gps", "enable:gps=true", "add:network", "enable:network=true",
                "add:fused", "enable:fused=true"), api.events);
        assertTrue(session.isStarted());
    }

    @Test
    public void start_skipsUnsupportedOptionalProviders() {
        FakeProviderApi api = new FakeProviderApi();
        api.supported.remove("fused");
        MockLocationSession session = new MockLocationSession(api);

        ProviderSetupResult result = session.start();
        assertTrue(result.success());
        assertEquals(Arrays.asList("gps", "network"), result.publishedProviders());
        assertEquals(Arrays.asList("fused"), result.skippedProviders());
    }

    @Test
    public void start_rollsBackWhenOptionalProviderFails() {
        FakeProviderApi api = new FakeProviderApi();
        api.failAdd.add("network");
        MockLocationSession session = new MockLocationSession(api);

        ProviderSetupResult result = session.start();

        assertFalse(result.success());
        assertEquals("network", result.failedProvider());
        assertEquals(RunErrorCode.PROVIDER_SETUP_FAILED, result.errorCode());
        assertEquals("已创建的 gps 必须被撤销", 0, api.created.size());
        assertTrue(api.events.contains("remove:gps"));
        assertFalse(session.isStarted());
    }

    @Test
    public void start_mapsSecurityExceptionToMockAppNotSelected() {
        FakeProviderApi api = new FakeProviderApi();
        api.failAdd.add("gps");
        api.addErrorCode = RunErrorCode.MOCK_APP_NOT_SELECTED;
        MockLocationSession session = new MockLocationSession(api);

        ProviderSetupResult result = session.start();

        assertFalse(result.success());
        assertEquals(RunErrorCode.MOCK_APP_NOT_SELECTED, result.errorCode());
        assertEquals("gps", result.failedProvider());
    }

    @Test
    public void start_twice_doesNotCreateProvidersTwice() {
        FakeProviderApi api = new FakeProviderApi();
        MockLocationSession session = new MockLocationSession(api);
        session.start();
        int eventCount = api.events.size();

        ProviderSetupResult second = session.start();

        assertTrue(second.success());
        assertEquals("重复 start 不应再次创建 provider", eventCount, api.events.size());
    }

    @Test
    public void publish_sendsToEveryCreatedProvider() throws Exception {
        FakeProviderApi api = new FakeProviderApi();
        MockLocationSession session = new MockLocationSession(api);
        session.start();
        session.publish(fix());

        assertEquals(3, api.lastPublished.size());
        MockFix received = api.lastPublished.get("gps");
        assertEquals(0.0005d, received.latitude(), 1e-12d);
        assertEquals(0.0005d, received.longitude(), 1e-12d);
        assertEquals(2.5d, received.speedMps(), 1e-12d);
        assertEquals(45d, received.bearingDegrees(), 1e-12d);
        assertEquals(12_345_000_000L, received.elapsedRealtimeNanos());
    }

    @Test
    public void publish_wrapsFailureIntoPublishFailed() {
        FakeProviderApi api = new FakeProviderApi();
        MockLocationSession session = new MockLocationSession(api);
        session.start();
        api.failPublish = true;

        MockProviderException exception = assertThrows(MockProviderException.class,
                () -> session.publish(fix()));
        assertEquals(RunErrorCode.PUBLISH_FAILED, exception.errorCode());
    }

    @Test
    public void publish_beforeStart_throwsIllegalState() {
        MockLocationSession session = new MockLocationSession(new FakeProviderApi());
        assertThrows(IllegalStateException.class, () -> session.publish(fix()));
    }

    @Test
    public void stop_removesOnlyCreatedProviders_andIsIdempotent() {
        FakeProviderApi api = new FakeProviderApi();
        MockLocationSession session = new MockLocationSession(api);
        session.start();
        session.stop();

        assertFalse(session.isStarted());
        assertEquals(0, api.created.size());
        assertTrue(api.events.contains("remove:fused"));
        assertTrue(api.events.contains("remove:network"));
        assertTrue(api.events.contains("remove:gps"));
        int eventsAfterStop = api.events.size();

        session.stop();
        assertEquals("重复 stop 不应再调用平台", eventsAfterStop, api.events.size());
    }

    @Test
    public void stop_disablesBeforeRemoving() {
        FakeProviderApi api = new FakeProviderApi();
        MockLocationSession session = new MockLocationSession(api);
        session.start();
        api.events.clear();

        session.stop();

        int disableGps = api.events.indexOf("enable:gps=false");
        int removeGps = api.events.indexOf("remove:gps");
        assertTrue("停止时应先停用 gps：" + api.events, disableGps >= 0);
        assertTrue("停用之后才移除 gps：" + api.events, removeGps > disableGps);
    }

    @Test
    public void stop_swallowsRemoveFailures() {
        FakeProviderApi api = new FakeProviderApi();
        api.removeThrows = true;
        MockLocationSession session = new MockLocationSession(api);
        session.start();
        session.stop();
        assertFalse(session.isStarted());
    }

    @Test
    public void start_requiresGpsInCandidates() {
        assertThrows(IllegalArgumentException.class,
                () -> new MockLocationSession(new FakeProviderApi(), Arrays.asList("network", "fused")));
    }

    @Test
    public void start_failsWhenGpsUnsupported() {
        FakeProviderApi api = new FakeProviderApi();
        api.supported.remove("gps");
        MockLocationSession session = new MockLocationSession(api);

        ProviderSetupResult result = session.start();
        assertFalse(result.success());
        assertEquals(RunErrorCode.PROVIDER_SETUP_FAILED, result.errorCode());
        assertEquals("gps", result.failedProvider());
    }
}
