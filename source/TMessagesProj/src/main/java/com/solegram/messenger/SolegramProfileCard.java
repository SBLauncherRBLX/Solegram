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
import org.telegram.messenger.UserObject;
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
    private static final int MAX_BYTES = 520;
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
        /** The emoji spreading out from the cover of the profile's music (0: none). */
        public long musicEmoji;
        /** The profile's colour and pattern emoji chosen in Solegram without Premium (Telegram keeps those only for
         *  Premium): published here so everyone with Solegram sees them. -1 / 0: none. */
        public int profileColor = -1;
        public long profileEmoji;
        /** Own colours of the profile (top and bottom of its gradient, ARGB); 0: none, the palette colour is used. */
        public int customColor1, customColor2;
        /** The name's colour, its emoji (replies), and own colours of the name, chosen in Solegram without Premium. */
        public int nameColor = -1;
        public long nameEmoji;
        public int nameCustom1, nameCustom2;
        /** The accent of the owner's theme (two colours, ARGB): their profile shows in it for everyone with Solegram
         *  when no profile colour was chosen. 0: none. */
        public int themeColor1, themeColor2;
        /** The owner's theme for their whole profile page: its built-in base and key colours (SolegramProfileTheme).
         *  null: none. */
        public String themeBase;
        public int[] themePalette;
        /** The linked stats.fm profile (what plays now shows on the profile for everyone with Solegram). null: none. */
        @Nullable
        public String statsFm;

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
            if (musicEmoji != 0) {
                sb.append("|me=").append(musicEmoji);
            }
            if (profileColor >= 0) {
                sb.append("|pc=").append(profileColor);
            }
            if (profileEmoji != 0) {
                sb.append("|pe=").append(profileEmoji);
            }
            if (customColor1 != 0 && customColor2 != 0) {
                sb.append("|px=").append(Integer.toHexString(customColor1)).append('.').append(Integer.toHexString(customColor2));
            }
            if (themeColor1 != 0 && themeColor2 != 0) {
                sb.append("|tx=").append(Integer.toHexString(themeColor1 & 0xFFFFFF)).append('.').append(Integer.toHexString(themeColor2 & 0xFFFFFF));
            }
            if (themeBase != null && themePalette != null && themePalette.length > 0) {
                sb.append("|tb=").append(themeBase).append("|tp=");
                for (int i = 0; i < themePalette.length; i++) {
                    if (i > 0) {
                        sb.append('.');
                    }
                    sb.append(Integer.toHexString(themePalette[i] & 0xFFFFFF));
                }
            }
            if (nameColor >= 0) {
                sb.append("|nc=").append(nameColor);
            }
            if (nameEmoji != 0) {
                sb.append("|ne=").append(nameEmoji);
            }
            if (nameCustom1 != 0) {
                sb.append("|nx=").append(Integer.toHexString(nameCustom1)).append('.').append(Integer.toHexString(nameCustom2 != 0 ? nameCustom2 : nameCustom1));
            }
            if (streakDays > 0) {
                sb.append("|s=").append(streakDays).append('.').append(streakColor).append('.').append(streakPartner).append('.').append(streakDay);
            }
            if (SolegramStatsFm.clean(statsFm) != null) {
                sb.append("|sf=").append(SolegramStatsFm.clean(statsFm));
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
                        case "me":
                            c.musicEmoji = Long.parseLong(v);
                            break;
                        case "pc":
                            c.profileColor = Math.max(-1, Math.min(1000, Integer.parseInt(v)));
                            break;
                        case "pe":
                            c.profileEmoji = Long.parseLong(v);
                            break;
                        case "nc":
                            c.nameColor = Math.max(-1, Math.min(1000, Integer.parseInt(v)));
                            break;
                        case "ne":
                            c.nameEmoji = Long.parseLong(v);
                            break;
                        case "sf":
                            c.statsFm = SolegramStatsFm.clean(v);
                            break;
                        case "nx": {
                            final String[] f = v.split("\\.");
                            if (f.length == 2) {
                                c.nameCustom1 = 0xFF000000 | (int) Long.parseLong(f[0], 16);
                                c.nameCustom2 = 0xFF000000 | (int) Long.parseLong(f[1], 16);
                            }
                            break;
                        }
                        case "tb":
                            break; // a theme base from 3.4.1 betas 48-49: not used
                        case "tp": {
                            final String[] f = v.split("\\.");
                            final int n = Math.min(f.length, 32);
                            final int[] palette = new int[n];
                            for (int i = 0; i < n; i++) {
                                palette[i] = 0xFF000000 | (int) Long.parseLong(f[i], 16);
                            }
                            c.themePalette = palette;
                            break;
                        }
                        case "tx": {
                            final String[] f = v.split("\\.");
                            if (f.length == 2) {
                                c.themeColor1 = 0xFF000000 | (int) Long.parseLong(f[0], 16);
                                c.themeColor2 = 0xFF000000 | (int) Long.parseLong(f[1], 16);
                            }
                            break;
                        }
                        case "px": {
                            final String[] f = v.split("\\.");
                            if (f.length == 2) {
                                // opaque colours only: a card can't make a profile invisible
                                c.customColor1 = 0xFF000000 | (int) Long.parseLong(f[0], 16);
                                c.customColor2 = 0xFF000000 | (int) Long.parseLong(f[1], 16);
                            }
                            break;
                        }
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
        long userId;
        Card card; // null: known to have none
        long at;
        /** When the card (or that there is none) was last known, from this run or from the disk (wall time). */
        long knownAt;
        boolean loading;
        final ArrayList<Runnable> waiting = new ArrayList<>();
    }

    private static final LongSparseArray<Entry> cache = new LongSparseArray<>();

    // the cards read are kept on the disk for a few days: names and profiles show others' colours at once
    private static final long DISK_TTL = 3L * 24 * 60 * 60 * 1000;

    private static android.content.SharedPreferences disk() {
        return org.telegram.messenger.ApplicationLoader.applicationContext.getSharedPreferences("solegram_cards", android.content.Context.MODE_PRIVATE);
    }

    /** The entry of a user, from this run or read back from the disk (null: nothing known). UI thread. */
    @Nullable
    private static Entry entry(long userId) {
        Entry e = cache.get(userId);
        if (e != null) {
            return e;
        }
        final String saved = disk().getString("c" + userId, null);
        e = new Entry();
        e.userId = userId;
        if (saved != null) {
            final int bar = saved.indexOf('|');
            try {
                e.knownAt = Long.parseLong(saved.substring(0, bar));
                final String body = saved.substring(bar + 1);
                e.card = "-".equals(body) ? null : Card.decode(body);
            } catch (Exception ignore) {
                e.knownAt = 0;
                e.card = null;
            }
        }
        cache.put(userId, e);
        return e;
    }

    private static void remember(Entry e) {
        e.knownAt = System.currentTimeMillis();
        if (e.userId == 0) {
            return;
        }
        final android.content.SharedPreferences prefs = disk();
        if (prefs.getAll().size() > 1500) {
            prefs.edit().clear().apply(); // a simple bound: read again when needed
        }
        prefs.edit().putString("c" + e.userId, e.knownAt + "|" + (e.card == null ? "-" : e.card.encode())).apply();
    }

    /** The card of a user as last read (null: none or not read yet). UI thread. */
    @Nullable
    public static Card get(long userId) {
        final Entry e = entry(userId);
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
        Entry e = entry(userId);
        if (e == null) {
            e = new Entry();
            e.userId = userId;
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
            remember(entry);
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
        remember(e);
        final ArrayList<Runnable> run = new ArrayList<>(e.waiting);
        e.waiting.clear();
        for (Runnable r : run) {
            r.run();
        }
    }

    public static void forget(long userId) {
        cache.remove(userId);
    }

    // region others' colours in chats: their cards read gently, one at a time
    private static final java.util.ArrayDeque<long[]> chatQueue = new java.util.ArrayDeque<>();
    private static final java.util.HashSet<Long> chatQueued = new java.util.HashSet<>();
    private static boolean chatBusy;
    private static long lastRedraw;

    /**
     * Someone's name is shown in a chat: their card (their Solegram name and profile colours) is read once in a few
     * days, one person at a time, so names take the colours they chose. UI thread; cheap when already known.
     */
    public static void requestForChat(int account, @Nullable TLRPC.User user) {
        if (user == null || user.self || user.bot || user.id == 0 || !SolegramConfig.profileCards || UserObject.isDeleted(user)) {
            return;
        }
        final Entry e = entry(user.id);
        if (e != null && (e.loading || e.at != 0 || e.knownAt != 0 && System.currentTimeMillis() - e.knownAt < DISK_TTL)) {
            return;
        }
        if (!chatQueued.add(user.id)) {
            return;
        }
        chatQueue.add(new long[]{account, user.id});
        pumpChat();
    }

    private static void pumpChat() {
        if (chatBusy) {
            return;
        }
        final long[] head = chatQueue.peek();
        if (head == null) {
            return;
        }
        // a message on its way goes first: names wait (and nothing is read while the app is not on screen)
        if (org.telegram.messenger.ApplicationLoader.mainInterfacePaused
            || org.telegram.messenger.SendMessagesHelper.getInstance((int) head[0]).isSendingAnyMessage()) {
            chatBusy = true;
            AndroidUtilities.runOnUIThread(() -> {
                chatBusy = false;
                pumpChat();
            }, org.telegram.messenger.ApplicationLoader.mainInterfacePaused ? 5000 : 1500);
            return;
        }
        final long[] next = chatQueue.poll();
        chatBusy = true;
        final int account = (int) next[0];
        final long userId = next[1];
        final Runnable done = () -> {
            chatBusy = false;
            chatQueued.remove(userId);
            final Card c = get(userId);
            if (c != null && (c.nameColor >= 0 || c.nameCustom1 != 0 || c.nameEmoji != 0 || c.profileColor >= 0 || c.customColor1 != 0)) {
                redraw(account);
            }
            // gently: a little while before the next person
            AndroidUtilities.runOnUIThread(SolegramProfileCard::pumpChat, 1200);
        };
        final MessagesController mc = MessagesController.getInstance(account);
        final TLRPC.User user = mc.getUser(userId);
        if (user == null) {
            done.run();
            return;
        }
        final TLRPC.UserFull full = mc.getUserFull(userId);
        final boolean[] finished = {false};
        final Runnable once = () -> {
            if (!finished[0]) {
                finished[0] = true;
                done.run();
            }
        };
        // never stuck: a request that does not answer lets the next one go
        AndroidUtilities.runOnUIThread(once, 15000);
        if (full != null) {
            loadOrSkip(account, userId, full, once);
        } else {
            mc.loadFullUser(user, 0, false, f -> AndroidUtilities.runOnUIThread(() -> {
                if (f == null) {
                    once.run();
                } else {
                    loadOrSkip(account, userId, f, once);
                }
            }));
        }
    }

    private static void loadOrSkip(int account, long userId, TLRPC.UserFull full, Runnable done) {
        final Entry before = entry(userId);
        final long at = before != null ? before.at : 0;
        load(account, userId, full, done);
        final Entry after = entry(userId);
        // nothing to wait for (no personal channel, or read just now): go on
        if (after == null || !after.loading && after.at != 0 && after.at != at || full.personal_channel_id == 0) {
            done.run();
        }
    }

    /** Chats redraw names in the colours just read (at most every two seconds). */
    private static void redraw(int account) {
        final long now = SystemClock.elapsedRealtime();
        if (now - lastRedraw < 2000) {
            AndroidUtilities.cancelRunOnUIThread(redrawLater);
            AndroidUtilities.runOnUIThread(redrawLater, 2000);
            redrawAccount = account;
            return;
        }
        lastRedraw = now;
        org.telegram.messenger.NotificationCenter.getInstance(account).postNotificationName(org.telegram.messenger.NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_NAME);
    }

    private static int redrawAccount;
    private static final Runnable redrawLater = () -> {
        lastRedraw = SystemClock.elapsedRealtime();
        org.telegram.messenger.NotificationCenter.getInstance(redrawAccount).postNotificationName(org.telegram.messenger.NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_NAME);
    };
    // endregion
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

    /** The profile colour and pattern chosen in Solegram, as the own card should carry them. */
    static void applyLocalStyle(Card card) {
        if (SolegramSelfColors.isSet()) {
            card.profileColor = SolegramSelfColors.profileColor();
            card.profileEmoji = SolegramSelfColors.profileEmoji();
        } else {
            card.profileColor = -1;
            card.profileEmoji = 0;
        }
        card.customColor1 = SolegramSelfColors.customColor1();
        card.customColor2 = SolegramSelfColors.customColor2();
        card.nameColor = SolegramSelfColors.isSet() ? SolegramSelfColors.nameColor() : -1;
        card.nameEmoji = SolegramSelfColors.isSet() ? SolegramSelfColors.nameEmoji() : 0;
        card.nameCustom1 = SolegramSelfColors.nameCustom1();
        card.nameCustom2 = SolegramSelfColors.nameCustom2();
        // the theme's colours are not carried any more (cards from 3.4.1 betas 48-49 may still hold them: ignored)
        card.themeColor1 = 0;
        card.themeColor2 = 0;
        card.themeBase = null;
        card.themePalette = null;
    }

    private static boolean styleMatches(Card card) {
        final Card want = new Card();
        applyLocalStyle(want);
        return card.profileColor == want.profileColor && card.profileEmoji == want.profileEmoji
            && card.customColor1 == want.customColor1 && card.customColor2 == want.customColor2
            && card.nameColor == want.nameColor && card.nameEmoji == want.nameEmoji
            && card.nameCustom1 == want.nameCustom1 && card.nameCustom2 == want.nameCustom2
            && card.themeColor1 == want.themeColor1 && card.themeColor2 == want.themeColor2
            && TextUtils.equals(card.themeBase, want.themeBase) && Arrays.equals(card.themePalette, want.themePalette);
    }


    /** The card for these lookups: only on the UI thread (the cache is not thread-safe; UserObject is asked from
     *  other threads too, which then just see no Solegram style). */
    @Nullable
    private static Card styleCard(long userId) {
        if (userId == 0 || !SolegramConfig.profileCards || android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            return null;
        }
        try {
            return get(userId);
        } catch (Throwable e) {
            return null;
        }
    }

    /** The profile colour someone published on their card (-1: none, or the card is not read yet). */
    public static int profileColorOf(long userId) {
        final Card c = styleCard(userId);
        return c != null ? c.profileColor : -1;
    }

    /** The profile pattern emoji someone published on their card (0: none). */
    public static long profileEmojiOf(long userId) {
        final Card c = styleCard(userId);
        return c != null ? c.profileEmoji : 0;
    }

    /** The name colour id someone chose in Solegram (their own colours, or a palette colour), -1: none / not read. */
    public static int nameColorOf(long userId) {
        final Card c = styleCard(userId);
        if (c == null) {
            return -1;
        }
        if (c.nameCustom1 != 0) {
            return SolegramCustomColors.nameId(c.nameCustom1, c.nameCustom2 != 0 ? c.nameCustom2 : c.nameCustom1);
        }
        return c.nameColor;
    }

    /** The emoji on someone's replies chosen in Solegram (0: none). */
    public static long nameEmojiOf(long userId) {
        final Card c = styleCard(userId);
        return c != null ? c.nameEmoji : 0;
    }

    /** Someone's own profile colours from their card, or null. */
    @Nullable
    public static int[] customColorsOf(long userId) {
        final Card c = styleCard(userId);
        return c != null && c.customColor1 != 0 && c.customColor2 != 0 ? new int[]{c.customColor1, c.customColor2} : null;
    }

    /** The own profile's colour or pattern changed: the card takes it at once so others see it. UI thread. */
    public static void syncProfileStyle(int account) {
        SolegramNameEmojiShare.scheduleSync(); // the colour and background emoji also go into the bio: seen with no banner
        syncMusicEmoji(account, null, true);
    }

    /**
     * The music's pattern emoji was picked: it goes onto the own card at once, so others see it (they read it from
     * the card). Without a card nobody else can see it; {@code noCard} runs then. UI thread.
     */
    public static void syncMusicEmoji(int account, @Nullable Runnable noCard) {
        syncMusicEmoji(account, noCard, true);
    }

    private static void syncMusicEmoji(int account, @Nullable Runnable noCard, boolean mayLoad) {
        final long self = org.telegram.messenger.UserConfig.getInstance(account).getClientUserId();
        final MessagesController mc = MessagesController.getInstance(account);
        final Card card = get(self);
        if (card == null || card.post == null) {
            final TLRPC.UserFull full = mc.getUserFull(self);
            if (mayLoad && full != null) {
                // the own card may not have been read in this run yet
                load(account, self, full, () -> syncMusicEmoji(account, noCard, false));
            } else if (noCard != null) {
                noCard.run();
            }
            return;
        }
        if (card.musicEmoji == SolegramConfig.profileMusicEmoji && styleMatches(card)
            && TextUtils.equals(SolegramStatsFm.clean(card.statsFm), SolegramStatsFm.clean(SolegramConfig.statsFmUser))) {
            return;
        }
        final TLRPC.Chat channel = mc.getChat(-card.post.getDialogId());
        if (channel == null) {
            if (noCard != null) {
                noCard.run();
            }
            return;
        }
        publish(account, channel, card.post, card, error -> {
            if (error != null) {
                org.telegram.messenger.FileLog.e("Solegram: the card did not take the music emoji: " + error);
            }
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
        card.musicEmoji = SolegramConfig.profileMusicEmoji; // the music's pattern goes with the card
        card.statsFm = SolegramStatsFm.clean(SolegramConfig.statsFmUser); // and the linked stats.fm
        applyLocalStyle(card); // and the profile's colour and pattern emoji chosen without Premium
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
