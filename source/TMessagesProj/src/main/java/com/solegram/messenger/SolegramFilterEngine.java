/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The matching part of the message filters, free of Android and Telegram classes so it can be tested
 * on its own. Rules are compiled once; a message becomes a {@link Subject} once and is checked against
 * every rule, the text normalised at most once per kind.
 */
public final class SolegramFilterEngine {

    private SolegramFilterEngine() {
    }

    public static final int TYPE_WORDS = 0;
    public static final int TYPE_REGEX = 1;
    public static final int TYPE_SENDER = 2;
    public static final int TYPE_FORWARD = 3;
    /** Built-in presets; which one is told by the (negative) rule id. */
    public static final int TYPE_PRESET = 9;

    public static final int SCOPE_ALL = 0;
    public static final int SCOPE_CHANNELS = 1;
    public static final int SCOPE_GROUPS = 2;
    public static final int SCOPE_PRIVATE = 3;
    public static final int SCOPE_FEED = 4;
    public static final int SCOPE_CHAT = 5;

    public static final int WHERE_CHANNEL = 1;
    public static final int WHERE_GROUP = 2;
    public static final int WHERE_PRIVATE = 3;

    public static final long PRESET_ADS = -1;
    public static final long PRESET_JUNK = -2;
    public static final long PRESET_INVITES = -3;

    /** Longest text looked at: Telegram messages are at most 4096 characters. */
    public static final int MAX_TEXT = 4096;
    public static final int MAX_PATTERN = 1000;
    /** How long the screen waits for the regular expressions of one message. */
    static final long REGEX_WAIT_MS = 40;
    /** A rule that runs out of time this many times is put aside until the rules change. */
    static final int MAX_TIMEOUTS = 3;

    public static final class Rule {
        public long id;
        public int type;
        public String title = "";
        public String pattern = "";
        public boolean wholeWord;
        /** Latin look-alikes count as Cyrillic letters (and back), so "рeклaмa" typed with Latin letters still matches. */
        public boolean fuzzy = true;
        /** Also look at the sender name and the forward source name. */
        public boolean names;
        public int scope;
        public long chatId;
        public String chatTitle = "";
        /** Sender or forward source for TYPE_SENDER / TYPE_FORWARD (user id, or -chat id). */
        public long peerId;
        public String peerName = "";
        public boolean enabled = true;
        /** The ads preset only: weak signals together count too (more ads found, more mistakes). */
        public boolean strict;

        String[] words;
        Pattern regex;
        String error;
        volatile int timeouts;

        public boolean isPreset() {
            return id < 0;
        }

        /** Why the rule cannot work (a bad expression), or null. */
        public String getError() {
            return error;
        }

        /** Put aside for this session: its expression took too long. */
        public boolean isSuspended() {
            return timeouts >= MAX_TIMEOUTS;
        }

        public Rule copy() {
            final Rule r = new Rule();
            r.id = id;
            r.type = type;
            r.title = title;
            r.pattern = pattern;
            r.wholeWord = wholeWord;
            r.fuzzy = fuzzy;
            r.names = names;
            r.scope = scope;
            r.chatId = chatId;
            r.chatTitle = chatTitle;
            r.peerId = peerId;
            r.peerName = peerName;
            r.enabled = enabled;
            r.strict = strict;
            return r;
        }
    }

    /** What is checked about one message. Reused for every message (one thread), see {@link #reset()}. */
    public static final class Subject {
        public CharSequence text;
        /** Link targets of text links and buttons, one per line. */
        public String urls;
        public long dialogId;
        public int where;
        public boolean feed;
        public long senderId;
        public long forwardId;
        /** Forwarded from a channel: its ad marks count outside channels too. */
        public boolean forwardFromChannel;
        /** The words that carry a link (text links, mentions by name), one per line: a footer link is no mark. */
        public String linkTexts;
        /** Filled only when a rule looks at names. */
        public String senderName, forwardName;
        /** Set by {@link #firstMatch}: an expression ran out of time, so "no match" is not final. */
        public boolean incomplete;

        private String clipped, lines, flat, flatFolded, cased;

        public void reset() {
            text = null;
            urls = null;
            dialogId = 0;
            where = 0;
            feed = false;
            senderId = 0;
            forwardId = 0;
            forwardFromChannel = false;
            linkTexts = null;
            senderName = null;
            forwardName = null;
            incomplete = false;
            clipped = lines = flat = flatFolded = cased = null;
        }

