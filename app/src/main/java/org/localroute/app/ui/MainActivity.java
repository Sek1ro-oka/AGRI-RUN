package org.localroute.app.ui;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.Lifecycle;

import org.localroute.app.BuildConfig;
import org.localroute.app.R;
import org.localroute.app.databinding.ActivityMainBinding;
import org.localroute.app.databinding.ItemHomeHomeworkRowBinding;
import org.localroute.app.databinding.ItemHomeUpcomingDayBinding;
import org.localroute.app.databinding.ItemHomeUpcomingRowBinding;
import org.localroute.app.diagnostics.DiagnosticLog;
import org.localroute.app.entry.HomeTarget;
import org.localroute.app.home.HomeAgenda;
import org.localroute.app.home.HomeGreeting;
import org.localroute.app.home.HomeUpcoming;
import org.localroute.app.home.HomeworkDue;
import org.localroute.app.overlay.OverlayHost;
import org.localroute.app.settings.OnboardingState;
import org.localroute.app.study.CustomHomework;
import org.localroute.app.study.CustomHomeworkStore;
import org.localroute.app.study.DayAdjustment;
import org.localroute.app.study.HomeworkItem;
import org.localroute.app.study.SemesterCalendar;
import org.localroute.app.study.StudyHomeworkCache;
import org.localroute.app.study.StudySchedule;
import org.localroute.app.study.StudySession;
import org.localroute.app.study.StudySessionStore;
import org.localroute.app.study.StudyTimetableStore;
import org.localroute.app.study.TimetableCourse;
import org.localroute.app.update.UpdateChecker;
import org.localroute.app.update.UpdateManager;
import org.localroute.app.update.UpdatePromptPolicy;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 首页：纯色问候 + 「接下来」跨天课程 + 待交作业。
 *
 * <p>它同时是启动页与桌面快捷方式的中转站（清单里只有本页 exported），所以 {@link HomeTarget}
 * 的转发留在这里；运行中与环境引导两张动态卡在「功能」页。
 */
public class MainActivity extends AppCompatActivity {

    private static final int HOME_HOMEWORK_LIMIT = 2;

