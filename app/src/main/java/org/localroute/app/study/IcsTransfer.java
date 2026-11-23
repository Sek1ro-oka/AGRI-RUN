package org.localroute.app.study;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 把 .ics 文本搬到用户选定的文件：只有这一层碰 {@code ContentResolver}，
 * 所以 {@link IcsTimetableWriter} 仍能留在纯 Java 侧被单元测试逐行断言。
 *
 * <p>写在工作线程执行、回调切回主线程，与备份导出走同一套做法：
 * 用户选完保存位置后界面不能卡住。
 */
public final class IcsTransfer {

    public interface SaveListener {
        void onSaved(int events);

        void onFailed(@NonNull String detail);
    }

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private IcsTransfer() {
    }

    /** {@code "wt"} 是覆盖写：同名再导出时留最后一次，不在网盘里攒一堆副本。 */
    public static void save(@NonNull Context context, @NonNull String text, int events,
                            @NonNull Uri target, @NonNull SaveListener listener) {
        WORKER.execute(() -> {
            try (OutputStream output = context.getContentResolver().openOutputStream(target, "wt")) {
                if (output == null) {
                    throw new IOException("openOutputStream(" + target + ") = null");
                }
                output.write(text.getBytes(StandardCharsets.UTF_8));
            } catch (IOException | RuntimeException e) {
                String detail = message(e);
                MAIN.post(() -> listener.onFailed(detail));
                return;
            }
            MAIN.post(() -> listener.onSaved(events));
        });
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
