/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationManagerCompat;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.NotificationCenter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Music in the profile: what plays now in Spotify or SoundCloud on this phone, read from the players' media
 * sessions (Android shows them to an app with notification access, see SolegramMediaListener). Nothing leaves
 * the device unless the user turns on sharing in the bio (SolegramNowPlayingBio).
 */
public final class SolegramNowPlaying {

    public static final int SOURCE_NONE = 0;
    public static final int SOURCE_SPOTIFY = 1;
    public static final int SOURCE_SOUNDCLOUD = 2;

    public static final String SPOTIFY = "com.spotify.music";
    public static final String SOUNDCLOUD = "com.soundcloud.android";

    /** The headphones that start the line in the bio while the music is shared. */
    public static final String BIO_MARK = "🎧";
    /** The headphones and an invisible word joiner: a bio that merely starts with 🎧 is not taken for music. */
    static final String BIO_TAG = BIO_MARK + "\u2060";
    private static final String BIO_SEPARATOR = " — ";

    /** A track as the profile card shows it. */
    public static final class Track {
        public final int source;
        public final String title, artist;
        /** Total length and the position at {@link #positionTime} (elapsed realtime), in milliseconds; 0 when unknown. */
        public final long duration, position, positionTime;
        public final float speed;
        public final boolean playing;
        /** The cover (scaled down), null when the player gives none or the track is someone else's. */
        @Nullable
        public final Bitmap art;
        /** The cover's main colour, or a colour made from the title when there is no cover. */
        public final int color;
        /** Someone else's track, read from their bio: no cover, no progress. */
        public final boolean remote;

        Track(int source, String title, String artist, long duration, long position, long positionTime, float speed,
              boolean playing, @Nullable Bitmap art, int color, boolean remote) {
            this.source = source;
            this.title = title;
            this.artist = artist;
            this.duration = duration;
            this.position = position;
            this.positionTime = positionTime;
            this.speed = speed;
            this.playing = playing;
            this.art = art;
            this.color = color;
            this.remote = remote;
        }

        public long currentPosition() {
            long p = position;
            if (playing && positionTime > 0) {
                p += (long) ((SystemClock.elapsedRealtime() - positionTime) * (speed > 0 ? speed : 1f));
            }
            if (duration > 0) {
                p = Math.min(p, duration);
            }
            return Math.max(0, p);
        }

        boolean sameSong(@Nullable Track o) {
            return o != null && o.source == source && TextUtils.equals(o.title, title) && TextUtils.equals(o.artist, artist);
        }

        boolean sameShown(@Nullable Track o) {
            if (!sameSong(o) || o.playing != playing || o.art != art || o.duration != duration) {
                return false;
            }
            // a seek, not just time passing
            return Math.abs(o.currentPosition() - currentPosition()) < 1500;
        }
    }

    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static MediaSessionManager manager;
    private static MediaSessionManager.OnActiveSessionsChangedListener sessionsListener;
    private static final Map<MediaController, MediaController.Callback> callbacks = new HashMap<>();
    private static Track current;
    private static boolean started;
    /** When each player was first seen paused (elapsed realtime). */
    private static final java.util.WeakHashMap<MediaController, Long> pausedSince = new java.util.WeakHashMap<>();

    private SolegramNowPlaying() {
    }

    private static Context context() {
        return ApplicationLoader.applicationContext;
    }

    private static ComponentName listenerComponent() {
        return new ComponentName(context(), SolegramMediaListener.class);
    }

    /** The user let Solegram see the players (notification access). */
    public static boolean hasAccess() {
        try {
            return NotificationManagerCompat.getEnabledListenerPackages(context()).contains(context().getPackageName());
        } catch (Throwable e) {
            return false;
        }
    }