    private ActivityMainBinding binding;
    private boolean onboardingOpen;
    private final ActivityResultLauncher<Intent> onboardingLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> onboardingOpen = false);
    /** 快捷入口或磁贴送进来的目标页；本页可见之后再跳，那时启动内部页面才不会被系统挡。 */
    private HomeTarget pendingTarget;
    private LocalDate today;
    private String[] weekdayLabels;
    private final List<View> upcomingRows = new ArrayList<>();
    private final List<UpcomingGroupViews> upcomingGroups = new ArrayList<>();
    private final List<View> homeworkRows = new ArrayList<>();
    private int upcomingHiddenBase;
    private int upcomingTrimmed;
    private String nextHiddenLabel;
    private int homeworkTotal;
    private int fitRetries;
    private final Runnable fitPass = this::fitHome;
    /** 冷启动静默检查只跑一次；旋转重建或从别的页面返回都不重复。 */
    private boolean updateCheckStarted;
    /** 带 saved state 重建（旋转等）时不再触发更新检查。 */
    private boolean restoredFromState;
    /** 跟着进程走：旋转销毁 Activity 时不打断已经点下的下载线路探测。 */
    private static final ExecutorService UPDATE_EXECUTOR =
            Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "home-update");
                thread.setDaemon(true);
                return thread;
            });
    private final Handler updateHandler = new Handler(Looper.getMainLooper());

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        onboardingOpen = savedInstanceState != null && savedInstanceState.getBoolean("onboarding_open", false);
        restoredFromState = savedInstanceState != null;
        DiagnosticLog.init(getApplicationContext());
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        ViewCompat.setOnApplyWindowInsetsListener(binding.getRoot(), (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        weekdayLabels = getResources().getStringArray(R.array.home_weekday_labels);
        binding.buttonHomeCalendar.setOnClickListener(v -> HomeTarget.TIMETABLE.open(this));
        binding.buttonHomeImport.setOnClickListener(v -> openJwImport());
        binding.buttonHomeworkEmpty.setOnClickListener(v -> onHomeworkEmptyAction());
        binding.buttonHomeworkAll.setOnClickListener(v -> HomeTarget.STUDY.open(this));
        FinmoriMotion.press(binding.buttonHomeCalendar);
        FinmoriMotion.press(binding.buttonHomeImport);
        FinmoriMotion.press(binding.buttonHomeworkEmpty);
        FinmoriMotion.press(binding.buttonHomeworkAll);
        FinmoriNav.bind(this, binding.bottomNav, FinmoriNav.Tab.HOME);
        OverlayHost.get(this).restore();
        // 只认冷启动那一次：进程被杀后恢复时不该把用户又甩进别的页面。
        pendingTarget = HomeTarget.from(savedInstanceState == null
                ? getIntent().getStringExtra(HomeTarget.EXTRA_TARGET)
                : savedInstanceState.getString("pending_home_target"));
    }

    @Override protected void onResume() {
        super.onResume();
        if (OnboardingState.shouldShow(this)) {
            if (!onboardingOpen) {
                onboardingOpen = true;
                onboardingLauncher.launch(new Intent(this, OnboardingActivity.class));
            }
            return;
        }
        // 绑定、导入、抓作业都发生在别的页面，回来必须重读，否则首页还挂着旧数据。
        render();
        if (pendingTarget != null) {
            // 快捷入口直接去目标页，不在跳转途中弹更新；回到首页后的这次 onResume 再静默检查。
            HomeTarget target = pendingTarget;
            pendingTarget = null;
            target.open(this);
            return;
        }
        startUpdateCheckIfNeeded();
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        outState.putBoolean("onboarding_open", onboardingOpen);
        if (pendingTarget != null) outState.putString("pending_home_target", pendingTarget.key());
        super.onSaveInstanceState(outState);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        pendingTarget = HomeTarget.from(intent.getStringExtra(HomeTarget.EXTRA_TARGET));
    }

    @Override protected void onDestroy() {
        updateHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    /** 冷启动静默查一次：不转圈、不挡首页，失败只进诊断日志。 */
    private void startUpdateCheckIfNeeded() {
        if (updateCheckStarted || restoredFromState) {
            return;
        }
        updateCheckStarted = true;
        UPDATE_EXECUTOR.execute(() -> {
            try {
                UpdateChecker.Release release = UpdateChecker.fetchLatest();
                updateHandler.post(() -> onUpdateChecked(release));
            } catch (IOException | RuntimeException error) {
                DiagnosticLog.warn("启动更新检查失败："
                        + (error.getMessage() == null ? error.getClass().getSimpleName()
                        : error.getMessage()));
            }
        });
    }

    private void onUpdateChecked(@NonNull UpdateChecker.Release release) {
        if (isFinishing() || isDestroyed() || !hasWindowFocus()
                || !getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED)
                || onboardingOpen || pendingTarget != null) {
            // 用户已经离开首页（或页面正在交接）：不打扰，下次冷启动重新检查。
            return;
        }
        if (!UpdatePromptPolicy.shouldPrompt(release.versionName(), BuildConfig.VERSION_NAME,
                UpdateManager.promptedVersion(this))) {
            return;
        }
        if (UpdateManager.isDownloadActive(this)) {
            // 正在下载时别叠加第二个任务；这次结果留给下载结束后的下一次冷启动。
            return;
        }
        String pendingVersion = UpdateManager.pendingVersion(this);
        if (!pendingVersion.isEmpty()
                && !UpdateChecker.isNewer(release.versionName(), pendingVersion)) {
            // 同一版本已经在下载/下载过：用户已经做过选择，不再自动提醒。
            UpdateManager.markPrompted(this, release.versionName());
            return;
        }
        DiagnosticLog.info("启动更新检查：发现新版本 " + release.versionName());
        UpdateDialogs.showFound(this, release, true, () -> downloadFromPrompt(release));
    }

    /** 启动弹窗里的「下载并安装」：直接交给系统下载器，进度在通知与更新页看。 */
    private void downloadFromPrompt(@NonNull UpdateChecker.Release release) {
        Toast.makeText(this, R.string.home_update_preparing, Toast.LENGTH_SHORT).show();
        UPDATE_EXECUTOR.execute(() -> {
            try {
                if (UpdateManager.isDownloadActive(getApplicationContext())) {
                    return;
                }
                UpdateManager.startDownload(getApplicationContext(), release);
                updateHandler.post(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    Toast.makeText(this, R.string.home_update_started,
                            Toast.LENGTH_LONG).show();
                });
            } catch (IOException | RuntimeException error) {
                updateHandler.post(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    String detail = error.getMessage();
                    if (detail == null || detail.isEmpty()) {
                        detail = "—";
                    }
                    Toast.makeText(this, getString(R.string.home_update_start_failed, detail),
                            Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void render() {
        today = LocalDate.now();

        StudySession session = StudySessionStore.load(this);
        List<HomeworkDue.Task> tasks = loadTasks(session);
        List<TimetableCourse> courses = StudyTimetableStore.loadCourses(this);
        StudySchedule schedule = StudyTimetableStore.loadSchedule(this);
        LocalDate semesterStart = StudyTimetableStore.loadSemesterStart(this);
        int totalWeeks = StudyTimetableStore.loadTotalWeeks(this);
        List<DayAdjustment> adjustments = StudyTimetableStore.loadAdjustments(this);
        LocalTime clock = LocalTime.now();
        int nowMinutes = clock.getHour() * 60 + clock.getMinute();

        HomeUpcoming.Feed feed = HomeUpcoming.of(courses, schedule, semesterStart, totalWeeks,
                adjustments, today, nowMinutes, HomeUpcoming.DEFAULT_LIMIT);
        List<HomeworkDue.Item> due = HomeworkDue.upcoming(tasks, today, HomeworkDue.HORIZON_DAYS);
        int week = SemesterCalendar.teachingWeek(semesterStart, today, totalWeeks);

        renderHero(session, feed.todayTotal(), due.size(), feed.notImported());
        renderUpcoming(feed, week, nowMinutes);
        renderHomework(due, session);
        scheduleHomeFit();
    }

    /** 学习通缓存与自建作业合成同一份待办；截止时刻为 0 的由 HomeworkDue 排到末尾。 */
    private List<HomeworkDue.Task> loadTasks(StudySession session) {
        List<HomeworkDue.Task> tasks = new ArrayList<>();
        if (session != null) {
            StudyHomeworkCache cache = StudyHomeworkCache.load(this, session.uid());
            if (cache != null) {
                for (HomeworkItem item : cache.items) {
                    tasks.add(new HomeworkDue.Task(item.title(), item.courseName(),
                            item.deadlineEpochMillis(), item.status()));
                }
            }
        }
        for (CustomHomework item : CustomHomeworkStore.load(this)) {
            tasks.add(new HomeworkDue.Task(item.title(), getString(R.string.home_task_custom),
                    item.deadlineEpochMillis(), HomeworkItem.Status.PENDING));
        }
        return tasks;
    }

    private void renderHero(StudySession session, int courseCount, int homeworkCount,
                            boolean notImported) {
        HomeGreeting.Phase phase = HomeGreeting.phaseOf(LocalTime.now().getHour());
        int greetRes;
        switch (phase) {
            case MORNING:
                greetRes = R.string.home_greet_morning;
                break;
            case DAY:
                greetRes = R.string.home_greet_day;
                break;
            default:
                greetRes = R.string.home_greet_night;
                break;
        }
        String name = session != null && !session.name().isEmpty()
                ? session.name() : getString(R.string.home_greet_guest);
        binding.textHomeGreeting.setText(getString(greetRes, name));
        // 课程看教务导入、作业看学习通绑定，两个数据源缺哪个就说哪个。
        int subRes;
        if (notImported && session == null && homeworkCount == 0) {
            subRes = R.string.home_greet_unbound_all;
        } else if (notImported) {
            subRes = R.string.home_greet_no_timetable;
        } else if (session == null && homeworkCount == 0) {
            subRes = R.string.home_greet_unbound;
        } else {
            binding.textHomeGreetingSub.setText(getString(R.string.home_greet_summary,
                    courseCount, homeworkCount));
            return;
        }
        binding.textHomeGreetingSub.setText(subRes);
    }

    private void renderUpcoming(@NonNull HomeUpcoming.Feed feed, int week, int nowMinutes) {
        binding.agendaList.removeAllViews();
        upcomingRows.clear();
        upcomingGroups.clear();
        upcomingTrimmed = 0;
        upcomingHiddenBase = feed.nextHiddenCount();
        nextHiddenLabel = feed.nextHiddenDate() == null ? null
                : nextHiddenDayLabel(feed.nextHiddenDate());
        binding.textUpcomingMeta.setText(week > 0
                ? getString(R.string.home_upcoming_meta_week, week,
                StudySchedule.formatMinutes(nowMinutes))
                : getString(R.string.home_upcoming_meta,
                StudySchedule.formatMinutes(nowMinutes)));

        boolean empty = feed.empty();
        binding.agendaList.setVisibility(empty ? View.GONE : View.VISIBLE);
        binding.agendaEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (empty) {
            renderUpcomingEmpty(feed);
            binding.textHomeAgendaCount.setText("");
            binding.textHomeAgendaCount.setVisibility(View.GONE);
            return;
        }

        LayoutInflater inflater = LayoutInflater.from(this);
        for (HomeUpcoming.DayGroup group : feed.groups()) {
            ItemHomeUpcomingDayBinding header = ItemHomeUpcomingDayBinding.inflate(
                    inflater, binding.agendaList, false);
            header.textUpcomingDayTitle.setText(dayTitle(group.date()));
            header.textUpcomingDayCount.setText(dayCountLabel(group));
            int titleColor = group.closed() ? R.color.fm_warning
                    : group.date().equals(today) ? R.color.fm_link : R.color.fm_text;
            int countColor = group.closed() ? R.color.fm_warning : R.color.fm_text_secondary;
            header.textUpcomingDayTitle.setTextColor(ContextCompat.getColor(this, titleColor));
            header.textUpcomingDayCount.setTextColor(ContextCompat.getColor(this, countColor));
            binding.agendaList.addView(header.getRoot());
            UpcomingGroupViews groupViews = new UpcomingGroupViews(header.getRoot(), group.closed());
            for (HomeAgenda.Item item : group.items()) {
                ItemHomeUpcomingRowBinding row = ItemHomeUpcomingRowBinding.inflate(
                        inflater, binding.agendaList, false);
                bindUpcomingRow(row, item);
                binding.agendaList.addView(row.getRoot());
                groupViews.rows.add(row.getRoot());
                upcomingRows.add(row.getRoot());
            }
            upcomingGroups.add(groupViews);
        }
        updateUpcomingFooter();
    }

    private void renderUpcomingEmpty(@NonNull HomeUpcoming.Feed feed) {
        binding.agendaList.setVisibility(View.GONE);
        binding.agendaEmpty.setVisibility(View.VISIBLE);
        binding.buttonHomeImport.setVisibility(View.GONE);
        if (feed.notImported()) {
            binding.textHomeEmptyTitle.setText(R.string.home_agenda_no_timetable);
            binding.buttonHomeImport.setText(R.string.home_agenda_import_action);
            binding.buttonHomeImport.setVisibility(View.VISIBLE);
        } else if (feed.todayClosed()) {
            binding.textHomeEmptyTitle.setText(R.string.home_upcoming_today_closed);
        } else if (feed.todayFinished() > 0) {
            binding.textHomeEmptyTitle.setText(R.string.home_agenda_all_done);
        } else {
            binding.textHomeEmptyTitle.setText(R.string.home_upcoming_empty);
        }
    }

    private void bindUpcomingRow(@NonNull ItemHomeUpcomingRowBinding row,
                                 @NonNull HomeAgenda.Item item) {
        row.textUpcomingTime.setText(item.timeLabel());
        row.textUpcomingTitle.setText(item.title());
        row.textUpcomingMeta.setText(item.subtitle());
        row.textUpcomingMeta.setVisibility(item.subtitle().isEmpty() ? View.GONE : View.VISIBLE);

        int tagText;
        int tagColor = R.color.fm_muted;
        boolean solid = false;
        switch (item.classState()) {
            case LIVE:
                tagText = R.string.home_class_live;
                tagColor = R.color.fm_link;
                solid = true;
                break;
            case DONE:
                tagText = R.string.home_class_done;
                break;
            default:
                tagText = R.string.home_class_upcoming;
                break;
        }
        row.textUpcomingTag.setText(tagText);
        row.textUpcomingTag.setTextColor(ContextCompat.getColor(this, tagColor));
        row.textUpcomingTag.setBackgroundResource(solid
                ? R.drawable.bg_home_tag_solid : R.drawable.bg_home_tag_outline);

        boolean evening = item.minutes() >= 18 * 60;
        int tone = ContextCompat.getColor(this, evening ? R.color.fm_warning : R.color.fm_link);
        row.viewUpcomingBar.setBackgroundTintList(ColorStateList.valueOf(tone));
        row.textUpcomingTime.setTextColor(ContextCompat.getColor(this,
                evening ? R.color.fm_warning : R.color.fm_text));
        row.getRoot().setContentDescription(item.timeLabel() + " " + item.title()
                + " " + getString(tagText));
        row.getRoot().setOnClickListener(v -> HomeTarget.TIMETABLE.open(this));
        FinmoriMotion.press(row.getRoot());
    }

    @NonNull
    private String dayTitle(@NonNull LocalDate day) {
        long offset = day.toEpochDay() - today.toEpochDay();
        String weekday = weekdayLabels[day.getDayOfWeek().getValue() - 1];
        if (offset == 0) {
            return getString(R.string.home_upcoming_today, weekday, day.getDayOfMonth());
        }
        if (offset == 1) {
            return getString(R.string.home_upcoming_tomorrow, weekday, day.getDayOfMonth());
        }
        if (day.getYear() == today.getYear() && day.getMonthValue() == today.getMonthValue()) {
            return getString(R.string.home_upcoming_weekday, weekday, day.getDayOfMonth());
        }
        return getString(R.string.home_upcoming_other_month, day.getMonthValue(),
                day.getDayOfMonth(), weekday);
    }

    @NonNull
    private String dayCountLabel(@NonNull HomeUpcoming.DayGroup group) {
        if (group.closed()) {
            return getString(R.string.home_upcoming_closed);
        }
        if (group.date().equals(today)) {
            return group.finished() > 0
                    ? getString(R.string.home_upcoming_today_counts, group.finished(),
                    group.remaining())
                    : getString(R.string.home_upcoming_today_remaining, group.remaining());
        }
        return getString(R.string.home_upcoming_day_count, group.remaining());
    }

    @NonNull
    private String nextHiddenDayLabel(@NonNull LocalDate date) {
        long offset = date.toEpochDay() - today.toEpochDay();
        if (offset == 0) {
            return getString(R.string.home_day_today);
        }
        if (offset == 1) {
            return getString(R.string.home_day_tomorrow);
        }
        String weekday = weekdayLabels[date.getDayOfWeek().getValue() - 1];
        if (date.getYear() == today.getYear() && date.getMonthValue() == today.getMonthValue()) {
            return getString(R.string.home_upcoming_next_weekday, weekday);
        }
        return getString(R.string.home_upcoming_next_date, date.getMonthValue(),
                date.getDayOfMonth());
    }

    private void updateUpcomingFooter() {
        int hidden = upcomingHiddenBase + upcomingTrimmed;
        if (hidden <= 0) {
            binding.textHomeAgendaCount.setText("");
            binding.textHomeAgendaCount.setVisibility(View.GONE);
            return;
        }
        binding.textHomeAgendaCount.setVisibility(View.VISIBLE);
        if (upcomingTrimmed > 0) {
            binding.textHomeAgendaCount.setText(R.string.home_upcoming_more_trimmed);
        } else {
            binding.textHomeAgendaCount.setText(getString(R.string.home_upcoming_more,
                    nextHiddenLabel, hidden));
        }
    }

    /** 待交作业只占两行；没有内容时保留空态卡，不整块消失。 */
    private void renderHomework(@NonNull List<HomeworkDue.Item> due,
                                @Nullable StudySession session) {
        binding.homeworkList.removeAllViews();
        homeworkRows.clear();
        homeworkTotal = due.size();
        binding.cardHomework.setVisibility(View.VISIBLE);
        boolean empty = due.isEmpty();
        binding.homeworkList.setVisibility(empty ? View.GONE : View.VISIBLE);
        binding.homeworkEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        binding.buttonHomeworkAll.setVisibility(empty ? View.GONE : View.VISIBLE);
        if (empty) {
            binding.textHomeworkSection.setText(R.string.home_homework_section);
            boolean unbound = session == null;
            binding.textHomeworkEmpty.setText(unbound
                    ? R.string.home_bind_title : R.string.home_homework_empty_title);
            binding.buttonHomeworkEmpty.setText(unbound
                    ? R.string.home_bind_action : R.string.home_homework_view_action);
            binding.textHomeworkFoot.setText("");
            binding.textHomeworkFoot.setVisibility(View.GONE);
            return;
        }

        binding.textHomeworkSection.setText(getString(R.string.home_homework_header, homeworkTotal));
        LayoutInflater inflater = LayoutInflater.from(this);
        int limit = Math.min(HOME_HOMEWORK_LIMIT, due.size());
        for (int i = 0; i < limit; i++) {
            ItemHomeHomeworkRowBinding row = ItemHomeHomeworkRowBinding.inflate(
                    inflater, binding.homeworkList, false);
            bindHomeworkRow(row, due.get(i));
            binding.homeworkList.addView(row.getRoot());
            homeworkRows.add(row.getRoot());
        }
        updateHomeworkFooter();
    }

    private void bindHomeworkRow(@NonNull ItemHomeHomeworkRowBinding row,
                                 @NonNull HomeworkDue.Item item) {
        row.textHomeworkTitle.setText(item.title());
        String due = homeworkDueLabel(item);
        row.textHomeworkDue.setText(due);
        row.viewHomeworkDot.setBackgroundTintList(ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.fm_warning)));
        row.getRoot().setContentDescription(item.title() + " " + due);
        row.getRoot().setOnClickListener(v -> HomeTarget.STUDY.open(this));
        FinmoriMotion.press(row.getRoot());
    }

    @NonNull
    private String homeworkDueLabel(@NonNull HomeworkDue.Item item) {
        if (item.undated()) {
            return getString(R.string.home_task_undated);
        }
        return dayLabel(item.dayOffset()) + " " + item.timeLabel();
    }

    private void updateHomeworkFooter() {
        int hidden = homeworkTotal - homeworkRows.size();
        if (hidden <= 0) {
            binding.textHomeworkFoot.setText("");
            binding.textHomeworkFoot.setVisibility(View.GONE);
            return;
        }
        binding.textHomeworkFoot.setText(getString(R.string.home_homework_more, hidden));
        binding.textHomeworkFoot.setVisibility(View.VISIBLE);
    }

    private void scheduleHomeFit() {
        fitRetries = 0;
        binding.homeScroll.removeCallbacks(fitPass);
        binding.homeScroll.post(fitPass);
    }

    /**
     * 固定六节 + 两条作业是目标；只在屏幕真的放不下时从尾部收课程，再收作业。
     * 被收掉的条数写进底部「还有 N 节/份」，而不是让内容被裁掉或整页被迫滚动。
     */
    private void fitHome() {
        if (binding == null) {
            return;
        }
        int viewport = binding.homeScroll.getHeight();
        int content = binding.homeContent.getHeight();
        if (viewport <= 0 || (content <= 0
                && (!upcomingRows.isEmpty() || !homeworkRows.isEmpty()))) {
            if (fitRetries < 3) {
                fitRetries++;
                binding.homeScroll.post(fitPass);
            }
            return;
        }
        if (content <= viewport) {
            fitRetries = 0;
            return;
        }
        if (!upcomingRows.isEmpty()) {
            trimLastUpcomingRow();
            updateUpcomingFooter();
            binding.homeScroll.post(fitPass);
            return;
        }
        if (!homeworkRows.isEmpty()) {
            trimLastHomeworkRow();
            updateHomeworkFooter();
            binding.homeScroll.post(fitPass);
            return;
        }
        fitRetries = 0;
    }

    private void trimLastUpcomingRow() {
        if (upcomingRows.isEmpty()) {
            return;
        }
        View row = upcomingRows.remove(upcomingRows.size() - 1);
        binding.agendaList.removeView(row);
        upcomingTrimmed++;
        for (int i = upcomingGroups.size() - 1; i >= 0; i--) {
            UpcomingGroupViews group = upcomingGroups.get(i);
            if (group.rows.remove(row)) {
                if (group.rows.isEmpty() && !group.closed) {
                    binding.agendaList.removeView(group.header);
                    upcomingGroups.remove(i);
                }
                return;
            }
        }
    }

    private void trimLastHomeworkRow() {
        if (homeworkRows.isEmpty()) {
            return;
        }
        View row = homeworkRows.remove(homeworkRows.size() - 1);
        binding.homeworkList.removeView(row);
    }

    private String dayLabel(int dayOffset) {
        if (dayOffset == 0) return getString(R.string.home_day_today);
        if (dayOffset == 1) return getString(R.string.home_day_tomorrow);
        LocalDate day = today.plusDays(dayOffset);
        return day.getMonthValue() + "-" + day.getDayOfMonth();
    }

    private static final class UpcomingGroupViews {
        final View header;
        final boolean closed;
        final List<View> rows = new ArrayList<>();

        UpcomingGroupViews(View header, boolean closed) {
            this.header = header;
            this.closed = closed;
        }
    }

    /** 课表统一走教务导入这条既有链路，不在首页另造一套绑定。 */
    private void openJwImport() {
        startActivity(new Intent(this, JwImportActivity.class));
    }

    private void onHomeworkEmptyAction() {
        if (StudySessionStore.load(this) == null) {
            startActivity(new Intent(this, StudyLoginActivity.class));
        } else {
            HomeTarget.STUDY.open(this);
        }
    }
}
