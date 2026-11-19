/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger;

import android.util.LruCache;
import android.util.SparseIntArray;

import androidx.annotation.Nullable;

import org.telegram.ui.ActionBar.Theme;

/**
 * Someone's profile page in the colours of their own theme. The owner's profile card carries the base of their theme
 * (one of Telegram's built-in ones) and a few of its key colours; a viewer builds the whole colour set from that base,
 * the way Telegram does for chat themes, puts the published colours over it, and the profile page is drawn with it.
 */
public final class SolegramProfileTheme {

    private SolegramProfileTheme() {
    }

    /** Built-in bases a card may name (anything else reads as Blue / Night). */
    private static final String[] BASES = {"Blue", "Dark Blue", "Arctic Blue", "Day", "Night"};

    /** The colours a card carries, in this order (a card from a newer version may carry more; extra ones are skipped). */
    static final int[] KEYS = {
        Theme.key_windowBackgroundWhite,
        Theme.key_windowBackgroundGray,
        Theme.key_windowBackgroundWhiteBlackText,
        Theme.key_windowBackgroundWhiteGrayText2,
        Theme.key_windowBackgroundWhiteBlueText4,
        Theme.key_windowBackgroundWhiteBlueHeader,
        Theme.key_actionBarDefault,
        Theme.key_actionBarDefaultIcon,
        Theme.key_actionBarDefaultTitle,
        Theme.key_featuredStickers_addButton,
        Theme.key_chats_actionBackground,
        Theme.key_divider,
    };

    /** The base of the theme in use now, as a card names it. */
    static String baseNow() {
        try {
            final Theme.ThemeInfo active = Theme.getActiveTheme();
            if (active != null && active.info == null) {
                final String name = active.getKey();
                for (String b : BASES) {
                    if (b.equals(name)) {
                        return b;
                    }
                }
            }
            return Theme.isCurrentThemeDark() ? "Night" : "Blue";
        } catch (Throwable e) {
            return "Blue";
        }
    }

    /** The key colours of the theme in use now (opaque), in {@link #KEYS} order. */
    static int[] paletteNow() {
        final int[] out = new int[KEYS.length];
        for (int i = 0; i < KEYS.length; i++) {
            out[i] = Theme.getColor(KEYS[i]) | 0xFF000000;
        }
        return out;
    }

    static boolean validBase(String base) {
        for (String b : BASES) {
            if (b.equals(base)) {
                return true;
            }
        }
        return false;
    }

    private static final LruCache<String, Provider> cache = new LruCache<>(8);

    /** The colours for this person's profile page, or null (no card, no theme on it, or it is the own profile). */
    @Nullable
    public static Theme.ResourcesProvider providerFor(long userId, boolean self) {
        if (self || userId == 0 || !SolegramConfig.profileCards) {
            return null;
        }
        final SolegramProfileCard.Card card = SolegramProfileCard.cardForTheme(userId);
        if (card == null || card.themeBase == null || card.themePalette == null || card.themePalette.length == 0) {
            return null;
        }
        final StringBuilder key = new StringBuilder(card.themeBase);
        for (int c : card.themePalette) {
            key.append('.').append(Integer.toHexString(c));
        }
        Provider p = cache.get(key.toString());
        if (p == null) {
            p = build(card.themeBase, card.themePalette);
            if (p != null) {
                cache.put(key.toString(), p);
            }
        }
        return p;
    }

    @Nullable
    private static Provider build(String base, int[] palette) {
        try {
            Theme.ThemeInfo info = Theme.getTheme(validBase(base) ? base : "Blue");
            if (info == null) {
                info = Theme.getTheme("Blue");
            }
            final SparseIntArray colors = info != null && info.assetName != null
                ? Theme.getThemeFileValues(null, info.assetName, null).clone()
                : new SparseIntArray();
            final SparseIntArray fallbackKeys = Theme.getFallbackKeys();
            for (int i = 0; i < fallbackKeys.size(); i++) {
                final int colorKey = fallbackKeys.keyAt(i);
                final int fallbackKey = fallbackKeys.valueAt(i);
                if (colors.indexOfKey(colorKey) < 0) {
                    final int at = colors.indexOfKey(fallbackKey);
                    if (at >= 0) {
                        colors.put(colorKey, colors.valueAt(at));
                    }
                }
            }
            final int[] defaults = Theme.getDefaultColors();
            for (int i = 0; i < defaults.length; i++) {
                if (colors.indexOfKey(i) < 0) {
                    colors.put(i, defaults[i]);
                }
            }
            for (int i = 0; i < palette.length && i < KEYS.length; i++) {
                if (palette[i] != 0) {
                    colors.put(KEYS[i], palette[i]);
                }
            }
            // what follows the profile's accent in Telegram's own themes
            final int accent = colors.get(Theme.key_windowBackgroundWhiteBlueText4);
            colors.put(Theme.key_windowBackgroundWhiteBlueText, accent);
            colors.put(Theme.key_windowBackgroundWhiteBlueText2, accent);
            colors.put(Theme.key_windowBackgroundWhiteBlueButton, accent);
            colors.put(Theme.key_windowBackgroundWhiteBlueIcon, accent);
            colors.put(Theme.key_windowBackgroundWhiteGrayText, colors.get(Theme.key_windowBackgroundWhiteGrayText2));
            colors.put(Theme.key_windowBackgroundWhiteGrayIcon, colors.get(Theme.key_windowBackgroundWhiteGrayText2));
            final boolean dark = androidx.core.graphics.ColorUtils.calculateLuminance(colors.get(Theme.key_windowBackgroundWhite)) < 0.5;
            return new Provider(colors, dark);
        } catch (Throwable e) {
            org.telegram.messenger.FileLog.e(e);
            return null;
        }
    }

    private static final class Provider implements Theme.ResourcesProvider {
        private final SparseIntArray colors;
        private final boolean dark;

        Provider(SparseIntArray colors, boolean dark) {
            this.colors = colors;
            this.dark = dark;
        }

        @Override
        public int getColor(int key) {
            final int at = colors.indexOfKey(key);
            return at >= 0 ? colors.valueAt(at) : Theme.getColor(key);
        }

        @Override
        public boolean isDark() {
            return dark;
        }
    }
}
