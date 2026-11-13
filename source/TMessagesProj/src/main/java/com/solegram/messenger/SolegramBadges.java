/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Solegram badges next to names: the developer (the Solegram mark in orange), testers (the mark in violet) and
 * the official channel (an orange scalloped badge with the mark in it).
 * <p>
 * The list lives in the description of the official channel, as an invisible mark at its end (an invisible plus
 * and then variation selectors, four bits each), so it needs no server of our own and only the channel's admins can
 * change it: Telegram itself checks that. Every Solegram reads it from the channel now and then, people see
 * nothing in the description in any app. Who may manage the list (the admin panel) is decided the same way: an
 * account that may change the channel's info.
 */
public final class SolegramBadges {

    public static final int NONE = 0;
    public static final int DEVELOPER = 1;
    public static final int TESTER = 2;
    /** A whole custom emoji pack hidden as an imitation of a badge (by its set id). */
    public static final int BLOCK_SET = 3;
    /** One custom emoji hidden as an imitation of a badge (by its document id). */
    public static final int BLOCK_EMOJI = 4;
    /** Only for the official channel itself; never stored in the list. */
    public static final int OFFICIAL = 15;

    public static final int COLOR_ORANGE = 0xFFF2462C;
    public static final int COLOR_VIOLET = 0xFF8B5CF6;

    /** Telegram's limit for a channel description. */
    public static final int ABOUT_LIMIT = 255;

    private static final char START = '⁤';
    private static final int VERSION = 1;
    private static final long STALE_MS = 6 * 60 * 60 * 1000L;

    private static final String PREFS = "solegram_badges";

    private static volatile Map<Long, Integer> roles;
    private static volatile long officialChannelId;
    private static boolean refreshing;
    private static final boolean[] adminOf = new boolean[UserConfig.MAX_ACCOUNT_COUNT];
    private static final boolean[] adminKnown = new boolean[UserConfig.MAX_ACCOUNT_COUNT];

