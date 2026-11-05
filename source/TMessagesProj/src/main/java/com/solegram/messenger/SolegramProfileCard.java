/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger;

import android.os.SystemClock;
import android.text.TextUtils;

import androidx.annotation.Nullable;
import androidx.collection.LongSparseArray;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

/**
 * A Solegram profile card: a banner (a picture or GIF) over the profile header and the profile's tiles in an order
 * of the user's own, with some hidden; seen by everyone who has Solegram.
 * <p>
 * There is no Solegram server, so the card lives in Telegram: the banner is a post in the user's personal channel
 * (Telegram shows that channel in the profile), the post is pinned there, and its caption ends with an invisible mark
 * (an invisible plus and variation selectors, two per byte) that holds the settings. Solegram reads the channel's
 * pinned post when it opens a profile; other apps show the post as it is.
 */
public final class SolegramProfileCard {

    /** The tiles of a profile's info block that can be moved or hidden. */
    public static final String[] TILES = {"phone", "bio", "links", "music", "posts", "streak", "username", "id", "birthday", "hours", "location", "note"};
    /** Posts of the personal channel shown in the profile, at most. */
    public static final int MAX_POSTS = 6;
    public static final String CHANNEL_TOP = "top", CHANNEL_BOTTOM = "bottom", CHANNEL_HIDDEN = "hidden";

    private static final char START = '⁤'; // invisible plus
    private static final int MAX_BYTES = 400;
    private static final long REFRESH_MS = 20 * 60_000L;

    /** One user's card. */
    public static final class Card {
        public final ArrayList<String> order = new ArrayList<>(Arrays.asList(TILES));
        public final HashSet<String> hidden = new HashSet<>();
        public String channel = CHANNEL_TOP;
        /** Banner height, dp of extra header (0 = Telegram's own height). */
        public int bannerHeight = 0;
        /** Darkening under the name, 0..80 %. */
        public int dim = 35;
        /** The post that carries the card (its photo or GIF is the banner); set when read from Telegram. */
        @Nullable
        public MessageObject post;
        /** Posts of the personal channel shown in the profile, by id. */
        public final ArrayList<Integer> posts = new ArrayList<>();
        /** Those posts as read from Telegram (in the order of {@link #posts}). */
        public final ArrayList<MessageObject> featured = new ArrayList<>();
        /** A Solegram Streak pinned to the profile: its days, sun colour, partner (0: not shown) and the day it was true. */
        public int streakDays;
        public int streakColor;
        public long streakPartner;
        public long streakDay;

        /** The pinned streak, unless it is more than a day older than its last check. */
        public boolean hasStreak() {
            return streakDays >= SolegramStreak.MIN_DAYS && streakDay >= SolegramStreak.today() - 2;
        }

        public boolean hasBanner() {
            return post != null && post.messageOwner != null && (post.messageOwner.media instanceof TLRPC.TL_messageMediaPhoto
                || post.messageOwner.media instanceof TLRPC.TL_messageMediaDocument && post.getDocument() != null
                && (MessageObject.isGifDocument(post.getDocument()) || MessageObject.isVideoDocument(post.getDocument()) || "image/webp".equals(post.getDocument().mime_type)));
        }

        public boolean shown(String tile) {
            return !hidden.contains(tile);
        }

        String encode() {
            final StringBuilder sb = new StringBuilder("v1");
            sb.append("|o=").append(TextUtils.join(",", order));
            if (!hidden.isEmpty()) {
                sb.append("|h=").append(TextUtils.join(",", hidden));
            }
            sb.append("|c=").append(channel);
            sb.append("|b=").append(bannerHeight);
            sb.append("|d=").append(dim);
            if (!posts.isEmpty()) {
                sb.append("|p=").append(TextUtils.join(",", posts));
            }
            if (streakDays > 0) {
                sb.append("|s=").append(streakDays).append('.').append(streakColor).append('.').append(streakPartner).append('.').append(streakDay);
            }
            return sb.toString();
        }

