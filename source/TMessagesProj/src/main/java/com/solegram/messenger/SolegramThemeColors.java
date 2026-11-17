/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;

/**
 * Solegram theme editor: the colours a person picked for parts of the interface, laid over whatever theme is on.
 * <p>
 * The interface is split into a few understandable "slots" (the accent, the screens' background, my messages...);
 * each slot paints a set of Telegram's colour keys, and works out their pressed, faded and contrasting shades by
 * itself, so one choice is enough. Light and dark themes keep their own choices. The colours are applied when
 * Telegram builds its colour table ({@link Theme#refreshThemeColors}), so every screen draws them as theme colours.
 */
public final class SolegramThemeColors {

    private SolegramThemeColors() {
    }

    public static final int SECTION_GENERAL = 0;
    public static final int SECTION_HEADER = 1;
    public static final int SECTION_CHATS = 2;
    public static final int SECTION_CHAT = 3;
    public static final int SECTION_CONTROLS = 4;

    /** A part of the interface the editor colours as one. */
    public static final class Slot {
        public final String id;
        public final int section;
        public final int title;
        public final int info;
        /** The key whose colour the slot shows (its current colour). */
        public final int shownKey;
        /** A short name for the slot's tile in the editor. */
        public final int shortTitle;

        Slot(String id, int section, int title, int info, int shownKey, int shortTitle) {
            this.id = id;
            this.section = section;
            this.title = title;
            this.info = info;
            this.shownKey = shownKey;
            this.shortTitle = shortTitle;
        }
    }

