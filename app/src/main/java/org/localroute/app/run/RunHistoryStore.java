package org.localroute.app.run;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

/**
 * 跑圈历史的本机存储。
 *
 * <p>与运行状态分开：{@code SimulationStateRepo} 是进程内单例、重启即空，历史记录要长期留着，
 * 所以单独一个偏好文件，也方便整体进备份与一键清空。
 */
public final class RunHistoryStore {

    public static final String PREFS = "run_history";
    public static final String KEY_RECORDS = "records";
    /** 列表只保留最近这么多条：按每天 3 次跑算够记两个多月，再多就没人看，也会拖慢统计。 */
    public static final int MAX_RECORDS = 300;

    private RunHistoryStore() {
    }

    /** 最新的在前。 */
    @NonNull
    public static List<RunRecord> load(Context context) {
        return RunHistoryCodec.decode(prefs(context).getString(KEY_RECORDS, "[]"));
    }

    public static void save(Context context, @NonNull List<RunRecord> records) {
        List<RunRecord> trimmed = records.size() > MAX_RECORDS
                ? new ArrayList<>(records.subList(0, MAX_RECORDS))
                : new ArrayList<>(records);
        prefs(context).edit().putString(KEY_RECORDS, RunHistoryCodec.encode(trimmed)).apply();
    }

    /** 追加一条并挤掉超出上限的旧记录；里程为 0 的记录（误触即停）不入库。 */
    public static void add(Context context, @NonNull RunRecord record) {
        if (record.distanceMeters() <= 0d) {
            return;
        }
        List<RunRecord> records = load(context);
        for (int i = 0; i < records.size(); i++) {
            if (records.get(i).id().equals(record.id()) && !record.id().isEmpty()) {
                // 同一次运行重复收尾（完成后再点停止）：以新的这条为准，不记两遍里程。
                records.set(i, record);
                save(context, records);
                return;
            }
        }
        records.add(0, record);
        save(context, records);
    }

    public static void remove(Context context, @NonNull String id) {
        List<RunRecord> records = load(context);
        for (int i = 0; i < records.size(); i++) {
            if (records.get(i).id().equals(id)) {
                records.remove(i);
                save(context, records);
                return;
            }
        }
    }

    public static void clear(Context context) {
        prefs(context).edit().remove(KEY_RECORDS).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