    private SolegramBadges() {
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** The official channel's username (fixed in the build: a channel picked in the settings does not count). */
    public static String officialUsername() {
        return BuildConfig.SOLEGRAM_UPDATE_CHANNEL;
    }

    // region Reading

    private static Map<Long, Integer> roles() {
        Map<Long, Integer> r = roles;
        if (r == null) {
            synchronized (SolegramBadges.class) {
                if (roles == null) {
                    final SharedPreferences p = prefs();
                    final Map<Long, Integer> loaded = decode(p.getString("list", ""));
                    officialChannelId = p.getLong("official", 0);
                    roles = loaded == null ? new HashMap<>() : loaded;
                    for (int a = 0; a < adminOf.length; a++) {
                        adminOf[a] = p.getBoolean("admin" + a, false);
                    }
                }
                r = roles;
            }
        }
        return r;
    }

    /** The badge of a user (dialog id > 0) or of a channel (dialog id < 0). */
    public static int roleOf(long dialogId) {
        if (dialogId == 0) {
            return NONE;
        }
        final Map<Long, Integer> r = roles();
        if (dialogId < 0) {
            return officialChannelId != 0 && -dialogId == officialChannelId ? OFFICIAL : NONE;
        }
        final Integer role = r.get(dialogId);
        return role == null || role != DEVELOPER && role != TESTER ? NONE : role;
    }

    public static int roleOf(@Nullable TLRPC.User user) {
        return user == null ? NONE : roleOf(user.id);
    }

    public static int roleOf(@Nullable TLRPC.Chat chat) {
        return chat == null ? NONE : roleOf(-chat.id);
    }

    // region Imitations

    /** Emoji already looked at: document id -> hidden. Cleared whenever the list changes. */
    private static final androidx.collection.LongSparseArray<Boolean> verdicts = new androidx.collection.LongSparseArray<>();

    /**
     * An emoji status (or an emoji next to a name shared by Solegram) as it may be shown: 0 when it is an imitation of
     * a Solegram badge, hidden by the developer. Nobody can wear a badge but the people in the list: a lookalike
     * custom emoji is not drawn in Solegram at all.
     */
    public static long filterEmoji(long documentId) {
        if (documentId == 0) {
            return 0;
        }
        final Map<Long, Integer> r = roles();
        final Integer role = r.get(documentId);
        if (role != null && role == BLOCK_EMOJI) {
            return 0;
        }
        synchronized (verdicts) {
            final Boolean known = verdicts.get(documentId);
            if (known != null) {
                return known ? 0 : documentId;
            }
        }
        // the pack it comes from, once the emoji itself is loaded (it is, by the time it is drawn)
        final TLRPC.Document document = org.telegram.ui.Components.AnimatedEmojiDrawable.findDocument(UserConfig.selectedAccount, documentId);
        if (document == null) {
            return documentId;
        }
        final TLRPC.InputStickerSet set = org.telegram.messenger.MessageObject.getInputStickerSet(document);
        boolean hidden = set instanceof TLRPC.TL_inputStickerSetID && r.get(set.id) != null && r.get(set.id) == BLOCK_SET;
        if (!hidden && set instanceof TLRPC.TL_inputStickerSetID) {
            // a pack named after Solegram is taken for an imitation by itself, without waiting for the developer
            final TLRPC.TL_messages_stickerSet full = org.telegram.messenger.MediaDataController.getInstance(UserConfig.selectedAccount).getStickerSetById(set.id);
            if (full != null && full.set != null) {
                final String name = ((full.set.title == null ? "" : full.set.title) + " " + (full.set.short_name == null ? "" : full.set.short_name)).toLowerCase(java.util.Locale.ROOT);
                hidden = name.contains("solegram") || name.contains("солеграм");
            } else {
                return documentId; // the pack's name is not known yet: asked again next time
            }
        }
        synchronized (verdicts) {
            verdicts.put(documentId, hidden);
        }
        return hidden ? 0 : documentId;
    }

    private static void forgetVerdicts() {
        synchronized (verdicts) {
            verdicts.clear();
        }
    }
    // endregion

    /** Everyone in the list, by role. */
    public static ArrayList<long[]> entries() {
        final ArrayList<long[]> out = new ArrayList<>();
        for (Map.Entry<Long, Integer> e : roles().entrySet()) {
            out.add(new long[]{e.getKey(), e.getValue()});
        }
        Collections.sort(out, (a, b) -> a[1] != b[1] ? Long.compare(a[1], b[1]) : Long.compare(a[0], b[0]));
        return out;
    }

    public static long officialChannelId() {
        roles();
        return officialChannelId;
    }

    /** Whether this account may change the list (it may change the official channel's info), as last checked. */
    public static boolean isAdmin(int account) {
        roles();
        return account >= 0 && account < adminOf.length && adminOf[account];
    }
    // endregion

    // region The mark

    /** The list as an invisible mark: START, the version, then for each person: role, length of the id, the id. */
    public static String encode(Map<Long, Integer> list) {
        if (list == null || list.isEmpty()) {
            return "";
        }
        final StringBuilder sb = new StringBuilder();
        sb.append(START);
        nibble(sb, VERSION);
        final ArrayList<Long> ids = new ArrayList<>(list.keySet());
        Collections.sort(ids);
        for (long id : ids) {
            final int role = list.get(id);
            if (id == 0 || role <= NONE || role > BLOCK_EMOJI) {
                continue;
            }
            int digits = 1;
            while (digits < 16 && (id >>> (digits * 4)) != 0) {
                digits++;
            }
            nibble(sb, role);
            nibble(sb, digits & 0xF); // 0 stands for 16 (a full 64-bit id of an emoji or a pack)
            for (int i = digits - 1; i >= 0; i--) {
                nibble(sb, (int) ((id >>> (i * 4)) & 0xF));
            }
        }
        return sb.toString();
    }

    private static void nibble(StringBuilder sb, int v) {
        sb.append((char) (0xFE00 + (v & 0xF)));
    }

    /** The list in a description, null when it has no (valid) mark. */
    @Nullable
    public static Map<Long, Integer> decode(String about) {
        if (TextUtils.isEmpty(about)) {
            return null;
        }
        final int at = about.lastIndexOf(START);
        if (at < 0) {
            return null;
        }
        final ArrayList<Integer> n = new ArrayList<>();
        for (int i = at + 1; i < about.length(); i++) {
            final char c = about.charAt(i);
            if (c < 0xFE00 || c > 0xFE0F) {
                break;
            }
            n.add(c - 0xFE00);
        }
        if (n.isEmpty() || n.get(0) != VERSION) {
            return null;
        }
        final HashMap<Long, Integer> out = new HashMap<>();
        int i = 1;
        while (i + 2 <= n.size()) {
            final int role = n.get(i);
            final int digits = n.get(i + 1) == 0 ? 16 : n.get(i + 1);
            i += 2;
            if (digits <= 0 || i + digits > n.size()) {
                break;
            }
            long id = 0;
            for (int k = 0; k < digits; k++) {
                id = (id << 4) | n.get(i + k);
            }
            i += digits;
            if (id != 0 && role > NONE && role <= BLOCK_EMOJI) {
                out.put(id, role);
            }
        }
        return out;
    }

    /** A description without the mark (what people read). */
    public static String strip(String about) {
        if (about == null) {
            return "";
        }
        final int at = about.lastIndexOf(START);
        if (at < 0) {
            return about;
        }
        int end = at + 1;
        while (end < about.length() && about.charAt(end) >= 0xFE00 && about.charAt(end) <= 0xFE0F) {
            end++;
        }
        return about.substring(0, at) + about.substring(end);
    }

    /** Characters a list would take in the description. */
    public static int markLength(Map<Long, Integer> list) {
        return encode(list).length();
    }
    // endregion

    // region Loading

    /** Checks the list now and then (on start and every few hours). */
    public static void refreshIfStale() {
        if (System.currentTimeMillis() - prefs().getLong("checked", 0) > STALE_MS) {
            refresh(null);
        }
    }

    public interface Done {
        void run(boolean ok, @Nullable String error);
    }

    /** Reads the official channel: the list in its description, its id, and whether each account may manage it. */
    public static void refresh(@Nullable Done done) {
        AndroidUtilities.runOnUIThread(() -> {
            final int account = firstAccount();
            if (account < 0 || refreshing || TextUtils.isEmpty(officialUsername())) {
                if (done != null) {
                    done.run(false, account < 0 ? "no account" : null);
                }
                return;
            }
            refreshing = true;
            resolve(account, (chat, error) -> {
                if (chat == null) {
                    refreshing = false;
                    if (done != null) {
                        done.run(false, error);
                    }
                    return;
                }
                readFull(account, chat, (about, error2) -> {
                    refreshing = false;
                    if (about == null) {
                        if (done != null) {
                            done.run(false, error2);
                        }
                        return;
                    }
                    apply(chat.id, about);
                    checkAdmins(chat);
                    if (done != null) {
                        done.run(true, null);
                    }
                });
            });
        });
    }

    /** Whether each signed-in account may change the channel's info (asked of Telegram for each of them). */
    private static void checkAdmins(TLRPC.Chat seen) {
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (!UserConfig.getInstance(a).isClientActivated()) {
                setAdmin(a, false);
                continue;
            }
            final int account = a;
            resolve(account, (chat, error) -> {
                if (chat != null) {
                    setAdmin(account, canManage(chat));
                } else if (error != null) {
                    adminKnown[account] = false;
                }
            });
        }
    }