    // the order is the order they are applied in: the general ones first, the specific ones may repaint their keys
    public static final Slot[] SLOTS = {
        new Slot("accent", SECTION_GENERAL, R.string.SG_ThemeSlotAccent, R.string.SG_ThemeSlotAccentInfo, Theme.key_featuredStickers_addButton, R.string.SG_ThemeShortAccent),
        new Slot("background", SECTION_GENERAL, R.string.SG_ThemeSlotBackground, R.string.SG_ThemeSlotBackgroundInfo, Theme.key_windowBackgroundWhite, R.string.SG_ThemeShortBackground),
        new Slot("text", SECTION_GENERAL, R.string.SG_ThemeSlotText, R.string.SG_ThemeSlotTextInfo, Theme.key_windowBackgroundWhiteBlackText, R.string.SG_ThemeShortText),
        new Slot("secondary", SECTION_GENERAL, R.string.SG_ThemeSlotSecondary, R.string.SG_ThemeSlotSecondaryInfo, Theme.key_windowBackgroundWhiteGrayText, R.string.SG_ThemeShortSecondary),
        new Slot("icons", SECTION_GENERAL, R.string.SG_ThemeSlotIcons, R.string.SG_ThemeSlotIconsInfo, Theme.key_windowBackgroundWhiteGrayIcon, R.string.SG_ThemeShortIcons),
        new Slot("divider", SECTION_GENERAL, R.string.SG_ThemeSlotDivider, R.string.SG_ThemeSlotDividerInfo, Theme.key_divider, R.string.SG_ThemeShortDivider),

        new Slot("headerBg", SECTION_HEADER, R.string.SG_ThemeSlotHeaderBg, R.string.SG_ThemeSlotHeaderBgInfo, Theme.key_actionBarDefault, R.string.SG_ThemeShortHeaderBg),
        new Slot("headerText", SECTION_HEADER, R.string.SG_ThemeSlotHeaderText, R.string.SG_ThemeSlotHeaderTextInfo, Theme.key_actionBarDefaultTitle, R.string.SG_ThemeShortHeaderText),
        new Slot("menus", SECTION_HEADER, R.string.SG_ThemeSlotMenus, R.string.SG_ThemeSlotMenusInfo, Theme.key_actionBarDefaultSubmenuBackground, R.string.SG_ThemeShortMenus),

        new Slot("chatName", SECTION_CHATS, R.string.SG_ThemeSlotChatName, R.string.SG_ThemeSlotChatNameInfo, Theme.key_chats_name, R.string.SG_ThemeShortChatName),
        new Slot("chatMessage", SECTION_CHATS, R.string.SG_ThemeSlotChatMessage, R.string.SG_ThemeSlotChatMessageInfo, Theme.key_chats_message, R.string.SG_ThemeShortChatMessage),
        new Slot("chatDate", SECTION_CHATS, R.string.SG_ThemeSlotChatDate, R.string.SG_ThemeSlotChatDateInfo, Theme.key_chats_date, R.string.SG_ThemeShortChatDate),
        new Slot("unread", SECTION_CHATS, R.string.SG_ThemeSlotUnread, R.string.SG_ThemeSlotUnreadInfo, Theme.key_chats_unreadCounter, R.string.SG_ThemeShortUnread),
        new Slot("muted", SECTION_CHATS, R.string.SG_ThemeSlotMuted, R.string.SG_ThemeSlotMutedInfo, Theme.key_chats_unreadCounterMuted, R.string.SG_ThemeShortMuted),
        new Slot("online", SECTION_CHATS, R.string.SG_ThemeSlotOnline, R.string.SG_ThemeSlotOnlineInfo, Theme.key_chats_onlineCircle, R.string.SG_ThemeShortOnline),
        new Slot("fab", SECTION_CHATS, R.string.SG_ThemeSlotFab, R.string.SG_ThemeSlotFabInfo, Theme.key_chats_actionBackground, R.string.SG_ThemeShortFab),

        new Slot("outBubble", SECTION_CHAT, R.string.SG_ThemeSlotOutBubble, R.string.SG_ThemeSlotOutBubbleInfo, Theme.key_chat_outBubble, R.string.SG_ThemeShortOutBubble),
        new Slot("outGradient", SECTION_CHAT, R.string.SG_ThemeSlotOutGradient, R.string.SG_ThemeSlotOutGradientInfo, Theme.key_chat_outBubbleGradient1, R.string.SG_ThemeShortOutGradient),
        new Slot("outText", SECTION_CHAT, R.string.SG_ThemeSlotOutText, R.string.SG_ThemeSlotOutTextInfo, Theme.key_chat_messageTextOut, R.string.SG_ThemeShortOutText),
        new Slot("inBubble", SECTION_CHAT, R.string.SG_ThemeSlotInBubble, R.string.SG_ThemeSlotInBubbleInfo, Theme.key_chat_inBubble, R.string.SG_ThemeShortInBubble),
        new Slot("inText", SECTION_CHAT, R.string.SG_ThemeSlotInText, R.string.SG_ThemeSlotInTextInfo, Theme.key_chat_messageTextIn, R.string.SG_ThemeShortInText),
        new Slot("links", SECTION_CHAT, R.string.SG_ThemeSlotLinks, R.string.SG_ThemeSlotLinksInfo, Theme.key_chat_messageLinkIn, R.string.SG_ThemeShortLinks),
        new Slot("checks", SECTION_CHAT, R.string.SG_ThemeSlotChecks, R.string.SG_ThemeSlotChecksInfo, Theme.key_chat_outSentCheckRead, R.string.SG_ThemeShortChecks),
        new Slot("panel", SECTION_CHAT, R.string.SG_ThemeSlotPanel, R.string.SG_ThemeSlotPanelInfo, Theme.key_chat_messagePanelBackground, R.string.SG_ThemeShortPanel),
        new Slot("send", SECTION_CHAT, R.string.SG_ThemeSlotSend, R.string.SG_ThemeSlotSendInfo, Theme.key_chat_messagePanelSend, R.string.SG_ThemeShortSend),

        new Slot("buttons", SECTION_CONTROLS, R.string.SG_ThemeSlotButtons, R.string.SG_ThemeSlotButtonsInfo, Theme.key_featuredStickers_addButton, R.string.SG_ThemeShortButtons),
        new Slot("switches", SECTION_CONTROLS, R.string.SG_ThemeSlotSwitches, R.string.SG_ThemeSlotSwitchesInfo, Theme.key_switchTrackChecked, R.string.SG_ThemeShortSwitches),
        new Slot("checkboxes", SECTION_CONTROLS, R.string.SG_ThemeSlotCheckboxes, R.string.SG_ThemeSlotCheckboxesInfo, Theme.key_checkboxSquareBackground, R.string.SG_ThemeShortCheckboxes),
    };

    public static Slot slot(String id) {
        for (Slot s : SLOTS) {
            if (s.id.equals(id)) {
                return s;
            }
        }
        return null;
    }

