/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Reminder alarms and notification buttons; also sets the alarm again after a reboot, an app update, a change of
 * the clock or time zone, and when exact alarms are allowed or taken back.
 */
public class SolegramReminderReceiver extends BroadcastReceiver {

    // its own thread: reading chat photos and the accounts' storage must not hold up Telegram's queues
    private static final ExecutorService worker = Executors.newSingleThreadExecutor();

    @Override
    public void onReceive(Context context, Intent intent) {
        final String action = intent != null ? intent.getAction() : null;
        if (action == null) {
            return;
        }
        ApplicationLoader.postInitApplication();
        final int id = intent.getIntExtra(SolegramReminders.EXTRA_ID, 0);
        final PendingResult result = goAsync();
        worker.execute(() -> {
            try {
                if (SolegramReminders.ACTION_SNOOZE.equals(action)) {
                    SolegramReminders.snooze(id);
                } else if (SolegramReminders.ACTION_DONE.equals(action)) {
                    SolegramReminders.doneFromNotification(id);
                } else {
                    // the alarm, boot, update, clock, time zone, exact alarm permission: what is due now, then the next alarm
                    SolegramReminders.deliverDue();
                    SolegramReminders.scheduleNow();
                }
            } catch (Throwable e) {
                FileLog.e(e);
            } finally {
                SolegramReminders.flush();
                result.finish();
            }
        });
    }
}
