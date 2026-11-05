/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.wallet;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationsController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_stars;
import org.telegram.ui.Stars.StarsController;

import java.util.ArrayList;

/**
 * The wallet's vault: a private channel of the user's own, where collectible gifts can be put away out of the
 * profile and taken back at any time. The user owns the channel, so nobody else — not Solegram either — can touch
 * what is in it. Moving a gift is Telegram's own gift transfer (Telegram may ask a few Stars for it). Plain gifts and
 * Stars can't be moved by Telegram's rules, so the vault holds collectible gifts only.
 */
public final class SoleVault {

    private SoleVault() {
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("solegram_wallet", Context.MODE_PRIVATE);
    }

    private static String k(int account, String name) {
        return name + "_" + UserConfig.getInstance(account).getClientUserId();
    }

    // ------------------------------------------------------------------ the channel

    public static long channelId(int account) {
        return prefs().getLong(k(account, "vault_id"), 0);
    }

    /** The channel's dialog id (negative), or 0 when there is no vault yet. */
    public static long dialogId(int account) {
        final long id = channelId(account);
        return id == 0 ? 0 : -id;
    }

    public static TLRPC.InputPeer inputPeer(int account) {
        final long id = channelId(account);
        if (id == 0) {
            return null;
        }
        final TLRPC.TL_inputPeerChannel peer = new TLRPC.TL_inputPeerChannel();
        peer.channel_id = id;
        peer.access_hash = prefs().getLong(k(account, "vault_hash"), 0);
        return peer;
    }

    private static TLRPC.InputChannel inputChannel(int account) {
        final TLRPC.TL_inputChannel c = new TLRPC.TL_inputChannel();
        c.channel_id = channelId(account);
        c.access_hash = prefs().getLong(k(account, "vault_hash"), 0);
        return c;
    }

    private static void save(int account, TLRPC.Chat chat) {
        prefs().edit().putLong(k(account, "vault_id"), chat.id).putLong(k(account, "vault_hash"), chat.access_hash).apply();
    }

    public static void forget(int account) {
        prefs().edit().remove(k(account, "vault_id")).remove(k(account, "vault_hash")).apply();
    }

    /** Checks that the vault is still there and still the user's; forgets it otherwise. */
    public static void check(int account, Utilities.Callback<Boolean> done) {
        if (channelId(account) == 0) {
            done.run(false);
            return;
        }
        final TLRPC.TL_channels_getChannels req = new TLRPC.TL_channels_getChannels();
        req.id.add(inputChannel(account));
        ConnectionsManager.getInstance(account).sendRequest(req, (res, err) -> AndroidUtilities.runOnUIThread(() -> {
            if (res instanceof TLRPC.messages_Chats) {
                final ArrayList<TLRPC.Chat> chats = ((TLRPC.messages_Chats) res).chats;
                MessagesController.getInstance(account).putChats(chats, false);
                final TLRPC.Chat c = chats.isEmpty() ? null : chats.get(0);
                final boolean ok = c != null && c.creator && !c.left && !(c instanceof TLRPC.TL_channelForbidden);
                if (!ok) {
                    forget(account);
                }
                done.run(ok);
            } else {
                // the channel is gone only when Telegram says so; a network error keeps the vault
                final boolean gone = err != null && ("CHANNEL_INVALID".equals(err.text) || "CHANNEL_PRIVATE".equals(err.text));
                if (gone) {
                    forget(account);
                }
                done.run(!gone);
            }
        }));
    }