    // region storage

    private static final String PREFS = "solegram_theme_colors";

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String prefKey(boolean dark, String id) {
        return (dark ? "dark_" : "light_") + id;
    }

    /** The colour picked for the slot in light or dark themes, or null: the theme's own. */
    public static Integer get(boolean dark, String id) {
        final SharedPreferences p = prefs();
        final String key = prefKey(dark, id);
        return p.contains(key) ? p.getInt(key, 0) : null;
    }

    public static boolean isSet(boolean dark, String id) {
        return prefs().contains(prefKey(dark, id));
    }

    public static boolean anySet(boolean dark) {
        for (Slot s : SLOTS) {
            if (isSet(dark, s.id)) {
                return true;
            }
        }
        return false;
    }

    public static void set(boolean dark, String id, Integer color) {
        final SharedPreferences.Editor e = prefs().edit();
        if (color == null) {
            e.remove(prefKey(dark, id));
        } else {
            e.putInt(prefKey(dark, id), color);
        }
        e.apply();
    }

    public static void resetAll(boolean dark) {
        final SharedPreferences.Editor e = prefs().edit();
        for (Slot s : SLOTS) {
            e.remove(prefKey(dark, s.id));
        }
        e.apply();
    }

    // endregion

    // region palettes

    /** The quick palettes: an accent each; the bubbles and links are worked out for light and dark themes. */
    public static final int[] PALETTES = {0xFF3390EC, 0xFF14A8A0, 0xFF2FAA5B, 0xFFF0892A, 0xFFE5484D, 0xFFE0559B, 0xFF8E5CF7, 0xFF6E7781};
    public static final int[] PALETTE_NAMES = {
        R.string.SG_ThemePaletteBlue, R.string.SG_ThemePaletteTeal, R.string.SG_ThemePaletteGreen, R.string.SG_ThemePaletteOrange,
        R.string.SG_ThemePaletteRed, R.string.SG_ThemePalettePink, R.string.SG_ThemePaletteViolet, R.string.SG_ThemePaletteGraphite
    };
    private static final String[] PALETTE_SLOTS = {"accent", "outBubble", "outGradient", "links"};

    /** My bubbles in a palette: a light tint in light themes, a deep shade in dark ones. */
    public static int paletteBubble(int accent, boolean dark) {
        return dark ? ColorUtils.blendARGB(accent, 0xFF000000, 0.45f) : ColorUtils.blendARGB(accent, 0xFFFFFFFF, 0.80f);
    }

    /** Applies a palette ({@code accent}), or with 0 gives these parts their theme colours back. */
    public static void applyPalette(boolean dark, int accent) {
        final SharedPreferences.Editor e = prefs().edit();
        for (String id : PALETTE_SLOTS) {
            e.remove(prefKey(dark, id));
        }
        if (accent != 0) {
            e.putInt(prefKey(dark, "accent"), accent);
            e.putInt(prefKey(dark, "outBubble"), paletteBubble(accent, dark));
            e.putInt(prefKey(dark, "links"), dark ? ColorUtils.blendARGB(accent, 0xFFFFFFFF, 0.25f) : accent);
        }
        e.apply();
    }

    /** The palette on now (its accent), 0 for none. */
    public static int currentPalette(boolean dark) {
        final Integer accent = get(dark, "accent");
        final Integer bubble = get(dark, "outBubble");
        if (accent == null || bubble == null || bubble != paletteBubble(accent, dark)) {
            return 0;
        }
        return accent;
    }

    // endregion

    // region applying

    /** The keys the picked colours paint: Telegram's default theme reads them from the table, not its defaults. */
    private static volatile SparseBooleanArray painted = new SparseBooleanArray();

    public static boolean paints(int key) {
        final SparseBooleanArray p = painted;
        return p.size() > 0 && p.get(key);
    }

