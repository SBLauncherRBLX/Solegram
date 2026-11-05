/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger;

import android.text.TextUtils;

import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Which tabs the bottom bar has, and in what order. The bar keeps its size and holds at most four; Chats is
 * always the first. Without a layout of the user's own, it is Telegram's (with Solegram's Feed and its options).
 */
public final class SolegramTabs {

    public static final String CHATS = "chats", CONTACTS = "contacts", FEED = "feed", MUSIC = "music", CALLS = "calls",
        SETTINGS = "settings", PROFILE = "profile";
    /** Telegram's own slot: Calls when the calls tab is on in Telegram's settings, Settings otherwise. */
    public static final String CALLS_OR_SETTINGS = "callsOrSettings";
    /** Every tab that can be in the bar, in the order the editor lists them. */
    public static final String[] ALL = {CHATS, FEED, CONTACTS, MUSIC, CALLS, SETTINGS, PROFILE};
    public static final int MAX = 4;
    public static final int MIN = 2;

    private SolegramTabs() {
    }

    public static boolean isCustom() {
        return !TextUtils.isEmpty(SolegramConfig.tabsLayout);
    }

    /** The user's own layout (Chats first, no repeats, at most four), or null when there is none. */
    public static List<String> custom() {
        if (!isCustom()) {
            return null;
        }
        final ArrayList<String> out = new ArrayList<>();
        out.add(CHATS);
        for (String k : SolegramConfig.tabsLayout.split(",")) {
            k = k.trim();
            if (Arrays.asList(ALL).contains(k) && !out.contains(k) && out.size() < MAX) {
                out.add(k);
            }
        }
        return out.size() >= MIN ? out : null;
    }

    /** The pages of the bar, left to right, as the main screen builds them now. */
    public static List<String> pages() {
        final List<String> custom = custom();
        if (custom != null) {
            return custom;
        }
        final ArrayList<String> out = new ArrayList<>();
        out.add(CHATS);
        out.add(SolegramConfig.feedInsteadOfContacts ? FEED : CONTACTS);
        out.add(CALLS_OR_SETTINGS);
        out.add(PROFILE);
        return out;
    }

    /** The layout as the editor shows it (Telegram's when there is no own one), Calls or Settings resolved. */
    public static List<String> current() {
        final List<String> custom = custom();
        if (custom != null) {
            return custom;
        }
        final ArrayList<String> out = new ArrayList<>();
        out.add(CHATS);
        if (!SolegramConfig.hideContactsTab) {
            out.add(SolegramConfig.feedInsteadOfContacts ? FEED : CONTACTS);
        }
        out.add(UserConfig.getInstance(UserConfig.selectedAccount).showCallsTab ? CALLS : SETTINGS);
        if (!SolegramConfig.hideProfileTab) {
            out.add(PROFILE);
        }
        return out;
    }

    public static void save(List<String> tabs) {
        final ArrayList<String> rest = new ArrayList<>();
        for (String k : tabs) {
            if (!CHATS.equals(k)) {
                rest.add(k);
            }
        }
        SolegramConfig.putString("tabsLayout", TextUtils.join(",", rest));
    }

    public static void reset() {
        SolegramConfig.putString("tabsLayout", "");
    }

    public static int name(String kind) {
        switch (kind) {
            case CHATS:
                return R.string.MainTabsChats;
            case CONTACTS:
                return R.string.MainTabsContacts;
            case FEED:
                return R.string.SG_Feed;
            case MUSIC:
                return R.string.SG_PlayerMusic;
            case CALLS:
                return R.string.MainTabsCalls;
            case SETTINGS:
                return R.string.Settings;
            default:
                return R.string.MainTabsProfile;
        }
    }

    public static int icon(String kind) {
        switch (kind) {
            case CHATS:
                return R.drawable.msg_discussion;
            case CONTACTS:
                return R.drawable.msg_contacts;
            case FEED:
                return R.drawable.msg_channel;
            case MUSIC:
                return R.drawable.files_music;
            case CALLS:
                return R.drawable.msg_calls;
            case SETTINGS:
                return R.drawable.msg_settings;
            default:
                return R.drawable.msg_openprofile;
        }
    }
}
