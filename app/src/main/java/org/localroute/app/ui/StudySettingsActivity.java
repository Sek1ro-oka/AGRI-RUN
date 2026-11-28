package org.localroute.app.ui;

import android.app.DatePickerDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.SeekBar;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.timepicker.MaterialTimePicker;
import com.google.android.material.timepicker.TimeFormat;

import org.localroute.app.R;
import org.localroute.app.databinding.ActivityStudySettingsBinding;
import org.localroute.app.study.ClassReminderPlanner;
import org.localroute.app.study.ClassReminderScheduler;
import org.localroute.app.study.CustomHomework;
import org.localroute.app.study.CustomHomeworkStore;
import org.localroute.app.study.DayAdjustment;
import org.localroute.app.study.DayAdjustmentLabel;
import org.localroute.app.study.HomeworkReminderPlanner;
import org.localroute.app.study.HomeworkReminderScheduler;
import org.localroute.app.study.IcsTimetableWriter;
import org.localroute.app.study.IcsTransfer;
import org.localroute.app.study.SemesterArchive;
import org.localroute.app.study.SemesterProfile;
import org.localroute.app.study.SemesterStore;
import org.localroute.app.study.StudySchedule;
import org.localroute.app.study.StudySession;
import org.localroute.app.study.StudySessionStore;
import org.localroute.app.study.StudyTimetableStore;
import org.localroute.app.study.TimetableCourse;
import org.localroute.app.study.TimetableDay;
import org.localroute.app.study.TimetableImportPlan;
import org.localroute.app.study.TimetableOfflineImport;
import org.localroute.app.study.TimetableShareCodec;
import org.localroute.app.widget.TimetableWidgetProvider;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;

/** 作业查询的独立设置页：学习通账户、课表导入与冬夏令时作息。 */
public class StudySettingsActivity extends AppCompatActivity {

    /** 开学日期改过时带回课表页，让它跳到新的本周。 */
    public static final String EXTRA_WEEK_CHANGED = "week_changed";

    /** 系统保存面板只看 MIME，写 .ics 用 text/calendar 才不会被改名成 .txt。 */
    private static final String ICS_MIME = "text/calendar";

    /** 教务课表页另存也就几百 KB，再大就不是课表了，读到一半直接停。 */
    private static final int MAX_IMPORT_FILE_BYTES = 16 * 1024 * 1024;

    private ActivityStudySettingsBinding binding;
    private StudySession session;
    private int season = StudySchedule.SEASON_WINTER;
    private StudySchedule schedule = StudySchedule.winterDefault();
    private boolean weekChanged;

