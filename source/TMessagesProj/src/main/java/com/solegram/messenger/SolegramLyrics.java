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
 * Song lyrics from LRCLIB (lrclib.net, a free open lyrics library): synced lines when it has them, plain text
 * otherwise. Asked only when the lyrics are opened, with the track's title, artist and length; kept in memory.
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
    }

    private static final String API = "https://lrclib.net/api/";
    private static final String AGENT = "Solegram (https://github.com/SBLauncherRBLX/Solegram)";
    private static final Pattern TAG = Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]");

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
            Lyrics result = null;
            try {
                JSONObject best = null;
                if (!TextUtils.isEmpty(artist)) {
                    final String url = API + "get?track_name=" + enc(title) + "&artist_name=" + enc(artist) + (seconds > 0 ? "&duration=" + seconds : "");
                    final String body = fetch(url);
                    if (body != null) {
                        best = new JSONObject(body);
                    }
                }
                if (best == null || TextUtils.isEmpty(best.optString("syncedLyrics")) && TextUtils.isEmpty(best.optString("plainLyrics")) && !best.optBoolean("instrumental")) {
                    final String body = fetch(API + "search?q=" + enc((TextUtils.isEmpty(artist) ? "" : artist + " ") + title));
                    if (body != null) {
                        best = pick(new JSONArray(body), seconds);
                    }
                }
                if (best != null) {
                    result = parse(best);
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
            final Lyrics r = result;
            AndroidUtilities.runOnUIThread(() -> {
                cache.put(k, r);
                done.run(r);
            });
        });
    }

    /** From search results: synced first, then the closest in length. */
    @Nullable
    private static JSONObject pick(JSONArray results, int seconds) {
        JSONObject best = null;
        double bestScore = Double.MAX_VALUE;
        for (int i = 0; i < results.length() && i < 20; i++) {
            final JSONObject o = results.optJSONObject(i);
            if (o == null) {
                continue;
            }
            final boolean synced = !TextUtils.isEmpty(o.optString("syncedLyrics"));
            final boolean plain = !TextUtils.isEmpty(o.optString("plainLyrics"));
            if (!synced && !plain) {
                continue;
            }
            final double diff = seconds > 0 ? Math.abs(o.optDouble("duration", seconds) - seconds) : 0;
            if (seconds > 0 && diff > 8) {
                continue;
            }
            final double score = diff + (synced ? 0 : 30);
            if (score < bestScore) {
                bestScore = score;
                best = o;
            }
        }
        return best;
    }

    private static Lyrics parse(JSONObject o) {
        final Lyrics l = new Lyrics();
        l.instrumental = o.optBoolean("instrumental");
        final String synced = o.optString("syncedLyrics");
        if (!TextUtils.isEmpty(synced) && !"null".equals(synced)) {
            l.synced = true;
            for (String raw : synced.split("\n")) {
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
                final String text = raw.substring(end).trim();
                for (long t : times) {
                    l.lines.add(new Line(t, text));
                }
            }
            java.util.Collections.sort(l.lines, (a, b) -> Long.compare(a.time, b.time));
        } else {
            final String plain = o.optString("plainLyrics");
            if (!TextUtils.isEmpty(plain) && !"null".equals(plain)) {
                for (String raw : plain.split("\n")) {
                    l.lines.add(new Line(-1, raw.trim()));
                }
            }
        }
        return l.lines.isEmpty() && !l.instrumental ? null : l;
    }

    private static String enc(String s) throws Exception {
        return URLEncoder.encode(s, "UTF-8");
    }

    @Nullable
    private static String fetch(String url) throws Exception {
        final HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        c.setRequestProperty("User-Agent", AGENT);
        c.setRequestProperty("Accept", "application/json");
        try {
            if (c.getResponseCode() != 200) {
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
