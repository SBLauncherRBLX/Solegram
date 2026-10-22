/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger;

import android.text.TextUtils;
import android.util.SparseArray;

import com.solegram.messenger.SolegramFilterEngine.Rule;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.utils.tlutils.TLKeyboardHelper;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_keyboard;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

/**
 * Message filters: messages that match the user's rules or the built-in presets are folded to a slim line
 * or not shown at all, in chats and in the Feed. Each message is checked once: the verdict is kept per
 * (dialog, message, edit date) until the rules change, so binding and drawing never run a rule again.
 * Your own messages, Telegram's sponsored messages and search results are never filtered.
 * Everything runs on the UI thread; the file is written in the background.
 */
public final class SolegramMessageFilters {

    private SolegramMessageFilters() {
    }

    public static final int MODE_COLLAPSE = 0;
    public static final int MODE_HIDE = 1;

    private static final String FILE = "solegram_filters.json";
    private static final String FORMAT = "solegram-filters";

    private static boolean loaded;
    private static final ArrayList<Rule> rules = new ArrayList<>();
    private static final Rule presetAds = preset(SolegramFilterEngine.PRESET_ADS);
    private static final Rule presetJunk = preset(SolegramFilterEngine.PRESET_JUNK);
    private static final Rule presetInvites = preset(SolegramFilterEngine.PRESET_INVITES);
    /** Chats where the filters are off. */
    private static final HashSet<Long> excluded = new HashSet<>();
    /** rule id -> {total, today, day of "today"} */
    private static final HashMap<Long, long[]> stats = new HashMap<>();
    /** dialog id -> {lowest, highest} message id already counted, so a message counts once across restarts */
    private static final HashMap<Long, int[]> counted = new HashMap<>();

    private static int localVersion;
    /** The settings that decide what is hidden, as last seen by {@link #version()}. */
    private static int configStamp = Integer.MIN_VALUE;
    private static int builtFor = -1;
    private static final ArrayList<Rule> active = new ArrayList<>();
    private static boolean needNames, needUrls;

    private static Rule preset(long id) {
        final Rule r = new Rule();
        r.id = id;
        r.type = SolegramFilterEngine.TYPE_PRESET;
        return r;
    }

    // region State

    /**
     * Changes whenever anything that decides what is hidden changes (rules, presets, mode, exceptions). Only the
     * filter settings count: any other Solegram setting changing must not throw away every verdict and re-lay out chats.
     */
    public static int version() {
        final int stamp = (SolegramConfig.messageFilters ? 1 : 0) | (SolegramConfig.filterAds ? 2 : 0) | (SolegramConfig.filterJunk ? 4 : 0)
            | (SolegramConfig.filterInvites ? 8 : 0) | (SolegramConfig.filterAdsStrict ? 16 : 0) | SolegramConfig.messageFiltersMode << 5;
        if (stamp != configStamp) {
            configStamp = stamp;
            localVersion++;
        }
        return localVersion;
    }

    /** Cheap: whether anything can be hidden at all. */
    public static boolean isActive() {
        if (!SolegramConfig.messageFilters) {
            return false;
        }
        ensureBuilt();
        return !active.isEmpty();
    }

    public static int mode() {
        return SolegramConfig.messageFiltersMode == MODE_HIDE ? MODE_HIDE : MODE_COLLAPSE;
    }

    private static void ensureLoaded() {
        if (!loaded) {
            loaded = true;
            load();
        }
    }

    private static void ensureBuilt() {
        ensureLoaded();
        final int v = version();
        if (builtFor == v) {
            return;
        }
        builtFor = v;
        active.clear();
        needNames = false;
        // the user's own rules first: their names say more on the folded line than a preset's
        for (Rule r : rules) {
            if (r.enabled && r.getError() == null) {
                active.add(r);
                needNames |= SolegramFilterEngine.needsNames(r);
            }
        }
        presetAds.enabled = SolegramConfig.filterAds;
        presetAds.strict = SolegramConfig.filterAdsStrict;
        presetJunk.enabled = SolegramConfig.filterJunk;
        presetInvites.enabled = SolegramConfig.filterInvites;
        for (Rule p : new Rule[]{presetAds, presetJunk, presetInvites}) {
            if (p.enabled) {
                active.add(p);
            }
        }
        needUrls = presetAds.enabled || presetInvites.enabled;
    }