        /** The text as is, at most {@link #MAX_TEXT} characters. */
        String clipped() {
            if (clipped == null) {
                final CharSequence t = text == null ? "" : text;
                clipped = (t.length() > MAX_TEXT ? t.subSequence(0, MAX_TEXT) : t).toString();
            }
            return clipped;
        }

        /** Lower case, invisible characters and stacked accents dropped, line breaks kept (for the presets). */
        String lines() {
            if (lines == null) {
                lines = normalize(clipped(), false, true);
            }
            return lines;
        }

        /** As {@link #lines()} but in the original case (erid tokens are told from words by their capitals). */
        String cased() {
            if (cased == null) {
                cased = normalize(clipped(), false, true, false);
            }
            return cased;
        }

        /** As {@link #lines()}, all white space one space (for words and phrases). */
        String flat(boolean fold) {
            if (fold) {
                if (flatFolded == null) {
                    flatFolded = normalize(clipped(), true, false);
                }
                return flatFolded;
            }
            if (flat == null) {
                flat = normalize(clipped(), false, false);
            }
            return flat;
        }
    }

    // region Compiling

    /** Prepares a rule for matching; afterwards {@link Rule#getError()} tells whether it can work. */
    public static void compile(Rule r) {
        r.words = null;
        r.regex = null;
        r.error = null;
        r.timeouts = 0;
        if (r.type == TYPE_WORDS) {
            r.words = splitWords(r.pattern, r.fuzzy);
            if (r.words.length == 0) {
                r.error = "empty";
            }
        } else if (r.type == TYPE_REGEX) {
            final String problem = checkRegex(r.pattern);
            if (problem != null) {
                r.error = problem;
            } else {
                r.regex = Pattern.compile(r.pattern, REGEX_FLAGS);
            }
        } else if (r.type == TYPE_SENDER || r.type == TYPE_FORWARD) {
            if (r.peerId == 0) {
                r.error = "empty";
            }
        }
    }

    // Android's (ICU) \w and \b already know every alphabet; the JVM (tests) needs the flag, which Android lacks
    static final int REGEX_FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.MULTILINE
        | (String.valueOf(System.getProperty("java.vendor")).contains("Android") ? 0 : Pattern.UNICODE_CHARACTER_CLASS);

    /** Words and phrases separated by commas or lines, normalised like the text they are looked for in. */
    public static String[] splitWords(String pattern, boolean fold) {
        final ArrayList<String> list = new ArrayList<>();
        if (pattern != null) {
            for (String part : pattern.split("[,\\n]")) {
                final String w = normalize(part, fold, false).trim();
                if (!w.isEmpty() && !list.contains(w)) {
                    list.add(w);
                }
            }
        }
        return list.toArray(new String[0]);
    }

    public static final String REGEX_EMPTY = "empty";
    public static final String REGEX_TOO_LONG = "long";
    public static final String REGEX_NESTED = "nested";
    public static final String REGEX_BACKREF = "backref";
    public static final String REGEX_MATCHES_EMPTY = "matchesEmpty";

    /**
     * Whether an expression may be used: it compiles, is not too long, does not match an empty text (it would
     * hide everything) and has no shape known to take exponential time - repeats inside repeats like (a+)+ and
     * back references. Anything that slips through is still stopped at run time, see {@link #firstMatch}.
     * @return null when fine, else REGEX_* or the syntax error ("syntax:" + description)
     */
    public static String checkRegex(String pattern) {
        if (pattern == null || pattern.trim().isEmpty()) {
            return REGEX_EMPTY;
        }
        if (pattern.length() > MAX_PATTERN) {
            return REGEX_TOO_LONG;
        }
        final Pattern p;
        try {
            p = Pattern.compile(pattern, REGEX_FLAGS);
        } catch (PatternSyntaxException e) {
            return "syntax:" + e.getDescription();
        } catch (Throwable e) {
            return "syntax:" + e.getMessage();
        }
        final String shape = checkShape(pattern);
        if (shape != null) {
            return shape;
        }
        if (p.matcher("").find()) {
            return REGEX_MATCHES_EMPTY;
        }
        return null;
    }