    public static boolean isInstalled(String packageName) {
        try {
            context().getPackageManager().getPackageInfo(packageName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        } catch (Throwable e) {
            return false;
        }
    }

    /** The system screen where notification access for Solegram is turned on. */
    public static Intent accessSettingsIntent() {
        if (Build.VERSION.SDK_INT >= 30) {
            final Intent intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS);
            intent.putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, listenerComponent().flattenToString());
            if (intent.resolveActivity(context().getPackageManager()) != null) {
                return intent;
            }
        }
        return new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
    }

    /** Opens the player app (for the own card). */
    public static boolean openPlayer(Context from, int source) {
        final String pkg = source == SOURCE_SOUNDCLOUD ? SOUNDCLOUD : SPOTIFY;
        try {
            final Intent intent = from.getPackageManager().getLaunchIntentForPackage(pkg);
            if (intent != null) {
                from.startActivity(intent);
                return true;
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return false;
    }

    public static String sourceName(int source) {
        return source == SOURCE_SOUNDCLOUD ? "SoundCloud" : "Spotify";
    }

    /** What the own profile shows now, or null. */
    @Nullable
    public static Track get() {
        if (!SolegramConfig.nowPlaying) {
            return null;
        }
        if (!started) {
            start();
        } else if (!hasAccess()) {
            stop(); // access was taken back in the system settings
        }
        return current;
    }

    // region Sessions

    /** Starts watching the players (when allowed and turned on); safe to call any number of times. */
    public static void start() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(SolegramNowPlaying::start);
            return;
        }
        if (started || !SolegramConfig.nowPlaying || !hasAccess()) {
            return;
        }
        try {
            manager = (MediaSessionManager) context().getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (manager == null) {
                return;
            }
            sessionsListener = SolegramNowPlaying::onSessionsChanged;
            manager.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent(), handler);
            started = true;
            onSessionsChanged(manager.getActiveSessions(listenerComponent()));
        } catch (Throwable e) {
            // access was taken back between the check and the call
            FileLog.e(e);
            stop();
        }
    }

    public static void stop() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(SolegramNowPlaying::stop);
            return;
        }
        if (manager != null && sessionsListener != null) {
            try {
                manager.removeOnActiveSessionsChangedListener(sessionsListener);
            } catch (Throwable ignore) {
            }
        }
        sessionsListener = null;
        for (Map.Entry<MediaController, MediaController.Callback> e : callbacks.entrySet()) {
            try {
                e.getKey().unregisterCallback(e.getValue());
            } catch (Throwable ignore) {
            }
        }
        callbacks.clear();
        started = false;
        setCurrent(null);
    }

    /** After the settings changed: start, stop or pick again. */
    public static void onSettingsChanged() {
        SolegramMediaListener.setWanted(SolegramConfig.nowPlaying);
        if (!SolegramConfig.nowPlaying) {
            stop();
            return;
        }
        if (!started) {
            start();
        } else {
            refresh();
        }
        SolegramNowPlayingBio.onTrackChanged(current);
    }

    private static boolean watched(String packageName) {
        return SPOTIFY.equals(packageName) && SolegramConfig.nowPlayingSpotify
            || SOUNDCLOUD.equals(packageName) && SolegramConfig.nowPlayingSoundCloud;
    }

    private static void onSessionsChanged(@Nullable List<MediaController> controllers) {
        final ArrayList<MediaController> keep = new ArrayList<>();
        if (controllers != null) {
            for (MediaController c : controllers) {
                if (c != null && (SPOTIFY.equals(c.getPackageName()) || SOUNDCLOUD.equals(c.getPackageName()))) {
                    keep.add(c);
                }
            }
        }
        // sessions are new objects each time: drop the old callbacks, follow the current ones
        for (Map.Entry<MediaController, MediaController.Callback> e : callbacks.entrySet()) {
            try {
                e.getKey().unregisterCallback(e.getValue());
            } catch (Throwable ignore) {
            }
        }
        callbacks.clear();
        for (MediaController c : keep) {
            final MediaController.Callback[] self = new MediaController.Callback[1];
            final MediaController.Callback callback = self[0] = new MediaController.Callback() {
                @Override
                public void onPlaybackStateChanged(@Nullable PlaybackState state) {
                    refresh();
                }

                @Override
                public void onMetadataChanged(@Nullable MediaMetadata metadata) {
                    refresh();
                }

                @Override
                public void onSessionDestroyed() {
                    // the player closed its session: its last track must not stay on the card
                    try {
                        c.unregisterCallback(self[0]);
                    } catch (Throwable ignore) {
                    }
                    callbacks.remove(c);
                    refresh();
                }
            };
            try {
                c.registerCallback(callback, handler);
                callbacks.put(c, callback);
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
        refresh();
    }

    /** The playing player wins; else the one paused last keeps its card. */
    /** A paused song keeps its card this long after the pause, then the card goes. */
    private static final long PAUSED_KEEP_MS = 3 * 60_000;
    private static final Runnable refreshLater = SolegramNowPlaying::refresh;

    private static void refresh() {
        handler.removeCallbacks(refreshLater);
        long nextCheck = Long.MAX_VALUE;
        MediaController best = null;
        MediaMetadata bestMetadata = null;
        PlaybackState bestState = null;
        boolean bestPlaying = false;
        for (MediaController c : callbacks.keySet()) {
            if (!watched(c.getPackageName())) {
                continue;
            }
            final MediaMetadata metadata;
            final PlaybackState state;
            try {
                metadata = c.getMetadata();
                state = c.getPlaybackState();
            } catch (Throwable e) {
                continue;
            }
            if (metadata == null || TextUtils.isEmpty(metadata.getString(MediaMetadata.METADATA_KEY_TITLE))) {
                continue;
            }
            final boolean playing = state != null && (state.getState() == PlaybackState.STATE_PLAYING || state.getState() == PlaybackState.STATE_BUFFERING);
            if (playing) {
                pausedSince.remove(c);
            }
            if (!playing) {
                // stopped, or paused a while ago: nothing is being listened to any more
                if (state == null || state.getState() != PlaybackState.STATE_PAUSED) {
                    continue;
                }
                Long seen = pausedSince.get(c);
                if (seen == null) {
                    // the pause as the player dates it, but never later than when it was first seen here: players
                    // that keep refreshing a paused state kept the card for hours
                    final long byPlayer = state.getLastPositionUpdateTime() > 0 ? state.getLastPositionUpdateTime() : SystemClock.elapsedRealtime() - PAUSED_KEEP_MS;
                    seen = Math.min(SystemClock.elapsedRealtime(), byPlayer);
                    pausedSince.put(c, seen);
                }
                final long since = SystemClock.elapsedRealtime() - seen;
                if (since >= PAUSED_KEEP_MS) {
                    continue;
                }
                nextCheck = Math.min(nextCheck, PAUSED_KEEP_MS - since + 1000);
            }
            if (best == null || playing && !bestPlaying) {
                best = c;
                bestMetadata = metadata;
                bestState = state;
                bestPlaying = playing;
            }
        }
        Track track = null;
        if (best != null) {
            try {
                track = trackOf(best, bestMetadata, bestState);
            } catch (Throwable e) {
                // a player handing over broken metadata: keep what is shown
                FileLog.e(e);
                return;
            }
        }
        if (nextCheck != Long.MAX_VALUE && started) {
            handler.postDelayed(refreshLater, nextCheck); // the paused card goes when its time is up
        }
        setCurrent(track);
    }

    /** The track from what was read once (the player may change it between two reads). */
    private static Track trackOf(MediaController c, MediaMetadata m, @Nullable PlaybackState state) {
        final int source = SOUNDCLOUD.equals(c.getPackageName()) ? SOURCE_SOUNDCLOUD : SOURCE_SPOTIFY;
        final String title = m.getString(MediaMetadata.METADATA_KEY_TITLE);
        String artist = m.getString(MediaMetadata.METADATA_KEY_ARTIST);
        if (TextUtils.isEmpty(artist)) {
            artist = m.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
        }
        if (artist == null) {
            artist = "";
        }
        final long duration = Math.max(0, m.getLong(MediaMetadata.METADATA_KEY_DURATION));
        final boolean playing = state != null && (state.getState() == PlaybackState.STATE_PLAYING || state.getState() == PlaybackState.STATE_BUFFERING);
        final long position = state != null ? Math.max(0, state.getPosition()) : 0;
        final long positionTime = state != null ? state.getLastPositionUpdateTime() : 0;
        final float speed = state != null && state.getPlaybackSpeed() > 0 ? state.getPlaybackSpeed() : 1f;

        Bitmap art = null;
        int color;
        final Track previous = current;
        if (previous != null && previous.art != null && previous.sameSong(new Track(source, title, artist, 0, 0, 0, 1, false, null, 0, false))) {
            art = previous.art; // the same song: keep the scaled cover (and the card does not blink)
            color = previous.color;
        } else {
            final Bitmap raw = coverOf(m);
            if (raw != null && (art = scaled(raw, 320)) != null) {
                color = mainColor(art);
            } else {
                color = colorFor(title + artist);
            }
        }
        return new Track(source, title, artist, duration, position, positionTime, speed, playing, art, color, false);
    }

    @Nullable
    private static Bitmap coverOf(MediaMetadata m) {
        final String[] keys = {MediaMetadata.METADATA_KEY_ALBUM_ART, MediaMetadata.METADATA_KEY_ART, MediaMetadata.METADATA_KEY_DISPLAY_ICON};
        for (String key : keys) {
            try {
                final Bitmap b = m.getBitmap(key);
                if (b != null && !b.isRecycled() && b.getWidth() > 0) {
                    return b;
                }
            } catch (Throwable ignore) {
            }
        }
        return null;
    }

    private static Bitmap scaled(Bitmap b, int max) {
        try {
            Bitmap source = b;
            if (Build.VERSION.SDK_INT >= 26 && b.getConfig() == Bitmap.Config.HARDWARE) {
                source = b.copy(Bitmap.Config.ARGB_8888, false); // hardware bitmaps cannot be scaled or read
            }
            final int w = source.getWidth(), h = source.getHeight();
            final float s = Math.min(1f, max / (float) Math.max(w, h));
            return Bitmap.createScaledBitmap(source, Math.max(1, Math.round(w * s)), Math.max(1, Math.round(h * s)), true);
        } catch (Throwable e) {
            return null;
        }
    }

    /** The cover's average colour, a little livelier, for the card's tint. */
    public static int mainColor(Bitmap b) {
        try {
            final Bitmap one = Bitmap.createScaledBitmap(b, 1, 1, true);
            final int c = one.getPixel(0, 0);
            final float[] hsv = new float[3];
            Color.colorToHSV(c | 0xFF000000, hsv);
            hsv[1] = Math.min(1f, hsv[1] * 1.25f + 0.05f);
            hsv[2] = Math.max(0.35f, Math.min(0.9f, hsv[2]));
            return Color.HSVToColor(hsv);
        } catch (Throwable e) {
            return 0xFF7E57C2;
        }
    }

    /** A stable, pleasant colour for a track without a cover. */
    public static int colorFor(String key) {
        final float hue = ((key == null ? 0 : key.hashCode()) & 0x7FFFFFFF) % 360;
        return Color.HSVToColor(new float[]{hue, 0.55f, 0.78f});
    }

    private static void setCurrent(@Nullable Track track) {
        final Track was = current;
        if (track == null ? was == null : track.sameShown(was)) {
            if (track != null) {
                current = track; // the newest position
            }
            return;
        }
        current = track;
        // posted, not sent right away: a profile asking for the track while building its rows must not rebuild them inside
        handler.post(() -> NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.solegramNowPlayingChanged));
        try {
            SolegramNowPlayingBio.onTrackChanged(track);
        } catch (Throwable e) {
            // sharing in the bio must never stop the player being watched (start() stops everything on an error)
            FileLog.e(e);
        }
    }
    // endregion

    // region The bio line

    /** The line the bio gets while the music is shared: «🎧 Artist — Title», cut to the bio limit. */
    public static String bioLine(Track t, int limit) {
        final String head = BIO_TAG + " ";
        String artist = t.artist == null ? "" : t.artist.trim();
        String title = t.title == null ? "" : t.title.trim();
        String line = head + (artist.isEmpty() ? title : artist + BIO_SEPARATOR + title);
        if (line.codePointCount(0, line.length()) <= limit) {
            return line;
        }
        // the title is cut first, the artist only if it alone is too long
        final int room = limit - (head + artist + BIO_SEPARATOR).codePointCount(0, (head + artist + BIO_SEPARATOR).length()) - 1;
        if (!artist.isEmpty() && room >= 8) {
            return head + artist + BIO_SEPARATOR + cut(title, room) + "…";
        }
        return cut(line, limit - 1) + "…";
    }

    private static String cut(String s, int codePoints) {
        if (codePoints <= 0) {
            return "";
        }
        if (s.codePointCount(0, s.length()) <= codePoints) {
            return s;
        }
        return s.substring(0, s.offsetByCodePoints(0, codePoints)).trim();
    }

    /** The track in someone's bio (the line Solegram writes, «🎧 Artist — Title»), or null. */
    @Nullable
    public static Track fromBio(@Nullable String about) {
        if (TextUtils.isEmpty(about)) {
            return null;
        }
        final Track hidden = fromHidden(about);
        if (hidden != null || about.indexOf(HIDDEN_START) >= 0) {
            return hidden; // a hidden track that is too old shows nothing
        }
        if (!about.contains(BIO_TAG)) {
            return null;
        }
        for (String line : about.split("\n")) {
            final String l = line.trim();
            if (!l.startsWith(BIO_TAG)) {
                continue;
            }
            String rest = l.substring(BIO_TAG.length()).trim();
            if (rest.isEmpty()) {
                continue;
            }
            String artist = "", title = rest;
            final int sep = rest.indexOf(BIO_SEPARATOR.trim());
            if (sep > 0) {
                artist = rest.substring(0, sep).trim();
                title = rest.substring(sep + 1).trim();
            }
            if (title.isEmpty()) {
                continue;
            }
            return remoteTrack(SOURCE_NONE, title, artist);
        }
        return null;
    }

    /** Someone else's track, with its cover when it was found already (it is looked up otherwise). */
    private static Track remoteTrack(int source, String title, String artist) {
        final Bitmap art = remoteArt(artist, title);
        return new Track(source, title, artist, 0, 0, 0, 1f, true, art, art != null ? mainColor(art) : colorFor(title + artist), true);
    }

    /** The bio without the music line (the card shows it), trimmed; empty when nothing else is there. */
    public static String stripBio(@Nullable String about) {
        about = stripHidden(about);
        if (about == null || !about.contains(BIO_TAG)) {
            return about;
        }
        final StringBuilder out = new StringBuilder();
        boolean removed = false;
        for (String line : about.split("\n", -1)) {
            // only the line Solegram wrote, the one the card shows
            if (!removed && fromBio(line) != null) {
                removed = true;
                continue;
            }
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append(line);
        }
        return out.toString().trim();
    }
    // endregion

    // region The hidden track

    /*
     * The track travels in the bio as invisible characters after the user's own text, so the bio reads the same in
     * Telegram and in other apps and only Solegram shows the music: an invisible times sign, then variation
     * selectors, four bits each. The bytes: three of time (minutes, so a track left behind by a closed app goes
     * away by itself), one of source, then the artist, a separator and the title in a one-byte alphabet (Latin,
     * Cyrillic, a few signs; anything else escaped as UTF-16).
     */
    static final char HIDDEN_START = '\u2062';
    private static final long STAMP_EPOCH_MIN = 28928160L; // 2025-01-01 00:00 UTC, in minutes
    /** How long a hidden track is shown after it was written (the sender writes it anew every few minutes). */
    private static final long HIDDEN_FRESH_MIN = 15;
    private static final int ESCAPE = 0xFE, SEP = 0x01, CUT = 0xC5; // a cut is the ellipsis itself
    private static final String SIGNS = "\u0401\u0451\u2014\u2013\u2019\u2026"; // Ё ё — – ’ … -> 0xC0..

    private static void encodeChar(java.io.ByteArrayOutputStream out, char c) {
        final int sign = SIGNS.indexOf(c);
        if (c >= 0x20 && c <= 0x7E) {
            out.write(c);
        } else if (c >= 0x0410 && c <= 0x044F) {
            out.write(0x80 + (c - 0x0410));
        } else if (sign >= 0) {
            out.write(0xC0 + sign);
        } else {
            out.write(ESCAPE);
            out.write(c >> 8);
            out.write(c & 0xFF);
        }
    }

    private static int encodedSize(char c) {
        return c >= 0x20 && c <= 0x7E || c >= 0x0410 && c <= 0x044F || SIGNS.indexOf(c) >= 0 ? 1 : 3;
    }

    /** As much of the text as fits into the bytes left (whole characters, a cut marked with an ellipsis). */
    private static void encodeText(java.io.ByteArrayOutputStream out, String text, int bytes) {
        int need = 0;
        for (int i = 0; i < text.length(); i++) {
            need += encodedSize(text.charAt(i));
        }
        if (need <= bytes) {
            for (int i = 0; i < text.length(); i++) {
                encodeChar(out, text.charAt(i));
            }
            return;
        }
        int used = 1; // the ellipsis
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            if (used + encodedSize(c) > bytes || Character.isHighSurrogate(c) && used + 6 > bytes) {
                break;
            }
            encodeChar(out, c);
            used += encodedSize(c);
        }
        out.write(CUT);
    }

    /**
     * The invisible block for this track, at most {@code room} characters long; null when even a short form does
     * not fit (a long bio of the user's own).
     */
    @Nullable
    static String hiddenBlock(Track t, int room) {
        final String artist = t.artist == null ? "" : t.artist.trim();
        final String title = t.title == null ? "" : t.title.trim();
        final int bytes = (room - 1) / 2 - 4; // the start sign; three bytes of time and one of source
        if (title.isEmpty() || bytes < 8) {
            return null;
        }
        final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        final long stamp = System.currentTimeMillis() / 60000L - STAMP_EPOCH_MIN;
        out.write((int) (stamp >> 16) & 0xFF);
        out.write((int) (stamp >> 8) & 0xFF);
        out.write((int) stamp & 0xFF);
        out.write(t.source & 0xFF);
        if (!artist.isEmpty()) {
            // the title keeps at least half of the room (and no less than six bytes)
            encodeText(out, artist, Math.max(3, Math.min(bytes - 1 - Math.max(6, bytes / 2), bytes - 7)));
            out.write(SEP);
        }
        encodeText(out, title, bytes - (out.size() - 4));
        final byte[] data = out.toByteArray();
        final StringBuilder sb = new StringBuilder(1 + data.length * 2);
        sb.append(HIDDEN_START);
        for (byte b : data) {
            sb.append((char) (0xFE00 + ((b >> 4) & 0xF))).append((char) (0xFE00 + (b & 0xF)));
        }
        return sb.toString();
    }

    private static int hiddenEnd(String about, int start) {
        int j = start + 1;
        while (j < about.length() && about.charAt(j) >= 0xFE00 && about.charAt(j) <= 0xFE0F) {
            j++;
        }
        return j;
    }

    /** The track hidden in a bio, null when there is none or it was written too long ago. */
    @Nullable
    private static Track fromHidden(String about) {
        final int at = about.lastIndexOf(HIDDEN_START);
        if (at < 0) {
            return null;
        }
        final int end = hiddenEnd(about, at);
        final int n = (end - at - 1) / 2;
        if (n < 6) {
            return null;
        }
        final int[] data = new int[n];
        for (int i = 0; i < n; i++) {
            data[i] = ((about.charAt(at + 1 + i * 2) - 0xFE00) << 4) | (about.charAt(at + 2 + i * 2) - 0xFE00);
        }
        final long stamp = ((long) data[0] << 16) | ((long) data[1] << 8) | data[2];
        final long age = System.currentTimeMillis() / 60000L - STAMP_EPOCH_MIN - stamp;
        if (age > HIDDEN_FRESH_MIN || age < -HIDDEN_FRESH_MIN) {
            return null;
        }
        final StringBuilder artist = new StringBuilder(), title = new StringBuilder();
        StringBuilder cur = title;
        boolean hasArtist = false;
        for (int i = 4; i < n; i++) {
            if (data[i] == SEP) {
                hasArtist = true;
                break;
            }
        }
        if (hasArtist) {
            cur = artist;
        }
        for (int i = 4; i < n; i++) {
            final int b = data[i];
            if (b == SEP) {
                cur = title;
            } else if (b == ESCAPE) {
                if (i + 2 >= n) {
                    break;
                }
                cur.append((char) ((data[i + 1] << 8) | data[i + 2]));
                i += 2;
            } else if (b >= 0x20 && b <= 0x7E) {
                cur.append((char) b);
            } else if (b >= 0x80 && b <= 0xBF) {
                cur.append((char) (0x0410 + b - 0x80));
            } else if (b >= 0xC0 && b < 0xC0 + SIGNS.length()) {
                cur.append(SIGNS.charAt(b - 0xC0));
            }
        }
        if (title.length() == 0) {
            return null;
        }
        final int source = data[3] == SOURCE_SPOTIFY || data[3] == SOURCE_SOUNDCLOUD ? data[3] : SOURCE_NONE;
        return remoteTrack(source, title.toString(), artist.toString());
    }

    /** The bio without the hidden track. */
    public static String stripHidden(@Nullable String about) {
        if (about == null || about.indexOf(HIDDEN_START) < 0) {
            return about;
        }
        final StringBuilder out = new StringBuilder(about.length());
        for (int i = 0; i < about.length(); i++) {
            if (about.charAt(i) == HIDDEN_START) {
                i = hiddenEnd(about, i) - 1;
                continue;
            }
            out.append(about.charAt(i));
        }
        return out.toString();
    }
    // endregion

    // region Covers of other people's tracks

    private static final android.util.LruCache<String, Bitmap> remoteArts = new android.util.LruCache<>(24);
    private static final java.util.HashSet<String> remoteArtAsked = new java.util.HashSet<>();

    private static String artKey(String artist, String title) {
        return (artist == null ? "" : artist.trim().toLowerCase()) + "\n" + (title == null ? "" : title.trim().toLowerCase());
    }

    /**
     * The cover of someone else's track: from the cache, or looked up once by artist and title (the same public
     * search Telegram uses for the covers of audio files); the profile is told when it arrives.
     */
    @Nullable
    private static Bitmap remoteArt(String artist, String title) {
        final String key = artKey(artist, title);
        final Bitmap cached = remoteArts.get(key);
        if (cached != null || !remoteArtAsked.add(key)) {
            return cached;
        }
        org.telegram.messenger.Utilities.globalQueue.postRunnable(() -> {
            Bitmap found = null;
            try {
                final String cut = title.endsWith("\u2026") ? title.substring(0, title.length() - 1) : title;
                final String term = java.net.URLEncoder.encode((artist + " " + cut).trim(), "UTF-8");
                final String json = httpGet("https://itunes.apple.com/search?media=music&entity=song&limit=1&term=" + term, 256 * 1024);
                final org.json.JSONArray results = new org.json.JSONObject(json).optJSONArray("results");
                if (results != null && results.length() > 0) {
                    String url = results.getJSONObject(0).optString("artworkUrl100", "");
                    if (!url.isEmpty()) {
                        url = url.replace("100x100", "300x300");
                        final byte[] image = httpBytes(url, 2 * 1024 * 1024);
                        final Bitmap b = android.graphics.BitmapFactory.decodeByteArray(image, 0, image.length);
                        found = b != null ? scaled(b, 320) : null;
                    }
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
            final Bitmap art = found;
            handler.post(() -> {
                if (art != null) {
                    remoteArts.put(key, art);
                    NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.solegramNowPlayingChanged);
                } else {
                    // not found or no network: asked again after a while
                    handler.postDelayed(() -> remoteArtAsked.remove(key), 10 * 60_000);
                }
            });
        });
        return null;
    }

    private static byte[] httpBytes(String address, int max) throws Exception {
        final java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(address).openConnection();
        try {
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setRequestProperty("User-Agent", "Solegram");
            if (c.getResponseCode() != 200) {
                throw new java.io.IOException("HTTP " + c.getResponseCode());
            }
            try (java.io.InputStream in = c.getInputStream()) {
                final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                final byte[] buf = new byte[8192];
                int r;
                while ((r = in.read(buf)) > 0) {
                    out.write(buf, 0, r);
                    if (out.size() > max) {
                        throw new java.io.IOException("too large");
                    }
                }
                return out.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }

    private static String httpGet(String address, int max) throws Exception {
        return new String(httpBytes(address, max), java.nio.charset.StandardCharsets.UTF_8);
    }
    // endregion
}
