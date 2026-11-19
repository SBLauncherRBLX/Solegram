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
 * them wherever it asks for the user's colours. The profile's colour and emoji also go onto the Solegram profile
 * card, so others with Solegram see them (see SolegramProfileCard).
 */
public final class SolegramSelfColors {

    private static boolean loaded;
    private static boolean set;
    private static int nameColor, profileColor;
    private static long nameEmoji, profileEmoji;
    // own colours of the profile (top, bottom of its gradient); 0: none
    private static int custom1, custom2;
    // own colours of the name (the name, the reply line's second stripe); 0: none
    private static int nameCustom1, nameCustom2;

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
        custom1 = p.getInt("selfCustom1", 0);
        custom2 = p.getInt("selfCustom2", 0);
        nameCustom1 = p.getInt("selfNameCustom1", 0);
        nameCustom2 = p.getInt("selfNameCustom2", 0);
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

    public static int customColor1() {
        load();
        return custom1;
    }

    public static int customColor2() {
        load();
        return custom2;
    }

    public static int nameCustom1() {
        load();
        return nameCustom1;
    }

    public static int nameCustom2() {
        load();
        return nameCustom2;
    }

    /** The name's own colours (0, 0: back to the palette colour). */
    public static void saveNameCustomColors(int c1, int c2) {
        SolegramConfig.prefs().edit().putInt("selfNameCustom1", c1).putInt("selfNameCustom2", c2).apply();
        loaded = false;
    }

    /** The colour id the own name is drawn with when own colours are chosen, else -1. */
    public static int ownNameCustomId() {
        load();
        return nameCustom1 != 0 ? SolegramCustomColors.nameId(nameCustom1, nameCustom2 != 0 ? nameCustom2 : nameCustom1) : -1;
    }

    /** The profile's own colours (0, 0: back to the palette colour). */
    public static void saveCustomColors(int c1, int c2) {
        SolegramConfig.prefs().edit().putInt("selfCustom1", c1).putInt("selfCustom2", c2).apply();
        loaded = false;
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
            .remove("selfCustom1").remove("selfCustom2").remove("selfNameCustom1").remove("selfNameCustom2")
            .apply();
        loaded = false;
    }

    /** The profile colour made of someone's own colours (the own ones from here, others' from their profile card),
     *  or null. UI thread. */
    @androidx.annotation.Nullable
    public static org.telegram.messenger.MessagesController.PeerColor customPeerColor(TLRPC.User user) {
        if (user == null) {
            return null;
        }
        int c1, c2;
        if (user.self) {
            load();
            c1 = custom1;
            c2 = custom2;
        } else {
            final int[] own = SolegramProfileCard.customColorsOf(user.id);
            if (own == null) {
                return null;
            }
            c1 = own[0];
            c2 = own[1];
        }
        return org.telegram.messenger.MessagesController.PeerColor.fromCustom(c1, c2);
    }

    public static boolean isSet() {
        load();
        return set;
    }
}
