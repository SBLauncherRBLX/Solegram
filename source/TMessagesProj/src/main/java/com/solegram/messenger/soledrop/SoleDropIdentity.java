/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.soledrop;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import androidx.annotation.Nullable;


import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

/**
 * Who this phone is, for the other phone: the Telegram contact token of an account (contacts.exportContactToken)
 * with its user id, first name and username. The token lets the other side find the account
 * (contacts.importContactToken gives the user with its access hash) without a phone number.
 * <p>
 * Tokens expire, so each account keeps a fresh one on disk: the card emulation service may start in a cold
 * process and has to answer in milliseconds, long before any request could return. A token is renewed when the
 * app opens, when SoleDrop opens and after a tap, some minutes before it runs out.
 */
public final class SoleDropIdentity {

    /** Renewed this long before it expires. */
    private static final int REFRESH_BEFORE = 10 * 60;

    public final long userId;
    public final String name;
    @Nullable
    public final String username;
    @Nullable
    public final String token;
    public final int expires;

    private SoleDropIdentity(long userId, String name, @Nullable String username, @Nullable String token, int expires) {
        this.userId = userId;
        this.name = name;
        this.username = username;
        this.token = token;
        this.expires = expires;
    }

    public boolean hasToken() {
        return token != null && expires > now();
    }

    private static int now() {
        return (int) (System.currentTimeMillis() / 1000);
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("solegram_soledrop", Context.MODE_PRIVATE);
    }

    private static final boolean[] requesting = new boolean[UserConfig.MAX_ACCOUNT_COUNT];

    /**
     * The identity of an account as kept on disk (null when there is none yet). Name and username are left out
     * when the user chose not to show them before the other side resolves the token.
     */
    @Nullable
    public static SoleDropIdentity get(int account) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) {
            return null;
        }
        final SharedPreferences p = prefs();
        final long userId = p.getLong("uid_" + account, 0);
        if (userId == 0) {
            return null;
        }
        String token = p.getString("token_" + account, null);
        int expires = p.getInt("expires_" + account, 0);
        if (expires <= now()) {
            token = null;
        }
        final boolean share = SoleDrop.shareName();
        final String name = share ? p.getString("name_" + account, "") : "";
        final String username = share ? p.getString("username_" + account, null) : null;
        return new SoleDropIdentity(userId, name, TextUtils.isEmpty(username) ? null : username, token, expires);
    }

    /** Fills a packet with this account's identity (the sender's in an offer, the receiver's in an identity). */
    public static boolean fill(int account, SoleDropProtocol.Packet packet) {
        final SoleDropIdentity id = get(account);
        if (id == null) {
            return false;
        }
        packet.sender(id.userId, id.name, id.username, id.hasToken() ? id.token : null);
        return true;
    }

    /** Name and username as they are now (they change in the profile without a new token). */
    private static void storeProfile(int account) {
        final UserConfig config = UserConfig.getInstance(account);
        if (!config.isClientActivated()) {
            return;
        }
        final TLRPC.User self = config.getCurrentUser();
        if (self == null) {
            return;
        }
        prefs().edit()
            .putLong("uid_" + account, self.id)
            .putString("name_" + account, UserObject.getFirstName(self))
            .putString("username_" + account, UserObject.getPublicUsername(self))
            .apply();
    }

    /**
     * Keeps the account's token fresh: a new one is asked for when there is none or it runs out soon.
     *
     * @param done called on the UI thread with the identity (also when nothing had to be done), may be null
     */
    public static void refresh(int account, @Nullable Runnable done) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT || !UserConfig.getInstance(account).isClientActivated()) {
            if (done != null) {
                done.run();
            }
            return;
        }
        storeProfile(account);
        final SharedPreferences p = prefs();
        final int expires = p.getInt("expires_" + account, 0);
        final long uid = UserConfig.getInstance(account).getClientUserId();
        if (expires - REFRESH_BEFORE > now() && p.getLong("uid_" + account, 0) == uid && p.getString("token_" + account, null) != null) {
            if (done != null) {
                done.run();
            }
            return;
        }
        if (requesting[account]) {
            if (done != null) {
                AndroidUtilities.runOnUIThread(done, 1500);
            }
            return;
        }
        requesting[account] = true;
        ConnectionsManager.getInstance(account).sendRequest(new TLRPC.TL_contacts_exportContactToken(), (res, err) -> AndroidUtilities.runOnUIThread(() -> {
            requesting[account] = false;
            if (res instanceof TLRPC.TL_exportedContactToken) {
                final TLRPC.TL_exportedContactToken t = (TLRPC.TL_exportedContactToken) res;
                final String token = SoleDropProtocol.tokenFromLink(t.url);
                if (token != null) {
                    prefs().edit()
                        .putLong("uid_" + account, uid)
                        .putString("token_" + account, token)
                        .putString("url_" + account, t.url)
                        .putInt("expires_" + account, t.expires)
                        .apply();
                    scheduleRefresh(account, t.expires);
                }
            }
            if (done != null) {
                done.run();
            }
        }));
    }

    /** The contact link of the account (for the QR code), null while there is no fresh token. */
    @Nullable
    public static String link(int account) {
        final SharedPreferences p = prefs();
        if (p.getInt("expires_" + account, 0) <= now()) {
            return null;
        }
        return p.getString("url_" + account, null);
    }

    public static int expiresIn(int account) {
        return Math.max(0, prefs().getInt("expires_" + account, 0) - now());
    }

    private static final Runnable[] scheduled = new Runnable[UserConfig.MAX_ACCOUNT_COUNT];

    /** While the app lives, the token is renewed by itself before it runs out (the next tap may come from a locked process). */
    private static void scheduleRefresh(int account, int expires) {
        if (scheduled[account] != null) {
            AndroidUtilities.cancelRunOnUIThread(scheduled[account]);
        }
        final long delay = Math.max(60, expires - REFRESH_BEFORE - now() + 5) * 1000L;
        scheduled[account] = () -> {
            scheduled[account] = null;
            if (SoleDrop.receiveMode() != SoleDrop.RECEIVE_OFF || !ApplicationLoader.mainInterfacePaused) {
                refresh(account, null);
            }
        };
        AndroidUtilities.runOnUIThread(scheduled[account], delay);
    }

    /** A user logged out: the account's identity is forgotten. */
    public static void forget(int account) {
        prefs().edit()
            .remove("uid_" + account)
            .remove("token_" + account)
            .remove("url_" + account)
            .remove("expires_" + account)
            .remove("name_" + account)
            .remove("username_" + account)
            .apply();
    }
}
