package org.localroute.app.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.localroute.app.model.RouteDefinition;
import org.localroute.app.model.RouteSummary;
import org.localroute.core.GeoPoint;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** 本地仓库：原子写入、排序、损坏隔离、删除。 */
public class RouteRepositoryTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private RouteDefinition route(String id, String name, double offset) {
        Instant now = Instant.now();
        List<GeoPoint> points = Arrays.asList(
                new GeoPoint(30.0d + offset, 120.0d),
                new GeoPoint(30.0d + offset, 120.001d),
                new GeoPoint(30.001d + offset, 120.001d));
        return new RouteDefinition(id, name, points, now, now);
    }

    @Test
    public void saveThenLoadRoundTrip() throws Exception {
        RouteRepository repository = new RouteRepository(folder.newFolder("routes"));
        RouteDefinition original = route("route-a", "操场 A", 0d);
        repository.save(original);

        File expected = new File(repository.routesDir(), "route-a.json");
        assertTrue("应写入 <id>.json", expected.isFile());

        RouteDefinition loaded = repository.load("route-a");
        assertEquals("操场 A", loaded.name());
        assertEquals(original.points(), loaded.points());
        assertTrue(loaded.closed());
    }

    @Test
    public void saveLeavesNoTemporaryFile() throws Exception {
        RouteRepository repository = new RouteRepository(folder.newFolder("routes"));
        repository.save(route("route-tmp", "临时", 0d));
        String[] names = repository.routesDir().list();
        assertNotNull(names);
        assertTrue("不应残留 .tmp 文件", Arrays.stream(names).noneMatch(n -> n.endsWith(".tmp")));
    }

    @Test
    public void listSortsByUpdatedAtDescending() throws Exception {
        RouteRepository repository = new RouteRepository(folder.newFolder("routes"));
        repository.save(route("route-old", "旧路线", 0d));
        Thread.sleep(10);
        repository.save(route("route-new", "新路线", 0.01d));

        List<RouteSummary> summaries = repository.list();
        assertEquals(2, summaries.size());
        assertEquals("route-new", summaries.get(0).id());
        assertEquals("route-old", summaries.get(1).id());
    }

    @Test
    public void corruptFileIsQuarantinedNotDeleted() throws Exception {
        File dir = folder.newFolder("routes");
        RouteRepository repository = new RouteRepository(dir);
        File bad = new File(dir, "route-bad.json");
        Files.write(bad.toPath(), "{ this is not json".getBytes(StandardCharsets.UTF_8));

        List<RouteSummary> summaries = repository.list();
        assertTrue(summaries.isEmpty());
        assertFalse("原位置不应保留损坏文件", bad.isFile());
        File quarantined = new File(new File(dir, "corrupt"), "route-bad.json");
        assertTrue("损坏文件应被移到 corrupt/ 保留现场", quarantined.isFile());
        List<String> warnings = repository.drainWarnings();
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("route-bad"));
        assertTrue("警告取走后应清空", repository.drainWarnings().isEmpty());
    }

    @Test
    public void deleteRemovesFileAndIsIdempotent() throws Exception {
        RouteRepository repository = new RouteRepository(folder.newFolder("routes"));
        repository.save(route("route-del", "待删", 0d));
        assertTrue(repository.delete("route-del"));
        assertFalse(repository.delete("route-del"));
        assertFalse(new File(repository.routesDir(), "route-del.json").exists());
    }

    @Test
    public void ignoresFilesThatAreNotRouteIds() throws Exception {
        File dir = folder.newFolder("routes");
        RouteRepository repository = new RouteRepository(dir);
        Files.write(new File(dir, "notes.txt").toPath(), "hello".getBytes(StandardCharsets.UTF_8));
        repository.save(route("route-ok", "正常", 0d));

        List<RouteSummary> summaries = repository.list();
        assertEquals(1, summaries.size());
        assertEquals("route-ok", summaries.get(0).id());
        assertTrue("非路线文件不应被隔离", new File(dir, "notes.txt").isFile());
    }

    @Test
    public void newRouteIdIsValidAndUnique() {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            String id = RouteRepository.newRouteId();
            assertTrue(id, RouteRepository.isValidId(id));
            assertFalse("ID 不应重复", ids.contains(id));
            ids.add(id);
        }
    }

    @Test
    public void rejectsInvalidIds() {
        assertFalse(RouteRepository.isValidId(""));
        assertFalse(RouteRepository.isValidId("../evil"));
        assertFalse(RouteRepository.isValidId("route/with/slash"));
        assertFalse(RouteRepository.isValidId("a".repeat(81)));
        assertTrue(RouteRepository.isValidId("route-20261026-120000-abcdef"));
    }
}
