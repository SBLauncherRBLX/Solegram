/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger;

import android.text.TextUtils;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.Utilities;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Song lyrics: synced lines when a source has them, plain text otherwise. Asked only when the lyrics are opened, with
 * the track's title, artist and length; kept in memory. Sources, one after another until good synced lyrics are found:
 * LRCLIB (lrclib.net, a free open lyrics library: exact lookup, then searches), NetEase Cloud Music's public web API
 * (synced, many Russian and world songs), then lyrics.ovh (plain text). Titles are cleaned first (track numbers, file
 * extensions, «(Official Video)», «(feat. …)», «- Remastered»; «Artist - Title» file names are split) and every answer
 * must match the title and one of the artists (also across Cyrillic/Latin spelling). Lyrics of a version whose length is
 * far from the track's are shown as plain text (their times would be off).
 */
public final class SolegramLyrics {

    public static final class Line {
        /** Start in milliseconds (-1 for plain lyrics). */
        public final long time;
        public final String text;

        Line(long time, String text) {
            this.time = time;
            this.text = text;
        }
    }

    public static final class Lyrics {
        public final ArrayList<Line> lines = new ArrayList<>();
        public boolean synced;
        public boolean instrumental;

        /** The line playing at a moment (-1 before the first). */
        public int lineAt(long ms) {
            if (!synced) {
                return -1;
            }
            int found = -1;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).time <= ms) {
                    found = i;
                } else {
                    break;
                }
            }
            return found;
        }

        /** The same lines without their times. */
        Lyrics plain() {
            final Lyrics l = new Lyrics();
            l.instrumental = instrumental;
            for (Line ln : lines) {
                l.lines.add(new Line(-1, ln.text));
            }
            return l;
        }
    }

    private static final String API = "https://lrclib.net/api/";
    private static final String NETEASE = "https://music.163.com/api/";
    private static final String OVH = "https://api.lyrics.ovh/v1/";
    private static final String AGENT = "Solegram (https://github.com/SBLauncherRBLX/Solegram)";
    private static final String BROWSER = "Mozilla/5.0";
    private static final Pattern TAG = Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]");
    private static final Pattern ANY_TAG = Pattern.compile("\\[[^\\]]*]");
    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
    private static final Pattern EXTENSION = Pattern.compile("\\.(mp3|m4a|flac|ogg|opus|wav|aac|wma)$", FLAGS);
    private static final Pattern TRACK_NUMBER = Pattern.compile("^\\d{1,3}\\s*[.\\-_)]\\s*");
    private static final Pattern JUNK = Pattern.compile("\\s*[(\\[]\\s*(?:official|lyric|lyrics|audio|video|music video|clip|клип|премьера|hd|hq|4k|mv|visuali[sz]er|explicit|clean|prod\\.?|remaster|remastered|bonus|текст)[^)\\]]*[)\\]]", FLAGS);
    private static final Pattern FEAT = Pattern.compile("\\s*[(\\[]?\\s*(?:feat\\.?|ft\\.?|featuring|при уч\\.?)\\s+[^)\\]]*[)\\]]?", FLAGS);
    private static final Pattern TAIL = Pattern.compile("\\s+-\\s+(?:(?:\\d{4}\\s+)?remaster(?:ed)?|radio edit|single version|original mix).*$", FLAGS);
    private static final Pattern BRACKETS = Pattern.compile("\\s*[(\\[][^)\\]]*[)\\]]");
    private static final Pattern ARTIST_SPLIT = Pattern.compile("\\s*(?:,|&|;|/|\\bfeat\\.?|\\bft\\.?|\\bfeaturing\\b|\\bx\\b|\\bх\\b|\\bvs\\.?)\\s*", FLAGS | Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern NOT_LETTER = Pattern.compile("[^\\p{L}\\p{Nd}]");
    /** NetEase credit lines («作词 : …», «制作人 : …»). */
    private static final Pattern CREDIT = Pattern.compile("^\\s*[^:：]{0,24}[\\u4e00-\\u9fff][^:：]{0,24}[:：]");
    private static final String CYRILLIC = "абвгдеёжзийклмнопрстуфхцчшщъыьэюяіїєґ";
    private static final String[] LATIN = {"a", "b", "v", "g", "d", "e", "e", "zh", "z", "i", "y", "k", "l", "m", "n", "o", "p", "r",
            "s", "t", "u", "f", "kh", "ts", "ch", "sh", "shch", "", "y", "", "e", "yu", "ya", "i", "yi", "ye", "g"};

    /** Results by track (null value: looked up, nothing found). */
    private static final HashMap<String, Lyrics> cache = new HashMap<>();

    private SolegramLyrics() {
    }

    private static String key(String title, String artist, int seconds) {
        return (artist == null ? "" : artist.toLowerCase()) + "\n" + (title == null ? "" : title.toLowerCase()) + "\n" + seconds;
    }

    public static boolean isCached(String title, String artist, int seconds) {
        return cache.containsKey(key(title, artist, seconds));
    }

    @Nullable
    public static Lyrics cached(String title, String artist, int seconds) {
        return cache.get(key(title, artist, seconds));
    }

    /** Looks the lyrics up; {@code done} runs on the UI thread with them, or null when there are none. */
    public static void load(String title, String artist, int seconds, Utilities.Callback<Lyrics> done) {
        if (TextUtils.isEmpty(title)) {
            done.run(null);
            return;
        }
        final String k = key(title, artist, seconds);
        if (cache.containsKey(k)) {
            done.run(cache.get(k));
            return;
        }
        Utilities.globalQueue.postRunnable(() -> {
            final Search s = new Search(title, artist, seconds);
            Lyrics result = null;
            try {
                result = s.run();
            } catch (Throwable e) {
                FileLog.e(e);
            }
            final Lyrics r = result;
            // no source answered at all (no network): don't remember "nothing", try again next time
            final boolean remember = r != null || s.answered;
            AndroidUtilities.runOnUIThread(() -> {
                if (remember) {
                    cache.put(k, r);
                }
                done.run(r);
            });
        });
    }

    // ------------------------------------------------------------------ matching

    static String cleanTitle(String t) {
        t = EXTENSION.matcher(t.trim()).replaceAll("").trim();
        t = TRACK_NUMBER.matcher(t).replaceAll("");
        t = JUNK.matcher(t).replaceAll("");
        t = FEAT.matcher(t).replaceAll("");
        t = TAIL.matcher(t).replaceAll("");
        t = t.replaceAll("\\s+", " ").trim();
        int a = 0, b = t.length();
        while (a < b && " -_".indexOf(t.charAt(a)) >= 0) a++;
        while (b > a && " -_".indexOf(t.charAt(b - 1)) >= 0) b--;
        return t.substring(a, b);
    }

    static ArrayList<String> artists(String a) {
        final ArrayList<String> out = new ArrayList<>();
        if (a == null) {
            return out;
        }
        for (String x : ARTIST_SPLIT.split(a)) {
            x = x.trim();
            if (!x.isEmpty()) {
                out.add(x);
            }
        }
        return out;
    }

    /** Lower case, Cyrillic spelled in Latin letters, only letters and digits (Джарахов = Dzharakhov). */
    static String norm(String s) {
        s = s.toLowerCase().replace('ё', 'е');
        final StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            final int j = CYRILLIC.indexOf(c);
            if (j >= 0) {
                b.append(LATIN[j]);
            } else {
                b.append(c);
            }
        }
        s = b.toString().replace('j', 'y').replace("x", "ks").replace("h", "");
        return NOT_LETTER.matcher(s).replaceAll("");
    }

    private static String bare(String t) {
        final String b = BRACKETS.matcher(t).replaceAll("").trim();
        return b.isEmpty() ? t : b;
    }

    static boolean titleOk(String want, String got) {
        if (got == null) {
            return false;
        }
        final String a = norm(bare(cleanTitle(want)));
        final String b = norm(bare(cleanTitle(got)));
        if (a.isEmpty() || b.isEmpty()) {
            return false;
        }
        if (a.equals(b)) {
            return true;
        }
        if (a.length() < 4 || b.length() < 4 || !a.contains(b) && !b.contains(a)) {
            return false;
        }
        // «Song 2» is another song than «Song»
        final String rest = a.length() > b.length() ? a.replace(b, "") : b.replace(a, "");
        return !rest.matches("\\d+");
    }

    static boolean artistOk(String want, String got) {
        if (TextUtils.isEmpty(want)) {
            return true;
        }
        if (got == null) {
            return false;
        }
        final ArrayList<String> gs = new ArrayList<>();
        for (String g : artists(got)) {
            gs.add(norm(g));
        }
        gs.add(norm(got));
        for (String w0 : artists(want)) {
            final String w = norm(w0);
            if (w.isEmpty()) {
                continue;
            }
            for (String g : gs) {
                if (!g.isEmpty() && (w.equals(g) || w.length() >= 4 && (g.contains(w) || w.contains(g) && g.length() >= 4))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** One lookup: the cleaned names, the best lyrics found so far. */
    private static final class Search {
        final String title, artist, first;
        final int seconds;
        boolean answered;
        Lyrics best;
        double bestRank = Double.MAX_VALUE;

        Search(String rawTitle, String rawArtist, int seconds) {
            String t = cleanTitle(rawTitle);
            String a = rawArtist == null ? "" : rawArtist.trim();
            final String al = a.toLowerCase();
            if (al.equals("unknown") || al.equals("unknown artist") || al.equals("<unknown>") || al.equals("неизвестный исполнитель")) {
                a = "";
            }
            final int dash = t.indexOf(" - ");
            if (a.isEmpty() && dash > 0) {
                a = t.substring(0, dash).trim();
                t = cleanTitle(t.substring(dash + 3));
            }
            if (t.isEmpty()) {
                t = rawTitle.trim();
            }
            title = t;
            artist = a;
            final ArrayList<String> list = artists(a);
            first = list.isEmpty() ? "" : list.get(0);
            this.seconds = seconds;
        }

        /** Lower is better: synced and close in length, then synced a bit off, then plain/far, then instrumental. */
        private void offer(Lyrics l, double diff) {
            if (l == null) {
                return;
            }
            double rank;
            if (l.synced && diff <= 8) {
                rank = diff / 100;
            } else if (l.synced && diff <= 20) {
                rank = 1 + diff / 100;
            } else if (!l.lines.isEmpty()) {
                rank = 2;
                if (l.synced) {
                    l = l.plain();
                }
            } else {
                rank = 2.5;
            }
            if (rank < bestRank) {
                bestRank = rank;
                best = l;
            }
        }

        private boolean good() {
            return bestRank < 1;
        }

        private double diff(double duration) {
            return seconds > 0 && duration > 0 ? Math.abs(duration - seconds) : 0;
        }

        @Nullable
        private String get(String url, String agent) {
            try {
                final String body = fetch(url, agent);
                if (body != null) {
                    answered = true;
                }
                return body;
            } catch (Exception e) {
                return null;
            }
        }

        Lyrics run() {
            lrclib();
            if (!good()) {
                netease();
            }
            if (best == null) {
                ovh();
            }
            return best;
        }

        private void lrclibOne(JSONObject o) {
            if (o == null || !titleOk(title, o.optString("trackName")) || !artistOk(artist, o.optString("artistName"))) {
                return;
            }
            offer(parse(o), diff(o.optDouble("duration", 0)));
        }

        private void lrclib() {
            try {
                if (!artist.isEmpty()) {
                    final String body = get(API + "get?track_name=" + enc(title) + "&artist_name=" + enc(artist) + (seconds > 0 ? "&duration=" + seconds : ""), AGENT);
                    if (!TextUtils.isEmpty(body)) {
                        lrclibOne(new JSONObject(body));
                    }
                    if (good()) {
                        return;
                    }
                }
                final ArrayList<String> queries = new ArrayList<>();
                if (!first.isEmpty()) {
                    queries.add("track_name=" + enc(title) + "&artist_name=" + enc(first));
                }
                queries.add("q=" + enc((first.isEmpty() ? "" : first + " ") + title));
                if (!first.isEmpty()) {
                    queries.add("track_name=" + enc(title));
                }
                for (String q : queries) {
                    final String body = get(API + "search?" + q, AGENT);
                    if (!TextUtils.isEmpty(body)) {
                        final JSONArray a = new JSONArray(body);
                        for (int i = 0; i < a.length() && i < 20; i++) {
                            lrclibOne(a.optJSONObject(i));
                        }
                    }
                    if (good()) {
                        return;
                    }
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
        }

        private void netease() {
            try {
                final String body = get(NETEASE + "search/get?s=" + enc((first.isEmpty() ? "" : first + " ") + title) + "&type=1&limit=10", BROWSER);
                if (TextUtils.isEmpty(body)) {
                    return;
                }
                final JSONObject result = new JSONObject(body).optJSONObject("result");
                final JSONArray songs = result != null ? result.optJSONArray("songs") : null;
                if (songs == null) {
                    return;
                }
                final ArrayList<JSONObject> matching = new ArrayList<>();
                for (int i = 0; i < songs.length(); i++) {
                    final JSONObject so = songs.optJSONObject(i);
                    if (so == null || !titleOk(title, so.optString("name"))) {
                        continue;
                    }
                    final JSONArray ar = so.optJSONArray("artists");
                    final StringBuilder names = new StringBuilder();
                    for (int j = 0; ar != null && j < ar.length(); j++) {
                        final JSONObject x = ar.optJSONObject(j);
                        if (x != null) {
                            if (names.length() > 0) names.append(" & ");
                            names.append(x.optString("name"));
                        }
                    }
                    if (artistOk(artist, names.toString())) {
                        matching.add(so);
                    }
                }
                java.util.Collections.sort(matching, (a, b) -> Double.compare(diff(a.optDouble("duration", 0) / 1000), diff(b.optDouble("duration", 0) / 1000)));
                for (int i = 0; i < matching.size() && i < 2; i++) {
                    final JSONObject so = matching.get(i);
                    final String lb = get(NETEASE + "song/lyric?id=" + so.optLong("id") + "&lv=1", BROWSER);
                    if (TextUtils.isEmpty(lb)) {
                        continue;
                    }
                    final JSONObject lrc = new JSONObject(lb).optJSONObject("lrc");
                    final String text = lrc != null ? lrc.optString("lyric") : "";
                    if (TextUtils.isEmpty(text) || text.contains("纯音乐")) {
                        continue;
                    }
                    final Lyrics l = parseText(text, true);
                    if (l != null && l.lines.size() >= 4) {
                        offer(l, diff(so.optDouble("duration", 0) / 1000));
                        if (good()) {
                            return;
                        }
                    }
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
        }

        private void ovh() {
            if (first.isEmpty()) {
                return;
            }
            try {
                final String body = get(OVH + enc(first).replace("+", "%20") + "/" + enc(title).replace("+", "%20"), AGENT);
                if (TextUtils.isEmpty(body)) {
                    return;
                }
                final String text = new JSONObject(body).optString("lyrics");
                if (!TextUtils.isEmpty(text) && !text.trim().isEmpty()) {
                    offer(parseText(text.replace("\r\n", "\n").trim(), false), 0);
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
    }

    private static Lyrics parse(JSONObject o) {
        final boolean instrumental = o.optBoolean("instrumental");
        String text = o.optString("syncedLyrics");
        if (TextUtils.isEmpty(text) || "null".equals(text)) {
            text = o.optString("plainLyrics");
        }
        Lyrics l = !TextUtils.isEmpty(text) && !"null".equals(text) ? parseText(text, false) : null;
        if (l == null && instrumental) {
            l = new Lyrics();
        }
        if (l != null) {
            l.instrumental = instrumental;
        }
        return l;
    }

    /** LRC (timed lines) or plain text; null when there are no lines. */
    @Nullable
    private static Lyrics parseText(String text, boolean dropCredits) {
        final Lyrics l = new Lyrics();
        final String[] rows = text.split("\n");
        boolean timed = false;
        for (String raw : rows) {
            final Matcher m = TAG.matcher(raw);
            if (m.find() && m.start() == 0) {
                timed = true;
                break;
            }
        }
        if (timed) {
            l.synced = true;
            for (String raw : rows) {
                final Matcher m = TAG.matcher(raw);
                final ArrayList<Long> times = new ArrayList<>();
                int end = 0;
                while (m.find() && m.start() == end) {
                    final long min = Long.parseLong(m.group(1));
                    final long sec = Long.parseLong(m.group(2));
                    long frac = 0;
                    if (m.group(3) != null) {
                        final String f = m.group(3);
                        frac = Long.parseLong(f) * (f.length() == 1 ? 100 : f.length() == 2 ? 10 : 1);
                    }
                    times.add(min * 60_000L + sec * 1000L + frac);
                    end = m.end();
                }
                final String line = raw.substring(end).trim();
                if (dropCredits && CREDIT.matcher(line).find()) {
                    continue;
                }
                for (long t : times) {
                    l.lines.add(new Line(t, line));
                }
            }
            java.util.Collections.sort(l.lines, (a, b) -> Long.compare(a.time, b.time));
            // leading empty lines say nothing
            while (!l.lines.isEmpty() && l.lines.get(0).text.isEmpty()) {
                l.lines.remove(0);
            }
        } else {
            for (String raw : rows) {
                final String line = ANY_TAG.matcher(raw).replaceAll("").trim();
                if (dropCredits && CREDIT.matcher(line).find()) {
                    continue;
                }
                l.lines.add(new Line(-1, line));
            }
            while (!l.lines.isEmpty() && l.lines.get(0).text.isEmpty()) {
                l.lines.remove(0);
            }
        }
        return l.lines.isEmpty() ? null : l;
    }

    private static String enc(String s) throws Exception {
        return URLEncoder.encode(s, "UTF-8");
    }

    @Nullable
    /** The answer's body; "" when the source has nothing for it (404); null when it could not be asked. */
    private static String fetch(String url, String agent) throws Exception {
        final HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(8000);
        c.setRequestProperty("User-Agent", agent);
        c.setRequestProperty("Accept", "application/json");
        try {
            final int code = c.getResponseCode();
            if (code == 404) {
                return "";
            }
            if (code != 200) {
                return null;
            }
            try (InputStream in = c.getInputStream()) {
                return SolegramNet.readString(in, 512 * 1024);
            }
        } finally {
            c.disconnect();
        }
    }
}
