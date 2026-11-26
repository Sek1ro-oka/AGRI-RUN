package org.localroute.app.ui;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import org.localroute.app.R;
import org.localroute.app.databinding.ActivityOnboardingBinding;
import org.localroute.app.databinding.ItemOnboardingRowBinding;
import org.localroute.app.overlay.OverlayPanel;
import org.localroute.app.run.SimulationStateRepo;
import org.localroute.app.settings.OnboardingState;
import org.localroute.app.settings.SettingsInspector;
import org.localroute.app.settings.SetupItem;
import org.localroute.app.study.StudySessionStore;
import org.localroute.app.study.StudyTimetableStore;

import com.google.android.material.card.MaterialCardView;

import java.util.List;
import java.util.ArrayList;

/** 首次安装的可跳过引导，授权与登录只在用户点击后发起。 */
public final class OnboardingActivity extends AppCompatActivity {
    private static final String LOCATION_ASKED = "location_asked";
    private static final String NOTIFICATION_ASKED = "notification_asked";
    private ActivityOnboardingBinding binding;

    private final ActivityResultLauncher<String[]> permissions = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(), result -> render());
    private final ActivityResultLauncher<String> notifications = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), result -> render());
    private final ActivityResultLauncher<Intent> studyLogin = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> render());
    private final ActivityResultLauncher<Intent> jwImport = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == Activity.RESULT_OK) OnboardingState.markJwImported(this);
                render();
            });

    @Override protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        binding = ActivityOnboardingBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        ViewCompat.setOnApplyWindowInsetsListener(binding.getRoot(), (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        binding.buttonOnboardingNext.setOnClickListener(v -> advance());
        binding.buttonOnboardingSkip.setOnClickListener(v -> advance());
        constrainContentWidth();
        FinmoriMotion.press(binding.buttonOnboardingNext);
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { moveTaskToBack(true); }
        });
    }

    private void constrainContentWidth() {
        int max = getResources().getDimensionPixelSize(R.dimen.content_max_width);
        binding.getRoot().addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            int available = right - left - view.getPaddingLeft() - view.getPaddingRight();
            if (available <= 0) return;
            int width = Math.min(max, available);
            FrameLayout.LayoutParams content = (FrameLayout.LayoutParams) binding.onboardingContent.getLayoutParams();
            if (content.width != width || content.gravity != Gravity.CENTER_HORIZONTAL) {
                content.width = width;
                content.gravity = Gravity.CENTER_HORIZONTAL;
                binding.onboardingContent.setLayoutParams(content);
            }
            LinearLayout.LayoutParams footer = (LinearLayout.LayoutParams) binding.onboardingFooter.getLayoutParams();
            if (footer.width != width || footer.gravity != Gravity.CENTER_HORIZONTAL) {
                footer.width = width;
                footer.gravity = Gravity.CENTER_HORIZONTAL;
                binding.onboardingFooter.setLayoutParams(footer);
            }
        });
    }

    @Override protected void onResume() {
        super.onResume();
        render();
    }

    private void advance() {
        int step = OnboardingState.step(this);
        if (step == OnboardingState.FINISH) {
            OnboardingState.complete(this);
            setResult(RESULT_OK);
            finish();
            return;
        }
        OnboardingState.setStep(this, step + 1);
        render();
    }

    private void render() {
        if (binding == null || isFinishing()) return;
        int step = OnboardingState.step(this);
        binding.textOnboardingStep.setText(getString(R.string.onboarding_step, step + 1));
        binding.textOnboardingTitle.setText(step == OnboardingState.PERMISSIONS
                ? R.string.onboarding_permissions_title : step == OnboardingState.ACCOUNTS
                ? R.string.onboarding_accounts_title : R.string.onboarding_finish_title);
        binding.textOnboardingSubtitle.setText(step == OnboardingState.PERMISSIONS
                ? R.string.onboarding_permissions_subtitle : step == OnboardingState.ACCOUNTS
                ? R.string.onboarding_accounts_subtitle : R.string.onboarding_finish_subtitle);
        binding.buttonOnboardingSkip.setVisibility(step == OnboardingState.FINISH ? View.GONE : View.VISIBLE);
        binding.buttonOnboardingNext.setText(step == OnboardingState.FINISH
                ? R.string.onboarding_start : R.string.onboarding_next);
        binding.onboardingRows.removeAllViews();
        if (step == OnboardingState.PERMISSIONS) renderPermissions();
        else if (step == OnboardingState.ACCOUNTS) renderAccounts();
        else renderSummary();
    }

    private void renderPermissions() {
        MaterialCardView card = new MaterialCardView(this);
        card.setCardBackgroundColor(ContextCompat.getColor(this, R.color.fm_surface));
        card.setRadius(getResources().getDimension(R.dimen.radius_sheet));
        card.setCardElevation(0);
        card.setStrokeColor(ContextCompat.getColor(this, R.color.fm_border));
        card.setStrokeWidth(getResources().getDimensionPixelSize(R.dimen.stroke_edge));
        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);
        int inset = getResources().getDimensionPixelSize(R.dimen.space_3xl);
        group.setPadding(inset, inset, inset, inset);
        card.addView(group);
        binding.onboardingRows.addView(card);
        List<SettingsInspector.Check> checks = SettingsInspector.inspect(this);
        for (int i = 0; i < checks.size(); i++) {
            SettingsInspector.Check check = checks.get(i);
            SetupItem item = check.item();
            boolean ready = check.status() == SettingsInspector.Status.OK;
            if (item == SetupItem.NOTIFICATIONS) ready = notificationsReady();
            int status = item == SetupItem.MOCK_APP
                    ? (ready ? R.string.onboarding_recent_check : R.string.onboarding_unconfirmed)
                    : (ready ? R.string.onboarding_ready : R.string.onboarding_pending);
            String detail = item == SetupItem.NOTIFICATIONS && !ready && check.status() == SettingsInspector.Status.OK
                    ? getString(R.string.onboarding_notifications_disabled) : check.detail();
            ItemOnboardingRowBinding row = row(group, check.title(), getString(status), detail);
            row.getRoot().setCardBackgroundColor(Color.TRANSPARENT);
            int vertical = getResources().getDimensionPixelSize(R.dimen.space_md);
            row.getRoot().getChildAt(0).setPadding(0, vertical, 0, vertical);
            ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) row.getRoot().getLayoutParams();
            margins.bottomMargin = 0;
            row.getRoot().setLayoutParams(margins);
            int stateColor = ready ? R.color.fm_success
                    : check.status() == SettingsInspector.Status.FAIL ? R.color.fm_error : R.color.fm_warning;
            int stateBackground = ready ? R.color.campus_mint_pale
                    : check.status() == SettingsInspector.Status.FAIL ? R.color.study_state_expired_fill : R.color.study_state_pending_fill;
            row.textOnboardingRowState.setTextColor(ContextCompat.getColor(this, stateColor));
            GradientDrawable chip = new GradientDrawable();
            chip.setColor(ContextCompat.getColor(this, stateBackground));
            chip.setCornerRadius(getResources().getDimension(R.dimen.radius_control));
            row.textOnboardingRowState.setBackground(chip);
            int chipH = getResources().getDimensionPixelSize(R.dimen.space_md);
            int chipV = getResources().getDimensionPixelSize(R.dimen.space_xs);
            row.textOnboardingRowState.setPadding(chipH, chipV, chipH, chipV);
            row.buttonOnboardingRow.setStrokeColor(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.fm_link)));
            row.buttonOnboardingRow.setText(ready ? R.string.onboarding_manage
                    : item == SetupItem.LOCATION_PERMISSION || item == SetupItem.NOTIFICATIONS
                    ? R.string.onboarding_authorize : R.string.onboarding_configure);
            row.buttonOnboardingRow.setContentDescription(check.title() + " · " + row.buttonOnboardingRow.getText());
            row.buttonOnboardingRow.setOnClickListener(v -> openPermission(item));
            if (item == SetupItem.MOCK_APP) {
                row.buttonOnboardingRowSecondary.setVisibility(View.VISIBLE);
                row.buttonOnboardingRowSecondary.setText(R.string.onboarding_check);
                row.buttonOnboardingRowSecondary.setContentDescription(check.title() + " · " + getString(R.string.onboarding_check));
                row.buttonOnboardingRowSecondary.setOnClickListener(v -> checkMock());
            }
            if (i < checks.size() - 1) {
                View divider = new View(this);
                divider.setBackgroundColor(ContextCompat.getColor(this, R.color.fm_border));
                group.addView(divider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        getResources().getDimensionPixelSize(R.dimen.stroke_edge)));
            }
        }
    }

    private void renderAccounts() {
        boolean bound = StudySessionStore.load(this) != null;
        ItemOnboardingRowBinding study = row(getString(R.string.onboarding_study_title),
                getString(bound ? R.string.onboarding_study_bound : R.string.onboarding_study_unbound),
                getString(R.string.onboarding_study_detail));
        study.buttonOnboardingRow.setText(bound ? R.string.onboarding_manage : R.string.onboarding_bind);
        study.buttonOnboardingRow.setOnClickListener(v -> {
            if (bound) startActivity(new Intent(this, ProfileActivity.class));
            else studyLogin.launch(new Intent(this, StudyLoginActivity.class));
        });
        boolean imported = jwImported();
        ItemOnboardingRowBinding jw = row(getString(R.string.onboarding_jw_title),
                getString(imported ? R.string.onboarding_jw_imported : R.string.onboarding_jw_unimported),
                getString(R.string.onboarding_jw_detail));
        jw.buttonOnboardingRow.setText(imported ? R.string.onboarding_import_again : R.string.onboarding_import);
        jw.buttonOnboardingRow.setOnClickListener(v -> jwImport.launch(new Intent(this, JwImportActivity.class)));
    }

    private void renderSummary() {
        List<SettingsInspector.Check> checks = SettingsInspector.inspect(this);
        int ready = 0;
        List<String> readyItems = new ArrayList<>();
        for (SettingsInspector.Check check : checks) {
            if (check.item() == SetupItem.NOTIFICATIONS ? notificationsReady()
                    : check.status() == SettingsInspector.Status.OK) {
                ready++;
                readyItems.add(check.title());
            }
        }
        String permissionSummary = getString(R.string.onboarding_summary_permissions_value, ready, checks.size());
        if (!readyItems.isEmpty()) permissionSummary += "\n" + String.join("、", readyItems);
        ItemOnboardingRowBinding permission = row(getString(R.string.onboarding_summary_permissions), "",
                permissionSummary);
        permission.onboardingRowActions.setVisibility(View.GONE);
        ItemOnboardingRowBinding accounts = row(getString(R.string.onboarding_summary_accounts), "",
                getString(R.string.onboarding_summary_accounts_value,
                        getString(StudySessionStore.load(this) == null ? R.string.onboarding_study_unbound : R.string.onboarding_study_bound),
                        getString(jwImported() ? R.string.onboarding_jw_imported : R.string.onboarding_jw_unimported)));
        accounts.onboardingRowActions.setVisibility(View.GONE);
    }

    private ItemOnboardingRowBinding row(String title, String state, String detail) {
        return row(binding.onboardingRows, title, state, detail);
    }

    private ItemOnboardingRowBinding row(LinearLayout parent, String title, String state, String detail) {
        ItemOnboardingRowBinding row = ItemOnboardingRowBinding.inflate(
                LayoutInflater.from(this), parent, false);
        row.textOnboardingRowTitle.setText(title);
        row.textOnboardingRowState.setText(state);
        row.textOnboardingRowDetail.setText(detail);
        parent.addView(row.getRoot());
        return row;
    }

    private boolean jwImported() {
        return OnboardingState.jwImported(this) && !StudyTimetableStore.loadCourses(this).isEmpty();
    }

    private boolean notificationsReady() {
        return NotificationManagerCompat.from(this).areNotificationsEnabled()
                && (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(this,
                Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED);
    }

    private void openPermission(SetupItem item) {
        switch (item) {
            case LOCATION_PERMISSION:
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED || (getPreferences(MODE_PRIVATE).getBoolean(LOCATION_ASKED, false)
                        && !ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.ACCESS_FINE_LOCATION))) {
                    openAppDetails();
                } else {
                    getPreferences(MODE_PRIVATE).edit().putBoolean(LOCATION_ASKED, true).apply();
                    permissions.launch(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION});
                }
                break;
            case LOCATION_SWITCH: openSettings(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)); break;
            case MOCK_APP: openSettings(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)); break;
            case OVERLAY: OverlayPanel.requestPermission(this); break;
            case NOTIFICATIONS:
                if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this,
                        Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                        && !(getPreferences(MODE_PRIVATE).getBoolean(NOTIFICATION_ASKED, false)
                        && !ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.POST_NOTIFICATIONS))) {
                    getPreferences(MODE_PRIVATE).edit().putBoolean(NOTIFICATION_ASKED, true).apply();
                    notifications.launch(Manifest.permission.POST_NOTIFICATIONS);
                } else {
                    openSettings(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
                }
                break;
            default: break;
        }
    }

    private void checkMock() {
        if (SimulationStateRepo.get().current().isActive()) {
            Toast.makeText(this, R.string.onboarding_check_running, Toast.LENGTH_SHORT).show();
            return;
        }
        SettingsInspector.MockProbeResult result = SettingsInspector.probeMockProvider(this);
        Toast.makeText(this, result == SettingsInspector.MockProbeResult.ALLOWED
                || result == SettingsInspector.MockProbeResult.ALLOWED_AFTER_CLEANUP
                ? R.string.onboarding_check_ok : R.string.onboarding_check_failed, Toast.LENGTH_LONG).show();
        render();
    }

    private void openAppDetails() {
        openSettings(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", getPackageName(), null)));
    }

    private void openSettings(Intent intent) {
        try { startActivity(intent); }
        catch (ActivityNotFoundException error) {
            try { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
            catch (ActivityNotFoundException ignored) { }
        }
    }
}
