/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger;

import android.os.SystemClock;

import java.util.ArrayList;

/**
 * Solegram: beats of the music that plays in the app's player. The player's audio visualizer (FFT of what is heard,
 * sent every ~64 ms while someone listens) feeds this; a beat is a jump of the low end and the loudness over their
 * recent average. Listeners hear about beats only while they are registered (the profile's gifts, while shown).
 */
public final class SolegramBeat {

    private SolegramBeat() {
    }

    public interface Listener {
        /** A beat, {@code strength} 0..1. UI thread. */
        void onBeat(float strength);
    }

    private static final ArrayList<Listener> listeners = new ArrayList<>();
    // onset detection: the rise of the low end and of the loudness from one update to the next ("spectral flux"), against
    // its own running mean and spread, so a beat is a rise clearly bigger than the music's usual movement
    private static float prevLow, prevAll, fluxMean, fluxVar;
    private static long lastBeat;
    private static boolean warm;

    public static void add(Listener l) {
        if (l != null && !listeners.contains(l)) {
            listeners.add(l);
        }
    }

    public static void remove(Listener l) {
        listeners.remove(l);
    }

    /** Whether the player should keep sending its waveform. */
    public static boolean wanted() {
        return !listeners.isEmpty();
    }

    /** From the player's visualizer: values[0..5] = parts of the spectrum (low first), values[6] = loudness. UI thread. */
    public static void onWaveform(boolean playing, float[] values) {
        if (!playing || values == null || values.length < 7) {
            prevLow = prevAll = 0;
            warm = false;
            return;
        }
        final float low = values[0] * 0.7f + values[1] * 0.3f;
        final float all = values[6];
        final float flux = Math.max(0f, low - prevLow) + 0.6f * Math.max(0f, all - prevAll);
        prevLow = low;
        prevAll = all;
        if (!warm) {
            warm = true;
            fluxMean = flux;
            fluxVar = 0.01f;
            return;
        }
        final float std = (float) Math.sqrt(fluxVar);
        final float threshold = fluxMean + 1.3f * std + 0.025f;
        final long now = SystemClock.elapsedRealtime();
        final boolean beat = flux > threshold && now - lastBeat > 170;
        // the statistics follow the music (about a second of memory at ~15 updates a second)
        final float d = flux - fluxMean;
        fluxMean += 0.08f * d;
        fluxVar = 0.92f * (fluxVar + 0.08f * d * d);
        if (!beat) {
            return;
        }
        lastBeat = now;
        final float strength = Math.max(0.4f, Math.min(1f, 0.4f + (flux - threshold) / (3f * std + 0.05f)));
        for (Listener l : new ArrayList<>(listeners)) {
            l.onBeat(strength);
        }
    }
}
