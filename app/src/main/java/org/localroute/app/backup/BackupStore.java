package org.localroute.app.backup;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.localroute.app.campus.CampusRoute;
import org.localroute.app.campus.CampusRouteCodec;
import org.localroute.app.model.RouteDefinition;
import org.localroute.app.model.RouteJsonCodec;
import org.localroute.app.storage.RouteRepository;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 备份的收集与写回：连接 {@link BackupCatalog} 描述的存储位置与实际 SharedPreferences / 文件。
 *
 * <p>格式与合并判定都在 {@link BackupCodec}、{@link BackupMerge} 里，本类只负责搬运；
 * 读写放在工作线程执行（{@link BackupTransfer}），界面线程不参与磁盘。
 */
public final class BackupStore {

    public static final String FILE_PREFIX = "unloop-backup-";
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** 一段数据的写回结果，用于导入完成后的计数提示。 */
    public static final class SectionResult {
        private final String id;
        private int applied;
        private int skipped;
        private int dropped;

        SectionResult(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        /** 实际写入的数据条数（或恢复的设置项数）。 */
        public int applied() {
            return applied;
        }

        /** 因为本机已有、或内容读不出来而没有写入的条数。 */
        public int skipped() {
            return skipped;
        }

        /** 整段替换时被备份内容顶掉的本机条数。 */
        public int dropped() {
            return dropped;
        }
    }

    private BackupStore() {
    }

    /** 备份文件名不带敏感信息，只含版本号与时间，便于用户自己区分多份。 */
    @NonNull
    public static String suggestedFileName(@NonNull String versionName, @NonNull LocalDateTime now) {
        return FILE_PREFIX + versionName + "-" + now.format(STAMP) + ".json";
    }

    @NonNull
    public static BackupBundle collect(@NonNull Context context, @NonNull List<String> sectionIds,
                                       @NonNull String versionName, int versionCode,
                                       long nowMillis) throws JSONException {
        BackupBundle bundle = new BackupBundle(nowMillis, versionName, versionCode);
        for (String id : sectionIds) {
            BackupCatalog.Section section = BackupCatalog.byId(id);
            if (section == null) {
                continue;
            }
            if (BackupCatalog.SECTION_ROUTES.equals(id)) {
                putIfPresent(bundle, collectRoutes(context));
            } else if (BackupCatalog.SECTION_ROUTE_FILES.equals(id)) {
                putIfPresent(bundle, collectRouteFiles(context));
            } else {
                putIfPresent(bundle, collectPrefs(context, section));
            }
        }
        return bundle;
    }

    private static void putIfPresent(BackupBundle bundle, BackupSection section) {
        if (section != null && section.count() > 0) {
            bundle.put(section);
        }
    }

    private static BackupSection collectPrefs(Context context, BackupCatalog.Section section)
            throws JSONException {
        Map<String, Object> values = new TreeMap<>();
        for (BackupCatalog.KeyRef key : section.keys()) {
            Map<String, ?> all = context.getSharedPreferences(key.prefs(), Context.MODE_PRIVATE).getAll();
            Object value = all.get(key.key());
            if (value != null) {
                values.put(prefValueKey(key), value);
            }
        }
        JSONObject data = new JSONObject();
        data.put("values", BackupValues.pack(values));
        return new BackupSection(section.id(), section.schema(), values.size(), data);
    }

    private static BackupSection collectRoutes(Context context) throws JSONException {
        List<CampusRoute> routes = localRoutes(context);
        JSONArray array = new JSONArray();
        int custom = 0;
        for (CampusRoute route : routes) {
            if (route.isPreset()) {
                // 预设路线由内置目录重新给出，备份里再存一份只会在换机后变成「重复的预设」。
                continue;
            }
            custom++;
            JSONObject entry = new JSONObject(CampusRouteCodec.encode(
                    Collections.singletonList(route)));
            array.put(entry.optJSONArray("routes").optJSONObject(0));
        }
        JSONObject data = new JSONObject();
        data.put("routes", array);
        return new BackupSection(BackupCatalog.SECTION_ROUTES, BackupCatalog.byId(
                BackupCatalog.SECTION_ROUTES).schema(), custom, data);
    }

