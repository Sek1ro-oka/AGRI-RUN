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
import org.localroute.app.databinding.ActivityMainBinding;
import org.localroute.app.diagnostics.DiagnosticLog;
import org.localroute.app.entry.HomeTarget;
import org.localroute.app.overlay.OverlayHost;
import org.localroute.app.run.RunSnapshot;
import org.localroute.app.run.SimulationStateRepo;
import org.localroute.core.RunState;

/** Campus services and a live shortcut to the active simulation. */
public class MainActivity extends AppCompatActivity {
    private ActivityMainBinding binding;
    /** 快捷入口或磁贴送进来的目标页；本页可见之后再跳，那时启动内部页面才不会被系统挡。 */
    private HomeTarget pendingTarget;
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
        binding.cardCampusRun.setOnClickListener(v -> openRun());
        binding.cardVirtualLocation.setOnClickListener(v -> HomeTarget.VIRTUAL.open(this));
        binding.cardActiveRun.setOnClickListener(v -> openRun());
        binding.cardStudy.setOnClickListener(v -> HomeTarget.STUDY.open(this));
        binding.cardTimetable.setOnClickListener(v -> HomeTarget.TIMETABLE.open(this));
        binding.navRun.setOnClickListener(v -> openRun());
        binding.navStudy.setOnClickListener(v -> HomeTarget.STUDY.open(this));
        FinmoriMotion.press(binding.cardCampusRun);
        FinmoriMotion.press(binding.cardVirtualLocation);
        FinmoriMotion.press(binding.cardActiveRun);
        FinmoriMotion.press(binding.cardStudy);
        FinmoriMotion.press(binding.cardTimetable);
        FinmoriMotion.press(binding.navRun);
        FinmoriMotion.press(binding.navStudy);
        binding.toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_settings) {
                startActivity(new Intent(this, AppSettingsActivity.class)); return true;
            }
            return false;
        });
        SimulationStateRepo.get().snapshot().observe(this, this::renderRun);
        OverlayHost.get(this).restore();
        // 只认冷启动那一次：进程被杀后恢复时不该把用户又甩进别的页面。
        if (savedInstanceState == null) {
            pendingTarget = HomeTarget.from(getIntent().getStringExtra(HomeTarget.EXTRA_TARGET));
        }
    }
    @Override protected void onResume() {
        super.onResume();
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
    private void openRun() {
        HomeTarget.RUN.open(this);
    }
    private void renderRun(RunSnapshot snapshot) {
        boolean justStarted = snapshot.isActive() && binding.cardActiveRun.getVisibility() != View.VISIBLE;
        binding.cardActiveRun.setVisibility(snapshot.isActive() ? View.VISIBLE : View.GONE);
        if (justStarted) FinmoriMotion.reveal(binding.cardActiveRun);
        int state = snapshot.state() == RunState.STARTING ? R.string.run_state_starting
                : snapshot.state() == RunState.PAUSED ? R.string.run_state_paused
                : snapshot.singlePoint() ? R.string.run_state_hold : R.string.run_state_running;
        binding.textActiveRunTitle.setText(getString(R.string.run_active_home, getString(state)));
        binding.textActiveRunDetail.setText(getString(R.string.run_active_detail,
                CampusFormat.kilometers(snapshot.distanceMeters()), CampusFormat.time(snapshot.activeElapsedMs())));
    }
}