    private static void changed() {
        localVersion++;
        builtFor = -1;
        scheduleSave(300);
    }

    public static ArrayList<Rule> getRules() {
        ensureLoaded();
        return rules;
    }

    public static Rule getPreset(long id) {
        return id == SolegramFilterEngine.PRESET_ADS ? presetAds : id == SolegramFilterEngine.PRESET_JUNK ? presetJunk : presetInvites;
    }

    public static Rule findRule(long id) {
        if (id < 0) {
            return getPreset(id);
        }
        for (Rule r : getRules()) {
            if (r.id == id) {
                return r;
            }
        }
        return null;
    }

    /** Adds or replaces (same id) a rule; the first rule turns the filters on. */
    public static void saveRule(Rule rule) {
        ensureLoaded();
        SolegramFilterEngine.compile(rule);
        boolean replaced = false;
        for (int i = 0; i < rules.size(); i++) {
            if (rules.get(i).id == rule.id) {
                rules.set(i, rule);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            if (rule.id <= 0) {
                rule.id = newId();
            }
            rules.add(rule);
            turnOnIfOff();
        }
        changed();
    }

    public static void deleteRule(long id) {
        ensureLoaded();
        for (Iterator<Rule> it = rules.iterator(); it.hasNext(); ) {
            if (it.next().id == id) {
                it.remove();
            }
        }
        stats.remove(id);
        changed();
    }

    public static void setRuleEnabled(Rule rule, boolean enabled) {
        rule.enabled = enabled;
        if (enabled) {
            turnOnIfOff();
        }
        changed();
    }

    /** A preset or a rule switched on while the master switch is off: the user clearly wants filtering. */
    public static void turnOnIfOff() {
        if (!SolegramConfig.messageFilters) {
            SolegramConfig.putBoolean("messageFilters", true);
        }
    }

    private static long newId() {
        long id = System.currentTimeMillis();
        for (Rule r : rules) {
            if (r.id >= id) {
                id = r.id + 1;
            }
        }
        return id;
    }

    public static boolean isChatExcluded(long dialogId) {
        ensureLoaded();
        return excluded.contains(dialogId);
    }

    public static void setChatExcluded(long dialogId, boolean off) {
        ensureLoaded();
        if (off ? excluded.add(dialogId) : excluded.remove(dialogId)) {
            changed();
        }
    }

    public static ArrayList<Long> getExcludedChats() {
        ensureLoaded();
        return new ArrayList<>(excluded);
    }
    // endregion

    // region Checking messages

    private static final class Verdict {
        int account;
        int editDate;
        /** [0] = in a chat, [1] = in the Feed */
        final int[] version = {-1, -1};
        final Rule[] rule = new Rule[2];
        boolean revealed;
    }

    private static final int MAX_DIALOGS = 48;
    private static final int MAX_PER_DIALOG = 3000;
    private static final LinkedHashMap<Long, SparseArray<Verdict>> cache = new LinkedHashMap<Long, SparseArray<Verdict>>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, SparseArray<Verdict>> eldest) {
            return size() > MAX_DIALOGS;
        }
    };
    private static long lastDialog = Long.MIN_VALUE;
    private static SparseArray<Verdict> lastEntries;
    private static final SolegramFilterEngine.Subject subject = new SolegramFilterEngine.Subject();

    private static Verdict entry(int account, long dialogId, int messageId, boolean create) {
        SparseArray<Verdict> entries;
        if (dialogId == lastDialog && lastEntries != null) {
            entries = lastEntries;
        } else {
            entries = cache.get(dialogId);
            if (entries == null) {
                if (!create) {
                    return null;
                }
                cache.put(dialogId, entries = new SparseArray<>());
            }
            lastDialog = dialogId;
            lastEntries = entries;
        }
        Verdict e = entries.get(messageId);
        if (e != null && e.account != account) {
            // the same dialog id and message id in another account is another message
            if (!create) {
                return null;
            }
            entries.remove(messageId);
            e = null;
        }
        if (e == null && create) {
            if (entries.size() >= MAX_PER_DIALOG) {
                entries.clear();
            }
            e = new Verdict();
            e.account = account;
            entries.put(messageId, e);
        }
        return e;
    }

    /** Where a chat is, for the rule scopes. */
    public static int whereOf(TLRPC.Chat chat) {
        if (chat == null) {
            return SolegramFilterEngine.WHERE_PRIVATE;
        }
        return ChatObject.isChannelAndNotMegaGroup(chat) ? SolegramFilterEngine.WHERE_CHANNEL : SolegramFilterEngine.WHERE_GROUP;
    }

    /** Whether filters work in this chat at all (they are off in your Saved Messages and in excluded chats). */
    public static boolean appliesIn(int account, long dialogId) {
        return isActive() && !excluded.contains(dialogId) && dialogId != org.telegram.messenger.UserConfig.getInstance(account).getClientUserId();
    }

    private static boolean filterable(MessageObject m) {
        return m != null && m.messageOwner != null && !m.isOut() && !m.isSponsored() && m.getId() != 0 && !m.isDateObject
            && !(m.messageOwner instanceof TLRPC.TL_messageService);
    }

    /**
     * The rule that hides this message, or null. Cached: the rules run once per message and edit.
     * @param where SolegramFilterEngine.WHERE_*
     */
    public static Rule check(int account, MessageObject m, int where, boolean feed) {
        if (!isActive() || !filterable(m)) {
            return null;
        }
        final long dialogId = m.getDialogId();
        if (excluded.contains(dialogId)) {
            return null;
        }
        final Verdict e = entry(account, dialogId, m.getId(), true);
        final int editDate = m.messageOwner.edit_date;
        final int slot = feed ? 1 : 0;
        final int v = version();
        if (e.editDate != editDate) {
            e.editDate = editDate;
            e.version[0] = e.version[1] = -1;
        }
        if (e.version[slot] == v) {
            return e.rule[slot];
        }
        Rule rule = null;
        evaluationIncomplete = false;
        try {
            rule = evaluate(account, m, where, feed);
        } catch (Throwable t) {
            FileLog.e(t);
        }
        e.rule[slot] = rule;
        // an expression that ran late decided nothing: the message is checked again when it is shown next
        e.version[slot] = evaluationIncomplete ? -1 : v;
        if (rule != null) {
            count(dialogId, m.getId(), rule);
        }
        return rule;
    }

    /** The rule that hides any message of an album (or a single message), or null. */
    public static Rule checkGroup(int account, List<MessageObject> messages, int where, boolean feed) {
        for (int i = 0, n = messages.size(); i < n; i++) {
            final Rule r = check(account, messages.get(i), where, feed);
            if (r != null) {
                return r;
            }
        }
        return null;
    }

    public static boolean isRevealed(int account, MessageObject m) {
        if (m == null) {
            return false;
        }
        final Verdict e = entry(account, m.getDialogId(), m.getId(), false);
        return e != null && e.revealed;
    }

    public static boolean isGroupRevealed(int account, List<MessageObject> messages) {
        for (int i = 0, n = messages.size(); i < n; i++) {
            if (isRevealed(account, messages.get(i))) {
                return true;
            }
        }
        return false;
    }

    /** The user opened a hidden message (or jumped to it): it stays shown while the app runs. */
    public static void reveal(int account, long dialogId, int messageId) {
        final Verdict e = entry(account, dialogId, messageId, true);
        e.revealed = true;
    }

    private static boolean evaluationIncomplete;

    private static Rule evaluate(int account, MessageObject m, int where, boolean feed) {
        final TLRPC.Message msg = m.messageOwner;
        final SolegramFilterEngine.Subject s = subject;
        s.reset();
        s.text = msg.message;
        s.dialogId = m.getDialogId();
        s.where = where;
        s.feed = feed;
        s.senderId = m.getFromChatId();
        if (msg.fwd_from != null && msg.fwd_from.from_id != null) {
            s.forwardId = MessageObject.getPeerId(msg.fwd_from.from_id);
            s.forwardFromChannel = msg.fwd_from.from_id instanceof TLRPC.TL_peerChannel && isBroadcast(account, msg.fwd_from.from_id.channel_id);
        }
        if (needUrls) {
            s.urls = collectUrls(msg);
            s.linkTexts = collectLinkTexts(msg);
        }
        if (needNames) {
            s.senderName = peerName(account, s.senderId);
            if (msg.fwd_from != null) {
                s.forwardName = !TextUtils.isEmpty(msg.fwd_from.from_name) ? msg.fwd_from.from_name : peerName(account, s.forwardId);
            }
        }
        final Rule rule = SolegramFilterEngine.firstMatch(active, s);
        evaluationIncomplete = rule == null && s.incomplete;
        s.reset();
        return rule;
    }

    /** A channel, not a supergroup (a chat not loaded yet counts as a channel: a forward from a group is rarer). */
    private static boolean isBroadcast(int account, long channelId) {
        final TLRPC.Chat chat = MessagesController.getInstance(account).getChat(channelId);
        return chat == null || !chat.megagroup;
    }

    /** The words that carry a link (text links, mentions by name), one per line. */
    private static String collectLinkTexts(TLRPC.Message msg) {
        if (msg.entities == null || TextUtils.isEmpty(msg.message)) {
            return null;
        }
        StringBuilder sb = null;
        final int length = msg.message.length();
        for (int i = 0, n = msg.entities.size(); i < n; i++) {
            final TLRPC.MessageEntity entity = msg.entities.get(i);
            if (entity instanceof TLRPC.TL_messageEntityTextUrl || entity instanceof TLRPC.TL_messageEntityMentionName) {
                final int start = Math.max(0, entity.offset), end = Math.min(length, entity.offset + entity.length);
                if (start < end) {
                    sb = append(sb, msg.message.substring(start, end));
                }
            }
        }
        return sb == null ? null : sb.toString();
    }

    /** Link targets the text does not show: text links, URL buttons, the link preview. */
    private static String collectUrls(TLRPC.Message msg) {
        StringBuilder sb = null;
        if (msg.entities != null) {
            for (int i = 0, n = msg.entities.size(); i < n; i++) {
                final TLRPC.MessageEntity entity = msg.entities.get(i);
                if (entity instanceof TLRPC.TL_messageEntityTextUrl && entity.url != null) {
                    sb = append(sb, entity.url);
                }
            }
        }
        if (msg.reply_markup instanceof TLRPC.TL_replyInlineMarkup) {
            for (TL_keyboard.KeyboardInlineButtonRow row : ((TLRPC.TL_replyInlineMarkup) msg.reply_markup).rows) {
                for (TL_keyboard.KeyboardInlineButton button : row.buttons) {
                    final TL_keyboard.TL_inlineButtonTypeUrl url = TLKeyboardHelper.getType(button, TL_keyboard.TL_inlineButtonTypeUrl.class);
                    if (url != null) {
                        sb = append(sb, url.url);
                    }
                    final TL_keyboard.TL_inlineButtonTypeUrlAuth auth = TLKeyboardHelper.getType(button, TL_keyboard.TL_inlineButtonTypeUrlAuth.class);
                    if (auth != null) {
                        sb = append(sb, auth.url);
                    }
                }
            }
        }
        if (msg.media instanceof TLRPC.TL_messageMediaWebPage && msg.media.webpage != null) {
            sb = append(sb, msg.media.webpage.url);
        }
        return sb == null ? null : sb.toString();
    }

    private static StringBuilder append(StringBuilder sb, String url) {
        if (TextUtils.isEmpty(url)) {
            return sb;
        }
        if (sb == null) {
            sb = new StringBuilder();
        } else {
            sb.append('\n');
        }
        return sb.append(url);
    }

    public static String peerName(int account, long peerId) {
        if (peerId == 0) {
            return null;
        }
        final MessagesController mc = MessagesController.getInstance(account);
        if (peerId > 0) {
            final TLRPC.User user = mc.getUser(peerId);
            return user == null ? null : UserObject.getUserName(user);
        }
        final TLRPC.Chat chat = mc.getChat(-peerId);
        return chat == null ? null : chat.title;
    }
    // endregion

    // region Names

    /** What the folded line and the settings call a rule. */
    public static String titleOf(Rule r) {
        if (r == null) {
            return "";
        }
        if (r.id == SolegramFilterEngine.PRESET_ADS) {
            return LocaleController.getString(R.string.SG_FilterPresetAdsShort);
        }
        if (r.id == SolegramFilterEngine.PRESET_JUNK) {
            return LocaleController.getString(R.string.SG_FilterPresetJunkShort);
        }
        if (r.id == SolegramFilterEngine.PRESET_INVITES) {
            return LocaleController.getString(R.string.SG_FilterPresetInvitesShort);
        }
        if (!TextUtils.isEmpty(r.title)) {
            return r.title;
        }
        switch (r.type) {
            case SolegramFilterEngine.TYPE_SENDER:
            case SolegramFilterEngine.TYPE_FORWARD:
                return TextUtils.isEmpty(r.peerName) ? String.valueOf(r.peerId) : r.peerName;
            case SolegramFilterEngine.TYPE_REGEX:
                return "/" + ellipsize(r.pattern, 32) + "/";
            default:
                return ellipsize(r.pattern.replace('\n', ',').replaceAll("\\s*,\\s*", ", "), 40);
        }
    }

    private static String ellipsize(String s, int max) {
        s = s == null ? "" : s.trim();
        return s.length() <= max ? s : s.substring(0, max - 1).trim() + "…";
    }

    public static String typeName(int type) {
        switch (type) {
            case SolegramFilterEngine.TYPE_REGEX:
                return LocaleController.getString(R.string.SG_FilterTypeRegexShort);
            case SolegramFilterEngine.TYPE_SENDER:
                return LocaleController.getString(R.string.SG_FilterTypeSenderShort);
            case SolegramFilterEngine.TYPE_FORWARD:
                return LocaleController.getString(R.string.SG_FilterTypeForwardShort);
            default:
                return LocaleController.getString(R.string.SG_FilterTypeWordsShort);
        }
    }

    public static String scopeName(Rule r) {
        switch (r.scope) {
            case SolegramFilterEngine.SCOPE_CHANNELS:
                return LocaleController.getString(R.string.SG_FilterScopeChannels);
            case SolegramFilterEngine.SCOPE_GROUPS:
                return LocaleController.getString(R.string.SG_FilterScopeGroups);
            case SolegramFilterEngine.SCOPE_PRIVATE:
                return LocaleController.getString(R.string.SG_FilterScopePrivate);
            case SolegramFilterEngine.SCOPE_FEED:
                return LocaleController.getString(R.string.SG_FilterScopeFeed);
            case SolegramFilterEngine.SCOPE_CHAT:
                return TextUtils.isEmpty(r.chatTitle) ? LocaleController.getString(R.string.SG_FilterScopeChat) : r.chatTitle;
            default:
                return LocaleController.getString(R.string.SG_FilterScopeAll);
        }
    }

    /** Text of an expression check result for the editor, or null when the expression is fine. */
    public static String regexProblemText(String problem) {
        if (problem == null) {
            return null;
        }
        if (problem.startsWith("syntax:")) {
            return LocaleController.formatString(R.string.SG_FilterRegexSyntax, problem.substring(7));
        }
        switch (problem) {
            case SolegramFilterEngine.REGEX_EMPTY:
                return LocaleController.getString(R.string.SG_FilterPatternEmpty);
            case SolegramFilterEngine.REGEX_TOO_LONG:
                return LocaleController.getString(R.string.SG_FilterRegexLong);
            case SolegramFilterEngine.REGEX_NESTED:
                return LocaleController.getString(R.string.SG_FilterRegexNested);
            case SolegramFilterEngine.REGEX_BACKREF:
                return LocaleController.getString(R.string.SG_FilterRegexBackref);
            case SolegramFilterEngine.REGEX_MATCHES_EMPTY:
                return LocaleController.getString(R.string.SG_FilterRegexMatchesEmpty);
            default:
                return LocaleController.getString(R.string.SG_FilterRegexSlow);
        }
    }
    // endregion

    // region Statistics

    private static long today() {
        final long now = System.currentTimeMillis();
        return (now + TimeZone.getDefault().getOffset(now)) / 86_400_000L;
    }

    private static void count(long dialogId, int messageId, Rule rule) {
        int[] range = counted.get(dialogId);
        if (range != null && messageId >= range[0] && messageId <= range[1]) {
            return;
        }
        if (range == null) {
            if (counted.size() > 1000) {
                counted.clear();
            }
            counted.put(dialogId, new int[]{messageId, messageId});
        } else {
            range[0] = Math.min(range[0], messageId);
            range[1] = Math.max(range[1], messageId);
        }
        long[] s = stats.get(rule.id);
        if (s == null) {
            stats.put(rule.id, s = new long[3]);
        }
        final long day = today();
        if (s[2] != day) {
            s[2] = day;
            s[1] = 0;
        }
        s[0]++;
        s[1]++;
        scheduleSave(5000);
    }

    public static long hitsTotal(long ruleId) {
        ensureLoaded();
        final long[] s = stats.get(ruleId);
        return s == null ? 0 : s[0];
    }

    public static long hitsToday(long ruleId) {
        ensureLoaded();
        final long[] s = stats.get(ruleId);
        return s == null || s[2] != today() ? 0 : s[1];
    }

    public static long hitsTotalAll() {
        ensureLoaded();
        long sum = 0;
        for (long[] s : stats.values()) {
            sum += s[0];
        }
        return sum;
    }

    public static long hitsTodayAll() {
        ensureLoaded();
        final long day = today();
        long sum = 0;
        for (long[] s : stats.values()) {
            if (s[2] == day) {
                sum += s[1];
            }
        }
        return sum;
    }

    public static void resetStats() {
        ensureLoaded();
        stats.clear();
        scheduleSave(300);
    }
    // endregion

    // region Storage

    private static File file() {
        return new File(ApplicationLoader.getFilesDirFixed(), FILE);
    }

    private static void load() {
        try {
            final File f = file();
            if (!f.exists()) {
                return;
            }
            final byte[] data = new byte[(int) f.length()];
            try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                int read = 0;
                while (read < data.length) {
                    final int r = in.read(data, read, data.length - read);
                    if (r < 0) {
                        break;
                    }
                    read += r;
                }
            }
            final JSONObject root = new JSONObject(new String(data, StandardCharsets.UTF_8));
            final JSONArray list = root.optJSONArray("rules");
            if (list != null) {
                for (int i = 0; i < list.length(); i++) {
                    final Rule r = ruleFromJson(list.getJSONObject(i));
                    if (r != null) {
                        rules.add(r);
                    }
                }
            }
            final JSONArray ex = root.optJSONArray("excluded");
            if (ex != null) {
                for (int i = 0; i < ex.length(); i++) {
                    excluded.add(ex.getLong(i));
                }
            }
            final JSONObject st = root.optJSONObject("stats");
            if (st != null) {
                for (Iterator<String> it = st.keys(); it.hasNext(); ) {
                    final String key = it.next();
                    final JSONArray a = st.getJSONArray(key);
                    stats.put(Long.parseLong(key), new long[]{a.getLong(0), a.getLong(1), a.getLong(2)});
                }
            }
            final JSONObject seen = root.optJSONObject("counted");
            if (seen != null) {
                for (Iterator<String> it = seen.keys(); it.hasNext(); ) {
                    final String key = it.next();
                    final JSONArray a = seen.getJSONArray(key);
                    counted.put(Long.parseLong(key), new int[]{a.getInt(0), a.getInt(1)});
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private static boolean saveScheduled;
    private static final Runnable saveRunnable = () -> {
        saveScheduled = false;
        final String json = toJson(true).toString();
        Utilities.globalQueue.postRunnable(() -> write(json));
    };

    private static void scheduleSave(long delay) {
        if (saveScheduled) {
            AndroidUtilities.cancelRunOnUIThread(saveRunnable);
        }
        saveScheduled = true;
        AndroidUtilities.runOnUIThread(saveRunnable, delay);
    }

    private static synchronized void write(String json) {
        try {
            final File f = file();
            final File tmp = new File(f.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(json.getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
            if (!tmp.renameTo(f)) {
                FileLog.e("solegram filters: cannot replace " + f);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private static JSONObject toJson(boolean full) {
        final JSONObject root = new JSONObject();
        try {
            root.put("format", FORMAT);
            root.put("version", 1);
            final JSONArray list = new JSONArray();
            for (Rule r : rules) {
                list.put(ruleToJson(r));
            }
            root.put("rules", list);
            if (full) {
                final JSONArray ex = new JSONArray();
                for (Long id : excluded) {
                    ex.put(id);
                }
                root.put("excluded", ex);
                final JSONObject st = new JSONObject();
                for (Map.Entry<Long, long[]> e : stats.entrySet()) {
                    st.put(String.valueOf(e.getKey()), new JSONArray().put(e.getValue()[0]).put(e.getValue()[1]).put(e.getValue()[2]));
                }
                root.put("stats", st);
                final JSONObject seen = new JSONObject();
                for (Map.Entry<Long, int[]> e : counted.entrySet()) {
                    seen.put(String.valueOf(e.getKey()), new JSONArray().put(e.getValue()[0]).put(e.getValue()[1]));
                }
                root.put("counted", seen);
            } else {
                final JSONObject presets = new JSONObject();
                presets.put("ads", SolegramConfig.filterAds);
                presets.put("junk", SolegramConfig.filterJunk);
                presets.put("invites", SolegramConfig.filterInvites);
                root.put("presets", presets);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return root;
    }

    private static JSONObject ruleToJson(Rule r) throws org.json.JSONException {
        final JSONObject o = new JSONObject();
        o.put("id", r.id);
        o.put("type", r.type);
        o.put("title", r.title);
        o.put("pattern", r.pattern);
        o.put("wholeWord", r.wholeWord);
        o.put("fuzzy", r.fuzzy);
        o.put("names", r.names);
        o.put("scope", r.scope);
        o.put("chatId", r.chatId);
        o.put("chatTitle", r.chatTitle);
        o.put("peerId", r.peerId);
        o.put("peerName", r.peerName);
        o.put("enabled", r.enabled);
        return o;
    }

    private static Rule ruleFromJson(JSONObject o) {
        final Rule r = new Rule();
        r.id = o.optLong("id");
        r.type = o.optInt("type");
        if (r.type < SolegramFilterEngine.TYPE_WORDS || r.type > SolegramFilterEngine.TYPE_FORWARD) {
            return null;
        }
        r.title = o.optString("title", "");
        r.pattern = o.optString("pattern", "");
        r.wholeWord = o.optBoolean("wholeWord");
        r.fuzzy = o.optBoolean("fuzzy", true);
        r.names = o.optBoolean("names");
        r.scope = Math.max(0, Math.min(SolegramFilterEngine.SCOPE_CHAT, o.optInt("scope")));
        r.chatId = o.optLong("chatId");
        r.chatTitle = o.optString("chatTitle", "");
        r.peerId = o.optLong("peerId");
        r.peerName = o.optString("peerName", "");
        r.enabled = o.optBoolean("enabled", true);
        if (r.pattern.length() > SolegramFilterEngine.MAX_PATTERN * 4) {
            return null;
        }
        SolegramFilterEngine.compile(r);
        return r;
    }

    /** The rules and presets as a small file to share (no statistics, no per-chat exceptions). */
    public static String exportJson() {
        ensureLoaded();
        try {
            return toJson(false).toString(2);
        } catch (Throwable e) {
            return toJson(false).toString();
        }
    }

    /**
     * Adds the rules of an exported file (the same rule twice is skipped); its presets are switched on.
     * Expressions are checked like typed ones: a dangerous one comes in switched off.
     * @return the number of rules added, or -1 when it is not a filters file
     */
    public static int importJson(String json) {
        ensureLoaded();
        try {
            final JSONObject root = new JSONObject(json);
            if (!FORMAT.equals(root.optString("format"))) {
                return -1;
            }
            int added = 0;
            final JSONArray list = root.optJSONArray("rules");
            if (list != null) {
                for (int i = 0; i < list.length(); i++) {
                    final Rule r = ruleFromJson(list.getJSONObject(i));
                    if (r == null || isDuplicate(r)) {
                        continue;
                    }
                    if (r.getError() != null) {
                        r.enabled = false;
                    }
                    r.id = newId();
                    rules.add(r);
                    added++;
                }
            }
            final JSONObject presets = root.optJSONObject("presets");
            final android.content.SharedPreferences.Editor editor = SolegramConfig.prefs().edit();
            boolean presetsOn = false;
            if (presets != null) {
                for (String[] p : new String[][]{{"ads", "filterAds"}, {"junk", "filterJunk"}, {"invites", "filterInvites"}}) {
                    if (presets.optBoolean(p[0])) {
                        editor.putBoolean(p[1], true);
                        presetsOn = true;
                    }
                }
            }
            if (added > 0 || presetsOn) {
                editor.putBoolean("messageFilters", true);
            }
            editor.apply();
            SolegramConfig.reload();
            changed();
            return added;
        } catch (Throwable e) {
            FileLog.e(e);
            return -1;
        }
    }

    private static boolean isDuplicate(Rule r) {
        for (Rule o : rules) {
            if (o.type == r.type && o.scope == r.scope && o.chatId == r.chatId && o.peerId == r.peerId && TextUtils.equals(o.pattern, r.pattern)) {
                return true;
            }
        }
        return false;
    }
    // endregion

    // region From a message

    /** Whether "Hide similar" makes sense for a message (not yours, not a service message). */
    public static boolean canFilterMessage(MessageObject m) {
        if (!filterable(m) || m.getId() < 0) {
            return false;
        }
        return !TextUtils.isEmpty(m.messageOwner.message) || m.messageOwner.fwd_from != null && m.messageOwner.fwd_from.from_id != null
            || m.getFromChatId() != 0;
    }

    /** First line of a message, cut at a word, as a starting point for a word rule. */
    public static String suggestWords(String text) {
        if (TextUtils.isEmpty(text)) {
            return "";
        }
        String s = text.trim();
        final int nl = s.indexOf('\n');
        if (nl > 0) {
            s = s.substring(0, nl).trim();
        }
        s = s.replace(',', ' ');
        if (s.length() > 48) {
            final int space = s.lastIndexOf(' ', 48);
            s = s.substring(0, space > 16 ? space : 48).trim();
        }
        return s;
    }
    // endregion
}
