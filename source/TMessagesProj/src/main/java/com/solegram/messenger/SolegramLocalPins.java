/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger;

import android.text.TextUtils;
import android.util.SparseArray;

import java.util.ArrayList;

/**
 * Chats pinned past Telegram's limit (5 without Premium), kept on this device: they stay on top of the main chat
 * list right under the chats Telegram pins, the newest first. Telegram's server never hears of them.
 */
public final class SolegramLocalPins {

    // per account: the pinned chats, the top one first
    private static final SparseArray<ArrayList<Long>> pins = new SparseArray<>();

    private SolegramLocalPins() {
    }

    private static String key(int account) {
        return "localPins_" + account;
    }

    private static ArrayList<Long> list(int account) {
        ArrayList<Long> list = pins.get(account);
        if (list == null) {
            list = new ArrayList<>();
            final String saved = SolegramConfig.prefs().getString(key(account), "");
            if (!TextUtils.isEmpty(saved)) {
                for (String part : saved.split(",")) {
                    try {
                        list.add(Long.parseLong(part));
                    } catch (NumberFormatException ignore) {
                    }
                }
            }
            pins.put(account, list);
        }
        return list;
    }

    private static void save(int account) {
        SolegramConfig.prefs().edit().putString(key(account), TextUtils.join(",", list(account))).apply();
    }

    public static synchronized boolean isPinned(int account, long dialogId) {
        return SolegramConfig.localPins && list(account).contains(dialogId);
    }

    /** Place among the chats pinned here: 0 for the top one, -1 when not pinned here. */
    public static synchronized int position(int account, long dialogId) {
        return SolegramConfig.localPins ? list(account).indexOf(dialogId) : -1;
    }

    public static synchronized boolean hasAny(int account) {
        return SolegramConfig.localPins && !list(account).isEmpty();
    }

    public static synchronized void pin(int account, long dialogId) {
        final ArrayList<Long> list = list(account);
        list.remove(dialogId);
        list.add(0, dialogId);
        save(account);
    }

    public static synchronized void unpin(int account, long dialogId) {
        if (list(account).remove(dialogId)) {
            save(account);
        }
    }
}