    /**
     * Lays the picked colours over the colour table being built ({@code colors}, from {@code base}: the theme's own).
     * Called by {@link Theme#refreshThemeColors} before the table is published.
     */
    public static void apply(SparseIntArray colors, boolean dark) {
        final SparseBooleanArray keys = new SparseBooleanArray();
        final SharedPreferences p = prefs();
        final String accentKey = prefKey(dark, "accent");
        if (p.contains(accentKey)) {
            // first everything Telegram derives from the accent, then the colours picked part by part over it
            final SparseIntArray before = colors.clone();
            Theme.solegramShiftAccent(colors, p.getInt(accentKey, 0));
            for (int i = 0; i < colors.size(); i++) {
                final int key = colors.keyAt(i);
                if (before.get(key, 1) != colors.valueAt(i)) {
                    keys.put(key, true); // the default theme reads them from the table, not its defaults
                }
            }
        }
        for (Slot s : SLOTS) {
            final String key = prefKey(dark, s.id);
            if (!p.contains(key)) {
                continue;
            }
            paint(s.id, p.getInt(key, 0), colors, keys, dark);
        }
        painted = keys;
    }

    private static void put(SparseIntArray colors, SparseBooleanArray keys, int key, int color) {
        colors.put(key, color);
        keys.put(key, true);
    }

    /** The colour as the table has it now (the theme's, or a slot painted before). */
    private static int current(SparseIntArray colors, int key) {
        final int i = colors.indexOfKey(key);
        if (i >= 0) {
            return colors.valueAt(i);
        }
        // a preview is laid over the colours on screen now; the theme's table is being built from its defaults
        return colors instanceof PreviewTable ? Theme.getColor(key) : Theme.getDefaultColor(key);
    }

    /** The colours a set of slots would paint, over the colours on screen now: for a preview, nothing is applied. */
    public static final class PreviewTable extends SparseIntArray {
    }

    /** What {@code slots} (slot id → colour, in the order of {@link #SLOTS}) would paint, as a table of keys. */
    public static PreviewTable preview(java.util.Map<String, Integer> slots, boolean dark) {
        final PreviewTable colors = new PreviewTable();
        final SparseBooleanArray keys = new SparseBooleanArray();
        for (Slot s : SLOTS) {
            final Integer c = slots.get(s.id);
            if (c != null) {
                paint(s.id, c, colors, keys, dark);
            }
        }
        return colors;
    }

    /** Replaces the colours of light or dark themes with {@code slots} at once. */
    public static void setAll(boolean dark, java.util.Map<String, Integer> slots) {
        final SharedPreferences.Editor e = prefs().edit();
        for (Slot s : SLOTS) {
            final Integer c = slots.get(s.id);
            if (c == null) {
                e.remove(prefKey(dark, s.id));
            } else {
                e.putInt(prefKey(dark, s.id), c);
            }
        }
        e.apply();
    }

    /** The colour with the alpha the key had in the theme (faded texts, selectors). */
    private static int keepAlpha(SparseIntArray colors, int key, int color) {
        return ColorUtils.setAlphaComponent(color, Color.alpha(current(colors, key)));
    }

    private static boolean isLight(int color) {
        return ColorUtils.calculateLuminance(color | 0xFF000000) > 0.6f;
    }

    /** White or black text over the colour, whichever reads. */
    private static int onColor(int color) {
        return ColorUtils.calculateLuminance(color | 0xFF000000) > 0.62f ? 0xFF000000 : 0xFFFFFFFF;
    }

    /** A pressed shade: a little darker (lighter on a dark colour). */
    private static int pressed(int color) {
        return ColorUtils.blendARGB(color, isLight(color) ? 0xFF000000 : 0xFFFFFFFF, 0.12f);
    }

