/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger;

import android.text.TextUtils;

import androidx.collection.LongSparseArray;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_account;

import java.util.Iterator;

/**
 * The emoji next to the own name, shown to other Solegram users: it travels as an invisible mark at the end of
 * the bio (an invisible separator and sixteen variation selectors, the emoji's id four bits each). Telegram and
 * other apps show nothing; Solegram reads the mark when it loads a profile, draws the emoji next to that user's
 * name wherever it is drawn, and hides the mark from the bio. A real Premium status always wins.
 */
public final class SolegramNameEmojiShare {

    private static final char START = '⁣';
    private static final int DIGITS = 16;
    private static final int LENGTH = DIGITS + 1;
    private static final String KEY_KNOWN = "sharedNameEmoji";
    private static final int MAX_KNOWN = 3000;

    /** Someone else's emoji, read from their bio. */
    public static final class SharedStatus extends TLRPC.TL_emojiStatus {
        SharedStatus(long documentId) {
            this.document_id = documentId;
        }
    }

    // other users' emoji: user id -> emoji id (0 = known to have none)
    private static LongSparseArray<Long> known;
    private static boolean syncScheduled;

    private SolegramNameEmojiShare() {
    }

    public static boolean enabled() {
        return SolegramConfig.nameEmojiShare;
    }

    // region The mark

    public static String mark(long documentId) {
        final StringBuilder sb = new StringBuilder(LENGTH);
        sb.append(START);
        for (int i = DIGITS - 1; i >= 0; i--) {
            sb.append((char) (0xFE00 + (int) ((documentId >>> (i * 4)) & 0xF)));
        }
        return sb.toString();
    }

    /** The emoji id in a bio, 0 when there is no mark. */
    public static long read(String about) {
        if (about == null) {
            return 0;
        }
        final int at = about.lastIndexOf(START);
        if (at < 0 || about.length() - at - 1 < DIGITS) {
            return 0;
        }
        long id = 0;
        for (int i = 0; i < DIGITS; i++) {
            final char c = about.charAt(at + 1 + i);
            if (c < 0xFE00 || c > 0xFE0F) {
                return 0;
            }
            id = (id << 4) | (c - 0xFE00);
        }
        return id;
    }

