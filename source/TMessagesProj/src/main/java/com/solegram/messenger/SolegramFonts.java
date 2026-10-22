/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger;

import android.graphics.Typeface;
import android.os.Build;

/** "System font" option: maps Telegram's bundled Roboto assets to the device fonts. */
public final class SolegramFonts {

    private SolegramFonts() {
    }

    /** @return a system typeface replacing the asset, or null to keep the bundled font. */
    public static Typeface systemFor(String assetPath) {
        if (assetPath == null || ApplicationLoaderReady.notReady()) {
            return null;
        }
        SolegramConfig.load();
        if (!SolegramConfig.useSystemFont) {
            return null;
        }
        String p = assetPath.toLowerCase();
        if (!p.startsWith("fonts/r")) {
            return null; // Instant View serif fonts, numbers etc. stay as they are
        }
        boolean italic = p.contains("italic");
        if (p.contains("rmono")) {
            return Typeface.MONOSPACE;
        }
        int weight;
        if (p.contains("rextrabold")) {
            weight = 800;
        } else if (p.contains("rcondensedbold")) {
            return Typeface.create("sans-serif-condensed", Typeface.BOLD);
        } else if (p.contains("medium") || p.contains("rbold")) {
            weight = 500;
        } else {
            weight = 400;
        }
        if (Build.VERSION.SDK_INT >= 28) {
            return Typeface.create(Typeface.DEFAULT, weight, italic);
        }
        int style = (weight >= 500 ? Typeface.BOLD : Typeface.NORMAL) | (italic ? Typeface.ITALIC : 0);
        return Typeface.create(weight == 500 ? Typeface.create("sans-serif-medium", Typeface.NORMAL) : Typeface.DEFAULT, weight == 500 ? (italic ? Typeface.ITALIC : Typeface.NORMAL) : style);
    }

    /** Guards against use before the application context exists. */
    static final class ApplicationLoaderReady {
        static boolean notReady() {
            return org.telegram.messenger.ApplicationLoader.applicationContext == null;
        }
    }
}
