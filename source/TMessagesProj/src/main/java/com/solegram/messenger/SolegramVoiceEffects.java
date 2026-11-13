/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger;

import org.telegram.messenger.R;

/**
 * Voice effects for a voice message before it is sent: the recording is read as samples (48 kHz mono), changed here
 * and written again as a voice message. Pitch is moved without changing the speed (two crossfaded taps sliding
 * along a delay line), the rest are filters, delays and modulation.
 */
public final class SolegramVoiceEffects {

    public static final int NONE = 0, CHIPMUNK = 1, DEEP = 2, ROBOT = 3, ECHO = 4, RADIO = 5, HALL = 6, ALIEN = 7, UNDERWATER = 8;
    public static final int COUNT = 9;
    private static final int RATE = 48000;

    private SolegramVoiceEffects() {
    }

    public static int nameOf(int effect) {
        switch (effect) {
            case CHIPMUNK: return R.string.SG_VoiceFxChipmunk;
            case DEEP: return R.string.SG_VoiceFxDeep;
            case ROBOT: return R.string.SG_VoiceFxRobot;
            case ECHO: return R.string.SG_VoiceFxEcho;
            case RADIO: return R.string.SG_VoiceFxRadio;
            case HALL: return R.string.SG_VoiceFxHall;
            case ALIEN: return R.string.SG_VoiceFxAlien;
            case UNDERWATER: return R.string.SG_VoiceFxUnderwater;
            default: return R.string.SG_VoiceFxNone;
        }
    }

    public static int iconOf(int effect) {
        switch (effect) {
            case CHIPMUNK: return R.drawable.msg_emoji_activities;
            case DEEP: return R.drawable.msg_voice_speaker;
            case ROBOT: return R.drawable.msg_bot;
            case ECHO: return R.drawable.msg_noise_on;
            case RADIO: return R.drawable.msg_voice_phone;
            case HALL: return R.drawable.msg_channel;
            case ALIEN: return R.drawable.msg_emoji_objects;
            case UNDERWATER: return R.drawable.msg_emoji_travel;
            default: return R.drawable.msg_voice_unmuted;
        }
    }

    /** The samples with the effect (a new array; echo and hall add a short tail). */
    public static short[] apply(short[] in, int effect) {
        final int n = in.length;
        final float[] x = new float[n];
        for (int i = 0; i < n; i++) {
            x[i] = in[i] / 32768f;
        }
        float[] y;
        switch (effect) {
            case CHIPMUNK:
                y = pitch(x, 1.6f, 1800);
                break;
            case DEEP:
                y = pitch(x, 0.7f, 2600);
                break;
            case ROBOT: {
                // the voice flattened to a buzz: ring modulation and a short metallic comb
                y = new float[n];
                final float[] comb = new float[n];
                final int d = (int) (RATE * 0.0075f);
                for (int i = 0; i < n; i++) {
                    final float c = x[i] + (i >= d ? comb[i - d] * 0.62f : 0f);
                    comb[i] = c;
                    final float ring = (float) Math.sin(2 * Math.PI * 55 * i / RATE);
                    y[i] = 0.55f * c * ring + 0.35f * c;
                }
                y = softClip(y, 1.6f);
                break;
            }
            case ECHO: {
                final int d = (int) (RATE * 0.28f), tail = d * 4;
                y = new float[n + tail];
                for (int i = 0; i < y.length; i++) {
                    final float dry = i < n ? x[i] : 0f;
                    y[i] = dry + (i >= d ? y[i - d] * 0.45f : 0f);
                }
                y = trimTail(y, n);
                break;
            }
            case RADIO: {
                y = biquad(x, highPass(450, 0.8f));
                y = biquad(y, lowPass(2800, 0.9f));
                y = softClip(y, 3.2f);
                // a little hiss
                long seed = 1234567;
                for (int i = 0; i < y.length; i++) {
                    seed = seed * 6364136223846793005L + 1442695040888963407L;
                    y[i] += ((seed >>> 40) / (float) (1L << 24) - 0.5f) * 0.012f;
                }
                break;
            }
            case HALL:
                y = trimTail(reverb(x, (int) (RATE * 1.4f)), n);
                break;
            case ALIEN: {
                final float[] p = pitch(x, 1.22f, 2000);
                y = new float[n];
                // vibrato through a short modulated delay, a high ring on top
                final int base = (int) (RATE * 0.006f);
                for (int i = 0; i < n; i++) {
                    final float mod = base * (1f + 0.7f * (float) Math.sin(2 * Math.PI * 6.5 * i / RATE));
                    final float s = sample(p, i - mod);
                    final float ring = (float) Math.sin(2 * Math.PI * 420 * i / RATE);
                    y[i] = s * 0.8f + s * ring * 0.3f;
                }
                break;
            }
            case UNDERWATER: {
                y = biquad(x, lowPass(650, 1.2f));
                final float[] w = new float[n];
                final int base = (int) (RATE * 0.012f);
                for (int i = 0; i < n; i++) {
                    final float mod = base * (1f + 0.8f * (float) Math.sin(2 * Math.PI * 1.3 * i / RATE));
                    w[i] = sample(y, i - mod) * 1.4f;
                }
                y = w;
                break;
            }
            default:
                y = x;
        }
        return toShorts(normalize(y));
    }

    // region Building blocks

