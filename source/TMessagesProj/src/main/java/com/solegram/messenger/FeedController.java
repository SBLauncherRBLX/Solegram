/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger;

import android.text.TextUtils;
import android.util.SparseArray;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;

/**
 * The Feed: recent posts of every channel the user follows, newest first, in one list.
 * Telegram has no such method for regular accounts, so the controller asks each followed channel
 * for its latest posts (a few requests at a time) and merges them. Albums become one post.
 * Loading history does not mark anything as read and does not count views.
 */
public class FeedController {

    public static class Post {
        public long channelId;
        public TLRPC.Chat channel;
        /** First message of the post (the album's first item for albums). */
        public MessageObject message;
        /** All messages of an album, in order; a single message otherwise. */
        public final ArrayList<MessageObject> group = new ArrayList<>();
        public int date;
        public String key;
        /** The long text was unfolded by the user. */
        public boolean expanded;
        /** Translation of the text (Solegram translator), shown instead of the original while showTranslated. */
        public String translatedText, translatedSource;
        public boolean showTranslated, translating;
        /** AI summary of the text (Solegram summarizer), shown instead of the original while showSummary. */
        public String summaryText, summarySource;
        public boolean showSummary, summarizing;
    }

    private static final int CHANNELS_LIMIT = 60;
    private static final int POSTS_PER_CHANNEL = 10;
    private static final int PARALLEL = 3;
    private static final long FRESH_MS = 3 * 60 * 1000L;

    private static final SparseArray<FeedController> instances = new SparseArray<>();

    public static FeedController getInstance(int account) {
        FeedController c = instances.get(account);
        if (c == null) {
            instances.put(account, c = new FeedController(account));
        }
        return c;
    }

    private final int account;
    private final ArrayList<Post> posts = new ArrayList<>();
    /** Posts of each loaded channel; kept while a refresh runs so the list does not collapse. */
    private final HashMap<Long, ArrayList<Post>> byChannel = new HashMap<>();
    /** Every shown post by key, so a refresh keeps the same objects (and their expanded/translated state). */
    private final HashMap<String, Post> postsByKey = new HashMap<>();
    private final HashSet<Long> requestedChannels = new HashSet<>();
    private boolean loading;
    private long loadedAt;
    private int generation;
    private int pendingRequests;
    private ArrayList<TLRPC.Chat> queue;

    private FeedController(int account) {
        this.account = account;
    }

    public ArrayList<Post> getPosts() {
        return posts;
    }

    public boolean isLoading() {
        return loading;
    }

    public boolean isEmptyAndLoaded() {
        return !loading && loadedAt != 0 && posts.isEmpty();
    }

    /** Loads unless the feed was refreshed a moment ago. */
    public void loadIfStale() {
        if (!loading && System.currentTimeMillis() - loadedAt > FRESH_MS) {
            reload();
        }
    }

    /** Followed broadcast channels, most recently active first. */
    private ArrayList<TLRPC.Dialog> channelDialogs() {
        final MessagesController mc = MessagesController.getInstance(account);
        final ArrayList<TLRPC.Dialog> result = new ArrayList<>();
        final HashSet<Long> seen = new HashSet<>();
        for (TLRPC.Dialog d : mc.getAllDialogs()) {
            if (!DialogObject.isChatDialog(d.id)) {
                continue;
            }
            final TLRPC.Chat chat = mc.getChat(-d.id);
            if (chat == null || !ChatObject.isChannelAndNotMegaGroup(chat) || ChatObject.isNotInChat(chat) || mc.isPromoDialog(d.id, false) || !seen.add(chat.id)) {
                continue;
            }
            if (SolegramConfig.feedSkipMuted && mc.isDialogMuted(d.id, 0)) {
                continue;
            }
            result.add(d);
        }
        Collections.sort(result, (a, b) -> Integer.compare(b.last_message_date, a.last_message_date));
        return result;
    }

    private int seenDate = -1;

    private int seenDate() {
        if (seenDate < 0) {
            seenDate = MessagesController.getMainSettings(account).getInt("solegramFeedSeen", 0);
            if (seenDate == 0) {
                // first run: start counting from now instead of showing every channel as new
                markSeen();
            }
        }
        return seenDate;
    }

