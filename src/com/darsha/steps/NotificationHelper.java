package com.darsha.steps;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import java.util.Locale;

public class NotificationHelper {

    public static final String CH_SERVICE = "stride_service";
    public static final String CH_GOALS = "stride_goals";
    public static final String CH_REMINDERS = "stride_reminders";

    public static final int ID_SERVICE = 1;
    public static final int ID_GOAL = 2;
    public static final int ID_REMINDER = 3;

    public static void createChannels(Context context) {
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null) return;

        NotificationChannel service = new NotificationChannel(
                CH_SERVICE, "Step counting", NotificationManager.IMPORTANCE_LOW);
        service.setDescription("Ongoing private step counter");
        service.setShowBadge(false);
        nm.createNotificationChannel(service);

        NotificationChannel goals = new NotificationChannel(
                CH_GOALS, "Goal celebrations", NotificationManager.IMPORTANCE_DEFAULT);
        goals.setDescription("A quiet celebration when you reach your daily goal");
        nm.createNotificationChannel(goals);

        NotificationChannel reminders = new NotificationChannel(
                CH_REMINDERS, "Walk reminders", NotificationManager.IMPORTANCE_DEFAULT);
        reminders.setDescription("Optional local reminder to take a walk");
        nm.createNotificationChannel(reminders);
    }

    private static PendingIntent openApp(Context context) {
        Intent intent = new Intent(context, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    public static Notification serviceNotification(Context context, int steps, int goal, boolean sensorOk) {
        if (sensorOk) {
            return serviceNotification(context,
                    String.format(Locale.US, "%,d steps today", steps),
                    String.format(Locale.US, "Goal %,d · counted on this phone", goal));
        }
        return serviceNotification(context, "Step sensor unavailable",
                "No hardware step counter was found on this device");
    }

    public static Notification serviceNotification(Context context, String title, String text) {
        return new Notification.Builder(context, CH_SERVICE)
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(openApp(context))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .build();
    }

    public static void goalReached(Context context, int steps, int goal) {
        Notification n = new Notification.Builder(context, CH_GOALS)
                .setSmallIcon(android.R.drawable.btn_star_big_on)
                .setContentTitle("Daily goal reached")
                .setContentText(String.format(Locale.US, "%,d of %,d steps. Nicely done.", steps, goal))
                .setContentIntent(openApp(context))
                .setAutoCancel(true)
                .build();
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(ID_GOAL, n);
    }

    public static void reminder(Context context) {
        Notification n = new Notification.Builder(context, CH_REMINDERS)
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentTitle("Time for a short walk")
                .setContentText("A few minutes outside keeps the momentum going.")
                .setContentIntent(openApp(context))
                .setAutoCancel(true)
                .build();
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(ID_REMINDER, n);
    }
}
