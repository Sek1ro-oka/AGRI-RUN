package org.localroute.app.ui;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import org.localroute.app.R;
import org.localroute.app.databinding.ActivityMainBinding;
import org.localroute.app.databinding.ItemHomeAgendaBinding;
import org.localroute.app.databinding.ItemHomeWeekDayBinding;
import org.localroute.app.diagnostics.DiagnosticLog;
import org.localroute.app.entry.HomeTarget;
import org.localroute.app.home.HomeAgenda;
import org.localroute.app.home.HomeGreeting;
import org.localroute.app.home.HomeworkDue;
import org.localroute.app.overlay.OverlayHost;
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

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 首页：插画问候 + 当天日程时间轴 + 校园账户占位。
 *
 * <p>它同时是启动页与桌面快捷方式的中转站（清单里只有本页 exported），所以 {@link HomeTarget}
 * 的转发留在这里；运行中与环境引导两张动态卡在「功能」页。
 */
public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;
    /** 快捷入口或磁贴送进来的目标页；本页可见之后再跳，那时启动内部页面才不会被系统挡。 */
    private HomeTarget pendingTarget;
    private LocalDate today;
    private LocalDate selectedDay;
    private List<LocalDate> weekDays = new ArrayList<>();
    private String[] weekdayLabels;
    /** 课程时间轴与待交作业各自折叠，各自记展开态。 */
    private boolean courseExpanded;
    private boolean homeworkExpanded;
    private boolean courseFootActionable;
    private boolean homeworkFootActionable;
    private int courseTotal;
    private int collapseRetries;
    private int homeworkTotal;
    private final Runnable collapsePass = this::applyCollapse;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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
        binding.buttonHomeBind.setOnClickListener(v -> onEmptyAction());
        binding.textHomeAgendaCount.setOnClickListener(v -> {
            if (!courseFootActionable) return;
            courseExpanded = !courseExpanded;
            render();
        });
        binding.textHomeworkFoot.setOnClickListener(v -> {
            if (!homeworkFootActionable) return;
            homeworkExpanded = !homeworkExpanded;
            render();
        });
        FinmoriMotion.press(binding.buttonHomeCalendar);
        FinmoriMotion.press(binding.buttonHomeBind);
        FinmoriNav.bind(this, binding.bottomNav, FinmoriNav.Tab.HOME);
        OverlayHost.get(this).restore();
        // 只认冷启动那一次：进程被杀后恢复时不该把用户又甩进别的页面。
        if (savedInstanceState == null) {
            pendingTarget = HomeTarget.from(getIntent().getStringExtra(HomeTarget.EXTRA_TARGET));
        }
    }

    @Override protected void onResume() {
        super.onResume();
        // 绑定、导入、抓作业都发生在别的页面，回来必须重读，否则首页还挂着旧数据。
        render();
        if (pendingTarget == null) return;
        HomeTarget target = pendingTarget;
        pendingTarget = null;
        target.open(this);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        pendingTarget = HomeTarget.from(intent.getStringExtra(HomeTarget.EXTRA_TARGET));
    }

    private void openProfile() {
        startActivity(new Intent(this, ProfileActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP));
    }

    private void render() {
        LocalDate now = LocalDate.now();
        if (today == null || !today.equals(now)) {
            today = now;
            LocalDate monday = SemesterCalendar.mondayOf(today);
            weekDays = new ArrayList<>();
            for (int offset = 0; offset < 7; offset++) {
                weekDays.add(monday.plusDays(offset));
            }
            selectedDay = today;
        }
        if (selectedDay == null) {
            selectedDay = today;
        }

        StudySession session = StudySessionStore.load(this);
        List<HomeworkDue.Task> tasks = loadTasks(session);
        List<TimetableCourse> courses = StudyTimetableStore.loadCourses(this);
        StudySchedule schedule = StudyTimetableStore.loadSchedule(this);
        LocalDate semesterStart = StudyTimetableStore.loadSemesterStart(this);
        int totalWeeks = StudyTimetableStore.loadTotalWeeks(this);
        List<DayAdjustment> adjustments = StudyTimetableStore.loadAdjustments(this);
        LocalTime clock = LocalTime.now();
        int nowMinutes = clock.getHour() * 60 + clock.getMinute();

        HomeAgenda.Day todayAgenda = HomeAgenda.of(courses, schedule, semesterStart, totalWeeks,
                adjustments, today, today, nowMinutes);
        HomeAgenda.Day shown = selectedDay.equals(today) ? todayAgenda
                : HomeAgenda.of(courses, schedule, semesterStart, totalWeeks, adjustments,
                selectedDay, today, nowMinutes);

        List<HomeworkDue.Item> due = HomeworkDue.upcoming(tasks, today, HomeworkDue.HORIZON_DAYS);
        renderHero(session, todayAgenda.courseCount(), due.size(), todayAgenda.notImported());
        renderDate();
        renderWeekStrip();
        renderAgenda(shown, session);
        renderHomework(due);
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
        int hero;
        int scrim;
        int textColor;
        int subColor;
        int greetRes;
        switch (phase) {
            case MORNING:
                hero = R.drawable.home_hero_morning;
                scrim = R.drawable.bg_home_hero_scrim_light;
                textColor = R.color.fm_hero_on_light;
                subColor = R.color.fm_hero_on_light_sub;
                greetRes = R.string.home_greet_morning;
                break;
            case DAY:
                hero = R.drawable.home_hero_day;
                scrim = R.drawable.bg_home_hero_scrim_light;
                textColor = R.color.fm_hero_on_light;
                subColor = R.color.fm_hero_on_light_sub;
                greetRes = R.string.home_greet_day;
                break;
            default:
                hero = R.drawable.home_hero_night;
                scrim = R.drawable.bg_home_hero_scrim_dark;
                textColor = R.color.fm_hero_on_dark;
                subColor = R.color.fm_hero_on_dark_sub;
                greetRes = R.string.home_greet_night;
                break;
        }
        binding.imageHomeHero.setImageResource(hero);
        binding.viewHomeScrim.setBackgroundResource(scrim);
        binding.textHomeGreeting.setTextColor(ContextCompat.getColor(this, textColor));
        binding.textHomeGreetingSub.setTextColor(ContextCompat.getColor(this, subColor));
        String name = session != null && !session.name().isEmpty()
                ? session.name() : getString(R.string.home_greet_guest);
        binding.textHomeGreeting.setText(getString(greetRes, name));
        // 没绑定但已经手动加过课／作业时，不能再谎称「绑定后才有课程」。
        boolean nothingToday = courseCount == 0 && homeworkCount == 0;
        int subRes;
        if (session == null && nothingToday) {
            subRes = R.string.home_greet_unbound;
        } else if (notImported) {
            subRes = R.string.home_greet_no_timetable;
        } else {
            binding.textHomeGreetingSub.setText(getString(R.string.home_greet_summary,
                    courseCount, homeworkCount));
            return;
        }
        binding.textHomeGreetingSub.setText(subRes);
    }

    private void renderDate() {
        binding.textHomeDate.setText(dateLabel(selectedDay));
    }

    private String dateLabel(LocalDate day) {
        return getString(R.string.home_date_format, day.getMonthValue(), day.getDayOfMonth(),
                weekdayLabels[day.getDayOfWeek().getValue() - 1]);
    }

    private void renderWeekStrip() {
        binding.weekStrip.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        for (LocalDate day : weekDays) {
            ItemHomeWeekDayBinding cell =
                    ItemHomeWeekDayBinding.inflate(inflater, binding.weekStrip, false);
            boolean selected = day.equals(selectedDay);
            cell.textDayNumber.setText(String.valueOf(day.getDayOfMonth()));
            cell.textDayWeek.setText(weekdayLabels[day.getDayOfWeek().getValue() - 1]);
            cell.getRoot().setSelected(selected);
            int color = ContextCompat.getColor(this,
                    selected ? R.color.fm_surface : R.color.fm_text_secondary);
            cell.textDayNumber.setTextColor(color);
            cell.textDayWeek.setTextColor(color);
            cell.getRoot().setContentDescription(dateLabel(day));
            cell.getRoot().setOnClickListener(v -> {
                selectedDay = day;
                courseExpanded = false;
                homeworkExpanded = false;
                render();
            });
            binding.weekStrip.addView(cell.getRoot());
        }
    }

    private void renderAgenda(HomeAgenda.Day shown, StudySession session) {
        binding.homeScroll.removeCallbacks(collapsePass);
        courseFootActionable = false;
        courseTotal = shown.courseCount();
        binding.agendaList.removeAllViews();
        boolean empty = shown.items().isEmpty();
        binding.agendaList.setVisibility(empty ? View.GONE : View.VISIBLE);
        binding.agendaEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        binding.textHomeAgendaCount.setVisibility(empty ? View.GONE : View.VISIBLE);
        if (empty) {
            renderEmpty(shown, session);
            return;
        }
        LayoutInflater inflater = LayoutInflater.from(this);
        List<HomeAgenda.Item> items = shown.items();
        for (int i = 0; i < items.size(); i++) {
            HomeAgenda.Item item = items.get(i);
            ItemHomeAgendaBinding row =
                    ItemHomeAgendaBinding.inflate(inflater, binding.agendaList, false);
            bindRow(row, item, i == items.size() - 1);
            binding.agendaList.addView(row.getRoot());
        }
        binding.textHomeAgendaCount.setText(getString(R.string.home_agenda_count,
                courseTotal));
    }

    /** 待交作业：跨天清单，没有内容就整块不出现。 */
    private void renderHomework(List<HomeworkDue.Item> due) {
        binding.homeScroll.removeCallbacks(collapsePass);
        homeworkFootActionable = false;
        homeworkTotal = due.size();
        binding.homeworkList.removeAllViews();
        binding.cardHomework.setVisibility(due.isEmpty() ? View.GONE : View.VISIBLE);
        if (due.isEmpty()) {
            // 课程也没有内容时，整页只剩空态卡，不需要再留一行脚注。
            binding.homeScroll.post(collapsePass);
            return;
        }
        LayoutInflater inflater = LayoutInflater.from(this);
        for (HomeworkDue.Item item : due) {
            ItemHomeAgendaBinding row =
                    ItemHomeAgendaBinding.inflate(inflater, binding.homeworkList, false);
            bindHomeworkRow(row, item);
            binding.homeworkList.addView(row.getRoot());
        }
        binding.textHomeworkFoot.setText(getString(R.string.home_homework_count, homeworkTotal));
        // 两段都渲染完之后量一次真实高度，再决定各自收起几条。
        binding.homeScroll.post(collapsePass);
    }

