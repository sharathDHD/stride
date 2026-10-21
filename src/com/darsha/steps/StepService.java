package com.darsha.steps;

import android.Manifest;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.IBinder;

import java.time.LocalDate;
import java.util.Locale;

/**
 * Foreground service that listens to the hardware pedometer and stores
 * daily aggregates locally. No network access exists in this app.
 */
public class StepService extends Service implements SensorEventListener {

    public static final String ACTION_STOP = "com.darsha.steps.STOP";
    public static volatile boolean RUNNING = false;

    private Store store;
    private SensorManager sensorManager;
    private boolean sensorOk = false;
    private long lastNotificationAt = 0;
    private long lastQuestSyncAt = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        store = Store.get(this);
        NotificationHelper.createChannels(this);
        RUNNING = true;
        try {
            startForeground(NotificationHelper.ID_SERVICE,
                    NotificationHelper.serviceNotification(this, todaySteps(), store.goal(), true));
        } catch (Exception e) {
            stopSelf();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        registerSensors();
        return START_STICKY;
    }

    private void registerSensors() {
        if (sensorManager == null) {
            sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        }
        if (sensorManager == null) return;
        sensorManager.unregisterListener(this);

        boolean canSense = Build.VERSION.SDK_INT < 29
                || checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION)
                == PackageManager.PERMISSION_GRANTED;
        if (!canSense) {
            sensorOk = false;
            updateNotification("Sensor permission needed",
                    "Open Stride and allow physical activity access");
            return;
        }

        Sensor counter = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
        if (counter != null) {
            sensorOk = sensorManager.registerListener(this, counter, SensorManager.SENSOR_DELAY_NORMAL);
        } else {
            Sensor detector = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR);
            if (detector != null) {
                sensorOk = sensorManager.registerListener(this, detector, SensorManager.SENSOR_DELAY_NORMAL);
            }
        }
        updateNotification(todaySteps(), store.goal(), sensorOk);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        Store.ApplyResult result = null;
        if (event.sensor.getType() == Sensor.TYPE_STEP_COUNTER) {
            result = store.applyCounter((long) event.values[0]);
        } else if (event.sensor.getType() == Sensor.TYPE_STEP_DETECTOR) {
            result = store.applyDetectedStep();
        }
        if (result == null) return;

        if (result.goalReachedNow) {
            NotificationHelper.goalReached(this, result.daySteps, result.goal);
        }

        long now = System.currentTimeMillis();
        if (result.delta > 0 && (now - lastNotificationAt > 5000 || result.goalReachedNow)) {
            lastNotificationAt = now;
            updateNotification(result.daySteps, result.goal, true);
        }

        // Quest progress is derived from stored daily totals, so a slow refresh is enough.
        if (now - lastQuestSyncAt > 60000) {
            lastQuestSyncAt = now;
            try {
                QuestEngine.sync(this);
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    private int todaySteps() {
        Store.Day day = store.day(LocalDate.now().toString());
        return day == null ? 0 : day.steps;
    }

    private void updateNotification(int steps, int goal, boolean ok) {
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.notify(NotificationHelper.ID_SERVICE,
                        NotificationHelper.serviceNotification(this, steps, goal, ok));
            }
        } catch (Exception ignored) {
        }
    }

    private void updateNotification(String title, String text) {
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.notify(NotificationHelper.ID_SERVICE,
                        NotificationHelper.serviceNotification(this, title, text));
            }
        } catch (Exception ignored) {
        }
    }

    public static String stepsLabel(int steps) {
        return String.format(Locale.US, "%,d", steps);
    }

    @Override
    public void onDestroy() {
        RUNNING = false;
        if (sensorManager != null) sensorManager.unregisterListener(this);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
