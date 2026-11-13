/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Ignore mode for a chat, a channel or a person: until it is turned off, Solegram does not let you see that they
 * wrote. No notifications, no unread counter (in the list, on the tabs and on the app icon), no preview of the new
 * messages in the list, and the messages that came after the mode was turned on are not shown in the chat either.
 * A ghost next to the name shows that the mode is on. Turning it off brings everything back as it is: nothing is
 * read, nothing is deleted, the other side notices nothing.
 * <p>
 * Kept on this device, per account: the dialog and the last message that was there when the mode was turned on.
 */
public final class SolegramIgnore {

    private static final String PREFS = "solegram_ignore";
    /** account -> (dialog id -> the top message id when the mode was turned on) */
    private static final HashMap<Integer, HashMap<Long, Integer>> cache = new HashMap<>();
    private static final HashMap<Integer, long[]> sortedIds = new HashMap<>();

    private SolegramIgnore() {
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static synchronized HashMap<Long, Integer> of(int account) {
        HashMap<Long, Integer> m = cache.get(account);
        if (m == null) {
            m = new HashMap<>();
            final String s = prefs().getString("a" + account, "");
            for (String part : s.split(";")) {
                final int colon = part.indexOf(':');
                if (colon > 0) {
                    try {
                        m.put(Long.parseLong(part.substring(0, colon)), Integer.parseInt(part.substring(colon + 1)));
                    } catch (NumberFormatException ignore) {
                    }
                }
            }
            cache.put(account, m);
        }
        return m;
    }

    private static synchronized void save(int account) {
        final StringBuilder sb = new StringBuilder();
        for (Map.Entry<Long, Integer> e : of(account).entrySet()) {
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append(e.getKey()).append(':').append(e.getValue());
        }
        prefs().edit().putString("a" + account, sb.toString()).apply();
        sortedIds.remove(account);
    }

    public static synchronized boolean isIgnored(int account, long dialogId) {
        return dialogId != 0 && of(account).containsKey(dialogId);
    }

    /** Turns the mode on or off for a chat; returns whether it is now on. */
    public static boolean toggle(int account, long dialogId) {
        final boolean on;
        synchronized (SolegramIgnore.class) {
            final HashMap<Long, Integer> m = of(account);
            if (m.containsKey(dialogId)) {
                m.remove(dialogId);
                on = false;
            } else {
                final TLRPC.Dialog dialog = MessagesController.getInstance(account).dialogs_dict.get(dialogId);
                m.put(dialogId, dialog != null ? dialog.top_message : 0);
                on = true;
            }
            save(account);
        }
        final NotificationCenter nc = NotificationCenter.getInstance(account);
        nc.postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_READ_DIALOG_MESSAGE | MessagesController.UPDATE_MASK_NAME | MessagesController.UPDATE_MASK_CHAT_NAME);
        nc.postNotificationName(NotificationCenter.dialogsNeedReload);
        org.telegram.messenger.NotificationsController.getInstance(account).updateBadge();
        return on;
    }

    /** A message that came after the mode was turned on, from them: not shown while the mode is on. */
    public static boolean hides(int account, long dialogId, MessageObject m) {
        if (m == null || m.isOut() || m.getId() <= 0) {
            return false;
        }
        final Integer from;
        synchronized (SolegramIgnore.class) {
            from = of(account).get(dialogId);
        }
        return from != null && m.getId() > from;
    }

    /** Whether a dialog's last message is one the mode hides (the list then shows no preview and no counter). */
    public static boolean hidesTop(int account, TLRPC.Dialog dialog) {
        if (dialog == null) {
            return false;
        }
        final Integer from;
        synchronized (SolegramIgnore.class) {
            from = of(account).get(dialog.id);
        }
        return from != null;
    }

    /** The hidden chats' ids with the ignored ones added, sorted (for the app icon's counter). */
    public static long[] withIgnored(int account, long[] hidden) {
        long[] ignored;
        synchronized (SolegramIgnore.class) {
            ignored = sortedIds.get(account);
            if (ignored == null) {
                final HashMap<Long, Integer> m = of(account);
                ignored = new long[m.size()];
                int i = 0;
                for (Long id : m.keySet()) {
                    ignored[i++] = id;
                }
                Arrays.sort(ignored);
                sortedIds.put(account, ignored);
            }
        }
        if (ignored.length == 0) {
            return hidden;
        }
        if (hidden == null || hidden.length == 0) {
            return ignored;
        }
        final long[] all = Arrays.copyOf(hidden, hidden.length + ignored.length);
        System.arraycopy(ignored, 0, all, hidden.length, ignored.length);
        Arrays.sort(all);
        return all;
    }

    /** The account a dialog cell or a header belongs to is the selected one unless told otherwise. */
    public static boolean isIgnored(long dialogId) {
        return isIgnored(UserConfig.selectedAccount, dialogId);
    }
}
