/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.plugins;

import java.util.List;

/**
 * A loaded plugin instance. Implemented by the plugin engine (Python side through a dynamic proxy).
 * All methods must be safe to call from any thread.
 */
public interface PluginHandle {
    HookResult preRequestHook(String requestName, int account, Object request);

    HookResult postRequestHook(String requestName, int account, Object response, Object error);

    HookResult onUpdateHook(String updateName, int account, Object update);

    HookResult onUpdatesHook(String containerName, int account, Object updates);

    HookResult onSendMessageHook(int account, Object params);

    void onAppEvent(String event);

    /** Builds the plugin settings page; returns {@code null} when the plugin has no settings. */
    List<PluginSetting> createSettings();

    boolean hasSettings();

    /** Invokes a menu item callback registered by this plugin. */
    void onMenuItemClick(String itemId, java.util.Map<String, Object> context);
}
