/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger;

import android.content.SharedPreferences;
import android.os.SystemClock;
import android.text.TextUtils;

import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_account;

/**
 * Music in the profile, shared: while music plays, the bio of the account that turned sharing on reads
 * «🎧 Artist — Title», so everyone sees it (Solegram draws it as a card); when the music stops, the user's own
 * bio comes back.
 * <p>
 * The user's bio must never be lost, so: it is kept on the device (per user, not per account slot) before the
 * first replacement; every change starts from the bio freshly fetched from Telegram, never from a cache; the
 * music line is noted as written before it is sent, so turning sharing off at any moment still brings the bio
 * back; a bio the user changed meanwhile (here or on another device) is theirs and stays; a failed restore is
 * tried again. Bio changes are spaced out so Telegram does not slow them down.
 */
public final class SolegramNowPlayingBio {

    /** A new song goes into the bio once it has played this long (skipping through songs changes nothing). */
    private static final long WRITE_DELAY = 6000;
    /** The bio comes back after the music is paused or stopped this long. */
    private static final long RESTORE_DELAY = 90_000;
    /** At most one bio change this often. */
    private static final long MIN_INTERVAL = 30_000;

    // all keys start with "nowPlayingBio": they stay on the device (not in backups, kept through a reset)
    private static final String KEY_ORIGINAL = "nowPlayingBio_";
    private static final String KEY_WRITTEN = "nowPlayingBioWritten_";
    /** The user id of the account that shares its music. */
    public static final String KEY_USER = "nowPlayingBioUser";

    private static long lastSent;
    private static boolean busy;
    private static String pending;
    private static int restoreAttempts;

    private static final Runnable writeRunnable = SolegramNowPlayingBio::writePending;
    private static final Runnable restoreRunnable = SolegramNowPlayingBio::restoreAll;

    private SolegramNowPlayingBio() {
    }

    private static SharedPreferences prefs() {
        return SolegramConfig.prefs();
    }

    /** Sharing was turned on: it belongs to the account open now. */
    public static void setSharingAccount(int account) {
        prefs().edit().putLong(KEY_USER, UserConfig.getInstance(account).getClientUserId()).apply();
    }

