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
            final MediaController.Callback callback = new MediaController.Callback() {
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
    private static void refresh() {
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
        SolegramNowPlayingBio.onTrackChanged(track);
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
        if (TextUtils.isEmpty(about) || !about.contains(BIO_TAG)) {
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
            return new Track(SOURCE_NONE, title, artist, 0, 0, 0, 1f, true, null, colorFor(title + artist), true);
        }
        return null;
    }

    /** The bio without the music line (the card shows it), trimmed; empty when nothing else is there. */
    public static String stripBio(@Nullable String about) {
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
}
