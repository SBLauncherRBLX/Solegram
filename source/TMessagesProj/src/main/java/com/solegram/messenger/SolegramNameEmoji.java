/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger;

import android.util.SparseArray;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;

/**
 * An emoji next to the user's own name, like Premium's emoji status, but kept on this device: Solegram shows it
 * wherever the user's name is drawn with a status (profile, settings, member lists), nobody else sees it, and it
 * works without Premium. The emoji is put into the user's own {@link TLRPC.User} object without its flag, so it is
 * never saved or sent; the status the server gave is kept aside and comes back when the emoji is removed.
 */
public final class SolegramNameEmoji {

    /** Our emoji, told apart from a status the server gave. */
    public static final class LocalStatus extends TLRPC.TL_emojiStatus {
        LocalStatus(long documentId) {
            this.document_id = documentId;
        }
    }

    /** The account's own status from the server, per account, while ours is shown instead. */
    private static final SparseArray<TLRPC.EmojiStatus> serverStatus = new SparseArray<>();

    private SolegramNameEmoji() {
    }

    /** Custom emoji document id, 0 = none. */
    public static long get() {
        return SolegramConfig.nameEmoji;
    }

    /** Saves the choice and shows it at once on every account. */
    public static void set(long documentId) {
        if (documentId == 0) {
            SolegramConfig.prefs().edit().remove("nameEmoji").apply();
            SolegramConfig.reload();
        } else {
            SolegramConfig.putLong("nameEmoji", documentId);
        }
        AndroidUtilities.runOnUIThread(() -> {
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                if (!UserConfig.isValidAccount(a)) {
                    continue;
                }
                final UserConfig config = UserConfig.getInstance(a);
                final TLRPC.User current = config.getCurrentUser();
                apply(a, current);
                final TLRPC.User cached = MessagesController.getInstance(a).getUser(config.getClientUserId());
                if (cached != current) {
                    apply(a, cached);
                }
                if (current != null) {
                    NotificationCenter.getInstance(a).postNotificationName(NotificationCenter.userEmojiStatusUpdated, current);
                }
                NotificationCenter.getInstance(a).postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_EMOJI_STATUS);
            }
        });
    }

    /**
     * Puts our emoji into the account's own user (or takes it back out); any other user is left alone.
     * Called wherever the app takes in the own user.
     */
    public static void apply(int account, TLRPC.User user) {
        if (user == null || account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT || !user.self && user.id != UserConfig.getInstance(account).getClientUserId()) {
            return;
        }
        final long id = get();
        synchronized (serverStatus) {
            if (id != 0) {
                if (user.emoji_status instanceof LocalStatus) {
                    if (((LocalStatus) user.emoji_status).document_id == id) {
                        return;
                    }
                } else {
                    serverStatus.put(account, user.emoji_status);
                }
                user.emoji_status = new LocalStatus(id);
            } else if (user.emoji_status instanceof LocalStatus) {
                user.emoji_status = serverStatus.get(account);
                serverStatus.remove(account);
            }
        }
    }
}