        static Card decode(String s) {
            if (s == null || !s.startsWith("v1")) {
                return null;
            }
            final Card c = new Card();
            for (String part : s.split("\\|")) {
                final int eq = part.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                final String k = part.substring(0, eq), v = part.substring(eq + 1);
                try {
                    switch (k) {
                        case "o": {
                            final ArrayList<String> order = new ArrayList<>();
                            for (String t : v.split(",")) {
                                if (Arrays.asList(TILES).contains(t) && !order.contains(t)) {
                                    order.add(t);
                                }
                            }
                            for (String t : TILES) {
                                if (!order.contains(t)) {
                                    order.add(t); // a tile newer than the card goes last
                                }
                            }
                            c.order.clear();
                            c.order.addAll(order);
                            break;
                        }
                        case "h":
                            for (String t : v.split(",")) {
                                if (Arrays.asList(TILES).contains(t)) {
                                    c.hidden.add(t);
                                }
                            }
                            break;
                        case "c":
                            c.channel = CHANNEL_BOTTOM.equals(v) || CHANNEL_HIDDEN.equals(v) ? v : CHANNEL_TOP;
                            break;
                        case "b":
                            c.bannerHeight = Math.max(0, Math.min(220, Integer.parseInt(v)));
                            break;
                        case "d":
                            c.dim = Math.max(0, Math.min(80, Integer.parseInt(v)));
                            break;
                        case "p":
                            for (String id : v.split(",")) {
                                final int n = Integer.parseInt(id);
                                if (n > 0 && !c.posts.contains(n) && c.posts.size() < MAX_POSTS) {
                                    c.posts.add(n);
                                }
                            }
                            break;
                        case "s": {
                            final String[] f = v.split("\\.");
                            if (f.length >= 4) {
                                c.streakDays = Math.max(0, Math.min(100_000, Integer.parseInt(f[0])));
                                c.streakColor = Math.max(0, Math.min(SolegramStreak.COLOR_NAMES.length - 1, Integer.parseInt(f[1])));
                                c.streakPartner = Long.parseLong(f[2]);
                                c.streakDay = Long.parseLong(f[3]);
                            }
                            break;
                        }
                    }
                } catch (NumberFormatException ignore) {
                }
            }
            return c;
        }

        public Card copy() {
            final Card c = decode(encode());
            c.post = post;
            c.featured.addAll(featured);
            return c;
        }
    }

    private SolegramProfileCard() {
    }

    // region The mark

    static String mark(String config) {
        final byte[] bytes = config.getBytes(StandardCharsets.UTF_8);
        final StringBuilder sb = new StringBuilder(1 + Math.min(bytes.length, MAX_BYTES) * 2);
        sb.append(START);
        for (int i = 0; i < bytes.length && i < MAX_BYTES; i++) {
            sb.append((char) (0xFE00 + ((bytes[i] >> 4) & 0xF)));
            sb.append((char) (0xFE00 + (bytes[i] & 0xF)));
        }
        return sb.toString();
    }

