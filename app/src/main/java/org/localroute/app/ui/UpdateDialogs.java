package org.localroute.app.ui;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import androidx.annotation.NonNull;

import org.localroute.app.BuildConfig;
import org.localroute.app.R;
import org.localroute.app.update.UpdateChecker;
import org.localroute.app.update.UpdateManager;

/**
 * 更新弹窗：更新页的手动检查与首页的启动静默检查共用同一套文案与按钮。
 *
 * <p>弹窗展示即记为「已提醒过这个版本」，两个入口不会对同一版本重复打扰。
 */
public final class UpdateDialogs {

    private UpdateDialogs() {
    }

    /**
     * 展示「发现新版本」。
     *
     * @param automatic      启动时的自动提醒：关闭按钮写「暂不更新」，并说明本版本不再自动提醒
     * @param downloadAction 点「下载并安装」后的动作；附件缺失时改为打开发布页，不会调用
     */
    public static void showFound(@NonNull Activity activity,
                                 @NonNull UpdateChecker.Release release,
                                 boolean automatic,
                                 @NonNull Runnable downloadAction) {
        UpdateManager.markPrompted(activity, release.versionName());
        String notes = release.notes().isEmpty()
                ? activity.getString(R.string.home_update_no_notes) : release.notes();
        if (release.apkUrl() == null) {
            notes = notes + "\n\n" + activity.getString(R.string.home_update_no_apk);
        }
        if (automatic) {
            notes = notes + activity.getString(R.string.home_update_auto_hint);
        }
        String message = activity.getString(R.string.home_update_dialog_message,
                BuildConfig.VERSION_NAME, release.versionName(), notes);
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
                .setTitle(activity.getString(R.string.home_update_dialog_title,
                        release.versionName()))
                .setMessage(message)
                .setNegativeButton(automatic
                        ? R.string.home_update_ignore : R.string.campus_cancel, null)
                .setPositiveButton(release.apkUrl() == null
                                ? R.string.home_update_release_page
                                : R.string.home_update_download,
                        (dialog, which) -> {
                            if (release.apkUrl() == null) {
                                openReleasePage(activity, release.pageUrl());
                            } else {
                                downloadAction.run();
                            }
                        })
                .show();
    }

    /** 没有可用浏览器时如实提示，不让按钮看起来像点坏了。 */
    public static void openReleasePage(@NonNull Activity activity, @NonNull String url) {
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException error) {
            Toast.makeText(activity, R.string.home_update_release_open_failed,
                    Toast.LENGTH_LONG).show();
        }
    }
}