    /** The account slot of the user who shares, or -1. */
    private static int sharingAccount() {
        final long user = prefs().getLong(KEY_USER, 0);
        if (user == 0) {
            return -1;
        }
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            final UserConfig config = UserConfig.getInstance(a);
            if (config.isClientActivated() && config.getClientUserId() == user) {
                return a;
            }
        }
        return -1;
    }

    private static boolean sharing() {
        return SolegramConfig.nowPlaying && SolegramConfig.nowPlayingShareBio && sharingAccount() >= 0;
    }

    /** The track changed (or stopped, or the settings changed). On the UI thread. */
    static void onTrackChanged(@Nullable SolegramNowPlaying.Track track) {
        AndroidUtilities.cancelRunOnUIThread(writeRunnable);
        if (!sharing()) {
            pending = null;
            AndroidUtilities.cancelRunOnUIThread(restoreRunnable);
            restoreAll();
            return;
        }
        if (track != null && track.playing) {
            AndroidUtilities.cancelRunOnUIThread(restoreRunnable);
            pending = SolegramNowPlaying.bioLine(track, MessagesController.getInstance(sharingAccount()).getAboutLimit());
            AndroidUtilities.runOnUIThread(writeRunnable, WRITE_DELAY);
        } else {
            pending = null;
            AndroidUtilities.cancelRunOnUIThread(restoreRunnable);
            AndroidUtilities.runOnUIThread(restoreRunnable, RESTORE_DELAY);
        }
    }

    /** After a restart: a bio left with music in it (the app was closed while sharing) gets the user's own back. */
    public static void checkOnStart() {
        AndroidUtilities.runOnUIThread(() -> {
            final SolegramNowPlaying.Track track = SolegramNowPlaying.get();
            if (!sharing() || track == null || !track.playing) {
                restoreAll();
            }
        }, 20_000);
    }

    /** The account's bio as Telegram has it now (null when it could not be fetched); on the UI thread. */
    private static void fetchAbout(int account, Utilities.Callback<TLRPC.UserFull> done) {
        final TLRPC.User self = UserConfig.getInstance(account).getCurrentUser();
        if (self == null) {
            done.run(null);
            return;
        }
        final TLRPC.TL_users_getFullUser req = new TLRPC.TL_users_getFullUser();
        req.id = MessagesController.getInstance(account).getInputUser(self);
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> {
            final TLRPC.UserFull full = response instanceof TLRPC.TL_users_userFull ? ((TLRPC.TL_users_userFull) response).full_user : null;
            AndroidUtilities.runOnUIThread(() -> done.run(full));
        });
    }

    private static void writePending() {
        final String text = pending;
        final int account = sharingAccount();
        if (text == null || account < 0 || !sharing()) {
            return;
        }
        if (busy || SystemClock.elapsedRealtime() - lastSent < MIN_INTERVAL) {
            AndroidUtilities.runOnUIThread(writeRunnable, busy ? 3000 : MIN_INTERVAL - (SystemClock.elapsedRealtime() - lastSent) + 100);
            return;
        }
        busy = true;
        fetchAbout(account, full -> {
            busy = false;
            if (full == null) {
                AndroidUtilities.runOnUIThread(writeRunnable, 15_000);
                return;
            }
            if (!sharing() || !TextUtils.equals(text, pending)) {
                onTrackChanged(SolegramNowPlaying.get()); // things changed meanwhile: decide again
                return;
            }
            final long user = full.id;
            final String about = full.about == null ? "" : full.about;
            final String written = prefs().getString(KEY_WRITTEN + user, null);
            if (written == null || !same(about, written)) {
                // the first replacement, or the user wrote a bio meanwhile: that one comes back later
                prefs().edit().putString(KEY_ORIGINAL + user, withoutMusic(about, written)).apply();
            }
            if (same(about, text)) {
                prefs().edit().putString(KEY_WRITTEN + user, text).apply();
                return;
            }
            // noted before sending: whatever happens next, the restore knows what to look for
            prefs().edit().putString(KEY_WRITTEN + user, text).apply();
            send(account, text, ok -> {
                if (ok && !sharing()) {
                    AndroidUtilities.cancelRunOnUIThread(restoreRunnable);
                    AndroidUtilities.runOnUIThread(restoreRunnable, 1000);
                }
            });
        });
    }

    /** Brings the user's own bio back on every account whose bio still has the music in it. */
    private static void restoreAll() {
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            final UserConfig config = UserConfig.getInstance(a);
            if (!config.isClientActivated() || prefs().getString(KEY_WRITTEN + config.getClientUserId(), null) == null) {
                continue;
            }
            if (sharing() && a == sharingAccount() && pending != null) {
                continue; // this one is playing on
            }
            restore(a);
            return; // one at a time; the next one follows
        }
        restoreAttempts = 0;
    }

    private static void restore(int account) {
        if (busy) {
            AndroidUtilities.runOnUIThread(restoreRunnable, 3000);
            return;
        }
        busy = true;
        fetchAbout(account, full -> {
            busy = false;
            if (full == null) {
                retryRestore();
                return;
            }
            final long user = full.id;
            final String about = full.about == null ? "" : full.about;
            final String written = prefs().getString(KEY_WRITTEN + user, null);
            final String original = prefs().getString(KEY_ORIGINAL + user, "");
            final Runnable forget = () -> prefs().edit().remove(KEY_WRITTEN + user).remove(KEY_ORIGINAL + user).apply();
            if (written == null) {
                restoreAll();
                return;
            }
            String target;
            if (same(about, written)) {
                target = original;
            } else if (plain(about).contains(plain(written).trim())) {
                // the user added to the bio around the music line: theirs stays, the line goes
                target = withoutMusic(about, written);
            } else {
                forget.run(); // the user changed the bio themselves: theirs stays
                AndroidUtilities.runOnUIThread(restoreRunnable);
                return;
            }
            target = cut(target == null ? "" : target, MessagesController.getInstance(account).getAboutLimit());
            send(account, target, ok -> {
                if (ok) {
                    forget.run();
                    restoreAttempts = 0;
                    AndroidUtilities.runOnUIThread(restoreRunnable);
                } else {
                    retryRestore();
                }
            });
        });
    }

    private static void retryRestore() {
        // 30 s, 2 min, 8 min, then every half hour while the app runs
        final long delay = Math.min(30 * 60_000L, 30_000L << Math.min(6, restoreAttempts * 2));
        restoreAttempts++;
        AndroidUtilities.cancelRunOnUIThread(restoreRunnable);
        AndroidUtilities.runOnUIThread(restoreRunnable, delay);
    }

    /** Without the invisible mark, in case Telegram drops it from the bio. */
    private static String plain(@Nullable String s) {
        return s == null ? "" : s.replace("\u2060", "");
    }

    private static boolean same(@Nullable String a, @Nullable String b) {
        return TextUtils.equals(plain(a).trim(), plain(b).trim());
    }

    /** The bio without the music line: the one Solegram recognises, or else the exact line it wrote. */
    private static String withoutMusic(String about, @Nullable String written) {
        final String stripped = SolegramNowPlaying.stripBio(about);
        if (written == null || !plain(stripped).contains(plain(written).trim())) {
            return stripped;
        }
        final StringBuilder out = new StringBuilder();
        boolean removed = false;
        for (String line : stripped.split("\n", -1)) {
            if (!removed && same(line, written)) {
                removed = true;
                continue;
            }
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append(line);
        }
        return out.toString().trim();
    }

    private static String cut(String s, int codePoints) {
        if (s.codePointCount(0, s.length()) <= codePoints) {
            return s;
        }
        return s.substring(0, s.offsetByCodePoints(0, Math.max(0, codePoints))).trim();
    }

    private static void send(int account, String about, Utilities.Callback<Boolean> done) {
        busy = true;
        lastSent = SystemClock.elapsedRealtime();
        final TL_account.updateProfile req = new TL_account.updateProfile();
        req.about = about;
        req.flags |= 4;
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            busy = false;
            if (error != null) {
                FileLog.e("solegram now playing bio: " + error.text);
                done.run(false);
                return;
            }
            final long self = UserConfig.getInstance(account).getClientUserId();
            final TLRPC.UserFull full = MessagesController.getInstance(account).getUserFull(self);
            if (full != null) {
                full.about = about;
                if (TextUtils.isEmpty(about)) {
                    full.flags &= ~2;
                } else {
                    full.flags |= 2;
                }
                MessagesStorage.getInstance(account).updateUserInfo(full, false);
                NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.userInfoDidLoad, full.id, full);
            }
            done.run(true);
        }), ConnectionsManager.RequestFlagFailOnServerErrors);
    }
}
