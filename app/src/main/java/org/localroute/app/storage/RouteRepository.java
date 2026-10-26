package org.localroute.app.storage;

import org.json.JSONException;
import org.localroute.app.model.RouteDefinition;
import org.localroute.app.model.RouteJsonCodec;
import org.localroute.app.model.RouteSummary;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 本地路线仓库：一条路线一个 JSON 文件，文件名使用路线 ID（不使用中文名拼路径）。
 *
 * <p>写入策略：先写 {@code <id>.json.tmp} 并 fsync，再原子替换 {@code <id>.json}；
 * 中途断电最多留下一个临时文件，正式文件不会被写坏。
 * 读取策略：解析失败的文件会被移动到 {@code corrupt/} 子目录隔离并保留现场，不会被静默覆盖或删除。
 */
public final class RouteRepository {

    private final File routesDir;
    private final List<String> warnings = new ArrayList<>();

    public RouteRepository(File routesDir) {
        this.routesDir = routesDir;
        if (!routesDir.exists() && !routesDir.mkdirs()) {
            warnings.add("无法创建路线目录：" + routesDir.getAbsolutePath());
        }
    }

    public File routesDir() {
        return routesDir;
    }

    /** 取出并清空本次操作累积的警告（损坏文件隔离、跳过非法文件等）。 */
    public List<String> drainWarnings() {
        List<String> copy = new ArrayList<>(warnings);
        warnings.clear();
        return copy;
    }

    /** 生成本地路线 ID：时间 + 随机后缀，纯 ASCII，可直接作为文件名。 */
    public static String newRouteId() {
        String timestamp = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.US)
                .withZone(java.time.ZoneOffset.UTC)
                .format(Instant.now());
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        return "route-" + timestamp + "-" + suffix;
    }

    public static boolean isValidId(String id) {
        return id != null && id.matches("[A-Za-z0-9._-]{1,80}");
    }

    public File fileFor(String id) {
        if (!isValidId(id)) {
            throw new IllegalArgumentException("非法路线 id：" + id);
        }
        return new File(routesDir, id + ".json");
    }

    public List<RouteSummary> list() {
        File[] files = routesDir.listFiles((dir, name) -> name.endsWith(".json"));
        if (files == null) {
            warnings.add("无法读取路线目录");
            return Collections.emptyList();
        }
        List<RouteSummary> summaries = new ArrayList<>(files.length);
        for (File file : files) {
            String idFromName = file.getName().substring(0, file.getName().length() - ".json".length());
            try {
                RouteDefinition route = readFile(file);
                if (!route.id().equals(idFromName)) {
                    // 文件名与内容 id 不一致：列表里会出现“点开说路线不存在、删也删不掉”的幽灵条目。
                    quarantine(file, new RouteJsonCodec.RouteFormatException(
                            "文件名与内容 id 不一致（" + idFromName + " ≠ " + route.id() + "）"));
                    continue;
                }
                summaries.add(new RouteSummary(route.id(), route.displayName(), route.updatedAt(),
                        route.points().size()));
            } catch (IOException | JSONException | RouteJsonCodec.RouteFormatException e) {
                quarantine(file, e);
            }
        }
        summaries.sort(Comparator.comparing(RouteSummary::updatedAt).reversed());
        return summaries;
    }

    public RouteDefinition load(String id) throws IOException, RouteJsonCodec.RouteFormatException {
        File file = fileFor(id);
        if (!file.exists()) {
            throw new IOException("路线不存在：" + id);
        }
        try {
            return readFile(file);
        } catch (JSONException | RouteJsonCodec.RouteFormatException e) {
            quarantine(file, e);
            throw new RouteJsonCodec.RouteFormatException("路线文件已损坏，已隔离到 corrupt/ 目录：" + id, e);
        }
    }

    /** 原子保存（新增或覆盖）。 */
    public void save(RouteDefinition route) throws IOException {
        File target = fileFor(route.id());
        // 临时文件名带随机后缀：同一 id 的两次并发保存不会互相截断同一个临时文件。
        File tmp = new File(routesDir,
                route.id() + ".json." + UUID.randomUUID().toString().substring(0, 8) + ".tmp");
        byte[] payload;
        try {
            payload = RouteJsonCodec.encode(route).getBytes(StandardCharsets.UTF_8);
        } catch (JSONException e) {
            throw new IOException("路线序列化失败：" + e.getMessage(), e);
        }

        try (FileOutputStream output = new FileOutputStream(tmp)) {
            output.write(payload);
            output.flush();
            output.getFD().sync();
        }

        try {
            Files.move(tmp.toPath(), target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } finally {
            if (tmp.exists() && !tmp.delete()) {
                warnings.add("临时文件未能删除：" + tmp.getName());
            }
        }
        syncDirectory();
    }

    /**
     * 目录项也要落盘，否则掉电后 rename 可能丢失（旧版本还在，新版本没了）。
     * Windows 上目录不能以 READ 打开，这里 best-effort：失败只忽略，不影响保存结果。
     */
    private void syncDirectory() {
        try (FileChannel channel = FileChannel.open(routesDir.toPath(), StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | RuntimeException ignored) {
            // 平台不支持时跳过（真机 Android 上通常可行）。
        }
    }

    /** 删除路线；文件不存在时返回 false（幂等，重复删除不会报错）。 */
    public boolean delete(String id) {
        File file = fileFor(id);
        if (!file.exists()) {
            return false;
        }
        return file.delete();
    }

    private RouteDefinition readFile(File file) throws IOException, JSONException,
            RouteJsonCodec.RouteFormatException {
        String json = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        return RouteJsonCodec.decode(json);
    }

    private void quarantine(File file, Exception cause) {
        File corruptDir = new File(routesDir, "corrupt");
        if (!corruptDir.exists() && !corruptDir.mkdirs()) {
            warnings.add("无法创建隔离目录，损坏文件保留原处：" + file.getName());
            return;
        }
        // 默认保留原文件名，便于和用户手里的文件对上；重名时才加时间戳后缀。
        File target = new File(corruptDir, file.getName());
        if (target.exists()) {
            String stamp = String.format(Locale.US, "%d", System.currentTimeMillis());
            target = new File(corruptDir, file.getName() + ".corrupt-" + stamp);
        }
        if (file.renameTo(target)) {
            warnings.add("发现损坏的路线文件，已隔离为 corrupt/" + target.getName() + "（" + cause.getMessage() + "）");
        } else {
            warnings.add("发现损坏的路线文件但无法隔离：" + file.getName());
        }
    }
}
