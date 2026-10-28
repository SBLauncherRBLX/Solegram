/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.soledrop;

import androidx.core.graphics.ColorUtils;

import org.telegram.ui.ActionBar.Theme;

/**
 * SoleDrop's colours, taken from the theme: its accent and two neighbours of it on the colour wheel (a warmer and a
 * cooler one), so the light, the rings and the buttons follow whatever theme and accent the user has.
 * {@link #refresh} is called when a SoleDrop screen is built.
 */
final class SoleDropPalette {

    /** The accent itself (the name stayed from the first, fixed palette). */
    static int PINK = 0xFFFF4F8B;
    /** The warmer neighbour of the accent. */
    static int ORANGE = 0xFFFF9F43;
    /** The cooler neighbour of the accent. */
    static int VIOLET = 0xFF8E5CFF;
    static int CORAL = 0xFFFF6B6B;

    /** The gradient of buttons, rings and the aura, in order around a circle (the first again at the end). */
    static final int[] SWEEP = new int[4];
    static final int[] BUTTON = new int[3];

    private static final float[] hsl = new float[3];

    static {
        fill();
    }

    static void refresh(Theme.ResourcesProvider rp) {
        int accent = Theme.getColor(Theme.key_featuredStickers_addButton, rp) | 0xFF000000;
        ColorUtils.colorToHSL(accent, hsl);
        if (hsl[1] < 0.12f) {
            // a grey accent: keep it, its neighbours stay grey too, only lighter and darker
            PINK = accent;
            ORANGE = shift(accent, 0, 0, 0.10f);
            VIOLET = shift(accent, 0, 0, -0.08f);
            CORAL = shift(accent, 0, 0, 0.05f);
        } else {
            PINK = accent;
            ORANGE = shift(accent, 32f, 0.04f, 0.06f);
            VIOLET = shift(accent, -42f, 0f, -0.02f);
            CORAL = shift(accent, 12f, 0f, 0.04f);
        }
        fill();
    }

    private static void fill() {
        SWEEP[0] = PINK;
        SWEEP[1] = ORANGE;
        SWEEP[2] = VIOLET;
        SWEEP[3] = PINK;
        // buttons and frames are the accent itself, as Solegram's own buttons, with a gentle light from the top left
        BUTTON[0] = shift(PINK, 4f, 0f, 0.06f);
        BUTTON[1] = PINK;
        BUTTON[2] = shift(PINK, -4f, 0f, -0.05f);
    }

    private static int shift(int color, float hue, float saturation, float lightness) {
        final float[] c = new float[3];
        ColorUtils.colorToHSL(color, c);
        c[0] = ((c[0] + hue) % 360f + 360f) % 360f;
        c[1] = Math.max(0f, Math.min(1f, c[1] + saturation));
        c[2] = Math.max(0.12f, Math.min(0.82f, c[2] + lightness));
        return ColorUtils.HSLToColor(c);
    }

    private SoleDropPalette() {
    }

    static boolean dark(Theme.ResourcesProvider rp) {
        return rp != null ? rp.isDark() : Theme.isCurrentThemeDark();
    }

    /** The page, as Solegram's settings pages: the grey (in the dark theme black) background the cards lie on. */
    static int background(Theme.ResourcesProvider rp) {
        return Theme.getColor(Theme.key_windowBackgroundGray, rp) | 0xFF000000;
    }

    static int text(Theme.ResourcesProvider rp) {
        return Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, rp);
    }

    static int gray(Theme.ResourcesProvider rp) {
        return Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, rp);
    }

    /** Cards that lie on the page: Solegram's section cards. */
    static int surface(Theme.ResourcesProvider rp) {
        return Theme.getColor(Theme.key_windowBackgroundWhite, rp) | 0xFF000000;
    }

    /** The plain look of glass (no live glass on this phone): a veil of the surface with a light rim. */
    static int veil(Theme.ResourcesProvider rp) {
        return dark(rp) ? 0xB82C2C32 : 0xC8FFFFFF;
    }

    static int rim(Theme.ResourcesProvider rp) {
        return dark(rp) ? 0x26FFFFFF : 0x14000000;
    }
}
