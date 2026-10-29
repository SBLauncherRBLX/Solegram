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
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.ContactsController;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * "Search by meaning": a question in the user's words is turned into search terms ({@link SolegramMeaning}: the
 * built-in dictionary, and Gemini Nano on the phone where it exists), Telegram's own server search runs for each
 * term (all chats, plus the chat of the person the question names), and the merged results are ranked on the phone.
 * Gemini Nano may then reorder the best ones from short snippets, within two seconds and never holding them back.
 * <p>
 * What leaves the phone: the search terms go to Telegram, as in any search. The question goes to the AI service only
 * when the user taps "Refine with AI". Messages never go anywhere.
 * <p>
 * One instance per results screen. Public methods are called on the UI thread; the listener is called there too.
 */
public final class SolegramMeaningSearch {

    public static final int SOURCE_BUILTIN = 0;
    public static final int SOURCE_NANO = 1;
    public static final int SOURCE_AI = 2;

    /** Requests at a time, and all requests of one question (Telegram answers FLOOD_WAIT to a burst). */
    private static final int MAX_PARALLEL = 6;
    private static final int MAX_REQUESTS = 24;
    /** Terms searched in all chats, and in the chat of the named person. */
    private static final int GLOBAL_TERMS = 10;
    private static final int PEER_TERMS = 4;
    private static final int PEERS = 2;
    private static final int PER_REQUEST = 40;
    private static final int MAX_RESULTS = 120;
    /** Fewer than this many results inside the date range: the terms are searched once more without it. */
    private static final int RELAX_BELOW = 5;
    /** Gemini Nano reorders this many of the best results, and its answer counts only within this time. */
    private static final int RERANK_COUNT = 30;
    private static final long RERANK_BUDGET_MS = 2000;
    private static final long DAY_MS = 24 * 3600_000L;

    private static final String EXPAND_INSTRUCTION = "You help to search a messenger chat history. Turn the user's question into "
        + "search keywords. Answer with one line of JSON only: {\"terms\":[...],\"date\":\"\",\"person\":\"\"}. "
        + "terms: 6 to 12 single words that a message about this would contain: the key words of the question in other forms, "
        + "synonyms, slang, related words, and the same words in Russian and English. No question words, no pronouns. "
        + "date: YYYY-MM-DD..YYYY-MM-DD when the question names a time, else empty. Today is {today}. "
        + "person: the name of the person the chat was with, if the question names one, else empty.";
    private static final String RERANK_INSTRUCTION = "You rank messenger messages for a search. The user asks a question; "
        + "numbered messages follow. Answer only with the numbers of the messages that best answer the question, the best first, "
        + "separated by commas, at most 10 numbers. Nothing else.";

