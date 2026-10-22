/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.plugins;

/** A menu entry registered by a plugin through BasePlugin.add_menu_item(). */
public class PluginMenuItem {
    public static final String MESSAGE_CONTEXT_MENU = "MESSAGE_CONTEXT_MENU";
    public static final String DRAWER_MENU = "DRAWER_MENU";
    public static final String MAIN_MENU = "MAIN_MENU";
    public static final String CHAT_ACTION_MENU = "CHAT_ACTION_MENU";
    public static final String PROFILE_ACTION_MENU = "PROFILE_ACTION_MENU";

    public String pluginId;
    public String itemId;
    public String menuType;
    public String text;
    public String subtext;
    public String icon;
    public String condition;
    public int priority;

    public int getIconResId() {
        return PluginsController.resolveDrawable(icon);
    }
}
