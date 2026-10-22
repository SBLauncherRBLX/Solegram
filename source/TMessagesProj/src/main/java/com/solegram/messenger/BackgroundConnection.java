/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.provider.Settings;

import androidx.core.app.NotificationCompat;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationsService;
import org.telegram.messenger.R;

/**
 * Forks of Telegram cannot receive Firebase pushes sent for the official app, so Solegram keeps
 * one lightweight MTProto connection alive in a foreground service to deliver notifications.
 */
public final class BackgroundConnection {

    public static final String CHANNEL_ID = "solegram_background";
    private static final int NOTIFICATION_ID = 0x50e6;

    private static volatile boolean serviceRunning;

    private BackgroundConnection() {
    }

    /** Re-applies the push connection flag on every logged-in account (it keeps the network alive in background). */
    public static void applyPushConnection() {
        for (int a = 0; a < org.telegram.messenger.UserConfig.MAX_ACCOUNT_COUNT; a++) {
            try {
                if (org.telegram.messenger.UserConfig.getInstance(a).isClientActivated()) {
                    org.telegram.tgnet.ConnectionsManager cm = org.telegram.tgnet.ConnectionsManager.getInstance(a);
                    cm.setPushConnectionEnabled(cm.isPushConnectionEnabled());
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
    }

    /** Called when the UI comes to the foreground: restarts the service if Android refused a background start. */
    public static void ensureRunning(Context context) {
        if (SolegramConfig.backgroundConnection && !serviceRunning) {
            start(context);
        }
    }

    public static void onServiceDestroyed() {
        serviceRunning = false;
    }

    public static void start(Context context) {
        if (!SolegramConfig.backgroundConnection) {
            stop(context);
            return;
        }
        try {
            Intent intent = new Intent(context, NotificationsService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Throwable e) {
            // Starting from the background is not allowed on Android 12+; it will start with the UI.
            FileLog.e(e);
        }
    }

    public static void stop(Context context) {
        try {
            context.stopService(new Intent(context, NotificationsService.class));
        } catch (Throwable ignore) {
        }
    }

    /** Called from NotificationsService.onCreate/onStartCommand. */
    public static void promote(Service service) {
        if (Build.VERSION.SDK_INT < 26) {
            serviceRunning = true;
            return;
        }
        try {
            NotificationManager nm = (NotificationManager) service.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel channel = new NotificationChannel(CHANNEL_ID, LocaleController.getString(R.string.SG_BackgroundChannel), NotificationManager.IMPORTANCE_MIN);
                channel.setShowBadge(false);
                channel.enableVibration(false);
                channel.setSound(null, null);
                nm.createNotificationChannel(channel);
            }
            Intent settings = new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, service.getPackageName())
                .putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL_ID)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent pi = PendingIntent.getActivity(service, 0, settings, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification n = new NotificationCompat.Builder(service, CHANNEL_ID)
                .setSmallIcon(R.drawable.notification)
                .setContentTitle(LocaleController.getString(R.string.SG_BackgroundTitle))
                .setContentText(LocaleController.getString(R.string.SG_BackgroundText))
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setOngoing(true)
                .setShowWhen(false)
                .setContentIntent(pi)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build();
            if (Build.VERSION.SDK_INT >= 34) {
                service.startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING);
            } else {
                service.startForeground(NOTIFICATION_ID, n);
            }
            serviceRunning = true;
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    public static boolean shouldRun() {
        return SolegramConfig.backgroundConnection && ApplicationLoader.applicationContext != null;
    }
}