    private static final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "SolegramMeaning");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });
    // Gemini Nano blocks for seconds: its own thread, so the ranking never waits for it
    private static final ExecutorService nanoQueue = Executors.newSingleThreadExecutor(r -> new Thread(r, "SolegramMeaningNano"));

    private static volatile SolegramMeaning.Lexicon lexicon;
    /** Telegram asked to slow down until then (elapsedRealtime). */
    private static volatile long floodUntil;

    public static boolean enabled() {
        return SolegramConfig.meaningSearch;
    }

    /** Whether the Rerank setting means anything on this phone. */
    public static boolean rerankAvailable() {
        return SolegramGeminiNano.isAvailable();
    }

    /** Reads the dictionary in the background, so the first search does not wait for it. */
    public static void preload() {
        if (lexicon == null) {
            worker.execute(SolegramMeaningSearch::lexicon);
        }
    }

    private static SolegramMeaning.Lexicon lexicon() {
        SolegramMeaning.Lexicon lex = lexicon;
        if (lex == null) {
            synchronized (SolegramMeaningSearch.class) {
                lex = lexicon;
                if (lex == null) {
                    try (InputStream in = ApplicationLoader.applicationContext.getAssets().open("solegram_meaning.json")) {
                        lex = SolegramMeaning.Lexicon.parse(SolegramNet.readString(in, 1024 * 1024));
                    } catch (Throwable e) {
                        FileLog.e(e);
                        lex = SolegramMeaning.Lexicon.parse("{}");
                    }
                    lexicon = lex;
                }
            }
        }
        return lex;
    }

    /** One found message. */
    public static final class Result implements SolegramMeaning.Found {
        public final MessageObject message;
        final String text;
        final long date;
        /** The terms whose search brought it (empty: found on the phone). */
        final HashSet<String> foundBy = new HashSet<>();
        public SolegramMeaning.Match match;
        int scoredVersion = -1;
        float nanoBonus;
        /** Words to highlight, as written in the message (lower case). */
        public ArrayList<String> highlight;

        Result(MessageObject message) {
            this.message = message;
            this.text = searchableText(message);
            this.date = message.messageOwner.date * 1000L;
        }

        @Override
        public long chatId() {
            return message.getDialogId();
        }

        @Override
        public long dateMs() {
            return date;
        }

        @Override
        public SolegramMeaning.Match match() {
            return match;
        }

        public float rank() {
            return match.score + nanoBonus;
        }
    }

    public interface Listener {
        /** What is being searched for now (a copy), who understood the question, and whether Gemini Nano still works on it. */
        void onPlan(SolegramMeaning.Plan plan, int source, boolean nanoWorking, @Nullable String personChat);

        /** The results so far, best first. */
        void onResults(ArrayList<Result> results, boolean searching, boolean reranked);

        /** A note under the terms (Telegram asked to wait, a refinement failed), or null to clear it. */
        void onNotice(@Nullable String notice);
    }

    private static final class Request {
        final String query;
        @Nullable
        final TLRPC.InputPeer peer;
        final int minDate, maxDate;

        Request(String query, @Nullable TLRPC.InputPeer peer, int minDate, int maxDate) {
            this.query = query;
            this.peer = peer;
            this.minDate = minDate;
            this.maxDate = maxDate;
        }
    }

    private final int account;
    private final Listener listener;
    private final AtomicInteger generation = new AtomicInteger();
    private final ArrayList<Integer> requestIds = new ArrayList<>();
    private String question = "";

    // worker thread only
    private String asked = "";
    private SolegramMeaning.Plan plan;
    private int planVersion;
    private int source;
    private final ArrayList<TLRPC.InputPeer> peers = new ArrayList<>();
    private String personChat;
    private final LinkedHashMap<String, Result> results = new LinkedHashMap<>();
    private final ArrayDeque<Request> queue = new ArrayDeque<>();
    private int inFlight;
    private int sent;
    private boolean relaxed;
    private boolean nanoExpanding;
    private boolean rerankStarted;
    private boolean reranked;
    private final HashSet<String> requested = new HashSet<>();

    public SolegramMeaningSearch(int account, Listener listener) {
        this.account = account;
        this.listener = listener;
        preload();
    }

    // region Starting

    /** Searches for a question (what was searched before is cancelled). UI thread. */
    public void search(String text) {
        final int g = generation.incrementAndGet();
        question = text == null ? "" : text.trim();
        cancelRequests();
        final String q = question;
        worker.execute(() -> {
            final SolegramMeaning.Plan p = SolegramMeaning.expand(q, lexicon(), System.currentTimeMillis(), TimeZone.getDefault());
            AndroidUtilities.runOnUIThread(() -> {
                if (g == generation.get()) {
                    begin(g, p);
                }
            });
        });
    }

    /** Stops everything (the screen is closed). UI thread. */
    public void cancel() {
        generation.incrementAndGet();
        cancelRequests();
    }

    private void cancelRequests() {
        final ArrayList<Integer> ids;
        synchronized (requestIds) {
            ids = new ArrayList<>(requestIds);
            requestIds.clear();
        }
        for (Integer id : ids) {
            ConnectionsManager.getInstance(account).cancelRequest(id, false);
        }
    }

    /** UI thread: the chats of the named person and the messages already on the phone, then the work goes on in the background. */
    private void begin(int g, SolegramMeaning.Plan p) {
        final ArrayList<TLRPC.InputPeer> chatPeers = new ArrayList<>();
        final String chatName = resolvePeers(p, chatPeers);
        // the last message of every chat is in memory: searched on the phone at once (secret chats too, they never leave it)
        final ArrayList<TLRPC.Message> local = new ArrayList<>();
        final LongSparseArray<ArrayList<MessageObject>> last = MessagesController.getInstance(account).dialogMessage;
        // hidden chats never show up in a search
        final long[] hidden = SolegramHiddenChats.idsFor(account);
        for (int i = 0, n = Math.min(last.size(), 1500); i < n; i++) {
            final ArrayList<MessageObject> list = last.valueAt(i);
            if (list == null || list.isEmpty() || SolegramHiddenChats.contains(hidden, last.keyAt(i))) {
                continue;
            }
            final MessageObject m = list.get(0);
            if (m != null && m.messageOwner != null && m.getId() != 0 && !TextUtils.isEmpty(m.messageOwner.message) && !m.isSponsored()) {
                local.add(m.messageOwner);
            }
        }
        final boolean nano = nanoUsable();
        final String q = question;
        worker.execute(() -> {
            if (g != generation.get()) {
                return;
            }
            asked = q;
            plan = p;
            planVersion++;
            source = SOURCE_BUILTIN;
            peers.clear();
            peers.addAll(chatPeers);
            personChat = chatName;
            results.clear();
            queue.clear();
            requested.clear();
            inFlight = 0;
            sent = 0;
            relaxed = false;
            rerankStarted = false;
            reranked = false;
            nanoExpanding = nano && !TextUtils.isEmpty(q);
            notifyPlan(g);
            notifyNotice(g, null);
            final long now = System.currentTimeMillis();
            for (TLRPC.Message m : local) {
                // the text is scored first; a message object (a copy: the chat list's own must not get highlights) only for a match
                if (!plan.terms.isEmpty() && SolegramMeaning.score(plan, m.message, m.date * 1000L, false, now).terms > 0) {
                    final MessageObject copy = new MessageObject(account, m, false, true);
                    results.put(key(copy), new Result(copy));
                }
            }
            queueTerms(g, plan.terms, true);
            if (plan.terms.isEmpty() && peers.isEmpty() && !TextUtils.isEmpty(q)) {
                notifyNotice(g, LocaleController.getString(R.string.SG_MeaningNeedWords));
            }
            pump(g);
            publish(g);
            if (nanoExpanding) {
                expandWithNano(g, q);
            }
        });
    }

    private static boolean nanoUsable() {
        return SolegramGeminiNano.sdkSupported() && SolegramGeminiNano.isAvailable() && !SolegramGeminiNano.quotaExhausted()
            && SolegramSummarizer.usesNano();
    }

    /** The chats whose name is the person the question names (private chats first, the most recent first). UI thread. */
    @Nullable
    private String resolvePeers(SolegramMeaning.Plan p, ArrayList<TLRPC.InputPeer> out) {
        if (p.personStems.isEmpty()) {
            return null;
        }
        final MessagesController mc = MessagesController.getInstance(account);
        final ArrayList<TLRPC.Dialog> dialogs = mc.getAllDialogs();
        final ArrayList<Long> users = new ArrayList<>();
        final ArrayList<Long> chats = new ArrayList<>();
        for (int i = 0, n = Math.min(dialogs.size(), 2000); i < n && users.size() < PEERS; i++) {
            final long did = dialogs.get(i).id;
            if (DialogObject.isEncryptedDialog(did) || did == UserConfig.getInstance(account).getClientUserId() || SolegramHiddenChats.isHidden(account, did)) {
                continue;
            }
            final String name = dialogName(did);
            if (name != null && p.matchesPerson(name)) {
                (DialogObject.isUserDialog(did) ? users : chats).add(did);
            }
        }
        final ArrayList<Long> chosen = new ArrayList<>(users);
        for (int i = 0; i < chats.size() && chosen.size() < PEERS; i++) {
            chosen.add(chats.get(i));
        }
        String first = null;
        for (Long did : chosen) {
            final TLRPC.InputPeer peer = mc.getInputPeer(did);
            if (peer != null && !(peer instanceof TLRPC.TL_inputPeerEmpty)) {
                out.add(peer);
                if (first == null) {
                    first = dialogName(did);
                }
            }
        }
        return first;
    }

    /** A chat's name as the user sees it (the contact name, the username) for matching the person. */
    @Nullable
    private String dialogName(long did) {
        final MessagesController mc = MessagesController.getInstance(account);
        if (DialogObject.isUserDialog(did)) {
            final TLRPC.User u = mc.getUser(did);
            if (u == null) {
                return null;
            }
            final String name = ContactsController.formatName(u.first_name, u.last_name);
            return TextUtils.isEmpty(u.username) ? name : name + " " + u.username;
        }
        if (DialogObject.isChatDialog(did)) {
            final TLRPC.Chat c = mc.getChat(-did);
            return c != null ? c.title : null;
        }
        return null;
    }

    // endregion

    // region Requests (worker thread)

    private void queueTerms(int g, List<SolegramMeaning.Term> terms, boolean withPeers) {
        final int minDate = plan.hasDate() && !relaxed ? (int) ((plan.dateFrom - DAY_MS) / 1000) : 0;
        final int maxDate = plan.hasDate() && !relaxed ? (int) ((plan.dateTo + DAY_MS) / 1000) : 0;
        int global = 0;
        for (SolegramMeaning.Term t : terms) {
            if (global++ >= GLOBAL_TERMS) {
                break;
            }
            add(new Request(t.query, null, minDate, maxDate));
        }
        if (withPeers) {
            for (TLRPC.InputPeer peer : peers) {
                if (terms.isEmpty()) {
                    // "что Саша писал вчера": the chat itself, in that time
                    add(new Request("", peer, minDate, maxDate));
                }
                for (int i = 0; i < terms.size() && i < PEER_TERMS; i++) {
                    add(new Request(terms.get(i).query, peer, minDate, maxDate));
                }
            }
        }
    }

    private void add(Request r) {
        final String id = r.query + '\u0000' + (r.peer != null ? peerId(r.peer) : 0) + '\u0000' + r.minDate;
        if (requested.add(id)) {
            queue.add(r);
        }
    }

    private static long peerId(TLRPC.InputPeer p) {
        return p.user_id != 0 ? p.user_id : p.chat_id != 0 ? -p.chat_id : -p.channel_id;
    }

    private void pump(int g) {
        if (SystemClock.elapsedRealtime() < floodUntil) {
            queue.clear();
            return;
        }
        while (inFlight < MAX_PARALLEL && !queue.isEmpty() && sent < MAX_REQUESTS) {
            final Request r = queue.poll();
            inFlight++;
            sent++;
            send(g, r);
        }
        if (sent >= MAX_REQUESTS) {
            queue.clear();
        }
    }

    private void send(int g, Request r) {
        final TLObject req;
        if (r.peer == null) {
            final TLRPC.TL_messages_searchGlobal s = new TLRPC.TL_messages_searchGlobal();
            s.q = r.query;
            s.filter = new TLRPC.TL_inputMessagesFilterEmpty();
            s.min_date = r.minDate;
            s.max_date = r.maxDate;
            s.offset_peer = new TLRPC.TL_inputPeerEmpty();
            s.limit = PER_REQUEST;
            req = s;
        } else {
            final TLRPC.TL_messages_search s = new TLRPC.TL_messages_search();
            s.peer = r.peer;
            s.q = r.query;
            s.filter = new TLRPC.TL_inputMessagesFilterEmpty();
            s.min_date = r.minDate;
            s.max_date = r.maxDate;
            s.limit = PER_REQUEST;
            req = s;
        }
        final int[] holder = new int[1];
        holder[0] = ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> {
            synchronized (requestIds) {
                requestIds.remove((Integer) holder[0]);
            }
            onResponse(g, r, response, error);
        });
        synchronized (requestIds) {
            requestIds.add(holder[0]);
        }
    }

    /** Network thread. */
    private void onResponse(int g, Request r, TLObject response, TLRPC.TL_error error) {
        if (g != generation.get()) {
            return;
        }
        if (error != null || !(response instanceof TLRPC.messages_Messages)) {
            final int wait = error != null ? floodSeconds(error.text) : 0;
            if (wait > 0) {
                floodUntil = SystemClock.elapsedRealtime() + wait * 1000L;
                notifyNotice(g, LocaleController.formatString(R.string.SG_MeaningFlood, wait));
            }
            worker.execute(() -> {
                if (g != generation.get()) {
                    return;
                }
                inFlight--;
                pump(g);
                publish(g);
            });
            return;
        }
        final TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
        final LongSparseArray<TLRPC.User> users = new LongSparseArray<>();
        final LongSparseArray<TLRPC.Chat> chats = new LongSparseArray<>();
        for (TLRPC.User u : res.users) {
            users.put(u.id, u);
        }
        for (TLRPC.Chat c : res.chats) {
            chats.put(c.id, c);
        }
        final ArrayList<MessageObject> found = new ArrayList<>();
        for (TLRPC.Message m : res.messages) {
            if (m instanceof TLRPC.TL_messageEmpty || m instanceof TLRPC.TL_messageService) {
                continue;
            }
            try {
                found.add(new MessageObject(account, m, users, chats, false, true));
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
        AndroidUtilities.runOnUIThread(() -> {
            if (g != generation.get()) {
                return;
            }
            // the result rows show names and photos of these chats
            MessagesStorage.getInstance(account).putUsersAndChats(res.users, res.chats, true, true);
            MessagesController.getInstance(account).putUsers(res.users, false);
            MessagesController.getInstance(account).putChats(res.chats, false);
            // history the user cleared stays out
            final MessagesController mc = MessagesController.getInstance(account);
            final long[] hidden = SolegramHiddenChats.idsFor(account);
            for (Iterator<MessageObject> it = found.iterator(); it.hasNext(); ) {
                final MessageObject m = it.next();
                final int deletedUpTo = mc.deletedHistory.get(m.getDialogId());
                if (m.isSponsored() || m.isRestrictedMessage || deletedUpTo != 0 && m.getId() <= deletedUpTo
                    || SolegramHiddenChats.contains(hidden, m.getDialogId())) {
                    it.remove();
                }
            }
            worker.execute(() -> {
                if (g != generation.get()) {
                    return;
                }
                inFlight--;
                for (MessageObject m : found) {
                    final String k = key(m);
                    Result result = results.get(k);
                    if (result == null) {
                        results.put(k, result = new Result(m));
                    }
                    result.foundBy.add(r.query);
                }
                maybeRelax(g);
                pump(g);
                publish(g);
            });
        });
    }

    /** "FLOOD_WAIT_17" -> 17, else 0. */
    private static int floodSeconds(String text) {
        if (text == null || !text.contains("FLOOD")) {
            return 0;
        }
        try {
            return Math.max(1, Integer.parseInt(text.substring(text.lastIndexOf('_') + 1)));
        } catch (NumberFormatException e) {
            return 30;
        }
    }

    /** Few results in the date range when everything came back: the date may be remembered wrong, so once without it. */
    private void maybeRelax(int g) {
        if (relaxed || !plan.hasDate() || inFlight > 0 || !queue.isEmpty() || plan.terms.isEmpty()) {
            return;
        }
        int inDate = 0;
        for (Result r : results.values()) {
            if (r.date >= plan.dateFrom && r.date < plan.dateTo) {
                inDate++;
            }
        }
        if (inDate < RELAX_BELOW) {
            relaxed = true;
            queueTerms(g, plan.terms.subList(0, Math.min(4, plan.terms.size())), false);
        }
    }

    private static String key(MessageObject m) {
        return m.getDialogId() + "_" + m.getId();
    }

    // endregion

    // region Ranking (worker thread)

    /** Everything of a message the server may have matched: text or caption, file name, link preview. */
    static String searchableText(MessageObject m) {
        final StringBuilder sb = new StringBuilder();
        if (m.messageOwner.message != null) {
            sb.append(m.messageOwner.message);
        }
        try {
            final TLRPC.Document doc = m.getDocument();
            if (doc != null) {
                final String name = FileLoader.getDocumentFileName(doc);
                if (!TextUtils.isEmpty(name)) {
                    sb.append('\n').append(name);
                }
            }
            final TLRPC.MessageMedia media = MessageObject.getMedia(m.messageOwner);
            if (media instanceof TLRPC.TL_messageMediaWebPage && media.webpage != null) {
                if (media.webpage.title != null) {
                    sb.append('\n').append(media.webpage.title);
                }
                if (media.webpage.description != null) {
                    sb.append('\n').append(media.webpage.description.length() > 300 ? media.webpage.description.substring(0, 300) : media.webpage.description);
                }
            }
        } catch (Throwable ignore) {
        }
        return sb.toString();
    }

    private void publish(int g) {
        final long now = System.currentTimeMillis();
        final MessagesController mc = MessagesController.getInstance(account);
        final ArrayList<Result> list = new ArrayList<>(results.size());
        final long[] hidden = SolegramHiddenChats.idsFor(account);
        for (Result r : results.values()) {
            if (SolegramHiddenChats.contains(hidden, r.message.getDialogId())) {
                continue; // hidden while the search ran
            }
            if (r.scoredVersion != planVersion) {
                final boolean inChat = !plan.personStems.isEmpty() && isPersonChat(r.message.getDialogId());
                r.match = SolegramMeaning.score(plan, r.text, r.date, inChat, now);
                r.scoredVersion = planVersion;
                r.highlight = null;
            }
            // what the server found but none of the terms explains (a word inside another word) is left out
            if (r.match.terms > 0 || plan.terms.isEmpty() && r.match.inChat) {
                list.add(r);
            }
        }
        SolegramMeaning.boostConversations(list, plan);
        Collections.sort(list, (a, b) -> Float.compare(b.rank(), a.rank()));
        while (list.size() > MAX_RESULTS) {
            list.remove(list.size() - 1);
        }
        for (Result r : list) {
            if (r.highlight == null) {
                r.highlight = highlightWords(r);
            }
        }
        final boolean searching = inFlight > 0 || !queue.isEmpty() || nanoExpanding;
        final boolean wasReranked = reranked;
        AndroidUtilities.runOnUIThread(() -> {
            if (g == generation.get()) {
                listener.onResults(list, searching, wasReranked);
            }
        });
        if (!searching && !rerankStarted && list.size() >= 3 && SolegramConfig.meaningRerank && nanoUsable()) {
            rerankStarted = true;
            rerank(g, planVersion, new ArrayList<>(list.subList(0, Math.min(RERANK_COUNT, list.size()))));
        }
    }

    private final HashSet<Long> personChats = new HashSet<>();
    private int personChatsVersion = -1;

    private boolean isPersonChat(long did) {
        if (personChatsVersion != planVersion) {
            personChats.clear();
            for (TLRPC.InputPeer p : peers) {
                personChats.add(peerId(p));
            }
            personChatsVersion = planVersion;
        }
        if (personChats.contains(did)) {
            return true;
        }
        // a chat that was not among the resolved ones but carries the name (a group "Саша и Петя")
        final String name = dialogName(did);
        return name != null && plan.matchesPerson(name);
    }

    /** The found words of the message text, as written there (lower case), first found first. */
    private static ArrayList<String> highlightWords(Result r) {
        final ArrayList<String> words = new ArrayList<>();
        final String message = r.message.messageOwner.message;
        if (message == null) {
            return words;
        }
        for (int[] span : r.match.spans) {
            if (span[1] <= message.length()) {
                final String w = message.substring(span[0], span[1]).toLowerCase();
                if (!words.contains(w)) {
                    words.add(w);
                }
            }
        }
        return words;
    }

    /** Puts the found words into the message for the result row (Telegram's search cells read them). UI thread. */
    public static void applyHighlight(Result r) {
        final MessageObject m = r.message;
        if (r.highlight == null || r.highlight.isEmpty() || TextUtils.isEmpty(m.messageOwner.message)) {
            m.highlightedWords = null;
            m.messageTrimmedToHighlight = null;
            return;
        }
        if (m.highlightedWords == r.highlight) {
            return;
        }
        CharSequence text = !TextUtils.isEmpty(m.caption) ? m.caption : m.messageText;
        if (text == null) {
            text = m.messageOwner.message;
        }
        // as Telegram cuts its own search results: one line, from a little before the first found word
        CharSequence line = AndroidUtilities.replaceMultipleCharSequence("\n", text, " ");
        final int length = line.length();
        int at = line.toString().toLowerCase().indexOf(r.highlight.get(0));
        if (at < 0) {
            at = 0;
        }
        final int maxSymbols = 120;
        if (length > maxSymbols) {
            final int start = Math.max(0, at - (int) (maxSymbols * .1f));
            line = line.subSequence(start, Math.min(length, at - start + at + (int) (maxSymbols * .9f)));
        }
        m.messageTrimmedToHighlight = line;
        m.messageTrimmedToHighlightCut = true;
        m.highlightedWords = r.highlight;
    }

    // endregion

    // region Gemini Nano

    private static String today() {
        final SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        return f.format(new Date());
    }

    /** More words for the question from Gemini Nano; the search has already started with the built-in ones. */
    private void expandWithNano(int g, String q) {
        nanoQueue.execute(() -> {
            String answer = null;
            if (g == generation.get()) {
                try {
                    answer = SolegramGeminiNano.generate(EXPAND_INSTRUCTION.replace("{today}", today()), q, 160);
                } catch (Throwable e) {
                    if (!(e instanceof SolegramGeminiNano.NanoException)) {
                        FileLog.e(e);
                    }
                }
            }
            final String a = answer;
            worker.execute(() -> {
                if (g != generation.get()) {
                    return;
                }
                nanoExpanding = false;
                if (a != null && applyModelAnswer(g, a, 6) > 0) {
                    source = SOURCE_NANO;
                }
                notifyPlan(g);
                pump(g);
                publish(g);
            });
        });
    }

    /** Adds the words (and a date, if none was found) of a model's answer; returns how many terms were added. Worker thread. */
    private int applyModelAnswer(int g, String answer, int maxNew) {
        final SolegramMeaning.Lexicon lex = lexicon();
        final ArrayList<String> words = SolegramMeaning.parseModelTerms(answer);
        final SolegramMeaning.Plan p = plan.copy();
        final ArrayList<SolegramMeaning.Term> added = p.addWords(words, lex, 0.7f, Math.min(SolegramMeaning.MAX_TERMS + maxNew, p.terms.size() + maxNew));
        boolean changed = !added.isEmpty();
        if (!p.hasDate()) {
            final long[] range = SolegramMeaning.parseModelDate(answer, TimeZone.getDefault());
            // a date the model made up far in the future or long ago is not trusted
            if (range != null && range[0] < System.currentTimeMillis() && range[1] - range[0] < 400 * DAY_MS) {
                p.dateFrom = range[0];
                p.dateTo = range[1];
                p.dateLabel = LocaleController.formatDateChat(range[0] / 1000) + " – " + LocaleController.formatDateChat((range[1] - DAY_MS) / 1000);
                changed = true;
            }
        }
        if (!changed) {
            return 0;
        }
        plan = p;
        planVersion++;
        queueTerms(g, added, false);
        return added.size();
    }

    /** Gemini Nano reads short snippets of the best results and names the best ones; its answer counts only in time. */
    private void rerank(int g, int version, ArrayList<Result> top) {
        final long deadline = SystemClock.elapsedRealtime() + RERANK_BUDGET_MS;
        final StringBuilder input = new StringBuilder();
        input.append("Question: ").append(asked).append("\n\n");
        for (int i = 0; i < top.size(); i++) {
            final Result r = top.get(i);
            input.append(i + 1).append(". ").append(SolegramMeaning.snippet(r.text, r.match, 140)).append('\n');
        }
        final String in = input.toString();
        nanoQueue.execute(() -> {
            if (g != generation.get() || SystemClock.elapsedRealtime() > deadline - 300) {
                return;
            }
            String answer = null;
            try {
                answer = SolegramGeminiNano.generate(RERANK_INSTRUCTION, in, 60);
            } catch (Throwable e) {
                if (!(e instanceof SolegramGeminiNano.NanoException)) {
                    FileLog.e(e);
                }
            }
            if (answer == null || SystemClock.elapsedRealtime() > deadline) {
                return; // too late: the results stay as the phone ranked them
            }
            final ArrayList<Integer> order = SolegramMeaning.parseModelOrder(answer, top.size());
            worker.execute(() -> {
                if (g != generation.get() || version != planVersion || order.isEmpty()) {
                    return;
                }
                for (int i = 0; i < order.size(); i++) {
                    top.get(order.get(i)).nanoBonus = 1.2f * (1f - i / (float) order.size()) + 0.3f;
                }
                reranked = true;
                publish(g);
            });
        });
    }

    // endregion

    // region Changes from the user (UI thread)

    /** A term chip was removed: the results are ranked again; messages only that term brought go away. */
    public void removeTerm(String query) {
        final int g = generation.get();
        worker.execute(() -> {
            if (g != generation.get() || plan == null) {
                return;
            }
            plan = plan.withoutTerm(query);
            planVersion++;
            resetRerank();
            for (Iterator<Result> it = results.values().iterator(); it.hasNext(); ) {
                final Result r = it.next();
                if (r.foundBy.remove(query) && r.foundBy.isEmpty()) {
                    it.remove();
                }
            }
            notifyPlan(g);
            publish(g);
        });
    }

    /** The date chip was removed: ranked again, and the terms are searched once without the date. */
    public void removeDate() {
        final int g = generation.get();
        worker.execute(() -> {
            if (g != generation.get() || plan == null || !plan.hasDate()) {
                return;
            }
            plan = plan.withoutDate();
            planVersion++;
            resetRerank();
            relaxed = true;
            queueTerms(g, plan.terms, false);
            notifyPlan(g);
            pump(g);
            publish(g);
        });
    }

    /** The person chip was removed. */
    public void removePerson() {
        final int g = generation.get();
        worker.execute(() -> {
            if (g != generation.get() || plan == null) {
                return;
            }
            plan = plan.withoutPerson();
            planVersion++;
            resetRerank();
            peers.clear();
            personChat = null;
            notifyPlan(g);
            publish(g);
        });
    }

    /** A word the user added. */
    public void addWord(String word) {
        final int g = generation.get();
        worker.execute(() -> {
            if (g != generation.get() || plan == null) {
                return;
            }
            final SolegramMeaning.Plan p = plan.copy();
            final ArrayList<SolegramMeaning.Term> added = p.addWords(Collections.singletonList(word), lexicon(), 1f, p.terms.size() + 1);
            if (added.isEmpty()) {
                return;
            }
            plan = p;
            planVersion++;
            resetRerank();
            queueTerms(g, added, true);
            notifyPlan(g);
            pump(g);
            publish(g);
        });
    }

    private void resetRerank() {
        rerankStarted = false;
        reranked = false;
        for (Result r : results.values()) {
            r.nanoBonus = 0;
        }
    }

    /** "Refine with AI" can do something with the user's AI choice (not "on device only", a set-up own service). */
    public static boolean canRefineWithAi() {
        if (SolegramConfig.aiProvider == SolegramSummarizer.PROVIDER_NANO) {
            return false;
        }
        return SolegramConfig.aiProvider != SolegramSummarizer.PROVIDER_OWN || !TextUtils.isEmpty(SolegramConfig.aiBaseUrl);
    }

    /**
     * Sends the question (only the question) to the AI the user chose for summaries, asking first when the free service
     * has not been agreed to. UI thread; {@code done} gets whether new words came.
     */
    public void refineWithAi(org.telegram.messenger.Utilities.Callback<Boolean> done) {
        final int g = generation.get();
        final String q = question;
        if (TextUtils.isEmpty(q)) {
            done.run(false);
            return;
        }
        final String instruction = EXPAND_INSTRUCTION.replace("{today}", today());
        SolegramSummarizer.compose(instruction, q, q, LocaleController.getInstance().getCurrentLocale().getLanguage(), 160,
            true, R.string.SG_MeaningSearch, R.string.SG_MeaningConsent, (answer, code) -> {
                if (g != generation.get()) {
                    return;
                }
                if (answer == null) {
                    if (code != SolegramSummarizer.COMPOSE_NEEDS_CONSENT) {
                        listener.onNotice(LocaleController.getString(R.string.SG_MeaningRefineFailed));
                    }
                    done.run(false);
                    return;
                }
                worker.execute(() -> {
                    if (g != generation.get()) {
                        return;
                    }
                    final int added = applyModelAnswer(g, answer, 6);
                    if (added > 0) {
                        source = code == SolegramSummarizer.SOURCE_NANO ? SOURCE_NANO : SOURCE_AI;
                        resetRerank();
                    }
                    notifyPlan(g);
                    pump(g);
                    publish(g);
                    AndroidUtilities.runOnUIThread(() -> {
                        if (g == generation.get()) {
                            if (added == 0) {
                                listener.onNotice(LocaleController.getString(R.string.SG_MeaningRefineNothing));
                            }
                            done.run(added > 0);
                        }
                    });
                });
            });
    }

    // endregion

    private void notifyPlan(int g) {
        final SolegramMeaning.Plan copy = plan.copy();
        final int src = source;
        final boolean nano = nanoExpanding;
        final String chat = personChat;
        AndroidUtilities.runOnUIThread(() -> {
            if (g == generation.get()) {
                listener.onPlan(copy, src, nano, chat);
            }
        });
    }

    private void notifyNotice(int g, @Nullable String notice) {
        AndroidUtilities.runOnUIThread(() -> {
            if (g == generation.get()) {
                listener.onNotice(notice);
            }
        });
    }
}
