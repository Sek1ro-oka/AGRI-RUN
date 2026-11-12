package org.localroute.app.update;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.Settings;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;

import org.localroute.app.R;
import org.localroute.app.diagnostics.DiagnosticLog;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 更新包的下载与安装：交给系统 DownloadManager，完成后用 FileProvider 拉起安装器。
 *
 * <p>下载前会并发探测 GitHub 直连与加速镜像，选当前最快的一条交给系统；
 * 安装前再由 {@link UpdateVerifier} 核对包名、签名与 Release 记录的 SHA-256。
 *
 * <p>只接受 GitHub Release 里主程序的 APK 附件；下载进度由系统通知展示，
 * 应用只保存下载 id、目标路径与摘要，重启后仍能找回已完成但未安装的包。
 */
public final class UpdateManager {

    private static final String PREFS = "app_update";
    private static final String KEY_DOWNLOAD_ID = "download_id";
    private static final String KEY_DOWNLOAD_VERSION = "download_version";
    private static final String KEY_DOWNLOAD_PATH = "download_path";
    private static final String KEY_DOWNLOAD_SHA256 = "download_sha256";

    /** 线路探测：每个源最多读约 96 KB，用实际吞吐挑最快的一条。 */
    private static final int PROBE_CONNECT_TIMEOUT_MS = 6_000;
    private static final int PROBE_READ_TIMEOUT_MS = 6_000;
    private static final int PROBE_SAMPLE_BYTES = 96 * 1024;
    private static final int PROBE_MIN_BYTES = 4 * 1024;
    private static final int PROBE_BUDGET_MS = 9_000;

    private UpdateManager() {
    }

