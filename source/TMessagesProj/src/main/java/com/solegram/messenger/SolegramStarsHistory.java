/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.tl.TL_stars;

/**
 * Clearing the Stars history: Telegram keeps every transaction and gives no way to delete one, so Solegram hides
 * them on this device — everything up to the moment of clearing; what comes after shows as usual. It can be shown
 * again at any time.
 */
public final class SolegramStarsHistory {

    private SolegramStarsHistory() {
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("solegram_stars_history", Context.MODE_PRIVATE);
    }

    private static String key(int account) {
        return "cleared_" + UserConfig.getInstance(account).getClientUserId();
    }

    /** Transactions at or before this moment (unix seconds) are hidden; 0 when nothing is. */
    public static int clearedAt(int account) {
        return prefs().getInt(key(account), 0);
    }

    public static boolean isCleared(int account) {
        return clearedAt(account) > 0;
    }

    public static void clear(int account) {
        prefs().edit().putInt(key(account), (int) (System.currentTimeMillis() / 1000L)).apply();
    }

    public static void restore(int account) {
        prefs().edit().remove(key(account)).apply();
    }

    public static boolean hidden(int account, TL_stars.StarsTransaction t) {
        final int at = clearedAt(account);
        return at > 0 && t != null && t.date <= at;
    }
}
