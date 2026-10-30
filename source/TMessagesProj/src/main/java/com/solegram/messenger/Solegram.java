/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

import com.solegram.messenger.plugins.PluginsController;
import com.solegram.messenger.updater.UpdateController;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.FileLog;

/** Solegram entry points called from Telegram's application lifecycle. */
public final class Solegram {

    public static final String NAME = "Solegram";
    public static final String SOURCE_URL = "https://github.com/DrKLO/Telegram";
    /** Repository with update.json and release APKs (owner/name). */
    public static final String GITHUB_REPO = "SBLauncherRBLX/Solegram";

    private static boolean inited;
    private static boolean postInited;

    /** System-level hooks implemented in the app module (method hooking is not available to the library). */
    public interface SystemHooks {
        void setVibrationDisabled(boolean disabled);
    }

    private static volatile SystemHooks systemHooks;

    public static void setSystemHooks(SystemHooks hooks) {
        systemHooks = hooks;
    }

    /** Applies "Disable vibration" (global haptics + vibrator). */
    public static void applyVibrationSetting() {
        SystemHooks hooks = systemHooks;
        if (hooks == null) {
            return;
        }
        try {
            hooks.setVibrationDisabled(SolegramConfig.disableVibration);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private Solegram() {
    }

    /** Early init: before accounts and connections are created. */
    public static void init() {
        if (inited) {
            return;
        }
        inited = true;
        try {
            SolegramConfig.load();
            MessageVault.init();
            // "hide in recent apps" follows every activity of the app
            SolegramSecurity.init();
            if (SolegramConfig.disableVibration) {
                applyVibrationSetting();
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /** Late init: accounts are loaded. */
    public static void postInit() {
        if (postInited) {
            return;
        }
        postInited = true;
        try {
            PluginsController.getInstance().init();
        } catch (Throwable e) {
            FileLog.e(e);
        }
        // reminders: missed ones are shown and the alarm is set again (a force stop or an update removes it)
        SolegramReminders.warmUp();
        // the ad block list, loaded in the background when blocking is on
        AndroidUtilities.runOnUIThread(SolegramAdBlock::warmUp, 5000);
        // proxy auto mode watches the connection from here on
        AndroidUtilities.runOnUIThread(() -> {
            try {
                SolegramProxy.init();
            } catch (Throwable e) {
                FileLog.e(e);
            }
        });
        AndroidUtilities.runOnUIThread(() -> {
            try {
                // music in the profile: watch the players, and bring a bio left with music back after a restart
                SolegramMediaListener.setWanted(SolegramConfig.nowPlaying);
                SolegramNowPlaying.start();
                SolegramNowPlayingBio.checkOnStart();
                org.telegram.messenger.AndroidUtilities.runOnUIThread(SolegramNameEmojiShare::scheduleSync, 25_000);
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }, 1000); // after the first frame: it asks the system for the listener access and the players, and scales the cover
        // updates: the background schedule, and "Solegram updated" on the first start of a new version
        AndroidUtilities.runOnUIThread(() -> {
            try {
                com.solegram.messenger.updater.AutoUpdater.start();
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }, 1500);
        AndroidUtilities.runOnUIThread(() -> {
            try {
                UpdateController.getInstance().scheduleAutoCheck();
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }, 15000);
    }

    public static String getVersionName() {
        return BuildConfig.SOLEGRAM_VERSION_NAME;
    }

    public static int getBuild() {
        return BuildConfig.SOLEGRAM_BUILD;
    }

    /** "Solegram 1.0.0 (1) · Telegram 12.10.6" */
    public static String getFullVersion() {
        return NAME + " " + BuildConfig.SOLEGRAM_VERSION_NAME + " (" + BuildConfig.SOLEGRAM_BUILD + ") · Telegram " + BuildConfig.BUILD_VERSION_STRING;
    }

    /** Restarts the process and opens the app again. */
    public static void restartApp(Activity activity) {
        Context ctx = activity != null ? activity : ApplicationLoader.applicationContext;
        try {
            Intent intent = ctx.getPackageManager().getLaunchIntentForPackage(ctx.getPackageName());
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                ctx.startActivity(intent);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        if (activity != null) {
            activity.finishAffinity();
        }
        AndroidUtilities.runOnUIThread(() -> Runtime.getRuntime().exit(0), 300);
    }
}