    private static BackupSection collectRouteFiles(Context context) throws JSONException {
        File dir = new File(context.getFilesDir(), "routes");
        File[] files = dir.listFiles((parent, name) -> name.endsWith(".json"));
        JSONArray array = new JSONArray();
        int count = 0;
        if (files != null) {
            for (File file : files) {
                try {
                    JSONObject data = new JSONObject(read(file));
                    JSONObject item = new JSONObject();
                    item.put("id", file.getName().substring(0, file.getName().length() - 5));
                    item.put("data", data);
                    array.put(item);
                    count++;
                } catch (RuntimeException | IOException | JSONException ignored) {
                    // 单个文件读不动就跳过：备份不该因为一条坏路线整体失败。
                }
            }
        }
        if (count == 0) {
            return null;
        }
        JSONObject data = new JSONObject();
        data.put("files", array);
        return new BackupSection(BackupCatalog.SECTION_ROUTE_FILES,
                BackupCatalog.byId(BackupCatalog.SECTION_ROUTE_FILES).schema(), count, data);
    }

    /**
     * 写回备份内容。
     *
     * @param sectionIds 用户确认要导入的段落；不在清单里的段落完全不写
     * @return 每段的结果，供界面汇总
     */
    @NonNull
    public static List<SectionResult> apply(@NonNull Context context, @NonNull BackupBundle bundle,
                                            @NonNull List<String> sectionIds) throws JSONException {
        List<SectionResult> results = new ArrayList<>();
        for (String id : sectionIds) {
            BackupSection section = bundle.get(id);
            if (section == null) {
                continue;
            }
            if (BackupCatalog.SECTION_ROUTES.equals(id)) {
                results.add(applyRoutes(context, section));
            } else if (BackupCatalog.SECTION_ROUTE_FILES.equals(id)) {
                results.add(applyRouteFiles(context, section));
            } else {
                results.add(applyPrefs(context, section));
            }
        }
        return results;
    }

    private static SectionResult applyPrefs(Context context, BackupSection section) {
        BackupCatalog.Section kind = BackupCatalog.byId(section.name());
        SectionResult result = new SectionResult(section.name());
        if (kind == null) {
            result.skipped = section.count();
            return result;
        }
        Map<String, Object> values = BackupValues.unpack(section.payload().optJSONObject("values"));
        for (BackupCatalog.KeyRef key : kind.keys()) {
            Object value = values.get(prefValueKey(key));
            if (value == null) {
                continue;
            }
            writePref(context.getSharedPreferences(key.prefs(), Context.MODE_PRIVATE), key, value);
            result.applied++;
        }
        return result;
    }

    private static SectionResult applyRoutes(Context context, BackupSection section) {
        BackupCatalog.KeyRef routesKey = customRoutesKey();
        SectionResult result = new SectionResult(section.name());
        List<CampusRoute> local = localRoutes(context);
        List<CampusRoute> incoming = new ArrayList<>();
        JSONArray array = section.payload().optJSONArray("routes");
        if (array == null) {
            return result;
        }
        for (int i = 0; i < array.length(); i++) {
            JSONObject entry = array.optJSONObject(i);
            if (entry == null) {
                result.skipped++;
                continue;
            }
            // 逐条交给 CampusRouteCodec 校验，避免备份里的坏航点进到存储。
            String single = "{\"version\":" + CampusRouteCodec.SCHEMA_VERSION + ",\"routes\":["
                    + entry + "]}";
            List<CampusRoute> parsed = CampusRouteCodec.decode(single);
            if (parsed.isEmpty()) {
                result.skipped++;
            } else {
                incoming.add(parsed.get(0));
            }
        }
        List<BackupMerge.Entry> localIds = new ArrayList<>();
        for (CampusRoute route : local) {
            localIds.add(new BackupMerge.Entry(route.id(), 0L));
        }
        List<BackupMerge.Entry> incomingIds = new ArrayList<>();
        for (CampusRoute route : incoming) {
            incomingIds.add(new BackupMerge.Entry(route.id(), 0L));
        }
        BackupMerge.Plan plan = BackupMerge.plan(localIds, incomingIds,
                BackupCatalog.policyOf(section.name()));
        Map<String, CampusRoute> incomingById = new TreeMap<>();
        for (CampusRoute route : incoming) {
            incomingById.put(route.id(), route);
        }
        List<CampusRoute> merged = new ArrayList<>(local);
        for (int i = 0; i < plan.actions().size(); i++) {
            if (plan.actions().get(i) != BackupMerge.Action.ADD) {
                result.skipped++;
                continue;
            }
            CampusRoute route = incomingById.get(plan.entries().get(i).id());
            if (route == null) {
                result.skipped++;
            } else {
                merged.add(route);
                result.applied++;
            }
        }
        context.getSharedPreferences(routesKey.prefs(), Context.MODE_PRIVATE)
                .edit().putString(routesKey.key(), CampusRouteCodec.encode(merged)).apply();
        return result;
    }