    /** Star height and back references, read from the pattern text (classes and escapes skipped). */
    static String checkShape(String p) {
        // per open group: whether it holds a repeat without an upper bound
        final ArrayList<boolean[]> stack = new ArrayList<>();
        stack.add(new boolean[1]);
        boolean lastGroupUnbounded = false;
        boolean lastWasGroup = false;
        final int n = p.length();
        for (int i = 0; i < n; i++) {
            char c = p.charAt(i);
            if (c == '\\') {
                if (i + 1 < n) {
                    final char e = p.charAt(i + 1);
                    if (e >= '1' && e <= '9' || e == 'k') {
                        return REGEX_BACKREF;
                    }
                    if (e == 'Q') {
                        final int end = p.indexOf("\\E", i + 2);
                        i = end < 0 ? n : end + 1;
                        lastWasGroup = false;
                        continue;
                    }
                }
                i++;
                lastWasGroup = false;
                continue;
            }
            if (c == '[') {
                i = skipClass(p, i);
                lastWasGroup = false;
                continue;
            }
            if (c == '(') {
                stack.add(new boolean[1]);
                lastWasGroup = false;
                continue;
            }
            if (c == ')') {
                if (stack.size() > 1) {
                    final boolean[] inner = stack.remove(stack.size() - 1);
                    lastGroupUnbounded = inner[0];
                    // what the group holds is held by its parent too
                    stack.get(stack.size() - 1)[0] |= inner[0];
                    lastWasGroup = true;
                } else {
                    lastWasGroup = false;
                }
                continue;
            }
            final int unbounded = repeatAt(p, i);
            if (unbounded != 0) {
                if (lastWasGroup && lastGroupUnbounded && unbounded > 0) {
                    return REGEX_NESTED;
                }
                if (unbounded > 0) {
                    stack.get(stack.size() - 1)[0] = true;
                }
                if (c == '{') {
                    i = p.indexOf('}', i);
                    if (i < 0) {
                        break;
                    }
                }
                // a lazy or possessive mark belongs to this repeat
                if (i + 1 < n && (p.charAt(i + 1) == '?' || p.charAt(i + 1) == '+')) {
                    i++;
                }
                lastWasGroup = false;
                continue;
            }
            lastWasGroup = false;
        }
        return null;
    }

    /** 1 = a repeat without a (small) upper bound starts at i, -1 = a bounded one, 0 = none. */
    private static int repeatAt(String p, int i) {
        final char c = p.charAt(i);
        if (c == '*' || c == '+') {
            return 1;
        }
        if (c == '?') {
            return -1;
        }
        if (c == '{') {
            final int end = p.indexOf('}', i);
            if (end < 0) {
                return 0;
            }
            final String body = p.substring(i + 1, end);
            if (!body.matches("\\d+(,\\d*)?")) {
                return 0;
            }
            final int comma = body.indexOf(',');
            final String max = comma < 0 ? body : body.substring(comma + 1);
            // {2,} and wide ranges like {1,100} backtrack as much as + does; a few repeats like {3} only polynomially
            return max.isEmpty() || max.length() > 2 || Integer.parseInt(max) > 5 ? 1 : -1;
        }
        return 0;
    }