    private final ActivityResultLauncher<Intent> loginLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() == RESULT_OK) {
                            renderAccount();
                            notifyChanged();
                        }
                    });

    private final ActivityResultLauncher<Intent> jwLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() == RESULT_OK) {
                            onTimetableChanged();
                        }
                    });

    /** 点「导出」时生成的日历文本，等系统保存面板回来后再写。 */
    private String pendingIcsText = "";
    private int pendingIcsEvents;
    /** 分享码或课表文件解出来的课程，冲突预演确认后才写本机课表。 */
    private List<TimetableCourse> pendingImport = Collections.emptyList();
    /** 预演对话框上要写明这批课从哪来；分享码不需要说，留空串。 */
    private String pendingImportSource = "";

    /** 导出 .ics 用的保存面板：文本在点「导出」时就生成好，等用户选完落点再写。 */
    private final ActivityResultLauncher<Intent> icsLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        Uri target = result.getData() == null ? null : result.getData().getData();
                        if (result.getResultCode() != RESULT_OK || target == null) {
                            return; // 用户在系统面板里取消，不再多提示一次。
                        }
                        String text = pendingIcsText;
                        int events = pendingIcsEvents;
                        pendingIcsText = "";
                        IcsTransfer.save(this, text, events, target, new IcsTransfer.SaveListener() {
                            @Override
                            public void onSaved(int count) {
                                toast(getString(R.string.study_timetable_export_saved, count));
                            }

                            @Override
                            public void onFailed(@NonNull String detail) {
                                toast(getString(R.string.study_timetable_export_failed, detail));
                            }
                        });
                    });

    /** 课表文件由用户自己从能打开教务的设备上另存，这里只读他选中的那一个。 */
    private final ActivityResultLauncher<Intent> timetableFileLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        Uri picked = result.getData() == null ? null : result.getData().getData();
                        if (result.getResultCode() != RESULT_OK || picked == null) {
                            return; // 用户在系统面板里取消，不再多提示一次。
                        }
                        importTimetableFile(picked);
                    });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityStudySettingsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        binding.buttonSettingsBack.setOnClickListener(view -> finish());
        binding.buttonSwitchAccount.setOnClickListener(view -> onSwitchAccount());
        binding.rowSemesterStart.setOnClickListener(view -> pickSemesterStart());
        binding.rowReimport.setOnClickListener(view ->
                jwLauncher.launch(new Intent(this, JwImportActivity.class)));
        binding.rowAddCourse.setOnClickListener(view ->
                StudyDialogs.showCourseEditor(this, null, course -> onTimetableChanged()));
        binding.rowClearTimetable.setOnClickListener(view -> confirmClearTimetable());
        binding.rowExportIcs.setOnClickListener(view -> confirmExportIcs());
        binding.rowCopyShareCode.setOnClickListener(view -> copyShareCode());
        binding.rowImportShareCode.setOnClickListener(view -> showShareCodeDialog());
        binding.rowImportTimetableFile.setOnClickListener(view -> confirmTimetableFilePick());
        binding.rowSemesters.setOnClickListener(view -> showSemesters());
        binding.rowAdjustments.setOnClickListener(view -> showAdjustments());

        binding.switchClassReminder.setOnClickListener(view -> {
            boolean enabled = binding.switchClassReminder.isChecked();
            StudyTimetableStore.saveClassReminderEnabled(this, enabled);
            Toast.makeText(this, enabled
                            ? R.string.study_reminder_enabled_toast
                            : R.string.study_reminder_disabled_toast,
                    Toast.LENGTH_SHORT).show();
            renderReminder();
            notifyChanged();
        });
        binding.rowReminderLead.setOnClickListener(view -> pickReminderLead());

        binding.rowHomeworkReminderLead.setOnClickListener(view -> pickHomeworkReminderLead());
        binding.switchHomeworkReminder.setOnClickListener(view -> {
            boolean enabled = binding.switchHomeworkReminder.isChecked();
            CustomHomeworkStore.saveReminderEnabled(this, enabled);
            Toast.makeText(this, enabled
                            ? R.string.study_homework_reminder_enabled_toast
                            : R.string.study_homework_reminder_disabled_toast,
                    Toast.LENGTH_SHORT).show();
            renderHomeworkReminder();
            notifyChanged();
        });

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
        renderAdjustments();
        renderSemesters();
        renderSchedule();
        renderReminder();
        renderHomeworkReminder();
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderAccount();
        renderTimetableCount();
        renderAdjustments();
        renderSemesters();
        renderSchedule();
        renderReminder();
        renderHomeworkReminder();
    }

    private void onSwitchAccount() {
        session = StudySessionStore.load(this);
        if (session == null) {
            loginLauncher.launch(new Intent(this, StudyLoginActivity.class));
            return;
        }
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
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
        renderSemesterStart();
    }

    // ------------------------------------------------------------------ 多学期

    /** 行上写「N 个学期 · 当前「X」」；还没分过学期时直说，不摆一个假的 1。 */
    private void renderSemesters() {
        SemesterProfile active = SemesterStore.active(this);
        List<SemesterProfile> profiles = SemesterStore.profiles(this);
        if (active == null || profiles.isEmpty()) {
            binding.textSemesterValue.setText(R.string.study_semester_manage_none);
            return;
        }
        binding.textSemesterValue.setText(getString(R.string.study_semester_manage_count,
                profiles.size(), active.name()));
    }

    private void showSemesters() {
        List<SemesterProfile> profiles = SemesterStore.profiles(this);
        if (profiles.isEmpty()) {
            // 一份归档都还没有时列表是空的，与其摆个假条目，不如直接把「新建」讲清楚。
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.study_semester_manage_title)
                    .setMessage(R.string.study_semester_manage_single)
                    .setNegativeButton(R.string.campus_cancel, null)
                    .setPositiveButton(R.string.study_semester_create,
                            (dialog, which) -> promptCreateSemester())
                    .show();
            return;
        }
        String activeId = SemesterStore.activeId(this);
        LocalDate today = LocalDate.now();
        String[] labels = new String[profiles.size()];
        for (int i = 0; i < profiles.size(); i++) {
            SemesterProfile profile = profiles.get(i);
            labels[i] = profile.name() + "（" + (profile.id().equals(activeId)
                    ? getString(R.string.study_semester_current) + " · " : "")
                    + SemesterStore.summary(this, profile.id(), today) + "）";
        }
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.study_semester_manage_title)
                .setMessage(R.string.study_semester_manage_hint)
                .setItems(labels, (dialog, which) -> showSemesterActions(profiles.get(which),
                        profiles.get(which).id().equals(activeId)))
                .setNegativeButton(R.string.campus_cancel, null)
                .setPositiveButton(R.string.study_semester_create,
                        (dialog, which) -> promptCreateSemester())
                .show();
    }

    /** 当前学期只给改名：删掉正在看的这份没有意义，切到自己也不产生任何变化。 */
    private void showSemesterActions(@NonNull SemesterProfile profile, boolean current) {
        String[] actions = current
                ? new String[]{getString(R.string.study_semester_action_rename)}
                : new String[]{getString(R.string.study_semester_action_switch),
                        getString(R.string.study_semester_action_rename),
                        getString(R.string.study_semester_action_delete)};
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(profile.name())
                .setItems(actions, (dialog, which) -> {
                    if (current) {
                        promptRenameSemester(profile);
                    } else if (which == 0) {
                        confirmSwitchSemester(profile);
                    } else if (which == 1) {
                        promptRenameSemester(profile);
                    } else {
                        confirmDeleteSemester(profile);
                    }
                })
                .setNegativeButton(R.string.campus_cancel, null)
                .show();
    }

    /**
     * 新建时不立刻切过去：用户可能只是想先把下学期的位置占上，
     * 手滑切走会把当前课表收进归档，屏幕上的内容整个换掉。
     */
    private void promptCreateSemester() {
        if (!SemesterStore.canCreate(this)) {
            toast(getString(R.string.study_semester_full, SemesterArchive.MAX_SEMESTERS));
            return;
        }
        promptForName(getString(R.string.study_semester_create), SemesterProfile.defaultName(
                        StudyTimetableStore.loadSemesterStart(this),
                        SemesterStore.profiles(this).size()),
                name -> {
                    SemesterProfile created = SemesterStore.create(this, name);
                    renderSemesters();
                    new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                            .setMessage(getString(R.string.study_semester_created,
                                    created.name()))
                            .setNegativeButton(R.string.study_semester_stay, null)
                            .setPositiveButton(R.string.study_semester_switch_now,
                                    (dialog, which) -> confirmSwitchSemester(created))
                            .show();
                });
    }

    private void promptRenameSemester(@NonNull SemesterProfile profile) {
        promptForName(getString(R.string.study_semester_action_rename), profile.name(), name -> {
            SemesterStore.rename(this, profile.id(), name);
            renderSemesters();
            toast(getString(R.string.study_semester_renamed, name));
        });
    }

    /** 新建与改名都只要一行文字，共用同一个输入框：默认值填好、光标停在末尾。 */
    private void promptForName(@NonNull CharSequence title, @NonNull String suggested,
                               @NonNull java.util.function.Consumer<String> onAccept) {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setTextSize(14f);
        input.setText(suggested);
        input.setSelection(input.getText().length());
        int padding = Math.round(20 * getResources().getDisplayMetrics().density);
        FrameLayout container = new FrameLayout(this);
        container.setPadding(padding, Math.round(8 * getResources().getDisplayMetrics().density),
                padding, 0);
        container.addView(input);
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setView(container)
                .setNegativeButton(R.string.campus_cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) {
                        toast(getString(R.string.study_semester_name_empty));
                        return;
                    }
                    onAccept.accept(name);
                })
                .show();
    }

    /** 切换前把「本机存起来几门、换上几门」说清楚：切完才发现课表空了是最糟的体验。 */
    private void confirmSwitchSemester(@NonNull SemesterProfile target) {
        int localCourses = StudyTimetableStore.loadCourses(this).size();
        int targetCourses = SemesterStore.courseCount(this, target.id());
        confirmSwitch(target, targetCourses == 0
                ? getString(R.string.study_semester_switch_empty, localCourses)
                : getString(R.string.study_semester_switch_message, localCourses, target.name(),
                        targetCourses));
    }

    private void confirmSwitch(@NonNull SemesterProfile target, @NonNull String message) {
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.study_semester_switch_title, target.name()))
                .setMessage(message)
                .setNegativeButton(R.string.campus_cancel, null)
                .setPositiveButton(R.string.study_semester_switch_now,
                        (dialog, which) -> switchSemester(target))
                .show();
    }

    /** 换学期等于换了开学日期、总周数、课表与作息：这几处显示与闹钟都得跟着重算。 */
    private void switchSemester(@NonNull SemesterProfile target) {
        SemesterStore.switchTo(this, target.id());
        markWeekChanged();
        renderTimetableCount();
        renderSchedule();
        renderAdjustments();
        renderSemesters();
        renderReminder();
        ClassReminderScheduler.update(this);
        TimetableWidgetProvider.updateAll(this);
        toast(getString(R.string.study_semester_switched, target.name()));
    }

    private void confirmDeleteSemester(@NonNull SemesterProfile profile) {
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.study_semester_delete_title, profile.name()))
                .setMessage(R.string.study_semester_delete_message)
                .setNegativeButton(R.string.campus_cancel, null)
                .setPositiveButton(R.string.study_semester_action_delete, (dialog, which) -> {
                    if (!SemesterStore.delete(this, profile.id())) {
                        toast(getString(R.string.study_semester_cannot_delete_current));
                        return;
                    }
                    renderSemesters();
                    toast(getString(R.string.study_semester_deleted, profile.name()));
                })
                .show();
    }

    // ------------------------------------------------------------ 调课与节假日

    /** 例外条数写在行上，不用点开也知道这学期调过几次课。 */
    private void renderAdjustments() {
        int count = StudyTimetableStore.loadAdjustments(this).size();
        binding.textAdjustmentValue.setText(count == 0
                ? getString(R.string.study_timetable_adjust_none)
                : getString(R.string.study_timetable_adjust_count, count));
    }

    /** 列表就是全部例外：点一条问要不要删，右下角「添加」走一遍录入。 */
    private void showAdjustments() {
        List<DayAdjustment> items = StudyTimetableStore.loadAdjustments(this);
        List<TimetableCourse> courses = StudyTimetableStore.loadCourses(this);
        LocalDate today = LocalDate.now();
        String[] labels = new String[items.size()];
        for (int i = 0; i < items.size(); i++) {
            labels[i] = DayAdjustmentLabel.label(items.get(i), courses, today);
        }
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.study_timetable_adjust_title)
                .setMessage(items.isEmpty() ? R.string.study_timetable_adjust_empty
                        : R.string.study_timetable_adjust_manage_hint)
                .setItems(labels, (dialog, which) -> confirmRemoveAdjustment(items.get(which)))
                .setNegativeButton(R.string.campus_cancel, null)
                .setPositiveButton(R.string.study_timetable_adjust_add,
                        (dialog, which) -> pickAdjustmentKind())
                .show();
    }

    private void confirmRemoveAdjustment(@NonNull DayAdjustment item) {
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.study_timetable_adjust_delete_title)
                .setMessage(DayAdjustmentLabel.label(item,
                        StudyTimetableStore.loadCourses(this), LocalDate.now()))
                .setNegativeButton(R.string.campus_cancel, null)
                .setPositiveButton(R.string.study_course_delete, (dialog, which) -> {
                    StudyTimetableStore.removeAdjustment(this, item);
                    renderAdjustments();
                    toast(getString(R.string.study_timetable_adjust_removed));
                })
                .show();
    }

    private void pickAdjustmentKind() {
        DayAdjustment.Kind[] kinds = {DayAdjustment.Kind.HOLIDAY, DayAdjustment.Kind.MAKEUP,
                DayAdjustment.Kind.CANCEL, DayAdjustment.Kind.MOVE};
        String[] labels = new String[kinds.length];
        for (int i = 0; i < kinds.length; i++) {
            labels[i] = DayAdjustmentLabel.kindLabel(kinds[i]);
        }
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.study_timetable_adjust_pick_title)
                .setItems(labels, (dialog, which) -> pickAdjustmentDate(kinds[which]))
                .setNegativeButton(R.string.campus_cancel, null)
                .show();
    }

    /**
     * 先定日期再问细节。日期只让选今天及以后：过去的日子小组件和提醒都不会再回头看，
     * 记了也等于没记，还白占存储。
     */
    private void pickAdjustmentDate(@NonNull DayAdjustment.Kind kind) {
        if ((kind == DayAdjustment.Kind.CANCEL || kind == DayAdjustment.Kind.MOVE)
                && StudyTimetableStore.loadCourses(this).isEmpty()) {
            toast(getString(R.string.study_timetable_adjust_need_courses));
            return;
        }
        if (kind == DayAdjustment.Kind.MOVE
                && StudyTimetableStore.loadSemesterStart(this) == null) {
            toast(getString(R.string.study_timetable_adjust_need_start));
            return;
        }
        LocalDate today = LocalDate.now();
        DatePickerDialog dialog = new DatePickerDialog(this, (view, year, month, dayOfMonth) ->
                askRestOf(kind, LocalDate.of(year, month + 1, dayOfMonth)),
                today.getYear(), today.getMonthValue() - 1, today.getDayOfMonth());
        dialog.getDatePicker().setMinDate(System.currentTimeMillis());
        dialog.show();
    }

    private void askRestOf(@NonNull DayAdjustment.Kind kind, @NonNull LocalDate date) {
        switch (kind) {
            case MAKEUP:
                pickMakeupWeekday(date);
                return;
            case CANCEL:
                pickCourseOn(date, false);
                return;
            case MOVE:
                pickCourseOn(date, true);
                return;
            default:
                saveAdjustment(DayAdjustment.holiday(date));
        }
    }

    private void pickMakeupWeekday(@NonNull LocalDate date) {
        newItemChooser(getString(R.string.study_timetable_adjust_ref_title), weekdayLabels(),
                which -> saveAdjustment(DayAdjustment.makeup(date, which + 1)));
    }

    /**
     * 只列这天真的会上到的课：把例外记在一节本来就没有的课上，除了让人以为上过之外没有别的效果。
     */
    private void pickCourseOn(@NonNull LocalDate date, boolean move) {
        List<TimetableCourse> onDay = TimetableDay.classesOn(
                StudyTimetableStore.loadCourses(this), date,
                StudyTimetableStore.loadSemesterStart(this),
                StudyTimetableStore.loadTotalWeeks(this),
                StudyTimetableStore.loadAdjustments(this));
        if (onDay.isEmpty()) {
            toast(getString(R.string.study_timetable_adjust_no_class_on_day));
            return;
        }
        String[] labels = new String[onDay.size()];
        for (int i = 0; i < onDay.size(); i++) {
            labels[i] = onDay.get(i).name() + " " + onDay.get(i).periodLabel();
        }
        newItemChooser(getString(R.string.study_timetable_adjust_course_title), labels, which -> {
            TimetableCourse course = onDay.get(which);
            if (move) {
                pickMoveTarget(date, course);
            } else {
                saveAdjustment(DayAdjustment.cancel(date, course.id()));
            }
        });
    }

    /** 改课只问「改到哪天、从第几节开始」：目标日期由同一周内的星期几推出来，不必再填一次日期。 */
    private void pickMoveTarget(@NonNull LocalDate date, @NonNull TimetableCourse source) {
        newItemChooser(getString(R.string.study_timetable_adjust_target_title), weekdayLabels(),
                which -> pickMovePeriod(date, source, which + 1));
    }

    /** 连上几节沿用原来的长度，超出当天最后一节时贴到最后一节。 */
    private void pickMovePeriod(@NonNull LocalDate date, @NonNull TimetableCourse source,
                                int weekday) {
        int total = StudyTimetableStore.loadSchedule(this).totalPeriods();
        String[] labels = new String[total];
        for (int i = 1; i <= total; i++) {
            labels[i - 1] = DayAdjustmentLabel.periodLabel(i, i);
        }
        newItemChooser(getString(R.string.study_timetable_adjust_period_title), labels, which -> {
            int start = which + 1;
            int length = Math.max(1, source.endPeriod() - source.startPeriod() + 1);
            saveAdjustment(DayAdjustment.move(date, source.id(), weekday, start,
                    Math.min(total, start + length - 1)));
        });
    }

    private void saveAdjustment(@NonNull DayAdjustment adjustment) {
        StudyTimetableStore.putAdjustment(this, adjustment);
        renderAdjustments();
        toast(getString(R.string.study_timetable_adjust_saved, DayAdjustmentLabel.label(
                adjustment, StudyTimetableStore.loadCourses(this), LocalDate.now())));
    }

    private void newItemChooser(@NonNull String title, @NonNull String[] labels,
                                @NonNull java.util.function.IntConsumer onPick) {
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setItems(labels, (dialog, which) -> onPick.accept(which))
                .setNegativeButton(R.string.campus_cancel, null)
                .show();
    }

    private static String[] weekdayLabels() {
        String[] labels = new String[7];
        for (int i = 1; i <= 7; i++) {
            labels[i - 1] = DayAdjustmentLabel.weekdayName(i);
        }
        return labels;
    }

    private void renderSemesterStart() {
        LocalDate start = StudyTimetableStore.loadSemesterStart(this);
        if (start == null) {
            binding.textSemesterStartValue.setText(R.string.study_semester_start_unset);
            return;
        }
        int week = StudyTimetableStore.currentTeachingWeek(this);
        binding.textSemesterStartValue.setText(week > 0
                ? getString(R.string.study_semester_start_value, start.toString(), week)
                : getString(R.string.study_semester_start_outside, start.toString()));
    }

    private void pickSemesterStart() {
        LocalDate stored = StudyTimetableStore.loadSemesterStart(this);
        LocalDate initial = stored != null ? stored : LocalDate.now();
        DatePickerDialog dialog = new DatePickerDialog(this,
                (view, year, month, dayOfMonth) -> {
                    StudyTimetableStore.saveSemesterStart(this,
                            LocalDate.of(year, month + 1, dayOfMonth));
                    markWeekChanged();
                },
                initial.getYear(), initial.getMonthValue() - 1, initial.getDayOfMonth());
        dialog.setTitle(R.string.study_semester_start_pick_title);
        if (stored != null) {
            dialog.setButton(DialogInterface.BUTTON_NEUTRAL,
                    getString(R.string.study_semester_start_clear), (d, which) -> {
                        StudyTimetableStore.clearSemesterStart(this);
                        markWeekChanged();
                    });
        }
        dialog.show();
    }

    private void markWeekChanged() {
        weekChanged = true;
        notifyChanged();
        renderSemesterStart();
    }

    /** 把「开学日期是否改过」一并带回课表页。 */
    private void notifyChanged() {
        setResult(RESULT_OK, weekChanged
                ? new Intent().putExtra(EXTRA_WEEK_CHANGED, true) : null);
    }

    private void onTimetableChanged() {
        renderTimetableCount();
        renderSemesters();
        notifyChanged();
    }

    private void confirmClearTimetable() {
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
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

    /**
     * 导出 .ics：先把「日历里没有调课与节假日」说清楚，再交给系统保存面板选落点。
     *
     * <p>没有开学日期时不导出——日历事件必须有具体第一天，否则整份文件都是空的。
     */
    private void confirmExportIcs() {
        List<TimetableCourse> courses = StudyTimetableStore.loadCourses(this);
        if (courses.isEmpty()) {
            toast(getString(R.string.study_timetable_export_empty));
            return;
        }
        LocalDate semesterStart = StudyTimetableStore.loadSemesterStart(this);
        if (semesterStart == null) {
            toast(getString(R.string.study_timetable_export_needs_start));
            return;
        }
        int totalWeeks = StudyTimetableStore.loadTotalWeeks(this);
        pendingIcsText = IcsTimetableWriter.write(courses,
                StudyTimetableStore.loadSchedule(this), semesterStart, totalWeeks,
                getString(R.string.study_timetable_calendar_name));
        pendingIcsEvents = IcsTimetableWriter.exportable(courses,
                StudyTimetableStore.loadSchedule(this), totalWeeks);
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.study_timetable_export_title)
                .setMessage(R.string.study_timetable_export_message)
                .setNegativeButton(R.string.campus_cancel, null)
                .setPositiveButton(R.string.study_timetable_export_ics, (dialog, which) -> {
                    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType(ICS_MIME);
                    intent.putExtra(Intent.EXTRA_TITLE, IcsTimetableWriter.suggestedFileName(
                            semesterStart, LocalDate.now()));
                    icsLauncher.launch(intent);
                })
                .show();
    }

    /** 分享码只是把课表 JSON 压成一段文本，复制走系统剪贴板，不发网络。 */
    private void copyShareCode() {
        List<TimetableCourse> courses = StudyTimetableStore.loadCourses(this);
        if (courses.isEmpty()) {
            toast(getString(R.string.study_timetable_share_empty));
            return;
        }
        String code = TimetableShareCodec.encode(courses);
        ClipboardManager manager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (manager == null) {
            toast(getString(R.string.study_timetable_share_failed));
            return;
        }
        manager.setPrimaryClip(ClipData.newPlainText(
                getString(R.string.study_timetable_share_code), code));
        toast(getString(R.string.study_timetable_share_copied, courses.size(), code.length()));
    }

    /** 粘帖框预填剪贴板里现成的分享码，多数情况下用户只要点一下预览。 */
    private void showShareCodeDialog() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setMinLines(3);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setTextSize(12f);
        String clipboard = readClipboardText();
        if (TimetableShareCodec.looksLikeShareCode(clipboard)) {
            input.setText(clipboard);
            input.setSelection(input.getText().length());
        }
        int padding = Math.round(20 * getResources().getDisplayMetrics().density);
        FrameLayout container = new FrameLayout(this);
        container.setPadding(padding, Math.round(8 * getResources().getDisplayMetrics().density),
                padding, 0);
        container.addView(input);
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.study_timetable_import_code_title)
                .setMessage(R.string.study_timetable_import_code_hint)
                .setView(container)
                .setNegativeButton(R.string.campus_cancel, null)
                .setPositiveButton(R.string.study_timetable_import_preview_action,
                        (dialog, which) -> previewShareImport(input.getText().toString()))
                .show();
    }

    private void previewShareImport(String pasted) {
        TimetableShareCodec.DecodeResult result = TimetableShareCodec.decode(pasted);
        if (!result.ok()) {
            toast(getString(R.string.study_timetable_import_failed, result.error()));
            return;
        }
        pendingImportSource = "";
        previewImport(result.courses(), result.skipped());
    }

    /** 先说清「会撞在哪」再让用户挑追加还是替换，分享码与课表文件共用这一条路。 */
    private void previewImport(List<TimetableCourse> incoming, int skipped) {
        List<TimetableCourse> existing = StudyTimetableStore.loadCourses(this);
        TimetableImportPlan plan = TimetableImportPlan.of(incoming, existing);
        if (plan.toAdd().isEmpty()) {
            toast(getString(R.string.study_timetable_import_nothing));
            return;
        }
        pendingImport = incoming;
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.study_timetable_import_preview_title,
                        plan.toAdd().size()))
                .setMessage(importPreviewMessage(plan, skipped))
                .setNeutralButton(R.string.campus_cancel, null)
                .setNegativeButton(R.string.study_timetable_import_replace,
                        (dialog, which) -> confirmReplace())
                .setPositiveButton(R.string.study_timetable_import_append,
                        (dialog, which) -> applyImport(false))
                .show();
    }

    /** 说明文件从哪来、本机不联网，再开系统选择器——避免用户以为这里能代登教务。 */
    private void confirmTimetableFilePick() {
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.study_timetable_import_file)
                .setMessage(R.string.study_timetable_import_file_hint)
                .setNegativeButton(R.string.campus_cancel, null)
                .setPositiveButton(R.string.study_timetable_import_file_action,
                        (dialog, which) -> launchTimetableFileChooser())
                .show();
    }

    private void launchTimetableFileChooser() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        // 教务另存的网页在各单位 MIME 五花八门（text/html、octet-stream…），按后缀让人自己挑。
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"text/html", "text/plain",
                "text/csv", "application/octet-stream"});
        timetableFileLauncher.launch(intent);
    }

    private void importTimetableFile(Uri uri) {
        byte[] raw = readPickedFile(uri);
        if (raw == null) {
            toast(getString(R.string.study_timetable_import_file_unreadable));
            return;
        }
        TimetableOfflineImport.Result result = TimetableOfflineImport.parseBytes(raw);
        if (!result.ok()) {
            toast(getString(R.string.study_timetable_import_file_failed, result.error()));
            return;
        }
        pendingImportSource = result.sourceLabel(
                getString(R.string.study_timetable_source_zhengfang),
                getString(R.string.study_timetable_source_qiangzhi),
                getString(R.string.study_timetable_source_csv));
        previewImport(result.courses(), 0);
    }

    /** 读到上限就当作读不到：教务课表页不会上百 MB，那样的人为失误不必分两种提示。 */
    @Nullable
    private byte[] readPickedFile(Uri uri) {
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) {
                return null;
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) > 0) {
                bytes.write(buffer, 0, read);
                if (bytes.size() > MAX_IMPORT_FILE_BYTES) {
                    return null;
                }
            }
            return bytes.toByteArray();
        } catch (IOException | SecurityException e) {
            return null;
        }
    }

    /** 冲突一行一条，最多列 4 条，剩下的报个数——对话框不是课表全览。 */
    private String importPreviewMessage(TimetableImportPlan plan, int skipped) {
        StringBuilder text = new StringBuilder();
        if (!pendingImportSource.isEmpty()) {
            text.append(getString(R.string.study_timetable_import_source, pendingImportSource))
                    .append('\n');
        }
        text.append(getString(R.string.study_timetable_import_preview,
                plan.toAdd().size(), plan.duplicates()));
        if (skipped > 0) {
            text.append('\n').append(getString(R.string.study_timetable_import_skipped, skipped));
        }
        if (plan.hasConflicts()) {
            List<String> conflicts = plan.conflicts();
            int shown = Math.min(4, conflicts.size());
            StringBuilder lines = new StringBuilder();
            for (int i = 0; i < shown; i++) {
                if (i > 0) {
                    lines.append('\n');
                }
                lines.append("· ").append(conflicts.get(i));
            }
            if (conflicts.size() > shown) {
                lines.append('\n')
                        .append(getString(R.string.study_timetable_import_more_conflicts,
                                conflicts.size() - shown));
            }
            text.append('\n').append(getString(R.string.study_timetable_import_conflicts,
                    conflicts.size(), lines));
        }
        return text.toString();
    }

    /** 替换会清掉本机课表，所以单独确认一次，并写明会少掉几门。 */
    private void confirmReplace() {
        int kept = StudyTimetableStore.loadCourses(this).size();
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.study_timetable_import_replace)
                .setMessage(getString(R.string.study_timetable_import_replace_confirm,
                        kept, pendingImport.size()))
                .setNegativeButton(R.string.campus_cancel, null)
                .setPositiveButton(R.string.study_timetable_import_replace,
                        (dialog, which) -> applyImport(true))
                .show();
    }

    private void applyImport(boolean replace) {
        List<TimetableCourse> courses = StudyTimetableStore.loadCourses(this);
        List<TimetableCourse> merged = replace ? pendingImport
                : TimetableImportPlan.appendTo(courses, pendingImport);
        int added = merged.size() - courses.size();
        StudyTimetableStore.saveCourses(this, merged);
        pendingImport = Collections.emptyList();
        pendingImportSource = "";
        onTimetableChanged();
        // 替换后「多了几门」没有意义（课变少也是正常的），报最终门数。
        toast(replace ? getString(R.string.study_timetable_import_replaced, merged.size())
                : getString(R.string.study_timetable_import_done, Math.max(0, added)));
    }

    @Nullable
    private String readClipboardText() {
        ClipboardManager manager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (manager == null || !manager.hasPrimaryClip() || manager.getPrimaryClip() == null
                || manager.getPrimaryClip().getItemCount() == 0) {
            return "";
        }
        CharSequence text = manager.getPrimaryClip().getItemAt(0).coerceToText(this);
        return text == null ? "" : text.toString();
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
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
        notifyChanged();
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
        notifyChanged();
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

    // ------------------------------------------------------------------ 课前提醒

    private void renderReminder() {
        boolean enabled = StudyTimetableStore.loadClassReminderEnabled(this);
        binding.switchClassReminder.setChecked(enabled);
        binding.textReminderLeadValue.setText(getString(R.string.study_reminder_lead_value,
                StudyTimetableStore.loadClassReminderLeadMinutes(this)));
        // 设置或课表可能已经变化，每次回到本页都按存储重排一次闹钟（幂等）。
        ClassReminderScheduler.update(this);
        if (!enabled) {
            binding.textReminderWarning.setVisibility(View.GONE);
            return;
        }
        LocalDate semesterStart = StudyTimetableStore.loadSemesterStart(this);
        if (semesterStart == null) {
            showReminderWarning(getString(R.string.study_reminder_need_semester), true);
            return;
        }
        if (StudyTimetableStore.loadCourses(this).isEmpty()) {
            showReminderWarning(getString(R.string.study_reminder_no_courses), true);
            return;
        }
        ClassReminderPlanner.Reminder next = ClassReminderPlanner.next(
                StudyTimetableStore.loadCourses(this),
                StudyTimetableStore.loadSchedule(this),
                semesterStart,
                StudyTimetableStore.loadTotalWeeks(this),
                LocalDateTime.now(),
                StudyTimetableStore.loadClassReminderLeadMinutes(this),
                ClassReminderPlanner.DEFAULT_HORIZON_DAYS,
                StudyTimetableStore.loadAdjustments(this));
        if (next == null) {
            showReminderWarning(getString(R.string.study_reminder_no_upcoming), true);
            return;
        }
        binding.textReminderWarning.setText(getString(R.string.study_reminder_next_hint,
                next.course.weekdayLabel(),
                StudySchedule.formatMinutes(next.startMinutesOfDay),
                next.course.name()));
        binding.textReminderWarning.setTextColor(
                ContextCompat.getColor(this, R.color.campus_muted));
        binding.textReminderWarning.setVisibility(View.VISIBLE);
    }

    private void showReminderWarning(@NonNull String message, boolean danger) {
        binding.textReminderWarning.setText(message);
        binding.textReminderWarning.setTextColor(ContextCompat.getColor(this,
                danger ? R.color.campus_danger : R.color.campus_muted));
        binding.textReminderWarning.setVisibility(View.VISIBLE);
    }

    // ------------------------------------------------------------------ 作业截止提醒

    private void renderHomeworkReminder() {
        boolean enabled = CustomHomeworkStore.loadReminderEnabled(this);
        binding.switchHomeworkReminder.setChecked(enabled);
        int leadMinutes = StudyTimetableStore.loadHomeworkReminderLeadMinutes(this);
        binding.textHomeworkReminderLeadValue.setText(getString(
                R.string.study_homework_reminder_lead_value,
                HomeworkReminderPlanner.leadLabel(leadMinutes)));
        // 自建作业或开关可能刚改过，每次回到本页按存储重排一次闹钟（幂等）。
        HomeworkReminderScheduler.update(this);
        if (!enabled) {
            binding.textHomeworkReminderHint.setVisibility(View.GONE);
            return;
        }
        List<CustomHomework> items = CustomHomeworkStore.load(this);
        if (items.isEmpty()) {
            showHomeworkReminderHint(getString(
                    R.string.study_homework_reminder_no_items), true);
            return;
        }
        HomeworkReminderPlanner.Reminder next = HomeworkReminderPlanner.next(items,
                LocalDateTime.now(), leadMinutes, HomeworkReminderPlanner.DEFAULT_HORIZON_DAYS);
        if (next == null) {
            showHomeworkReminderHint(getString(
                    R.string.study_homework_reminder_no_upcoming), true);
            return;
        }
        showHomeworkReminderHint(getString(R.string.study_homework_reminder_next_hint,
                CustomHomework.formatShortDateTime(next.homework.remindEpochMillis(leadMinutes)),
                next.homework.title(),
                getString(R.string.study_reminder_lead_value, leadMinutes)),
                false);
    }

    private void showHomeworkReminderHint(@NonNull String message, boolean danger) {
        binding.textHomeworkReminderHint.setText(message);
        binding.textHomeworkReminderHint.setTextColor(ContextCompat.getColor(this,
                danger ? R.color.campus_danger : R.color.campus_muted));
        binding.textHomeworkReminderHint.setVisibility(View.VISIBLE);
    }

    private void pickReminderLead() {
        promptLead(R.string.study_reminder_lead_dialog_title,
                R.string.study_reminder_lead_invalid,
                StudyTimetableStore.loadClassReminderLeadMinutes(this),
                ClassReminderPlanner.MIN_LEAD_MINUTES, ClassReminderPlanner.MAX_LEAD_MINUTES,
                minutes -> {
                    StudyTimetableStore.saveClassReminderLeadMinutes(this, minutes);
                    ClassReminderScheduler.update(this);
                    renderReminder();
                    notifyChanged();
                });
    }

    /** 作业截止提前量：与课前提醒同一套做法，改一次即影响全部自建作业。 */
    private void pickHomeworkReminderLead() {
        promptLead(R.string.study_homework_reminder_lead_dialog_title,
                R.string.study_homework_reminder_lead_invalid,
                StudyTimetableStore.loadHomeworkReminderLeadMinutes(this),
                HomeworkReminderPlanner.MIN_LEAD_MINUTES,
                HomeworkReminderPlanner.MAX_LEAD_MINUTES,
                minutes -> {
                    StudyTimetableStore.saveHomeworkReminderLeadMinutes(this, minutes);
                    HomeworkReminderScheduler.update(this);
                    renderHomeworkReminder();
                    notifyChanged();
                });
    }

    private void promptLead(@StringRes int titleRes, @StringRes int invalidRes, int current,
                            int min, int max, IntConsumer onSaved) {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setText(String.valueOf(current));
        input.setSelectAllOnFocus(true);
        int padding = (int) dp(20);
        FrameLayout container = new FrameLayout(this);
        container.setPadding(padding, padding / 2, padding, 0);
        container.addView(input);
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(titleRes)
                .setView(container)
                .setNegativeButton(R.string.campus_cancel, null)
                .setPositiveButton(R.string.campus_save, (dialog, which) -> {
                    int minutes;
                    try {
                        minutes = Integer.parseInt(input.getText().toString().trim());
                    } catch (NumberFormatException error) {
                        minutes = -1;
                    }
                    if (minutes < min || minutes > max) {
                        Toast.makeText(this, invalidRes, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    onSaved.accept(minutes);
                })
                .show();
    }
}
