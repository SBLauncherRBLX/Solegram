/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger;

import android.graphics.Typeface;
import android.os.Build;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import java.io.File;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The app's font. By default Telegram's bundled Roboto; the user may choose the device's system font, one of a
 * curated set (downloaded once from Google Fonts as TTF), or their own font from a file or a link.
 *
 * <p>Every typeface in the app comes through {@link org.telegram.messenger.AndroidUtilities#getTypeface}, which
 * calls {@link #systemFor}: so one place decides the whole app's font. A custom font is given per weight/italic
 * where those files exist, otherwise one base file is used and the heavier/italic faces are synthesised. Anything
 * that fails to load falls back to the bundled Roboto, so a broken font never breaks the app's text.
 */
public final class SolegramFonts {

    private SolegramFonts() {
    }

    /** Where the chosen font's files live. */
    /** Deletes the fonts downloaded or picked in 3.4.1 betas 41-50 (custom fonts are gone). */
    static void deleteCustom() {
        new Thread(() -> {
            try {
                final File d = new File(ApplicationLoader.applicationContext.getFilesDir(), "solegram_fonts");
                final File[] files = d.listFiles();
                if (files != null) {
                    for (File f : files) {
                        //noinspection ResultOfMethodCallIgnored
                        f.delete();
                    }
                }
                //noinspection ResultOfMethodCallIgnored
                d.delete();
            } catch (Throwable ignore) {
            }
        }, "sgFontsCleanup").start();
    }

    public static File dir() {
        final File d = new File(ApplicationLoader.applicationContext.getFilesDir(), "solegram_fonts");
        if (!d.exists()) {
            //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
        }
        return d;
    }

    // a face is named by weight and italic, e.g. "400", "500i"
    private static final ConcurrentHashMap<String, Typeface> cache = new ConcurrentHashMap<>();
    private static volatile String cacheKey = "";

    private static void resetIfChanged() {
        final String key = SolegramConfig.appFont + "|" + SolegramConfig.appFontToken;
        if (!key.equals(cacheKey)) {
            cache.clear();
            cacheKey = key;
        }
    }

    /** @return a typeface replacing the bundled asset, or null to keep it. */
    public static Typeface systemFor(String assetPath) {
        if (assetPath == null || ApplicationLoaderReady.notReady()) {
            return null;
        }
        SolegramConfig.load();
        final String mode = SolegramConfig.appFont;
        if (mode == null || mode.isEmpty()) {
            return null; // bundled Roboto
        }
        String p = assetPath.toLowerCase();
        if (!p.startsWith("fonts/r")) {
            return null; // Instant View serif, numbers, icons stay as they are
        }
        if ("system".equals(mode)) {
            return systemFont(p);
        }
        // a chosen or custom font
        resetIfChanged();
        final boolean italic = p.contains("italic");
        final int weight;
        if (p.contains("rmono")) {
            // a code/monospace face: a custom font is not monospaced, so keep the device monospace
            return Typeface.MONOSPACE;
        } else if (p.contains("rextrabold")) {
            weight = 800;
        } else if (p.contains("medium") || p.contains("rbold") || p.contains("rcondensedbold")) {
            weight = 500;
        } else {
            weight = 400;
        }
        final Typeface t = custom(weight, italic);
        return t != null ? t : null; // null -> bundled font
    }

    private static Typeface custom(int weight, boolean italic) {
        final String name = weight + (italic ? "i" : "");
        Typeface cached = cache.get(name);
        if (cached != null) {
            return cached;
        }
        // the file for this exact weight/italic, then nearby ones, then the plain base
        Typeface base = load(fileFor(weight, italic));
        boolean exact = base != null;
        if (base == null && italic) {
            base = load(fileFor(weight, false));
        }
        if (base == null) {
            base = load(fileFor(400, false));
        }
        if (base == null) {
            return null;
        }
        Typeface result;
        if (exact) {
            result = base;
        } else if (Build.VERSION.SDK_INT >= 28) {
            result = Typeface.create(base, weight, italic);
        } else {
            int style = (weight >= 600 ? Typeface.BOLD : Typeface.NORMAL) | (italic ? Typeface.ITALIC : 0);
            result = Typeface.create(base, style);
        }
        if (result != null) {
            cache.put(name, result);
        }
        return result;
    }

    /** The file name for a weight/italic inside {@link #dir()}. */
    public static String fileName(int weight, boolean italic) {
        return weight + (italic ? "i" : "") + ".ttf";
    }

    private static File fileFor(int weight, boolean italic) {
        return new File(dir(), fileName(weight, italic));
    }

    private static Typeface load(File file) {
        if (file == null || !file.isFile() || file.length() < 64) {
            return null;
        }
        try {
            return Typeface.createFromFile(file);
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    private static Typeface systemFont(String p) {
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
        return Typeface.create(weight == 500 ? Typeface.create("sans-serif-medium", Typeface.NORMAL) : Typeface.DEFAULT, style);
    }

    /** Validates an sfnt (TrueType/OpenType) file by its magic, so a download that is really an error page is refused. */
    public static boolean looksLikeFont(File file) {
        if (file == null || !file.isFile() || file.length() < 256) {
            return false;
        }
        try (java.io.InputStream in = new java.io.FileInputStream(file)) {
            final byte[] b = new byte[4];
            if (in.read(b) != 4) {
                return false;
            }
            final int magic = ((b[0] & 0xFF) << 24) | ((b[1] & 0xFF) << 16) | ((b[2] & 0xFF) << 8) | (b[3] & 0xFF);
            return magic == 0x00010000   // TrueType
                || magic == 0x4F54544F   // 'OTTO' (OpenType CFF)
                || magic == 0x74727565   // 'true'
                || magic == 0x74746366;  // 'ttcf' (collection)
        } catch (Throwable e) {
            return false;
        }
    }

    /** Guards against use before the application context exists. */
    static final class ApplicationLoaderReady {
        static boolean notReady() {
            return ApplicationLoader.applicationContext == null;
        }
    }
}
