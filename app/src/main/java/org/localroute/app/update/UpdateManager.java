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

import java.io.File;
import java.io.IOException;

/**
 * 更新包的下载与安装：交给系统 DownloadManager，完成后用 FileProvider 拉起安装器。
 *
 * <p>只接受 GitHub Release 里主程序的 APK 附件；下载进度由系统通知展示，
 * 应用只保存下载 id 与目标路径，重启后仍能找回已完成但未安装的包。
 */
public final class UpdateManager {

    private static final String PREFS = "app_update";
    private static final String KEY_DOWNLOAD_ID = "download_id";
    private static final String KEY_DOWNLOAD_VERSION = "download_version";
    private static final String KEY_DOWNLOAD_PATH = "download_path";

    private UpdateManager() {
    }

    /** 下载到应用私有的外部下载目录，不需要存储权限。 */
    public static void startDownload(@NonNull Context context,
                                     @NonNull UpdateChecker.Release release) throws IOException {
        String apkUrl = release.apkUrl();
        if (apkUrl == null || apkUrl.isEmpty()) {
            throw new IOException("release has no apk");
        }
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
                .apply();
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
