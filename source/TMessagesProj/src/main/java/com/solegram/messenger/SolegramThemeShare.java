/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger;

import android.content.SharedPreferences;
import android.text.TextUtils;

import androidx.annotation.Nullable;

import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Solegram: a theme to share in a chat - the colours of light and dark themes (SolegramThemeColors), the three colours
 * the theme was made of, and the settings of how the app looks (glass, avatar corners, motion...), in a file of our own
 * ({@code .solegram}: JSON, format "solegram-theme"). Only what is about the look is ever taken from such a file: a
 * theme somebody sent cannot touch privacy, protection, the updater or anything else.
 */
public final class SolegramThemeShare {

    private SolegramThemeShare() {
    }

    public static final String EXTENSION = ".solegram";
    /** The first themes were sent with this one: they still open. */
    private static final String OLD_EXTENSION = ".sgtheme";
    public static final String MIME = "application/x-solegram-theme";
    private static final String FORMAT = "solegram-theme";
    private static final int MAX_BYTES = 256 * 1024;

    /** The settings of the look: the only ones a theme carries (and the only ones taken from one). */
    private static final List<String> LOOK = Arrays.asList(
        "avatarCorners", "useSystemFont", "centerTitle", "monetAccent", "showSeconds", "mainTitle",
        "iconPack", "iconSolarDefault",
        "glassOn", "glassThickness", "glassRefraction", "glassIndex", "glassDispersion", "glassRim", "glassOpacity", "glassBlur",
        "glassHighlight", "glassTint", "glassTintAmount", "glassShadow", "glassSquircle", "glassVibrancy", "glassMenus",
        "glassPreviewLook", "glassHeaderButtons", "glassCallScreen", "frostedHeaders", "liquidControls", "liquidMotion",
        "iosInputBar", "tabsLayout", "typingAnimation", "shapeProgress", "streaks", "newPlayer", "animatedAvatars",
        "nowPlayingBlurBackground", "nowPlayingVinyl", "nowPlayingProgress", "nowPlayingEqualizer", "nowPlayingAccent",
        "smoothTransitions", "pressScale", "fluidTouch", "animateLists", "neutralOverscroll", "stickerSize", "profileGiftsOrbit");

    /** Of the look's settings: these are numbers, this one text (a comma list of tab names), the rest switches. */
    private static final List<String> INTS = Arrays.asList("avatarCorners", "mainTitle", "iconPack", "glassThickness", "glassRefraction",
        "glassIndex", "glassDispersion", "glassRim", "glassOpacity", "glassBlur", "glassHighlight", "glassTint", "glassTintAmount",
        "nowPlayingAccent", "stickerSize");
    private static final List<String> STRINGS = Arrays.asList("tabsLayout");

    /** Whether a value fits the type the app keeps the setting in (a wrong type would break reading the settings). */
    private static boolean fits(String key, Object v) {
        if (INTS.contains(key)) {
            return v instanceof Integer || v instanceof Long && Math.abs((Long) v) < Integer.MAX_VALUE;
        }
        if (STRINGS.contains(key)) {
            return v instanceof String && ((String) v).matches("[A-Za-z0-9_,]{0,128}");
        }
        return v instanceof Boolean;
    }

    /** A theme read from a file. */
    public static final class Theme {
        public String name;
        /** slot id -> colour, for light and dark themes (may be empty: the theme keeps Telegram's colours). */
        public final Map<String, Integer> light = new LinkedHashMap<>(), dark = new LinkedHashMap<>();
        /** The three colours and the style it was made of (0 primary: none). */
        public int primary, secondary, background, style;
        /** key -> value of the look's settings. */
        public final Map<String, Object> settings = new LinkedHashMap<>();
        /** Telegram's own look: the corners of message bubbles (-1: not in the theme). */
        public int bubbleRadius = -1;

        public int accent(boolean darkTheme) {
            final Integer a = (darkTheme ? dark : light).get("accent");
            if (a != null) {
                return a;
            }
            return primary != 0 ? primary : 0xFF3390EC;
        }
    }

