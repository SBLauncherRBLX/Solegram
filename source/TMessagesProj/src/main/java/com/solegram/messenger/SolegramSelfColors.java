/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger;

import android.content.SharedPreferences;

import org.telegram.tgnet.TLRPC;

/**
 * The colours of the user's own name and profile (and their emoji patterns), like Premium's, but kept on this
 * device: Telegram's own colour screen saves here when the account has no Premium, and the app draws the user with
 * them wherever it asks for the user's colours. Nobody else sees them.
 */
public final class SolegramSelfColors {

    private static boolean loaded;
    private static boolean set;
    private static int nameColor, profileColor;
    private static long nameEmoji, profileEmoji;

    private SolegramSelfColors() {
    }

    private static void load() {
        if (loaded) {
            return;
        }
        final SharedPreferences p = SolegramConfig.prefs();
        set = p.getBoolean("selfColorsSet", false);
        nameColor = p.getInt("selfNameColor", 0);
        nameEmoji = p.getLong("selfNameEmoji", 0);
        profileColor = p.getInt("selfProfileColor", -1);
        profileEmoji = p.getLong("selfProfileEmoji", 0);
        loaded = true;
    }

    /** Whether the user's own colours come from here (for this user object). */
    public static boolean applies(TLRPC.User user) {
        if (user == null || !user.self) {
            return false;
        }
        load();
        return set;
    }

    public static int nameColor() {
        load();
        return nameColor;
    }

    public static long nameEmoji() {
        load();
        return nameEmoji;
    }

    /** -1 = the profile keeps its plain look. */
    public static int profileColor() {
        load();
        return profileColor;
    }

    public static long profileEmoji() {
        load();
        return profileEmoji;
    }

    public static void save(int nameColor, long nameEmoji, int profileColor, long profileEmoji) {
        SolegramConfig.prefs().edit()
            .putBoolean("selfColorsSet", true)
            .putInt("selfNameColor", nameColor)
            .putLong("selfNameEmoji", nameEmoji)
            .putInt("selfProfileColor", profileColor)
            .putLong("selfProfileEmoji", profileEmoji)
            .apply();
        loaded = false;
    }

    /** Back to the colours the account really has. */
    public static void reset() {
        SolegramConfig.prefs().edit()
            .remove("selfColorsSet").remove("selfNameColor").remove("selfNameEmoji").remove("selfProfileColor").remove("selfProfileEmoji")
            .apply();
        loaded = false;
    }

    public static boolean isSet() {
        load();
        return set;
    }
}
