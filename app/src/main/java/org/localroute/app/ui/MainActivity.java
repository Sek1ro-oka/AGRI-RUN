package org.localroute.app.ui;

import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import org.localroute.app.databinding.ActivityMainBinding;
import org.localroute.app.diagnostics.DiagnosticLog;
import org.localroute.app.overlay.OverlayHost;

/**
 * 主页：只保留校园快跑与设置两个入口。
 *
 * <p>原来的地图选点、路线编辑和运行控制界面已移除；模拟定位引擎仍在
 * {@link org.localroute.app.service.SimulationService}，统一由
 * {@link CampusRunActivity} 调用。
 */
public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DiagnosticLog.init(getApplicationContext());

        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        ActivityMainBinding binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        ViewCompat.setOnApplyWindowInsetsListener(binding.getRoot(), (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        binding.cardCampusRun.setOnClickListener(v ->
                startActivity(new Intent(this, CampusRunActivity.class)));
        binding.cardSettings.setOnClickListener(v ->
                startActivity(new Intent(this, SettingsCheckActivity.class)));

        // 用户上次打开过悬浮控制台：进程一启动就按保存的开关和位置恢复。
        OverlayHost.get(this).restore();
    }
}