    /** Creates the vault: a private channel, muted and put into the archive so it doesn't crowd the chats. */
    public static void create(int account, Utilities.Callback<TLRPC.TL_error> done) {
        final TLRPC.TL_channels_createChannel req = new TLRPC.TL_channels_createChannel();
        req.broadcast = true;
        req.title = "SoleWallet Vault";
        req.about = LocaleController.getString(R.string.SG_VaultAbout);
        ConnectionsManager.getInstance(account).sendRequest(req, (res, err) -> {
            if (res instanceof TLRPC.Updates) {
                final TLRPC.Updates updates = (TLRPC.Updates) res;
                MessagesController.getInstance(account).processUpdates(updates, false);
                AndroidUtilities.runOnUIThread(() -> {
                    TLRPC.Chat chat = null;
                    for (TLRPC.Chat c : updates.chats) {
                        if (c instanceof TLRPC.TL_channel) {
                            chat = c;
                            break;
                        }
                    }
                    if (chat == null) {
                        final TLRPC.TL_error e = new TLRPC.TL_error();
                        e.text = "NO_CHANNEL";
                        done.run(e);
                        return;
                    }
                    MessagesController.getInstance(account).putChat(chat, false);
                    save(account, chat);
                    try {
                        NotificationsController.getInstance(account).muteDialog(-chat.id, 0, true);
                        MessagesController.getInstance(account).addDialogToFolder(-chat.id, 1, -1, 0);
                    } catch (Exception e) {
                        FileLog.e(e);
                    }
                    done.run(null);
                });
            } else {
                AndroidUtilities.runOnUIThread(() -> done.run(err));
            }
        });
    }

    // ------------------------------------------------------------------ the gifts

    /** The gifts a peer keeps (all pages, up to a few hundred). */
    public static void loadGifts(int account, TLRPC.InputPeer peer, Utilities.Callback<ArrayList<TL_stars.SavedStarGift>> done) {
        final ArrayList<TL_stars.SavedStarGift> all = new ArrayList<>();
        loadPage(account, peer, "", all, done);
    }

    private static void loadPage(int account, TLRPC.InputPeer peer, String offset, ArrayList<TL_stars.SavedStarGift> all,
                                 Utilities.Callback<ArrayList<TL_stars.SavedStarGift>> done) {
        final TL_stars.getSavedStarGifts req = new TL_stars.getSavedStarGifts();
        req.peer = peer;
        req.offset = offset;
        req.limit = 100;
        ConnectionsManager.getInstance(account).sendRequest(req, (res, err) -> AndroidUtilities.runOnUIThread(() -> {
            if (!(res instanceof TL_stars.TL_payments_savedStarGifts)) {
                done.run(all);
                return;
            }
            final TL_stars.TL_payments_savedStarGifts r = (TL_stars.TL_payments_savedStarGifts) res;
            MessagesController.getInstance(account).putUsers(r.users, false);
            MessagesController.getInstance(account).putChats(r.chats, false);
            all.addAll(r.gifts);
            if (r.next_offset != null && !r.next_offset.isEmpty() && !r.gifts.isEmpty() && all.size() < 600) {
                loadPage(account, peer, r.next_offset, all, done);
            } else {
                done.run(all);
            }
        }));
    }

    public static boolean collectible(TL_stars.SavedStarGift g) {
        return g != null && g.gift instanceof TL_stars.TL_starGiftUnique;
    }

    /** What a collectible gift is worth, in US cents (Telegram's estimate); 0 when unknown. */
    public static long usdCents(TL_stars.SavedStarGift g) {
        return collectible(g) ? Math.max(0, ((TL_stars.TL_starGiftUnique) g.gift).value_usd_amount) : 0;
    }

    public static String title(TL_stars.SavedStarGift g) {
        if (g == null || g.gift == null) {
            return "";
        }
        if (g.gift instanceof TL_stars.TL_starGiftUnique) {
            final TL_stars.TL_starGiftUnique u = (TL_stars.TL_starGiftUnique) g.gift;
            return (u.title == null ? "" : u.title) + " #" + LocaleController.formatNumber(u.num, ',');
        }
        return g.gift.title == null ? LocaleController.getString(R.string.SG_VaultGift) : g.gift.title;
    }

