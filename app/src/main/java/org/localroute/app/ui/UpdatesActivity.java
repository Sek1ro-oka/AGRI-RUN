package org.localroute.app.ui;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.Lifecycle;

import org.localroute.app.BuildConfig;
import org.localroute.app.R;
import org.localroute.app.databinding.ActivityUpdatesBinding;
import org.localroute.app.diagnostics.DiagnosticLog;
import org.localroute.app.overlay.OverlayHost;
import org.localroute.app.update.UpdateChecker;
import org.localroute.app.update.UpdateManager;
import org.localroute.app.update.UpdateManager.DownloadState;
import org.localroute.app.update.UpdateVerifier;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 设置中的应用更新页。
 *
 * <p>更新从项目的 GitHub Releases 读取：有新版本时显示说明，下载交给系统
 * DownloadManager，完成后通过 FileProvider 拉起安装器。
 */
public class UpdatesActivity extends AppCompatActivity {

    private static final long PROGRESS_POLL_MS = 500;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private ActivityUpdatesBinding binding;
    private boolean checking;
    private boolean awaitingInstallPermission;
    /** 正在探测线路并交给系统下载器；此时还没有下载记录，只能显示不确定进度。 */
    private boolean preparing;
    /** 下载完成后的校验；避免轮询把「正在校验」覆盖回「已下载」。 */
    private boolean verifying;
    /** 已通过校验的安装包路径；点「立即安装」时不必再算一次 SHA-256。 */
    private String verifiedPath = "";
    /** 最近一次检查到的发布页；元数据走镜像时，发布页也用同一个镜像。 */
    private String releasePageUrl = UpdateChecker.RELEASES_PAGE;

