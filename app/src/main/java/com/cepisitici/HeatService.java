package com.cepisitici;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.BatteryManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import androidx.core.app.NotificationCompat;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

public class HeatService extends Service {

    public static final String PREFS = "cepisitici";
    public static final String KEY_TARGET = "target_temp";
    public static final String KEY_CPU = "cpu_threads";
    public static final String KEY_GPU = "gpu_enabled";
    public static final String KEY_KEEP_AWAKE = "keep_awake";
    public static final String KEY_RUNNING = "is_running";

    public static final String ACTION_START = "com.cepisitici.action.START";
    public static final String ACTION_STOP = "com.cepisitici.action.STOP";

    private static final String CHANNEL_ID = "heating_service";
    private static final String ALERT_CHANNEL_ID = "safety_alerts";
    private static final int NOTIFICATION_ID = 1001;
    private static final int ALERT_NOTIFICATION_ID = 1002;

    private static final float MIN_TARGET = 30f;
    private static final float MAX_TARGET = 45f;
    private static final float SAFETY_LIMIT_C = 47f;
    private static final float HYSTERESIS = 1f;

    public interface Listener {
        void onHeatStateChanged();
    }

    public class LocalBinder extends Binder {
        public HeatService getService() {
            return HeatService.this;
        }
    }

    private final IBinder binder = new LocalBinder();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private CpuBurner cpuBurner;
    private GpuBurner gpuBurner;
    private PowerManager.WakeLock wakeLock;

    private boolean running;
    private boolean throttled;
    private boolean safetyStopped;
    private boolean gpuEnabled = true;
    private boolean keepAwake = true;
    private int cpuThreads = 1;
    private int maxCores = 1;
    private float targetTemp = 40f;
    private float currentTemp = Float.NaN;
    private int thermalStatus;
    private String stateText;