    /** 下载到应用私有的外部下载目录，不需要存储权限。 */
    public static void startDownload(@NonNull Context context,
                                     @NonNull UpdateChecker.Release release) throws IOException {
        List<String> candidates = UpdateChecker.apkSources(release);
        if (candidates.isEmpty()) {
            throw new IOException("release has no apk");
        }
        String apkUrl = chooseFastestSource(candidates);
        DiagnosticLog.info("更新下载线路：" + UpdateChecker.sourceLabel(apkUrl));
        File directory = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (directory == null) {
            throw new IOException("external files dir unavailable");
        }
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("cannot create download dir");
        }
        File target = new File(directory, "legion-" + safeVersion(release.versionName())
                + ".apk");
        if (target.exists() && !target.delete()) {
            throw new IOException("cannot replace old apk");
        }

        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(apkUrl))
                .setTitle(context.getString(R.string.home_update_notification_title))
                .setDescription(context.getString(
                        R.string.home_update_notification_description,
                        release.versionName()))
                .setMimeType("application/vnd.android.package-archive")
                .setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(context,
                        Environment.DIRECTORY_DOWNLOADS, target.getName())
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(false);
        DownloadManager manager =
                (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        if (manager == null) {
            throw new IOException("download manager unavailable");
        }
        long id = manager.enqueue(request);
        prefs(context).edit()
                .putLong(KEY_DOWNLOAD_ID, id)
                .putString(KEY_DOWNLOAD_VERSION, release.versionName())
                .putString(KEY_DOWNLOAD_PATH, target.getAbsolutePath())
                .putString(KEY_DOWNLOAD_SHA256, release.apkSha256() == null
                        ? "" : release.apkSha256())
                .apply();
    }

    /** 校验下载完成的 APK；摘要缺失时仍会核对包名与签名。 */
    @NonNull
    public static UpdateVerifier.Issue verifyDownload(@NonNull Context context,
                                                      @NonNull File apk) {
        String expected = prefs(context).getString(KEY_DOWNLOAD_SHA256, "");
        return UpdateVerifier.verify(context, apk, expected);
    }

    /** 校验失败或用户放弃时删除安装包与记录，避免反复提示同一个坏文件。 */
    public static void discardDownload(@NonNull Context context) {
        String path = prefs(context).getString(KEY_DOWNLOAD_PATH, null);
        if (path != null && !path.isEmpty()) {
            File file = new File(path);
            if (file.isFile() && !file.delete()) {
                DiagnosticLog.warn("更新包删除失败：" + file.getName());
            }
        }
        clearPending(context);
    }

    public static long pendingDownloadId(@NonNull Context context) {
        return prefs(context).getLong(KEY_DOWNLOAD_ID, -1);
    }

    @NonNull
    public static String pendingVersion(@NonNull Context context) {
        return prefs(context).getString(KEY_DOWNLOAD_VERSION, "");
    }

    public static boolean isDownloadActive(@NonNull Context context) {
        int status = queryStatus(context);
        return status == DownloadManager.STATUS_PENDING
                || status == DownloadManager.STATUS_RUNNING;
    }

    public static boolean isDownloadSuccessful(@NonNull Context context) {
        return queryStatus(context) == DownloadManager.STATUS_SUCCESSFUL;
    }

    /** 已完成且文件仍在时返回 APK；下载中或失败返回 null。 */
    @Nullable
    public static File downloadedFile(@NonNull Context context) {
        if (!isDownloadSuccessful(context)) {
            return null;
        }
        String path = prefs(context).getString(KEY_DOWNLOAD_PATH, null);
        if (path == null || path.isEmpty()) {
            return null;
        }
        File file = new File(path);
        return file.isFile() && file.length() > 0 ? file : null;
    }

    /** 安装器已拉起或用户放弃后清除记录，避免下次进入又提示。 */
    public static void clearPending(@NonNull Context context) {
        prefs(context).edit()
                .remove(KEY_DOWNLOAD_ID)
                .remove(KEY_DOWNLOAD_VERSION)
                .remove(KEY_DOWNLOAD_PATH)
                .remove(KEY_DOWNLOAD_SHA256)
                .apply();
    }

    public static boolean canInstall(@NonNull Context context) {
        return context.getPackageManager().canRequestPackageInstalls();
    }

    public static void openInstallSettings(@NonNull Activity activity) {
        Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + activity.getPackageName()));
        try {
            activity.startActivity(intent);
        } catch (ActivityNotFoundException error) {
            activity.startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS));
        }
    }

    /** 拉起系统安装器；成功后调用方应清除待安装记录。 */
    public static boolean launchInstaller(@NonNull Context context, @NonNull File apk) {
        try {
            Uri uri = FileProvider.getUriForFile(context,
                    context.getPackageName() + ".fileprovider", apk);
            Intent intent = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            return true;
        } catch (ActivityNotFoundException | IllegalArgumentException error) {
            return false;
        }
    }

    /**
     * 并发读取每条线路的前 96 KB，谁的实际吞吐最高就用谁。这样国内网络下
     * 会绕开被墙的直连和过慢的镜像，不需要用户手动选择或挂代理。
     */
    @NonNull
    static String chooseFastestSource(@NonNull List<String> candidates)
            throws IOException {
        ExecutorService pool = Executors.newFixedThreadPool(candidates.size(), runnable -> {
            Thread thread = new Thread(runnable, "update-probe");
            thread.setDaemon(true);
            return thread;
        });
        CompletionService<Probe> completion = new ExecutorCompletionService<>(pool);
        for (String candidate : candidates) {
            completion.submit(() -> probe(candidate));
        }
        List<Probe> probes = new ArrayList<>(candidates.size());
        long deadline = System.currentTimeMillis() + PROBE_BUDGET_MS;
        try {
            for (int i = 0; i < candidates.size(); i++) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    break;
                }
                Future<Probe> future;
                try {
                    future = completion.poll(remaining, TimeUnit.MILLISECONDS);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (future == null) {
                    break;
                }
                try {
                    Probe probe = future.get();
                    if (probe != null) {
                        probes.add(probe);
                    }
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (ExecutionException ignored) {
                    // 单个线路失败不影响其他线路。
                }
            }
        } finally {
            pool.shutdownNow();
        }
        Probe best = null;
        for (Probe probe : probes) {
            if (probe.bytes < PROBE_MIN_BYTES) {
                continue;
            }
            if (best == null || probe.speed() > best.speed()) {
                best = probe;
            }
        }
        if (best == null) {
            throw new IOException("更新线路都连不上，请检查网络后重试");
        }
        return best.url;
    }

    /** 读取文件开头并确认是 APK（ZIP 以 PK 开头），防止把镜像的 HTML 页面当成安装包。 */
    @Nullable
    private static Probe probe(@NonNull String url) {
        HttpURLConnection connection = null;
        long start = System.currentTimeMillis();
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(PROBE_CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(PROBE_READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("Range", "bytes=0-" + (PROBE_SAMPLE_BYTES - 1));
            connection.setRequestProperty("User-Agent", "Legion-Android-Updater");
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK && code != 206) {
                return null;
            }
            try (InputStream input = connection.getInputStream()) {
                byte[] magic = new byte[2];
                int got = 0;
                while (got < magic.length) {
                    int read = input.read(magic, got, magic.length - got);
                    if (read < 0) {
                        return null;
                    }
                    got += read;
                }
                if (magic[0] != 'P' || magic[1] != 'K') {
                    return null;
                }
                int total = magic.length;
                byte[] buffer = new byte[16 * 1024];
                while (total < PROBE_SAMPLE_BYTES) {
                    int read = input.read(buffer, 0,
                            Math.min(buffer.length, PROBE_SAMPLE_BYTES - total));
                    if (read < 0) {
                        break;
                    }
                    total += read;
                    if (System.currentTimeMillis() - start > PROBE_BUDGET_MS) {
                        break;
                    }
                }
                if (total < PROBE_MIN_BYTES) {
                    return null;
                }
                return new Probe(url, total,
                        Math.max(1, System.currentTimeMillis() - start));
            }
        } catch (IOException | RuntimeException error) {
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /** 一次线路探测的结果：读取字节数与耗时，用于比较吞吐。 */
    private static final class Probe {
        final String url;
        final long bytes;
        final long elapsedMs;

        Probe(@NonNull String url, long bytes, long elapsedMs) {
            this.url = url;
            this.bytes = bytes;
            this.elapsedMs = elapsedMs;
        }

        double speed() {
            return bytes / (double) elapsedMs;
        }
    }

    private static int queryStatus(@NonNull Context context) {
        long id = pendingDownloadId(context);
        if (id < 0) {
            return -1;
        }
        DownloadManager manager =
                (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        if (manager == null) {
            return -1;
        }
        try (Cursor cursor = manager.query(
                new DownloadManager.Query().setFilterById(id))) {
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getInt(cursor.getColumnIndexOrThrow(
                        DownloadManager.COLUMN_STATUS));
            }
        } catch (RuntimeException ignored) {
            // 下载记录被系统清理时按「没有下载」处理。
        }
        return -1;
    }

    @NonNull
    private static String safeVersion(@NonNull String version) {
        String safe = version.replaceAll("[^0-9A-Za-z._-]", "_");
        return safe.isEmpty() ? "latest" : safe;
    }

    private static SharedPreferences prefs(@NonNull Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
