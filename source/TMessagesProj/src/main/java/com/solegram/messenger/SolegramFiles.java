/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger;

import android.app.Activity;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;

import com.solegram.messenger.plugins.PluginsController;
import com.solegram.messenger.ui.PluginInstallSheet;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.LaunchActivity;

import java.io.File;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * File-open interception: installs .plugin files and dispatches extensions registered by plugins
 * through file_utils.FilesController (exteraGram SDK compatible).
 */
public final class SolegramFiles {

    /** Implemented by the plugin SDK (Python dynamic proxy). */
    public interface Handler {
        /** @return true when the plugin handled the click. */
        boolean onClick(String place, File file, String fileName, MessageObject message, Activity activity, BaseFragment fragment);

        Drawable getIcon();
    }

    private static final class Entry {
        final String secret;
        final Handler handler;
        final String[] whitelist;
        final String[] blacklist;

        Entry(String secret, Handler handler, String[] whitelist, String[] blacklist) {
            this.secret = secret;
            this.handler = handler;
            this.whitelist = whitelist;
            this.blacklist = blacklist;
        }
    }

    private static final ConcurrentHashMap<String, Entry> handlers = new ConcurrentHashMap<>();

    private SolegramFiles() {
    }

    /** @return secret needed to unregister, or null if the extension is already taken. */
    public static String register(String ext, Handler handler, String[] whitelistPlaces, String[] blacklistPlaces) {
        String key = normalize(ext);
        if (handlers.containsKey(key)) {
            return null;
        }
        String secret = UUID.randomUUID().toString();
        handlers.put(key, new Entry(secret, handler, whitelistPlaces, blacklistPlaces));
        return secret;
    }

    /** @return 0 ok, 1 not registered, 2 wrong secret */
    public static int unregister(String ext, String secret) {
        String key = normalize(ext);
        Entry e = handlers.get(key);
        if (e == null) {
            return 1;
        }
        if (!e.secret.equals(secret)) {
            return 2;
        }
        handlers.remove(key);
        return 0;
    }

    public static boolean isRegistered(String ext) {
        return handlers.containsKey(normalize(ext));
    }

    public static Drawable getIcon(String fileName) {
        String ext = extOf(fileName);
        if (ext == null) {
            return null;
        }
        Entry e = handlers.get(ext);
        if (e == null) {
            return null;
        }
        try {
            return e.handler.getIcon();
        } catch (Throwable t) {
            FileLog.e(t);
            return null;
        }
    }

    private static String normalize(String ext) {
        if (ext == null) {
            return "";
        }
        ext = ext.trim().toLowerCase(Locale.ROOT);
        return ext.startsWith(".") ? ext.substring(1) : ext;
    }

    private static String extOf(String fileName) {
        if (TextUtils.isEmpty(fileName)) {
            return null;
        }
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? null : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static boolean placeAllowed(Entry e, String place) {
        if (e.whitelist != null && e.whitelist.length > 0) {
            for (String p : e.whitelist) {
                if (p.equals(place)) {
                    return true;
                }
            }
            return false;
        }
        if (e.blacklist != null) {
            for (String p : e.blacklist) {
                if (p.equals(place)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Called from AndroidUtilities.openForView(MessageObject): an installer or a program from a chat asks first
     * (SolegramSecurity); then plugins and .plugin files. The rest stays Telegram's.
     */
    public static boolean tryOpen(File file, String fileName, MessageObject message, Activity activity,
                                  String mimeType, org.telegram.ui.ActionBar.Theme.ResourcesProvider resourcesProvider, boolean restrict) {
        if (file == null || !file.exists()) {
            return false;
        }
        if (!restrict && activity != null && SolegramSecurity.needsWarning(fileName != null ? fileName : file.getName(), message)) {
            SolegramSecurity.confirmOpen(activity, fileName != null ? fileName : file.getName(), message, () -> {
                if (!tryOpen(file, fileName, message, activity)) {
                    org.telegram.messenger.AndroidUtilities.openForView(file, fileName, mimeType, activity, resourcesProvider, false);
                }
            });
            return true;
        }
        return tryOpen(file, fileName, message, activity);
    }

    /** Plugins' own file types and .plugin files. */
    public static boolean tryOpen(File file, String fileName, MessageObject message, Activity activity) {
        if (file == null || !file.exists()) {
            return false;
        }
        String ext = extOf(fileName != null ? fileName : file.getName());
        if (ext == null) {
            return false;
        }
        BaseFragment fragment = LaunchActivity.getSafeLastFragment();
        String place = fragment == null ? "UNKNOWN" : fragment.getClass().getSimpleName();
        Entry e = handlers.get(ext);
        if (e != null && placeAllowed(e, place)) {
            try {
                if (e.handler.onClick(place, file, fileName, message, activity, fragment)) {
                    return true;
                }
            } catch (Throwable t) {
                FileLog.e(t);
            }
        }
        if (PluginsController.isPluginFileName(fileName) || PluginsController.isPluginFileName(file.getName())) {
            if (fragment != null) {
                PluginInstallSheet.show(fragment, file);
                return true;
            }
        }
        return false;
    }
}
