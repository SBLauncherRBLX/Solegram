/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.ui;

import androidx.core.graphics.ColorUtils;

import org.telegram.ui.ActionBar.Theme;

/**
 * The colours of the call screen's moving background, taken from the theme instead of Telegram's fixed
 * blue (calling) and green (talking): while the call is being placed or is ringing - the theme's background
 * colour, once it is connected - the theme's accent. Each is a set of four near shades for the gradient, kept
 * deep enough for the white text and icons of the call screen.
 */
public final class SolegramCallColors {

    public static final int NORMAL = 0, DARK = 1, LIGHT = 2;

    private SolegramCallColors() {
    }

    private static int accent() {
        return Theme.getColor(Theme.key_chats_actionBackground) | 0xFF000000;
    }

    private static int color(float h, float s, float l) {
        h = ((h % 360f) + 360f) % 360f;
        return ColorUtils.HSLToColor(new float[]{h, Math.max(0f, Math.min(1f, s)), Math.max(0.04f, Math.min(0.92f, l))});
    }

    /**
     * Calling and ringing: the theme's background.
     *
     * @param second the second set the background slowly breathes to and from
     */
    public static int[] dialing(int tone, boolean second) {
        final float[] bg = new float[3], ac = new float[3];
        ColorUtils.colorToHSL(Theme.getColor(Theme.key_windowBackgroundWhite) | 0xFF000000, bg);
        ColorUtils.colorToHSL(accent(), ac);
        float h = bg[0], s = bg[1], l = bg[2];
        if (s < 0.1f) {
            // white, grey or black has no hue of its own: a breath of the accent keeps it from looking dead
            h = ac[0];
            s = 0.16f;
        }
        s = Math.max(0.14f, Math.min(0.46f, s));
        // a light background is taken down to a deep tone of itself: the call screen's text is white
        l = l > 0.5f ? 0.30f : Math.max(0.12f, Math.min(0.26f, l));
        if (tone == DARK) {
            l -= 0.05f;
        } else if (tone == LIGHT) {
            l += 0.09f;
        }
        if (second) {
            h += 16f;
            l += 0.025f;
        }
        return new int[]{color(h - 12f, s, l + 0.07f), color(h + 8f, s * 1.15f, l - 0.02f), color(h + 26f, s, l + 0.035f), color(h - 4f, s * 0.9f, l - 0.05f)};
    }

    /** Connected, talking: the theme's accent. */
    public static int[] connected(int tone) {
        final float[] ac = new float[3];
        ColorUtils.colorToHSL(accent(), ac);
        final float h = ac[0];
        final float s = Math.max(0.5f, Math.min(0.85f, ac[1]));
        float l = Math.max(0.40f, Math.min(0.50f, ac[2]));
        if (tone == DARK) {
            l -= 0.08f;
        } else if (tone == LIGHT) {
            l += 0.10f;
        }
        return new int[]{color(h - 24f, s, l + 0.03f), color(h - 8f, s, l - 0.02f), color(h + 30f, s * 0.9f, l + 0.06f), color(h + 12f, s, l)};
    }
}
