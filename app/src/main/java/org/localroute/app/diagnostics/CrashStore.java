package org.localroute.app.diagnostics;

import android.content.Context;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.localroute.app.BuildConfig;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 崩溃记录的落盘与读取：只写本机 {@code files/crash/}，不发网络、不外发、不申请任何权限。
 *
 * <p>这一层的活只有三件：接住未捕获异常、把 {@link CrashReport} 的文本写成一个文件、
 * 保留最近 {@value #MAX_RECORDS} 份。写文件本身出任何问题都被吞掉——崩溃已经发生了，
 * 再抛一个异常只会把「为什么崩」这条线索一起弄丢。
 *
 * <p>装好之后一定把异常转交给原来的默认处理器：系统该收的进程还是要收，本应用不做「崩了继续跑」
 * 那种把状态留在半路上的事。
 */
public final class CrashStore {

    /** 留几份：一份最长 16 KB，10 份不到 160 KB，够回看最近几天，也不占存储。 */
    public static final int MAX_RECORDS = 10;
    private static final String DIRECTORY = "crash";
    private static final String SUFFIX = ".txt";
    private static final Charset UTF_8 = Charset.forName("UTF-8");

    private static boolean installed;

    private CrashStore() {
    }

    /** 在 {@code Application.onCreate} 里调用；重复调用只会装一次。 */
    public static synchronized void install(@NonNull Context context) {
        Context app = context.getApplicationContext();
        if (installed) {
            return;
        }
        installed = true;
        final Thread.UncaughtExceptionHandler previous =
                Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                write(app, thread, error);
            } catch (Throwable ignored) {
                // 记录失败不是新问题：崩溃本身才是要说的那件事，别让写文件反过来吞掉它。
            }
            if (previous != null) {
                previous.uncaughtException(thread, error);
            }
        });
    }

    /**
     * 一份记录就是一个文件，名字带崩溃时刻，因此文件名本身就是排序键。
     *
     * <p>目录建不出来（存储被占用、只读）就什么都不做，等下次崩溃再试。
     */
    private static void write(Context app, Thread thread, Throwable error) {
        File directory = directory(app, true);
        if (directory == null) {
            return;
        }
        String text = CrashReport.format(CrashReport.header(BuildConfig.VERSION_NAME,
                BuildConfig.VERSION_CODE, Build.MANUFACTURER, Build.MODEL,
                Build.VERSION.SDK_INT, thread), error);
        File target = new File(directory, CrashReport.fileName(LocalDateTime.now()));
        try (FileOutputStream output = new FileOutputStream(target)) {
            output.write(text.getBytes(UTF_8));
        } catch (IOException ignored) {
            return;
        }
        trim(directory);
    }

    /** 最近的几份，新在前。每份按 {@link CrashReport#clamp} 再收一次长度。 */
    @NonNull
    public static List<String> recent(@NonNull Context context) {
        File directory = directory(context, false);
        List<String> texts = new ArrayList<>();
        if (directory == null) {
            return texts;
        }
        File[] files = directory.listFiles((dir, name) -> name.endsWith(SUFFIX));
        if (files == null) {
            return texts;
        }
        Arrays.sort(files, (left, right) -> right.getName().compareTo(left.getName()));
        for (int i = 0; i < files.length && i < MAX_RECORDS; i++) {
            String text = read(files[i]);
            if (text != null) {
                texts.add(CrashReport.clamp(text));
            }
        }
        return texts;
    }

    public static int count(@NonNull Context context) {
        File directory = directory(context, false);
        if (directory == null) {
            return 0;
        }
        File[] files = directory.listFiles((dir, name) -> name.endsWith(SUFFIX));
        return files == null ? 0 : files.length;
    }

    public static void clear(@NonNull Context context) {
        File directory = directory(context, false);
        if (directory == null) {
            return;
        }
        File[] files = directory.listFiles((dir, name) -> name.endsWith(SUFFIX));
        if (files == null) {
            return;
        }
        for (File file : files) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    private static void trim(File directory) {
        File[] files = directory.listFiles((dir, name) -> name.endsWith(SUFFIX));
        if (files == null || files.length <= MAX_RECORDS) {
            return;
        }
        Arrays.sort(files, (left, right) -> right.getName().compareTo(left.getName()));
        for (int i = MAX_RECORDS; i < files.length; i++) {
            //noinspection ResultOfMethodCallIgnored
            files[i].delete();
        }
    }

    @Nullable
    private static String read(File file) {
        long length = file.length();
        if (length <= 0 || length > CrashReport.MAX_TOTAL_LENGTH * 2L) {
            return null;
        }
        byte[] buffer = new byte[(int) Math.min(length, Integer.MAX_VALUE)];
        try (FileInputStream input = new FileInputStream(file)) {
            int read = input.read(buffer);
            return read <= 0 ? null : new String(buffer, 0, read, UTF_8);
        } catch (IOException ignored) {
            return null;
        }
    }

    @Nullable
    private static File directory(Context context, boolean create) {
        File file = new File(context.getApplicationContext().getFilesDir(), DIRECTORY);
        if (file.isDirectory()) {
            return file;
        }
        if (!create) {
            return null;
        }
        //noinspection ResultOfMethodCallIgnored
        file.mkdirs();
        return file.isDirectory() ? file : null;
    }
}
