/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.plugins;

import android.os.SystemClock;

import com.solegram.messenger.SolegramConfig;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.ForegroundDetector;
import org.telegram.ui.LaunchActivity;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The developer server (plugin dev mode) listens on 127.0.0.1, which every app on the phone can reach, and it
 * installs and runs plugin code. So a connection may do more than "ping" only after the user allowed it here,
 * on the phone. A connection that was allowed lets a reconnect of the same tool through for a short while.
 */
public final class PluginDevGate {

    private static final long WAIT = 60_000;
    /** A tool that reconnects right after an allowed connection is not asked again. */
    private static final long GRACE = 2 * 60_000;
    /** After "Deny" (or no answer) new connections are refused without asking for a moment: no dialog storm. */
    private static final long DENY_COOLDOWN = 30_000;

    private static final Object lock = new Object();
    private static long graceUntil;
    private static long deniedUntil;

    private PluginDevGate() {
    }

    /** Dev mode was switched: whatever was allowed before is forgotten. */
    public static void reset() {
        synchronized (lock) {
            graceUntil = 0;
            deniedUntil = 0;
        }
    }

    /** An allowed connection is still in use: a reconnect of it stays allowed a little longer. */
    public static void touch() {
        synchronized (lock) {
            if (graceUntil != 0) {
                graceUntil = SystemClock.elapsedRealtime() + GRACE;
            }
        }
    }

    /**
     * Asks the user whether a new dev connection may control plugins. Blocks the calling (dev server) thread up
     * to a minute; never call it on the UI thread.
     */
    public static boolean approve() {
        if (!SolegramConfig.pluginsDevMode) {
            return false;
        }
        // one dialog at a time: further connections wait here for its answer
        synchronized (lock) {
            final long now = SystemClock.elapsedRealtime();
            if (now < graceUntil) {
                graceUntil = now + GRACE;
                return true;
            }
            if (now < deniedUntil) {
                return false;
            }
            final boolean allowed = ask();
            if (allowed) {
                graceUntil = SystemClock.elapsedRealtime() + GRACE;
            } else {
                graceUntil = 0;
                deniedUntil = SystemClock.elapsedRealtime() + DENY_COOLDOWN;
            }
            return allowed;
        }
    }

    private static boolean ask() {
        final CountDownLatch answered = new CountDownLatch(1);
        final boolean[] result = new boolean[1];
        final AlertDialog[] shown = new AlertDialog[1];
        AndroidUtilities.runOnUIThread(() -> {
            try {
                final ForegroundDetector fd = ForegroundDetector.getInstance();
                final BaseFragment fragment = LaunchActivity.getSafeLastFragment();
                // nobody could see the question: the answer is no, the tool asks again once the app is open
                if (fd == null || !fd.isForeground() || fragment == null || fragment.getParentActivity() == null) {
                    answered.countDown();
                    return;
                }
                final AlertDialog dialog = new AlertDialog.Builder(fragment.getParentActivity(), fragment.getResourceProvider())
                    .setTitle(LocaleController.getString(R.string.SG_DevConnectTitle))
                    .setMessage(AndroidUtilities.replaceTags(LocaleController.getString(R.string.SG_DevConnectText)))
                    .setPositiveButton(LocaleController.getString(R.string.SG_DevConnectAllow), (d, w) -> {
                        result[0] = true;
                        answered.countDown();
                    })
                    .setNegativeButton(LocaleController.getString(R.string.SG_DevConnectDeny), (d, w) -> answered.countDown())
                    .create();
                dialog.setOnDismissListener(d -> answered.countDown());
                dialog.setCanceledOnTouchOutside(false);
                shown[0] = dialog;
                fragment.showDialog(dialog);
                final android.view.View allow = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
                if (allow instanceof android.widget.TextView) {
                    ((android.widget.TextView) allow).setTextColor(Theme.getColor(Theme.key_text_RedBold));
                }
            } catch (Throwable e) {
                FileLog.e(e);
                answered.countDown();
            }
        });
        try {
            if (!answered.await(WAIT, TimeUnit.MILLISECONDS)) {
                AndroidUtilities.runOnUIThread(() -> {
                    if (shown[0] != null) {
                        try {
                            shown[0].dismiss();
                        } catch (Throwable ignore) {
                        }
                    }
                });
                return false;
            }
        } catch (InterruptedException e) {
            return false;
        }
        return result[0];
    }
}
