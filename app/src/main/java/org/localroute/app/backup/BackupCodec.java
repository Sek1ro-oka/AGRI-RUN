package org.localroute.app.backup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 备份文件的编解码。纯 org.json，不依赖 Context。
 *
 * <p>解码策略是「整体能读就尽量读」：坏段落跳过并记录原因，好段落照常返回，
 * 因为一份备份往往只有个别段落出问题，整份作废反而更容易丢数据。
 * 只有文件本身不是 JSON、或不是本应用的备份格式时才判定失败。
 */
public final class BackupCodec {

    /** 备份文件字符上限。路线按节点上限算，几十条也远不到这个数；超了说明拿错了文件。 */
    public static final int MAX_CHARS = 32 * 1024 * 1024;

    private BackupCodec() {
    }

    @NonNull
    public static String encode(@NonNull BackupBundle bundle) throws JSONException {
        JSONArray sections = new JSONArray();
        for (BackupSection section : bundle.sections()) {
            JSONObject item = new JSONObject();
            item.put("name", section.name());
            item.put("schema", section.schema());
            item.put("count", section.count());
            item.put("data", section.payload());
            sections.put(item);
        }
        JSONObject root = new JSONObject();
        root.put("format", BackupBundle.FORMAT_ID);
        root.put("formatVersion", BackupBundle.FORMAT_VERSION);
        root.put("createdAt", Instant.ofEpochMilli(bundle.createdAtMillis()).toString());
        root.put("appVersion", bundle.appVersionName());
        root.put("appVersionCode", bundle.appVersionCode());
        root.put("sections", sections);
        // 缩进输出：备份是要给人看的，出问题时能直接读懂比省几百字节重要。
        return root.toString(2);
    }

    @NonNull
    public static DecodeResult decode(@Nullable String text) {
        if (text == null || text.trim().isEmpty()) {
            return DecodeResult.failure("文件是空的");
        }
        if (text.length() > MAX_CHARS) {
            return DecodeResult.failure("文件超过 " + (MAX_CHARS / 1024 / 1024) + " MB，不像本应用的备份");
        }
        JSONObject root;
        try {
            root = new JSONObject(text);
        } catch (JSONException ignored) {
            return DecodeResult.failure("不是有效的 JSON 文件");
        }
        String format = root.optString("format", "");
        if (!BackupBundle.FORMAT_ID.equals(format)) {
            return DecodeResult.failure(format.isEmpty()
                    ? "文件里没有「" + BackupBundle.FORMAT_ID + "」标记，可能不是本应用的备份"
                    : "这是「" + format + "」的备份，不是本应用的");
        }
        int formatVersion = root.optInt("formatVersion", 1);
        if (formatVersion > BackupBundle.FORMAT_VERSION) {
            return DecodeResult.failure("备份格式版本 " + formatVersion + " 比当前应用支持的 "
                    + BackupBundle.FORMAT_VERSION + " 更新，请先升级应用");
        }
        BackupBundle bundle = new BackupBundle(parseEpochMillis(root.optString("createdAt", "")),
                root.optString("appVersion", ""), root.optInt("appVersionCode", 0));
        List<String> warnings = new ArrayList<>();
        JSONArray array = root.optJSONArray("sections");
        if (array == null) {
            warnings.add("备份里没有任何数据段");
        } else {
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                String name = item == null ? "" : item.optString("name", "");
                if (name.isEmpty()) {
                    warnings.add("第 " + (i + 1) + " 段缺少名称，已跳过");
                    continue;
                }
                JSONObject data = item.optJSONObject("data");
                if (data == null) {
                    warnings.add("「" + name + "」没有可读的数据，已跳过");
                    continue;
                }
                bundle.put(new BackupSection(name, item.optInt("schema", 1),
                        Math.max(0, item.optInt("count", 0)), data));
            }
        }
        return DecodeResult.success(bundle, warnings);
    }

    /** 时间字段读不出来时按 0 处理，不能因为一个日期把整份备份判死。 */
    private static long parseEpochMillis(String iso) {
        try {
            return Instant.parse(iso).toEpochMilli();
        } catch (RuntimeException ignored) {
            return 0L;
        }
    }

    /** 解码结果：失败时只有原因；成功时带上段落列表与告警。 */
    public static final class DecodeResult {
        private final BackupBundle bundle;
        private final String error;
        private final List<String> warnings;

        private DecodeResult(@Nullable BackupBundle bundle, @Nullable String error,
                             @NonNull List<String> warnings) {
            this.bundle = bundle;
            this.error = error;
            this.warnings = warnings;
        }

        static DecodeResult failure(String error) {
            return new DecodeResult(null, error, new ArrayList<>());
        }

        static DecodeResult success(BackupBundle bundle, List<String> warnings) {
            return new DecodeResult(bundle, null, warnings);
        }

        public boolean ok() {
            return error == null;
        }

        @Nullable
        public String error() {
            return error;
        }

        @Nullable
        public BackupBundle bundle() {
            return bundle;
        }

        @NonNull
        public List<String> warnings() {
            return warnings;
        }
    }
}
