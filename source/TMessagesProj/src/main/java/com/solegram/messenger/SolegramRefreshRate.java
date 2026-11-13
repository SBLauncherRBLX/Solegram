/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.view.Display;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

/**
 * The fastest refresh rate the screen has (120 Hz and more where there is one), for every window of the app: the
 * main one, sheets and dialogs. Telegram asks for it only in a few windows, so phones with an adaptive refresh rate
 * often kept Solegram at 60 Hz. Each window now picks the display mode with the highest rate at the current
 * resolution, and the animations learn the real rate as soon as the screen switches to it.
 */
public final class SolegramRefreshRate {

    private static boolean listening;

    private SolegramRefreshRate() {
    }

    /** The display mode with the highest refresh rate at the current resolution, or null. */
    @Nullable
    private static Display.Mode fastestMode(Display display) {
        if (Build.VERSION.SDK_INT < 23 || display == null) {
            return null;
        }
        final Display.Mode current = display.getMode();
        Display.Mode best = current;
        for (Display.Mode m : display.getSupportedModes()) {
            if (m.getPhysicalWidth() == current.getPhysicalWidth() && m.getPhysicalHeight() == current.getPhysicalHeight()
                && m.getRefreshRate() > best.getRefreshRate() + 0.1f) {
                best = m;
            }
        }
        return best;
    }

    /** Asks for the fastest mode in this window (call before or after the window is shown). */
    public static void apply(@Nullable Window window) {
        if (window == null) {
            return;
        }
        try {
            final Display display = Build.VERSION.SDK_INT >= 30 ? window.getContext().getDisplay()
                : ((WindowManager) window.getContext().getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay();
            final WindowManager.LayoutParams params = window.getAttributes();
            final Display.Mode mode = fastestMode(display);
            boolean changed = false;
            if (mode != null && params.preferredDisplayModeId != mode.getModeId()) {
                params.preferredDisplayModeId = mode.getModeId();
                changed = true;
            }
            final float rate = mode != null ? mode.getRefreshRate() : AndroidUtilities.screenMaxRefreshRate;
            if (Math.abs(params.preferredRefreshRate - rate) > 0.1f) {
                params.preferredRefreshRate = rate;
                changed = true;
            }
            if (changed) {
                window.setAttributes(params);
            }
            listen();
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /** Animations that step by the frame time follow the rate the screen really runs at. */
    private static void listen() {
        if (listening || Build.VERSION.SDK_INT < 23) {
            return;
        }
        listening = true;
        try {
            final DisplayManager dm = (DisplayManager) ApplicationLoader.applicationContext.getSystemService(Context.DISPLAY_SERVICE);
            if (dm == null) {
                return;
            }
            dm.registerDisplayListener(new DisplayManager.DisplayListener() {
                @Override
                public void onDisplayAdded(int displayId) {
                }

                @Override
                public void onDisplayRemoved(int displayId) {
                }

                @Override
                public void onDisplayChanged(int displayId) {
                    if (displayId != Display.DEFAULT_DISPLAY) {
                        return;
                    }
                    final Display d = dm.getDisplay(displayId);
                    if (d != null) {
                        final float rate = d.getRefreshRate();
                        if (rate > 1) {
                            AndroidUtilities.screenRefreshRate = rate;
                            AndroidUtilities.screenRefreshTime = 1000 / rate;
                            AndroidUtilities.screenMaxRefreshRate = Math.max(AndroidUtilities.screenMaxRefreshRate, rate);
                        }
                    }
                }
            }, null);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }
}