    private static SectionResult applyRouteFiles(Context context, BackupSection section) {
        SectionResult result = new SectionResult(section.name());
        JSONArray array = section.payload().optJSONArray("files");
        if (array == null) {
            return result;
        }
        RouteRepository repository = new RouteRepository(new File(context.getFilesDir(), "routes"));
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            String id = item == null ? "" : item.optString("id", "");
            JSONObject data = item == null ? null : item.optJSONObject("data");
            if (!RouteRepository.isValidId(id) || data == null) {
                result.skipped++;
                continue;
            }
            try {
                RouteDefinition route = RouteJsonCodec.decode(data.toString());
                if (!id.equals(route.id())) {
                    // 文件名与内容 id 不一致时不写，否则会和存储层的隔离规则打架。
                    result.skipped++;
                    continue;
                }
                repository.save(route);
                result.applied++;
            } catch (Exception e) {
                result.skipped++;
            }
        }
        return result;
    }

    private static List<CampusRoute> localRoutes(Context context) {
        BackupCatalog.KeyRef routesKey = customRoutesKey();
        SharedPreferences prefs =
                context.getSharedPreferences(routesKey.prefs(), Context.MODE_PRIVATE);
        return CampusRouteCodec.decode(prefs.getString(routesKey.key(), ""));
    }

    /** 自建路线存在哪个文件、哪个键，以 {@link BackupCatalog} 为准，免得两处走散。 */
    private static BackupCatalog.KeyRef customRoutesKey() {
        return BackupCatalog.byId(BackupCatalog.SECTION_ROUTES).keys().get(0);
    }

    /** SharedPreferences 文件相同、键名相同时仍可能类型不同，所以键名带上文件前缀。 */
    private static String prefValueKey(BackupCatalog.KeyRef key) {
        return key.prefs() + "/" + key.key();
    }

    private static void writePref(SharedPreferences prefs, BackupCatalog.KeyRef key, Object value) {
        SharedPreferences.Editor editor = prefs.edit();
        switch (key.type()) {
            case BackupValues.TYPE_BOOLEAN:
                editor.putBoolean(key.key(), BackupValues.asBoolean(value, false));
                break;
            case BackupValues.TYPE_INT:
                editor.putInt(key.key(), (int) BackupValues.asLong(value, 0L));
                break;
            case BackupValues.TYPE_LONG:
                editor.putLong(key.key(), BackupValues.asLong(value, 0L));
                break;
            case BackupValues.TYPE_FLOAT:
                editor.putFloat(key.key(), BackupValues.asFloat(value, 0f));
                break;
            default:
                editor.putString(key.key(), BackupValues.asString(value, ""));
                break;
        }
        editor.apply();
    }

    private static String read(File file) throws IOException {
        try (InputStream input = new FileInputStream(file)) {
            return readAll(input);
        }
    }

    static String readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) > 0) {
            output.write(buffer, 0, read);
        }
        return output.toString(UTF8.name());
    }

    static void writeAll(OutputStream output, String text) throws IOException {
        output.write(text.getBytes(UTF8));
        output.flush();
    }
}
