package org.localroute.app.ui;

import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import org.localroute.app.BuildConfig;
import org.localroute.app.R;
import org.localroute.app.databinding.ActivityMainBinding;
import org.localroute.app.diagnostics.DiagnosticLog;
import org.localroute.app.overlay.OverlayHost;
import org.localroute.app.update.UpdateChecker;
import org.localroute.app.update.UpdateManager;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 主页：校园快跑、作业查询与检查更新三个入口。
 *
 * <p>更新从项目的 GitHub Releases 读取：有新版本时显示说明，下载交给系统
 * DownloadManager，完成后通过 FileProvider 拉起安装器。
 */
public class MainActivity extends AppCompatActivity {

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private ActivityMainBinding binding;
    private boolean checking;
    private boolean awaitingInstallPermission;

    private final BroadcastReceiver downloadReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
            if (id < 0 || id != UpdateManager.pendingDownloadId(MainActivity.this)) {
                return;
            }
            updateUpdateCard();
            if (UpdateManager.downloadedFile(MainActivity.this) != null) {
                promptInstall(UpdateManager.pendingVersion(MainActivity.this));
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DiagnosticLog.init(getApplicationContext());

        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        ViewCompat.setOnApplyWindowInsetsListener(binding.getRoot(), (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        binding.cardCampusRun.setOnClickListener(v ->
                startActivity(new Intent(this, CampusRunActivity.class)));
        binding.cardStudy.setOnClickListener(v ->
                startActivity(new Intent(this, StudyActivity.class)));
        binding.cardUpdate.setOnClickListener(v -> onUpdateCardTapped());
        updateUpdateCard();

        // 用户上次打开过悬浮控制台：进程一启动就按保存的开关和位置恢复。
        OverlayHost.get(this).restore();
    }

    @Override
    protected void onStart() {
        super.onStart();
        ContextCompat.registerReceiver(this, downloadReceiver,
                new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                ContextCompat.RECEIVER_EXPORTED);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateUpdateCard();
        if (awaitingInstallPermission) {
            awaitingInstallPermission = false;
            if (UpdateManager.canInstall(this) && UpdateManager.downloadedFile(this) != null) {
                promptInstall(UpdateManager.pendingVersion(this));
            }
        }
    }

    @Override
    protected void onStop() {
        unregisterReceiver(downloadReceiver);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ 检查更新

    private void onUpdateCardTapped() {
        if (checking) {
            return;
        }
        if (UpdateManager.isDownloadActive(this)) {
            Toast.makeText(this, R.string.home_update_downloading_wait,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        File apk = UpdateManager.downloadedFile(this);
        if (apk != null) {
            promptInstall(UpdateManager.pendingVersion(this));
            return;
        }
        checkForUpdate();
    }

    private void updateUpdateCard() {
        if (binding == null) {
            return;
        }
        String pendingVersion = UpdateManager.pendingVersion(this);
        if (!pendingVersion.isEmpty()
                && !UpdateChecker.isNewer(pendingVersion, BuildConfig.VERSION_NAME)) {
            // 已经装上这个版本（或更新），旧的安装包记录不再提示。
            UpdateManager.clearPending(this);
            pendingVersion = "";
        }
        File apk = UpdateManager.downloadedFile(this);
        if (apk != null) {
            binding.textUpdateSubtitle.setText(getString(
                    R.string.home_update_downloaded, pendingVersion));
            binding.cardUpdate.setEnabled(true);
            return;
        }
        if (UpdateManager.isDownloadActive(this)) {
            binding.textUpdateSubtitle.setText(getString(
                    R.string.home_update_downloading, pendingVersion));
            binding.cardUpdate.setEnabled(false);
            return;
        }
        binding.cardUpdate.setEnabled(true);
        if (!checking) {
            binding.textUpdateSubtitle.setText(getString(
                    R.string.home_update_subtitle, BuildConfig.VERSION_NAME));
        }
    }

    private void checkForUpdate() {
        if (checking) {
            return;
        }
        checking = true;
        binding.cardUpdate.setEnabled(false);
        binding.textUpdateSubtitle.setText(R.string.home_update_checking);
        executor.execute(() -> {
            try {
                UpdateChecker.Release release = UpdateChecker.fetchLatest();
                mainHandler.post(() -> onCheckFinished(release, null));
            } catch (IOException | RuntimeException error) {
                mainHandler.post(() -> onCheckFinished(null, error));
            }
        });
    }

    private void onCheckFinished(@Nullable UpdateChecker.Release release,
                                 @Nullable Exception error) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        checking = false;
        binding.cardUpdate.setEnabled(true);
        if (error instanceof UpdateChecker.NoReleaseException) {
            updateUpdateCard();
            Toast.makeText(this, R.string.home_update_no_release, Toast.LENGTH_LONG).show();
            return;
        }
        if (error != null) {
            updateUpdateCard();
            String detail = error.getMessage();
            if (detail == null || detail.isEmpty()) {
                detail = getString(R.string.home_update_check_network);
            }
            Toast.makeText(this, getString(R.string.home_update_check_failed, detail),
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (release != null && UpdateChecker.isNewer(release.versionName(),
                BuildConfig.VERSION_NAME)) {
            binding.textUpdateSubtitle.setText(getString(
                    R.string.home_update_found, release.versionName()));
            showUpdateDialog(release);
            return;
        }
        updateUpdateCard();
        Toast.makeText(this, getString(R.string.home_update_latest_toast,
                BuildConfig.VERSION_NAME), Toast.LENGTH_SHORT).show();
    }

    private void showUpdateDialog(@NonNull UpdateChecker.Release release) {
        String notes = release.notes().isEmpty()
                ? getString(R.string.home_update_no_notes) : release.notes();
        if (release.apkUrl() == null) {
            notes = notes + "\n\n" + getString(R.string.home_update_no_apk);
        }
        String message = getString(R.string.home_update_dialog_message,
                BuildConfig.VERSION_NAME, release.versionName(), notes);
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.home_update_dialog_title, release.versionName()))
                .setMessage(message)
                .setNegativeButton(R.string.campus_cancel, null);
        if (release.apkUrl() != null) {
            builder.setPositiveButton(R.string.home_update_download,
                    (dialog, which) -> startDownload(release));
            builder.setNeutralButton(R.string.home_update_release_page,
                    (dialog, which) -> openReleasePage(release.pageUrl()));
        } else {
            builder.setPositiveButton(R.string.home_update_release_page,
                    (dialog, which) -> openReleasePage(release.pageUrl()));
        }
        builder.show();
    }

    private void startDownload(@NonNull UpdateChecker.Release release) {
        try {
            UpdateManager.startDownload(this, release);
            updateUpdateCard();
            Toast.makeText(this, R.string.home_update_started, Toast.LENGTH_LONG).show();
        } catch (IOException | RuntimeException error) {
            updateUpdateCard();
            String detail = error.getMessage();
            if (detail == null || detail.isEmpty()) {
                detail = getString(R.string.home_update_start_failed, "—");
            } else {
                detail = getString(R.string.home_update_start_failed, detail);
            }
            Toast.makeText(this, detail, Toast.LENGTH_LONG).show();
        }
    }

    private void promptInstall(@NonNull String version) {
        String label = version.isEmpty()
                ? BuildConfig.VERSION_NAME : version;
        new AlertDialog.Builder(this)
                .setTitle(R.string.home_update_ready_title)
                .setMessage(getString(R.string.home_update_ready_message, label))
                .setNegativeButton(R.string.home_update_later, null)
                .setPositiveButton(R.string.home_update_install,
                        (dialog, which) -> requestInstall())
                .show();
    }

    private void requestInstall() {
        File apk = UpdateManager.downloadedFile(this);
        if (apk == null) {
            updateUpdateCard();
            return;
        }
        if (!UpdateManager.canInstall(this)) {
            awaitingInstallPermission = true;
            new AlertDialog.Builder(this)
                    .setTitle(R.string.home_update_permission_title)
                    .setMessage(R.string.home_update_permission_message)
                    .setNegativeButton(R.string.campus_cancel, null)
                    .setPositiveButton(R.string.home_update_permission_action,
                            (dialog, which) -> UpdateManager.openInstallSettings(this))
                    .show();
            return;
        }
        if (!UpdateManager.launchInstaller(this, apk)) {
            Toast.makeText(this, R.string.home_update_install_failed,
                    Toast.LENGTH_LONG).show();
        }
    }

    private void openReleasePage(@NonNull String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException error) {
            Toast.makeText(this, R.string.home_update_release_open_failed,
                    Toast.LENGTH_LONG).show();
        }
    }
}
