package org.localroute.app.ui;

import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import org.localroute.app.R;
import org.localroute.app.databinding.ActivityProfileBinding;
import org.localroute.app.study.StudySession;
import org.localroute.app.study.StudySessionStore;
import org.localroute.app.study.StudyTimetableStore;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 我的：学习通绑定与教务课表导入。设置是独立 tab，不在这里。 */
public class ProfileActivity extends AppCompatActivity {

    private ActivityProfileBinding binding;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        binding = ActivityProfileBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        ViewCompat.setOnApplyWindowInsetsListener(binding.getRoot(), (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        binding.buttonProfileAccount.setOnClickListener(v -> openAccount());
        binding.rowProfileJwImport.setOnClickListener(v ->
                startActivity(new Intent(this, JwImportActivity.class)));
        FinmoriMotion.press(binding.buttonProfileAccount);
        FinmoriMotion.press(binding.rowProfileJwImport);
        FinmoriNav.bind(this, binding.bottomNav, FinmoriNav.Tab.PROFILE);
    }

    @Override protected void onResume() {
        super.onResume();
        renderAccount();
        renderTimetable();
    }

    private void renderTimetable() {
        int count = StudyTimetableStore.loadCourses(this).size();
        binding.textProfileJwMeta.setText(count > 0
                ? getString(R.string.profile_jw_import_count, count)
                : getString(R.string.profile_jw_import_none));
    }

    private void renderAccount() {
        StudySession session = StudySessionStore.load(this);
        if (session == null) {
            binding.textProfileName.setText(R.string.study_account_unbound);
            binding.textProfileMeta.setText(R.string.study_account_unbound_hint);
            binding.buttonProfileAccount.setText(R.string.study_account_bind);
            return;
        }
        binding.textProfileName.setText(session.displayName());
        String boundAt = session.boundAt() > 0
                ? new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
                .format(new Date(session.boundAt()))
                : "—";
        binding.textProfileMeta.setText(getString(R.string.study_settings_account_meta,
                session.uid(), boundAt));
        binding.buttonProfileAccount.setText(R.string.profile_manage_action);
    }

    /** 解绑与切换沿用学习通设置页那条既有路径，这里不另开一条会清数据的入口。 */
    private void openAccount() {
        Class<?> target = StudySessionStore.load(this) == null
                ? StudyLoginActivity.class : StudySettingsActivity.class;
        startActivity(new Intent(this, target));
    }
}