/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger;

import android.content.Intent;

import org.telegram.messenger.FileLog;

/**
 * Lets the plugin SDK (intents.IntentsManager) observe and intercept links / intents handled by
 * LaunchActivity.handleIntent.
 */
public final class SolegramIntents {

    /** Implemented by the plugin SDK (Python dynamic proxy). */
    public interface Listener {
        /** @return true to stop the default handling of the intent. */
        boolean before(Intent intent);

        void after(Intent intent);
    }

    private static volatile Listener listener;

    private SolegramIntents() {
    }

    public static void setListener(Listener l) {
        listener = l;
    }

    public static boolean before(Intent intent) {
        Listener l = listener;
        if (l == null || intent == null) {
            return false;
        }
        try {
            return l.before(intent);
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        }
    }

    public static void after(Intent intent) {
        Listener l = listener;
        if (l == null || intent == null) {
            return;
        }
        try {
            l.after(intent);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }
}
