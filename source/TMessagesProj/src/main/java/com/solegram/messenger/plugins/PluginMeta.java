/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.plugins;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Metadata parsed from a plugin file (__id__, __name__, ...). */
public class PluginMeta {
    public String id;
    public String name;
    public String description = "";
    public String author = "";
    public String version = "1.0";
    public String icon;
    public String appVersion;
    public String sdkVersion;
    public List<String> requirements = new ArrayList<>();
    public File file;

    // runtime state
    public volatile boolean enabled;
    public volatile boolean loaded;
    public volatile String error;
    public volatile PluginHandle handle;

    // security (see PluginSecurity / PluginGuard)
    public volatile String sha256;
    public volatile PluginSecurity.Report report;
    /** PluginGuard.BLOCK_*: why the plugin may not run. */
    public volatile int blocked;

    public boolean isIdValid() {
        return id != null && id.matches("[A-Za-z][A-Za-z0-9_-]{1,31}");
    }

    @Override
    public String toString() {
        return "Plugin{" + id + " " + version + "}";
    }
}
