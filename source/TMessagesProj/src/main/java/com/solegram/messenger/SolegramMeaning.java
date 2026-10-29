/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/**
 * The language half of "search by meaning", with no Android in it (a plain JVM runs it in tests).
 * <p>
 * A question ("где мы обсуждали билеты на концерт") becomes a {@link Plan}: the words that carry its meaning
 * (concepts), each with its stem and related words from a small curated dictionary (synonyms, slang,
 * English and Russian twins), plus a date range ("на прошлой неделе") and a person ("с Сашей") when the
 * question names them. Telegram's server then searches each term (it matches word prefixes), and
 * {@link #score} ranks what comes back: how many concepts a message touches, how close together, the date,
 * the chat and how recent it is.
 * <p>
 * This is query expansion and ranking, not real understanding: a message that shares no word, stem or listed
 * related word with the question is not found.
 */
public final class SolegramMeaning {

    private SolegramMeaning() {
    }

    /** At most this many terms go to the server for one question. */
    public static final int MAX_TERMS = 12;
    /** At most this many meaning words are taken from one question. */
    private static final int MAX_CONCEPTS = 6;

    // region Words

    private static final Set<String> STOP = new HashSet<>(Arrays.asList(
        // russian: pronouns, prepositions, particles
        "я", "мы", "ты", "вы", "он", "она", "оно", "они", "меня", "мне", "мной", "нас", "нам", "нами", "тебя", "тебе", "тобой",
        "вас", "вам", "вами", "его", "ему", "им", "ней", "нее", "ей", "их", "них", "ним", "ними", "мой", "моя", "мое", "мои",
        "моего", "моей", "моих", "наш", "наша", "наше", "наши", "нашей", "наших", "твой", "твоя", "твои", "ваш", "ваша", "свой", "своя",
        "свои", "себе", "себя", "в", "во", "на", "с", "со", "к", "ко", "у", "о", "об", "обо", "от", "до", "по", "за", "из", "изо",
        "для", "про", "при", "без", "над", "под", "через", "между", "и", "а", "но", "или", "либо", "да", "нет", "не", "ни", "же",
        "ли", "бы", "то", "вот", "ну", "уж", "еще", "ещё", "уже", "тоже", "также", "это", "этот", "эта", "эти", "этого", "этой",
        "этом", "тот", "та", "те", "того", "той", "том", "тех", "там", "тут", "здесь", "так", "как", "какой", "какая", "какие",
        "какую", "каком", "который", "которая", "которые", "что", "чего", "чем", "чему", "кто", "кого", "кому", "где", "куда",
        "откуда", "когда", "зачем", "почему", "сколько", "все", "всё", "всех", "весь", "вся", "был", "была", "было", "были",
        "быть", "есть", "будет", "буду", "можно", "нужно", "надо", "очень", "просто", "только", "там", "тогда", "потом",
        "чтобы", "если", "раз", "ведь", "какойто", "чтото", "ктото", "типа",
        // russian: what people say around the subject of a search
        "обсуждали", "обсуждал", "обсуждала", "обсуждать", "обсудили", "обсуждение", "говорили", "говорил", "говорила", "сказал",
        "сказала", "сказали", "писали", "писал", "писала", "написал", "написала", "написали", "переписывались", "переписка",
        "переписке", "переписку", "присылал", "присылала", "присылали", "прислал", "прислала", "прислали", "скидывал", "скидывала",
        "скидывали", "скинул", "скинула", "скинули", "кидал", "кидала", "кинул", "кинула", "отправлял", "отправляла", "отправил",
        "отправила", "отправили", "упоминал", "упоминала", "упоминали", "упомянул", "речь", "шла", "шел", "разговор", "разговаривали",
        "болтали", "сообщение", "сообщения", "сообщений", "сообщении", "чат", "чате", "чатик", "найди", "найти", "найдите",
        "покажи", "показать", "помню", "помнишь", "вспомнить", "искать", "ищу", "поиск", "там", "был", "какое", "какието",
        // english
        "i", "me", "my", "we", "us", "our", "you", "your", "he", "she", "him", "her", "they", "them", "their", "it", "its",
        "a", "an", "the", "of", "to", "in", "on", "at", "by", "for", "with", "from", "about", "into", "and", "or", "but",
        "not", "no", "is", "are", "was", "were", "be", "been", "do", "does", "did", "done", "have", "has", "had", "this",
        "that", "these", "those", "what", "which", "who", "whom", "where", "when", "why", "how", "much", "many", "some",
        "any", "there", "here", "can", "could", "would", "should", "will", "just", "also", "so", "very", "if", "then",
        "discuss", "discussed", "discussing", "talk", "talked", "talking", "said", "say", "told", "tell", "mention",
        "mentioned", "send", "sent", "wrote", "write", "written", "chat", "chatted", "message", "messages", "find", "show",
        "search", "remember", "conversation", "thing", "stuff"
    ));

    /** "с Сашей", "у мамы", "from Alex": the next word is likely the other person of the chat. */
    private static final Set<String> PERSON_PREPOSITIONS = new HashSet<>(Arrays.asList(
        "с", "со", "у", "от", "для", "к", "ко", "with", "from", "to"
    ));
    /** Prepositions after which even a word the dictionary does not know is taken for a name ("to" is too often a place). */
    private static final Set<String> STRONG_PERSON_PREPOSITIONS = new HashSet<>(Arrays.asList(
        "с", "со", "у", "от", "with", "from"
    ));

    /** English words whose "-ing" is not a verb ending (booking is not book). */
    private static final Set<String> KEEP_ING = new HashSet<>(Arrays.asList(
        "booking", "meeting", "wedding", "parking", "building", "morning", "evening", "thing", "something", "nothing",
        "anything", "everything", "string", "ring", "king", "spring", "during", "ceiling", "clothing", "shopping", "housing",
        "training", "ticketing", "listing", "recording", "painting", "setting", "settings", "boarding", "gaming"
    ));

    private static boolean isVowelRu(char c) {
        return c == 'а' || c == 'е' || c == 'и' || c == 'о' || c == 'у' || c == 'ы' || c == 'э' || c == 'ю' || c == 'я';
    }