    /** Pitch by a ratio, the speed kept: two taps slide along a delay line, crossfaded as each one wraps. */
    private static float[] pitch(float[] x, float ratio, int window) {
        final int n = x.length;
        final float[] y = new float[n];
        float phase = 0f;
        final float step = (1f - ratio) / window;
        for (int i = 0; i < n; i++) {
            phase += step;
            if (phase >= 1f) {
                phase -= 1f;
            } else if (phase < 0f) {
                phase += 1f;
            }
            final float p2 = phase + 0.5f >= 1f ? phase - 0.5f : phase + 0.5f;
            final float g1 = 0.5f - 0.5f * (float) Math.cos(2 * Math.PI * phase);
            final float g2 = 0.5f - 0.5f * (float) Math.cos(2 * Math.PI * p2);
            y[i] = sample(x, i - phase * window) * g1 + sample(x, i - p2 * window) * g2;
        }
        return y;
    }

    private static float sample(float[] x, float at) {
        if (at < 0 || at >= x.length - 1) {
            return 0f;
        }
        final int i = (int) at;
        final float f = at - i;
        return x[i] * (1f - f) + x[i + 1] * f;
    }

    /** Schroeder's reverb: four combs side by side, then two all-passes. */
    private static float[] reverb(float[] x, int tail) {
        final int n = x.length + tail;
        final float[] in = new float[n];
        System.arraycopy(x, 0, in, 0, x.length);
        final int[] combs = {1557, 1617, 1491, 1422};
        final float[] wet = new float[n];
        for (int c : combs) {
            final int d = c * 2; // the classic lengths are for 44.1 kHz; longer for a hall
            final float[] buf = new float[n];
            for (int i = 0; i < n; i++) {
                buf[i] = in[i] + (i >= d ? buf[i - d] * 0.8f : 0f);
                wet[i] += buf[i] * 0.25f;
            }
        }
        float[] y = allPass(wet, 225 * 2, 0.5f);
        y = allPass(y, 556 * 2, 0.5f);
        for (int i = 0; i < n; i++) {
            y[i] = in[i] * 0.7f + y[i] * 0.45f;
        }
        return y;
    }

    private static float[] allPass(float[] x, int d, float g) {
        final float[] y = new float[x.length];
        for (int i = 0; i < x.length; i++) {
            final float xd = i >= d ? x[i - d] : 0f, yd = i >= d ? y[i - d] : 0f;
            y[i] = -g * x[i] + xd + g * yd;
        }
        return y;
    }

    /** RBJ biquad coefficients: b0, b1, b2, a1, a2 (a0 = 1). */
    private static float[] lowPass(float f, float q) {
        final double w = 2 * Math.PI * f / RATE, alpha = Math.sin(w) / (2 * q), cos = Math.cos(w), a0 = 1 + alpha;
        return new float[]{(float) ((1 - cos) / 2 / a0), (float) ((1 - cos) / a0), (float) ((1 - cos) / 2 / a0), (float) (-2 * cos / a0), (float) ((1 - alpha) / a0)};
    }

    private static float[] highPass(float f, float q) {
        final double w = 2 * Math.PI * f / RATE, alpha = Math.sin(w) / (2 * q), cos = Math.cos(w), a0 = 1 + alpha;
        return new float[]{(float) ((1 + cos) / 2 / a0), (float) (-(1 + cos) / a0), (float) ((1 + cos) / 2 / a0), (float) (-2 * cos / a0), (float) ((1 - alpha) / a0)};
    }

    private static float[] biquad(float[] x, float[] c) {
        final float[] y = new float[x.length];
        float x1 = 0, x2 = 0, y1 = 0, y2 = 0;
        for (int i = 0; i < x.length; i++) {
            final float out = c[0] * x[i] + c[1] * x1 + c[2] * x2 - c[3] * y1 - c[4] * y2;
            x2 = x1;
            x1 = x[i];
            y2 = y1;
            y1 = out;
            y[i] = out;
        }
        return y;
    }

    private static float[] softClip(float[] x, float drive) {
        final float norm = (float) Math.tanh(drive);
        for (int i = 0; i < x.length; i++) {
            x[i] = (float) Math.tanh(x[i] * drive) / norm;
        }
        return x;
    }

    /** The tail after the voice is kept only while it is still heard. */
    private static float[] trimTail(float[] y, int voiceLength) {
        int end = y.length;
        while (end > voiceLength && Math.abs(y[end - 1]) < 0.002f) {
            end--;
        }
        if (end == y.length) {
            return y;
        }
        final float[] out = new float[end];
        System.arraycopy(y, 0, out, 0, end);
        // fade the last 30 ms
        final int fade = Math.min(end, RATE * 3 / 100);
        for (int i = 0; i < fade; i++) {
            out[end - 1 - i] *= i / (float) fade;
        }
        return out;
    }

    /** As loud as the voice can be without clipping (peaks to 0.95, quiet recordings lifted up to 4x). */
    private static float[] normalize(float[] y) {
        float peak = 0f;
        for (float v : y) {
            peak = Math.max(peak, Math.abs(v));
        }
        if (peak < 1e-4f) {
            return y;
        }
        final float gain = Math.min(4f, 0.95f / peak);
        for (int i = 0; i < y.length; i++) {
            y[i] *= gain;
        }
        return y;
    }

    private static short[] toShorts(float[] y) {
        final short[] out = new short[y.length];
        for (int i = 0; i < y.length; i++) {
            final float v = Math.max(-1f, Math.min(1f, y[i]));
            out[i] = (short) (v * 32767f);
        }
        return out;
    }
    // endregion
}
