package org.localroute.app.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import org.localroute.app.R;
import org.localroute.app.campus.CampusFormat;
import org.localroute.app.databinding.ActivityFunctionsBinding;
import org.localroute.app.entry.HomeTarget;
import org.localroute.app.run.RunSnapshot;
import org.localroute.app.run.SimulationStateRepo;
import org.localroute.app.settings.SetupGuide;
import org.localroute.app.settings.SettingsInspector;
import org.localroute.core.RunState;

import java.util.List;

/** 功能：校园快跑、虚拟定位、课表与作业的入口，以及运行中与环境就绪两张动态卡。 */
public class FunctionsActivity extends AppCompatActivity {

    private ActivityFunctionsBinding binding;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        binding = ActivityFunctionsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        ViewCompat.setOnApplyWindowInsetsListener(binding.getRoot(), (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        binding.cardCampusRun.setOnClickListener(v -> HomeTarget.RUN.open(this));
        binding.cardVirtualLocation.setOnClickListener(v -> HomeTarget.VIRTUAL.open(this));
        binding.cardActiveRun.setOnClickListener(v -> {
            RunSnapshot current = SimulationStateRepo.get().current();
            (current.singlePoint() ? HomeTarget.VIRTUAL : HomeTarget.RUN).open(this);
        });
        binding.cardStudy.setOnClickListener(v -> HomeTarget.STUDY.open(this));
        binding.cardTimetable.setOnClickListener(v -> HomeTarget.TIMETABLE.open(this));
        binding.cardSetupGuide.setOnClickListener(v ->
                startActivity(new Intent(this, SettingsCheckActivity.class)));
        FinmoriMotion.press(binding.cardCampusRun);
        FinmoriMotion.press(binding.cardVirtualLocation);
        FinmoriMotion.press(binding.cardActiveRun);
        FinmoriMotion.press(binding.cardStudy);
        FinmoriMotion.press(binding.cardTimetable);
        FinmoriMotion.press(binding.cardSetupGuide);
        FinmoriNav.bind(this, binding.bottomNav, FinmoriNav.Tab.FUNCTIONS);
        SimulationStateRepo.get().snapshot().observe(this, this::renderRun);
        renderSetupGuide();
    }

    @Override protected void onResume() {
        super.onResume();
        // 从系统授权页或设置检查页回来，环境状态可能已经变了。
        renderSetupGuide();
    }

    /** 环境还差阻塞项时提示「下一步做什么」，全部就绪后卡片自己收起来。 */
    private void renderSetupGuide() {
        List<SettingsInspector.Check> checks = SettingsInspector.inspect(this);
        SettingsInspector.Check next = SetupGuide.next(checks);
        boolean needed = next != null && next.item().blocking();
        binding.cardSetupGuide.setVisibility(needed ? View.VISIBLE : View.GONE);
        if (!needed) return;
        binding.textSetupGuideTitle.setText(getString(R.string.guide_home_title, next.title()));
        binding.textSetupGuideDetail.setText(getString(R.string.guide_home_detail,
                SetupGuide.pendingBlocking(checks)));
    }

    private void renderRun(RunSnapshot snapshot) {
        boolean justStarted = snapshot.isActive()
                && binding.cardActiveRun.getVisibility() != View.VISIBLE;
        binding.cardActiveRun.setVisibility(snapshot.isActive() ? View.VISIBLE : View.GONE);
        if (justStarted) FinmoriMotion.reveal(binding.cardActiveRun);
        int state = snapshot.state() == RunState.STARTING ? R.string.run_state_starting
                : snapshot.state() == RunState.PAUSED ? R.string.run_state_paused
                : snapshot.singlePoint() ? R.string.run_state_hold : R.string.run_state_running;
        binding.textActiveRunTitle.setText(getString(snapshot.singlePoint()
                ? R.string.virtual_active_home : R.string.run_active_home, getString(state)));
        binding.textActiveRunDetail.setText(snapshot.singlePoint()
                ? getString(R.string.virtual_active_detail)
                : getString(R.string.run_active_detail,
                        CampusFormat.kilometers(snapshot.distanceMeters()),
                        CampusFormat.time(snapshot.activeElapsedMs())));
    }
}
