/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.SystemClock;
import android.text.Layout;
import android.text.TextPaint;
import android.util.SparseArray;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.collection.LongSparseArray;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LiteMode;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;

import java.util.Iterator;

/**
 * Effects on the own name in the profile, seen by other Solegram users too: a glow (steady, breathing, rainbow or
 * a neon flicker) in a chosen colour, a typing loop (the name is typed letter by letter, stays, is erased and typed
 * again), a light sweeping over it, or a flowing gradient. The effect travels in the same invisible bio mark as the
 * emoji next to the name (four more variation selectors: version, kind, mode, colour).
 */
public final class SolegramNameEffect {

    public static final int NONE = 0, GLOW = 1, TYPING = 2, SHIMMER = 3, GRADIENT = 4;
    public static final int KIND_COUNT = 5;

    /** Glow modes. */
    public static final int GLOW_STEADY = 0, GLOW_PULSE = 1, GLOW_RAINBOW = 2, GLOW_FLICKER = 3;
    /** Typing modes. */
    public static final int TYPING_CALM = 0, TYPING_FAST = 1;
    /** Gradient modes. */
    public static final int GRADIENT_TWO = 0, GRADIENT_RAINBOW = 1;

    public static final int[] PALETTE = {
        0xFFFFB443, 0xFFFF6B4A, 0xFFFF4F8B, 0xFFC86BFF, 0xFF8C6CFF, 0xFF4F8BFF,
        0xFF33C7FF, 0xFF2EE6C2, 0xFF5BE36B, 0xFFE6E14A, 0xFFFFFFFF, 0xFFFF9EC7
    };

    private static final String KEY_OWN = "nameEffect";
    private static final String KEY_KNOWN = "sharedNameEffect";
    private static LongSparseArray<Integer> known;

    private SolegramNameEffect() {
    }

    // region Values: kind, mode and colour packed in an int (0 = none)

    public static int pack(int kind, int mode, int color) {
        return kind <= NONE ? 0 : (kind & 0xF) << 8 | (mode & 0xF) << 4 | (color & 0xF);
    }

    public static int kindOf(int v) {
        return v >> 8 & 0xF;
    }

    public static int modeOf(int v) {
        return v >> 4 & 0xF;
    }

    public static int colorOf(int v) {
        return v & 0xF;
    }

    public static int own() {
        return SolegramConfig.prefs().getInt(KEY_OWN, 0);
    }

    public static void setOwn(int v) {
        SolegramConfig.prefs().edit().putInt(KEY_OWN, v).apply();
        SolegramNameEmojiShare.scheduleSync();
    }

    public static int nameOf(int kind) {
        switch (kind) {
            case GLOW: return R.string.SG_NameEffectGlow;
            case TYPING: return R.string.SG_NameEffectTyping;
            case SHIMMER: return R.string.SG_NameEffectShimmer;
            case GRADIENT: return R.string.SG_NameEffectGradient;
            default: return R.string.SG_NameEffectNone;
        }
    }

    /** The modes of a kind (their names), or null when it has none. */
    @Nullable
    public static int[] modesOf(int kind) {
        switch (kind) {
            case GLOW: return new int[]{R.string.SG_NameEffectSteady, R.string.SG_NameEffectPulse, R.string.SG_NameEffectRainbow, R.string.SG_NameEffectFlicker};
            case TYPING: return new int[]{R.string.SG_NameEffectCalm, R.string.SG_NameEffectFast};
            case GRADIENT: return new int[]{R.string.SG_NameEffectTwoTone, R.string.SG_NameEffectRainbow};
            default: return null;
        }
    }

    /** Whether the kind (in this mode) uses the chosen colour. */
    public static boolean usesColor(int kind, int mode) {
        return kind == GLOW && mode != GLOW_RAINBOW || kind == SHIMMER || kind == GRADIENT && mode == GRADIENT_TWO || kind == TYPING;
    }
    // endregion

    // region In the bio mark (after the emoji's sixteen digits)

    static final int MARK_DIGITS = 4;
    private static final int MARK_VERSION = 1;

    /** The effect's digits for the mark, or "" for none. */
    static String markDigits(int v) {
        if (v == 0) {
            return "";
        }
        return new String(new char[]{
            (char) (0xFE00 + MARK_VERSION), (char) (0xFE00 + kindOf(v)), (char) (0xFE00 + modeOf(v)), (char) (0xFE00 + colorOf(v))
        });
    }

