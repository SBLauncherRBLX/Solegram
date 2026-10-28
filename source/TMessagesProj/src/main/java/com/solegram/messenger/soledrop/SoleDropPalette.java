/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.soledrop;

import androidx.core.graphics.ColorUtils;

import org.telegram.ui.ActionBar.Theme;

/** SoleDrop's colours: Solegram's sunset (pink, orange, violet), a little deeper in the light theme. */
final class SoleDropPalette {

    static final int PINK = 0xFFFF4F8B;
    static final int ORANGE = 0xFFFF9F43;
    static final int VIOLET = 0xFF8E5CFF;
    static final int CORAL = 0xFFFF6B6B;

    /** The gradient of buttons, rings and the aura, in order around a circle (the first again at the end). */
    static final int[] SWEEP = {PINK, ORANGE, VIOLET, PINK};
    static final int[] BUTTON = {0xFFFF5C8A, 0xFFFF8A4C, 0xFF9B63FF};

    private SoleDropPalette() {
    }

    static boolean dark(Theme.ResourcesProvider rp) {
        return rp != null ? rp.isDark() : Theme.isCurrentThemeDark();
    }

    static int background(Theme.ResourcesProvider rp) {
        return Theme.getColor(Theme.key_windowBackgroundWhite, rp) | 0xFF000000;
    }

    static int text(Theme.ResourcesProvider rp) {
        return Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, rp);
    }

    static int gray(Theme.ResourcesProvider rp) {
        return Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, rp);
    }

    /** Cards that lie on the page: a step lighter than the page in the dark theme, white in the light one. */
    static int surface(Theme.ResourcesProvider rp) {
        final int bg = background(rp);
        return dark(rp) ? ColorUtils.blendARGB(bg, 0xFFFFFFFF, 0.075f) : 0xFFFFFFFF;
    }

    /** The plain look of glass (no live glass on this phone): a veil of the surface with a light rim. */
    static int veil(Theme.ResourcesProvider rp) {
        return dark(rp) ? 0xB82C2C32 : 0xC8FFFFFF;
    }

    static int rim(Theme.ResourcesProvider rp) {
        return dark(rp) ? 0x26FFFFFF : 0x14000000;
    }
}
