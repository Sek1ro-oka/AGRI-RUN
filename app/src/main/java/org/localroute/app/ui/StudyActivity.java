package org.localroute.app.ui;

import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.NumberPicker;
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
import org.localroute.app.study.ClassReminderScheduler;
import org.localroute.app.study.ChaoxingClient;
import org.localroute.app.study.HomeworkFilter;
import org.localroute.app.study.HomeworkItem;
import org.localroute.app.study.SemesterCalendar;
import org.localroute.app.study.StudyHomeworkCache;
import org.localroute.app.study.StudySchedule;
import org.localroute.app.study.StudySession;
import org.localroute.app.study.StudySessionStore;
import org.localroute.app.study.StudyTimetableStore;
import org.localroute.app.study.TimetableCourse;
import org.localroute.app.study.TimetableText;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
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
    public static final String EXTRA_TIMETABLE = "open_timetable";

    /** 作业自动刷新间隔；逐课程读取请求较多，缩短间隔容易触发学习通安全验证。 */
    private static final long AUTO_REFRESH_INTERVAL_MS = 30L * 60L * 1000L;
    /** 横向滑动超过这个距离才翻到相邻周，避免误触课程卡片。 */
    private static final float WEEK_SWIPE_DP = 56f;
    /** 纵向移动超过这个距离就视为在滚动课表，不再判定为翻周。 */
    private static final float WEEK_SWIPE_VERTICAL_DP = 24f;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<HomeworkItem> homework = new ArrayList<>();

    private ActivityStudyBinding binding;
    private StudySession session;
    private StudyHomeworkCache cache;
    private boolean fetching;
    private boolean loginLaunched;
    private int totalWeeks = StudyTimetableStore.DEFAULT_TOTAL_WEEKS;
    private int currentWeek = 1;
    private int lastTeachingWeek;
    private boolean weekSwipeTracking;
    private float weekSwipeStartX;
    private float weekSwipeStartY;

    private ActivityResultLauncher<Intent> loginLauncher;
    private ActivityResultLauncher<Intent> settingsLauncher;
    private ActivityResultLauncher<Intent> verifyLauncher;
    private ActivityResultLauncher<Intent> jwLauncher;

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
        jwLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    if (result.getResultCode() == RESULT_OK) {
                        renderTimetable();
                    }
                });
        verifyLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    StudySession latest = StudySessionStore.load(this);
                    if (latest != null) {
                        session = latest;
                        renderAccount();
                    }
                    if (result.getResultCode() == RESULT_OK) {
                        startFetch();
                    } else if (homework.isEmpty()
                            && (cache == null || cache.items.isEmpty())) {
                        showVerificationState();
                    }
                });
        settingsLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    String previousUid = session == null ? null : session.uid();
                    session = StudySessionStore.load(this);
                    renderAccount();
                    if (result.getResultCode() == RESULT_OK && result.getData() != null
                            && result.getData().getBooleanExtra(
                            StudySettingsActivity.EXTRA_WEEK_CHANGED, false)) {
                        jumpToTeachingWeek();
                    }
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
        binding.bannerSemesterStart.setOnClickListener(view ->
                settingsLauncher.launch(new Intent(this, StudySettingsActivity.class)));
        binding.tabHomework.setOnClickListener(view -> selectTab(false));
        binding.tabTimetable.setOnClickListener(view -> selectTab(true));
        FinmoriMotion.press(binding.tabHomework);
        FinmoriMotion.press(binding.tabTimetable);
        binding.buttonHomeworkRefresh.setOnClickListener(view -> {
            if (session == null) {
                openLogin(false);
            } else {
                startFetch();
            }
        });
        binding.buttonImportTimetable.setOnClickListener(view ->
                jwLauncher.launch(new Intent(this, JwImportActivity.class)));
        binding.buttonAddCourse.setOnClickListener(view ->
                StudyDialogs.showCourseEditor(this, null, course -> renderTimetable()));
        binding.timetableGrid.setOnEmptySlotClickListener((weekday, period) ->
                StudyDialogs.showCourseEditor(this, null, weekday, period, period,
                        course -> renderTimetable()));
        binding.timetableGrid.setOnCourseClickListener(course ->
                StudyDialogs.showCourseEditor(this, course,
                        updated -> renderTimetable(), this::renderTimetable));
        binding.buttonPrevWeek.setOnClickListener(view -> shiftWeek(-1));
        binding.buttonNextWeek.setOnClickListener(view -> shiftWeek(1));
        binding.weekPicker.setOnClickListener(view -> showWeekPicker());

        session = StudySessionStore.load(this);
        renderAccount();
        initializeWeek();
        renderTimetable();
        boolean openTimetable = getIntent().getBooleanExtra(EXTRA_TIMETABLE, false);
        selectTab(openTimetable);

        if (session != null) {
            loadCachedHomework();
            if (cache == null
                    || System.currentTimeMillis() - cache.fetchedAt > AUTO_REFRESH_INTERVAL_MS) {
                startFetch();
            }
        } else if (savedInstanceState == null && !openTimetable) {
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
        followTeachingWeek();
        renderTimetable();
        // 课表或作息可能在设置页/导入页被改过，回到本页时重新排一次课前提醒。
        ClassReminderScheduler.update(this);
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
                List<HomeworkItem> merged = HomeworkFilter.mergeCachedDeadlines(
                        result.items, previous);
                if (result.blocked) {
                    merged = HomeworkFilter.mergeUnread(merged, previous);
                }
                StudyHomeworkCache snapshot = new StudyHomeworkCache(current.uid(),
                        result.fetchedAt, result.failedCourses, merged);
                StudyHomeworkCache.save(this, snapshot);
                StudySession refreshed = new StudySession(current.uid(), current.name(),
                        result.cookieHeader, current.boundAt());
                StudySessionStore.updateCookie(this, refreshed);
                mainHandler.post(() -> onFetchSuccess(snapshot, refreshed, result.blocked));
            } catch (ChaoxingClient.SessionExpiredException expired) {
                mainHandler.post(() -> onSessionExpired());
            } catch (ChaoxingClient.AntiSpiderException blocked) {
                mainHandler.post(this::onAntiSpider);
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

    private void onFetchSuccess(StudyHomeworkCache snapshot, StudySession refreshed,
                                boolean blocked) {
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
        if (blocked) {
            Toast.makeText(this, R.string.study_antispider_partial, Toast.LENGTH_LONG).show();
        }
    }

    private void showRecentEmpty() {
        int returned = cache == null ? 0 : cache.items.size();
        CharSequence hint = returned > 0
                ? getString(R.string.study_homework_recent_empty_hint_count, returned)
                : getString(R.string.study_homework_recent_empty_hint);
        showEmptyState(R.string.study_homework_recent_empty_title, hint,
                R.string.study_homework_retry, this::startFetch);
    }

    /** 学习通风控：有缓存时保留列表，并引导用户完成一次本机安全验证。 */
    private void onAntiSpider() {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        fetching = false;
        binding.buttonHomeworkRefresh.setEnabled(true);
        binding.buttonHomeworkRefresh.setAlpha(1f);
        if (!homework.isEmpty() || (cache != null && !cache.items.isEmpty())) {
            if (homework.isEmpty()) {
                showRecentEmpty();
            } else {
                showList();
            }
            if (cache != null) {
                updateSummary(cache);
            }
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.study_antispider_title)
                    .setMessage(R.string.study_antispider_verify_hint)
                    .setNegativeButton(R.string.campus_cancel, null)
                    .setPositiveButton(R.string.study_antispider_verify_action,
                            (dialog, which) -> openVerification())
                    .show();
            return;
        }
        showVerificationState();
    }

    private void showVerificationState() {
        showEmptyState(R.string.study_antispider_title,
                getString(R.string.study_antispider_verify_hint),
                R.string.study_antispider_verify_action, this::openVerification);
        binding.textHomeworkSummary.setText(R.string.study_antispider_title);
    }

    private void openVerification() {
        verifyLauncher.launch(new Intent(this, StudyVerifyActivity.class));
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
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
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
        // 刷新时整表重建会把 ScrollView 的滚动位置清零；先记下再在布局后恢复，
        // 避免用户正在看下半部分时突然回到顶部。
        int scrollY = binding.homeworkScroll.getScrollY();
        binding.homeworkList.removeAllViews();
        LayoutInflater inflater = getLayoutInflater();
        for (HomeworkItem item : homework) {
            ItemStudyHomeworkBinding itemBinding = ItemStudyHomeworkBinding.inflate(
                    inflater, binding.homeworkList, false);
            bindHomework(itemBinding, item);
            binding.homeworkList.addView(itemBinding.getRoot());
        }
        if (scrollY > 0) {
            ViewTreeObserver observer = binding.homeworkScroll.getViewTreeObserver();
            observer.addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
                @Override
                public void onGlobalLayout() {
                    ViewTreeObserver current = binding.homeworkScroll.getViewTreeObserver();
                    if (current.isAlive()) {
                        current.removeOnGlobalLayoutListener(this);
                    }
                    int max = Math.max(0, binding.homeworkList.getHeight()
                            - binding.homeworkScroll.getHeight());
                    binding.homeworkScroll.scrollTo(0, Math.min(scrollY, max));
                }
            });
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
            case SUBMITTED:
                label = item.statusText().isEmpty()
                        ? getString(R.string.study_homework_status_submitted)
                        : item.statusText();
                fill = R.color.study_state_other_fill;
                textColor = R.color.campus_blue;
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
        StudySchedule schedule = StudyTimetableStore.loadSchedule(this);
        int season = StudyTimetableStore.loadSeason(this);

        totalWeeks = StudyTimetableStore.loadTotalWeeks(this);
        int teachingWeek = StudyTimetableStore.currentTeachingWeek(this);
        lastTeachingWeek = teachingWeek;
        currentWeek = Math.max(1, Math.min(totalWeeks, currentWeek));
        List<TimetableCourse> weekCourses = new ArrayList<>();
        for (TimetableCourse course : courses) {
            if (TimetableText.activeInWeek(course.weeks(), currentWeek)) {
                weekCourses.add(course);
            }
        }

        boolean viewingCurrentWeek = teachingWeek > 0 && currentWeek == teachingWeek;
        binding.textWeekLabel.setText(getString(viewingCurrentWeek
                ? R.string.study_week_current_this : R.string.study_week_current,
                currentWeek));
        binding.textWeekRange.setText(getString(R.string.study_week_total, totalWeeks));
        // 边界也允许继续翻：满周后回到第 1 周，避免卡在两头。
        binding.buttonPrevWeek.setEnabled(true);
        binding.buttonPrevWeek.setAlpha(1f);
        binding.buttonNextWeek.setEnabled(true);
        binding.buttonNextWeek.setAlpha(1f);

        LocalDate today = LocalDate.now();
        LocalDate semesterStart = StudyTimetableStore.loadSemesterStart(this);
        binding.bannerSemesterStart.setVisibility(
                semesterStart == null ? View.VISIBLE : View.GONE);
        LocalDate weekStart = semesterStart == null ? null
                : SemesterCalendar.mondayOf(semesterStart).plusWeeks(currentWeek - 1L);
        int todayWeekday;
        if (weekStart == null) {
            // 没设置开学日期时不知道真实周次，沿用旧行为：高亮今天的星期。
            todayWeekday = today.getDayOfWeek().getValue();
        } else {
            todayWeekday = SemesterCalendar.mondayOf(today).equals(weekStart)
                    ? today.getDayOfWeek().getValue() : 0;
        }

        binding.textTimetableSummary.setText(
                getString(R.string.study_timetable_summary,
                        currentWeek, weekCourses.size(), courses.size()));
        binding.textTimetableNote.setText(getString(R.string.study_timetable_note,
                getString(season == StudySchedule.SEASON_SUMMER
                        ? R.string.study_season_summer : R.string.study_season_winter),
                schedule.startLabel(schedule.firstPeriod(StudySchedule.SEGMENT_MORNING)),
                schedule.endLabel(schedule.lastPeriod(StudySchedule.SEGMENT_MORNING)),
                schedule.startLabel(schedule.firstPeriod(StudySchedule.SEGMENT_AFTERNOON)),
                schedule.endLabel(schedule.lastPeriod(StudySchedule.SEGMENT_AFTERNOON)),
                schedule.startLabel(schedule.firstPeriod(StudySchedule.SEGMENT_EVENING)),
                schedule.endLabel(schedule.lastPeriod(StudySchedule.SEGMENT_EVENING))));
        binding.timetableGrid.setData(weekCourses, schedule, weekStart, todayWeekday);

        boolean empty = courses.isEmpty();
        binding.timetableGrid.setVisibility(empty ? View.GONE : View.VISIBLE);
        binding.textTimetableEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
    }

    /** 打开页面时定位到本学期真实教学周；没有开学日期时沿用上次查看的周。 */
    private void initializeWeek() {
        int teaching = StudyTimetableStore.currentTeachingWeek(this);
        currentWeek = teaching > 0 ? teaching : StudyTimetableStore.loadWeek(this);
        StudyTimetableStore.saveWeek(this, currentWeek);
    }

    /** 设置开学日期后跳到新的本周。 */
    private void jumpToTeachingWeek() {
        int teaching = StudyTimetableStore.currentTeachingWeek(this);
        if (teaching > 0) {
            currentWeek = teaching;
            StudyTimetableStore.saveWeek(this, currentWeek);
        }
    }

    /** 停留在「本周」时跨周后自动跟到新教学周；手动翻到其他周则不打扰。 */
    private void followTeachingWeek() {
        int teaching = StudyTimetableStore.currentTeachingWeek(this);
        if (teaching > 0 && teaching != currentWeek && currentWeek == lastTeachingWeek) {
            currentWeek = teaching;
            StudyTimetableStore.saveWeek(this, currentWeek);
        }
    }

    private void selectWeek(int week) {
        currentWeek = Math.max(1, Math.min(totalWeeks, week));
        StudyTimetableStore.saveWeek(this, currentWeek);
        renderTimetable();
    }

    /** 左右翻周，越过第一/最后一周年时绕回另一端。 */
    private void shiftWeek(int delta) {
        if (binding == null || isFinishing() || isDestroyed()) {
            return;
        }
        int next = SemesterCalendar.wrapWeek(currentWeek, delta, totalWeeks);
        if (next == currentWeek) {
            return;
        }
        currentWeek = next;
        StudyTimetableStore.saveWeek(this, currentWeek);
        renderTimetable();
        animateWeekChange(delta);
    }

    private void animateWeekChange(int delta) {
        View grid = binding.timetableGrid;
        if (grid.getVisibility() != View.VISIBLE) {
            return;
        }
        grid.setTranslationX(dp(18f) * (delta < 0 ? -1f : 1f));
        grid.setAlpha(0.4f);
        grid.animate().translationX(0f).alpha(1f).setDuration(160L).start();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        handleWeekSwipe(event);
        return super.dispatchTouchEvent(event);
    }

    /** 在课表页监听横向滑动；纵向滚动和普通点击保持原样。 */
    private void handleWeekSwipe(@NonNull MotionEvent event) {
        if (binding == null || binding.timetableScreen.getVisibility() != View.VISIBLE) {
            weekSwipeTracking = false;
            return;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                weekSwipeStartX = event.getX();
                weekSwipeStartY = event.getY();
                weekSwipeTracking = true;
                break;
            case MotionEvent.ACTION_MOVE: {
                if (!weekSwipeTracking) {
                    return;
                }
                float dx = Math.abs(event.getX() - weekSwipeStartX);
                float dy = Math.abs(event.getY() - weekSwipeStartY);
                if (dy > dp(WEEK_SWIPE_VERTICAL_DP) && dy > dx) {
                    weekSwipeTracking = false;
                }
                break;
            }
            case MotionEvent.ACTION_UP: {
                if (!weekSwipeTracking) {
                    return;
                }
                weekSwipeTracking = false;
                float dx = event.getX() - weekSwipeStartX;
                float dy = event.getY() - weekSwipeStartY;
                if (Math.abs(dx) >= dp(WEEK_SWIPE_DP)
                        && Math.abs(dx) > Math.abs(dy) * 1.5f) {
                    int direction = dx < 0 ? 1 : -1;
                    // 等这次触摸分发结束再重绘课表，避免在事件中途替换内容。
                    binding.getRoot().post(() -> shiftWeek(direction));
                }
                break;
            }
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_POINTER_DOWN:
                weekSwipeTracking = false;
                break;
            default:
                break;
        }
    }

    /** 选周弹窗：可直接跳到某一周，也可在这里调整学期总周数。 */
    private void showWeekPicker() {
        NumberPicker picker = new NumberPicker(this);
        picker.setMinValue(1);
        picker.setMaxValue(totalWeeks);
        picker.setValue(currentWeek);
        FrameLayout container = new FrameLayout(this);
        int padding = Math.round(20 * getResources().getDisplayMetrics().density);
        container.setPadding(padding, 0, padding, 0);
        container.addView(picker);
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.study_week_pick_title)
                .setView(container)
                .setPositiveButton(R.string.study_week_confirm,
                        (dialog, which) -> selectWeek(picker.getValue()))
                .setNeutralButton(R.string.study_week_pick_total,
                        (dialog, which) -> showTotalWeeksPicker())
                .setNegativeButton(R.string.campus_cancel, null)
                .show();
    }

    private void showTotalWeeksPicker() {
        NumberPicker picker = new NumberPicker(this);
        picker.setMinValue(StudyTimetableStore.MIN_TOTAL_WEEKS);
        picker.setMaxValue(StudyTimetableStore.MAX_TOTAL_WEEKS);
        picker.setValue(totalWeeks);
        FrameLayout container = new FrameLayout(this);
        int padding = Math.round(20 * getResources().getDisplayMetrics().density);
        container.setPadding(padding, 0, padding, 0);
        container.addView(picker);
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.study_week_total_title)
                .setMessage(R.string.study_week_total_message)
                .setView(container)
                .setPositiveButton(R.string.study_week_confirm, (dialog, which) -> {
                    StudyTimetableStore.saveTotalWeeks(this, picker.getValue());
                    renderTimetable();
                })
                .setNegativeButton(R.string.campus_cancel, null)
                .show();
    }

    private void selectTab(boolean timetable) {
        binding.textStudyTitle.setText(timetable ? R.string.fm_timetable : R.string.study_title);
        binding.homeworkScreen.setVisibility(timetable ? View.GONE : View.VISIBLE);
        binding.timetableScreen.setVisibility(timetable ? View.VISIBLE : View.GONE);
        binding.tabHomework.setSelected(!timetable);
        binding.tabTimetable.setSelected(timetable);

        int active = ContextCompat.getColor(this, R.color.fm_on_nav);
        int idle = ContextCompat.getColor(this, R.color.fm_nav_idle);
        binding.iconTabHomework.setColorFilter(timetable ? idle : active);
        binding.iconTabTimetable.setColorFilter(timetable ? active : idle);
        binding.textTabHomework.setTextColor(timetable ? idle : active);
        binding.textTabTimetable.setTextColor(timetable ? active : idle);
        if (timetable) {
            renderTimetable();
        }
        FinmoriMotion.reveal(timetable ? binding.timetableScreen : binding.homeworkScreen);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
