package org.localroute.app.ui;

import android.content.Intent;
import android.os.Bundle;
import android.widget.SeekBar;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import org.localroute.app.R;
import org.localroute.app.databinding.ActivityStudySettingsBinding;
import org.localroute.app.study.StudySession;
import org.localroute.app.study.StudySessionStore;
import org.localroute.app.study.StudyTimetableStore;
import org.localroute.app.study.TimetableCourse;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 作业查询的独立设置页：学习通账户、课表导入与每节课时长。 */
public class StudySettingsActivity extends AppCompatActivity {

    private ActivityStudySettingsBinding binding;
    private StudySession session;

    private final ActivityResultLauncher<Intent> loginLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() == RESULT_OK) {
                            renderAccount();
                            setResult(RESULT_OK);
                        }
                    });

    private final ActivityResultLauncher<String[]> pdfLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) {
                    StudyDialogs.showImportTimetable(this, uri, this::onTimetableChanged);
                }
            });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityStudySettingsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        binding.buttonSettingsBack.setOnClickListener(view -> finish());
        binding.buttonSwitchAccount.setOnClickListener(view -> onSwitchAccount());
        binding.rowReimport.setOnClickListener(view -> {
            try {
                pdfLauncher.launch(new String[]{"application/pdf"});
            } catch (RuntimeException error) {
                Toast.makeText(this, R.string.study_import_pdf_no_picker,
                        Toast.LENGTH_LONG).show();
            }
        });
        binding.rowAddCourse.setOnClickListener(view ->
                StudyDialogs.showCourseEditor(this, null, course -> onTimetableChanged()));
        binding.rowClearTimetable.setOnClickListener(view -> confirmClearTimetable());

        int minutes = StudyTimetableStore.loadPeriodMinutes(this);
        binding.seekPeriod.setProgress(minutes - StudyTimetableStore.MIN_PERIOD_MINUTES);
        updatePeriodLabel(minutes);
        binding.seekPeriod.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updatePeriodLabel(StudyTimetableStore.MIN_PERIOD_MINUTES + progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                int value = StudyTimetableStore.MIN_PERIOD_MINUTES + seekBar.getProgress();
                StudyTimetableStore.savePeriodMinutes(StudySettingsActivity.this, value);
                setResult(RESULT_OK);
                Toast.makeText(StudySettingsActivity.this,
                        getString(R.string.study_period_saved, value),
                        Toast.LENGTH_SHORT).show();
            }
        });
        binding.buttonResetPeriod.setOnClickListener(view -> {
            int value = StudyTimetableStore.DEFAULT_PERIOD_MINUTES;
            binding.seekPeriod.setProgress(value - StudyTimetableStore.MIN_PERIOD_MINUTES);
            StudyTimetableStore.savePeriodMinutes(this, value);
            setResult(RESULT_OK);
            Toast.makeText(this, getString(R.string.study_period_saved, value),
                    Toast.LENGTH_SHORT).show();
        });

        renderAccount();
        renderTimetableCount();
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderAccount();
        renderTimetableCount();
    }

    private void onSwitchAccount() {
        session = StudySessionStore.load(this);
        if (session == null) {
            loginLauncher.launch(new Intent(this, StudyLoginActivity.class));
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.study_logout_confirm_title)
                .setMessage(R.string.study_logout_confirm_message)
                .setNegativeButton(R.string.campus_cancel, null)
                .setPositiveButton(R.string.study_account_switch, (dialog, which) -> {
                    Intent intent = new Intent(this, StudyLoginActivity.class);
                    intent.putExtra(StudyLoginActivity.EXTRA_SWITCH, true);
                    loginLauncher.launch(intent);
                })
                .show();
    }

    private void renderAccount() {
        session = StudySessionStore.load(this);
        if (session == null) {
            binding.textAccountName.setText(R.string.study_account_unbound);
            binding.textAccountMeta.setText(R.string.study_account_unbound_hint);
            binding.buttonSwitchAccount.setText(R.string.study_account_bind);
            return;
        }
        binding.textAccountName.setText(session.displayName());
        String boundAt = session.boundAt() > 0
                ? new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
                .format(new Date(session.boundAt()))
                : "—";
        binding.textAccountMeta.setText(getString(R.string.study_settings_account_meta,
                session.uid(), boundAt));
        binding.buttonSwitchAccount.setText(R.string.study_account_switch);
    }

    private void renderTimetableCount() {
        binding.textTimetableCount.setText(getString(R.string.study_settings_timetable_count,
                StudyTimetableStore.loadCourses(this).size()));
    }

    private void onTimetableChanged() {
        renderTimetableCount();
        setResult(RESULT_OK);
    }

    private void confirmClearTimetable() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.study_timetable_clear_confirm_title)
                .setMessage(R.string.study_timetable_clear_confirm_message)
                .setNegativeButton(R.string.campus_cancel, null)
                .setPositiveButton(R.string.study_timetable_clear, (dialog, which) -> {
                    StudyTimetableStore.clearCourses(this);
                    onTimetableChanged();
                    Toast.makeText(this, R.string.study_timetable_cleared,
                            Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private void updatePeriodLabel(int minutes) {
        binding.textPeriodValue.setText(getString(R.string.study_period_value, minutes));
        binding.textPeriodNote.setText(getString(R.string.study_period_note,
                TimetableCourse.MAX_PERIOD,
                StudyTimetableStore.periodEndLabel(TimetableCourse.MAX_PERIOD, minutes)));
    }
}