    /**
     * Moves a collectible gift to a peer with Telegram's gift transfer; when Telegram asks Stars for it, they are paid
     * from the balance through Telegram's own payment form. Errors come back as they are (BALANCE_TOO_LOW and so on).
     */
    public static void transfer(int account, TL_stars.SavedStarGift g, TLRPC.InputPeer to, Utilities.Callback<TLRPC.TL_error> done) {
        if (!collectible(g) || to == null) {
            final TLRPC.TL_error e = new TLRPC.TL_error();
            e.text = "NOT_COLLECTIBLE";
            done.run(e);
            return;
        }
        final TL_stars.TL_inputSavedStarGiftSlug input = new TL_stars.TL_inputSavedStarGiftSlug();
        input.slug = g.gift.slug;
        if (g.transfer_stars <= 0) {
            final TL_stars.transferStarGift req = new TL_stars.transferStarGift();
            req.stargift = input;
            req.to_id = to;
            ConnectionsManager.getInstance(account).sendRequest(req, (res, err) -> {
                if (res instanceof TLRPC.Updates) {
                    MessagesController.getInstance(account).processUpdates((TLRPC.Updates) res, false);
                }
                AndroidUtilities.runOnUIThread(() -> done.run(err));
            });
            return;
        }
        final TLRPC.TL_inputInvoiceStarGiftTransfer invoice = new TLRPC.TL_inputInvoiceStarGiftTransfer();
        invoice.stargift = input;
        invoice.to_id = to;
        final TLRPC.TL_payments_getPaymentForm formReq = new TLRPC.TL_payments_getPaymentForm();
        formReq.invoice = invoice;
        ConnectionsManager.getInstance(account).sendRequest(formReq, (res, err) -> {
            if (!(res instanceof TLRPC.PaymentForm)) {
                AndroidUtilities.runOnUIThread(() -> done.run(err));
                return;
            }
            final TLRPC.PaymentForm form = (TLRPC.PaymentForm) res;
            final TL_stars.TL_payments_sendStarsForm pay = new TL_stars.TL_payments_sendStarsForm();
            pay.form_id = form.form_id;
            pay.invoice = invoice;
            ConnectionsManager.getInstance(account).sendRequest(pay, (res2, err2) -> {
                if (res2 instanceof TLRPC.TL_payments_paymentResult) {
                    MessagesController.getInstance(account).processUpdates(((TLRPC.TL_payments_paymentResult) res2).updates, false);
                }
                AndroidUtilities.runOnUIThread(() -> {
                    StarsController.getInstance(account).invalidateBalance();
                    StarsController.getInstance(account).invalidateTransactions(false);
                    done.run(res2 instanceof TLRPC.TL_payments_paymentResult ? null : err2);
                });
            });
        });
    }

    // ------------------------------------------------------------------ the collection's worth over time

    /** Notes today's worth of the collection (US cents), one value a day, the last 90 days. */
    public static void noteWorth(int account, long cents) {
        try {
            final JSONObject days = new JSONObject(prefs().getString(k(account, "worth"), "{}"));
            final long today = System.currentTimeMillis() / 86_400_000L;
            days.put(String.valueOf(today), cents);
            final JSONArray names = days.names();
            if (names != null && names.length() > 90) {
                long oldest = Long.MAX_VALUE;
                for (int i = 0; i < names.length(); i++) {
                    oldest = Math.min(oldest, Long.parseLong(names.getString(i)));
                }
                days.remove(String.valueOf(oldest));
            }
            prefs().edit().putString(k(account, "worth"), days.toString()).apply();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    /** The last noted worth of the collection (US cents), or 0. */
    public static long lastWorth(int account) {
        try {
            final JSONObject days = new JSONObject(prefs().getString(k(account, "worth"), "{}"));
            final JSONArray names = days.names();
            if (names == null) {
                return 0;
            }
            long last = -1, value = 0;
            for (int i = 0; i < names.length(); i++) {
                final long d = Long.parseLong(names.getString(i));
                if (d > last) {
                    last = d;
                    value = days.getLong(names.getString(i));
                }
            }
            return value;
        } catch (Exception e) {
            return 0;
        }
    }

    /** The worth noted about a week ago (the closest day 6–30 days back), or -1 when there is none. */
    public static long worthWeekAgo(int account) {
        try {
            final JSONObject days = new JSONObject(prefs().getString(k(account, "worth"), "{}"));
            final long today = System.currentTimeMillis() / 86_400_000L;
            long best = -1, bestDay = -1;
            final JSONArray names = days.names();
            if (names == null) {
                return -1;
            }
            for (int i = 0; i < names.length(); i++) {
                final long d = Long.parseLong(names.getString(i));
                final long age = today - d;
                if (age >= 6 && age <= 30 && (bestDay < 0 || Math.abs(age - 7) < Math.abs(today - bestDay - 7))) {
                    bestDay = d;
                    best = days.getLong(names.getString(i));
                }
            }
            return best;
        } catch (Exception e) {
            return -1;
        }
    }
}
