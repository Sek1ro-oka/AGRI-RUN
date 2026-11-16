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
import org.localroute.app.overlay.OverlayHost;
import org.localroute.app.run.RunSnapshot;
import org.localroute.app.run.SimulationStateRepo;
import org.localroute.core.RunState;

/** Campus services and a live shortcut to the active simulation. */
public class MainActivity extends AppCompatActivity {
    private ActivityMainBinding binding;
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
        binding.cardActiveRun.setOnClickListener(v -> openRun());
        binding.cardStudy.setOnClickListener(v -> startActivity(new Intent(this, StudyActivity.class)));
        binding.toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_settings) {
                startActivity(new Intent(this, AppSettingsActivity.class)); return true;
            }
            return false;
        });
        SimulationStateRepo.get().snapshot().observe(this, this::renderRun);
        OverlayHost.get(this).restore();
    }
    private void openRun() {
        startActivity(new Intent(this, CampusRunActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP));
    }
    private void renderRun(RunSnapshot snapshot) {
        binding.cardActiveRun.setVisibility(snapshot.isActive() ? View.VISIBLE : View.GONE);
        int state = snapshot.state() == RunState.STARTING ? R.string.run_state_starting
                : snapshot.state() == RunState.PAUSED ? R.string.run_state_paused
                : snapshot.singlePoint() ? R.string.run_state_hold : R.string.run_state_running;
        binding.textActiveRunTitle.setText(getString(R.string.run_active_home, getString(state)));
        binding.textActiveRunDetail.setText(getString(R.string.run_active_detail,
                CampusFormat.kilometers(snapshot.distanceMeters()), CampusFormat.time(snapshot.activeElapsedMs())));
    }
}
