package org.localroute.app.ui;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.view.MenuItem;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import org.localroute.app.R;
import org.localroute.app.campus.PresetUnlock;
import org.localroute.app.databinding.ActivityAppSettingsBinding;

/**
 * 应用设置：语言（本期只展示入口）与激活码。
 *
 * <p>激活码只保存在本机，用于解锁校园快跑里的 A操 / B操 预设路线；
 * 校验逻辑在 {@link PresetUnlock}，本页不记录用户输入。
 */
public class AppSettingsActivity extends AppCompatActivity {

    private ActivityAppSettingsBinding binding;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        binding = ActivityAppSettingsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        ViewCompat.setOnApplyWindowInsetsListener(binding.getRoot(), (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        setSupportActionBar(binding.toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        prefs = getSharedPreferences(PresetUnlock.PREFS_NAME, MODE_PRIVATE);
        binding.rowActivation.setOnClickListener(v -> showActivationDialog());
        renderActivationStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderActivationStatus();
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void renderActivationStatus() {
        boolean unlocked = prefs.getBoolean(PresetUnlock.KEY_UNLOCKED, false);
        binding.textActivationStatus.setText(unlocked
                ? R.string.app_settings_activation_done
                : R.string.app_settings_activation_hint);
    }

    private void showActivationDialog() {
        if (prefs.getBoolean(PresetUnlock.KEY_UNLOCKED, false)) {
            Toast.makeText(this, R.string.app_settings_activation_already,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        input.setSingleLine(true);
        input.setHint(R.string.app_settings_activation_dialog_hint);
        int padding = dp(20);
        FrameLayout container = new FrameLayout(this);
        container.setPadding(padding, dp(8), padding, 0);
        container.addView(input);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.app_settings_activation_dialog_title)
                .setView(container)
                .setPositiveButton(R.string.app_settings_activation_confirm, null)
                .setNegativeButton(R.string.app_settings_activation_cancel, null)
                .create();
        dialog.setOnShowListener(ignored ->
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                    if (!PresetUnlock.matches(input.getText().toString())) {
                        input.setError(getString(R.string.app_settings_activation_wrong));
                        input.requestFocus();
                        return;
                    }
                    prefs.edit().putBoolean(PresetUnlock.KEY_UNLOCKED, true).apply();
                    renderActivationStatus();
                    dialog.dismiss();
                    Toast.makeText(AppSettingsActivity.this,
                            R.string.app_settings_activation_success,
                            Toast.LENGTH_LONG).show();
                }));
        dialog.show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