/**
     * 自适应折叠：整页放得下就全展开；放不下先收作业、再收课程，各自换成「展开还有 N 项」。
     *
     * <p>用真实测量高度而不是估算，插画、间距、字号任何一处改动都不用回来调常数。
     * 作业优先收，因为它有一周缓冲；课程是今天的时刻表，最后才动。
     */
    private void applyCollapse() {
        int viewport = binding.homeScroll.getHeight();
        if (viewport <= 0) return;

        int cRows = binding.agendaList.getChildCount();
        int hRows = binding.homeworkList.getChildCount();
        int cH = cRows > 0 ? binding.agendaList.getChildAt(0).getHeight() : 0;
        int hH = hRows > 0 ? binding.homeworkList.getChildAt(0).getHeight() : 0;
        // 刚加进来的行可能还没量完高度，读到 0 就等下一帧再量；否则会误判成「一条都放不下」。
        if ((cRows > 0 && cH == 0) || (hRows > 0 && hH == 0)) {
            if (collapseRetries < 3) {
                collapseRetries++;
                binding.homeScroll.post(collapsePass);
            }
            return;
        }
        collapseRetries = 0;
        int rowsHeight = cRows * cH + hRows * hH;
        int available = viewport - (binding.homeContent.getHeight() - rowsHeight);

        // 按可用高度统一分配：课程优先占位，作业吃剩下的；手动展开的一段不参与裁剪。
        int cFit = courseExpanded ? cRows
                : (cH > 0 ? Math.max(0, Math.min(cRows, available / cH)) : 0);
        int rest = available - cFit * cH;
        int hFit = homeworkExpanded ? hRows
                : (hH > 0 ? Math.max(0, Math.min(hRows, rest / hH)) : 0);
        // 作业有内容却一条都分不到时，从课程匀一条给它：只剩一个标题的作业块看起来像坏了。
        if (!homeworkExpanded && !courseExpanded && hRows > 0 && hFit == 0 && cFit > 1) {
            cFit--;
            hFit = Math.min(hRows, (available - cFit * cH) / Math.max(1, hH));
        }

        int cHidden = cRows - cFit;
        for (int i = cRows - 1; i >= cFit; i--) {
            binding.agendaList.removeViewAt(i);
        }
        if (cFit > 0) {
            binding.agendaList.getChildAt(cFit - 1)
                    .findViewById(R.id.viewItemLine).setVisibility(View.INVISIBLE);
        }
        applySectionFooter(binding.textHomeAgendaCount, cRows, cHidden, courseExpanded,
                courseTotal, R.string.home_agenda_count);
        courseFootActionable = cRows > 0 && (courseExpanded || cHidden > 0);

        int hHidden = hRows - hFit;
        for (int i = hRows - 1; i >= hFit; i--) {
            binding.homeworkList.removeViewAt(i);
        }
        applySectionFooter(binding.textHomeworkFoot, hRows, hHidden, homeworkExpanded,
                homeworkTotal, R.string.home_homework_count);
        homeworkFootActionable = hRows > 0 && (homeworkExpanded || hHidden > 0);
    }

    private void applySectionFooter(TextView footer, int rows, int hidden, boolean expanded,
                                    int total, int countRes) {
        if (rows == 0) {
            footer.setText("");
        } else if (expanded) {
            footer.setText(R.string.home_agenda_collapse);
        } else if (hidden > 0) {
            footer.setText(getString(R.string.home_agenda_expand, hidden));
        } else {
            footer.setText(getString(countRes, total));
        }
    }

    private void bindRow(ItemHomeAgendaBinding row, HomeAgenda.Item item, boolean last) {
        row.textItemTime.setText(item.timeLabel());
        row.textItemTime.setTextColor(ContextCompat.getColor(this, R.color.fm_text));
        row.textItemTitle.setText(item.title());
        row.textItemMeta.setText(item.subtitle());
        row.textItemMeta.setVisibility(item.subtitle().isEmpty() ? View.GONE : View.VISIBLE);
        row.viewItemLine.setVisibility(last ? View.INVISIBLE : View.VISIBLE);
        row.viewItemDot.setBackgroundTintList(
                ColorStateList.valueOf(ContextCompat.getColor(this, R.color.fm_link)));

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
        row.textItemTag.setText(tagText);
        row.textItemTag.setTextColor(ContextCompat.getColor(this, tagColor));
        row.textItemTag.setBackgroundResource(solid
                ? R.drawable.bg_home_tag_solid : R.drawable.bg_home_tag_outline);
        row.cardItem.setContentDescription(row.textItemTime.getText() + " " + item.title());
        row.cardItem.setOnClickListener(v -> HomeTarget.TIMETABLE.open(this));
        FinmoriMotion.press(row.cardItem);
    }

    /** 作业行沿用同一套行布局：左列放「今天／明天／日期」，副行放时刻与课程。 */
    private void bindHomeworkRow(ItemHomeAgendaBinding row, HomeworkDue.Item item) {
        row.textItemTime.setText(item.undated()
                ? getString(R.string.home_task_undated) : dayLabel(item.dayOffset()));
        row.textItemTime.setTextColor(ContextCompat.getColor(this, R.color.fm_warning));
        row.textItemTitle.setText(item.title());
        String meta = item.undated() || item.timeLabel() == null
                ? item.course() : item.timeLabel() + " · " + item.course();
        row.textItemMeta.setText(meta);
        row.textItemMeta.setVisibility(meta.isEmpty() ? View.GONE : View.VISIBLE);
        row.viewItemLine.setVisibility(View.INVISIBLE);
        row.viewItemDot.setBackgroundTintList(
                ColorStateList.valueOf(ContextCompat.getColor(this, R.color.fm_warning)));
        row.textItemTag.setText(R.string.home_task_pending);
        row.textItemTag.setTextColor(ContextCompat.getColor(this, R.color.fm_warning));
        row.textItemTag.setBackgroundResource(R.drawable.bg_home_tag_outline);
        row.cardItem.setContentDescription(row.textItemTime.getText() + " " + item.title());
        row.cardItem.setOnClickListener(v -> HomeTarget.STUDY.open(this));
        FinmoriMotion.press(row.cardItem);
    }

    private String dayLabel(int dayOffset) {
        if (dayOffset == 0) return getString(R.string.home_day_today);
        if (dayOffset == 1) return getString(R.string.home_day_tomorrow);
        LocalDate day = today.plusDays(dayOffset);
        return day.getMonthValue() + "-" + day.getDayOfMonth();
    }

    /** 空态分四种：今天都结束了、没绑定、绑了但没课表、这天真的没安排。 */
    private void renderEmpty(HomeAgenda.Day shown, StudySession session) {
        if (shown.hiddenFinished() > 0) {
            binding.textHomeEmptyTitle.setText(R.string.home_agenda_all_done);
            binding.buttonHomeBind.setVisibility(View.GONE);
            return;
        }
        if (shown.notImported()) {
            boolean unbound = session == null;
            binding.textHomeEmptyTitle.setText(unbound
                    ? R.string.home_bind_title : R.string.home_agenda_no_timetable);
            binding.buttonHomeBind.setText(unbound
                    ? R.string.home_bind_action : R.string.home_agenda_import_action);
            binding.buttonHomeBind.setVisibility(View.VISIBLE);
            return;
        }
        binding.textHomeEmptyTitle.setText(shown.closed()
                ? R.string.home_agenda_closed : R.string.home_agenda_empty);
        binding.buttonHomeBind.setVisibility(View.GONE);
    }

    private void onEmptyAction() {
        if (StudySessionStore.load(this) == null) {
            openProfile();
        } else {
            HomeTarget.TIMETABLE.open(this);
        }
    }
}