    @Nullable
    static String readMark(String text) {
        if (text == null) {
            return null;
        }
        final int at = text.lastIndexOf(START);
        if (at < 0) {
            return null;
        }
        final int n = (text.length() - at - 1) / 2;
        final byte[] bytes = new byte[n];
        for (int i = 0; i < n; i++) {
            final char hi = text.charAt(at + 1 + i * 2), lo = text.charAt(at + 2 + i * 2);
            if (hi < 0xFE00 || hi > 0xFE0F || lo < 0xFE00 || lo > 0xFE0F) {
                return null;
            }
            bytes[i] = (byte) (((hi - 0xFE00) << 4) | (lo - 0xFE00));
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** A caption without the mark. */
    public static String strip(String text) {
        if (text == null) {
            return "";
        }
        final int at = text.lastIndexOf(START);
        return at < 0 ? text : text.substring(0, at);
    }
    // endregion

    // region Reading others' cards

    private static final class Entry {
        Card card; // null: known to have none
        long at;
        boolean loading;
        final ArrayList<Runnable> waiting = new ArrayList<>();
    }

    private static final LongSparseArray<Entry> cache = new LongSparseArray<>();

    /** The card of a user as last read (null: none or not read yet). UI thread. */
    @Nullable
    public static Card get(long userId) {
        final Entry e = cache.get(userId);
        return e == null ? null : e.card;
    }

    /**
     * Reads the card from the user's personal channel (its pinned post), unless it was read lately;
     * {@code changed} runs on the UI thread when what is known changed. UI thread.
     */
    public static void load(int account, long userId, @Nullable TLRPC.UserFull full, Runnable changed) {
        if (!SolegramConfig.profileCards || full == null) {
            return;
        }
        Entry e = cache.get(userId);
        if (e == null) {
            e = new Entry();
            cache.put(userId, e);
        }
        if (e.loading) {
            e.waiting.add(changed);
            return;
        }
        if (e.at != 0 && SystemClock.elapsedRealtime() - e.at < REFRESH_MS && (e.card == null) == (full.personal_channel_id == 0)) {
            return;
        }
        final Entry entry = e;
        if (full.personal_channel_id == 0) {
            final boolean had = entry.card != null;
            entry.card = null;
            entry.at = SystemClock.elapsedRealtime();
            if (had) {
                changed.run();
            }
            return;
        }
        final MessagesController mc = MessagesController.getInstance(account);
        final TLRPC.Chat channel = mc.getChat(full.personal_channel_id);
        if (channel == null) {
            return;
        }
        entry.loading = true;
        entry.waiting.add(changed);
        final TLRPC.TL_channels_getFullChannel req = new TLRPC.TL_channels_getFullChannel();
        req.channel = mc.getInputChannel(channel);
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> {
            final int pinned = response instanceof TLRPC.TL_messages_chatFull && ((TLRPC.TL_messages_chatFull) response).full_chat != null
                ? ((TLRPC.TL_messages_chatFull) response).full_chat.pinned_msg_id : 0;
            if (response instanceof TLRPC.TL_messages_chatFull) {
                final TLRPC.TL_messages_chatFull res = (TLRPC.TL_messages_chatFull) response;
                AndroidUtilities.runOnUIThread(() -> {
                    mc.putUsers(res.users, false);
                    mc.putChats(res.chats, false);
                });
            }
            if (pinned == 0) {
                AndroidUtilities.runOnUIThread(() -> finish(entry, null));
                return;
            }
            final TLRPC.TL_channels_getMessages get = new TLRPC.TL_channels_getMessages();
            get.channel = mc.getInputChannel(channel);
            get.id.add(pinned);
            ConnectionsManager.getInstance(account).sendRequest(get, (r, err) -> {
                Card card = null;
                if (r instanceof TLRPC.messages_Messages) {
                    final TLRPC.messages_Messages res = (TLRPC.messages_Messages) r;
                    for (TLRPC.Message m : res.messages) {
                        if (m != null && m.id == pinned) {
                            card = Card.decode(readMark(m.message));
                            if (card != null) {
                                m.dialog_id = -channel.id;
                                card.post = new MessageObject(account, m, false, false);
                            }
                        }
                    }
                }
                final Card result = card;
                if (result == null || result.posts.isEmpty()) {
                    AndroidUtilities.runOnUIThread(() -> finish(entry, result));
                    return;
                }
                final TLRPC.TL_channels_getMessages feat = new TLRPC.TL_channels_getMessages();
                feat.channel = mc.getInputChannel(channel);
                feat.id.addAll(result.posts);
                ConnectionsManager.getInstance(account).sendRequest(feat, (fr, ferr) -> {
                    final ArrayList<MessageObject> found = new ArrayList<>();
                    if (fr instanceof TLRPC.messages_Messages) {
                        for (int id : result.posts) {
                            for (TLRPC.Message m : ((TLRPC.messages_Messages) fr).messages) {
                                if (m != null && m.id == id && !(m instanceof TLRPC.TL_messageEmpty)) {
                                    m.dialog_id = -channel.id;
                                    found.add(new MessageObject(account, m, false, false));
                                }
                            }
                        }
                    }
                    AndroidUtilities.runOnUIThread(() -> {
                        result.featured.clear();
                        result.featured.addAll(found);
                        finish(entry, result);
                    });
                });
            });
        });
    }

    private static void finish(Entry e, @Nullable Card card) {
        e.loading = false;
        e.at = SystemClock.elapsedRealtime();
        e.card = card;
        final ArrayList<Runnable> run = new ArrayList<>(e.waiting);
        e.waiting.clear();
        for (Runnable r : run) {
            r.run();
        }
    }

    public static void forget(long userId) {
        cache.remove(userId);
    }
    // endregion

    // region The own card

    /** The recent posts with a picture or GIF in the own personal channel: what can become the banner. */
    public static void loadOwnPosts(int account, TLRPC.Chat channel, org.telegram.messenger.Utilities.Callback<List<MessageObject>> done) {
        loadOwnPosts(account, channel, true, done);
    }

    /** The recent posts of the own personal channel: with a picture or GIF only, or every post with something in it. */
    public static void loadOwnPosts(int account, TLRPC.Chat channel, boolean bannersOnly, org.telegram.messenger.Utilities.Callback<List<MessageObject>> done) {
        final MessagesController mc = MessagesController.getInstance(account);
        final TLRPC.TL_messages_getHistory req = new TLRPC.TL_messages_getHistory();
        req.peer = mc.getInputPeer(-channel.id);
        req.limit = 50;
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> {
            final ArrayList<MessageObject> out = new ArrayList<>();
            if (response instanceof TLRPC.messages_Messages) {
                final TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
                for (TLRPC.Message m : res.messages) {
                    if (m == null || m instanceof TLRPC.TL_messageService || m instanceof TLRPC.TL_messageEmpty) {
                        continue;
                    }
                    if (bannersOnly && m.media == null) {
                        continue;
                    }
                    m.dialog_id = -channel.id;
                    final Card probe = new Card();
                    probe.post = new MessageObject(account, m, false, false);
                    if (!bannersOnly ? (!TextUtils.isEmpty(strip(m.message)) || m.media != null) : probe.hasBanner()) {
                        out.add(probe.post);
                    }
                }
            }
            AndroidUtilities.runOnUIThread(() -> {
                mc.putUsers(response instanceof TLRPC.messages_Messages ? ((TLRPC.messages_Messages) response).users : null, false);
                done.run(out);
            });
        });
    }