    private static void paint(String id, int c, SparseIntArray colors, SparseBooleanArray keys, boolean dark) {
        final int opaque = c | 0xFF000000;
        switch (id) {
            case "accent":
                for (int k : new int[]{Theme.key_windowBackgroundWhiteBlueHeader, Theme.key_windowBackgroundWhiteBlueText, Theme.key_windowBackgroundWhiteBlueText4,
                        Theme.key_windowBackgroundWhiteLinkText, Theme.key_switchTrackChecked, Theme.key_checkboxSquareBackground, Theme.key_radioBackgroundChecked,
                        Theme.key_checkbox, Theme.key_featuredStickers_addButton, Theme.key_chats_actionBackground, Theme.key_chats_unreadCounter,
                        Theme.key_chat_messagePanelSend, Theme.key_dialogTextBlue, Theme.key_chats_verifiedBackground, Theme.key_avatar_backgroundActionBarBlue}) {
                    put(colors, keys, k, opaque);
                }
                put(colors, keys, Theme.key_featuredStickers_addButtonPressed, pressed(opaque));
                put(colors, keys, Theme.key_chats_actionPressedBackground, pressed(opaque));
                put(colors, keys, Theme.key_featuredStickers_buttonText, onColor(opaque));
                put(colors, keys, Theme.key_chats_actionIcon, onColor(opaque));
                put(colors, keys, Theme.key_chats_unreadCounterText, onColor(opaque));
                put(colors, keys, Theme.key_checkboxSquareCheck, onColor(opaque));
                put(colors, keys, Theme.key_checkboxCheck, onColor(opaque));
                break;
            case "background": {
                final int gray = ColorUtils.blendARGB(opaque, 0xFF000000, dark ? 0.35f : 0.05f);
                put(colors, keys, Theme.key_windowBackgroundWhite, opaque);
                put(colors, keys, Theme.key_windowBackgroundGray, gray);
                put(colors, keys, Theme.key_chats_menuBackground, opaque);
                put(colors, keys, Theme.key_dialogBackground, opaque);
                put(colors, keys, Theme.key_dialogBackgroundGray, gray);
                break;
            }
            case "text":
                for (int k : new int[]{Theme.key_windowBackgroundWhiteBlackText, Theme.key_dialogTextBlack, Theme.key_actionBarDefaultSubmenuItem, Theme.key_chats_name}) {
                    put(colors, keys, k, keepAlpha(colors, k, c));
                }
                break;
            case "secondary":
                for (int k : new int[]{Theme.key_windowBackgroundWhiteGrayText, Theme.key_windowBackgroundWhiteGrayText2, Theme.key_windowBackgroundWhiteGrayText3,
                        Theme.key_dialogTextGray3, Theme.key_chats_message, Theme.key_chats_message_threeLines, Theme.key_chats_date}) {
                    put(colors, keys, k, keepAlpha(colors, k, c));
                }
                break;
            case "icons":
                for (int k : new int[]{Theme.key_windowBackgroundWhiteGrayIcon, Theme.key_dialogIcon, Theme.key_actionBarDefaultSubmenuItemIcon,
                        Theme.key_chat_messagePanelIcons}) {
                    put(colors, keys, k, keepAlpha(colors, k, c));
                }
                break;
            case "divider":
                put(colors, keys, Theme.key_divider, c);
                break;
            case "headerBg":
                put(colors, keys, Theme.key_actionBarDefault, opaque);
                break;
            case "headerText":
                put(colors, keys, Theme.key_actionBarDefaultTitle, opaque);
                put(colors, keys, Theme.key_actionBarDefaultIcon, opaque);
                put(colors, keys, Theme.key_actionBarDefaultSubtitle, ColorUtils.setAlphaComponent(opaque, 0xB0));
                put(colors, keys, Theme.key_actionBarDefaultSelector, ColorUtils.setAlphaComponent(opaque, 0x1A));
                break;
            case "menus":
                put(colors, keys, Theme.key_actionBarDefaultSubmenuBackground, opaque);
                put(colors, keys, Theme.key_actionBarDefaultSubmenuItem, onColor(opaque) == 0xFF000000 ? 0xFF222222 : 0xFFFFFFFF);
                put(colors, keys, Theme.key_actionBarDefaultSubmenuItemIcon, ColorUtils.setAlphaComponent(onColor(opaque), 0x99));
                break;
            case "chatName":
                put(colors, keys, Theme.key_chats_name, opaque);
                break;
            case "chatMessage":
                put(colors, keys, Theme.key_chats_message, opaque);
                put(colors, keys, Theme.key_chats_message_threeLines, opaque);
                break;
            case "chatDate":
                put(colors, keys, Theme.key_chats_date, opaque);
                put(colors, keys, Theme.key_chats_sentCheck, opaque);
                put(colors, keys, Theme.key_chats_sentReadCheck, opaque);
                break;
            case "unread":
                put(colors, keys, Theme.key_chats_unreadCounter, opaque);
                put(colors, keys, Theme.key_chats_unreadCounterText, onColor(opaque));
                break;
            case "muted":
                put(colors, keys, Theme.key_chats_unreadCounterMuted, opaque);
                break;
            case "online":
                put(colors, keys, Theme.key_chats_onlineCircle, opaque);
                break;
            case "fab":
                put(colors, keys, Theme.key_chats_actionBackground, opaque);
                put(colors, keys, Theme.key_chats_actionPressedBackground, pressed(opaque));
                put(colors, keys, Theme.key_chats_actionIcon, onColor(opaque));
                break;
            case "outBubble":
                put(colors, keys, Theme.key_chat_outBubble, opaque);
                put(colors, keys, Theme.key_chat_outBubbleSelected, pressed(opaque));
                // one colour: the theme's gradient goes (a gradient is the slot below)
                put(colors, keys, Theme.key_chat_outBubbleGradient1, 0);
                put(colors, keys, Theme.key_chat_outBubbleGradient2, 0);
                put(colors, keys, Theme.key_chat_outBubbleGradient3, 0);
                break;
            case "outGradient":
                put(colors, keys, Theme.key_chat_outBubbleGradient1, opaque);
                put(colors, keys, Theme.key_chat_outBubbleGradient2, 0);
                put(colors, keys, Theme.key_chat_outBubbleGradient3, 0);
                break;
            case "outText": {
                final int bubble = current(colors, Theme.key_chat_outBubble);
                final int faded = ColorUtils.blendARGB(opaque, bubble, 0.35f);
                put(colors, keys, Theme.key_chat_messageTextOut, opaque);
                put(colors, keys, Theme.key_chat_outTimeText, faded);
                put(colors, keys, Theme.key_chat_outTimeSelectedText, faded);
                put(colors, keys, Theme.key_chat_outViews, faded);
                break;
            }
            case "inBubble":
                put(colors, keys, Theme.key_chat_inBubble, opaque);
                put(colors, keys, Theme.key_chat_inBubbleSelected, pressed(opaque));
                break;
            case "inText": {
                final int bubble = current(colors, Theme.key_chat_inBubble);
                final int faded = ColorUtils.blendARGB(opaque, bubble, 0.4f);
                put(colors, keys, Theme.key_chat_messageTextIn, opaque);
                put(colors, keys, Theme.key_chat_inTimeText, faded);
                put(colors, keys, Theme.key_chat_inTimeSelectedText, faded);
                put(colors, keys, Theme.key_chat_inViews, faded);
                break;
            }
            case "links":
                put(colors, keys, Theme.key_chat_messageLinkIn, opaque);
                put(colors, keys, Theme.key_chat_messageLinkOut, opaque);
                break;
            case "checks":
                put(colors, keys, Theme.key_chat_outSentCheck, opaque);
                put(colors, keys, Theme.key_chat_outSentCheckRead, opaque);
                put(colors, keys, Theme.key_chat_outSentCheckSelected, opaque);
                put(colors, keys, Theme.key_chat_outSentCheckReadSelected, opaque);
                break;
            case "panel": {
                final int on = onColor(opaque);
                put(colors, keys, Theme.key_chat_messagePanelBackground, opaque);
                put(colors, keys, Theme.key_chat_messagePanelText, on == 0xFF000000 ? 0xFF222222 : 0xFFFFFFFF);
                put(colors, keys, Theme.key_chat_messagePanelHint, ColorUtils.setAlphaComponent(on, 0x80));
                break;
            }
            case "send":
                put(colors, keys, Theme.key_chat_messagePanelSend, opaque);
                break;
            case "buttons":
                put(colors, keys, Theme.key_featuredStickers_addButton, opaque);
                put(colors, keys, Theme.key_featuredStickers_addButtonPressed, pressed(opaque));
                put(colors, keys, Theme.key_featuredStickers_buttonText, onColor(opaque));
                break;
            case "switches":
                put(colors, keys, Theme.key_switchTrackChecked, opaque);
                break;
            case "checkboxes":
                put(colors, keys, Theme.key_checkboxSquareBackground, opaque);
                put(colors, keys, Theme.key_radioBackgroundChecked, opaque);
                put(colors, keys, Theme.key_checkbox, opaque);
                put(colors, keys, Theme.key_checkboxSquareCheck, onColor(opaque));
                put(colors, keys, Theme.key_checkboxCheck, onColor(opaque));
                break;
        }
    }

    // endregion
}