    public static boolean canManage(TLRPC.Chat chat) {
        return chat != null && (chat.creator || !chat.left && chat.admin_rights != null && chat.admin_rights.change_info);
    }

    private static void setAdmin(int account, boolean admin) {
        roles();
        adminKnown[account] = true;
        if (adminOf[account] != admin) {
            adminOf[account] = admin;
            prefs().edit().putBoolean("admin" + account, admin).apply();
            AndroidUtilities.runOnUIThread(() -> NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.mainUserInfoChanged));
        }
    }

    private static int firstAccount() {
        if (UserConfig.getInstance(UserConfig.selectedAccount).isClientActivated()) {
            return UserConfig.selectedAccount;
        }
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (UserConfig.getInstance(a).isClientActivated()) {
                return a;
            }
        }
        return -1;
    }

    public interface ChatCallback {
        void run(@Nullable TLRPC.Chat chat, @Nullable String error);
    }

    interface AboutCallback {
        void run(@Nullable String about, @Nullable String error);
    }

    /** The official channel as this account sees it (its rights in it included). */
    public static void resolve(int account, ChatCallback callback) {
        final TLRPC.TL_contacts_resolveUsername req = new TLRPC.TL_contacts_resolveUsername();
        req.username = officialUsername();
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (!(response instanceof TLRPC.TL_contacts_resolvedPeer)) {
                callback.run(null, error != null ? error.text : "not found");
                return;
            }
            final TLRPC.TL_contacts_resolvedPeer resolved = (TLRPC.TL_contacts_resolvedPeer) response;
            MessagesController.getInstance(account).putUsers(resolved.users, false);
            MessagesController.getInstance(account).putChats(resolved.chats, false);
            TLRPC.Chat chat = null;
            if (resolved.peer instanceof TLRPC.TL_peerChannel) {
                for (TLRPC.Chat c : resolved.chats) {
                    if (c.id == resolved.peer.channel_id) {
                        chat = c;
                    }
                }
            }
            // a broadcast channel only: a group under that name (after a username change) is not the official one
            if (chat == null || chat.megagroup) {
                callback.run(null, "not a channel");
                return;
            }
            // the channel is known by its id from the first time on: if the username ever went to another channel,
            // that channel would get neither the badge nor the list, nor the panel
            final long pinned = prefs().getLong("pinned", 0);
            if (pinned != 0 && pinned != chat.id) {
                callback.run(null, "CHANNEL_CHANGED");
                return;
            }
            callback.run(chat, null);
        }));
    }

    static void readFull(int account, TLRPC.Chat chat, AboutCallback callback) {
        final TLRPC.TL_channels_getFullChannel req = new TLRPC.TL_channels_getFullChannel();
        req.channel = MessagesController.getInputChannel(chat);
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (!(response instanceof TLRPC.TL_messages_chatFull) || ((TLRPC.TL_messages_chatFull) response).full_chat == null) {
                callback.run(null, error != null ? error.text : "error");
                return;
            }
            final TLRPC.TL_messages_chatFull full = (TLRPC.TL_messages_chatFull) response;
            MessagesController.getInstance(account).putUsers(full.users, false);
            MessagesController.getInstance(account).putChats(full.chats, false);
            callback.run(full.full_chat.about == null ? "" : full.full_chat.about, null);
        }));
    }

    /** Takes a description just read from the channel as the list. */
    private static void apply(long channelId, String about) {
        final Map<Long, Integer> list = decode(about);
        final String encoded = encode(list);
        final SharedPreferences p = prefs();
        final boolean changed = !encoded.equals(p.getString("list", "")) || p.getLong("official", 0) != channelId;
        final SharedPreferences.Editor e = p.edit().putString("list", encoded).putLong("official", channelId).putLong("checked", System.currentTimeMillis());
        if (p.getLong("pinned", 0) == 0) {
            e.putLong("pinned", channelId);
        }
        e.apply();
        synchronized (SolegramBadges.class) {
            roles = list == null ? new HashMap<>() : list;
            officialChannelId = channelId;
        }
        forgetVerdicts();
        if (changed) {
            redraw();
        }
    }

    /** Names everywhere are drawn again, with the new badges. */
    public static void redraw() {
        AndroidUtilities.runOnUIThread(() -> {
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                if (UserConfig.getInstance(a).isClientActivated()) {
                    NotificationCenter.getInstance(a).postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_NAME | MessagesController.UPDATE_MASK_CHAT_NAME);
                }
            }
        });
    }
    // endregion

    // region Changing (the admin panel)

    /**
     * Writes a changed list to the official channel's description: the description is read again first (someone
     * may have changed it), its visible text is kept, the list goes after it. Telegram refuses it unless this account
     * may change the channel's info.
     */
    public static void write(int account, Map<Long, Integer> list, Done done) {
        resolve(account, (chat, error) -> {
            if (chat == null) {
                done.run(false, error);
                return;
            }
            if (!canManage(chat)) {
                setAdmin(account, false);
                done.run(false, "CHAT_ADMIN_REQUIRED");
                return;
            }
            readFull(account, chat, (about, error2) -> {
                if (about == null) {
                    done.run(false, error2);
                    return;
                }
                final String text = strip(about);
                final String mark = encode(list);
                final String next = text + mark;
                if (next.length() > ABOUT_LIMIT) {
                    done.run(false, "ABOUT_TOO_LONG");
                    return;
                }
                final TLRPC.TL_messages_editChatAbout req = new TLRPC.TL_messages_editChatAbout();
                req.peer = MessagesController.getInputPeer(chat);
                req.about = next;
                ConnectionsManager.getInstance(account).sendRequest(req, (response, error3) -> AndroidUtilities.runOnUIThread(() -> {
                    if (response instanceof TLRPC.TL_boolTrue || error3 == null && response != null) {
                        apply(chat.id, next);
                        done.run(true, null);
                    } else if (error3 != null && "CHAT_ABOUT_NOT_MODIFIED".equals(error3.text)) {
                        apply(chat.id, next);
                        done.run(true, null);
                    } else {
                        done.run(false, error3 != null ? error3.text : "error");
                    }
                }));
            });
        });
    }

    /** Room left in the description for more people, in characters. */
    public static int roomLeft(String visibleAbout, Map<Long, Integer> list) {
        return ABOUT_LIMIT - (visibleAbout == null ? 0 : visibleAbout.length()) - markLength(list);
    }

    /** A copy of the list to change. */
    public static HashMap<Long, Integer> copy() {
        return new HashMap<>(roles());
    }
    // endregion

}