    /**
     * Puts the card on a post of the own channel: its caption gets the mark (the visible text stays), and the post
     * is pinned (silently). {@code done} gets null on success, or Telegram's error. UI thread.
     */
    public static void publish(int account, TLRPC.Chat channel, MessageObject post, Card card, org.telegram.messenger.Utilities.Callback<String> done) {
        final MessagesController mc = MessagesController.getInstance(account);
        final TLRPC.TL_messages_editMessage edit = new TLRPC.TL_messages_editMessage();
        edit.peer = mc.getInputPeer(-channel.id);
        edit.id = post.getId();
        final String caption = strip(post.messageOwner.message);
        final String mark = mark(card.encode());
        if (caption.length() + mark.length() > mc.maxCaptionLength) {
            done.run("CAPTION_TOO_LONG");
            return;
        }
        edit.message = caption + mark;
        edit.flags |= 2048;
        if (post.messageOwner.entities != null && !post.messageOwner.entities.isEmpty()) {
            edit.entities = post.messageOwner.entities;
            edit.flags |= 8;
        }
        ConnectionsManager.getInstance(account).sendRequest(edit, (response, error) -> {
            if (error != null && !"MESSAGE_NOT_MODIFIED".equals(error.text)) {
                AndroidUtilities.runOnUIThread(() -> done.run(error.text));
                return;
            }
            if (response instanceof TLRPC.Updates) {
                mc.processUpdates((TLRPC.Updates) response, false);
            }
            final TLRPC.TL_messages_updatePinnedMessage pin = new TLRPC.TL_messages_updatePinnedMessage();
            pin.peer = mc.getInputPeer(-channel.id);
            pin.id = post.getId();
            pin.silent = true;
            ConnectionsManager.getInstance(account).sendRequest(pin, (r, err) -> {
                if (r instanceof TLRPC.Updates) {
                    mc.processUpdates((TLRPC.Updates) r, false);
                }
                AndroidUtilities.runOnUIThread(() -> {
                    forget(UserConfig.getInstance(account).getClientUserId());
                    done.run(err == null ? null : err.text);
                });
            });
        });
    }