    private final Runnable progressTick = new Runnable() {
        @Override
        public void run() {
            if (binding == null) {
                return;
            }
            updateUpdateCard();
            if (getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED)
                    && (preparing || verifying
                    || UpdateManager.isDownloadActive(UpdatesActivity.this))) {
                mainHandler.postDelayed(this, PROGRESS_POLL_MS);
            }
        }
    };

    private final BroadcastReceiver downloadReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
            if (id < 0 || id != UpdateManager.pendingDownloadId(UpdatesActivity.this)) {
                return;
            }
            stopProgressPolling();
            if (UpdateManager.isDownloadActive(UpdatesActivity.this)) {
                updateUpdateCard();
                startProgressPolling();
                return;
            }
            File apk = UpdateManager.downloadedFile(UpdatesActivity.this);
            if (apk == null) {
                reportDownloadFailure();
                return;
            }
            verifying = true;
            updateUpdateCard();
            executor.execute(() -> {
                UpdateVerifier.Issue issue =
                        UpdateManager.verifyDownload(UpdatesActivity.this, apk);
                mainHandler.post(() -> onDownloadVerified(issue, apk));
            });
        }
    };

    /** 系统下载器报失败：把状态、原因、进度与线路写进日志并显示出来。 */
    private void reportDownloadFailure() {
        stopProgressPolling();
        String detail = UpdateManager.describeDownload(this);
        DiagnosticLog.warn("更新下载结束但未取得安装包"
                + (detail == null ? "（系统下载记录已不可读）" : "：" + detail));
        Toast.makeText(this, detail == null
                ? getString(R.string.home_update_download_failed)
                : getString(R.string.home_update_download_failed_reason, detail),
                Toast.LENGTH_LONG).show();
        updateUpdateCard();
    }

    /** 下载完成后的首道校验：拦下被网络拦截页替换的假安装包。 */
    private void onDownloadVerified(@NonNull UpdateVerifier.Issue issue,
                                    @NonNull File apk) {
        verifying = false;
        if (isFinishing() || isDestroyed()) {
            return;
        }
        if (issue == UpdateVerifier.Issue.OK) {
            verifiedPath = apk.getAbsolutePath();
            updateUpdateCard();
            promptInstall(UpdateManager.pendingVersion(this));
            return;
        }
        UpdateManager.discardDownload(this);
        verifiedPath = "";
        updateUpdateCard();
        showVerifyFailed(issue, apk);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DiagnosticLog.init(getApplicationContext());

        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        binding = ActivityUpdatesBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        ViewCompat.setOnApplyWindowInsetsListener(binding.getRoot(), (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        binding.cardUpdate.setOnClickListener(v -> onUpdateCardTapped());
        binding.buttonUpdateAction.setOnClickListener(v -> onUpdateActionTapped());
        binding.buttonReleasePage.setOnClickListener(v ->
                UpdateDialogs.openReleasePage(this, releasePageUrl));
        binding.buttonDiagnostics.setOnClickListener(v -> startActivity(
                new Intent(this, DiagnosticsActivity.class)));
        binding.toolbar.setNavigationOnClickListener(v -> finish());
        updateUpdateCard();
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
        if (preparing || verifying || UpdateManager.isDownloadActive(this)) {
            startProgressPolling();
        }
        if (awaitingInstallPermission) {
            awaitingInstallPermission = false;
            if (UpdateManager.canInstall(this) && UpdateManager.downloadedFile(this) != null) {
                promptInstall(UpdateManager.pendingVersion(this));
            }
        }
    }

    @Override
    protected void onStop() {
        stopProgressPolling();
        unregisterReceiver(downloadReceiver);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        stopProgressPolling();
        executor.shutdownNow();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ 检查更新

    private void onUpdateCardTapped() {
        if (checking || preparing || verifying) {
            return;
        }
        DownloadState state = UpdateManager.downloadState(this);
        if (state != null && state.active()) {
            return;
        }
        if (state != null && state.failed()) {
            retryDownload();
            return;
        }
        File apk = UpdateManager.downloadedFile(this);
        if (apk != null) {
            promptInstall(UpdateManager.pendingVersion(this));
            return;
        }
        checkForUpdate();
    }

    private void onUpdateActionTapped() {
        DownloadState state = UpdateManager.downloadState(this);
        if (state == null) {
            return;
        }
        if (state.active()) {
            cancelDownload();
        } else if (state.failed()) {
            if (UpdateManager.canRetry(this, state)) {
                retryDownload();
            } else {
                // 升级前留下的失败记录没有附件地址，退回重新检查。
                checkForUpdate();
            }
        }
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
        DownloadState state = UpdateManager.downloadState(this);
        File apk = UpdateManager.downloadedFile(this, state);
        if (verifying) {
            binding.cardUpdate.setEnabled(false);
            binding.buttonUpdateAction.setVisibility(View.GONE);
            binding.textUpdateSubtitle.setText(R.string.home_update_verifying);
            showProgress(null);
            return;
        }
        if (preparing) {
            binding.cardUpdate.setEnabled(false);
            binding.buttonUpdateAction.setVisibility(View.GONE);
            binding.textUpdateSubtitle.setText(R.string.home_update_preparing);
            showProgress(null);
            return;
        }
        if (apk != null) {
            binding.cardUpdate.setEnabled(true);
            binding.buttonUpdateAction.setVisibility(View.GONE);
            binding.textUpdateSubtitle.setText(getString(
                    R.string.home_update_downloaded, pendingVersion));
            hideProgress();
            return;
        }
        if (state != null && state.active()) {
            binding.cardUpdate.setEnabled(false);
            binding.buttonUpdateAction.setText(R.string.home_update_cancel);
            binding.buttonUpdateAction.setVisibility(View.VISIBLE);
            renderDownloading(state, pendingVersion);
            return;
        }
        if (state != null && state.failed()) {
            binding.cardUpdate.setEnabled(true);
            binding.textUpdateSubtitle.setText(R.string.home_update_download_failed);
            hideProgress();
            binding.buttonUpdateAction.setText(UpdateManager.canRetry(this, state)
                    ? R.string.home_update_retry : R.string.home_update_recheck);
            binding.buttonUpdateAction.setVisibility(View.VISIBLE);
            return;
        }
        binding.buttonUpdateAction.setVisibility(View.GONE);
        hideProgress();
        binding.cardUpdate.setEnabled(!checking);
        if (!checking) {
            binding.textUpdateSubtitle.setText(getString(
                    R.string.home_update_subtitle, BuildConfig.VERSION_NAME));
        }
    }

    private void renderDownloading(@NonNull DownloadState state, @NonNull String version) {
        showProgress(state);
        int percent = state.percent();
        if (state.status == DownloadManager.STATUS_PAUSED) {
            binding.textUpdateSubtitle.setText(percent < 0
                    ? getString(R.string.home_update_paused, version)
                    : getString(R.string.home_update_paused_progress, version, percent,
                            UpdateManager.sizeLabel(state.bytes),
                            UpdateManager.sizeLabel(state.total)));
            return;
        }
        binding.textUpdateSubtitle.setText(percent < 0
                ? getString(R.string.home_update_downloading, version)
                : getString(R.string.home_update_downloading_progress, version, percent,
                        UpdateManager.sizeLabel(state.bytes),
                        UpdateManager.sizeLabel(state.total)));
    }

    private void showProgress(@Nullable DownloadState state) {
        binding.progressUpdate.setVisibility(View.VISIBLE);
        int percent = state == null ? -1 : state.percent();
        if (percent < 0) {
            if (!binding.progressUpdate.isIndeterminate()) {
                binding.progressUpdate.setIndeterminate(true);
            }
            return;
        }
        if (binding.progressUpdate.isIndeterminate()) {
            binding.progressUpdate.setIndeterminate(false);
        }
        binding.progressUpdate.setProgressCompat(percent, true);
    }

    private void hideProgress() {
        binding.progressUpdate.setVisibility(View.GONE);
    }

    private void startProgressPolling() {
        mainHandler.removeCallbacks(progressTick);
        // 下载启动是异步的：回调回来时页面可能已经 stop，不能把轮询留在后台空转。
        if (!getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED)) {
            return;
        }
        mainHandler.post(progressTick);
    }

    private void stopProgressPolling() {
        mainHandler.removeCallbacks(progressTick);
    }

    private void checkForUpdate() {
        if (checking) {
            return;
        }
        checking = true;
        binding.cardUpdate.setEnabled(false);
        binding.buttonUpdateAction.setVisibility(View.GONE);
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
        if (release == null) {
            updateUpdateCard();
            Toast.makeText(this, getString(R.string.home_update_check_network),
                    Toast.LENGTH_LONG).show();
            return;
        }
        DiagnosticLog.info("检查更新线路：" + UpdateChecker.sourceLabel(release.source()));
        releasePageUrl = release.pageUrl();
        if (UpdateChecker.isNewer(release.versionName(), BuildConfig.VERSION_NAME)) {
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
        // 只留两个按钮：三个按钮在窄屏上会被 Material 竖排，「打开发布页」放到页面下方。
        UpdateDialogs.showFound(this, release, false, () -> startDownload(release));
    }

    private void startDownload(@NonNull UpdateChecker.Release release) {
        preparing = true;
        updateUpdateCard();
        executor.execute(() -> {
            try {
                UpdateManager.startDownload(getApplicationContext(), release);
                mainHandler.post(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    preparing = false;
                    updateUpdateCard();
                    startProgressPolling();
                    Toast.makeText(this, R.string.home_update_started,
                            Toast.LENGTH_LONG).show();
                });
            } catch (IOException | RuntimeException error) {
                mainHandler.post(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    preparing = false;
                    updateUpdateCard();
                    showStartFailure(error.getMessage());
                });
            }
        });
    }

    private void retryDownload() {
        if (preparing) {
            return;
        }
        if (!UpdateManager.canRetry(this)) {
            checkForUpdate();
            return;
        }
        preparing = true;
        updateUpdateCard();
        executor.execute(() -> {
            try {
                UpdateManager.retryDownload(getApplicationContext());
                mainHandler.post(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    preparing = false;
                    updateUpdateCard();
                    startProgressPolling();
                    Toast.makeText(this, R.string.home_update_started,
                            Toast.LENGTH_LONG).show();
                });
            } catch (IOException | RuntimeException error) {
                mainHandler.post(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    preparing = false;
                    updateUpdateCard();
                    showStartFailure(error.getMessage());
                });
            }
        });
    }

    private void cancelDownload() {
        UpdateManager.cancelDownload(this);
        stopProgressPolling();
        updateUpdateCard();
        Toast.makeText(this, R.string.home_update_cancelled, Toast.LENGTH_SHORT).show();
    }

    private void showStartFailure(@Nullable String detail) {
        String message = detail == null || detail.isEmpty() ? "—" : detail;
        Toast.makeText(this, getString(R.string.home_update_start_failed, message),
                Toast.LENGTH_LONG).show();
    }

    private void promptInstall(@NonNull String version) {
        String label = version.isEmpty()
                ? BuildConfig.VERSION_NAME : version;
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
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
            verifiedPath = "";
            updateUpdateCard();
            return;
        }
        if (!UpdateManager.canInstall(this)) {
            awaitingInstallPermission = true;
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.home_update_permission_title)
                    .setMessage(R.string.home_update_permission_message)
                    .setNegativeButton(R.string.campus_cancel, null)
                    .setPositiveButton(R.string.home_update_permission_action,
                            (dialog, which) -> UpdateManager.openInstallSettings(this))
                    .show();
            return;
        }
        if (apk.getAbsolutePath().equals(verifiedPath)) {
            launchInstaller(apk);
            return;
        }
        binding.textUpdateSubtitle.setText(R.string.home_update_verifying);
        executor.execute(() -> {
            UpdateVerifier.Issue issue = UpdateManager.verifyDownload(this, apk);
            mainHandler.post(() -> onInstallVerified(issue, apk));
        });
    }

    private void onInstallVerified(@NonNull UpdateVerifier.Issue issue, @NonNull File apk) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        if (issue == UpdateVerifier.Issue.OK) {
            verifiedPath = apk.getAbsolutePath();
            launchInstaller(apk);
            return;
        }
        verifiedPath = "";
        UpdateManager.discardDownload(this);
        updateUpdateCard();
        showVerifyFailed(issue, apk);
    }

    private void launchInstaller(@NonNull File apk) {
        updateUpdateCard();
        if (!UpdateManager.launchInstaller(this, apk)) {
            Toast.makeText(this, R.string.home_update_install_failed,
                    Toast.LENGTH_LONG).show();
        }
    }

    private void showVerifyFailed(@NonNull UpdateVerifier.Issue issue, @NonNull File apk) {
        DiagnosticLog.warn("更新包校验未通过：" + issue.name()
                + "，是否 APK 魔数=" + UpdateManager.looksLikeApk(apk));
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.home_update_verify_failed_title)
                .setMessage(getString(R.string.home_update_verify_failed_message,
                        verifyIssueText(issue)))
                .setPositiveButton(R.string.home_update_verify_dismiss, null)
                .show();
    }

    @NonNull
    private String verifyIssueText(@NonNull UpdateVerifier.Issue issue) {
        switch (issue) {
            case PACKAGE_MISMATCH:
                return getString(R.string.home_update_verify_package);
            case SIGNATURE_MISMATCH:
                return getString(R.string.home_update_verify_signature);
            case CHECKSUM_MISMATCH:
                return getString(R.string.home_update_verify_checksum);
            case UNREADABLE:
            default:
                return getString(R.string.home_update_verify_unreadable);
        }
    }

}
