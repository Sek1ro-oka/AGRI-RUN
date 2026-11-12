package org.localroute.app.ui;

import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.View;
import android.widget.SeekBar;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.timepicker.MaterialTimePicker;
import com.google.android.material.timepicker.TimeFormat;

import org.localroute.app.R;
import org.localroute.app.databinding.ActivityStudySettingsBinding;
import org.localroute.app.study.StudySchedule;
import org.localroute.app.study.StudySession;
import org.localroute.app.study.StudySessionStore;
import org.localroute.app.study.StudyTimetableStore;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 作业查询的独立设置页：学习通账户、课表导入与冬夏令时作息。 */
public class StudySettingsActivity extends AppCompatActivity {

    private ActivityStudySettingsBinding binding;
    private StudySession session;
    private int season = StudySchedule.SEASON_WINTER;
    private StudySchedule schedule = StudySchedule.winterDefault();

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

        binding.chipSeasonWinter.setOnClickListener(view ->
                selectSeason(StudySchedule.SEASON_WINTER));
        binding.chipSeasonSummer.setOnClickListener(view ->
                selectSeason(StudySchedule.SEASON_SUMMER));
        binding.rowMorningStart.setOnClickListener(view ->
                pickStartTime(StudySchedule.SEGMENT_MORNING));
        binding.rowAfternoonStart.setOnClickListener(view ->
                pickStartTime(StudySchedule.SEGMENT_AFTERNOON));
        binding.rowEveningStart.setOnClickListener(view ->
                pickStartTime(StudySchedule.SEGMENT_EVENING));
        binding.buttonMorningMinus.setOnClickListener(view ->
                changePeriodCount(StudySchedule.SEGMENT_MORNING, -1));
        binding.buttonMorningPlus.setOnClickListener(view ->
                changePeriodCount(StudySchedule.SEGMENT_MORNING, 1));
        binding.buttonAfternoonMinus.setOnClickListener(view ->
                changePeriodCount(StudySchedule.SEGMENT_AFTERNOON, -1));
        binding.buttonAfternoonPlus.setOnClickListener(view ->
                changePeriodCount(StudySchedule.SEGMENT_AFTERNOON, 1));
        binding.buttonEveningMinus.setOnClickListener(view ->
                changePeriodCount(StudySchedule.SEGMENT_EVENING, -1));
        binding.buttonEveningPlus.setOnClickListener(view ->
                changePeriodCount(StudySchedule.SEGMENT_EVENING, 1));