    /** The effect from the digits after the emoji's (0 when there is none or it is not understood). */
    static int readDigits(String about, int from) {
        if (about == null || from + MARK_DIGITS > about.length()) {
            return 0;
        }
        final int[] d = new int[MARK_DIGITS];
        for (int i = 0; i < MARK_DIGITS; i++) {
            final char c = about.charAt(from + i);
            if (c < 0xFE00 || c > 0xFE0F) {
                return 0;
            }
            d[i] = c - 0xFE00;
        }
        if (d[0] != MARK_VERSION || d[1] <= NONE || d[1] >= KIND_COUNT) {
            return 0;
        }
        return pack(d[1], d[2], d[3]);
    }

    private static synchronized LongSparseArray<Integer> known() {
        if (known == null) {
            known = new LongSparseArray<>();
            try {
                final JSONObject o = new JSONObject(SolegramConfig.prefs().getString(KEY_KNOWN, "{}"));
                final Iterator<String> keys = o.keys();
                while (keys.hasNext()) {
                    final String k = keys.next();
                    known.put(Long.parseLong(k), o.getInt(k));
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
        return known;
    }

    /** Someone's bio was read: their effect (or that they have none) is noted. */
    static void onRead(long userId, int effect) {
        synchronized (SolegramNameEffect.class) {
            final Integer was = known().get(userId);
            if (was == null && effect == 0 || was != null && was == effect) {
                return;
            }
            known().put(userId, effect);
            try {
                final JSONObject o = new JSONObject();
                final LongSparseArray<Integer> map = known();
                for (int i = Math.max(0, map.size() - 3000); i < map.size(); i++) {
                    if (map.valueAt(i) != 0) {
                        o.put(String.valueOf(map.keyAt(i)), (int) map.valueAt(i));
                    }
                }
                SolegramConfig.prefs().edit().putString(KEY_KNOWN, o.toString()).apply();
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
    }

    /** The effect on a user's name: the own one from the settings, others' as their bio says. */
    public static int of(int account, long userId) {
        if (userId == 0) {
            return 0;
        }
        if (userId == UserConfig.getInstance(account).getClientUserId()) {
            return own();
        }
        synchronized (SolegramNameEffect.class) {
            final Integer v = known().get(userId);
            return v == null ? 0 : v;
        }
    }
    // endregion

    // region Drawing

    private static final SparseArray<Renderer> renderers = new SparseArray<>();

    /** A renderer for an effect (shared: it keeps no state of a view), or null for none. */
    @Nullable
    public static Renderer renderer(int effect) {
        if (effect == 0) {
            return null;
        }
        Renderer r = renderers.get(effect);
        if (r == null) {
            r = new Renderer(effect);
            renderers.put(effect, r);
        }
        return r;
    }

    public static final class Renderer {
        private final int kind, mode, color;
        private final Matrix matrix = new Matrix();
        private final Paint caretPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private LinearGradient gradient;
        private float gradientWidth;

        Renderer(int effect) {
            kind = kindOf(effect);
            mode = modeOf(effect);
            color = PALETTE[Math.min(PALETTE.length - 1, colorOf(effect))];
        }

        private static boolean animate() {
            return LiteMode.isEnabled(LiteMode.FLAGS_ANIMATED_EMOJI);
        }

        /** Draws the name's layout with the effect; the paint is left as it was. */
        public void draw(View view, Canvas canvas, Layout layout, TextPaint paint) {
            final boolean animate = animate();
            final long now = animate ? SystemClock.uptimeMillis() : 0;
            final float width = layout.getLineCount() > 0 ? layout.getLineWidth(0) : 0;
            switch (kind) {
                case GLOW: {
                    final float base = AndroidUtilities.dp(8);
                    float radius = base;
                    int c = color;
                    if (mode == GLOW_PULSE) {
                        radius = base * (0.45f + 0.55f * (0.5f + 0.5f * (float) Math.sin(now / 520.0)));
                    } else if (mode == GLOW_RAINBOW) {
                        c = Color.HSVToColor(new float[]{(now / 14f) % 360f, 0.75f, 1f});
                    } else if (mode == GLOW_FLICKER) {
                        // a neon sign: mostly on, now and then a short dip
                        final long t = now % 2600;
                        final boolean dip = t > 2000 && t < 2080 || t > 2160 && t < 2210 || t > 2300 && t < 2330;
                        radius = dip ? base * 0.15f : base;
                    }
                    paint.setShadowLayer(radius, 0, 0, c);
                    layout.draw(canvas);
                    // a second pass makes the halo fuller round thin letters
                    paint.setShadowLayer(radius * 0.5f, 0, 0, c);
                    layout.draw(canvas);
                    paint.clearShadowLayer();
                    if (animate && mode != GLOW_STEADY) {
                        view.postInvalidateOnAnimation();
                    }
                    return;
                }
                case TYPING: {
                    final CharSequence text = layout.getText();
                    final int n = text.length();
                    if (n == 0 || !animate) {
                        layout.draw(canvas);
                        return;
                    }
                    final long perChar = mode == TYPING_FAST ? 70 : 140;
                    final long type = n * perChar, hold = 2600, erase = n * 45L, pause = 700;
                    final long t = now % (type + hold + erase + pause);
                    int shown;
                    if (t < type) {
                        shown = (int) (t / perChar) + 1;
                    } else if (t < type + hold) {
                        shown = n;
                    } else if (t < type + hold + erase) {
                        shown = n - (int) ((t - type - hold) / 45) - 1;
                    } else {
                        shown = 0;
                    }
                    shown = Math.max(0, Math.min(n, shown));
                    final float x = shown >= n ? width : shown <= 0 ? 0 : layout.getPrimaryHorizontal(shown);
                    canvas.save();
                    canvas.clipRect(-AndroidUtilities.dp(4), -AndroidUtilities.dp(8), x, layout.getHeight() + AndroidUtilities.dp(8));
                    layout.draw(canvas);
                    canvas.restore();
                    // the caret blinks while it waits, stays while it types
                    final boolean typing = t < type || t >= type + hold && t < type + hold + erase;
                    if (typing || now / 450 % 2 == 0) {
                        caretPaint.setColor(color);
                        final float top = layout.getLineTop(0) + AndroidUtilities.dp(2), bottom = layout.getLineBottom(0) - AndroidUtilities.dp(1);
                        canvas.drawRect(x + AndroidUtilities.dp(1.5f), top, x + AndroidUtilities.dp(3.5f), bottom, caretPaint);
                    }
                    view.postInvalidateOnAnimation();
                    return;
                }
                case SHIMMER: {
                    layout.draw(canvas);
                    if (width <= 0) {
                        return;
                    }
                    // a soft band of light in the colour crosses the name, then rests
                    final float band = Math.max(AndroidUtilities.dp(40), width * 0.35f);
                    if (gradient == null || gradientWidth != band) {
                        gradientWidth = band;
                        gradient = new LinearGradient(0, 0, band, 0,
                            new int[]{0x00FFFFFF & color, color, 0x00FFFFFF & color}, new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
                    }
                    final long cycle = 2400;
                    final float t = animate ? (now % cycle) / (float) cycle : 0.5f;
                    final float x = -band + (width + band * 2) * Math.min(1f, t / 0.6f);
                    matrix.setTranslate(x, 0);
                    gradient.setLocalMatrix(matrix);
                    final Shader was = paint.getShader();
                    final int wasColor = paint.getColor();
                    paint.setShader(gradient);
                    layout.draw(canvas);
                    paint.setShader(was);
                    paint.setColor(wasColor);
                    if (animate) {
                        view.postInvalidateOnAnimation();
                    }
                    return;
                }
                case GRADIENT: {
                    if (width <= 0) {
                        layout.draw(canvas);
                        return;
                    }
                    if (gradient == null || gradientWidth != width) {
                        gradientWidth = width;
                        final int[] colors;
                        if (mode == GRADIENT_RAINBOW) {
                            colors = new int[]{0xFFFF5E5E, 0xFFFFC14D, 0xFF6BE36B, 0xFF4FB7FF, 0xFFB46BFF, 0xFFFF5E5E};
                        } else {
                            final float[] hsv = new float[3];
                            Color.colorToHSV(color, hsv);
                            hsv[0] = (hsv[0] + 50f) % 360f;
                            final int second = Color.HSVToColor(hsv);
                            colors = new int[]{color, second, color};
                        }
                        gradient = new LinearGradient(0, 0, width, 0, colors, null, Shader.TileMode.MIRROR);
                    }
                    final float shift = animate ? (now % 3000) / 3000f * width * 2 : 0;
                    matrix.setTranslate(shift, 0);
                    gradient.setLocalMatrix(matrix);
                    final Shader was = paint.getShader();
                    paint.setShader(gradient);
                    layout.draw(canvas);
                    paint.setShader(was);
                    if (animate) {
                        view.postInvalidateOnAnimation();
                    }
                    return;
                }
                default:
                    layout.draw(canvas);
            }
        }
    }
    // endregion
}
