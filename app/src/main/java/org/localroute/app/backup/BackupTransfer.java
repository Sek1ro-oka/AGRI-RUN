package org.localroute.app.backup;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.localroute.app.BuildConfig;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 备份文件与界面之间的搬运层：读写都走 SAF 给的 {@link Uri}，在工作线程执行，回调回到主线程。
 *
 * <p>只有这一层碰 {@code ContentResolver}，所以格式（{@link BackupCodec}）与存储
 * （{@link BackupStore}）都能在 JVM 单元测试里跑通。失败时只回异常原文，
 * 提示文案由界面用字符串资源拼，避免在工作线程里取资源。
 */
public final class BackupTransfer {

    public interface SaveListener {
        void onSaved(int items);

        void onFailed(@NonNull String detail);
    }

    public interface OpenListener {
        void onOpened(@NonNull BackupCodec.DecodeResult result);
    }

    public interface ApplyListener {
        void onApplied(@NonNull List<BackupStore.SectionResult> results,
                       @NonNull List<String> unknownSections);

        void onFailed(@NonNull String detail);
    }

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private BackupTransfer() {
    }

    /** 收集选中段落并写进 SAF 创建好的文件；文件名为空或缺失都算失败。 */
    public static void save(@NonNull Context context, @NonNull List<String> sectionIds,
                            @NonNull Uri target, @NonNull SaveListener listener) {
        WORKER.execute(() -> {
            int items;
            try {
                BackupBundle bundle = BackupStore.collect(context, sectionIds,
                        BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, System.currentTimeMillis());
                String text = BackupCodec.encode(bundle);
                try (OutputStream output = context.getContentResolver()
                        .openOutputStream(target, "wt")) {
                    if (output == null) {
                        throw new IOException("openOutputStream(" + target + ") = null");
                    }
                    BackupStore.writeAll(output, text);
                }
                items = 0;
                for (BackupSection section : bundle.sections()) {
                    items += section.count();
                }
            } catch (IOException | JSONException | RuntimeException e) {
                String detail = message(e);
                MAIN.post(() -> listener.onFailed(detail));
                return;
            }
            int saved = items;
            MAIN.post(() -> listener.onSaved(saved));
        });
    }

    /** 读一份备份并解析；结果连同错误与告警一起回给界面，由界面决定下一步。 */
    public static void open(@NonNull Context context, @NonNull Uri source,
                            @NonNull OpenListener listener) {
        WORKER.execute(() -> {
            BackupCodec.DecodeResult result;
            try (InputStream input = context.getContentResolver().openInputStream(source)) {
                result = input == null
                        ? BackupCodec.DecodeResult.failure("openInputStream(" + source + ") = null")
                        : BackupCodec.decode(BackupStore.readAll(input));
            } catch (IOException | RuntimeException e) {
                result = BackupCodec.DecodeResult.failure(message(e));
            }
            BackupCodec.DecodeResult loaded = result;
            MAIN.post(() -> listener.onOpened(loaded));
        });
    }

    /**
     * 把确认过的段落写回本机。
     *
     * <p>本机不认识的段落 id 一律不写，只把数量交给界面提示；未知段落仍完整留在文件里，
     * 因此新版应用导出、旧版应用恢复也不会丢数据。
     */
    public static void apply(@NonNull Context context, @NonNull BackupBundle bundle,
                             @NonNull List<String> sectionIds, @NonNull ApplyListener listener) {
        List<String> unknown = bundle.unknownNames(BackupCatalog.knownIds());
        List<String> selected = new ArrayList<>(sectionIds);
        selected.retainAll(BackupCatalog.knownIds());
        WORKER.execute(() -> {
            List<BackupStore.SectionResult> results;
            try {
                results = BackupStore.apply(context, bundle, selected);
            } catch (JSONException | RuntimeException e) {
                String detail = message(e);
                MAIN.post(() -> listener.onFailed(detail));
                return;
            }
            MAIN.post(() -> listener.onApplied(results, unknown));
        });
    }

    /** 导出对话框用的默认文件名，带版本号与时间，用户可以在系统保存面板里改。 */
    @NonNull
    public static String suggestedFileName() {
        return BackupStore.suggestedFileName(BuildConfig.VERSION_NAME, LocalDateTime.now());
    }

    @NonNull
    private static String message(@Nullable Throwable e) {
        if (e == null) {
            return "unknown error";
        }
        String text = e.getLocalizedMessage();
        return text == null || text.trim().isEmpty() ? e.getClass().getSimpleName() : text;
    }
}