        binding.seekPeriod.setMax(StudySchedule.MAX_PERIOD_MINUTES
                - StudySchedule.MIN_PERIOD_MINUTES);
        binding.seekPeriod.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                binding.textPeriodValue.setText(getString(R.string.study_schedule_minutes,
                        StudySchedule.MIN_PERIOD_MINUTES + progress));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                applySchedule(schedule.withPeriodMinutes(
                        StudySchedule.MIN_PERIOD_MINUTES + seekBar.getProgress()));
            }
        });
        binding.seekBreak.setMax(StudySchedule.MAX_BREAK_MINUTES);
        binding.seekBreak.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                binding.textBreakValue.setText(getString(R.string.study_schedule_minutes,
                        progress));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                applySchedule(schedule.withBreakMinutes(seekBar.getProgress()));
            }
        });
        binding.seekLongBreak.setMax(StudySchedule.MAX_BREAK_MINUTES);
        binding.seekLongBreak.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                binding.textLongBreakValue.setText(getString(R.string.study_schedule_minutes,
                        progress));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                applySchedule(schedule.withLongBreakMinutes(seekBar.getProgress()));
            }
        });
        binding.buttonResetPeriod.setOnClickListener(view -> {
            applySchedule(StudySchedule.defaultsForSeason(season));
            Toast.makeText(this, R.string.study_schedule_reset_done, Toast.LENGTH_SHORT).show();
        });

        renderAccount();
        renderTimetableCount();
        renderSchedule();
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderAccount();
        renderTimetableCount();
        renderSchedule();
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

    // ------------------------------------------------------------------ 作息

    private void renderSchedule() {
        season = StudyTimetableStore.loadSeason(this);
        schedule = StudyTimetableStore.loadSchedule(this);
        fillScheduleValues();
        binding.textScheduleWarning.setVisibility(View.GONE);
    }

    private void fillScheduleValues() {
        binding.textMorningValue.setText(
                schedule.startLabel(schedule.firstPeriod(StudySchedule.SEGMENT_MORNING)));
        binding.textAfternoonValue.setText(
                schedule.startLabel(schedule.firstPeriod(StudySchedule.SEGMENT_AFTERNOON)));
        binding.textEveningValue.setText(
                schedule.startLabel(schedule.firstPeriod(StudySchedule.SEGMENT_EVENING)));

        int morningCount = schedule.periodCount(StudySchedule.SEGMENT_MORNING);
        int afternoonCount = schedule.periodCount(StudySchedule.SEGMENT_AFTERNOON);
        int eveningCount = schedule.periodCount(StudySchedule.SEGMENT_EVENING);
        binding.textMorningCount.setText(
                getString(R.string.study_schedule_count_value, morningCount));
        binding.textAfternoonCount.setText(
                getString(R.string.study_schedule_count_value, afternoonCount));
        binding.textEveningCount.setText(
                getString(R.string.study_schedule_count_value, eveningCount));
        binding.textTotalPeriods.setText(
                getString(R.string.study_schedule_total, schedule.totalPeriods()));
        boolean canAdd = schedule.totalPeriods() < StudySchedule.MAX_TOTAL_PERIODS;
        styleCountButton(binding.buttonMorningMinus,
                morningCount > StudySchedule.MIN_PERIODS_PER_SEGMENT);
        styleCountButton(binding.buttonMorningPlus,
                canAdd && morningCount < StudySchedule.MAX_PERIODS_PER_SEGMENT);
        styleCountButton(binding.buttonAfternoonMinus,
                afternoonCount > StudySchedule.MIN_PERIODS_PER_SEGMENT);
        styleCountButton(binding.buttonAfternoonPlus,
                canAdd && afternoonCount < StudySchedule.MAX_PERIODS_PER_SEGMENT);
        styleCountButton(binding.buttonEveningMinus,
                eveningCount > StudySchedule.MIN_PERIODS_PER_SEGMENT);
        styleCountButton(binding.buttonEveningPlus,
                canAdd && eveningCount < StudySchedule.MAX_PERIODS_PER_SEGMENT);

        binding.seekPeriod.setProgress(schedule.periodMinutes()
                - StudySchedule.MIN_PERIOD_MINUTES);
        binding.textPeriodValue.setText(getString(R.string.study_schedule_minutes,
                schedule.periodMinutes()));
        binding.seekBreak.setProgress(schedule.breakMinutes());
        binding.textBreakValue.setText(getString(R.string.study_schedule_minutes,
                schedule.breakMinutes()));
        binding.seekLongBreak.setProgress(schedule.longBreakMinutes());
        binding.textLongBreakValue.setText(getString(R.string.study_schedule_minutes,
                schedule.longBreakMinutes()));

        binding.textScheduleSummary.setText(getString(R.string.study_schedule_summary,
                schedule.startLabel(schedule.firstPeriod(StudySchedule.SEGMENT_MORNING)),
                schedule.endLabel(schedule.lastPeriod(StudySchedule.SEGMENT_MORNING)),
                schedule.startLabel(schedule.firstPeriod(StudySchedule.SEGMENT_AFTERNOON)),
                schedule.endLabel(schedule.lastPeriod(StudySchedule.SEGMENT_AFTERNOON)),
                schedule.startLabel(schedule.firstPeriod(StudySchedule.SEGMENT_EVENING)),
                schedule.endLabel(schedule.lastPeriod(StudySchedule.SEGMENT_EVENING)),
                schedule.breakMinutes(),
                schedule.longBreakMinutes()));

        boolean winter = season == StudySchedule.SEASON_WINTER;
        styleSeasonChip(binding.chipSeasonWinter, winter);
        styleSeasonChip(binding.chipSeasonSummer, !winter);
    }

    private void styleCountButton(@NonNull android.widget.TextView button, boolean enabled) {
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1f : 0.3f);
    }

    private void changePeriodCount(int segment, int delta) {
        int count = schedule.periodCount(segment) + delta;
        if (count < StudySchedule.MIN_PERIODS_PER_SEGMENT
                || count > StudySchedule.MAX_PERIODS_PER_SEGMENT) {
            return;
        }
        applySchedule(schedule.withPeriodCount(segment, count));
    }

    private void styleSeasonChip(@NonNull android.widget.TextView chip, boolean selected) {
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(20));
        background.setColor(ContextCompat.getColor(this, selected
                ? R.color.campus_mint : R.color.campus_mint_pale));
        chip.setBackground(background);
        chip.setTextColor(ContextCompat.getColor(this, selected
                ? R.color.brand_on_primary : R.color.campus_mint_dark));
        chip.setSelected(selected);
    }

    private void selectSeason(int target) {
        if (season == target) {
            return;
        }
        season = target;
        StudyTimetableStore.saveSeason(this, target);
        schedule = StudyTimetableStore.loadSchedule(this, target);
        fillScheduleValues();
        binding.textScheduleWarning.setVisibility(View.GONE);
        setResult(RESULT_OK);
    }

    private void pickStartTime(int segment) {
        int titleRes;
        switch (segment) {
            case StudySchedule.SEGMENT_AFTERNOON:
                titleRes = R.string.study_schedule_pick_afternoon;
                break;
            case StudySchedule.SEGMENT_EVENING:
                titleRes = R.string.study_schedule_pick_evening;
                break;
            default:
                titleRes = R.string.study_schedule_pick_morning;
                break;
        }
        int current = schedule.segmentStartMinutes(segment);
        MaterialTimePicker picker = new MaterialTimePicker.Builder()
                .setTimeFormat(TimeFormat.CLOCK_24H)
                .setHour(current / 60)
                .setMinute(current % 60)
                .setTitleText(titleRes)
                .build();
        picker.addOnPositiveButtonClickListener(view -> {
            int minutes = StudySchedule.snapToFiveMinutes(
                    picker.getHour() * 60 + picker.getMinute());
            applySchedule(schedule.withSegmentStart(segment, minutes));
        });
        picker.show(getSupportFragmentManager(), "study-schedule-" + segment);
    }

    /** 保存一套作息；不合理的组合回退到当前值，并在卡片内说明原因。 */
    private void applySchedule(@NonNull StudySchedule candidate) {
        String error = scheduleError(candidate);
        if (error != null) {
            fillScheduleValues();
            binding.textScheduleWarning.setText(error);
            binding.textScheduleWarning.setVisibility(View.VISIBLE);
            return;
        }
        StudyTimetableStore.saveSchedule(this, season, candidate);
        schedule = candidate;
        fillScheduleValues();
        binding.textScheduleWarning.setVisibility(View.GONE);
        setResult(RESULT_OK);
    }

    @Nullable
    private String scheduleError(@NonNull StudySchedule candidate) {
        if (candidate.totalPeriods() > StudySchedule.MAX_TOTAL_PERIODS) {
            return getString(R.string.study_schedule_error_total,
                    StudySchedule.MAX_TOTAL_PERIODS);
        }
        if (candidate.morningStart() >= candidate.afternoonStart()
                || candidate.afternoonStart() >= candidate.eveningStart()
                || candidate.eveningStart() >= 24 * 60) {
            return getString(R.string.study_schedule_error_order);
        }
        if (candidate.segmentEndMinutes(StudySchedule.SEGMENT_MORNING)
                > candidate.afternoonStart()) {
            return getString(R.string.study_schedule_error_morning,
                    candidate.periodCount(StudySchedule.SEGMENT_MORNING),
                    candidate.endLabel(candidate.lastPeriod(StudySchedule.SEGMENT_MORNING)),
                    candidate.startLabel(candidate.firstPeriod(StudySchedule.SEGMENT_AFTERNOON)));
        }
        if (candidate.segmentEndMinutes(StudySchedule.SEGMENT_AFTERNOON)
                > candidate.eveningStart()) {
            return getString(R.string.study_schedule_error_afternoon,
                    candidate.periodCount(StudySchedule.SEGMENT_AFTERNOON),
                    candidate.endLabel(candidate.lastPeriod(StudySchedule.SEGMENT_AFTERNOON)),
                    candidate.startLabel(candidate.firstPeriod(StudySchedule.SEGMENT_EVENING)));
        }
        if (candidate.segmentEndMinutes(StudySchedule.SEGMENT_EVENING) > 24 * 60) {
            return getString(R.string.study_schedule_error_evening,
                    candidate.periodCount(StudySchedule.SEGMENT_EVENING),
                    candidate.endLabel(candidate.lastPeriod(StudySchedule.SEGMENT_EVENING)));
        }
        return candidate.isValid() ? null : getString(R.string.study_schedule_invalid);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
