/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger;

import android.content.res.Configuration;
import android.content.res.Resources;

/**
 * Solegram: a phone turned on its side is laid out as Telegram lays out a tablet: the chats on the left (about a third)
 * and the open chat on the right, the rest (settings, profiles...) in a window over them. Telegram already switches
 * between the two layouts on the fly (for foldables, on a configuration change); Solegram only says "tablet" for a wide
 * landscape screen. Real tablets are not affected. A switch in Solegram's settings turns it off.
 */
public final class SolegramLandscape {

    private SolegramLandscape() {
    }

    /** Wide enough for two panes: the chats' 320dp and a chat of at least 280dp. */
    private static final int MIN_WIDTH_DP = 600;

    /** The newest configuration (the application's resources can lag behind it during a rotation). */
    private static volatile Configuration current;

    public static void onConfigurationChanged(Configuration configuration) {
        if (configuration != null) {
            current = new Configuration(configuration);
        }
    }

    /** True when this phone is wide and on its side, and the two panes are wanted. */
    public static boolean split(Resources resources) {
        if (!SolegramConfig.landscapeSplit) {
            return false;
        }
        final Configuration c = current != null ? current : resources.getConfiguration();
        return c != null && c.orientation == Configuration.ORIENTATION_LANDSCAPE && c.screenWidthDp >= MIN_WIDTH_DP
            && c.screenHeightDp > 0 && c.screenWidthDp > c.screenHeightDp;
    }
}