    private static int skipClass(String p, int start) {
        int i = start + 1;
        if (i < p.length() && p.charAt(i) == '^') {
            i++;
        }
        if (i < p.length() && p.charAt(i) == ']') {
            i++;
        }
        int depth = 1;
        for (; i < p.length(); i++) {
            final char c = p.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == '[') {
                depth++;
            } else if (c == ']') {
                if (--depth == 0) {
                    return i;
                }
            }
        }
        return p.length();
    }

    /**
     * Runs an expression on texts that make bad expressions slow (long runs of one character, mixed runs).
     * Meant for a background thread: the caller gives up waiting after its own time limit.
     * @return true when every text was done within the budget
     */
    public static boolean stressTest(Pattern p, long budgetNanos) {
        final long deadline = System.nanoTime() + budgetNanos;
        final String[] units = {"a", "1", " ", "аб", "ab", "a1 ", "-", ".", "a.", "\n", "aaaa1"};
        for (String unit : units) {
            final StringBuilder sb = new StringBuilder(MAX_TEXT);
            while (sb.length() + unit.length() < 2000) {
                sb.append(unit);
            }
            sb.append('\u0001');
            try {
                p.matcher(sb).find();
            } catch (Throwable e) {
                return false; // StackOverflowError: as bad as slow
            }
            if (System.nanoTime() > deadline) {
                return false;
            }
        }
        return true;
    }
    // endregion

    // region Normalising

    /**
     * Lower case, ё as е, invisible characters and combining accents dropped, white space runs as one space
     * (line breaks kept when {@code keepLines}). With {@code fold} letters that look alike in Latin and
     * Cyrillic become one letter, so text typed with mixed alphabets still matches.
     */
    public static String normalize(CharSequence s, boolean fold, boolean keepLines) {
        return normalize(s, fold, keepLines, true);
    }

    static String normalize(CharSequence s, boolean fold, boolean keepLines, boolean lower) {
        if (s == null) {
            return "";
        }
        final int n = Math.min(s.length(), MAX_TEXT);
        final StringBuilder sb = new StringBuilder(n);
        boolean space = false;
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (isInvisible(c) || isCombiningMark(c)) {
                continue;
            }
            if (c == '\n' && keepLines) {
                // trailing spaces of a line go
                int len = sb.length();
                while (len > 0 && sb.charAt(len - 1) == ' ') {
                    len--;
                }
                sb.setLength(len);
                if (len > 0 && sb.charAt(len - 1) != '\n') {
                    sb.append('\n');
                }
                space = false;
                continue;
            }
            if (Character.isWhitespace(c) || c == ' ' || c == ' ' || c == ' ') {
                space = sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n';
                continue;
            }
            if (space) {
                sb.append(' ');
                space = false;
            }
            if (lower) {
                c = Character.toLowerCase(c);
                if (c == 'ё') {
                    c = 'е';
                }
            }
            if (fold) {
                c = fold(c);
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private static char fold(char c) {
        switch (c) {
            case 'а': case 'α': return 'a';
            case 'в': return 'b';
            case 'с': return 'c';
            case 'ԁ': return 'd';
            case 'е': return 'e';
            case 'н': return 'h';
            case 'і': case 'ι': return 'i';
            case 'ј': return 'j';
            case 'к': case 'κ': return 'k';
            case 'ӏ': return 'l';
            case 'м': return 'm';
            case 'о': case 'ο': return 'o';
            case 'р': case 'ρ': return 'p';
            case 'ԛ': return 'q';
            case 'ѕ': return 's';
            case 'т': case 'τ': return 't';
            case 'ν': return 'v';
            case 'ԝ': return 'w';
            case 'х': case 'χ': return 'x';
            case 'у': return 'y';
            default: return c;
        }
    }

    /** Zero width and filler characters spammers use to break up words or make empty-looking text. */
    static boolean isInvisible(char c) {
        switch (c) {
            case '­': case '͏': case 'ᅟ': case 'ᅠ': case '᠎': case '​':
            case '⁠': case '⁡': case '⁢': case '⁣': case '⁤':
            case 'ㅤ': case '﻿': case 'ﾠ':
                return true;
            default:
                return false;
        }
    }

    /** Generic combining accents (not the vowel signs of Indic scripts): what zalgo text is made of. */
    static boolean isCombiningMark(char c) {
        return c >= '̀' && c <= 'ͯ' || c >= '᪰' && c <= '᫿' || c >= '᷀' && c <= '᷿'
            || c >= '⃐' && c <= '⃿' || c >= '︠' && c <= '︯';
    }
    // endregion

    // region Matching

    public static boolean inScope(Rule r, Subject s) {
        switch (r.scope) {
            case SCOPE_CHANNELS:
                return s.where == WHERE_CHANNEL;
            case SCOPE_GROUPS:
                return s.where == WHERE_GROUP && !s.feed;
            case SCOPE_PRIVATE:
                return s.where == WHERE_PRIVATE && !s.feed;
            case SCOPE_FEED:
                return s.feed;
            case SCOPE_CHAT:
                return r.chatId != 0 && s.dialogId == r.chatId;
            default:
                return true;
        }
    }

    /** Whether a rule wants the sender and forward source names in the subject. */
    public static boolean needsNames(Rule r) {
        return r.names && (r.type == TYPE_WORDS || r.type == TYPE_REGEX);
    }

    /**
     * Every rule but regular expressions, which go through {@link #firstMatch} so they run off the calling thread.
     */
    public static boolean matchesDirect(Rule r, Subject s) {
        if (!r.enabled || r.error != null || !inScope(r, s)) {
            return false;
        }
        switch (r.type) {
            case TYPE_SENDER:
                return s.senderId == r.peerId;
            case TYPE_FORWARD:
                return s.forwardId != 0 && s.forwardId == r.peerId;
            case TYPE_PRESET:
                return matchesPreset(r, s);
            case TYPE_WORDS:
                if (containsWords(r, s.flat(r.fuzzy))) {
                    return true;
                }
                return r.names && (containsWords(r, normalize(s.senderName, r.fuzzy, false)) || containsWords(r, normalize(s.forwardName, r.fuzzy, false)));
            default:
                return false;
        }
    }

    static boolean containsWords(Rule r, String text) {
        if (text == null || text.isEmpty() || r.words == null) {
            return false;
        }
        for (String w : r.words) {
            if (r.wholeWord ? containsWholeWord(text, w) : text.contains(w)) {
                return true;
            }
        }
        return false;
    }

    static boolean containsWholeWord(String text, String word) {
        int from = 0;
        while (true) {
            final int at = text.indexOf(word, from);
            if (at < 0) {
                return false;
            }
            final int end = at + word.length();
            if ((at == 0 || !isWordChar(text.charAt(at - 1))) && (end >= text.length() || !isWordChar(text.charAt(end)))) {
                return true;
            }
            from = at + 1;
        }
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    /**
     * The first rule that matches, or null. Regular expressions of the user run on a worker thread that is
     * waited for at most {@link #REGEX_WAIT_MS}; one that overruns is put aside, so a bad expression costs
     * the screen a few frames once instead of freezing it.
     */
    public static Rule firstMatch(List<Rule> rules, Subject s) {
        ArrayList<Rule> regexRules = null;
        for (int i = 0, n = rules.size(); i < n; i++) {
            final Rule r = rules.get(i);
            if (r.type == TYPE_REGEX) {
                if (r.enabled && r.error == null && r.regex != null && !r.isSuspended() && inScope(r, s)) {
                    if (regexRules == null) {
                        regexRules = new ArrayList<>(4);
                    }
                    regexRules.add(r);
                }
            } else if (matchesDirect(r, s)) {
                return r;
            }
        }
        return regexRules == null ? null : runRegex(regexRules, s);
    }

    private static ExecutorService regexThread;
    private static Future<?> overrunTask;

    private static synchronized ExecutorService regexThread() {
        // a thread still stuck in an expression is left to finish on its own; the next ones get a fresh one
        if (regexThread == null || overrunTask != null && !overrunTask.isDone()) {
            regexThread = Executors.newSingleThreadExecutor(r -> {
                final Thread t = new Thread(r, "SolegramFilters");
                t.setDaemon(true);
                t.setPriority(Thread.NORM_PRIORITY);
                return t;
            });
            overrunTask = null;
        }
        return regexThread;
    }

    private static Rule runRegex(ArrayList<Rule> rules, Subject s) {
        final String text = s.clipped();
        final String sender = s.senderName, forward = s.forwardName;
        final Rule[] found = new Rule[1];
        final Rule[] running = new Rule[1];
        final Future<?> task;
        try {
            task = regexThread().submit(() -> {
                for (Rule r : rules) {
                    synchronized (running) {
                        running[0] = r;
                    }
                    // the rule may be recompiled meanwhile when this task overran: work on what it had
                    final Pattern p = r.regex;
                    if (p != null && (p.matcher(text).find() || r.names && (sender != null && p.matcher(sender).find() || forward != null && p.matcher(forward).find()))) {
                        synchronized (running) {
                            found[0] = r;
                            running[0] = null;
                        }
                        return;
                    }
                }
                synchronized (running) {
                    running[0] = null;
                }
            });
        } catch (Throwable e) {
            return null;
        }
        try {
            task.get(REGEX_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            synchronized (running) {
                if (running[0] != null) {
                    // a busy phone (GC, cold start) can make a fine expression late once: put aside only after a few
                    running[0].timeouts++;
                }
                if (found[0] == null) {
                    // the rules after the late one were not run: the caller must not keep this "no match"
                    s.incomplete = true;
                }
            }
            synchronized (SolegramFilterEngine.class) {
                overrunTask = task;
            }
        } catch (java.util.concurrent.ExecutionException e) {
            // StackOverflowError of a deep expression on a long text: no match, and the rule is put aside too
            synchronized (running) {
                if (running[0] != null) {
                    running[0].timeouts = MAX_TIMEOUTS;
                }
            }
        } catch (Throwable ignore) {
        }
        synchronized (running) {
            return found[0];
        }
    }
    // endregion

    // region Presets

    // Ads: the marks the Russian advertising law asks for (erid, "Реклама." with the advertiser) and the usual
    // hashtags and phrases. A strong mark hides a post on its own; what ordinary posts say too ("по вопросам
    // рекламы", "рекламный пост", a promo code, a link) counts only in the strict mode, and only several together.

    public static final int AD_NONE = 0;
    /** erid token in the text */
    public static final int AD_ERID = 1;
    /** erid parameter in a link, a button or the link preview */
    public static final int AD_ERID_URL = 2;
    /** "Реклама." with the advertiser (ООО, ИП, ИНН...), or alone on the last line */
    public static final int AD_LABEL = 3;
    /** #реклама, #ad... */
    public static final int AD_HASHTAG = 4;
    /** "на правах рекламы", "Партнёрский пост", "О рекламодателе" */
    public static final int AD_PHRASE = 5;
    /** "Спонсор поста" / "При поддержке" with the advertiser */
    public static final int AD_SPONSOR = 6;
    /** weak signals together, strict mode only */
    public static final int AD_STRICT = 9;

    private static final String WORD_END = "(?![\\p{L}\\p{N}_])";
    private static final String WORD_START = "(?<![\\p{L}\\p{N}_])";
    /** Where a mark stands: the start of a line (after bullets or emoji), after the end of a sentence or a separator. */
    private static final String AT = "(?:^|[.!?…|•·;)]\\s?|\\s[—–-]\\s?)[^\\p{L}\\p{N}\\n]{0,12}";
    /** "реклама", also typed with Latin look-alikes */
    private static final String REKLAMA = "[рp][еe][кk]л[аa][мm][аa]";
    /** A tax or registration number, or an erid token, written out (lower case). */
    private static final String ID_NUMBER = WORD_START + "(?:(?:инн|огрн|огрнип)\\s*[:№]?\\s*\\d{10,15}|erid\\s*[:=—–-]?\\s*[0-9a-z]{6,})";
    /** The advertiser named the way the law asks: a legal form, or a number as above. */
    private static final String LEGAL = "(?:" + WORD_START + "(?:ооо|оао|зао|пао|ао|ип|ано|нко|мкк|мфк|тоо|инн|огрн|огрнип|самозанят\\p{L}*"
        + "|индивидуальн\\p{L}* предпринимател|общество с ограниченной|llc|ltd|gmbh)" + WORD_END + "|" + ID_NUMBER + ")";

    /** erid and its token, run on the text in its own case. */
    static final Pattern ERID = Pattern.compile("(?<![A-Za-z0-9])[eEеЕ][rR][iI][dD][ \\t]*[:=：—–-]?\\s*([0-9A-Za-z]{6,40})(?![0-9A-Za-z])");
    /** erid as a link parameter, also encoded inside a redirect link. */
    static final Pattern ERID_URL = Pattern.compile("(?:[?&#;]|%3f|%26)erid(?:=|%3d)[0-9a-z]", Pattern.CASE_INSENSITIVE);
    /** Groups: 1 the label with the advertiser, 2 a hashtag, 3 a phrase, 4 a sponsor with the advertiser. */
    static final Pattern ADS = Pattern.compile(
        // "Реклама. ООО «Ромашка»", "Реклама | ИП Иванов", "Реклама. Рекламодатель: ООО ..."
        "(" + AT + REKLAMA + WORD_END + "\\s*[.|/]\\s*(?:рекламодатель\\s*[:—–-]?\\s*)?" + LEGAL
            // "Реклама: ООО «Ромашка»", "Реклама, ИП Иванов И.И.": the name right after ("Реклама: ИП или ООО?" is a question)
            + "|" + AT + REKLAMA + WORD_END + "\\s*[,:]\\s*" + LEGAL + "\\s*(?:[«\"„“]|\\p{L}+\\s+\\p{L}\\.)"
            // "Реклама ООО «Ромашка» ИНН 7701234567", "Реклама: ИП Иванов И.И., ИНН ...": with a number close by
            + "|" + AT + REKLAMA + WORD_END + "[^#]{0,160}?" + ID_NUMBER
            + "|" + AT + "рекламодатель\\s*[:—–-]\\s*" + LEGAL + ")"
        + "|((?<![\\p{L}\\p{N}_/&=#@])#(?:" + REKLAMA + "|ad|ads|advert|advertising|sponsored|промо|партнерскийпост"
            + "|партнерскийматериал|рекламныйпост|нативнаяреклама|партнерскаяреклама)" + WORD_END + ")"
        + "|((?<!не\\s)на правах рекламы"
            + "|" + AT + "(?:партнерск(?:ий|ая|ое) (?:пост|материал|публикация|контент)|(?:информация )?об? рекламодателе"
            + "|sponsored post|paid partnership|paid promotion)" + WORD_END + ")"
        + "|(" + AT + "(?:спонсор (?:поста|публикации|выпуска|подборки)|при поддержке|sponsored by)" + WORD_END
            + "[^#]{0,100}?" + LEGAL + ")",
        Pattern.MULTILINE);
    /** The label alone on a line: "Реклама", "Реклама.", "#реклама", "📢 Реклама". */
    static final Pattern LABEL_LINE = Pattern.compile(
        "^[^\\p{L}\\p{N}\\n]{0,12}(" + REKLAMA + "|advertisement|sponsored)([^\\p{L}\\p{N}\\n]{0,8})$", Pattern.MULTILINE);
    static final Pattern REKLAMA_WORD = Pattern.compile(WORD_START + REKLAMA + WORD_END);
    // weak signals, strict mode only
    static final Pattern WEAK_LABEL = Pattern.compile(WORD_START + "(?:" + REKLAMA + "|рекламн(?:ый|ая|ое) (?:пост|интеграция|публикация)"
        + "|#интеграция|#спонсор|#партнерство|нативная интеграция)" + WORD_END);
    static final Pattern UTM = Pattern.compile("[?&]utm_(?:source|medium|campaign|content)=", Pattern.CASE_INSENSITIVE);
    static final Pattern CALL = Pattern.compile(WORD_START + "(?:переходи(?:те)?|жми(?:те)?|по ссылке|подписывай(?:ся|тесь)|подпиши(?:сь|тесь)|регистрируй(?:ся|тесь)"
        + "|успей(?:те)?|забирай(?:те)?|оставь(?:те)? заявку|оформи(?:те)?|скачивай(?:те)?|click the link|sign up)" + WORD_END);
    static final Pattern OFFER = Pattern.compile(WORD_START + "(?:промокод\\p{L}*|скидк\\p{L}*|кешбэк\\p{L}*|кэшбэк\\p{L}*|бесплатн\\p{L}*|promo code|discount)" + WORD_END);
    static final Pattern LINK = Pattern.compile("https?://|t\\.me/|(?<![\\p{L}\\p{N}_])@[a-z][a-z0-9_]{3,}", Pattern.CASE_INSENSITIVE);
    static final Pattern INVITE = Pattern.compile(
        "(?:t|telegram)\\.(?:me|dog)/(?:\\+|%2b|joinchat/)[a-z0-9_-]{5,}|tg://join\\?invite=", Pattern.CASE_INSENSITIVE);

    public static boolean presetInScope(long id, Subject s) {
        if (id == PRESET_ADS) {
            // a channel's ad forwarded to a group (or shown in its discussion group) is still that ad
            return s.where == WHERE_CHANNEL || s.where == WHERE_GROUP && s.forwardFromChannel;
        }
        if (id == PRESET_INVITES) {
            return s.where == WHERE_GROUP && !s.feed;
        }
        return true;
    }

    static boolean matchesPreset(Rule r, Subject s) {
        if (!presetInScope(r.id, s)) {
            return false;
        }
        if (r.id == PRESET_ADS) {
            return adSignal(s, r.strict) != AD_NONE;
        }
        if (r.id == PRESET_INVITES) {
            return INVITE.matcher(s.clipped()).find() || s.urls != null && INVITE.matcher(s.urls).find();
        }
        if (r.id == PRESET_JUNK) {
            return isJunk(s.clipped());
        }
        return false;
    }

    /** Why a message counts as an ad (AD_*), AD_NONE when it does not. Scope is not checked here. */
    public static int adSignal(Subject s, boolean strict) {
        if (s.urls != null && ERID_URL.matcher(s.urls).find()) {
            return AD_ERID_URL;
        }
        final String text = s.lines();
        if (text.isEmpty()) {
            return AD_NONE;
        }
        if (ERID_URL.matcher(text).find()) {
            return AD_ERID_URL;
        }
        if (text.contains("erid") || text.contains("еrid")) {
            final java.util.regex.Matcher m = ERID.matcher(s.cased());
            while (m.find()) {
                if (looksLikeToken(m.group(1))) {
                    return AD_ERID;
                }
            }
        }
        final java.util.regex.Matcher m = mayHaveMark(text) ? ADS.matcher(text) : null;
        if (m != null && m.find()) {
            // groups by number: named ones need Android 8
            return m.group(1) != null ? AD_LABEL : m.group(2) != null ? AD_HASHTAG : m.group(3) != null ? AD_PHRASE : AD_SPONSOR;
        }
        boolean weakLabel = false;
        final java.util.regex.Matcher line = LABEL_LINE.matcher(text);
        while (line.find()) {
            final String tail = line.group(2);
            if (tail.indexOf(':') >= 0 || tail.indexOf('—') >= 0 || tail.indexOf('–') >= 0 || tail.indexOf('-') >= 0) {
                // "Реклама:" with the contact on the next line: where to buy ads, not an ad
                continue;
            }
            if (s.linkTexts != null && REKLAMA_WORD.matcher(normalize(s.linkTexts, false, false)).find()) {
                // "Реклама" as a link (to the ad manager, an ad exchange): a footer of the channel, not a mark
                continue;
            }
            // the last line, or "Реклама." with its full stop; a lone "Реклама" on top may be a heading of news about ads
            // ("Sponsored" and "Advertisement" are no headings)
            if (tail.indexOf('.') >= 0 || text.substring(line.end()).trim().isEmpty()
                || line.group(1).startsWith("sponsored") || line.group(1).startsWith("advertisement")) {
                return AD_LABEL;
            }
            weakLabel = true;
        }
        if (!strict) {
            return AD_NONE;
        }
        // strict: an ad-like word and an outside link, with a tracked link or a call to act and an offer
        final String urls = s.urls == null ? "" : s.urls;
        final boolean link = LINK.matcher(text).find() || !urls.isEmpty();
        if (!link) {
            return AD_NONE;
        }
        weakLabel |= WEAK_LABEL.matcher(text).find();
        final boolean utm = UTM.matcher(urls).find() || UTM.matcher(text).find();
        int score = (weakLabel ? 2 : 0) + (utm ? 2 : 0);
        if (CALL.matcher(text).find()) {
            score++;
        }
        if (OFFER.matcher(text).find()) {
            score++;
        }
        return score >= 3 && (weakLabel || utm) ? AD_STRICT : AD_NONE;
    }

    /** Cheap test before the big expression: every mark has one of these pieces (most posts have none). */
    private static boolean mayHaveMark(String t) {
        return t.indexOf('#') >= 0 || t.contains("кл") || t.contains("kл") || t.contains("спонсор") || t.contains("поддержк")
            || t.contains("партнерск") || t.contains("sponsor") || t.contains("paid ");
    }

    /**
     * An erid token is a code like "2VtzqvZ5Ek6" or "LjN8KXyz": it has a digit or capitals inside, unlike the
     * words that may follow "erid" in a text about the marking ("erid tokens").
     */
    static boolean looksLikeToken(String t) {
        if (t == null || t.length() < 6) {
            return false;
        }
        int digits = 0, upper = 0;
        for (int i = 0; i < t.length(); i++) {
            final char c = t.charAt(i);
            if (c >= '0' && c <= '9') {
                digits++;
            } else if (c >= 'A' && c <= 'Z' && i > 0) {
                upper++;
            }
        }
        return digits > 0 && digits < t.length() || upper > 0;
    }

    /**
     * Zalgo (letters buried under stacks of accents), floods of invisible filler characters, one letter
     * repeated over and over, and "column" spam of near-empty lines.
     */
    public static boolean isJunk(CharSequence text) {
        if (text == null) {
            return false;
        }
        final int n = Math.min(text.length(), MAX_TEXT);
        int marks = 0, run = 0, maxRun = 0, stacked = 0;
        int fillers = 0, visible = 0;
        int sameRun = 0, maxSame = 0;
        char prev = 0;
        int lines = 1, lineVisible = 0, emptyishLines = 0;
        for (int i = 0; i < n; i++) {
            final char c = text.charAt(i);
            if (isCombiningMark(c)) {
                marks++;
                run++;
                if (run == 3) {
                    stacked++;
                }
                maxRun = Math.max(maxRun, run);
                continue;
            }
            run = 0;
            if (c == '\n') {
                if (lineVisible <= 2) {
                    emptyishLines++;
                }
                lines++;
                lineVisible = 0;
                prev = 0;
                sameRun = 0;
                continue;
            }
            if (isInvisible(c) || c == '⠀') {
                fillers++;
                continue;
            }
            if (Character.isWhitespace(c)) {
                prev = 0;
                sameRun = 0;
                continue;
            }
            visible++;
            lineVisible++;
            if (Character.isLetter(c) && Character.toLowerCase(c) == prev) {
                sameRun++;
                maxSame = Math.max(maxSame, sameRun);
            } else {
                sameRun = 1;
            }
            prev = Character.toLowerCase(c);
        }
        if (lineVisible <= 2) {
            emptyishLines++;
        }
        final boolean zalgo = stacked >= 2 && marks >= 8 || maxRun >= 6;
        final boolean filler = fillers >= 20 && fillers > visible;
        final boolean repeated = maxSame >= 40;
        final boolean column = lines >= 25 && emptyishLines * 10 >= lines * 9;
        return zalgo || filler || repeated || column;
    }
    // endregion
}