    private static boolean isCyrillic(String s) {
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (c >= 'а' && c <= 'я' || c == 'ё') {
                return true;
            }
        }
        return false;
    }

    private static boolean isLatin(String s) {
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (c >= 'a' && c <= 'z') {
                return true;
            }
        }
        return false;
    }

    /** Lower case, "ё" as "е" (people write both). */
    public static String normalize(String s) {
        return s.toLowerCase(Locale.ROOT).replace('ё', 'е');
    }

    /** One word of a text with where it stands. */
    public static final class Token {
        public final String word;
        public final String stem;
        public final int start, end;
        public final boolean capitalized;

        Token(String word, int start, int end, boolean capitalized) {
            this.word = word;
            this.stem = stem(word);
            this.start = start;
            this.end = end;
            this.capitalized = capitalized;
        }
    }

    /** Letters and digits make words; everything else (spaces, punctuation, hyphens, emoji) separates them. */
    public static ArrayList<Token> tokenize(String text) {
        final ArrayList<Token> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        final int n = text.length();
        int i = 0;
        while (i < n) {
            while (i < n && !Character.isLetterOrDigit(text.charAt(i))) {
                i++;
            }
            final int start = i;
            while (i < n && Character.isLetterOrDigit(text.charAt(i))) {
                i++;
            }
            if (i > start) {
                final String raw = text.substring(start, i);
                out.add(new Token(normalize(raw), start, i, Character.isUpperCase(raw.charAt(0))));
            }
        }
        return out;
    }

    // endregion

    // region Stemming

    /** The stem of one lower-case word: Russian (Snowball) for Cyrillic, a light English one for Latin. */
    public static String stem(String word) {
        if (word.length() < 3) {
            return word;
        }
        if (isCyrillic(word)) {
            return stemRu(word);
        }
        if (isLatin(word)) {
            return stemEn(word);
        }
        return word;
    }

    private static final String[] PERFECTIVE_1 = {"вшись", "вши", "в"};
    private static final String[] PERFECTIVE_2 = {"ившись", "ывшись", "ивши", "ывши", "ив", "ыв"};
    private static final String[] ADJECTIVE = {"ими", "ыми", "его", "ого", "ему", "ому", "ее", "ие", "ые", "ое", "ей", "ий", "ый",
        "ой", "ем", "им", "ым", "ом", "их", "ых", "ую", "юю", "ая", "яя", "ою", "ею"};
    private static final String[] PARTICIPLE_1 = {"ем", "нн", "вш", "ющ", "щ"};
    private static final String[] PARTICIPLE_2 = {"ивш", "ывш", "ующ"};
    private static final String[] REFLEXIVE = {"ся", "сь"};
    private static final String[] VERB_1 = {"ла", "на", "ете", "йте", "ли", "й", "л", "ем", "н", "ло", "но", "ет", "ют", "ны", "ть", "ешь", "нно"};
    private static final String[] VERB_2 = {"ила", "ыла", "ена", "ейте", "уйте", "ите", "или", "ыли", "ей", "уй", "ил", "ыл", "им", "ым", "ен",
        "ило", "ыло", "ено", "ят", "ует", "уют", "ит", "ыт", "ены", "ить", "ыть", "ишь", "ую", "ю"};
    private static final String[] NOUN = {"а", "ев", "ов", "ие", "ье", "е", "иями", "ями", "ами", "еи", "ии", "и", "ией", "ей", "ой", "ий", "й",
        "иям", "ям", "ием", "ем", "ам", "ом", "о", "у", "ах", "иях", "ях", "ы", "ь", "ию", "ью", "ю", "ия", "ья", "я"};
    private static final String[] DERIVATIONAL = {"ость", "ост"};
    private static final String[] SUPERLATIVE = {"ейше", "ейш"};

    /** The longest ending of {@code group} that lies in the region from {@code from}, or null. */
    private static String longest(StringBuilder w, int from, String[] group) {
        String best = null;
        for (String e : group) {
            final int start = w.length() - e.length();
            if (start >= from && (best == null || e.length() > best.length()) && w.indexOf(e, start) == start) {
                best = e;
            }
        }
        return best;
    }

    /**
     * Two groups as Snowball's "among": the longest ending of both wins; an ending of the first group only counts
     * after "а" or "я" (and that letter stays). Returns the length removed, 0 when nothing matched or the
     * condition failed.
     */
    private static int removeAmong(StringBuilder w, int rv, String[] needsAYa, String[] plain) {
        final String a = needsAYa != null ? longest(w, rv, needsAYa) : null;
        final String b = longest(w, rv, plain);
        if (a == null && b == null) {
            return 0;
        }
        if (a != null && (b == null || a.length() > b.length())) {
            final int before = w.length() - a.length() - 1;
            if (before < rv) {
                return 0;
            }
            final char c = w.charAt(before);
            if (c != 'а' && c != 'я') {
                return 0;
            }
            w.setLength(w.length() - a.length());
            return a.length();
        }
        w.setLength(w.length() - b.length());
        return b.length();
    }

    /** Snowball's Russian stemmer (the 2007 version), for one lower-case word with "ё" already turned into "е". */
    static String stemRu(String word) {
        final StringBuilder w = new StringBuilder(word);
        final int n = w.length();
        int rv = n;
        for (int i = 0; i < n; i++) {
            if (isVowelRu(w.charAt(i))) {
                rv = i + 1;
                break;
            }
        }
        // R2: after the first non-vowel following a vowel, twice
        int r1 = n;
        for (int i = 1; i < n; i++) {
            if (!isVowelRu(w.charAt(i)) && isVowelRu(w.charAt(i - 1))) {
                r1 = i + 1;
                break;
            }
        }
        int r2 = n;
        for (int i = r1 + 1; i < n; i++) {
            if (!isVowelRu(w.charAt(i)) && isVowelRu(w.charAt(i - 1))) {
                r2 = i + 1;
                break;
            }
        }
        if (rv >= n) {
            return word;
        }
        // step 1
        if (removeAmong(w, rv, PERFECTIVE_1, PERFECTIVE_2) == 0) {
            final String reflexive = longest(w, rv, REFLEXIVE);
            if (reflexive != null) {
                w.setLength(w.length() - reflexive.length());
            }
            final String adjective = longest(w, rv, ADJECTIVE);
            if (adjective != null) {
                w.setLength(w.length() - adjective.length());
                removeAmong(w, rv, PARTICIPLE_1, PARTICIPLE_2);
            } else if (removeAmong(w, rv, VERB_1, VERB_2) == 0) {
                final String noun = longest(w, rv, NOUN);
                if (noun != null) {
                    w.setLength(w.length() - noun.length());
                }
            }
        }
        // step 2
        if (w.length() > rv && w.charAt(w.length() - 1) == 'и') {
            w.setLength(w.length() - 1);
        }
        // step 3
        final String derivational = longest(w, Math.max(r2, rv), DERIVATIONAL);
        if (derivational != null) {
            w.setLength(w.length() - derivational.length());
        }
        // step 4
        final int len = w.length();
        if (len - 2 >= rv && w.charAt(len - 1) == 'н' && w.charAt(len - 2) == 'н') {
            w.setLength(len - 1);
        } else {
            final String superlative = longest(w, rv, SUPERLATIVE);
            if (superlative != null) {
                w.setLength(w.length() - superlative.length());
                final int l = w.length();
                if (l - 2 >= rv && w.charAt(l - 1) == 'н' && w.charAt(l - 2) == 'н') {
                    w.setLength(l - 1);
                }
            } else if (len - 1 >= rv && w.charAt(len - 1) == 'ь') {
                w.setLength(len - 1);
            }
        }
        return w.toString();
    }

    private static boolean isVowelEn(char c) {
        return c == 'a' || c == 'e' || c == 'i' || c == 'o' || c == 'u' || c == 'y';
    }

    /** A light English stemmer: plurals, -ing, -ed, -ly. The server matches prefixes, so this is enough. */
    static String stemEn(String w) {
        if (w.length() <= 3 || KEEP_ING.contains(w)) {
            return w;
        }
        if (w.endsWith("ies") && w.length() > 4) {
            w = w.substring(0, w.length() - 3) + "y";
        } else if (w.endsWith("sses")) {
            w = w.substring(0, w.length() - 2);
        } else if (w.endsWith("ches") || w.endsWith("shes") || w.endsWith("xes") || w.endsWith("zes")) {
            w = w.substring(0, w.length() - 2);
        } else if (w.endsWith("s") && !w.endsWith("ss") && !w.endsWith("us") && !w.endsWith("is")) {
            w = w.substring(0, w.length() - 1);
        }
        String cut = null;
        if (w.endsWith("ing") && w.length() > 5) {
            cut = w.substring(0, w.length() - 3);
        } else if (w.endsWith("ed") && w.length() > 4) {
            cut = w.substring(0, w.length() - 2);
        } else if (w.endsWith("ly") && w.length() > 5) {
            cut = w.substring(0, w.length() - 2);
        }
        if (cut != null) {
            boolean vowel = false;
            for (int i = 0; i < cut.length(); i++) {
                vowel |= isVowelEn(cut.charAt(i));
            }
            if (vowel) {
                final int l = cut.length();
                // running -> run, planned -> plan
                if (l >= 3 && cut.charAt(l - 1) == cut.charAt(l - 2) && !isVowelEn(cut.charAt(l - 1)) && "lsz".indexOf(cut.charAt(l - 1)) < 0) {
                    cut = cut.substring(0, l - 1);
                }
                w = cut;
            }
        }
        return w;
    }

    /**
     * What is sent to the server for a word: its stem where the stem is long enough to be a safe prefix
     * ("доставк" finds "доставка", "доставки", "доставку"), otherwise the word itself ("фото", "чек").
     */
    static String queryForm(String word, String stem) {
        if (stem.length() >= 4 || stem.length() >= word.length()) {
            return stem;
        }
        return word;
    }

    // endregion

    // region Dictionary

    /** The curated related words and the names with their short forms. */
    public static final class Lexicon {
        /** Clusters of related words, the closest first. A leading "=" means "this exact word only". */
        final ArrayList<String[]> clusters = new ArrayList<>();
        /** Stem -> clusters that have a word with this stem. */
        final HashMap<String, ArrayList<Integer>> clusterIndex = new HashMap<>();
        /** Two-word entries ("день рождения"): the stems of both words -> cluster. */
        final ArrayList<String[]> phrases = new ArrayList<>();
        final ArrayList<Integer> phraseCluster = new ArrayList<>();
        /** Names: full form, short forms, transliterations. A leading "?" marks a name that is also a common word. */
        final ArrayList<String[]> names = new ArrayList<>();
        final HashMap<String, Integer> nameIndex = new HashMap<>();
        final HashSet<String> ambiguousNames = new HashSet<>();
        final HashSet<Integer> relationGroups = new HashSet<>();

        public int clusterCount() {
            return clusters.size();
        }

        public int nameCount() {
            return names.size();
        }

        /** Parses the dictionary asset ({"clusters": [[...]], "names": [[...]], "relations": [[...]]}). */
        public static Lexicon parse(String json) {
            final Lexicon lex = new Lexicon();
            final Object root = new Json(json).value();
            if (!(root instanceof Map)) {
                return lex;
            }
            final Map<?, ?> map = (Map<?, ?>) root;
            for (List<String> group : groups(map.get("clusters"))) {
                lex.addCluster(group);
            }
            for (List<String> group : groups(map.get("names"))) {
                lex.addNames(group, false);
            }
            for (List<String> group : groups(map.get("relations"))) {
                lex.addNames(group, true);
            }
            return lex;
        }

        private static List<List<String>> groups(Object o) {
            final ArrayList<List<String>> out = new ArrayList<>();
            if (o instanceof List) {
                for (Object g : (List<?>) o) {
                    if (g instanceof List) {
                        final ArrayList<String> words = new ArrayList<>();
                        for (Object w : (List<?>) g) {
                            if (w instanceof String && !((String) w).trim().isEmpty()) {
                                words.add(normalize(((String) w).trim()));
                            }
                        }
                        if (!words.isEmpty()) {
                            out.add(words);
                        }
                    }
                }
            }
            return out;
        }

        private void addCluster(List<String> words) {
            final int id = clusters.size();
            clusters.add(words.toArray(new String[0]));
            for (String w : words) {
                final String plain = w.startsWith("=") ? w.substring(1) : w;
                final ArrayList<Token> parts = tokenize(plain);
                if (parts.size() == 1) {
                    index(parts.get(0).stem, id);
                } else if (parts.size() == 2) {
                    phrases.add(new String[]{parts.get(0).stem, parts.get(1).stem});
                    phraseCluster.add(id);
                }
            }
        }

        private void index(String stem, int id) {
            ArrayList<Integer> list = clusterIndex.get(stem);
            if (list == null) {
                clusterIndex.put(stem, list = new ArrayList<>());
            }
            if (!list.contains(id)) {
                list.add(id);
            }
        }

        private void addNames(List<String> words, boolean relation) {
            final int id = names.size();
            final String[] clean = new String[words.size()];
            for (int i = 0; i < words.size(); i++) {
                String w = words.get(i);
                if (w.startsWith("?")) {
                    w = w.substring(1);
                    ambiguousNames.add(stem(w));
                }
                clean[i] = w;
                final String s = stem(w);
                if (!nameIndex.containsKey(s)) {
                    nameIndex.put(s, id);
                }
            }
            names.add(clean);
            if (relation) {
                relationGroups.add(id);
            }
        }

        List<Integer> clustersOf(String stem) {
            final ArrayList<Integer> list = clusterIndex.get(stem);
            return list != null ? list : Collections.<Integer>emptyList();
        }

        /**
         * {@link #clustersOf}, and when the stem is not there, the clusters of a stem that differs only at the end
         * ("советова" and "совет", "вайф" and "вайфа"): Snowball sometimes cuts two forms of a word differently.
         */
        List<Integer> clustersNear(String stem) {
            final List<Integer> exact = clustersOf(stem);
            if (!exact.isEmpty() || stem.length() < 4) {
                return exact;
            }
            final ArrayList<Integer> out = new ArrayList<>();
            for (Map.Entry<String, ArrayList<Integer>> e : clusterIndex.entrySet()) {
                final String k = e.getKey();
                final int shorter = Math.min(k.length(), stem.length());
                final int diff = Math.abs(k.length() - stem.length());
                if ((shorter >= 5 && diff <= 3 || shorter >= 4 && diff <= 1) && (k.startsWith(stem) || stem.startsWith(k))) {
                    for (Integer id : e.getValue()) {
                        if (!out.contains(id)) {
                            out.add(id);
                        }
                    }
                }
            }
            return out;
        }

        /** Where the word stands in a cluster (0 = its head), or -1. */
        int positionIn(int cluster, String stem) {
            final String[] words = clusters.get(cluster);
            for (int i = 0; i < words.length; i++) {
                final String w = words[i].startsWith("=") ? words[i].substring(1) : words[i];
                if (sameStem(stem(w), stem)) {
                    return i;
                }
            }
            return -1;
        }

        /** The name group of a word ("сашей" -> Alexander's), or -1. */
        int nameGroup(String stem) {
            if (stem.length() < 2) {
                return -1;
            }
            final Integer id = nameIndex.get(stem);
            return id != null ? id : -1;
        }
    }

    // endregion

    // region Plan

    /** One thing to search for. */
    public static final class Term {
        /** As shown on a chip. */
        public final String display;
        /** As sent to the server (a word, a stem or a two-word phrase). */
        public final String query;
        /** Stems that count as this term in a message (one per word of a phrase). */
        final String[] stems;
        /** Only this exact word (no prefix). */
        final boolean exact;
        /** The meaning word of the question it came from, or -1 (added by AI or by hand). */
        public final int concept;
        /** 1 for a word of the question, less for related words. */
        public final float weight;

        Term(String display, String query, String[] stems, boolean exact, int concept, float weight) {
            this.display = display;
            this.query = query;
            this.stems = stems;
            this.exact = exact;
            this.concept = concept;
            this.weight = weight;
        }

        public boolean isOriginal() {
            return weight >= 1f;
        }
    }

    /** What a question asks for. */
    public static final class Plan {
        public final String question;
        /** The meaning words of the question, as written. */
        public final ArrayList<String> concepts = new ArrayList<>();
        public final ArrayList<Term> terms = new ArrayList<>();
        /** Date range in ms (to = exclusive), 0 = none, and the words that gave it. */
        public long dateFrom, dateTo;
        public String dateLabel;
        /** The person of the chat as written ("Сашей"), null when not named. */
        public String person;
        /** "с Сашей": the words that named the person. */
        public String personLabel;
        /** All forms of the person's name the dictionary knows (stems), with the person's own stem first. */
        public final ArrayList<String> personStems = new ArrayList<>();
        /** A relation ("мама") rather than a name: chats are matched by the name the user gave them. */
        public boolean personIsRelation;

        Plan(String question) {
            this.question = question;
        }

        public Plan copy() {
            final Plan p = new Plan(question);
            p.concepts.addAll(concepts);
            p.terms.addAll(terms);
            p.dateFrom = dateFrom;
            p.dateTo = dateTo;
            p.dateLabel = dateLabel;
            p.person = person;
            p.personLabel = personLabel;
            p.personStems.addAll(personStems);
            p.personIsRelation = personIsRelation;
            return p;
        }

        public boolean hasDate() {
            return dateTo > dateFrom;
        }

        /** The same plan without one term (a chip was removed). */
        public Plan withoutTerm(String query) {
            final Plan p = copy();
            for (int i = 0; i < p.terms.size(); i++) {
                if (p.terms.get(i).query.equals(query)) {
                    p.terms.remove(i);
                    break;
                }
            }
            return p;
        }

        public Plan withoutDate() {
            final Plan p = copy();
            p.dateFrom = p.dateTo = 0;
            p.dateLabel = null;
            return p;
        }

        public Plan withoutPerson() {
            final Plan p = copy();
            p.person = null;
            p.personLabel = null;
            p.personStems.clear();
            p.personIsRelation = false;
            return p;
        }

        /** Whether {@code query} is one of the terms. */
        public boolean hasTerm(String query) {
            for (Term t : terms) {
                if (t.query.equals(query)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Adds words from elsewhere (Gemini Nano, the AI service, the user) as terms. Words already there (same stem),
         * stop words and odd tokens are skipped; a word that shares a dictionary cluster with a meaning word counts
         * for it. Returns the terms that were added.
         */
        public ArrayList<Term> addWords(List<String> words, Lexicon lex, float weight, int limit) {
            final ArrayList<Term> added = new ArrayList<>();
            for (String raw : words) {
                if (terms.size() >= limit) {
                    break;
                }
                final ArrayList<Token> parts = tokenize(raw);
                if (parts.isEmpty() || parts.size() > 2) {
                    continue;
                }
                if (parts.size() == 1) {
                    final Token t = parts.get(0);
                    if (t.word.length() < 2 || t.word.length() > 24 || STOP.contains(t.word) || hasStem(t.stem) || isDigits(t.word) && t.word.length() < 3) {
                        continue;
                    }
                    final Term term = new Term(t.word, queryForm(t.word, t.stem), new String[]{t.stem}, false, conceptFor(t.stem, lex), weight);
                    terms.add(term);
                    added.add(term);
                } else {
                    final Token a = parts.get(0), b = parts.get(1);
                    if (STOP.contains(a.word) || STOP.contains(b.word)) {
                        continue;
                    }
                    final String query = queryForm(a.word, a.stem) + " " + queryForm(b.word, b.stem);
                    if (hasTerm(query)) {
                        continue;
                    }
                    final Term term = new Term(a.word + " " + b.word, query, new String[]{a.stem, b.stem}, false, conceptFor(a.stem, lex), weight);
                    terms.add(term);
                    added.add(term);
                }
            }
            return added;
        }

        private boolean hasStem(String stem) {
            for (Term t : terms) {
                if (t.stems.length == 1 && t.stems[0].equals(stem)) {
                    return true;
                }
            }
            return false;
        }

        /** The meaning word this stem belongs to through a shared cluster, or -1. */
        private int conceptFor(String stem, Lexicon lex) {
            final List<Integer> clusters = lex.clustersNear(stem);
            if (clusters.isEmpty()) {
                return -1;
            }
            for (Term t : terms) {
                if (t.concept >= 0 && t.stems.length == 1) {
                    for (Integer c : lex.clustersOf(t.stems[0])) {
                        if (clusters.contains(c)) {
                            return t.concept;
                        }
                    }
                }
            }
            return -1;
        }

        /** Whether a chat title or a person's name is the person the question names. */
        public boolean matchesPerson(String name) {
            if (personStems.isEmpty() || name == null) {
                return false;
            }
            for (Token t : tokenize(name)) {
                for (String s : personStems) {
                    if (sameStem(t.stem, s)) {
                        return true;
                    }
                }
            }
            return false;
        }

        @Override
        public String toString() {
            final StringBuilder sb = new StringBuilder();
            for (int i = 0; i < terms.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(terms.get(i).query);
            }
            return sb.toString();
        }
    }

    /** Stems of the same word, where one may be cut a letter shorter ("андр" and "андре"). */
    static boolean sameStem(String a, String b) {
        if (a.equals(b)) {
            return true;
        }
        final int min = Math.min(a.length(), b.length());
        return min >= 3 && Math.abs(a.length() - b.length()) <= 2 && (a.startsWith(b) || b.startsWith(a));
    }

    private static boolean isDigits(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return !s.isEmpty();
    }

    /**
     * The built-in understanding of a question: meaning words, related words, date and person.
     *
     * @param now when "yesterday" is, in ms
     */
    public static Plan expand(String question, Lexicon lex, long now, TimeZone zone) {
        final Plan plan = new Plan(question == null ? "" : question.trim());
        final ArrayList<Token> tokens = tokenize(plan.question);
        final boolean[] used = new boolean[tokens.size()];

        parseDate(tokens, used, plan, now, zone);
        findPerson(tokens, used, plan, lex);

        // two-word dictionary entries first ("дня рождения" is one thing, not a day and a birth)
        final ArrayList<int[]> conceptClusters = new ArrayList<>();
        final ArrayList<Term> originals = new ArrayList<>();
        final ArrayList<Term> translits = new ArrayList<>();
        for (int i = 0; i + 1 < tokens.size(); i++) {
            if (used[i] || used[i + 1]) {
                continue;
            }
            for (int p = 0; p < lex.phrases.size(); p++) {
                final String[] ph = lex.phrases.get(p);
                if (sameStem(tokens.get(i).stem, ph[0]) && sameStem(tokens.get(i + 1).stem, ph[1])) {
                    used[i] = used[i + 1] = true;
                    final Token a = tokens.get(i), b = tokens.get(i + 1);
                    final int concept = plan.concepts.size();
                    plan.concepts.add(a.word + " " + b.word);
                    originals.add(new Term(a.word + " " + b.word, queryForm(a.word, a.stem) + " " + queryForm(b.word, b.stem),
                        new String[]{a.stem, b.stem}, false, concept, 1f));
                    conceptClusters.add(new int[]{lex.phraseCluster.get(p)});
                    break;
                }
            }
        }
        for (int i = 0; i < tokens.size() && plan.concepts.size() < MAX_CONCEPTS; i++) {
            if (used[i]) {
                continue;
            }
            final Token t = tokens.get(i);
            if (STOP.contains(t.word) || t.word.length() < 2 && !isDigits(t.word)) {
                continue;
            }
            boolean dup = false;
            for (Term o : originals) {
                dup |= o.stems.length == 1 && o.stems[0].equals(t.stem);
            }
            if (dup) {
                continue;
            }
            final int concept = plan.concepts.size();
            plan.concepts.add(t.word);
            originals.add(new Term(t.word, queryForm(t.word, t.stem), new String[]{t.stem}, false, concept, 1f));
            final List<Integer> clusters = lex.clustersNear(t.stem);
            final int[] ids = new int[clusters.size()];
            for (int k = 0; k < ids.length; k++) {
                ids[k] = clusters.get(k);
            }
            conceptClusters.add(ids);
            if (ids.length == 0 && t.capitalized && i > 0 && t.word.length() >= 4 && !isDigits(t.word)) {
                // a name, a place or a brand the dictionary does not know: the other alphabet too ("Sochi" and "Сочи")
                final String other = transliterate(t.word);
                if (other != null && !other.equals(t.word)) {
                    final String otherStem = stem(other);
                    translits.add(new Term(other, queryForm(other, otherStem), new String[]{otherStem}, false, concept, 0.9f));
                }
            }
        }

        // every meaning word first, then related words in turn, one per meaning word, so none is left out
        final LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (Term o : originals) {
            if (plan.terms.size() < MAX_TERMS && seen.add(o.query)) {
                plan.terms.add(o);
            }
        }
        for (Term o : translits) {
            if (plan.terms.size() < MAX_TERMS && seen.add(o.query)) {
                plan.terms.add(o);
            }
        }
        final ArrayList<ArrayList<Term>> related = new ArrayList<>();
        for (int c = 0; c < originals.size(); c++) {
            final ArrayList<Term> list = new ArrayList<>();
            final int[] ids = conceptClusters.get(c);
            // several clusters of one word (a word with two meanings): their words in turn
            int maxLen = 0;
            for (int id : ids) {
                maxLen = Math.max(maxLen, lex.clusters.get(id).length);
            }
            // a word with two meanings ("рецепт" of a dish and of a doctor) leans to the cluster where it stands first;
            // a cluster where it is only a far relative ("номер" of homework next to the phone number) is left out
            final Term original = originals.get(c);
            final int[] at = new int[ids.length];
            int first = Integer.MAX_VALUE;
            for (int k = 0; k < ids.length; k++) {
                at[k] = original.stems.length == 1 ? lex.positionIn(ids[k], original.stems[0]) : 0;
                if (at[k] < 0) {
                    at[k] = 0; // found through a near stem
                }
                first = Math.min(first, at[k]);
            }
            final float[] lean = new float[ids.length];
            for (int k = 0; k < ids.length; k++) {
                lean[k] = at[k] == first ? 1f : at[k] > first + 5 ? 0f : 0.85f;
            }
            for (int k = 0; k < maxLen; k++) {
                for (int x = 0; x < ids.length; x++) {
                    final String[] cluster = lex.clusters.get(ids[x]);
                    if (k >= cluster.length || lean[x] == 0f) {
                        continue;
                    }
                    final Term t = relatedTerm(cluster[k], k, originals.get(c).concept, lean[x]);
                    if (t != null) {
                        list.add(t);
                    }
                }
            }
            related.add(list);
        }
        final int[] next = new int[related.size()];
        boolean progress = true;
        while (plan.terms.size() < MAX_TERMS && progress) {
            progress = false;
            for (int c = 0; c < related.size() && plan.terms.size() < MAX_TERMS; c++) {
                final ArrayList<Term> list = related.get(c);
                while (next[c] < list.size()) {
                    final Term t = list.get(next[c]++);
                    if (seen.add(t.query) && !sameAsOriginal(t, originals)) {
                        plan.terms.add(t);
                        progress = true;
                        break;
                    }
                }
            }
        }
        return plan;
    }

    private static boolean sameAsOriginal(Term t, List<Term> originals) {
        for (Term o : originals) {
            if (o.stems.length == t.stems.length && Arrays.equals(o.stems, t.stems)) {
                return true;
            }
        }
        return false;
    }

    /** A related word of a cluster as a term; closer words (earlier in the cluster) weigh more. */
    private static Term relatedTerm(String entry, int index, int concept, float lean) {
        final boolean exact = entry.startsWith("=");
        final String plain = exact ? entry.substring(1) : entry;
        final ArrayList<Token> parts = tokenize(plain);
        if (parts.isEmpty() || parts.size() > 2) {
            return null;
        }
        // the head word and its twin in the other language are the same thing; then nearer and farther relatives
        final float weight = lean * (index <= 1 ? 0.9f : index <= 4 ? 0.8f : index <= 8 ? 0.65f : 0.5f);
        if (parts.size() == 1) {
            final Token t = parts.get(0);
            return new Term(plain, exact ? t.word : queryForm(t.word, t.stem), new String[]{t.stem}, exact, concept, weight);
        }
        final Token a = parts.get(0), b = parts.get(1);
        return new Term(plain, queryForm(a.word, a.stem) + " " + queryForm(b.word, b.stem), new String[]{a.stem, b.stem}, false, concept, weight);
    }

    /** "с Сашей", "у мамы", "должен Саше", "from Alex": the person of the chat, if one is named. */
    private static void findPerson(ArrayList<Token> tokens, boolean[] used, Plan plan, Lexicon lex) {
        for (int i = 0; i < tokens.size(); i++) {
            if (used[i]) {
                continue;
            }
            final Token t = tokens.get(i);
            if (STOP.contains(t.word) || isDigits(t.word) || t.word.length() < 2) {
                continue;
            }
            final boolean afterPreposition = i > 0 && PERSON_PREPOSITIONS.contains(tokens.get(i - 1).word);
            final int group = lex.nameGroup(t.stem);
            // written small and on its own, a word is a name only when it is a form of one ("саше", not "дарим" for Дарья)
            final boolean known = group >= 0 && (t.capitalized || afterPreposition
                || !lex.ambiguousNames.contains(t.stem) && looksLikeForm(t.word, lex.names.get(group)));
            // an unknown capitalized word in the middle of a sentence after "с", "у", "от": most likely a name
            final boolean unknownName = group < 0 && i > 0 && t.capitalized && STRONG_PERSON_PREPOSITIONS.contains(tokens.get(i - 1).word)
                && lex.clustersNear(t.stem).isEmpty();
            if (!known && !unknownName) {
                continue;
            }
            used[i] = true;
            plan.person = t.word;
            plan.personLabel = afterPreposition ? tokens.get(i - 1).word + " " + t.word : t.word;
            plan.personStems.add(t.stem);
            if (known) {
                plan.personIsRelation = lex.relationGroups.contains(group);
                for (String w : lex.names.get(group)) {
                    final String s = stem(w);
                    if (!plan.personStems.contains(s)) {
                        plan.personStems.add(s);
                    }
                }
            }
            return;
        }
    }

    /** "саше" is a form of "саша", "андрею" of "андрей": the name without its last letter, then a short ending. */
    private static boolean looksLikeForm(String word, String[] forms) {
        for (String f : forms) {
            if (f.length() >= 3 && word.length() <= f.length() + 2 && word.startsWith(f.substring(0, f.length() - 1))) {
                return true;
            }
        }
        return false;
    }

    private static final String[] RU_LATIN = {"a", "b", "v", "g", "d", "e", "zh", "z", "i", "y", "k", "l", "m", "n", "o", "p", "r",
        "s", "t", "u", "f", "kh", "ts", "ch", "sh", "sch", "", "y", "", "e", "yu", "ya"};
    private static final String[][] LATIN_RU = {{"shch", "щ"}, {"sch", "щ"}, {"zh", "ж"}, {"kh", "х"}, {"ts", "ц"}, {"ch", "ч"},
        {"sh", "ш"}, {"yu", "ю"}, {"ya", "я"}, {"yo", "е"}, {"ye", "е"}, {"ph", "ф"}, {"th", "т"}, {"oo", "у"}, {"ee", "и"}};

    /** The word in the other alphabet ("сочи" and "sochi"), or null when it is neither Cyrillic nor Latin. */
    static String transliterate(String word) {
        final StringBuilder sb = new StringBuilder();
        if (isCyrillic(word)) {
            for (int i = 0; i < word.length(); i++) {
                final char c = word.charAt(i);
                if (c >= 'а' && c <= 'я') {
                    sb.append(RU_LATIN[c - 'а']);
                } else if (c == 'ё') {
                    sb.append("yo");
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }
        if (!isLatin(word)) {
            return null;
        }
        int i = 0;
        outer:
        while (i < word.length()) {
            for (String[] d : LATIN_RU) {
                if (word.startsWith(d[0], i)) {
                    sb.append(d[1]);
                    i += d[0].length();
                    continue outer;
                }
            }
            final char c = word.charAt(i++);
            final int k = "abcdefghijklmnopqrstuvwxyz".indexOf(c);
            if (k < 0) {
                sb.append(c);
                continue;
            }
            if (c == 'y') {
                // a "y" after a vowel is "й" (Sergey), otherwise "ы" or "и"
                final char prev = sb.length() > 0 ? sb.charAt(sb.length() - 1) : ' ';
                sb.append("аеиоуыэюя".indexOf(prev) >= 0 ? 'й' : 'и');
                continue;
            }
            sb.append(new String[]{"а", "б", "к", "д", "е", "ф", "г", "х", "и", "дж", "к", "л", "м", "н", "о", "п", "к", "р", "с",
                "т", "у", "в", "в", "кс", "", "з"}[k]);
        }
        return sb.toString();
    }

    // endregion

    // region Dates

    private static final String[][] MONTHS = {
        {"январ", "january", "jan"}, {"феврал", "february", "feb"}, {"март", "march", "mar"}, {"апрел", "april", "apr"},
        {"ма", "may"}, {"июн", "june", "jun"}, {"июл", "july", "jul"}, {"август", "august", "aug"},
        {"сентябр", "september", "sep", "sept"}, {"октябр", "october", "oct"}, {"ноябр", "november", "nov"}, {"декабр", "december", "dec"}
    };

    /** The month a word names (0-11), or -1. "ма" stands for май/мая/мае only. */
    private static int month(Token t, Token previous) {
        final String w = t.word;
        if (isCyrillic(w)) {
            if (w.equals("май") || w.equals("мая") || w.equals("мае")) {
                return 4;
            }
            for (int m = 0; m < 12; m++) {
                if (m != 4 && w.startsWith(MONTHS[m][0]) && w.length() <= MONTHS[m][0].length() + 2) {
                    return m;
                }
            }
            return -1;
        }
        for (int m = 0; m < 12; m++) {
            for (int k = 1; k < MONTHS[m].length; k++) {
                if (w.equals(MONTHS[m][k])) {
                    // "may" and "mar" are words too: a month only after "in", "last", "this" or a number
                    if ((m == 4 || m == 2 && w.length() == 3) && (previous == null || !(previous.word.equals("in")
                        || previous.word.equals("last") || previous.word.equals("this") || isDigits(previous.word)))) {
                        return -1;
                    }
                    return m;
                }
            }
        }
        return -1;
    }

    private static Calendar day(long now, TimeZone zone) {
        final Calendar c = Calendar.getInstance(zone, Locale.ROOT);
        c.setTimeInMillis(now);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c;
    }

    private static boolean prefixAny(String w, String... prefixes) {
        for (String p : prefixes) {
            if (w.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isLast(String w) {
        return prefixAny(w, "прошл", "прошед", "предыдущ", "last", "previous", "past");
    }

    private static boolean isThis(String w) {
        return prefixAny(w, "эт", "текущ", "нынешн", "this", "current");
    }

    /** "вчера", "на прошлой неделе", "в мае", "3 дня назад", "летом", "last month"... into a date range. */
    private static void parseDate(ArrayList<Token> tokens, boolean[] used, Plan plan, long now, TimeZone zone) {
        final Calendar today = day(now, zone);
        final long dayMs = 24 * 3600_000L;
        for (int i = 0; i < tokens.size(); i++) {
            if (used[i]) {
                continue;
            }
            final String w = tokens.get(i).word;
            final Token prev = i > 0 ? tokens.get(i - 1) : null;
            final Token nextTok = i + 1 < tokens.size() ? tokens.get(i + 1) : null;
            long from = 0, to = 0;
            int first = i, last = i;
            if (w.equals("сегодня") || w.equals("today") || w.equals("сегодняшн")) {
                from = today.getTimeInMillis();
                to = now + 1;
            } else if (w.equals("вчера") || w.equals("yesterday") || w.startsWith("вчерашн")) {
                from = today.getTimeInMillis() - dayMs;
                to = today.getTimeInMillis();
            } else if (w.equals("позавчера")) {
                from = today.getTimeInMillis() - 2 * dayMs;
                to = today.getTimeInMillis() - dayMs;
            } else if (w.equals("недавно") || w.equals("recently") || w.equals("lately") || w.equals("recent")) {
                from = today.getTimeInMillis() - 14 * dayMs;
                to = now + 1;
            } else if (prefixAny(w, "недел", "week") && !w.startsWith("weekend")) {
                final Calendar monday = (Calendar) today.clone();
                monday.setFirstDayOfWeek(Calendar.MONDAY);
                final int dow = (today.get(Calendar.DAY_OF_WEEK) + 5) % 7; // monday = 0
                monday.add(Calendar.DAY_OF_MONTH, -dow);
                if (prev != null && isLast(prev.word)) {
                    from = monday.getTimeInMillis() - 7 * dayMs;
                    to = monday.getTimeInMillis();
                    first = i - 1;
                } else if (prev != null && isThis(prev.word)) {
                    from = monday.getTimeInMillis();
                    to = now + 1;
                    first = i - 1;
                } else if (prev != null && (prev.word.equals("на") || prev.word.equals("за"))) {
                    // "на неделе": these days
                    from = today.getTimeInMillis() - 7 * dayMs;
                    to = now + 1;
                }
            } else if (prefixAny(w, "выходн", "weekend")) {
                // the last weekend (this one, if it is the weekend now)
                final Calendar sat = (Calendar) today.clone();
                final int dow = (today.get(Calendar.DAY_OF_WEEK) + 5) % 7;
                sat.add(Calendar.DAY_OF_MONTH, dow >= 5 ? -(dow - 5) : -(dow + 2));
                from = sat.getTimeInMillis();
                to = from + 2 * dayMs;
                if (prev != null && (isLast(prev.word) || isThis(prev.word))) {
                    first = i - 1;
                }
            } else if (prefixAny(w, "месяц", "month")) {
                final Calendar m = (Calendar) today.clone();
                m.set(Calendar.DAY_OF_MONTH, 1);
                if (prev != null && isLast(prev.word)) {
                    final Calendar start = (Calendar) m.clone();
                    start.add(Calendar.MONTH, -1);
                    from = start.getTimeInMillis();
                    to = m.getTimeInMillis();
                    first = i - 1;
                } else if (prev != null && isThis(prev.word)) {
                    from = m.getTimeInMillis();
                    to = now + 1;
                    first = i - 1;
                }
            } else if (prefixAny(w, "год", "year") && !w.startsWith("годн")) {
                final Calendar y = (Calendar) today.clone();
                y.set(Calendar.DAY_OF_YEAR, 1);
                if (prev != null && isLast(prev.word)) {
                    final Calendar start = (Calendar) y.clone();
                    start.add(Calendar.YEAR, -1);
                    from = start.getTimeInMillis();
                    to = y.getTimeInMillis();
                    first = i - 1;
                } else if (prev != null && isThis(prev.word)) {
                    from = y.getTimeInMillis();
                    to = now + 1;
                    first = i - 1;
                } else if (prev != null && isDigits(prev.word) && prev.word.length() == 4) {
                    final int year = Integer.parseInt(prev.word);
                    final Calendar start = (Calendar) y.clone();
                    start.set(Calendar.YEAR, year);
                    from = start.getTimeInMillis();
                    start.add(Calendar.YEAR, 1);
                    to = start.getTimeInMillis();
                    first = i - 1;
                }
            } else if (isDigits(w) && w.length() == 4 && (w.startsWith("20") || w.startsWith("19"))
                && (prev != null && (prev.word.equals("в") || prev.word.equals("in")) || nextTok != null && prefixAny(nextTok.word, "год", "year"))) {
                final int year = Integer.parseInt(w);
                final Calendar start = (Calendar) today.clone();
                start.set(Calendar.DAY_OF_YEAR, 1);
                start.set(Calendar.YEAR, year);
                from = start.getTimeInMillis();
                start.add(Calendar.YEAR, 1);
                to = start.getTimeInMillis();
                if (nextTok != null && prefixAny(nextTok.word, "год", "year")) {
                    last = i + 1;
                }
            } else if (prefixAny(w, "назад", "ago") && prev != null) {
                // "3 дня назад", "2 weeks ago", "неделю назад"
                int count = 1;
                Token unit = prev;
                if (i >= 2 && isDigits(tokens.get(i - 2).word)) {
                    count = Math.min(400, Integer.parseInt(tokens.get(i - 2).word));
                    first = i - 2;
                } else {
                    first = i - 1;
                }
                long unitMs = 0;
                if (prefixAny(unit.word, "дн", "ден", "day")) {
                    unitMs = dayMs;
                } else if (prefixAny(unit.word, "недел", "week")) {
                    unitMs = 7 * dayMs;
                } else if (prefixAny(unit.word, "месяц", "month")) {
                    unitMs = 30 * dayMs;
                } else if (prefixAny(unit.word, "год", "лет", "year")) {
                    unitMs = 365 * dayMs;
                }
                if (unitMs > 0) {
                    final long center = today.getTimeInMillis() - count * unitMs;
                    final long spread = Math.max(dayMs, (long) (unitMs * Math.max(0.5, count * 0.3)));
                    from = center - spread;
                    to = Math.min(now + 1, center + spread + dayMs);
                } else {
                    first = i;
                }
            } else if (prefixAny(w, "летом", "лето", "summer", "зимой", "зима", "winter", "весной", "весна", "spring", "осенью", "осень", "autumn", "fall")
                && !w.equals("fall") || w.equals("fall") && prev != null && (prev.word.equals("last") || prev.word.equals("this") || prev.word.equals("in"))) {
                final int startMonth = prefixAny(w, "лет", "summer") ? Calendar.JUNE : prefixAny(w, "зим", "winter") ? Calendar.DECEMBER
                    : prefixAny(w, "весн", "spring") ? Calendar.MARCH : Calendar.SEPTEMBER;
                final Calendar s = (Calendar) today.clone();
                s.set(Calendar.DAY_OF_MONTH, 1);
                s.set(Calendar.MONTH, startMonth);
                if (s.getTimeInMillis() > now || prev != null && isLast(prev.word)) {
                    s.add(Calendar.YEAR, -1);
                }
                if (startMonth == Calendar.DECEMBER && s.getTimeInMillis() > now) {
                    s.add(Calendar.YEAR, -1);
                }
                from = s.getTimeInMillis();
                s.add(Calendar.MONTH, 3);
                to = Math.min(now + 1, s.getTimeInMillis());
                if (prev != null && (isLast(prev.word) || isThis(prev.word))) {
                    first = i - 1;
                }
            } else {
                final int m = month(tokens.get(i), prev);
                if (m >= 0) {
                    final Calendar s = (Calendar) today.clone();
                    s.set(Calendar.DAY_OF_MONTH, 1);
                    s.set(Calendar.MONTH, m);
                    // "в мае": the last May that has begun; "in May 2024", "в мае 2024 года"
                    if (nextTok != null && isDigits(nextTok.word) && nextTok.word.length() == 4) {
                        s.set(Calendar.YEAR, Integer.parseInt(nextTok.word));
                        last = i + 1;
                        if (i + 2 < tokens.size() && prefixAny(tokens.get(i + 2).word, "год", "year")) {
                            last = i + 2;
                        }
                    } else if (s.getTimeInMillis() > now || prev != null && isLast(prev.word)) {
                        s.add(Calendar.YEAR, -1);
                    }
                    if (prev != null && isDigits(prev.word) && prev.word.length() <= 2) {
                        // "15 мая": that day, give or take
                        final int dayOfMonth = Integer.parseInt(prev.word);
                        if (dayOfMonth >= 1 && dayOfMonth <= 31) {
                            s.set(Calendar.DAY_OF_MONTH, Math.min(dayOfMonth, s.getActualMaximum(Calendar.DAY_OF_MONTH)));
                            from = s.getTimeInMillis() - dayMs;
                            to = s.getTimeInMillis() + 2 * dayMs;
                            first = i - 1;
                        }
                    }
                    if (from == 0) {
                        from = s.getTimeInMillis();
                        s.add(Calendar.MONTH, 1);
                        to = s.getTimeInMillis();
                    }
                    if (prev != null && (isLast(prev.word) || isThis(prev.word))) {
                        first = Math.min(first, i - 1);
                    }
                }
            }
            if (to > from && from > 0) {
                // the first date wins; its words are not searched for
                if (!plan.hasDate()) {
                    plan.dateFrom = from;
                    plan.dateTo = to;
                    // "на прошлой неделе": the preposition belongs to the label
                    int labelFirst = first;
                    if (labelFirst > 0 && (tokens.get(labelFirst - 1).word.equals("на") || tokens.get(labelFirst - 1).word.equals("в")
                        || tokens.get(labelFirst - 1).word.equals("во") || tokens.get(labelFirst - 1).word.equals("in")
                        || tokens.get(labelFirst - 1).word.equals("за") || tokens.get(labelFirst - 1).word.equals("last") && first == i)) {
                        labelFirst--;
                    }
                    plan.dateLabel = plan.question.substring(tokens.get(labelFirst).start, tokens.get(last).end);
                }
                for (int k = Math.max(0, first); k <= last && k < tokens.size(); k++) {
                    used[k] = true;
                }
            }
        }
    }

    // endregion

    // region Ranking

    /** How a message answers a plan. */
    public static final class Match {
        public float score;
        /** Meaning words of the question the message touches. */
        public int concepts;
        /** Different terms found in it. */
        public int terms;
        /** Where the found words stand in the text (start, end pairs), and the words as written there (lower case). */
        public final ArrayList<int[]> spans = new ArrayList<>();
        public final ArrayList<String> words = new ArrayList<>();
        public boolean inDate;
        public boolean inChat;
        /** The meaning words found, one bit each (for the conversation bonus). */
        public int conceptMask;
        /** Score before the conversation bonus. */
        float base;
    }

    /** Whether a word of a message is this term (one word of it, for a phrase). */
    private static boolean wordMatches(Token token, Term term, int part) {
        final String stem = term.stems[part];
        if (term.exact) {
            return token.word.equals(stem) || token.stem.equals(stem);
        }
        if (token.stem.equals(stem)) {
            return true;
        }
        // the server matches prefixes: "билет" finds "билетик", "доставк" finds "доставку"; a short prefix only
        // with a short ending ("кот" finds "кота", not "который")
        final String prefix = term.stems.length == 1 ? term.query : stem;
        return prefixOf(token.word, prefix) || prefixOf(token.word, stem);
    }

    private static boolean prefixOf(String word, String prefix) {
        final int n = prefix.length();
        if (n < 3 || !word.startsWith(prefix)) {
            return false;
        }
        final int tail = word.length() - n;
        return n >= 5 || n == 4 && tail <= 4 || tail <= 2;
    }

    /**
     * Scores one message for a plan.
     *
     * @param text      everything searchable of the message (text, caption, file name, link title)
     * @param dateMs    when it was sent
     * @param chatMatch the chat is the person the question names
     */
    public static Match score(Plan plan, String text, long dateMs, boolean chatMatch, long now) {
        final Match m = new Match();
        final ArrayList<Token> tokens = tokenize(text);
        final int conceptCount = plan.concepts.size();
        final float[] best = new float[Math.max(1, conceptCount)];
        final int[] extra = new int[Math.max(1, conceptCount)];
        final HashSet<String> foundTerms = new HashSet<>();
        final HashSet<String> loose = new HashSet<>();
        // hits: token index and the concept it stands for, for the proximity window
        final ArrayList<int[]> hits = new ArrayList<>();
        final boolean[] marked = new boolean[tokens.size()];
        for (Term term : plan.terms) {
            if (term.stems.length == 1) {
                for (int i = 0; i < tokens.size(); i++) {
                    if (wordMatches(tokens.get(i), term, 0)) {
                        hit(m, term, tokens, i, marked, hits, best, extra, foundTerms, loose);
                    }
                }
            } else {
                // a phrase: both words, near each other
                for (int i = 0; i < tokens.size(); i++) {
                    if (!wordMatches(tokens.get(i), term, 0)) {
                        continue;
                    }
                    for (int j = i + 1; j < tokens.size() && j <= i + 3; j++) {
                        if (wordMatches(tokens.get(j), term, 1)) {
                            hit(m, term, tokens, i, marked, hits, best, extra, foundTerms, loose);
                            hit(m, term, tokens, j, marked, hits, best, extra, foundTerms, loose);
                            break;
                        }
                    }
                }
            }
        }
        float conceptSum = 0;
        for (int c = 0; c < conceptCount; c++) {
            if (best[c] > 0) {
                // more words of one meaning (места, танцпол, касса) say it more surely than one
                conceptSum += Math.min(1f, best[c] + 0.15f * Math.max(0, extra[c] - 1));
                m.concepts++;
                m.conceptMask |= 1 << Math.min(c, 30);
            }
        }
        m.terms = foundTerms.size();
        final float looseScore = Math.min(1f, 0.5f * loose.size());
        final float coverage = Math.min(1.2f, (conceptSum + looseScore) / Math.max(1, conceptCount));
        float score = 2.2f * coverage + 0.12f * Math.min(m.terms, 6);
        if (m.concepts >= 2) {
            score += 0.7f / (1f + (window(hits, conceptCount) - 1) / 5f);
        }
        if (plan.hasDate()) {
            m.inDate = dateMs >= plan.dateFrom && dateMs < plan.dateTo;
            final long dayMs = 24 * 3600_000L;
            if (m.inDate) {
                score += 0.45f;
            } else if (dateMs >= plan.dateFrom - 3 * dayMs && dateMs < plan.dateTo + 3 * dayMs) {
                score += 0.15f;
            } else {
                score -= 0.2f;
            }
        }
        final float ageDays = Math.max(0, (now - dateMs) / (24f * 3600_000f));
        score += 0.15f * (float) Math.exp(-ageDays / 45f);
        if (chatMatch) {
            m.inChat = true;
            score += 0.8f;
        }
        if (!plan.personStems.isEmpty()) {
            // "какой подарок дарим Лене": the name in the text counts too
            for (Token t : tokens) {
                if (t.capitalized || plan.personIsRelation) {
                    boolean named = false;
                    for (String ps : plan.personStems) {
                        named |= sameStem(t.stem, ps);
                    }
                    if (named) {
                        score += 0.5f;
                        if (!m.words.contains(t.word)) {
                            m.words.add(t.word);
                            m.spans.add(new int[]{t.start, t.end});
                        }
                        break;
                    }
                }
            }
        }
        if (tokens.size() > 120) {
            // a long post that mentions everything once is less likely the conversation
            score -= 0.1f;
        }
        m.score = m.base = score;
        Collections.sort(m.spans, (a, b) -> a[0] - b[0]);
        return m;
    }

    private static void hit(Match m, Term term, ArrayList<Token> tokens, int i, boolean[] marked, ArrayList<int[]> hits,
                            float[] best, int[] extra, HashSet<String> foundTerms, HashSet<String> loose) {
        final boolean newTerm = foundTerms.add(term.query);
        if (term.concept >= 0 && term.concept < best.length) {
            best[term.concept] = Math.max(best[term.concept], term.weight);
            if (newTerm) {
                extra[term.concept]++;
            }
            hits.add(new int[]{i, term.concept});
        } else {
            loose.add(term.query);
        }
        if (!marked[i]) {
            marked[i] = true;
            final Token t = tokens.get(i);
            m.spans.add(new int[]{t.start, t.end});
            if (!m.words.contains(t.word)) {
                m.words.add(t.word);
            }
        }
    }

    /** One found message for {@link #boostConversations}. */
    public interface Found {
        long chatId();

        long dateMs();

        Match match();
    }

    /**
     * "Где мы обсуждали": a conversation answers, not one message. A message gets a bonus when messages of the same
     * chat around it (two days either way) touch the meaning words it lacks. Recomputes every {@link Match#score}.
     */
    public static void boostConversations(List<? extends Found> found, Plan plan) {
        final int conceptCount = plan.concepts.size();
        final HashMap<Long, ArrayList<Found>> byChat = new HashMap<>();
        for (Found f : found) {
            f.match().score = f.match().base;
            ArrayList<Found> list = byChat.get(f.chatId());
            if (list == null) {
                byChat.put(f.chatId(), list = new ArrayList<>());
            }
            list.add(f);
        }
        if (conceptCount < 2) {
            return;
        }
        final long window = 2 * 24 * 3600_000L;
        for (ArrayList<Found> list : byChat.values()) {
            if (list.size() < 2) {
                continue;
            }
            for (Found f : list) {
                int around = 0;
                for (Found g : list) {
                    if (g != f && Math.abs(g.dateMs() - f.dateMs()) <= window) {
                        around |= g.match().conceptMask;
                    }
                }
                final int added = Integer.bitCount(around & ~f.match().conceptMask);
                if (added > 0 && f.match().conceptMask != 0) {
                    f.match().score += 0.6f * added / conceptCount;
                }
            }
        }
    }

    /** The fewest words that hold one hit of every concept found (1 = all in one word). */
    private static int window(ArrayList<int[]> hits, int conceptCount) {
        Collections.sort(hits, (a, b) -> a[0] - b[0]);
        final HashSet<Integer> all = new HashSet<>();
        for (int[] h : hits) {
            all.add(h[1]);
        }
        final int need = all.size();
        final HashMap<Integer, Integer> counts = new HashMap<>();
        int have = 0, left = 0, bestSpan = Integer.MAX_VALUE;
        for (int right = 0; right < hits.size(); right++) {
            final int c = hits.get(right)[1];
            final int was = counts.containsKey(c) ? counts.get(c) : 0;
            counts.put(c, was + 1);
            if (was == 0) {
                have++;
            }
            while (have == need && left <= right) {
                bestSpan = Math.min(bestSpan, hits.get(right)[0] - hits.get(left)[0] + 1);
                final int lc = hits.get(left)[1];
                final int lcount = counts.get(lc) - 1;
                counts.put(lc, lcount);
                if (lcount == 0) {
                    have--;
                }
                left++;
            }
        }
        return bestSpan == Integer.MAX_VALUE ? 50 : bestSpan;
    }

    /** A short piece of a text around its first found word (for on-device reranking and the result rows). */
    public static String snippet(String text, Match m, int max) {
        if (text == null) {
            return "";
        }
        final String flat = text.replace('\n', ' ');
        if (flat.length() <= max) {
            return flat;
        }
        final int at = m != null && !m.spans.isEmpty() ? m.spans.get(0)[0] : 0;
        final int start = Math.max(0, Math.min(at - max / 4, flat.length() - max));
        return (start > 0 ? "…" : "") + flat.substring(start, Math.min(flat.length(), start + max)).trim() + (start + max < flat.length() ? "…" : "");
    }

    // endregion

    // region Answers of a language model

    /**
     * Search words from a model's answer: the "terms" array of a JSON answer, or a comma/line separated list
     * when the model did not keep to JSON.
     */
    public static ArrayList<String> parseModelTerms(String answer) {
        final ArrayList<String> out = new ArrayList<>();
        if (answer == null) {
            return out;
        }
        String body = answer.replaceAll("(?s)<think>.*?</think>", "").trim();
        final int terms = body.indexOf("\"terms\"");
        if (terms >= 0) {
            final int open = body.indexOf('[', terms);
            final int close = open < 0 ? -1 : body.indexOf(']', open);
            if (open >= 0 && close > open) {
                final Object list = new Json(body.substring(open, close + 1)).value();
                if (list instanceof List) {
                    for (Object o : (List<?>) list) {
                        if (o instanceof String) {
                            out.add(((String) o).trim());
                        }
                    }
                    return out;
                }
            }
        }
        // a plain list: drop bullets, numbering, quotes
        for (String part : body.split("[,;\\n]")) {
            String s = part.replaceAll("^[\\s\\-*•\\d.)]+", "").replace("\"", "").replace("«", "").replace("»", "").trim();
            if (!s.isEmpty() && s.length() <= 30 && !s.contains(":")) {
                out.add(s);
            }
        }
        return out;
    }

    /** A date range "YYYY-MM-DD..YYYY-MM-DD" from a model's answer, as {from, to} ms, or null. */
    public static long[] parseModelDate(String answer, TimeZone zone) {
        if (answer == null) {
            return null;
        }
        final java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})\\s*\\.\\.\\s*(\\d{4})-(\\d{2})-(\\d{2})").matcher(answer);
        if (!m.find()) {
            return null;
        }
        final Calendar a = Calendar.getInstance(zone, Locale.ROOT);
        a.clear();
        a.set(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)) - 1, Integer.parseInt(m.group(3)));
        final Calendar b = Calendar.getInstance(zone, Locale.ROOT);
        b.clear();
        b.set(Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)) - 1, Integer.parseInt(m.group(6)));
        b.add(Calendar.DAY_OF_MONTH, 1);
        if (b.getTimeInMillis() <= a.getTimeInMillis()) {
            return null;
        }
        return new long[]{a.getTimeInMillis(), b.getTimeInMillis()};
    }

    /** The order a model gave to numbered snippets ("3, 1, 7"), as 0-based indexes, each once. */
    public static ArrayList<Integer> parseModelOrder(String answer, int count) {
        final ArrayList<Integer> out = new ArrayList<>();
        if (answer == null) {
            return out;
        }
        final java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\d+").matcher(answer.replaceAll("(?s)<think>.*?</think>", ""));
        while (m.find()) {
            try {
                final int n = Integer.parseInt(m.group()) - 1;
                if (n >= 0 && n < count && !out.contains(n)) {
                    out.add(n);
                }
            } catch (NumberFormatException ignore) {
            }
        }
        return out;
    }

    // endregion

    // region JSON

    /** Just enough JSON for the dictionary and model answers (objects, arrays, strings, numbers, literals). */
    static final class Json {
        private final String s;
        private int i;

        Json(String s) {
            this.s = s;
        }

        Object value() {
            try {
                return read();
            } catch (RuntimeException e) {
                return null;
            }
        }

        private void space() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }

        private Object read() {
            space();
            final char c = s.charAt(i);
            if (c == '{') {
                i++;
                final HashMap<String, Object> map = new HashMap<>();
                space();
                if (s.charAt(i) == '}') {
                    i++;
                    return map;
                }
                while (true) {
                    space();
                    final String key = string();
                    space();
                    i++; // ':'
                    map.put(key, read());
                    space();
                    if (s.charAt(i++) == '}') {
                        return map;
                    }
                }
            }
            if (c == '[') {
                i++;
                final ArrayList<Object> list = new ArrayList<>();
                space();
                if (s.charAt(i) == ']') {
                    i++;
                    return list;
                }
                while (true) {
                    list.add(read());
                    space();
                    if (s.charAt(i++) == ']') {
                        return list;
                    }
                }
            }
            if (c == '"') {
                return string();
            }
            final int start = i;
            while (i < s.length() && ",]} \n\r\t".indexOf(s.charAt(i)) < 0) {
                i++;
            }
            return s.substring(start, i);
        }

        private String string() {
            if (s.charAt(i) != '"') {
                throw new IllegalStateException();
            }
            i++;
            final StringBuilder sb = new StringBuilder();
            while (true) {
                final char c = s.charAt(i++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    final char e = s.charAt(i++);
                    switch (e) {
                        case 'n':
                            sb.append('\n');
                            break;
                        case 't':
                            sb.append('\t');
                            break;
                        case 'u':
                            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                            break;
                        default:
                            sb.append(e);
                    }
                } else {
                    sb.append(c);
                }
            }
        }
    }

    // endregion
}
