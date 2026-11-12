package org.localroute.app.ui;

import android.app.Activity;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import org.localroute.app.R;
import org.localroute.app.databinding.DialogStudyCourseBinding;
import org.localroute.app.databinding.DialogStudyImportBinding;
import org.localroute.app.study.StudySchedule;
import org.localroute.app.study.StudyTimetableStore;
import org.localroute.app.study.TimetableCourse;
import org.localroute.app.study.TimetableText;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/** 教务课表导入预览与单门课程编辑对话框；课表页与设置页共用。 */
public final class StudyDialogs {

    private static final String[] WEEKDAY_LABELS = {
            "周一", "周二", "周三", "周四", "周五", "周六", "周日"
    };

    private StudyDialogs() {
    }

    /**
     * 展示从教务系统读取到的课程预览，确认后覆盖或追加到课表。
     *
     * <p>只保存课程本身，不保存教务系统的登录状态；来源与学期仅用于预览说明。
     */
    public static void showImportPreview(@NonNull Activity activity,
                                         @NonNull List<TimetableCourse> imported,
                                         @Nullable String sourceLabel,
                                         @Nullable Runnable onImported) {
        DialogStudyImportBinding binding =
                DialogStudyImportBinding.inflate(LayoutInflater.from(activity));
        int existing = StudyTimetableStore.loadCourses(activity).size();
        binding.textImportFile.setText(sourceLabel == null ? "" : sourceLabel);
        binding.textImportSummary.setText(preview(activity, imported));
        binding.checkImportAppend.setVisibility(existing > 0 ? View.VISIBLE : View.GONE);
        updateImportHint(activity, binding, existing);
        binding.checkImportAppend.setOnCheckedChangeListener(
                (button, checked) -> updateImportHint(activity, binding, existing));

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.study_import_title)
                .setView(binding.getRoot())
                .setNegativeButton(R.string.campus_cancel, null)
                .setPositiveButton(R.string.study_import_confirm, null)
                .create();
        dialog.setOnShowListener(ignored ->
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                    boolean append = binding.checkImportAppend.isChecked();
                    List<TimetableCourse> courses = append
                            ? new ArrayList<>(StudyTimetableStore.loadCourses(activity))
                            : new ArrayList<>();
                    Set<String> keys = new HashSet<>();
                    for (TimetableCourse course : courses) {
                        keys.add(courseKey(course));
                    }
                    int added = 0;
                    for (TimetableCourse course : imported) {
                        if (!append || keys.add(courseKey(course))) {
                            courses.add(course);
                            added++;
                        }
                    }
                    StudyTimetableStore.saveCourses(activity,
                            StudyTimetableStore.sorted(courses));
                    Toast.makeText(activity, added == 0
                                    ? activity.getString(R.string.study_import_no_new)
                                    : activity.getString(R.string.study_import_result, added),
                            Toast.LENGTH_LONG).show();
                    dialog.dismiss();
                    if (onImported != null) {
                        onImported.run();
                    }
                }));
        dialog.show();
    }

    private static String preview(Activity activity, List<TimetableCourse> imported) {
        StringBuilder builder = new StringBuilder(activity.getString(
                R.string.study_import_count, imported.size()));
        int limit = Math.min(5, imported.size());
        for (int i = 0; i < limit; i++) {
            TimetableCourse course = imported.get(i);
            builder.append('\n')
                    .append(course.weekdayLabel()).append(' ')
                    .append(course.periodLabel()).append(' ')
                    .append(course.name());
            if (!course.location().isEmpty()) {
                builder.append(" · ").append(course.location());
            }
        }
        if (imported.size() > limit) {
            builder.append(activity.getString(R.string.study_import_more,
                    imported.size() - limit));
        }
        return builder.toString();
    }

    private static void updateImportHint(Activity activity, DialogStudyImportBinding binding,
                                         int existing) {
        if (existing <= 0) {
            binding.textImportReplaceHint.setVisibility(View.GONE);
            return;
        }
        binding.textImportReplaceHint.setVisibility(View.VISIBLE);
        binding.textImportReplaceHint.setText(binding.checkImportAppend.isChecked()
                ? activity.getString(R.string.study_import_append_hint, existing)
                : activity.getString(R.string.study_import_replace_hint, existing));
    }

    public static void showCourseEditor(@NonNull Activity activity,
                                        @Nullable TimetableCourse existing,
                                        @NonNull Consumer<TimetableCourse> onSaved) {
        showCourseEditor(activity, existing, 0, 0, 0, onSaved, null);
    }

    public static void showCourseEditor(@NonNull Activity activity,
                                        @Nullable TimetableCourse existing,
                                        @NonNull Consumer<TimetableCourse> onSaved,
                                        @Nullable Runnable onDeleted) {
        showCourseEditor(activity, existing, 0, 0, 0, onSaved, onDeleted);
    }

    /**
     * 打开课程编辑器。新建时可预填星期与节次，用于点击课表空白格添加事件；
     * 修改已有课程时提供删除入口，删除成功后回调 onDeleted。
     */
    public static void showCourseEditor(@NonNull Activity activity,
                                        @Nullable TimetableCourse existing,
                                        int presetWeekday, int presetStart, int presetEnd,
                                        @NonNull Consumer<TimetableCourse> onSaved) {
        showCourseEditor(activity, existing, presetWeekday, presetStart, presetEnd,
                onSaved, null);
    }

    public static void showCourseEditor(@NonNull Activity activity,
                                        @Nullable TimetableCourse existing,
                                        int presetWeekday, int presetStart, int presetEnd,
                                        @NonNull Consumer<TimetableCourse> onSaved,
                                        @Nullable Runnable onDeleted) {
        DialogStudyCourseBinding binding =
                DialogStudyCourseBinding.inflate(LayoutInflater.from(activity));
        ArrayAdapter<String> adapter = new ArrayAdapter<>(activity,
                android.R.layout.simple_spinner_item, WEEKDAY_LABELS);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        binding.spinnerCourseWeekday.setAdapter(adapter);

        if (existing != null) {
            binding.inputCourseName.setText(existing.name());
            binding.spinnerCourseWeekday.setSelection(
                    Math.max(0, Math.min(6, existing.weekday() - 1)));
            binding.inputCoursePeriod.setText(
                    TimetableText.formatPeriods(existing.startPeriod(), existing.endPeriod()));
            binding.inputCourseWeeks.setText(existing.weeks());
            binding.inputCourseLocation.setText(existing.location());
            binding.inputCourseTeacher.setText(existing.teacher());
        } else {
            if (presetWeekday >= 1 && presetWeekday <= 7) {
                binding.spinnerCourseWeekday.setSelection(presetWeekday - 1);
            }
            if (presetStart >= TimetableCourse.MIN_PERIOD) {
                int start = Math.min(TimetableCourse.MAX_PERIOD, presetStart);
                int end = Math.max(start,
                        Math.min(TimetableCourse.MAX_PERIOD, presetEnd));
                binding.inputCoursePeriod.setText(
                        TimetableText.formatPeriods(start, end));
            } else {
                binding.inputCoursePeriod.setText("1-2");
            }
            binding.inputCourseWeeks.setText("1-16");
        }

        // 显示节次对应的实际时间，方便边改边核对；时间跟随设置页的冬/夏令时作息。
        updateCourseTimeHint(activity, binding);
        binding.inputCoursePeriod.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence text, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable text) {
                updateCourseTimeHint(activity, binding);
            }
        });

        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle(existing == null ? R.string.study_course_add_title
                        : R.string.study_course_edit_title)
                .setView(binding.getRoot())
                .setNegativeButton(R.string.campus_cancel, null)
                .setPositiveButton(R.string.campus_save, null);
        if (existing != null && onDeleted != null) {
            builder.setNeutralButton(R.string.study_course_delete, null);
        }
        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(ignored -> {
            if (existing != null && onDeleted != null) {
                Button delete = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
                if (delete != null) {
                    delete.setTextColor(ContextCompat.getColor(activity,
                            R.color.campus_danger));
                    delete.setOnClickListener(view ->
                            confirmDeleteCourse(activity, existing, () -> {
                                dialog.dismiss();
                                onDeleted.run();
                            }));
                }
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                String name = binding.inputCourseName.getText().toString().trim();
                int[] periods = TimetableText.parsePeriods(
                        binding.inputCoursePeriod.getText().toString());
                String weeksRaw = binding.inputCourseWeeks.getText().toString().trim();
                String weeks = weeksRaw.isEmpty() ? "1-16"
                        : TimetableText.normalizeWeeks(weeksRaw);
                if (name.isEmpty() || periods == null || weeks == null) {
                    Toast.makeText(activity, R.string.study_course_invalid,
                            Toast.LENGTH_LONG).show();
                    return;
                }
                int weekday = binding.spinnerCourseWeekday.getSelectedItemPosition() + 1;
                String location = binding.inputCourseLocation.getText().toString().trim();
                String teacher = binding.inputCourseTeacher.getText().toString().trim();
                TimetableCourse course = existing == null
                        ? TimetableCourse.create(name, teacher, location,
                        weekday, periods[0], periods[1], weeks)
                        : new TimetableCourse(existing.id(), name, teacher, location,
                        weekday, periods[0], periods[1], weeks);
                List<TimetableCourse> courses =
                        new ArrayList<>(StudyTimetableStore.loadCourses(activity));
                boolean replaced = false;
                for (int i = 0; i < courses.size(); i++) {
                    if (courses.get(i).id().equals(course.id())) {
                        courses.set(i, course);
                        replaced = true;
                        break;
                    }
                }
                if (!replaced) {
                    courses.add(course);
                }
                StudyTimetableStore.saveCourses(activity,
                        StudyTimetableStore.sorted(courses));
                Toast.makeText(activity, R.string.study_course_saved,
                        Toast.LENGTH_SHORT).show();
                dialog.dismiss();
                onSaved.accept(course);
            });
        });
        dialog.show();
    }

    /** 节次变化时刷新「第 X-Y 节 · HH:MM–HH:MM（时令，每节 N 分钟）」提示；节次非法时隐藏。 */
    private static void updateCourseTimeHint(Activity activity,
                                             DialogStudyCourseBinding binding) {
        int[] periods = TimetableText.parsePeriods(
                binding.inputCoursePeriod.getText().toString());
        if (periods == null) {
            binding.textCourseTimeRange.setVisibility(View.GONE);
            return;
        }
        StudySchedule schedule = StudyTimetableStore.loadSchedule(activity);
        int season = StudyTimetableStore.loadSeason(activity);
        binding.textCourseTimeRange.setText(activity.getString(
                R.string.study_course_time_range,
                TimetableText.formatPeriods(periods[0], periods[1]),
                schedule.startLabel(periods[0]),
                schedule.endLabel(periods[1]),
                activity.getString(season == StudySchedule.SEASON_SUMMER
                        ? R.string.study_season_summer : R.string.study_season_winter),
                schedule.periodMinutes()));
        binding.textCourseTimeRange.setVisibility(View.VISIBLE);
    }

    public static void confirmDeleteCourse(@NonNull Activity activity,
                                           @NonNull TimetableCourse course,
                                           @NonNull Runnable onDeleted) {
        new AlertDialog.Builder(activity)
                .setTitle(R.string.study_course_delete_title)
                .setMessage(activity.getString(R.string.study_course_delete_message,
                        course.name()))
                .setNegativeButton(R.string.campus_cancel, null)
                .setPositiveButton(R.string.campus_route_delete, (dialog, which) -> {
                    List<TimetableCourse> courses =
                            new ArrayList<>(StudyTimetableStore.loadCourses(activity));
                    for (int i = 0; i < courses.size(); i++) {
                        if (courses.get(i).id().equals(course.id())) {
                            courses.remove(i);
                            break;
                        }
                    }
                    StudyTimetableStore.saveCourses(activity, courses);
                    Toast.makeText(activity, R.string.study_course_deleted,
                            Toast.LENGTH_SHORT).show();
                    onDeleted.run();
                })
                .show();
    }

    @NonNull
    private static String courseKey(TimetableCourse course) {
        return course.name() + '|' + course.weekday() + '|' + course.startPeriod()
                + '|' + course.endPeriod() + '|' + course.weeks() + '|'
                + course.location() + '|' + course.teacher();
    }
}