    // region The pinned streak, kept up to date

    private static android.content.SharedPreferences ownPrefs() {
        return org.telegram.messenger.ApplicationLoader.applicationContext.getSharedPreferences("solegram_profile_card", android.content.Context.MODE_PRIVATE);
    }

    /** The chat whose streak is pinned to the own profile (0: none). It is kept here because the card may hide the partner. */
    public static long pinnedStreakDialog(int account) {
        return ownPrefs().getLong("streak_" + account, 0);
    }

    public static void setPinnedStreakDialog(int account, long dialogId) {
        ownPrefs().edit().putLong("streak_" + account, dialogId).putLong("streak_at_" + account, System.currentTimeMillis()).apply();
    }

    /**
     * The streak pinned to the own profile changed (or a day went by): the card gets its new length, at most every
     * few hours. The edit is silent and only the invisible mark changes. UI thread.
     */
    public static void refreshOwnStreak(int account) {
        final long dialogId = pinnedStreakDialog(account);
        if (dialogId == 0 || !SolegramConfig.profileCards) {
            return;
        }
        final android.content.SharedPreferences p = ownPrefs();
        final long now = System.currentTimeMillis();
        if (now - p.getLong("streak_at_" + account, 0) < 4 * 60 * 60_000L) {
            return;
        }
        final MessagesController mc = MessagesController.getInstance(account);
        final long self = UserConfig.getInstance(account).getClientUserId();
        final TLRPC.UserFull full = mc.getUserFull(self);
        if (full == null || full.personal_channel_id == 0) {
            return;
        }
        final TLRPC.Chat channel = mc.getChat(full.personal_channel_id);
        if (channel == null) {
            return;
        }
        final Runnable go = () -> {
            final Card c = get(self);
            final int days = SolegramStreak.days(account, dialogId);
            final long today = SolegramStreak.today();
            if (c == null || c.post == null || c.streakDays == days && (days == 0 || c.streakDay == today)) {
                return;
            }
            final Card n = c.copy();
            n.streakDays = days;
            n.streakDay = today;
            n.streakColor = SolegramStreak.colorOf(account, dialogId);
            p.edit().putLong("streak_at_" + account, System.currentTimeMillis()).apply();
            publish(account, channel, c.post, n, error -> {
            });
        };
        if (get(self) != null) {
            go.run();
        } else {
            load(account, self, full, go);
        }
    }
    // endregion

    /** Takes the card off a post (its caption loses the mark) and unpins it. */
    public static void remove(int account, TLRPC.Chat channel, MessageObject post, org.telegram.messenger.Utilities.Callback<String> done) {
        final MessagesController mc = MessagesController.getInstance(account);
        final TLRPC.TL_messages_editMessage edit = new TLRPC.TL_messages_editMessage();
        edit.peer = mc.getInputPeer(-channel.id);
        edit.id = post.getId();
        edit.message = strip(post.messageOwner.message);
        edit.flags |= 2048;
        if (post.messageOwner.entities != null && !post.messageOwner.entities.isEmpty()) {
            edit.entities = post.messageOwner.entities;
            edit.flags |= 8;
        }
        ConnectionsManager.getInstance(account).sendRequest(edit, (response, error) -> {
            if (response instanceof TLRPC.Updates) {
                mc.processUpdates((TLRPC.Updates) response, false);
            }
            final TLRPC.TL_messages_updatePinnedMessage pin = new TLRPC.TL_messages_updatePinnedMessage();
            pin.peer = mc.getInputPeer(-channel.id);
            pin.id = post.getId();
            pin.unpin = true;
            pin.silent = true;
            ConnectionsManager.getInstance(account).sendRequest(pin, (r, err) -> AndroidUtilities.runOnUIThread(() -> {
                if (r instanceof TLRPC.Updates) {
                    mc.processUpdates((TLRPC.Updates) r, false);
                }
                forget(UserConfig.getInstance(account).getClientUserId());
                done.run(error != null && !"MESSAGE_NOT_MODIFIED".equals(error.text) ? error.text : null);
            }));
        });
    }
    // endregion
}