    private final Runnable tickRunnable = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }

            currentTemp = readBatteryTemperature(HeatService.this);
            thermalStatus = readThermalStatus(HeatService.this);

            boolean overheated = !Float.isNaN(currentTemp) && currentTemp >= SAFETY_LIMIT_C;
            boolean critical = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    && thermalStatus >= PowerManager.THERMAL_STATUS_CRITICAL;
            if (overheated || critical) {
                safetyStopped = true;
                stopHeating(true);
                return;
            }

            boolean newThrottled;
            if (throttled) {
                newThrottled = !Float.isNaN(currentTemp) && currentTemp > targetTemp - HYSTERESIS;
            } else {
                newThrottled = !Float.isNaN(currentTemp) && currentTemp >= targetTemp;
            }

            if (newThrottled != throttled) {
                throttled = newThrottled;
                if (cpuBurner != null) {
                    cpuBurner.setThrottled(throttled);
                }
                if (gpuBurner != null) {
                    gpuBurner.setPaused(throttled);
                }
            }

            stateText = throttled
                    ? getString(R.string.state_target_reached)
                    : getString(R.string.state_heating);
            updateNotification();
            notifyListeners();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        maxCores = Math.max(1, Runtime.getRuntime().availableProcessors());
        cpuThreads = maxCores;
        stateText = getString(R.string.state_ready);
        createNotificationChannels();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;

        if (ACTION_STOP.equals(action)) {
            stopHeating(false);
            return START_NOT_STICKY;
        }

        if (intent == null && !getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean(KEY_RUNNING, false)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        loadSettings();
        if (!running) {
            startHeating();
        } else {
            updateNotification();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        if (cpuBurner != null) {
            cpuBurner.stop();
            cpuBurner = null;
        }
        if (gpuBurner != null) {
            gpuBurner.stop();
            gpuBurner = null;
        }
        releaseWakeLock();
        listeners.clear();
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_RUNNING, false).apply();
        super.onDestroy();
    }

    private void loadSettings() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        targetTemp = clamp(prefs.getFloat(KEY_TARGET, 40f), MIN_TARGET, MAX_TARGET);
        cpuThreads = Math.max(1, Math.min(maxCores, prefs.getInt(KEY_CPU, maxCores)));
        gpuEnabled = prefs.getBoolean(KEY_GPU, true);
        keepAwake = prefs.getBoolean(KEY_KEEP_AWAKE, true);
    }

    private void startHeating() {
        if (running) {
            updateNotification();
            return;
        }

        stateText = getString(R.string.state_heating);
        startForegroundCompat(buildNotification());

        running = true;
        throttled = false;
        safetyStopped = false;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_RUNNING, true).apply();

        if (keepAwake) {
            acquireWakeLock();
        }

        cpuBurner = new CpuBurner(cpuThreads);
        cpuBurner.start();

        if (gpuEnabled) {
            gpuBurner = new GpuBurner();
            gpuBurner.start();
        }

        currentTemp = readBatteryTemperature(this);
        thermalStatus = readThermalStatus(this);
        handler.postDelayed(tickRunnable, 800);
        notifyListeners();
    }

    public void stopHeating(boolean safety) {
        boolean wasRunning = running;
        running = false;
        handler.removeCallbacks(tickRunnable);

        if (cpuBurner != null) {
            cpuBurner.stop();
            cpuBurner = null;
        }
        if (gpuBurner != null) {
            gpuBurner.stop();
            gpuBurner = null;
        }
        releaseWakeLock();
        throttled = false;

        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_RUNNING, false).apply();

        if (safety) {
            stateText = getString(R.string.state_safety);
            showSafetyNotification();
        } else if (wasRunning) {
            stateText = getString(R.string.state_stopped);
        }

        if (wasRunning) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        }
        notifyListeners();
        stopSelf();
    }

    public void addListener(Listener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    private void notifyListeners() {
        for (Listener listener : listeners) {
            handler.post(listener::onHeatStateChanged);
        }
    }

    public boolean isRunning() {
        return running;
    }

    public float getCurrentTemp() {
        return currentTemp;
    }

    public float getTargetTemp() {
        return targetTemp;
    }

    public int getCpuThreads() {
        return cpuThreads;
    }

    public int getMaxCores() {
        return maxCores;
    }

    public boolean isGpuEnabled() {
        return gpuEnabled;
    }

    public int getThermalStatus() {
        return thermalStatus;
    }

    public boolean isSafetyStopped() {
        return safetyStopped;
    }

    public String getStateText() {
        if (stateText == null) {
            return getString(R.string.state_ready);
        }
        return stateText;
    }

    public void setTargetTemp(float value) {
        targetTemp = clamp(value, MIN_TARGET, MAX_TARGET);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putFloat(KEY_TARGET, targetTemp).apply();
        if (running) {
            updateNotification();
        }
        notifyListeners();
    }

    public void setCpuThreads(int value) {
        int threads = Math.max(1, Math.min(maxCores, value));
        if (threads == cpuThreads) {
            return;
        }
        cpuThreads = threads;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putInt(KEY_CPU, cpuThreads).apply();

        if (running) {
            if (cpuBurner != null) {
                cpuBurner.stop();
            }
            cpuBurner = new CpuBurner(cpuThreads);
            cpuBurner.setThrottled(throttled);
            cpuBurner.start();
            updateNotification();
        }
        notifyListeners();
    }

    public void setGpuEnabled(boolean enabled) {
        if (gpuEnabled == enabled) {
            return;
        }
        gpuEnabled = enabled;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean(KEY_GPU, gpuEnabled).apply();

        if (running) {
            if (enabled) {
                if (gpuBurner == null) {
                    gpuBurner = new GpuBurner();
                    gpuBurner.setPaused(throttled);
                    gpuBurner.start();
                }
            } else if (gpuBurner != null) {
                gpuBurner.stop();
                gpuBurner = null;
            }
            updateNotification();
        }
        notifyListeners();
    }

    public void setKeepAwake(boolean enabled) {
        if (keepAwake == enabled) {
            return;
        }
        keepAwake = enabled;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean(KEY_KEEP_AWAKE, keepAwake).apply();

        if (running) {
            if (enabled) {
                acquireWakeLock();
            } else {
                releaseWakeLock();
            }
        }
        notifyListeners();
    }

    private void acquireWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            return;
        }
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        if (powerManager == null) {
            return;
        }
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CepIsitici::Heat");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire();
    }

    private void releaseWakeLock() {
        if (wakeLock != null) {
            try {
                if (wakeLock.isHeld()) {
                    wakeLock.release();
                }
            } catch (Exception ignored) {
            }
            wakeLock = null;
        }
    }

    private void createNotificationChannels() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }

        NotificationChannel serviceChannel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel),
                NotificationManager.IMPORTANCE_LOW);
        serviceChannel.setDescription(getString(R.string.notif_channel_desc));
        serviceChannel.setShowBadge(false);
        serviceChannel.setSound(null, null);
        manager.createNotificationChannel(serviceChannel);

        NotificationChannel alertChannel = new NotificationChannel(
                ALERT_CHANNEL_ID,
                getString(R.string.alerts_channel),
                NotificationManager.IMPORTANCE_DEFAULT);
        alertChannel.setDescription(getString(R.string.alerts_channel_desc));
        manager.createNotificationChannel(alertChannel);
    }

    private void startForegroundCompat(Notification notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void updateNotification() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        try {
            manager.notify(NOTIFICATION_ID, buildNotification());
        } catch (Exception ignored) {
        }
    }

    private Notification buildNotification() {
        String temp = Float.isNaN(currentTemp)
                ? "—"
                : String.format(Locale.getDefault(), "%.1f°C", currentTemp);
        String text = getString(R.string.notif_text, temp, Math.round(targetTemp),
                cpuThreads, maxCores);

        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stopIntent = new Intent(this, HeatService.class).setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(this, 1, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_flame)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(text)
                .setSubText(getStateText())
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .addAction(0, getString(R.string.notif_stop), stopPendingIntent)
                .build();
    }

    private void showSafetyNotification() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }

        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 2, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_flame)
                .setContentTitle(getString(R.string.alert_title))
                .setContentText(getString(R.string.alert_text))
                .setStyle(new NotificationCompat.BigTextStyle()
                        .bigText(getString(R.string.alert_text)))
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build();

        manager.notify(ALERT_NOTIFICATION_ID, notification);
    }

    public static float readBatteryTemperature(Context context) {
        try {
            Intent intent = context.registerReceiver(null,
                    new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (intent != null) {
                int raw = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE,
                        Integer.MIN_VALUE);
                if (raw != Integer.MIN_VALUE && raw != 0) {
                    return raw / 10f;
                }
            }
        } catch (Exception ignored) {
        }
        return readThermalZoneTemperature();
    }

    public static int readThermalStatus(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            PowerManager powerManager =
                    (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (powerManager != null) {
                return powerManager.getCurrentThermalStatus();
            }
        }
        return 0;
    }

    private static float readThermalZoneTemperature() {
        File thermalDir = new File("/sys/class/thermal");
        File[] zones = thermalDir.listFiles(
                (dir, name) -> name.startsWith("thermal_zone"));
        if (zones == null) {
            return Float.NaN;
        }

        float best = Float.NaN;
        for (File zone : zones) {
            String type = readFirstLine(new File(zone, "type"));
            if (type == null) {
                continue;
            }
            String lower = type.toLowerCase(Locale.US);
            if (!lower.contains("batt") && !lower.contains("bcl")
                    && !lower.contains("charger") && !lower.contains("case")) {
                continue;
            }
            String raw = readFirstLine(new File(zone, "temp"));
            if (raw == null) {
                continue;
            }
            try {
                float value = Float.parseFloat(raw.trim());
                if (value > 1000f) {
                    value /= 1000f;
                } else if (value > 200f) {
                    value /= 10f;
                }
                if (value > -50f && value < 150f
                        && (Float.isNaN(best) || value > best)) {
                    best = value;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return best;
    }

    private static String readFirstLine(File file) {
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            return reader.readLine();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
