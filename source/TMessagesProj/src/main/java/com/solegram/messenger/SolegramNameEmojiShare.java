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
    /** The profile's look (colour, own colours, background emoji) has a mark of its own, before the name's. */
    private static final char START_PROFILE = '\u2064';
    private static final int PROFILE_VERSION = 1;
    private static final int PF_COLOR = 1, PF_CUSTOM = 2, PF_EMOJI = 4;
    private static final int PROFILE_MAX = 2 + 2 + 12 + 16;
    private static final String KEY_PROFILES = "sharedProfileLooks";
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
        return mark(documentId, 0);
    }

    /** The mark with the emoji's id and, after it, the name's effect (if any: four more selectors). */
    public static String mark(long documentId, int effect) {
        final StringBuilder sb = new StringBuilder(LENGTH + SolegramNameEffect.MARK_DIGITS);
        sb.append(START);
        for (int i = DIGITS - 1; i >= 0; i--) {
            sb.append((char) (0xFE00 + (int) ((documentId >>> (i * 4)) & 0xF)));
        }
        sb.append(SolegramNameEffect.markDigits(effect));
        return sb.toString();
    }

    /** The name's effect in a bio's mark, 0 when there is none. */
    public static int readEffect(String about) {
        if (about == null) {
            return 0;
        }
        final int at = about.lastIndexOf(START);
        if (at < 0 || about.length() - at - 1 < DIGITS) {
            return 0;
        }
        return SolegramNameEffect.readDigits(about, at + 1 + DIGITS);
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

    // region The profile's look

    /** Someone's profile look from their bio: a palette colour (-1: none), own colours (0: none), background emoji. */
    public static final class ProfileLook {
        public int color = -1;
        public int custom1, custom2;
        public long emoji;

        boolean isEmpty() {
            return color < 0 && (custom1 == 0 || custom2 == 0) && emoji == 0;
        }

        String encode() {
            return color + "," + Integer.toHexString(custom1) + "," + Integer.toHexString(custom2) + "," + emoji;
        }

        static ProfileLook decode(String v) {
            try {
                final String[] f = v.split(",");
                final ProfileLook l = new ProfileLook();
                l.color = Integer.parseInt(f[0]);
                l.custom1 = (int) Long.parseLong(f[1], 16);
                l.custom2 = (int) Long.parseLong(f[2], 16);
                l.emoji = Long.parseLong(f[3]);
                return l;
            } catch (Throwable e) {
                return null;
            }
        }
    }

    private static void digits(StringBuilder sb, long value, int count) {
        for (int i = count - 1; i >= 0; i--) {
            sb.append((char) (0xFE00 + (int) ((value >>> (i * 4)) & 0xF)));
        }
    }

    /** The own profile's look as a mark (the colour and background emoji chosen in Solegram), or "". */
    public static String ownProfileMark() {
        int flags = 0;
        final int color = SolegramSelfColors.isSet() ? SolegramSelfColors.profileColor() : -1;
        final long emoji = SolegramSelfColors.isSet() ? SolegramSelfColors.profileEmoji() : 0;
        final int c1 = SolegramSelfColors.customColor1(), c2 = SolegramSelfColors.customColor2();
        if (c1 != 0 && c2 != 0) {
            flags |= PF_CUSTOM;
        } else if (color >= 0 && color < 256) {
            flags |= PF_COLOR;
        }
        if (emoji != 0) {
            flags |= PF_EMOJI;
        }
        if (flags == 0) {
            return "";
        }
        final StringBuilder sb = new StringBuilder(1 + PROFILE_MAX);
        sb.append(START_PROFILE);
        digits(sb, PROFILE_VERSION, 1);
        digits(sb, flags, 1);
        if ((flags & PF_COLOR) != 0) {
            digits(sb, color, 2);
        }
        if ((flags & PF_CUSTOM) != 0) {
            digits(sb, c1 & 0xFFFFFF, 6);
            digits(sb, c2 & 0xFFFFFF, 6);
        }
        if ((flags & PF_EMOJI) != 0) {
            digits(sb, emoji, 16);
        }
        return sb.toString();
    }

    /** The profile look in a bio, or null. */
    @androidx.annotation.Nullable
    public static ProfileLook readProfile(String about) {
        if (about == null) {
            return null;
        }
        final int at = about.lastIndexOf(START_PROFILE);
        if (at < 0) {
            return null;
        }
        int pos = at + 1;
        final long[] v = new long[1];
        if (!readDigits(about, pos, 1, v) || v[0] != PROFILE_VERSION) {
            return null;
        }
        pos++;
        if (!readDigits(about, pos, 1, v)) {
            return null;
        }
        final int flags = (int) v[0];
        pos++;
        final ProfileLook look = new ProfileLook();
        if ((flags & PF_COLOR) != 0) {
            if (!readDigits(about, pos, 2, v)) {
                return null;
            }
            look.color = (int) v[0];
            pos += 2;
        }
        if ((flags & PF_CUSTOM) != 0) {
            if (!readDigits(about, pos, 6, v)) {
                return null;
            }
            look.custom1 = 0xFF000000 | (int) v[0]; // opaque only
            if (!readDigits(about, pos + 6, 6, v)) {
                return null;
            }
            look.custom2 = 0xFF000000 | (int) v[0];
            pos += 12;
        }
        if ((flags & PF_EMOJI) != 0) {
            if (!readDigits(about, pos, 16, v)) {
                return null;
            }
            look.emoji = v[0];
        }
        return look.isEmpty() ? null : look;
    }

    private static boolean readDigits(String s, int from, int count, long[] out) {
        if (from + count > s.length()) {
            return false;
        }
        long value = 0;
        for (int i = 0; i < count; i++) {
            final char c = s.charAt(from + i);
            if (c < 0xFE00 || c > 0xFE0F) {
                return false;
            }
            value = (value << 4) | (c - 0xFE00);
        }
        out[0] = value;
        return true;
    }

    // other users' looks: user id -> look (null entry: known to have none)
    private static java.util.HashMap<Long, ProfileLook> looks;

    private static synchronized java.util.HashMap<Long, ProfileLook> looks() {
        if (looks == null) {
            looks = new java.util.HashMap<>();
            try {
                final JSONObject o = new JSONObject(SolegramConfig.prefs().getString(KEY_PROFILES, "{}"));
                final Iterator<String> keys = o.keys();
                while (keys.hasNext()) {
                    final String k = keys.next();
                    final ProfileLook l = ProfileLook.decode(o.getString(k));
                    if (l != null) {
                        looks.put(Long.parseLong(k), l);
                    }
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
        return looks;
    }

    /** Written once a burst of reads is over (each one used to rewrite the whole settings file). */
    private static void saveLooks() {
        org.telegram.messenger.AndroidUtilities.cancelRunOnUIThread(SAVELOOKS_LATER);
        org.telegram.messenger.AndroidUtilities.runOnUIThread(SAVELOOKS_LATER, 3000);
    }

    private static final Runnable SAVELOOKS_LATER = () -> org.telegram.messenger.Utilities.globalQueue.postRunnable(SolegramNameEmojiShare::saveLooksNow);

    private static synchronized void saveLooksNow() {
        try {
            final JSONObject o = new JSONObject();
            int n = 0;
            for (java.util.Map.Entry<Long, ProfileLook> e : looks().entrySet()) {
                if (e.getValue() != null && n++ < MAX_KNOWN) {
                    o.put(String.valueOf(e.getKey()), e.getValue().encode());
                }
            }
            SolegramConfig.prefs().edit().putString(KEY_PROFILES, o.toString()).apply();
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /** Someone's profile look shared in their bio, or null (none, or their profile was not read yet). */
    @androidx.annotation.Nullable
    public static synchronized ProfileLook profileLookOf(long userId) {
        return looks().get(userId);
    }

    /** Notes the look in a loaded bio; true when it changed. */
    private static boolean onProfileLook(long userId, String about) {
        final ProfileLook now = readProfile(about);
        synchronized (SolegramNameEmojiShare.class) {
            final ProfileLook was = looks().get(userId);
            final String a = was == null ? null : was.encode(), b = now == null ? null : now.encode();
            if (TextUtils.equals(a, b)) {
                return false;
            }
            if (now == null) {
                looks().remove(userId);
            } else {
                looks().put(userId, now);
            }
            saveLooks();
            return true;
        }
    }
    // endregion

    /** The bio without the marks (for showing and editing). */
    public static String strip(String about) {
        if (about == null || about.indexOf(START) < 0 && about.indexOf(START_PROFILE) < 0) {
            return about;
        }
        final StringBuilder out = new StringBuilder(about.length());
        for (int i = 0; i < about.length(); i++) {
            final char c = about.charAt(i);
            if (c == START_PROFILE) {
                int j = i + 1;
                while (j < about.length() && about.charAt(j) >= 0xFE00 && about.charAt(j) <= 0xFE0F && j - i <= PROFILE_MAX) {
                    j++;
                }
                i = j - 1;
                continue;
            }
            if (c == START) {
                int j = i + 1;
                while (j < about.length() && about.charAt(j) >= 0xFE00 && about.charAt(j) <= 0xFE0F && j - i <= DIGITS + SolegramNameEffect.MARK_DIGITS) {
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
        final int effect = SolegramNameEffect.own();
        return enabled() && (id != 0 || effect != 0) ? mark(id, effect) : "";
    }

    /** A bio the user wrote, with the marks put back when they fit the account's limit (the profile's look first). */
    public static String withMark(int account, String bio) {
        final String base = strip(bio == null ? "" : bio);
        final String mark = ownMark();
        final String look = ownProfileMark();
        final int limit = MessagesController.getInstance(account).getAboutLimit();
        final int len = base.codePointCount(0, base.length());
        if (len + look.length() + mark.length() <= limit) {
            return base + look + mark; // the name's mark stays last, where every Solegram reads it
        }
        if (!look.isEmpty() && len + look.length() <= limit) {
            return base + look;
        }
        if (!mark.isEmpty() && len + mark.length() <= limit) {
            return base + mark;
        }
        return base;
    }

    /** Whether the mark does not fit after this bio. */
    public static boolean tooLong(int account, String bio) {
        final String base = strip(bio == null ? "" : bio);
        return base.codePointCount(0, base.length()) + Math.max(LENGTH, ownMark().length()) > MessagesController.getInstance(account).getAboutLimit();
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

    /** Written once a burst of reads is over (each one used to rewrite the whole settings file). */
    private static void saveKnown() {
        org.telegram.messenger.AndroidUtilities.cancelRunOnUIThread(SAVEKNOWN_LATER);
        org.telegram.messenger.AndroidUtilities.runOnUIThread(SAVEKNOWN_LATER, 3000);
    }

    private static final Runnable SAVEKNOWN_LATER = () -> org.telegram.messenger.Utilities.globalQueue.postRunnable(SolegramNameEmojiShare::saveKnownNow);

    private static synchronized void saveKnownNow() {
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
        if (onProfileLook(user.id, full.about)) {
            // the profile's colour or background emoji is new or gone: profiles and lists draw it again
            AndroidUtilities.runOnUIThread(() -> NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_NAME));
        }
        final long id = read(full.about);
        if (SolegramNameEffect.onRead(user.id, readEffect(full.about))) {
            // the name's effect is new or gone: lists, headers and profiles draw it again
            AndroidUtilities.runOnUIThread(() -> NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_NAME));
        }
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

    // users whose profile was asked for, and when (the mark is only in the profile's bio)
    private static final LongSparseArray<Long> asked = new LongSparseArray<>();
    private static final java.util.ArrayDeque<Object[]> queue = new java.util.ArrayDeque<>();
    private static boolean pumping;
    private static final long ASK_AGAIN_MS = 6 * 60 * 60 * 1000L;

    /**
     * Someone is shown (in the chat list): if their profile was not looked at lately, it is loaded quietly in the
     * background, one every second or so, to read the emoji their Solegram shares. Without it the emoji showed only
     * after their profile or chat had been opened once.
     */
    public static void want(int account, TLRPC.User user) {
        if (!enabled() || user == null || user.self || user.bot || user.id == 0 || user.deleted || UserConfig.getInstance(account).getClientUserId() == user.id) {
            return;
        }
        final long now = System.currentTimeMillis();
        synchronized (SolegramNameEmojiShare.class) {
            final Long at = asked.get(user.id);
            if (at != null && now - at < ASK_AGAIN_MS) {
                return;
            }
            asked.put(user.id, now);
            if (queue.size() >= 60) {
                return;
            }
            queue.add(new Object[]{account, user.id});
        }
        AndroidUtilities.runOnUIThread(SolegramNameEmojiShare::pump);
    }

    private static void pump() {
        if (pumping) {
            return;
        }
        final Object[] next;
        synchronized (SolegramNameEmojiShare.class) {
            next = queue.poll();
        }
        if (next == null) {
            return;
        }
        pumping = true;
        final int account = (Integer) next[0];
        final TLRPC.User user = MessagesController.getInstance(account).getUser((Long) next[1]);
        if (user != null && MessagesController.getInstance(account).getUserFull(user.id) == null) {
            MessagesController.getInstance(account).loadFullUser(user, 0, false);
        } else if (user != null) {
            onUserFull(account, user, MessagesController.getInstance(account).getUserFull(user.id));
        }
        AndroidUtilities.runOnUIThread(() -> {
            pumping = false;
            pump();
        }, 1200);
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

    private static boolean startSynced;

    /** Once a run: a profile look chosen before the bio could carry it gets into the bio. */
    public static void syncOnceOnStart() {
        if (startSynced) {
            return;
        }
        startSynced = true;
        if (!ownProfileMark().isEmpty() || !ownMark().isEmpty()) {
            scheduleSync();
        }
    }

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