    /** The Feed was looked at: posts up to now are no longer new. */
    public void markSeen() {
        final int now = ConnectionsManager.getInstance(account).getCurrentTime();
        if (now <= seenDate) {
            return;
        }
        seenDate = now;
        freshCountedAt = 0;
        MessagesController.getMainSettings(account).edit().putInt("solegramFeedSeen", now).apply();
        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.solegramFeedSeen);
    }

    /** Channels that posted since the Feed was last opened (for the tab badge); reads only local data. */
    private int freshCount;
    private boolean recountScheduled;
    private long freshCountedAt;

    public int countFreshChannels() {
        final long now = System.currentTimeMillis();
        if (now - freshCountedAt < 1500) {
            // asked on every interface update: recount at most every 1.5 s, and once more after the pause
            if (!recountScheduled) {
                recountScheduled = true;
                AndroidUtilities.runOnUIThread(() -> {
                    recountScheduled = false;
                    freshCountedAt = 0;
                    NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.solegramFeedSeen);
                }, 1600);
            }
            return freshCount;
        }
        freshCountedAt = now;
        final int since = seenDate();
        final MessagesController mc = MessagesController.getInstance(account);
        int count = 0;
        for (TLRPC.Dialog d : mc.getAllDialogs()) {
            if (d.last_message_date <= since || !DialogObject.isChatDialog(d.id)) {
                continue;
            }
            final TLRPC.Chat chat = mc.getChat(-d.id);
            if (chat == null || !ChatObject.isChannelAndNotMegaGroup(chat) || ChatObject.isNotInChat(chat) || mc.isPromoDialog(d.id, false)) {
                continue;
            }
            if (SolegramConfig.feedSkipMuted && mc.isDialogMuted(d.id, 0)) {
                continue;
            }
            count++;
        }
        return freshCount = count;
    }

    public void reload() {
        if (!UserConfig.getInstance(account).isClientActivated()) {
            return;
        }
        final MessagesController mc = MessagesController.getInstance(account);
        final ArrayList<TLRPC.Chat> channels = new ArrayList<>();
        for (TLRPC.Dialog d : channelDialogs()) {
            channels.add(mc.getChat(-d.id));
            if (channels.size() >= CHANNELS_LIMIT) {
                break;
            }
        }
        generation++;
        loading = true;
        requestedChannels.clear();
        for (TLRPC.Chat chat : channels) {
            requestedChannels.add(chat.id);
        }
        queue = channels;
        pendingRequests = 0;
        notifyChanged();
        if (channels.isEmpty()) {
            finish();
            return;
        }
        for (int i = 0; i < PARALLEL; i++) {
            requestNext(generation);
        }
    }

    private void requestNext(int gen) {
        if (gen != generation) {
            return;
        }
        if (queue == null || queue.isEmpty()) {
            if (pendingRequests == 0) {
                finish();
            }
            return;
        }
        final TLRPC.Chat chat = queue.remove(0);
        final TLRPC.TL_messages_getHistory req = new TLRPC.TL_messages_getHistory();
        req.peer = MessagesController.getInputPeer(chat);
        req.limit = POSTS_PER_CHANNEL;
        pendingRequests++;
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (gen != generation) {
                return; // reload() already reset the counter for the new round
            }
            pendingRequests--;
            if (response instanceof TLRPC.messages_Messages) {
                final TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
                MessagesController.getInstance(account).putUsers(res.users, false);
                MessagesController.getInstance(account).putChats(res.chats, false);
                final ArrayList<TLRPC.Message> list = new ArrayList<>();
                for (TLRPC.Message m : res.messages) {
                    if (m instanceof TLRPC.TL_message) {
                        list.add(m);
                    }
                }
                byChannel.put(chat.id, buildChannelPosts(chat, list));
                rebuild();
            } else if (error != null) {
                FileLog.d("feed: " + chat.id + " " + error.text);
            }
            requestNext(gen);
        }));
    }

    private void finish() {
        loading = false;
        loadedAt = System.currentTimeMillis();
        // channels that are no longer followed (or now hidden) leave the feed
        byChannel.keySet().retainAll(requestedChannels);
        final HashSet<String> alive = new HashSet<>();
        for (ArrayList<Post> list : byChannel.values()) {
            for (Post post : list) {
                alive.add(post.key);
            }
        }
        postsByKey.keySet().retainAll(alive);
        rebuild();
    }

    /** One channel's messages as posts (album items folded into one); MessageObjects are made once here. */
    private ArrayList<Post> buildChannelPosts(TLRPC.Chat chat, ArrayList<TLRPC.Message> messages) {
        final ArrayList<Post> result = new ArrayList<>();
        final HashMap<Long, Post> albums = new HashMap<>();
        for (TLRPC.Message m : messages) {
            final boolean noMedia = m.media == null || m.media instanceof TLRPC.TL_messageMediaEmpty;
            if (noMedia && TextUtils.isEmpty(m.message)) {
                continue;
            }
            final MessageObject mo = new MessageObject(account, m, false, false);
            if (m.grouped_id != 0) {
                final Post album = albums.get(m.grouped_id);
                if (album != null) {
                    album.group.add(mo);
                    // the album's caption usually sits on one of the items
                    if (TextUtils.isEmpty(album.message.messageOwner.message) && !TextUtils.isEmpty(m.message)) {
                        album.message = mo;
                    }
                    continue;
                }
            }
            final String key = chat.id + (m.grouped_id != 0 ? "g" + m.grouped_id : "m" + m.id);
            Post post = postsByKey.get(key);
            if (post == null) {
                postsByKey.put(key, post = new Post());
            }
            post.key = key;
            post.channelId = chat.id;
            post.channel = chat;
            post.message = mo;
            post.group.clear();
            post.group.add(mo);
            post.date = m.date;
            result.add(post);
            if (m.grouped_id != 0) {
                albums.put(m.grouped_id, post);
            }
        }
        for (Post album : albums.values()) {
            Collections.sort(album.group, (a, b) -> Integer.compare(a.getId(), b.getId()));
        }
        for (Post post : result) {
            if (post.translatedText != null && !TextUtils.equals(post.translatedSource, post.message.messageOwner.message)) {
                post.translatedText = null; // the post was edited since it was translated
                post.showTranslated = false;
            }
            if (post.summaryText != null && !TextUtils.equals(post.summarySource, post.message.messageOwner.message)) {
                post.summaryText = null; // edited since it was summarized
                post.showSummary = false;
            }
        }
        return result;
    }

    /** Merges all loaded channels into one list, newest first. */
    private void rebuild() {
        final ArrayList<Post> result = new ArrayList<>();
        for (ArrayList<Post> list : byChannel.values()) {
            result.addAll(list);
        }
        Collections.sort(result, (a, b) -> Integer.compare(b.date, a.date));
        posts.clear();
        posts.addAll(result);
        notifyChanged();
    }

    private void notifyChanged() {
        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.solegramFeedUpdated);
    }
}
