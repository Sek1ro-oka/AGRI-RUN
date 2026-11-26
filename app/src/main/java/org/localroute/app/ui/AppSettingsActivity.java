package org.localroute.app.ui;

import android.appwidget.AppWidgetManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import org.localroute.app.R;
import org.localroute.app.backup.BackupBundle;
import org.localroute.app.backup.BackupCatalog;
import org.localroute.app.backup.BackupCodec;
import org.localroute.app.backup.BackupMerge;
import org.localroute.app.backup.BackupStore;
import org.localroute.app.backup.BackupTransfer;
import org.localroute.app.campus.PresetUnlock;
import org.localroute.app.databinding.ActivityAppSettingsBinding;
import org.localroute.app.settings.AppearancePreferences;
import org.localroute.app.study.ClassReminderPlanner;
import org.localroute.app.study.CustomHomework;
import org.localroute.app.study.CustomHomeworkStore;
import org.localroute.app.study.DayAdjustment;
import org.localroute.app.study.DayAdjustmentLabel;
import org.localroute.app.study.HomeworkReminderPlanner;
import org.localroute.app.study.ReminderCenter;
import org.localroute.app.study.StudySchedule;
import org.localroute.app.study.StudyTimetableStore;
import org.localroute.app.study.TimetableCourse;
import org.localroute.app.study.TimetableDay;
import org.localroute.app.widget.TimetableWidgetProvider;
import org.localroute.app.widget.TimetableWidgetSync;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 应用设置：语言（本期只展示入口）、激活码、界面风格与本机数据备份。
 *
 * <p>激活码只保存在本机，用于补回校园快跑里的 A操 / B操 预设路线；
 * 校验逻辑在 {@link PresetUnlock}，本页不记录用户输入，也不显示激活状态。
 * 备份的读写都通过系统文件选择器交给 {@link BackupTransfer}，本页只负责选择内容、
 * 把后果说清楚，以及在真正写入前要到一次确认。
 */
public class AppSettingsActivity extends AppCompatActivity {

    private static final String JSON_MIME = "application/json";

    private ActivityAppSettingsBinding binding;
    private SharedPreferences prefs;
    /** 用户在导出对话框里勾选的段落，等到系统保存面板回来后再用。 */
    private List<String> pendingExportIds = Collections.emptyList();
    /** 导入对话框选中的段落与对应的备份内容，恢复确认后写回。 */
    private BackupBundle pendingBundle;
    private List<String> pendingImportIds = Collections.emptyList();

