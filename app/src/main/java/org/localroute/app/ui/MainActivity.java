package org.localroute.app.ui;

import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import org.localroute.app.databinding.ActivityMainBinding;
import org.localroute.app.diagnostics.DiagnosticLog;

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

        ActivityMainBinding binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        binding.cardCampusRun.setOnClickListener(v ->
                startActivity(new Intent(this, CampusRunActivity.class)));
        binding.cardSettings.setOnClickListener(v ->
                startActivity(new Intent(this, SettingsCheckActivity.class)));
    }
}