    // region export

    /** The current look as a theme named {@code name}. */
    public static String exportJson(String name) {
        try {
            final JSONObject root = new JSONObject();
            root.put("format", FORMAT);
            root.put("version", 1);
            root.put("name", TextUtils.isEmpty(name) ? "Solegram" : name);
            root.put("app", Solegram.getVersionName());
            final JSONObject colors = new JSONObject();
            colors.put("light", slots(false));
            colors.put("dark", slots(true));
            root.put("colors", colors);
            if (SolegramThemeColors.anySet(false) || SolegramThemeColors.anySet(true)) {
                final JSONObject made = new JSONObject();
                made.put("primary", hex(SolegramThemeGenerator.savedPrimary()));
                made.put("secondary", hex(SolegramThemeGenerator.savedSecondary()));
                made.put("background", hex(SolegramThemeGenerator.savedBackground()));
                made.put("style", SolegramThemeGenerator.savedStyle());
                root.put("generator", made);
            }
            final JSONObject settings = new JSONObject();
            final Map<String, ?> all = SolegramConfig.prefs().getAll();
            for (String key : LOOK) {
                final Object v = all.get(key);
                if (v instanceof Boolean || v instanceof Integer || v instanceof Float || v instanceof Long || v instanceof String) {
                    settings.put(key, v);
                }
            }
            root.put("settings", settings);
            final JSONObject telegram = new JSONObject();
            telegram.put("bubbleRadius", org.telegram.messenger.SharedConfig.bubbleRadius);
            root.put("telegram", telegram);
            return root.toString(1);
        } catch (Exception e) {
            FileLog.e(e);
            return "{}";
        }
    }

    private static JSONObject slots(boolean dark) throws Exception {
        final JSONObject o = new JSONObject();
        for (SolegramThemeColors.Slot s : SolegramThemeColors.SLOTS) {
            final Integer c = SolegramThemeColors.get(dark, s.id);
            if (c != null) {
                o.put(s.id, hex(c));
            }
        }
        return o;
    }

    private static String hex(int color) {
        return String.format("#%08X", color);
    }