    /** The bio without the mark (for showing and editing). */
    public static String strip(String about) {
        if (about == null || about.indexOf(START) < 0) {
            return about;
        }
        final StringBuilder out = new StringBuilder(about.length());
        for (int i = 0; i < about.length(); i++) {
            final char c = about.charAt(i);
            if (c == START) {
                int j = i + 1;
                while (j < about.length() && about.charAt(j) >= 0xFE00 && about.charAt(j) <= 0xFE0F && j - i <= DIGITS) {
                    j++;
                }
                i = j - 1;
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    /** The own mark to put at the end of the bio, or "" when nothing is shared. */
    public static String ownMark() {
        final long id = SolegramNameEmoji.get();
        return enabled() && id != 0 ? mark(id) : "";
    }

    /** A bio the user wrote, with the mark put back when it fits the account's limit. */
    public static String withMark(int account, String bio) {
        final String base = strip(bio == null ? "" : bio);
        final String mark = ownMark();
        if (mark.isEmpty()) {
            return base;
        }
        final int limit = MessagesController.getInstance(account).getAboutLimit();
        if (base.codePointCount(0, base.length()) + LENGTH > limit) {
            return base;
        }
        return base + mark;
    }

    /** Whether the mark does not fit after this bio. */
    public static boolean tooLong(int account, String bio) {
        final String base = strip(bio == null ? "" : bio);
        return base.codePointCount(0, base.length()) + LENGTH > MessagesController.getInstance(account).getAboutLimit();
    }
    // endregion

    // region Others

    private static synchronized LongSparseArray<Long> known() {
        if (known == null) {
            known = new LongSparseArray<>();
            try {
                final JSONObject o = new JSONObject(SolegramConfig.prefs().getString(KEY_KNOWN, "{}"));
                final Iterator<String> keys = o.keys();
                while (keys.hasNext()) {
                    final String k = keys.next();
                    known.put(Long.parseLong(k), o.getLong(k));
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
        return known;
    }

    private static synchronized void saveKnown() {
        try {
            final JSONObject o = new JSONObject();
            final LongSparseArray<Long> map = known();
            final int from = Math.max(0, map.size() - MAX_KNOWN);
            for (int i = from; i < map.size(); i++) {
                if (map.valueAt(i) != 0) {
                    o.put(String.valueOf(map.keyAt(i)), (long) map.valueAt(i));
                }
            }
            SolegramConfig.prefs().edit().putString(KEY_KNOWN, o.toString()).apply();
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /** A profile was loaded: its mark (or that it has none now) is noted and the user drawn with it. */
    public static void onUserFull(int account, TLRPC.User user, TLRPC.UserFull full) {
        if (user == null || full == null || user.self || user.bot) {
            return;
        }
        final long id = read(full.about);
        final Long was;
        synchronized (SolegramNameEmojiShare.class) {
            was = known().get(user.id);
            if (was == null && id == 0) {
                return;
            }
            if (was != null && was == id) {
                apply(user);
                return;
            }
            known().put(user.id, id);
            saveKnown();
        }
        apply(user);
        AndroidUtilities.runOnUIThread(() -> {
            apply(MessagesController.getInstance(account).getUser(user.id));
            NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_EMOJI_STATUS);
        });
    }

    /** Draws someone with the emoji their Solegram shares (unless they have a real status); any user object. */
    public static void apply(TLRPC.User user) {
        if (user == null || user.self) {
            return;
        }
        final Long id;
        synchronized (SolegramNameEmojiShare.class) {
            if (known == null && !SolegramConfig.prefs().contains(KEY_KNOWN)) {
                return;
            }
            id = known().get(user.id);
        }
        final boolean ours = user.emoji_status instanceof SharedStatus;
        if (id == null || id == 0) {
            if (ours) {
                user.emoji_status = null;
            }
            return;
        }
        if (ours) {
            if (((SharedStatus) user.emoji_status).document_id != id) {
                user.emoji_status = new SharedStatus(id);
            }
            return;
        }
        if (user.emoji_status == null || user.emoji_status instanceof TLRPC.TL_emojiStatusEmpty) {
            user.emoji_status = new SharedStatus(id);
        }
    }
    // endregion

    // region The own bio

    /** After the emoji or the setting changed (and when the app starts): the bio of every account gets its mark. */
    public static void scheduleSync() {
        if (syncScheduled) {
            return;
        }
        syncScheduled = true;
        AndroidUtilities.runOnUIThread(() -> {
            syncScheduled = false;
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                if (UserConfig.getInstance(a).isClientActivated()) {
                    sync(a);
                }
            }
        }, 3000);
    }

    private static void sync(int account) {
        final TLRPC.User self = UserConfig.getInstance(account).getCurrentUser();
        if (self == null || SolegramNowPlayingBio.writesNow(self.id)) {
            return; // the music line stands in the bio now; it carries the mark and gives the bio back with it
        }
        final TLRPC.TL_users_getFullUser req = new TLRPC.TL_users_getFullUser();
        req.id = MessagesController.getInstance(account).getInputUser(self);
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> {
            final TLRPC.UserFull full = response instanceof TLRPC.TL_users_userFull ? ((TLRPC.TL_users_userFull) response).full_user : null;
            if (full == null) {
                return;
            }
            AndroidUtilities.runOnUIThread(() -> {
                final String about = full.about == null ? "" : full.about;
                final String target = withMark(account, about);
                if (TextUtils.equals(about, target)) {
                    return;
                }
                final TL_account.updateProfile up = new TL_account.updateProfile();
                up.about = target;
                up.flags |= 4;
                ConnectionsManager.getInstance(account).sendRequest(up, (r, e) -> AndroidUtilities.runOnUIThread(() -> {
                    if (e != null) {
                        FileLog.e("solegram name emoji share: " + e.text);
                        return;
                    }
                    final TLRPC.UserFull own = MessagesController.getInstance(account).getUserFull(full.id);
                    if (own != null) {
                        own.about = target;
                        own.flags = TextUtils.isEmpty(target) ? own.flags & ~2 : own.flags | 2;
                        MessagesStorage.getInstance(account).updateUserInfo(own, false);
                        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.userInfoDidLoad, own.id, own);
                    }
                }));
            });
        });
    }
    // endregion
}
