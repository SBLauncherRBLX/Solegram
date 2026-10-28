/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.ContactsController;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.FlagSecureReason;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;

import java.util.Locale;
import java.util.WeakHashMap;

/**
 * The user-visible security switches: the app hidden in the recent apps, and a question before an installer or
 * a program file from a chat is opened.
 */
public final class SolegramSecurity {

    private SolegramSecurity() {
    }

    // region Recent apps

    /** The app's live activities; on older Android each with its FlagSecureReason, counted with Telegram's own. */
    private static final WeakHashMap<Activity, FlagSecureReason> reasons = new WeakHashMap<>();
    private static boolean inited;

    /** Watches every activity of the app from now on. Main thread, once at start. */
    public static void init() {
        if (inited || !(ApplicationLoader.applicationContext instanceof Application)) {
            return;
        }
        inited = true;
        ((Application) ApplicationLoader.applicationContext).registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
                apply(activity);
            }

            @Override
            public void onActivityStarted(Activity activity) {
                apply(activity);
            }

            @Override
            public void onActivityResumed(Activity activity) {
            }

            @Override
            public void onActivityPaused(Activity activity) {
            }

            @Override
            public void onActivityStopped(Activity activity) {
            }

            @Override
            public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
            }

            @Override
            public void onActivityDestroyed(Activity activity) {
                final FlagSecureReason reason = reasons.remove(activity);
                if (reason != null) {
                    reason.detach();
                }
            }
        });
    }

    /** The setting changed: every open activity follows it now. */
    public static void applyToAll() {
        for (Activity activity : new java.util.ArrayList<>(reasons.keySet())) {
            apply(activity);
        }
    }

    /**
     * Android 13+: no screenshot of the app for the recent apps, screenshots stay allowed. Older Android has only
     * FLAG_SECURE, which hides the recent apps picture and blocks screenshots as well.
     */
    private static void apply(Activity activity) {
        if (activity == null) {
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                reasons.put(activity, null);
                activity.setRecentsScreenshotEnabled(!SolegramConfig.secureRecents);
                return;
            }
            FlagSecureReason reason = reasons.get(activity);
            if (reason == null) {
                reason = new FlagSecureReason(activity.getWindow(), () -> SolegramConfig.secureRecents);
                reasons.put(activity, reason);
                reason.attach();
            } else {
                reason.invalidate();
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }
    // endregion

    // region Dangerous files

    /** Installers: they can replace or add apps. Asked about whoever sent them. */
    private static final String[] INSTALLERS = {"apk", "apks", "xapk", "apkm", "aab"};
    /** Programs and scripts for computers and phones: asked about when the sender is not a contact. */
    private static final String[] PROGRAMS = {"exe", "msi", "bat", "cmd", "com", "scr", "pif", "vbs", "vbe", "js", "jse", "wsf",
        "hta", "ps1", "jar", "lnk", "reg", "dll", "sh", "dmg", "pkg", "app", "deb", "rpm", "appimage", "iso", "img"};

    private static boolean in(String[] list, String ext) {
        for (String e : list) {
            if (e.equals(ext)) {
                return true;
            }
        }
        return false;
    }

    private static String extOf(String fileName) {
        if (TextUtils.isEmpty(fileName)) {
            return null;
        }
        final int dot = fileName.lastIndexOf('.');
        return dot < 0 || dot == fileName.length() - 1 ? null : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * Whether opening this file from this message needs the user's yes first: an installer from anyone but the
     * user, a program from someone who is not a contact. Messages of the user (and Saved Messages) never ask.
     */
    public static boolean needsWarning(String fileName, MessageObject message) {
        if (!SolegramConfig.warnDangerousFiles || message == null || message.messageOwner == null) {
            return false;
        }
        final String ext = extOf(fileName);
        if (ext == null) {
            return false;
        }
        final boolean installer = in(INSTALLERS, ext);
        if (!installer && !in(PROGRAMS, ext)) {
            return false;
        }
        if (message.isOutOwner() || message.getDialogId() == UserConfig.getInstance(message.currentAccount).getClientUserId()) {
            return false;
        }
        return installer || !isContact(message);
    }

    private static boolean isContact(MessageObject message) {
        try {
            final long from = message.getFromChatId();
            if (from <= 0) {
                return false; // a channel or a group posting as itself
            }
            final TLRPC.User user = MessagesController.getInstance(message.currentAccount).getUser(from);
            return user != null && (user.contact || UserObject.isService(user.id)
                || ContactsController.getInstance(message.currentAccount).contactsDict.containsKey(user.id));
        } catch (Throwable e) {
            return false;
        }
    }

    /** "Open this file?" before an installer or a program from a chat is handed to the system; {@code open} on yes. */
    public static void confirmOpen(Activity activity, String fileName, MessageObject message, Runnable open) {
        if (activity == null) {
            return;
        }
        final String ext = extOf(fileName);
        final boolean installer = ext != null && in(INSTALLERS, ext);
        String sender = null;
        try {
            final long from = message.getFromChatId();
            if (from > 0) {
                sender = UserObject.getUserName(MessagesController.getInstance(message.currentAccount).getUser(from));
            } else if (from < 0) {
                final TLRPC.Chat chat = MessagesController.getInstance(message.currentAccount).getChat(-from);
                sender = chat != null ? chat.title : null;
            }
        } catch (Throwable ignore) {
        }
        final String name = TextUtils.isEmpty(fileName) ? "" : fileName;
        final CharSequence text = AndroidUtilities.replaceTags(TextUtils.isEmpty(sender)
            ? LocaleController.formatString(installer ? R.string.SG_SecFileApkText : R.string.SG_SecFileProgramText, name)
            : LocaleController.formatString(installer ? R.string.SG_SecFileApkFromText : R.string.SG_SecFileProgramFromText, name, sender));
        final BaseFragment fragment = org.telegram.ui.LaunchActivity.getSafeLastFragment();
        final Context context = activity;
        final AlertDialog dialog = new AlertDialog.Builder(context, fragment != null ? fragment.getResourceProvider() : null)
            .setTitle(LocaleController.getString(installer ? R.string.SG_SecFileApkTitle : R.string.SG_SecFileProgramTitle))
            .setMessage(text)
            .setPositiveButton(LocaleController.getString(R.string.SG_SecFileOpen), (d, w) -> open.run())
            .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
            .create();
        if (fragment != null && fragment.getParentActivity() == activity) {
            fragment.showDialog(dialog);
        } else {
            dialog.show();
        }
        final android.view.View button = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (button instanceof android.widget.TextView) {
            ((android.widget.TextView) button).setTextColor(Theme.getColor(Theme.key_text_RedBold));
        }
    }
    // endregion
}
