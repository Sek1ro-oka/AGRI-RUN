package org.localroute.app.ui;

import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.localroute.app.R;
import org.localroute.app.campus.CampusFormat;
import org.localroute.app.databinding.ActivityRunHistoryBinding;
import org.localroute.app.run.RunHistoryStore;
import org.localroute.app.run.RunRecord;
import org.localroute.app.run.RunStats;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 跑步记录页：顶部是累计口径，下面是逐次清单，行左侧滑动删除。
 *
 * <p>统计口径全部在 {@link RunStats} 里算，本页只负责把它念出来；跑圈过程本身不在这里控制
 * （那属于跑圈页与悬浮控制台），从这页回不去正在运行的会话也是有意为之。
 */
public class RunHistoryActivity extends AppCompatActivity {

    private ActivityRunHistoryBinding binding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        binding = ActivityRunHistoryBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        ViewCompat.setOnApplyWindowInsetsListener(binding.getRoot(), (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        setSupportActionBar(binding.toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        binding.buttonClear.setOnClickListener(v -> confirmClear());
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void render() {
        List<RunRecord> records = RunHistoryStore.load(this);
        RunStats stats = RunStats.of(records, System.currentTimeMillis());
        binding.textTotalDistance.setText(CampusFormat.kilometers(stats.totalMeters()));
        binding.textRunCount.setText(getString(R.string.run_history_count_unit, stats.runCount()));
        binding.textAveragePace.setText(CampusFormat.paceWithUnit(stats.averagePaceSecondsPerKm()));
        binding.textStreak.setText(getString(R.string.run_history_streak_unit, stats.streakDays()));
        binding.textRangeSummary.setText(getString(R.string.run_history_range,
                CampusFormat.kilometers(stats.todayMeters()),
                CampusFormat.kilometers(stats.weekMeters()),
                CampusFormat.kilometers(stats.bestMeters()),
                CampusFormat.time(stats.totalActiveMillis())));
        binding.textEmpty.setVisibility(records.isEmpty() ? View.VISIBLE : View.GONE);
        binding.historyList.removeAllViews();
        for (RunRecord record : records) {
            binding.historyList.addView(row(record));
        }
    }

    private View row(RunRecord record) {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.HORIZONTAL);
        content.setGravity(Gravity.CENTER_VERTICAL);
        content.setPadding(dp(2), dp(11), dp(2), dp(11));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(0, 0, dp(10), 0);

        TextView date = new TextView(this);
        date.setTextSize(11f);
        date.setTextColor(ContextCompat.getColor(this, R.color.campus_muted));
        date.setText(new SimpleDateFormat(getString(R.string.run_history_date), Locale.CHINA)
                .format(new Date(record.endedAtMillis())));
        texts.addView(date);

        TextView name = new TextView(this);
        name.setTextSize(14f);
        name.setTextColor(ContextCompat.getColor(this, R.color.campus_ink));
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setMaxLines(1);
        name.setText(record.routeName().isEmpty() ? getString(R.string.run_history_unnamed)
                : record.routeName());
        texts.addView(name);

        TextView detail = new TextView(this);
        detail.setTextSize(11f);
        detail.setTextColor(ContextCompat.getColor(this, R.color.campus_muted));
        detail.setPadding(0, dp(2), 0, 0);
        detail.setText(getString(R.string.run_history_detail, loopsText(record),
                CampusFormat.time(record.activeMillis()),
                getString(record.finishedByGoal() ? R.string.run_history_finished
                        : R.string.run_history_partial)));
        texts.addView(detail);
        content.addView(texts, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView distance = new TextView(this);
        distance.setTextSize(16f);
        distance.setTextColor(ContextCompat.getColor(this, R.color.campus_mint_dark));
        distance.setTypeface(Typeface.DEFAULT_BOLD);
        distance.setText(CampusFormat.kilometers(record.distanceMeters()));
        content.addView(distance);

        SwipeActionRow swipe = new SwipeActionRow(this);
        swipe.setContent(content);
        swipe.addAction(getString(R.string.campus_route_delete),
                ContextCompat.getColor(this, R.color.campus_danger),
                () -> confirmDelete(record));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(2);
        swipe.setLayoutParams(params);
        return swipe;
    }

    private String loopsText(RunRecord record) {
        return record.targetLoops() > 0
                ? getString(R.string.run_history_loops, record.loops(), record.targetLoops())
                : getString(R.string.run_history_loops_no_target, record.loops());
    }

    private void confirmDelete(RunRecord record) {
        String when = new SimpleDateFormat(getString(R.string.run_history_date), Locale.CHINA)
                .format(new Date(record.endedAtMillis()));
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.campus_route_delete)
                .setMessage(getString(R.string.run_history_delete_confirm, when))
                .setPositiveButton(R.string.campus_route_delete, (dialog, which) -> {
                    RunHistoryStore.remove(this, record.id());
                    render();
                    toast(getString(R.string.run_history_deleted));
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void confirmClear() {
        if (RunHistoryStore.load(this).isEmpty()) {
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.run_history_clear)
                .setMessage(R.string.run_history_clear_confirm)
                .setPositiveButton(R.string.run_history_clear, (dialog, which) -> {
                    RunHistoryStore.clear(this);
                    render();
                    toast(getString(R.string.run_history_cleared));
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
