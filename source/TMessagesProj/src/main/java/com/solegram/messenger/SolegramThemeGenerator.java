/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.ApplicationLoader;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Solegram: a whole theme from one or two colours and a style. Not the colours as they are: their hue goes through
 * the interface in calm shades - a barely tinted background, pastel bubbles in light themes and deep ones in dark,
 * an accent bright enough to read - and every text is checked against what it is on, so nothing glares or fades
 * out. The result is a set of {@link SolegramThemeColors} slots for light or dark themes.
 */
public final class SolegramThemeGenerator {

    private SolegramThemeGenerator() {
    }

    public static final int STYLE_CALM = 0;
    public static final int STYLE_VIVID = 1;
    public static final int STYLE_MONO = 2;

    /** The colours to start from, chosen to look good as a theme. */
    public static final int[] COLORS = {
        0xFF3390EC, 0xFF2AA9E0, 0xFF14A8A0, 0xFF2FAA5B, 0xFF7CB342, 0xFFE0A526,
        0xFFF0892A, 0xFFE5484D, 0xFFE0559B, 0xFFB15CD9, 0xFF6C5CE7, 0xFF8D7B68
    };

    // region what was picked last time

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("solegram_theme_generator", Context.MODE_PRIVATE);
    }

    public static int savedPrimary() {
        return prefs().getInt("primary", COLORS[0]);
    }

    /** 0: worked out from the first colour. */
    public static int savedSecondary() {
        return prefs().getInt("secondary", 0);
    }

    public static int savedStyle() {
        return prefs().getInt("style", STYLE_CALM);
    }

    /** 0: the background is worked out from the main colour. */
    public static int savedBackground() {
        return prefs().getInt("background", 0);
    }

    public static void save(int primary, int secondary, int background, int style) {
        prefs().edit().putInt("primary", primary).putInt("secondary", secondary).putInt("background", background).putInt("style", style).apply();
    }

    /** Backgrounds to pick from: light ones for light themes, dark ones for dark (black for AMOLED screens). */
    public static final int[] BACKGROUNDS = {
        0xFFF7F7F8, 0xFFF4EEE4, 0xFFE8F4EC, 0xFFE8F0FA, 0xFFF0EBFA, 0xFFFAECEF,
        0xFF1C1C1E, 0xFF0F1828, 0xFF12201A, 0xFF1F1528, 0xFF000000
    };

    // endregion

    // region colour helpers

    private static float clamp(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private static float[] hsl(int color) {
        final float[] out = new float[3];
        ColorUtils.colorToHSL(color | 0xFF000000, out);
        return out;
    }

    private static int hsl(float h, float s, float l) {
        return ColorUtils.HSLToColor(new float[]{((h % 360) + 360) % 360, clamp(s), clamp(l)});
    }

    /** The colour made lighter or darker until it reads on {@code bg} with the given contrast. */
    private static int readable(int fg, int bg, double ratio, boolean lighter) {
        final float[] c = hsl(fg);
        int color = fg | 0xFF000000;
        for (int i = 0; i < 50 && ColorUtils.calculateContrast(color, bg | 0xFF000000) < ratio; i++) {
            c[2] = clamp(c[2] + (lighter ? 0.02f : -0.02f));
            color = hsl(c[0], c[1], c[2]);
            if (c[2] <= 0f || c[2] >= 1f) {
                break;
            }
        }
        return color;
    }

    /** The colour darkened until white text reads on it (buttons, counters, the send button carry white). */
    private static int underWhite(int color, double maxLuminance) {
        final float[] c = hsl(color);
        int out = color | 0xFF000000;
        for (int i = 0; i < 50 && ColorUtils.calculateLuminance(out) > maxLuminance; i++) {
            c[2] = clamp(c[2] - 0.02f);
            out = hsl(c[0], c[1], c[2]);
        }
        return out;
    }

    /** Yellows and greens look much lighter than blues at the same lightness: they are darkened a little. */
    private static float lightnessFix(float hue) {
        final float d = Math.abs(((hue - 75 + 540) % 360) - 180); // 0 at hue 75 (yellow-green)
        return d > 70 ? 0 : (70 - d) / 70f * 0.08f;
    }

    // endregion

    /**
     * The theme for {@code primary}, {@code secondary} (0: worked out) and {@code style}, for a light or dark theme,
     * as slot id → colour.
     */
    public static Map<String, Integer> generate(int primary, int secondary, int style, boolean dark) {
        return generate(primary, secondary, 0, style, dark);
    }

    /**
     * {@code background}: 0 for one worked out from the main colour; otherwise its hue and saturation tint every
     * surface (screens, menus, dividers, their bubbles, the input bar), and its lightness is kept in the themes it
     * suits (a light colour in light themes, a dark one in dark themes; the other kind takes the usual lightness).
     */
    public static Map<String, Integer> generate(int primary, int secondary, int background, int style, boolean dark) {
        final float[] p = hsl(primary);
        final float hue = p[0];
        float sat;
        switch (style) {
            case STYLE_VIVID:
                sat = Math.max(0.55f, Math.min(0.9f, p[1]));
                break;
            case STYLE_MONO:
                sat = Math.min(0.22f, p[1] * 0.3f);
                break;
            default:
                sat = Math.min(0.68f, p[1]) * 0.92f;
                break;
        }
        // the second colour: picked, or a neighbour of the first on the colour wheel
        final float hue2, sat2;
        if (secondary != 0) {
            final float[] s2 = hsl(secondary);
            hue2 = s2[0];
            sat2 = style == STYLE_MONO ? sat : Math.max(0.35f, Math.min(style == STYLE_VIVID ? 0.85f : 0.65f, s2[1]));
        } else {
            hue2 = hue + (style == STYLE_VIVID ? 42 : 26);
            sat2 = sat;
        }
        // how much of the colour the screens take: visible, never loud
        final float tint = style == STYLE_MONO ? 0.06f : style == STYLE_VIVID ? 0.42f : 0.3f;
        final float fix = lightnessFix(hue);

        // the surfaces' family: the main colour's hue, or the picked background's
        float nHue = hue, nTint = tint, lightL = 0.955f - fix * 0.3f, darkL = 0.12f;
        if (background != 0) {
            final float[] b = hsl(background);
            nHue = b[0];
            nTint = Math.min(0.6f, b[1]);
            if (b[2] >= 0.6f) {
                lightL = Math.max(0.86f, Math.min(0.99f, b[2]));
            } else if (b[2] <= 0.4f) {
                darkL = b[2] < 0.02f ? 0f : Math.max(0.05f, Math.min(0.2f, b[2]));
            }
        }
        final Map<String, Integer> out = new LinkedHashMap<>();

        // the screens: a tinted background, the header and the input bar the same family; a picked background that
        // suits this kind of theme (a light colour in a light theme, a dark one in a dark theme) is used exactly
        final float bgL = background != 0 ? hsl(background)[2] : -1;
        final boolean exactBg = background != 0 && (dark ? bgL <= 0.4f : bgL >= 0.6f);
        final int background0 = exactBg ? background | 0xFF000000 : dark ? hsl(nHue, nTint * 0.8f, darkL) : hsl(nHue, nTint, lightL);
        final int menus = dark ? hsl(nHue, nTint * 0.8f, darkL + 0.045f) : hsl(nHue, nTint * 0.6f, Math.min(0.99f, lightL + 0.03f));
        final int text = readable(dark ? hsl(nHue, 0.10f, 0.95f) : hsl(nHue, 0.20f, 0.12f), background0, 7, dark);
        final int secondaryText = readable(dark ? hsl(nHue, 0.10f, 0.64f) : hsl(nHue, 0.13f, 0.47f), background0, 3.6, dark);

        // the accent: the picked colour itself (only Mono, a style made to mute colour, works one out); a colour that
        // would be all but invisible on the background, or carry unreadable white text, moves just as far as it must
        final int accent;
        if (style == STYLE_MONO) {
            accent = underWhite(readable(hsl(hue, Math.max(sat, 0.12f), dark ? 0.6f : 0.5f - fix), background0, 3, dark), 0.26);
        } else {
            accent = underWhite(readable(primary | 0xFF000000, background0, 1.6, dark), 0.62);
        }

        out.put("accent", accent);
        out.put("background", background0);
        out.put("text", text);
        out.put("secondary", secondaryText);
        out.put("icons", readable(dark ? hsl(hue, sat * 0.35f, 0.66f) : hsl(hue, sat * 0.35f, 0.52f), background0, 2.6, dark));
        out.put("divider", dark ? hsl(nHue, nTint * 0.8f, darkL + 0.09f) : hsl(nHue, nTint, lightL - 0.075f));

        out.put("headerBg", background0);
        out.put("headerText", text);
        out.put("menus", menus);

        out.put("unread", accent);
        out.put("muted", dark ? hsl(nHue, 0.08f, Math.max(0.38f, darkL + 0.26f)) : hsl(nHue, 0.08f, Math.min(0.74f, lightL - 0.2f)));
        out.put("online", secondary != 0 && style != STYLE_MONO
            ? readable(secondary | 0xFF000000, background0, 1.6, dark) // the picked second colour itself
            : readable(hsl(hue2, Math.max(sat2, 0.5f), dark ? 0.6f : 0.47f - lightnessFix(hue2)), background0, 2.4, dark));
        out.put("fab", accent);

        // the chat: my bubbles pastel (light) or deep (dark), theirs nearly the background; texts readable on them
        final float bubbleSat = style == STYLE_MONO ? sat : sat * (dark ? 0.55f : 0.72f);
        final int outBubble = dark
            ? hsl(hue, bubbleSat, style == STYLE_VIVID ? 0.33f : 0.27f)
            : hsl(hue, bubbleSat, (style == STYLE_VIVID ? 0.86f : 0.9f) - fix * 0.5f);
        out.put("outBubble", outBubble);
        if (style == STYLE_VIVID || secondary != 0 && style != STYLE_MONO) {
            // a soft gradient into the second colour
            out.put("outGradient", dark
                ? hsl(hue2, sat2 * 0.55f, style == STYLE_VIVID ? 0.31f : 0.26f)
                : hsl(hue2, sat2 * 0.7f, (style == STYLE_VIVID ? 0.85f : 0.89f) - lightnessFix(hue2) * 0.5f));
        }
        out.put("outText", readable(dark ? hsl(hue, 0.12f, 0.96f) : hsl(hue, 0.3f, 0.12f), outBubble, 7, dark));
        final int inBubble = dark ? hsl(nHue, nTint * 0.8f, darkL + 0.07f) : hsl(nHue, nTint * 0.35f, Math.min(0.995f, lightL + 0.04f));
        out.put("inBubble", inBubble);
        out.put("inText", readable(text, inBubble, 7, dark));
        out.put("links", readable(dark ? hsl(hue, Math.max(sat, 0.45f), 0.72f) : hsl(hue, Math.max(sat, 0.45f), 0.42f - fix), inBubble, 3.5, dark));
        out.put("checks", readable(dark ? hsl(hue, sat * 0.8f, 0.76f) : hsl(hue, sat, 0.42f - fix), outBubble, 2.6, dark));
        out.put("panel", dark ? hsl(nHue, nTint * 0.8f, darkL + 0.02f) : hsl(nHue, nTint * 0.5f, Math.min(0.99f, lightL + 0.03f)));
        out.put("send", accent);

        out.put("buttons", accent);
        out.put("switches", accent);
        out.put("checkboxes", accent);
        return out;
    }
}
