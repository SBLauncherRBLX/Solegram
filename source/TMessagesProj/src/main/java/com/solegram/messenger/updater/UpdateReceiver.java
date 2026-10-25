/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.updater;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

/**
 * The result of an install session (explicit, not exported: only the system reports here), and the first start
 * of a new version: the process comes up again after the update, so pushes, the update schedule and the
 * "updated" message are set up without the user opening the app.
 */
public class UpdateReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        final String action = intent != null ? intent.getAction() : null;
        if (action == null) {
            return;
        }
        try {
            ApplicationLoader.postInitApplication();
            if (UpdateInstaller.ACTION_STATUS.equals(action)) {
                AutoUpdater.onInstallStatus(intent);
            } else if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
                AutoUpdater.start();
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }
}
