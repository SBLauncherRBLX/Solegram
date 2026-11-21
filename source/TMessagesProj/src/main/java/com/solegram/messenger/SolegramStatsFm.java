/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger;

import android.os.SystemClock;
import android.text.TextUtils;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.Utilities;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.regex.Pattern;

/**
 * Solegram: what plays now, from stats.fm (a service that follows a Spotify or Apple Music account). The user links
 * their stats.fm profile by its name; the profile shows the track in the music's line, as long as the stats.fm profile
 * lets others see what it plays. The name goes on the own Solegram card, so others with Solegram see it too (each
 * phone asks stats.fm itself; nothing else is sent anywhere).
 */
public final class SolegramStatsFm {

    private SolegramStatsFm() {
    }

    private static final String API = "https://api.stats.fm/api/v1/users/";
    /** A stats.fm name or id: letters, digits, '_', '.', '-'. */
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_.\\-]{1,40}");
    /** How long an answer stays fresh: the profile asks again after it. */
    private static final long FRESH_MS = 20_000;

    /** A track playing now. */
    public static final class Track {
        public final String id, title, artist;
        @Nullable
        public final String cover;
        public final long durationMs, progressMs;
        public final boolean playing;
        /** elapsedRealtime of the answer. */
        public final long at;

        Track(String id, String title, String artist, @Nullable String cover, long durationMs, long progressMs, boolean playing) {
            this.id = id;
            this.title = title;
            this.artist = artist;
            this.cover = cover;
            this.durationMs = durationMs;
            this.progressMs = progressMs;
            this.playing = playing;
            this.at = SystemClock.elapsedRealtime();
        }

        /** 0..1 now (it moves on by itself while it plays), negative when the length is not known. */
        public float progress() {
            if (durationMs <= 0) {
                return -1f;
            }
            final long now = progressMs + (playing ? SystemClock.elapsedRealtime() - at : 0);
            return Math.max(0f, Math.min(1f, now / (float) durationMs));
        }

        /** Over by now (the next answer may have the next track). */
        public boolean ended() {
            return durationMs > 0 && playing && progressMs + SystemClock.elapsedRealtime() - at >= durationMs + 1500;
        }

        public String url() {
            return "https://stats.fm/track/" + id;
        }
    }

    private static final class Entry {
        @Nullable
        Track track;
        long at;
        boolean loading;
        final ArrayList<Utilities.Callback<Track>> waiting = new ArrayList<>();
    }

    private static final HashMap<String, Entry> cache = new HashMap<>();

    /** The linked stats.fm name, cleaned; null when it is not one. */
    @Nullable
    public static String clean(@Nullable String id) {
        if (id == null) {
            return null;
        }
        String s = id.trim();
        // a pasted link: stats.fm/user/<name> or stats.fm/<name>
        final int slash = s.lastIndexOf('/');
        if (s.contains("stats.fm") && slash >= 0 && slash < s.length() - 1) {
            s = s.substring(slash + 1);
        }
        if (s.startsWith("@")) {
            s = s.substring(1);
        }
        final int q = s.indexOf('?');
        if (q >= 0) {
            s = s.substring(0, q);
        }
        return ID.matcher(s).matches() ? s : null;
    }

    public static String profileUrl(String id) {
        return "https://stats.fm/" + id;
    }

    /** The last known track of {@code id} (null: nothing plays, or not asked yet). UI thread. */
    @Nullable
    public static Track cached(@Nullable String id) {
        final Entry e = id != null ? cache.get(id.toLowerCase()) : null;
        return e != null ? e.track : null;
    }

    /**
     * What {@code id} plays now: from the cache while it is fresh, else asked from stats.fm. {@code done} gets the
     * track or null (nothing plays, hidden, no such name, no network). UI thread, done on the UI thread.
     */
    public static void current(@Nullable String id, Utilities.Callback<Track> done) {
        final String cleanId = clean(id);
        if (cleanId == null) {
            done.run(null);
            return;
        }
        final String key = cleanId.toLowerCase();
        Entry e = cache.get(key);
        if (e == null) {
            cache.put(key, e = new Entry());
        }
        final long now = SystemClock.elapsedRealtime();
        if (!e.loading && e.at != 0 && now - e.at < FRESH_MS && (e.track == null || !e.track.ended())) {
            done.run(e.track);
            return;
        }
        e.waiting.add(done);
        if (e.loading) {
            return;
        }
        e.loading = true;
        final Entry entry = e;
        Utilities.globalQueue.postRunnable(() -> {
            Track track = null;
            try {
                track = parseCurrent(httpGet(API + URLEncoder.encode(cleanId, "UTF-8") + "/streams/current"));
            } catch (Throwable t) {
                FileLog.e(t);
            }
            final Track result = track;
            AndroidUtilities.runOnUIThread(() -> {
                entry.loading = false;
                entry.track = result;
                entry.at = SystemClock.elapsedRealtime();
                final ArrayList<Utilities.Callback<Track>> list = new ArrayList<>(entry.waiting);
                entry.waiting.clear();
                for (Utilities.Callback<Track> c : list) {
                    c.run(result);
                }
            });
        });
    }

    /** Checks a stats.fm name: {@code done} gets its display name, "" for a profile without one, null for no such profile. */
    public static void check(@Nullable String id, Utilities.Callback<String> done) {
        final String cleanId = clean(id);
        if (cleanId == null) {
            done.run(null);
            return;
        }
        Utilities.globalQueue.postRunnable(() -> {
            String name = null;
            try {
                final JSONObject root = new JSONObject(httpGet(API + URLEncoder.encode(cleanId, "UTF-8")));
                final JSONObject item = root.optJSONObject("item");
                if (item != null) {
                    name = item.optString("displayName", "");
                }
            } catch (Throwable t) {
                FileLog.e(t);
            }
            final String result = name;
            AndroidUtilities.runOnUIThread(() -> done.run(result));
        });
    }

    /** The answer of streams/current: {"item": {"isPlaying", "progressMs", "track": {...}}} or {"item": null}. */
    @Nullable
    static Track parseCurrent(String json) throws Exception {
        final JSONObject item = new JSONObject(json).optJSONObject("item");
        if (item == null) {
            return null;
        }
        final JSONObject track = item.optJSONObject("track");
        if (track == null) {
            return null;
        }
        final String title = track.optString("name", "");
        if (TextUtils.isEmpty(title)) {
            return null;
        }
        final StringBuilder artists = new StringBuilder();
        final JSONArray a = track.optJSONArray("artists");
        if (a != null) {
            for (int i = 0; i < a.length() && i < 3; i++) {
                final JSONObject artist = a.optJSONObject(i);
                final String name = artist != null ? artist.optString("name", "") : "";
                if (!TextUtils.isEmpty(name)) {
                    if (artists.length() > 0) {
                        artists.append(", ");
                    }
                    artists.append(name);
                }
            }
        }
        String cover = null;
        final JSONArray albums = track.optJSONArray("albums");
        if (albums != null && albums.length() > 0 && albums.optJSONObject(0) != null) {
            cover = albums.optJSONObject(0).optString("image", null);
        }
        long duration = track.optLong("durationMs", 0);
        if (duration <= 0) {
            duration = item.optLong("durationMs", 0);
        }
        return new Track(String.valueOf(track.opt("id")), title, artists.toString(), cover, duration,
            item.optLong("progressMs", 0), item.optBoolean("isPlaying", true));
    }

    private static String httpGet(String address) throws Exception {
        final HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("User-Agent", "Solegram");
        c.setRequestProperty("Accept", "application/json");
        try {
            final int code = c.getResponseCode();
            if (code == 404) {
                return "{\"item\":null}";
            }
            if (code != 200) {
                throw new java.io.IOException("stats.fm: HTTP " + code);
            }
            try (InputStream in = c.getInputStream()) {
                final ByteArrayOutputStream out = new ByteArrayOutputStream();
                final byte[] buf = new byte[8192];
                int r, total = 0;
                while ((r = in.read(buf)) > 0) {
                    total += r;
                    if (total > 512 * 1024) {
                        throw new java.io.IOException("stats.fm: too long");
                    }
                    out.write(buf, 0, r);
                }
                return out.toString("UTF-8");
            }
        } finally {
            c.disconnect();
        }
    }
}
