package org.localroute.app.ui;

import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import org.localroute.app.R;
import org.localroute.app.databinding.ActivityStudyBinding;
import org.localroute.app.databinding.ItemStudyHomeworkBinding;
import org.localroute.app.databinding.ItemTimetableCourseBinding;
import org.localroute.app.study.ChaoxingClient;
import org.localroute.app.study.HomeworkFilter;
import org.localroute.app.study.HomeworkItem;
import org.localroute.app.study.StudyHomeworkCache;
import org.localroute.app.study.StudySession;
import org.localroute.app.study.StudySessionStore;
import org.localroute.app.study.StudyTimetableStore;
import org.localroute.app.study.TimetableCourse;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 作业查询主页：底部在「作业」与「课表」之间切换，右上角进入独立设置页。
 *
 * <p>第一次进入且没有绑定信息时先打开学习通绑定页；作业只从学习通网页读取，
 * 不生成模拟数据。抓取结果按截止时间缓存，换账号后不会串数据。
 */
public class StudyActivity extends AppCompatActivity {

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<HomeworkItem> homework = new ArrayList<>();

    private ActivityStudyBinding binding;
    private StudySession session;
    private StudyHomeworkCache cache;
    private boolean fetching;
    private boolean loginLaunched;

    private ActivityResultLauncher<Intent> loginLauncher;
    private ActivityResultLauncher<Intent> settingsLauncher;
    private ActivityResultLauncher<String[]> pdfLauncher;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        binding = ActivityStudyBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        ViewCompat.setOnApplyWindowInsetsListener(binding.getRoot(), (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });

        loginLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    loginLaunched = false;
                    StudySession latest = StudySessionStore.load(this);
                    if (latest == null) {
                        session = null;
                        renderAccount();
                        if (result.getResultCode() != RESULT_OK) {
                            finish();
                        } else {
                            showUnbound();
                        }
                        return;
                    }
                    session = latest;
                    renderAccount();
                    loadCachedHomework();
                    startFetch();
                });
        pdfLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(), uri -> {
                    if (uri != null) {
                        StudyDialogs.showImportTimetable(this, uri, this::renderTimetable);
                    }
                });
        settingsLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    String previousUid = session == null ? null : session.uid();
                    session = StudySessionStore.load(this);
                    renderAccount();
                    renderTimetable();
                    if (session == null) {
                        homework.clear();
                        cache = null;
                        showUnbound();
                        return;
                    }
                    if (!session.uid().equals(previousUid)) {
                        homework.clear();
                        cache = null;
                        loadCachedHomework();
                        startFetch();
                    } else if (homework.isEmpty()) {
                        loadCachedHomework();
                    }
                });

        binding.buttonStudyBack.setOnClickListener(view -> finish());
        binding.buttonStudySettings.setOnClickListener(view ->
                settingsLauncher.launch(new Intent(this, StudySettingsActivity.class)));
        binding.tabHomework.setOnClickListener(view -> selectTab(false));
        binding.tabTimetable.setOnClickListener(view -> selectTab(true));
        binding.buttonHomeworkRefresh.setOnClickListener(view -> {
            if (session == null) {
                openLogin(false);
            } else {
                startFetch();
            }
        });
        binding.buttonImportTimetable.setOnClickListener(view -> {
            try {
                pdfLauncher.launch(new String[]{"application/pdf"});
            } catch (RuntimeException error) {
                Toast.makeText(this, R.string.study_import_pdf_no_picker,
                        Toast.LENGTH_LONG).show();
            }
        });
        binding.buttonAddCourse.setOnClickListener(view ->
                StudyDialogs.showCourseEditor(this, null, course -> renderTimetable()));
        binding.timetableGrid.setOnEmptySlotClickListener((weekday, period) ->
                StudyDialogs.showCourseEditor(this, null, weekday, period, period,
                        course -> renderTimetable()));

        session = StudySessionStore.load(this);
        renderAccount();
        renderTimetable();
        selectTab(false);

        if (session != null) {
            loadCachedHomework();
            if (cache == null
                    || System.currentTimeMillis() - cache.fetchedAt > 60_000L) {
                startFetch();
            }
        } else if (savedInstanceState == null) {
            openLogin(false);
        } else {
            showUnbound();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (binding == null) {
            return;
        }
        StudySession latest = StudySessionStore.load(this);
        if (latest == null) {
            if (session != null) {
                session = null;
                homework.clear();
                cache = null;
                renderAccount();
                showUnbound();
            }
        } else if (session == null || !session.uid().equals(latest.uid())
                || !session.cookie().equals(latest.cookie())) {
            session = latest;
            renderAccount();
        }
        renderTimetable();
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private void openLogin(boolean switchAccount) {
        if (loginLaunched) {
            return;
        }
        loginLaunched = true;
        Intent intent = new Intent(this, StudyLoginActivity.class);
        if (switchAccount) {
            intent.putExtra(StudyLoginActivity.EXTRA_SWITCH, true);
        }
        loginLauncher.launch(intent);
    }

    private void renderAccount() {
        if (binding == null) {
            return;
        }
        binding.textStudyAccount.setText(session == null
                ? getString(R.string.study_account_unbound)
                : session.displayName());
    }

    // ------------------------------------------------------------------ 作业

    private void loadCachedHomework() {
        if (session == null) {
            return;
        }
        cache = StudyHomeworkCache.load(this, session.uid());
        homework.clear();
        if (cache == null) {
            return;
        }
        applyHomework(cache.items);
        updateSummary(cache);
        if (!homework.isEmpty()) {
            showList();
        } else if (!cache.items.isEmpty()) {
            showRecentEmpty();
        }
    }

    /** 只保留未截止与近 7 天截止的作业，并按截止时间排序。 */
    private void applyHomework(List<HomeworkItem> items) {
        homework.clear();
        homework.addAll(HomeworkFilter.recent(items, System.currentTimeMillis()));
        renderHomeworkList();
    }

    private void startFetch() {
        if (session == null || fetching) {
            return;
        }
        fetching = true;
        binding.buttonHomeworkRefresh.setEnabled(false);
        binding.buttonHomeworkRefresh.setAlpha(0.5f);
        binding.textHomeworkSummary.setText(R.string.study_homework_summary_loading);
        if (homework.isEmpty()) {
            showLoading(getString(R.string.study_homework_summary_loading));
        }

        final StudySession current = session;
        final List<HomeworkItem> previous =
                cache == null ? new ArrayList<>() : new ArrayList<>(cache.items);
        executor.execute(() -> {
            ChaoxingClient client = new ChaoxingClient(current.cookie());
            try {
                ChaoxingClient.FetchResult result = client.fetchHomework(
                        (done, total, courseName) -> {
                            if (courseName == null || courseName.isEmpty()) {
                                return;
                            }
                            String message = getString(R.string.study_homework_summary_progress,
                                    courseName, done, total);
                            mainHandler.post(() -> {
                                if (fetching && !isFinishing() && !isDestroyed()) {
                                    binding.textHomeworkSummary.setText(message);
                                }
                            });
                        });
                StudyHomeworkCache snapshot = new StudyHomeworkCache(current.uid(),
                        result.fetchedAt, result.failedCourses,
                        HomeworkFilter.mergeCachedDeadlines(result.items, previous));
                StudyHomeworkCache.save(this, snapshot);
                StudySession refreshed = new StudySession(current.uid(), current.name(),
                        result.cookieHeader, current.boundAt());
                StudySessionStore.updateCookie(this, refreshed);
                mainHandler.post(() -> onFetchSuccess(snapshot, refreshed));
            } catch (ChaoxingClient.SessionExpiredException expired) {
                mainHandler.post(() -> onSessionExpired());
            } catch (ChaoxingClient.AntiSpiderException blocked) {
                mainHandler.post(() -> onFetchFailed(
                        getString(R.string.study_antispider_message), true));
            } catch (IOException | RuntimeException error) {
                String message = error.getMessage();
                if (message == null || message.isEmpty()) {
                    message = getString(R.string.study_homework_error_hint);
                }
                String finalMessage = message;
                mainHandler.post(() -> onFetchFailed(finalMessage, true));
            }
        });
    }

    private void onFetchSuccess(StudyHomeworkCache snapshot, StudySession refreshed) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        fetching = false;
        session = refreshed;
        cache = snapshot;
        binding.buttonHomeworkRefresh.setEnabled(true);
        binding.buttonHomeworkRefresh.setAlpha(1f);
        applyHomework(snapshot.items);
        updateSummary(snapshot);
        if (homework.isEmpty()) {
            if (snapshot.items.isEmpty()) {
                showEmptyState(R.string.study_homework_empty_title,
                        getString(R.string.study_homework_empty_hint),
                        R.string.study_homework_retry, this::startFetch);
            } else {
                showRecentEmpty();
            }
        } else {
            showList();
        }
    }

    private void showRecentEmpty() {
        showEmptyState(R.string.study_homework_recent_empty_title,
                getString(R.string.study_homework_recent_empty_hint),
                R.string.study_homework_retry, this::startFetch);
    }

    private void onSessionExpired() {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        fetching = false;
        binding.buttonHomeworkRefresh.setEnabled(true);
        binding.buttonHomeworkRefresh.setAlpha(1f);
        if (homework.isEmpty()) {
            showEmptyState(R.string.study_account_expired,
                    getString(R.string.study_account_expired_message),
                    R.string.study_account_rebind, () -> openLogin(true));
            binding.textHomeworkSummary.setText(R.string.study_account_expired);
            return;
        }
        showList();
        if (cache != null) {
            updateSummary(cache);
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.study_account_expired)
                .setMessage(R.string.study_account_expired_message)
                .setNegativeButton(R.string.campus_cancel, null)
                .setPositiveButton(R.string.study_account_rebind,
                        (dialog, which) -> openLogin(true))
                .show();
    }

    private void onFetchFailed(String message, boolean retryable) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        fetching = false;
        binding.buttonHomeworkRefresh.setEnabled(true);
        binding.buttonHomeworkRefresh.setAlpha(1f);
        // 有缓存时保留缓存视图；即使近一周过滤后为空，也显示「近一周暂无作业」而不是读取失败。
        if (!homework.isEmpty() || (cache != null && !cache.items.isEmpty())) {
            if (homework.isEmpty()) {
                showRecentEmpty();
            } else {
                showList();
            }
            if (cache != null) {
                updateSummary(cache);
            }
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            return;
        }
        showEmptyState(R.string.study_homework_error_title, message,
                R.string.study_homework_retry, retryable ? this::startFetch : null);
        binding.textHomeworkSummary.setText(R.string.study_homework_error_title);
    }

    private void renderHomeworkList() {
        binding.homeworkList.removeAllViews();
        LayoutInflater inflater = getLayoutInflater();
        for (HomeworkItem item : homework) {
            ItemStudyHomeworkBinding itemBinding = ItemStudyHomeworkBinding.inflate(
                    inflater, binding.homeworkList, false);
            bindHomework(itemBinding, item);
            binding.homeworkList.addView(itemBinding.getRoot());
        }
    }

    private void bindHomework(ItemStudyHomeworkBinding itemBinding, HomeworkItem item) {
        itemBinding.textHomeworkCourse.setText(item.courseName().isEmpty()
                ? getString(R.string.study_title) : item.courseName());
        itemBinding.textHomeworkTitle.setText(item.title().isEmpty()
                ? getString(R.string.study_homework_untitled) : item.title());
        itemBinding.textHomeworkDeadline.setText(item.deadlineLabel());

        HomeworkItem.Status status = effectiveStatus(item);
        String label;
        int fill;
        int textColor;
        switch (status) {
            case PENDING:
                label = getString(R.string.study_homework_status_pending);
                fill = R.color.study_state_pending_fill;
                textColor = R.color.campus_orange;
                break;
            case DONE:
                label = getString(R.string.study_homework_status_done);
                fill = R.color.campus_mint_pale;
                textColor = R.color.campus_mint_dark;
                break;
            case EXPIRED:
                label = getString(R.string.study_homework_status_expired);
                fill = R.color.study_state_expired_fill;
                textColor = R.color.campus_muted;
                break;
            default:
                label = item.statusText().isEmpty()
                        ? getString(R.string.study_homework_status_other)
                        : item.statusText();
                fill = R.color.study_state_other_fill;
                textColor = R.color.campus_blue;
                break;
        }
        itemBinding.textHomeworkStatus.setText(label);
        itemBinding.textHomeworkStatus.setTextColor(
                ContextCompat.getColor(this, textColor));
        GradientDrawable chip = new GradientDrawable();
        chip.setColor(ContextCompat.getColor(this, fill));
        chip.setCornerRadius(dp(20));
        itemBinding.textHomeworkStatus.setBackground(chip);

        itemBinding.textHomeworkTag.setVisibility(item.peerReview() ? View.VISIBLE : View.GONE);
        itemBinding.textHomeworkTag.setText(R.string.study_homework_peer_review);
    }

    /** 缓存里仍标记待完成、但截止时间已经过去的，显示为已截止。 */
    private HomeworkItem.Status effectiveStatus(HomeworkItem item) {
        if (item.status() == HomeworkItem.Status.PENDING
                && item.deadlineEpochMillis() > 0
                && item.deadlineEpochMillis() < System.currentTimeMillis()) {
            return HomeworkItem.Status.EXPIRED;
        }
        return item.status();
    }

    private void updateSummary(StudyHomeworkCache snapshot) {
        String time = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
                .format(new Date(snapshot.fetchedAt));
        binding.textHomeworkSummary.setText(snapshot.failedCourses > 0
                ? getString(R.string.study_homework_summary_failed_courses,
                time, homework.size(), snapshot.failedCourses)
                : getString(R.string.study_homework_summary_updated,
                time, homework.size()));
    }

    private void showLoading(String message) {
        binding.homeworkScroll.setVisibility(View.GONE);
        binding.stateHomeworkEmpty.setVisibility(View.GONE);
        binding.stateHomeworkLoading.setVisibility(View.VISIBLE);
        binding.textHomeworkLoading.setText(message);
    }

    private void showList() {
        binding.homeworkScroll.setVisibility(View.VISIBLE);
        binding.stateHomeworkEmpty.setVisibility(View.GONE);
        binding.stateHomeworkLoading.setVisibility(View.GONE);
    }

    private void showUnbound() {
        showEmptyState(R.string.study_homework_unbound_title,
                getString(R.string.study_homework_unbound_hint),
                R.string.study_account_bind, () -> openLogin(false));
        binding.textHomeworkSummary.setText(R.string.study_account_unbound);
    }

    private void showEmptyState(int titleRes, CharSequence hint, int actionLabelRes,
                                @Nullable Runnable action) {
        binding.homeworkScroll.setVisibility(View.GONE);
        binding.stateHomeworkLoading.setVisibility(View.GONE);
        binding.stateHomeworkEmpty.setVisibility(View.VISIBLE);
        binding.textHomeworkEmptyTitle.setText(titleRes);
        binding.textHomeworkEmptyHint.setText(hint);
        if (action == null) {
            binding.buttonHomeworkEmptyAction.setVisibility(View.GONE);
            return;
        }
        binding.buttonHomeworkEmptyAction.setVisibility(View.VISIBLE);
        binding.buttonHomeworkEmptyAction.setText(actionLabelRes);
        binding.buttonHomeworkEmptyAction.setOnClickListener(view -> action.run());
    }

    // ------------------------------------------------------------------ 课表

    private void renderTimetable() {
        if (binding == null) {
            return;
        }
        List<TimetableCourse> courses =
                StudyTimetableStore.sorted(StudyTimetableStore.loadCourses(this));
        int minutes = StudyTimetableStore.loadPeriodMinutes(this);
        binding.textTimetableSummary.setText(
                getString(R.string.study_timetable_summary, courses.size()));
        binding.textTimetableNote.setText(
                getString(R.string.study_timetable_note, minutes));
        binding.timetableGrid.setData(courses, minutes);

        boolean empty = courses.isEmpty();
        binding.scrollTimetable.setVisibility(empty ? View.GONE : View.VISIBLE);
        binding.textTimetableEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        binding.textCourseListTitle.setVisibility(empty ? View.GONE : View.VISIBLE);
        binding.timetableCourseList.setVisibility(empty ? View.GONE : View.VISIBLE);
        renderCourseRows(courses);
    }

    private void renderCourseRows(List<TimetableCourse> courses) {
        binding.timetableCourseList.removeAllViews();
        LayoutInflater inflater = getLayoutInflater();
        for (TimetableCourse course : courses) {
            ItemTimetableCourseBinding row = ItemTimetableCourseBinding.inflate(
                    inflater, binding.timetableCourseList, false);
            row.textCourseName.setText(course.name());
            StringBuilder meta = new StringBuilder()
                    .append(course.weekdayLabel())
                    .append(' ')
                    .append(course.periodLabel())
                    .append(" · ")
                    .append(course.weeks())
                    .append("周");
            if (!course.location().isEmpty()) {
                meta.append(" · ").append(course.location());
            } else if (!course.teacher().isEmpty()) {
                meta.append(" · ").append(course.teacher());
            }
            row.textCourseMeta.setText(meta.toString());
            row.getRoot().setOnClickListener(view ->
                    StudyDialogs.showCourseEditor(this, course,
                            updated -> renderTimetable()));
            row.buttonCourseDelete.setOnClickListener(view ->
                    StudyDialogs.confirmDeleteCourse(this, course, this::renderTimetable));
            binding.timetableCourseList.addView(row.getRoot());
        }
    }

    private void selectTab(boolean timetable) {
        binding.homeworkScreen.setVisibility(timetable ? View.GONE : View.VISIBLE);
        binding.timetableScreen.setVisibility(timetable ? View.VISIBLE : View.GONE);
        binding.tabHomework.setSelected(!timetable);
        binding.tabTimetable.setSelected(timetable);

        int active = ContextCompat.getColor(this, R.color.campus_mint_dark);
        int idle = ContextCompat.getColor(this, R.color.campus_muted);
        binding.iconTabHomework.setColorFilter(timetable ? idle : active);
        binding.iconTabTimetable.setColorFilter(timetable ? active : idle);
        binding.textTabHomework.setTextColor(timetable ? idle : active);
        binding.textTabTimetable.setTextColor(timetable ? active : idle);
        if (timetable) {
            renderTimetable();
        }
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
