package com.agy.core;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.util.Log;

import com.agy.proxy.BypassProxyServer;

/** Builds and updates the ongoing core notification. */
public final class ForegroundNotification {

    private static final String TAG = "ForegroundNotif";
    public static final int NOTIFICATION_ID = 1001;
    public static final String CHANNEL_ID = "antigravity_core_service_v2";

    private ForegroundNotification() {
    }

    public static void start(Service service) {
        NotificationManager manager =
                (NotificationManager) service.getSystemService(Service.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager != null) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Служба Antigravity", NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription("Постоянное уведомление фоновой службы Antigravity и быстрый доступ к настройкам");
            channel.setShowBadge(false);
            channel.setSound(null, null);
            channel.enableVibration(false);
            manager.createNotificationChannel(channel);
        }
        Notification notification = build(service, "Служба активна • Нажмите для настроек");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            service.startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            service.startForeground(NOTIFICATION_ID, notification);
        }
        Log.i(TAG, "Started foreground notification on channel: " + CHANNEL_ID);
    }

    public static void update(Service service, String status) {
        NotificationManager manager =
                (NotificationManager) service.getSystemService(Service.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, build(service, status));
        }
    }

    private static Notification build(Service service, String status) {
        int icon = service.getResources().getIdentifier(
                "ic_notification", "drawable", service.getPackageName());
        if (icon == 0) {
            icon = android.R.drawable.stat_notify_sync;
        }

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(service, CHANNEL_ID)
                : new Notification.Builder(service);

        // Main click intent -> opens ProxySettingsActivity
        Intent settingsIntent = new Intent(service, com.agy.ui.ProxySettingsActivity.class);
        settingsIntent.setAction("com.antigravity.mobile.ACTION_SETTINGS");
        settingsIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent piSettings = PendingIntent.getActivity(service, 1002, settingsIntent, flags);

        builder.setContentTitle("Antigravity")
                .setContentText(status)
                .setSmallIcon(icon)
                .setContentIntent(piSettings)
                .setOngoing(true)
                .setAutoCancel(false)
                .setOnlyAlertOnce(true);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            builder.setVisibility(Notification.VISIBILITY_PUBLIC);
            builder.setPriority(Notification.PRIORITY_DEFAULT);
            builder.setCategory(Notification.CATEGORY_SERVICE);
        }

        // Action 1: "⚙️ Настройки"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
            Notification.Action actionSettings = new Notification.Action.Builder(
                    icon, "⚙️ Настройки", piSettings).build();
            builder.addAction(actionSettings);

            // Action 2: Open Main App
            Intent mainIntent = service.getPackageManager().getLaunchIntentForPackage(service.getPackageName());
            if (mainIntent == null) {
                mainIntent = new Intent(service, com.agy.ui.launcher.LauncherDarkActivity.class);
            }
            mainIntent.setAction("com.antigravity.mobile.ACTION_MAIN_APP");
            mainIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            PendingIntent piMain = PendingIntent.getActivity(service, 1003, mainIntent, flags);
            Notification.Action actionMain = new Notification.Action.Builder(
                    icon, "Открыть Antigravity", piMain).build();
            builder.addAction(actionMain);
        }

        Notification notification = builder.build();
        // Make non-swipeable and persistent
        notification.flags |= Notification.FLAG_ONGOING_EVENT
                | Notification.FLAG_NO_CLEAR
                | Notification.FLAG_FOREGROUND_SERVICE;

        return notification;
    }
}