    private final ActivityResultLauncher<Intent> exportLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        Uri target = result.getData() == null ? null : result.getData().getData();
                        if (result.getResultCode() != RESULT_OK || target == null) {
                            return; // 用户在系统面板里取消，不需要再提示一次。
                        }
                        List<String> ids = pendingExportIds;
                        BackupTransfer.save(this, ids, target, new BackupTransfer.SaveListener() {
                            @Override
                            public void onSaved(int items) {
                                toast(getString(R.string.backup_export_saved, items));
                            }

                            @Override
                            public void onFailed(@NonNull String detail) {
                                toast(getString(R.string.backup_export_failed, detail));
                            }
                        });
                    });

    private final ActivityResultLauncher<Intent> importLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        Uri source = result.getData() == null ? null : result.getData().getData();
                        if (result.getResultCode() != RESULT_OK || source == null) {
                            return;
                        }
                        BackupTransfer.open(this, source, this::showImportPicker);
                    });

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
        FinmoriNav.bind(this, binding.bottomNav, FinmoriNav.Tab.SETTINGS);
        prefs = getSharedPreferences(PresetUnlock.PREFS_NAME, MODE_PRIVATE);
        PresetUnlock.migrate(prefs);
        binding.rowUpdate.setOnClickListener(v -> startActivity(new Intent(this, UpdatesActivity.class)));
        binding.rowPermissions.setOnClickListener(v -> startActivity(new Intent(this, SettingsCheckActivity.class)));
        binding.rowAccounts.setOnClickListener(v -> startActivity(new Intent(this, ProfileActivity.class)));
        binding.rowActivation.setOnClickListener(v -> showActivationDialog());
        refreshAppearance();
        binding.rowAppearance.setOnClickListener(v -> showAppearanceDialog());
        binding.rowBackupExport.setOnClickListener(v -> showExportDialog());
        binding.rowBackupImport.setOnClickListener(v -> startImportPick());
        binding.rowRunHistory.setOnClickListener(v ->
                startActivity(new Intent(this, RunHistoryActivity.class)));
        binding.rowTimetableWidget.setOnClickListener(v -> requestTimetableWidgetPin());
        refreshReminders();
        binding.rowReminders.setOnClickListener(v -> showReminderCenter());
    }

    /**
     * 请求桌面直接把课表小组件放上主屏。
     *
     * <p>能不能这样做得由桌面决定：{@code requestPinAppWidget} 在部分启动器上就是不支持，
     * 那种情况必须给出可操作的手动路径，否则点完毫无反应会被当成 bug。放置本身仍要用户确认，
     * 应用无法代劳。
     */
    private void requestTimetableWidgetPin() {
        AppWidgetManager manager = AppWidgetManager.getInstance(this);
        ComponentName provider = new ComponentName(this, TimetableWidgetProvider.class);
        if (!manager.isRequestPinAppWidgetSupported()) {
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.app_settings_widget_title)
                    .setMessage(R.string.widget_pin_unsupported)
                    .setPositiveButton(R.string.backup_confirm, null)
                    .show();
            return;
        }
        manager.requestPinAppWidget(provider, null, null);
        // 组件落地后马上挂上数据变更监听，改完课表桌面上就能跟着变。
        TimetableWidgetSync.attach(this);
        toast(getString(R.string.widget_pin_requested));
    }

    /** 导出：先让用户勾选段落，再由系统保存面板决定落到哪个文件。 */
    private void showExportDialog() {
        List<BackupCatalog.Section> sections = BackupCatalog.sections();
        String[] labels = new String[sections.size()];
        boolean[] checked = new boolean[sections.size()];
        for (int i = 0; i < sections.size(); i++) {
            BackupCatalog.Section section = sections.get(i);
            labels[i] = getString(section.label());
            checked[i] = section.defaultSelected();
        }
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.backup_export_title)
                .setMessage(R.string.backup_privacy_hint)
                .setMultiChoiceItems(labels, checked,
                        (dialog, which, isChecked) -> checked[which] = isChecked)
                .setNegativeButton(R.string.backup_cancel, null)
                .setPositiveButton(R.string.backup_confirm, (dialog, which) -> {
                    List<String> ids = selectedIds(sections, checked);
                    if (ids.isEmpty()) {
                        toast(getString(R.string.backup_none_selected));
                        return;
                    }
                    pendingExportIds = ids;
                    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType(JSON_MIME);
                    intent.putExtra(Intent.EXTRA_TITLE, BackupTransfer.suggestedFileName());
                    exportLauncher.launch(intent);
                })
                .show();
    }

    private void startImportPick() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(JSON_MIME);
        importLauncher.launch(intent);
    }

    /** 读到的备份里只有本机认识、且真的有数据的段落可选。 */
    private void showImportPicker(@NonNull BackupCodec.DecodeResult result) {
        if (!result.ok() || result.bundle() == null) {
            toast(getString(R.string.backup_import_failed, result.error()));
            return;
        }
        BackupBundle bundle = result.bundle();
        List<BackupCatalog.Section> present = new ArrayList<>();
        for (BackupCatalog.Section section : BackupCatalog.sections()) {
            if (bundle.has(section.id()) && bundle.get(section.id()).count() > 0) {
                present.add(section);
            }
        }
        if (present.isEmpty()) {
            toast(getString(R.string.backup_import_nothing));
            return;
        }
        String[] labels = new String[present.size()];
        boolean[] checked = new boolean[present.size()];
        for (int i = 0; i < present.size(); i++) {
            BackupCatalog.Section section = present.get(i);
            labels[i] = getString(R.string.backup_count_of_items, getString(section.label()),
                    bundle.get(section.id()).count());
            checked[i] = section.defaultSelected();
        }
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.backup_import_title)
                .setMultiChoiceItems(labels, checked,
                        (dialog, which, isChecked) -> checked[which] = isChecked)
                .setNegativeButton(R.string.backup_cancel, null)
                .setPositiveButton(R.string.backup_confirm, (dialog, which) ->
                        showImportConfirm(bundle, present, checked))
                .show();
    }

    /**
     * 二次确认：整段替换的段落必须点名，因为它们的本机数据会没了。
     *
     * <p>自建路线走「只补缺、同名保留本机」，所以单独说明，不和替换项混在一起。
     */
    private void showImportConfirm(BackupBundle bundle, List<BackupCatalog.Section> present,
                                   boolean[] checked) {
        List<String> ids = selectedIds(present, checked);
        if (ids.isEmpty()) {
            toast(getString(R.string.backup_none_selected));
            return;
        }
        StringBuilder replaced = new StringBuilder();
        StringBuilder filled = new StringBuilder();
        for (String id : ids) {
            BackupCatalog.Section section = BackupCatalog.byId(id);
            if (section == null) {
                continue;
            }
            StringBuilder target = BackupCatalog.policyOf(id) == BackupMerge.Policy.REPLACE
                    ? replaced : filled;
            if (target.length() > 0) {
                target.append("、");
            }
            target.append(getString(section.label()));
        }
        StringBuilder message = new StringBuilder();
        if (replaced.length() > 0) {
            message.append(getString(R.string.backup_import_replace_warning, replaced));
        }
        if (filled.length() > 0) {
            if (message.length() > 0) {
                message.append('\n');
            }
            message.append(getString(R.string.backup_import_keyed_hint));
        }
        if (!bundle.unknownNames(BackupCatalog.knownIds()).isEmpty()) {
            if (message.length() > 0) {
                message.append('\n');
            }
            message.append(getString(R.string.backup_import_unknown_sections));
        }
        if (message.length() == 0) {
            message.append(getString(R.string.backup_privacy_hint));
        }
        pendingBundle = bundle;
        pendingImportIds = ids;
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.backup_import_confirm_title)
                .setMessage(message)
                .setNegativeButton(R.string.backup_cancel, null)
                .setPositiveButton(R.string.backup_import_action_label, (dialog, which) ->
                        BackupTransfer.apply(this, pendingBundle, pendingImportIds,
                                new BackupTransfer.ApplyListener() {
                                    @Override
                                    public void onApplied(
                                            @NonNull List<BackupStore.SectionResult> results,
                                            @NonNull List<String> unknownSections) {
                                        int applied = 0;
                                        int skipped = 0;
                                        for (BackupStore.SectionResult item : results) {
                                            applied += item.applied();
                                            skipped += item.skipped() + item.dropped();
                                        }
                                        String text = getString(
                                                R.string.backup_import_done, applied, skipped);
                                        if (!unknownSections.isEmpty()) {
                                            text += "\n" + getString(
                                                    R.string.backup_import_unknown_sections);
                                        }
                                        toast(text);
                                    }

                                    @Override
                                    public void onFailed(@NonNull String detail) {
                                        toast(getString(R.string.backup_import_failed, detail));
                                    }
                                }))
                .show();
    }

    private static List<String> selectedIds(List<BackupCatalog.Section> sections,
                                            boolean[] checked) {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < sections.size() && i < checked.length; i++) {
            if (checked[i]) {
                ids.add(sections.get(i).id());
            }
        }
        return ids;
    }

    // ------------------------------------------------------------- 提醒中心

    /** 行副标题只说结论：几项会主动通知、几项已经排到下一次；一项都没有就直说。 */
    @Override protected void onResume() {
        super.onResume();
        // 导航用 REORDER_TO_FRONT 复用本页实例，别处改过提醒或外观后切回来必须重读。
        refreshReminders();
        refreshAppearance();
    }

    private void refreshReminders() {
        ReminderCenter.State state = reminderState();
        int firing = ReminderCenter.firingCount(state);
        binding.textRemindersValue.setText(firing == 0
                ? getString(R.string.app_settings_reminders_subtitle_none)
                : getString(R.string.app_settings_reminders_subtitle, firing,
                        ReminderCenter.scheduledCount(ReminderCenter.entries(state))));
    }

    private void showReminderCenter() {
        ReminderCenter.State state = reminderState();
        refreshReminders();
        LocalDate today = LocalDate.now();
        StringBuilder text = new StringBuilder(getString(R.string.app_settings_reminders_intro));
        for (ReminderCenter.Entry entry : ReminderCenter.entries(state)) {
            text.append('\n').append("· ")
                    .append(getString(kindLabelRes(entry.kind)))
                    .append('：').append(reminderDetail(entry, state, today));
        }
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.app_settings_reminders_title)
                .setMessage(text.toString())
                .setNegativeButton(R.string.campus_cancel, null)
                .setNeutralButton(R.string.reminder_center_open_study,
                        (dialog, which) -> startActivity(
                                new Intent(this, StudySettingsActivity.class)))
                .setPositiveButton(R.string.reminder_center_open_notifications,
                        (dialog, which) -> openNotificationSettings())
                .show();
    }

    /**
     * 各功能当前的真实状态：只问已经存在的计划器「下一次是什么时候」，
     * 中心自己不排闹钟，也不会因为展示过一次就改动任何设置。
     */
    private ReminderCenter.State reminderState() {
        LocalDate today = LocalDate.now();
        LocalDateTime now = LocalDateTime.now();
        List<TimetableCourse> courses = StudyTimetableStore.loadCourses(this);
        List<DayAdjustment> adjustments = StudyTimetableStore.loadAdjustments(this);
        LocalDate semesterStart = StudyTimetableStore.loadSemesterStart(this);
        ReminderCenter.State state = new ReminderCenter.State();
        state.classEnabled = StudyTimetableStore.loadClassReminderEnabled(this);
        state.semesterStartKnown = semesterStart != null;
        state.courseCount = courses.size();
        if (state.classEnabled) {
            ClassReminderPlanner.Reminder next = ClassReminderPlanner.next(courses,
                    StudyTimetableStore.loadSchedule(this), semesterStart,
                    StudyTimetableStore.loadTotalWeeks(this), now,
                    StudyTimetableStore.loadClassReminderLeadMinutes(this),
                    ClassReminderPlanner.DEFAULT_HORIZON_DAYS, adjustments);
            state.nextClassReminder = next == null ? null : next.remindAt;
        }
        List<CustomHomework> homework = CustomHomeworkStore.load(this);
        state.homeworkEnabled = CustomHomeworkStore.loadReminderEnabled(this);
        state.openHomeworkCount = homework.size();
        if (state.homeworkEnabled) {
            HomeworkReminderPlanner.Reminder next = HomeworkReminderPlanner.next(homework, now,
                    StudyTimetableStore.loadHomeworkReminderLeadMinutes(this),
                    HomeworkReminderPlanner.DEFAULT_HORIZON_DAYS);
            state.nextHomeworkReminder = next == null ? null : next.remindAt;
        }
        // 存储时已经清掉过期记录，所以这里的条数就是「还在等着生效」的条数。
        state.adjustmentCount = adjustments.size();
        DayAdjustment nextAdjustment = TimetableDay.nextAhead(adjustments, today);
        state.nextAdjustmentDate = nextAdjustment == null ? null : nextAdjustment.date();
        state.nextAdjustmentLabel = nextAdjustment == null ? null
                : DayAdjustmentLabel.label(nextAdjustment, courses, today);
        state.seasonManuallyPinned = StudyTimetableStore.seasonChosenManually(this);
        state.nextSeasonChange = StudySchedule.nextSeasonChange(today, state.seasonManuallyPinned);
        return state;
    }

    private String reminderDetail(@NonNull ReminderCenter.Entry entry,
                                  @NonNull ReminderCenter.State state,
                                  @NonNull LocalDate today) {
        switch (entry.kind) {
            case CLASS:
                if (entry.nextAt != null) {
                    return getString(R.string.reminder_status_fire,
                            ReminderCenter.momentLabel(entry.nextAt));
                }
                // 开关开着却没设开学日期，跟「没开」是两回事，用户要做的动作完全不同。
                return getString(state.classEnabled ? R.string.reminder_status_off_no_start
                        : R.string.reminder_status_off);
            case HOMEWORK:
                if (entry.nextAt != null) {
                    return getString(R.string.reminder_status_fire,
                            ReminderCenter.momentLabel(entry.nextAt));
                }
                return getString(entry.enabled ? R.string.reminder_status_nothing
                        : R.string.reminder_status_off);
            case ADJUSTMENT:
                return entry.nextDate == null
                        ? getString(R.string.reminder_status_adjust_none)
                        : getString(R.string.reminder_status_adjust_next, entry.pendingCount,
                                state.nextAdjustmentLabel);
            case SEASON:
                if (entry.nextDate == null) {
                    return getString(R.string.reminder_status_season_pinned);
                }
                return getString(R.string.reminder_status_season_next,
                        ReminderCenter.dateLabel(entry.nextDate, today),
                        getString(StudySchedule.seasonForMonth(entry.nextDate.getMonthValue())
                                == StudySchedule.SEASON_SUMMER
                                ? R.string.study_season_summer : R.string.study_season_winter));
            case UPDATE:
            default:
                return getString(R.string.reminder_status_update);
        }
    }

    private int kindLabelRes(@NonNull ReminderCenter.Kind kind) {
        switch (kind) {
            case HOMEWORK:
                return R.string.reminder_kind_homework;
            case ADJUSTMENT:
                return R.string.reminder_kind_adjustment;
            case SEASON:
                return R.string.reminder_kind_season;
            case UPDATE:
                return R.string.reminder_kind_update;
            case CLASS:
            default:
                return R.string.reminder_kind_class;
        }
    }

    /** 直达本应用的通知设置；ROM 不认这个入口时退回应用详情页，两级都失败才算没救。 */
    private void openNotificationSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
        } catch (ActivityNotFoundException missing) {
            try {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", getPackageName(), null)));
            } catch (ActivityNotFoundException stillMissing) {
                toast(getString(R.string.reminder_center_open_failed));
            }
        }
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
    }

    /** 行尾显示当前风格；切换会让系统重建本页，所以只在创建时刷新一次就够。 */
    private void refreshAppearance() {
        binding.textAppearanceValue.setText(
                getString(AppearancePreferences.labelFor(AppearancePreferences.load(this))));
    }

    private void showAppearanceDialog() {
        int[] modes = AppearancePreferences.MODES;
        String[] labels = new String[modes.length];
        int checked = 0;
        int current = AppearancePreferences.load(this);
        for (int i = 0; i < modes.length; i++) {
            labels[i] = getString(AppearancePreferences.labelFor(modes[i]));
            if (modes[i] == current) {
                checked = i;
            }
        }
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.appearance_dialog_title)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    int mode = modes[which];
                    AppearancePreferences.save(this, mode);
                    dialog.dismiss();
                    // 换模式会重建当前页面，无需在这里再刷新文案。
                    AppCompatDelegate.setDefaultNightMode(
                            AppearancePreferences.nightModeFor(mode));
                })
                .setNegativeButton(R.string.app_settings_activation_cancel, null)
                .show();
    }

    private void showActivationDialog() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        input.setSingleLine(true);
        int padding = dp(20);
        FrameLayout container = new FrameLayout(this);
        container.setPadding(padding, dp(8), padding, 0);
        container.addView(input);

        AlertDialog dialog = new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
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
                    PresetUnlock.activate(prefs);
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
