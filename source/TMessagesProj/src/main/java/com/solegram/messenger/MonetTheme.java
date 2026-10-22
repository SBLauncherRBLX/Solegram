/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.NotificationCenter;
import org.telegram.ui.ActionBar.Theme;

/** Material You: tints the active built-in theme with the system (wallpaper) accent colour. */
public final class MonetTheme {

    private MonetTheme() {
    }

    public static boolean isSupported() {
        return Build.VERSION.SDK_INT >= 31;
    }

    private static int systemAccent(boolean dark) {
        Context ctx = ApplicationLoader.applicationContext;
        return ctx.getColor(dark ? android.R.color.system_accent1_300 : android.R.color.system_accent1_600);
    }

    /** Applies (or removes) the Monet accent for the current theme. Safe to call often. */
    public static void apply(Activity activity) {
        if (!isSupported()) {
            return;
        }
        try {
            Theme.ThemeInfo theme = Theme.getActiveTheme();
            if (theme == null || theme.themeAccents == null || theme.themeAccents.isEmpty() || theme.themeAccentsMap == null) {
                return;
            }
            SharedPreferences prefs = SolegramConfig.prefs();
            String key = "monetAccentId_" + theme.getKey();
            int storedId = prefs.getInt(key, 0);
            boolean dark = theme.isDark();

            if (!SolegramConfig.monetAccent) {
                if (storedId != 0 && theme.currentAccentId == storedId) {
                    theme.setCurrentAccentId(Theme.DEFALT_THEME_ACCENT_ID);
                    Theme.saveThemeAccents(theme, true, false, true, false);
                    NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.needSetDayNightTheme, theme, dark, null, Theme.DEFALT_THEME_ACCENT_ID);
                }
                return;
            }

            int color = systemAccent(dark);
            Theme.ThemeAccent accent = storedId != 0 ? theme.themeAccentsMap.get(storedId) : null;
            if (accent != null && accent.accentColor == color && theme.currentAccentId == accent.id) {
                return;
            }
            if (accent == null) {
                accent = theme.getAccent(true);
                if (accent == null) {
                    return;
                }
                prefs.edit().putInt(key, accent.id).apply();
            }
            accent.accentColor = color;
            accent.accentColor2 = 0;
            accent.myMessagesAccentColor = 0;
            accent.myMessagesGradientAccentColor1 = 0;
            accent.myMessagesGradientAccentColor2 = 0;
            accent.myMessagesGradientAccentColor3 = 0;
            theme.setCurrentAccentId(accent.id);
            Theme.saveThemeAccents(theme, true, false, false, false);
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.needSetDayNightTheme, theme, dark, null, accent.id);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }
}
