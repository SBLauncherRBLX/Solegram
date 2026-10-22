/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger;

import android.view.View;

import org.telegram.messenger.FileLog;

import java.lang.reflect.Method;
import java.util.ArrayList;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/** In-process hooks (AliuHook) for options Telegram has no single switch for. */
public final class SystemHooksImpl implements Solegram.SystemHooks {

    private final ArrayList<XC_MethodHook.Unhook> vibrationUnhooks = new ArrayList<>();
    private boolean vibrationFailed;

    @Override
    public synchronized void setVibrationDisabled(boolean disabled) {
        if (!disabled || vibrationFailed || !vibrationUnhooks.isEmpty()) {
            // Hooks stay installed once added and simply check the flag, so turning the option off is instant.
            return;
        }
        XC_MethodHook haptics = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (SolegramConfig.disableVibration) {
                    param.setResult(false);
                }
            }
        };
        XC_MethodHook vibrator = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (SolegramConfig.disableVibration) {
                    param.setResult(null);
                }
            }
        };
        try {
            for (Method m : View.class.getDeclaredMethods()) {
                if (m.getName().equals("performHapticFeedback") && m.getReturnType() == boolean.class) {
                    vibrationUnhooks.add(XposedBridge.hookMethod(m, haptics));
                }
            }
            hookVibrate("android.os.SystemVibrator", vibrator);
            hookVibrate("android.os.Vibrator", vibrator);
        } catch (Throwable e) {
            vibrationFailed = true;
            FileLog.e(e);
        }
    }

    private void hookVibrate(String className, XC_MethodHook hook) {
        Class<?> cls;
        try {
            cls = Class.forName(className);
        } catch (Throwable e) {
            return;
        }
        for (Method m : cls.getDeclaredMethods()) {
            if (!m.getName().equals("vibrate") || m.getReturnType() != void.class || java.lang.reflect.Modifier.isAbstract(m.getModifiers())) {
                continue;
            }
            try {
                vibrationUnhooks.add(XposedBridge.hookMethod(m, hook));
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
    }
}
