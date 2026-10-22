/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger;

import android.content.ComponentName;
import android.os.Build;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

/**
 * Music in the profile: Android lets an app see other apps' media sessions (what plays in Spotify or
 * SoundCloud) only while it holds notification access, so this listener exists to hold it. Notifications
 * themselves are ignored.
 */
public class SolegramMediaListener extends NotificationListenerService {

    /** The bound listener, so it can let go while the feature is off. */
    private static volatile SolegramMediaListener instance;

    /**
     * With the feature off the system need not keep the listener (and the app) bound; turned on again, it
     * binds anew. Android 7+ only: before that the listener simply stays bound and idle.
     */
    public static void setWanted(boolean wanted) {
        if (Build.VERSION.SDK_INT < 24) {
            return;
        }
        try {
            final SolegramMediaListener bound = instance;
            if (!wanted && bound != null) {
                bound.requestUnbind();
            } else if (wanted && bound == null && SolegramNowPlaying.hasAccess()) {
                requestRebind(new ComponentName(ApplicationLoader.applicationContext, SolegramMediaListener.class));
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        instance = this;
        if (!SolegramConfig.nowPlaying) {
            setWanted(false);
            return;
        }
        AndroidUtilities.runOnUIThread(SolegramNowPlaying::start);
    }

    @Override
    public void onListenerDisconnected() {
        instance = null;
        AndroidUtilities.runOnUIThread(SolegramNowPlaying::stop);
        super.onListenerDisconnected();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        // not used: only the media sessions matter
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        // not used
    }
}
