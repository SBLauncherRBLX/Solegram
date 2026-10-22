/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.plugins;

import java.io.File;
import java.util.Map;

/** Runtime able to execute plugins. The Python (Chaquopy) engine lives in the app module. */
public interface PluginEngine {

    /** Boots the runtime. Called once on the plugins queue. */
    void start() throws Exception;

    /** Statically parses plugin metadata without executing the plugin. */
    PluginMeta readMetadata(File file) throws Exception;

    /** Installs missing __requirements__ (pure-Python wheels). Returns an error message or null. */
    String installRequirements(PluginMeta meta);

    /** Executes the plugin file and returns its handle after on_plugin_load. */
    PluginHandle load(PluginMeta meta) throws Exception;

    /** Calls on_plugin_unload and releases everything the plugin registered (method hooks, factories...). */
    void unload(PluginMeta meta, PluginHandle handle);

    /** Evaluates a MenuItemData.condition expression. */
    boolean evalCondition(String expression, Map<String, Object> context);

    /** Human readable runtime description, e.g. "Python 3.11.9 · SDK 1.4.5.0". */
    String describe();

    /** Exteragram SDK version this engine is compatible with. */
    String sdkVersion();

    /** The plugin file was deleted: release engine resources kept for it (e.g. installed packages). */
    default void onPluginDeleted(String pluginId) {
    }

    /** Developer mode switched: verbose logs and the desktop dev server. */
    default void setDevMode(boolean enabled) {
    }
}
