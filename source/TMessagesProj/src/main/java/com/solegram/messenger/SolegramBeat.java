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
    private static float avgLow, avgAll;
    private static long lastBeat;

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
            avgLow *= 0.5f;
            avgAll *= 0.5f;
            return;
        }
        final float low = Math.max(values[0], values[1] * 0.8f);
        final float all = values[6];
        final long now = SystemClock.elapsedRealtime();
        final boolean warm = avgAll > 0.05f;
        final boolean jump = low > 0.25f && low > avgLow * 1.35f + 0.05f || all > 0.45f && all > avgAll * 1.25f + 0.04f;
        avgLow = avgLow * 0.82f + low * 0.18f;
        avgAll = avgAll * 0.85f + all * 0.15f;
        if (!warm || !jump || now - lastBeat < 230) {
            return;
        }
        lastBeat = now;
        final float strength = Math.max(0.35f, Math.min(1f, Math.max(low - avgLow, all - avgAll) * 2.5f + 0.35f));
        for (Listener l : new ArrayList<>(listeners)) {
            l.onBeat(strength);
        }
    }
}
