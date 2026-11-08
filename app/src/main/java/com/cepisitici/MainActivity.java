package com.cepisitici;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.LabelFormatter;
import com.google.android.material.slider.Slider;

import java.util.Locale;

public class MainActivity extends AppCompatActivity implements HeatService.Listener {

    private static final int REQ_NOTIFICATIONS = 1001;

    private SharedPreferences prefs;
    private HeatService service;
    private boolean bound;

    private TextView tvCurrentTemp;
    private TextView tvThermalInfo;
    private TextView tvTargetValue;
    private TextView tvCpuValue;
    private TextView tvHint;
    private Chip chipStatus;
    private Slider sliderTarget;
    private Slider sliderCpu;
    private MaterialSwitch switchGpu;
    private MaterialSwitch switchBackground;
    private MaterialButton btnToggle;
    private MaterialButton btnBatteryOpt;

    private int maxCores = 1;
    private boolean safetyToastShown;

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (service != null && service.isRunning()) {
                return;
            }
            updateTemperature(HeatService.readBatteryTemperature(context));
            updateThermalInfoIdle();
        }
    };

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((HeatService.LocalBinder) binder).getService();
            bound = true;
            service.addListener(MainActivity.this);
            safetyToastShown = false;
            onHeatStateChanged();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            if (service != null) {
                service.removeListener(MainActivity.this);
            }
            service = null;
            bound = false;
            updateIdleState();
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences(HeatService.PREFS, MODE_PRIVATE);
        maxCores = Math.max(1, Runtime.getRuntime().availableProcessors());

        bindViews();
        applyInsets();
        applySystemBarAppearance();
        setupSliders();
        setupSwitches();
        setupButtons();
        updateTemperature(HeatService.readBatteryTemperature(this));
        updateThermalInfoIdle();
        tvTargetValue.setText(formatTemp(sliderTarget.getValue()));
        tvCpuValue.setText(getString(R.string.cpu_value, (int) sliderCpu.getValue(), maxCores));
    }

    @Override
    protected void onStart() {
        super.onStart();
        bindService(new Intent(this, HeatService.class), connection, BIND_AUTO_CREATE);
        ContextCompat.registerReceiver(this, batteryReceiver,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                ContextCompat.RECEIVER_EXPORTED);
    }

    @Override
    protected void onStop() {
        super.onStop();
        try {
            unregisterReceiver(batteryReceiver);
        } catch (Exception ignored) {
        }
        if (bound) {
            if (service != null) {
                service.removeListener(this);
            }
            unbindService(connection);
            bound = false;
            service = null;
        }
    }

    private void bindViews() {
        tvCurrentTemp = findViewById(R.id.tvCurrentTemp);
        tvThermalInfo = findViewById(R.id.tvThermalInfo);
        tvTargetValue = findViewById(R.id.tvTargetValue);
        tvCpuValue = findViewById(R.id.tvCpuValue);
        tvHint = findViewById(R.id.tvHint);
        chipStatus = findViewById(R.id.chipStatus);
        sliderTarget = findViewById(R.id.sliderTarget);
        sliderCpu = findViewById(R.id.sliderCpu);
        switchGpu = findViewById(R.id.switchGpu);
        switchBackground = findViewById(R.id.switchBackground);
        btnToggle = findViewById(R.id.btnToggle);
        btnBatteryOpt = findViewById(R.id.btnBatteryOpt);
    }

    private void applyInsets() {
        View root = findViewById(R.id.root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        ViewCompat.requestApplyInsets(root);
    }

    private void applySystemBarAppearance() {
        boolean light = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) != Configuration.UI_MODE_NIGHT_YES;
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(
                getWindow(), getWindow().getDecorView());
        controller.setAppearanceLightStatusBars(light);
        controller.setAppearanceLightNavigationBars(light);
    }

    private void setupSliders() {
        sliderTarget.setValueFrom(30f);
        sliderTarget.setValueTo(45f);
        sliderTarget.setStepSize(1f);
        float savedTarget = prefs.getFloat(HeatService.KEY_TARGET, 40f);
        savedTarget = Math.max(30f, Math.min(45f, savedTarget));
        sliderTarget.setValue(savedTarget);
        sliderTarget.setLabelFormatter(value -> formatTemp(value));
        sliderTarget.addOnChangeListener((slider, value, fromUser) -> {
            tvTargetValue.setText(formatTemp(value));
            prefs.edit().putFloat(HeatService.KEY_TARGET, value).apply();
            if (service != null) {
                service.setTargetTemp(value);
            }
        });

        sliderCpu.setValueFrom(1f);
        sliderCpu.setValueTo(Math.max(2, maxCores));
        sliderCpu.setStepSize(1f);
        int savedCpu = prefs.getInt(HeatService.KEY_CPU, maxCores);
        savedCpu = Math.max(1, Math.min(maxCores, savedCpu));
        sliderCpu.setValue(savedCpu);
        sliderCpu.setLabelFormatter(new LabelFormatter() {
            @NonNull
            @Override
            public String getFormattedValue(float value) {
                return String.format(Locale.getDefault(), "%.0f", value);
            }
        });
        sliderCpu.addOnChangeListener((slider, value, fromUser) -> {
            int threads = (int) value;
            tvCpuValue.setText(getString(R.string.cpu_value, threads, maxCores));
            prefs.edit().putInt(HeatService.KEY_CPU, threads).apply();
            if (service != null) {
                service.setCpuThreads(threads);
            }
        });
    }

    private void setupSwitches() {
        switchGpu.setChecked(prefs.getBoolean(HeatService.KEY_GPU, true));
        switchGpu.setOnCheckedChangeListener((button, checked) -> {
            prefs.edit().putBoolean(HeatService.KEY_GPU, checked).apply();
            if (service != null) {
                service.setGpuEnabled(checked);
            }
            updateThermalInfoIdle();
        });

        switchBackground.setChecked(prefs.getBoolean(HeatService.KEY_KEEP_AWAKE, true));
        switchBackground.setOnCheckedChangeListener((button, checked) -> {
            prefs.edit().putBoolean(HeatService.KEY_KEEP_AWAKE, checked).apply();
            if (service != null) {
                service.setKeepAwake(checked);
            }
        });
    }

    private void setupButtons() {
        btnToggle.setOnClickListener(view -> {
            if (service != null && service.isRunning()) {
                service.stopHeating(false);
            } else {
                ensureNotificationPermission();
                safetyToastShown = false;
                Intent intent = new Intent(this, HeatService.class)
                        .setAction(HeatService.ACTION_START);
                ContextCompat.startForegroundService(this, intent);
                btnToggle.setText(R.string.stop_heating);
                tvHint.setText(R.string.hint_running);
                if (!bound) {
                    bindService(new Intent(this, HeatService.class), connection,
                            BIND_AUTO_CREATE);
                }
            }
        });

        btnBatteryOpt.setOnClickListener(view -> openBatteryOptimizationSettings());
    }

    private void ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQ_NOTIFICATIONS);
        }
    }

    private void openBatteryOptimizationSettings() {
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        if (powerManager != null
                && powerManager.isIgnoringBatteryOptimizations(getPackageName())) {
            Toast.makeText(this, R.string.battery_opt_ok, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception first) {
            try {
                startActivity(new Intent(
                        Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception second) {
                Toast.makeText(this, R.string.battery_opt_unavailable,
                        Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    public void onHeatStateChanged() {
        if (service == null) {
            updateIdleState();
            return;
        }

        boolean running = service.isRunning();
        btnToggle.setText(running ? R.string.stop_heating : R.string.start_heating);
        chipStatus.setText(service.getStateText());
        tvHint.setText(running ? R.string.hint_running : R.string.hint_idle);
        updateTemperature(service.getCurrentTemp());

        if (running) {
            tvThermalInfo.setText(getString(R.string.thermal_info,
                    service.getCpuThreads(), maxCores,
                    service.isGpuEnabled() ? getString(R.string.on) : getString(R.string.off),
                    thermalLabel(service.getThermalStatus())));
        } else {
            updateThermalInfoIdle();
            if (service.isSafetyStopped() && !safetyToastShown) {
                safetyToastShown = true;
                Toast.makeText(this, R.string.alert_title, Toast.LENGTH_LONG).show();
            }
        }
    }

    private void updateIdleState() {
        btnToggle.setText(R.string.start_heating);
        chipStatus.setText(R.string.state_ready);
        tvHint.setText(R.string.hint_idle);
        updateTemperature(HeatService.readBatteryTemperature(this));
        updateThermalInfoIdle();
    }

    private void updateThermalInfoIdle() {
        if (tvThermalInfo == null) {
            return;
        }
        int status = HeatService.readThermalStatus(this);
        tvThermalInfo.setText(getString(R.string.thermal_info,
                (int) sliderCpu.getValue(), maxCores,
                switchGpu.isChecked() ? getString(R.string.on) : getString(R.string.off),
                thermalLabel(status)));
    }

    private void updateTemperature(float temperature) {
        if (Float.isNaN(temperature) || temperature < -20f || temperature > 120f) {
            tvCurrentTemp.setText("--");
        } else {
            tvCurrentTemp.setText(String.format(Locale.getDefault(), "%.1f°C", temperature));
        }
    }

    private String thermalLabel(int status) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return getString(R.string.thermal_unknown);
        }
        switch (status) {
            case PowerManager.THERMAL_STATUS_NONE:
                return getString(R.string.thermal_normal);
            case PowerManager.THERMAL_STATUS_LIGHT:
                return getString(R.string.thermal_light);
            case PowerManager.THERMAL_STATUS_MODERATE:
                return getString(R.string.thermal_moderate);
            case PowerManager.THERMAL_STATUS_SEVERE:
                return getString(R.string.thermal_severe);
            case PowerManager.THERMAL_STATUS_CRITICAL:
                return getString(R.string.thermal_critical);
            default:
                return getString(R.string.thermal_unknown);
        }
    }

    private String formatTemp(float value) {
        return String.format(Locale.getDefault(), "%.0f°C", value);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_NOTIFICATIONS && grantResults.length > 0
                && grantResults[0] != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, R.string.notification_denied, Toast.LENGTH_LONG).show();
        }
    }
}