    /** Writes the current look into a file to send ({@code <name>.solegram} in the cache). */
    @Nullable
    public static File exportFile(String name) {
        try {
            final File dir = new File(ApplicationLoader.applicationContext.getCacheDir(), "sg_themes");
            dir.mkdirs();
            String fileName = TextUtils.isEmpty(name) ? "Solegram" : name.replaceAll("[\\\\/:*?\"<>|\\n\\r\\t]", " ").trim();
            if (fileName.length() > 48) {
                fileName = fileName.substring(0, 48).trim();
            }
            if (fileName.isEmpty()) {
                fileName = "Solegram";
            }
            final File file = new File(dir, fileName + EXTENSION);
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(exportJson(name).getBytes(StandardCharsets.UTF_8));
            }
            return file;
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    // endregion

    // region import

    public static boolean isThemeFile(@Nullable String name) {
        if (name == null) {
            return false;
        }
        final String lower = name.toLowerCase();
        return lower.endsWith(EXTENSION) || lower.endsWith(OLD_EXTENSION);
    }

    /** The theme in a file, or null when it is not one (or too big, or broken). */
    @Nullable
    public static Theme read(@Nullable File file) {
        if (file == null || !file.exists() || file.length() <= 0 || file.length() > MAX_BYTES) {
            return null;
        }
        try (InputStream in = new FileInputStream(file)) {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) > 0) {
                out.write(buf, 0, r);
                if (out.size() > MAX_BYTES) {
                    return null;
                }
            }
            return parse(out.toString("UTF-8"));
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    @Nullable
    public static Theme parse(String json) {
        try {
            final JSONObject root = new JSONObject(json);
            if (!FORMAT.equals(root.optString("format"))) {
                return null;
            }
            final Theme t = new Theme();
            t.name = root.optString("name", "Solegram");
            if (t.name.length() > 64) {
                t.name = t.name.substring(0, 64);
            }
            final JSONObject colors = root.optJSONObject("colors");
            if (colors != null) {
                readSlots(colors.optJSONObject("light"), t.light);
                readSlots(colors.optJSONObject("dark"), t.dark);
            }
            final JSONObject made = root.optJSONObject("generator");
            if (made != null) {
                t.primary = color(made.optString("primary"), 0);
                t.secondary = color(made.optString("secondary"), 0);
                t.background = color(made.optString("background"), 0);
                t.style = Math.max(0, Math.min(2, made.optInt("style", 0)));
            }
            final JSONObject settings = root.optJSONObject("settings");
            if (settings != null) {
                for (Iterator<String> it = settings.keys(); it.hasNext(); ) {
                    final String key = it.next();
                    if (!LOOK.contains(key)) {
                        continue; // only the look, nothing else
                    }
                    final Object v = settings.get(key);
                    if (fits(key, v)) {
                        t.settings.put(key, v);
                    }
                }
            }
            final JSONObject telegram = root.optJSONObject("telegram");
            if (telegram != null) {
                final int radius = telegram.optInt("bubbleRadius", -1);
                t.bubbleRadius = radius >= 0 && radius <= 17 ? radius : -1;
            }
            if (t.light.isEmpty() && t.dark.isEmpty() && t.settings.isEmpty()) {
                return null;
            }
            return t;
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    private static void readSlots(@Nullable JSONObject o, Map<String, Integer> into) {
        if (o == null) {
            return;
        }
        for (SolegramThemeColors.Slot s : SolegramThemeColors.SLOTS) {
            final int c = color(o.optString(s.id, null), 0);
            if (c != 0) {
                into.put(s.id, c);
            }
        }
    }

    private static int color(@Nullable String s, int fallback) {
        if (s == null || !s.startsWith("#") || s.length() != 9 && s.length() != 7) {
            return fallback;
        }
        try {
            final long v = Long.parseLong(s.substring(1), 16);
            return s.length() == 7 ? (int) (0xFF000000L | v) : (int) v;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static void setBubbleRadius(int radius) {
        if (radius < 0 || radius > 17 || radius == org.telegram.messenger.SharedConfig.bubbleRadius) {
            return;
        }
        org.telegram.messenger.SharedConfig.bubbleRadius = radius;
        org.telegram.messenger.MessagesController.getGlobalMainSettings().edit().putInt("bubbleRadius", radius).commit();
    }

    /**
     * Everything shows the look now: the settings read again (the icon pack too), Telegram's resources and colours made
     * again, and every screen asked to build its views again ({@code layout} may be null).
     */
    public static void refresh(@Nullable org.telegram.ui.ActionBar.INavigationLayout layout) {
        SolegramConfig.reload();
        org.telegram.ui.ActionBar.Theme.reloadAllResources(ApplicationLoader.applicationContext);
        org.telegram.ui.ActionBar.Theme.refreshThemeColors(false, true);
        org.telegram.messenger.NotificationCenter.getGlobalInstance().postNotificationName(org.telegram.messenger.NotificationCenter.solegramSettingsChanged, "theme");
        if (layout != null) {
            layout.rebuildAllFragmentViews(false, false);
        }
    }

    // region preview: the theme is put on for a while; what was there before is kept to come back to

    private static final String PREVIEW_KEY = "themePreviewSnapshot";

    /** What the look is now, to come back to after a preview (kept on disk: a preview the app did not end is undone). */
    public static void savePreviewSnapshot() {
        try {
            final JSONObject root = new JSONObject();
            final JSONObject colors = new JSONObject();
            colors.put("light", slots(false));
            colors.put("dark", slots(true));
            root.put("colors", colors);
            final JSONObject made = new JSONObject();
            made.put("primary", SolegramThemeGenerator.savedPrimary());
            made.put("secondary", SolegramThemeGenerator.savedSecondary());
            made.put("background", SolegramThemeGenerator.savedBackground());
            made.put("style", SolegramThemeGenerator.savedStyle());
            root.put("generator", made);
            final JSONObject settings = new JSONObject();
            final Map<String, ?> all = SolegramConfig.prefs().getAll();
            for (String key : LOOK) {
                final Object v = all.get(key);
                if (v != null && fits(key, v)) {
                    settings.put(key, v);
                }
            }
            root.put("settings", settings);
            root.put("bubbleRadius", org.telegram.messenger.SharedConfig.bubbleRadius);
            SolegramConfig.prefs().edit().putString(PREVIEW_KEY, root.toString()).commit();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public static boolean hasPreviewSnapshot() {
        return SolegramConfig.prefs().contains(PREVIEW_KEY);
    }

    /** The preview is kept: the saved look is forgotten. */
    public static void dropPreviewSnapshot() {
        SolegramConfig.prefs().edit().remove(PREVIEW_KEY).commit();
    }

    /** Back to the look saved before the preview (settings the theme brought that were not there are removed). */
    public static boolean restorePreviewSnapshot() {
        final String json = SolegramConfig.prefs().getString(PREVIEW_KEY, null);
        if (json == null) {
            return false;
        }
        try {
            final JSONObject root = new JSONObject(json);
            final JSONObject colors = root.optJSONObject("colors");
            final Map<String, Integer> light = new LinkedHashMap<>(), dark = new LinkedHashMap<>();
            if (colors != null) {
                readSlots(colors.optJSONObject("light"), light);
                readSlots(colors.optJSONObject("dark"), dark);
            }
            SolegramThemeColors.setAll(false, light);
            SolegramThemeColors.setAll(true, dark);
            final JSONObject made = root.optJSONObject("generator");
            if (made != null) {
                SolegramThemeGenerator.save(made.optInt("primary"), made.optInt("secondary"), made.optInt("background"), made.optInt("style"));
            }
            final JSONObject settings = root.optJSONObject("settings");
            final SharedPreferences.Editor e = SolegramConfig.prefs().edit();
            for (String key : LOOK) {
                final Object v = settings != null ? settings.opt(key) : null;
                if (v == null || !fits(key, v)) {
                    e.remove(key);
                } else if (INTS.contains(key)) {
                    e.putInt(key, ((Number) v).intValue());
                } else if (STRINGS.contains(key)) {
                    e.putString(key, (String) v);
                } else {
                    e.putBoolean(key, (Boolean) v);
                }
            }
            e.remove(PREVIEW_KEY);
            e.commit();
            setBubbleRadius(root.optInt("bubbleRadius", -1));
            SolegramConfig.reload();
            return true;
        } catch (Exception e) {
            FileLog.e(e);
            SolegramConfig.prefs().edit().remove(PREVIEW_KEY).commit();
            return false;
        }
    }

    // endregion

    /** Puts the theme on: its colours (or Telegram's when it has none), what it was made of, its look settings. */
    public static void apply(Theme t) {
        SolegramThemeColors.setAll(false, t.light);
        SolegramThemeColors.setAll(true, t.dark);
        if (t.primary != 0) {
            SolegramThemeGenerator.save(t.primary, t.secondary, t.background, t.style);
        }
        if (!t.settings.isEmpty()) {
            final SharedPreferences.Editor e = SolegramConfig.prefs().edit();
            for (Map.Entry<String, Object> s : t.settings.entrySet()) {
                final String key = s.getKey();
                final Object v = s.getValue();
                if (!LOOK.contains(key) || !fits(key, v)) {
                    continue;
                }
                if (INTS.contains(key)) {
                    e.putInt(key, ((Number) v).intValue());
                } else if (STRINGS.contains(key)) {
                    e.putString(key, (String) v);
                } else {
                    e.putBoolean(key, (Boolean) v);
                }
            }
            e.commit();
            SolegramConfig.reload();
        }
        setBubbleRadius(t.bubbleRadius);
    }

    // endregion
}
