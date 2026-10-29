/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;

import com.solegram.messenger.ui.SolegramHiddenChatsActivity;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.NotificationsController;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.PasscodeActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;

/**
 * Hidden chats: chats kept out of every list of this device (the chat list, folders, the archive, sharing,
 * search and its suggestions, the unread badges) until the user unlocks them with a fingerprint, the face or the
 * screen lock. Nothing changes on the server: a hidden chat is not archived, muted or deleted, it still gets
 * its messages.
 * <p>
 * The ids are kept per Telegram user (a new account in a freed slot does not inherit them) in
 * {@code no_backup/solegram_hidden.json}: neither Android's backup nor Solegram's settings file carry them.
 * Reads are lock free and allocation free (a sorted array per user, swapped whole on a change), because the
 * chat list asks for every dialog each time it sorts.
 */
public final class SolegramHiddenChats {

    private SolegramHiddenChats() {
    }

    /** Notifications of hidden chats: as for any chat, only "New message", or none at all. */
    public static final int NOTIFY_NORMAL = 0, NOTIFY_NO_TEXT = 1, NOTIFY_OFF = 2;

    private static final String FILE = "solegram_hidden.json";

    /** Everything at one moment; never changed once published. */
    private static final class State {
        final long[] users;
        final long[][] ids;
        final int notify;
        final boolean gesture;

        State(long[] users, long[][] ids, int notify, boolean gesture) {
            this.users = users;
            this.ids = ids;
            this.notify = notify;
            this.gesture = gesture;
        }
    }

    private static volatile State state;

    private static State state() {
        State s = state;
        if (s == null) {
            synchronized (SolegramHiddenChats.class) {
                s = state;
                if (s == null) {
                    state = s = read();
                }
            }
        }
        return s;
    }

    // region Reading

    /**
     * The hidden ids of an account, sorted, or null when it has none. Meant to be taken once before a loop
     * over many dialogs and checked with {@link #contains}.
     */
    public static long[] idsFor(int account) {
        final State s = state();
        if (s.users.length == 0 || account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) {
            return null;
        }
        final long user = UserConfig.getInstance(account).getClientUserId();
        if (user == 0) {
            return null;
        }
        for (int i = 0; i < s.users.length; i++) {
            if (s.users[i] == user) {
                return s.ids[i].length == 0 ? null : s.ids[i];
            }
        }
        return null;
    }

    public static boolean contains(long[] ids, long dialogId) {
        return ids != null && dialogId != 0 && Arrays.binarySearch(ids, dialogId) >= 0;
    }

    public static boolean isHidden(int account, long dialogId) {
        return contains(idsFor(account), dialogId);
    }

    /** A user, a chat or a secret chat of a search result or a suggestion. */
    public static boolean isHiddenPeer(int account, Object peer) {
        final long[] ids = idsFor(account);
        if (ids == null || peer == null) {
            return false;
        }
        if (peer instanceof TLRPC.User) {
            return contains(ids, ((TLRPC.User) peer).id);
        } else if (peer instanceof TLRPC.Chat) {
            return contains(ids, -((TLRPC.Chat) peer).id);
        } else if (peer instanceof TLRPC.EncryptedChat) {
            return contains(ids, DialogObject.makeEncryptedDialogId(((TLRPC.EncryptedChat) peer).id));
        }
        return false;
    }

    public static int count(int account) {
        final long[] ids = idsFor(account);
        return ids == null ? 0 : ids.length;
    }

    public static int notifyMode() {
        return state().notify;
    }

    /** Holding the title of the chats screen opens the hidden chats. */
    public static boolean gestureEnabled() {
        return state().gesture;
    }

    /** "No notifications": a new message of this chat makes no sound and shows nothing. */
    public static boolean silences(int account, long dialogId) {
        return state().notify == NOTIFY_OFF && isHidden(account, dialogId);
    }

    /** "Without text": the notification says only "New message", no name, no picture, no text. */
    public static boolean masks(int account, long dialogId) {
        return state().notify == NOTIFY_NO_TEXT && isHidden(account, dialogId);
    }

    /** Whether this chat can be hidden: Saved Messages, the promoted channel and folders cannot. */
    public static boolean canHide(int account, long dialogId) {
        if (dialogId == 0 || dialogId == UserConfig.getInstance(account).getClientUserId()) {
            return false;
        }
        final MessagesController controller = MessagesController.getInstance(account);
        if (controller.isPromoDialog(dialogId, false)) {
            return false;
        }
        final TLRPC.Dialog dialog = controller.dialogs_dict.get(dialogId);
        return !(dialog instanceof TLRPC.TL_dialogFolder) && !(dialog instanceof TLRPC.TL_dialogCommunity);
    }
    // endregion

    // region Changing

    /** @return how many of these chats were hidden now */
    public static int hide(int account, Collection<Long> dialogIds) {
        final ArrayList<Long> added = new ArrayList<>();
        for (Long id : dialogIds) {
            if (id != null && canHide(account, id) && !isHidden(account, id)) {
                added.add(id);
            }
        }
        if (added.isEmpty()) {
            return 0;
        }
        change(account, added, true);
        // what already sits in the notification shade goes, name and text with it
        final NotificationsController notifications = NotificationsController.getInstance(account);
        for (Long id : added) {
            notifications.removeNotificationsForDialog(id);
        }
        return added.size();
    }

    public static void unhide(int account, long dialogId) {
        if (isHidden(account, dialogId)) {
            final ArrayList<Long> list = new ArrayList<>();
            list.add(dialogId);
            change(account, list, false);
        }
    }

    public static void setNotifyMode(int mode) {
        synchronized (SolegramHiddenChats.class) {
            final State s = state();
            state = new State(s.users, s.ids, mode, s.gesture);
            save();
        }
    }

    public static void setGestureEnabled(boolean enabled) {
        synchronized (SolegramHiddenChats.class) {
            final State s = state();
            state = new State(s.users, s.ids, s.notify, enabled);
            save();
        }
    }

    private static void change(int account, Collection<Long> dialogIds, boolean hide) {
        final long user = UserConfig.getInstance(account).getClientUserId();
        if (user == 0) {
            return;
        }
        synchronized (SolegramHiddenChats.class) {
            final State s = state();
            int index = -1;
            for (int i = 0; i < s.users.length; i++) {
                if (s.users[i] == user) {
                    index = i;
                    break;
                }
            }
            final long[] old = index >= 0 ? s.ids[index] : new long[0];
            final ArrayList<Long> list = new ArrayList<>();
            for (long id : old) {
                list.add(id);
            }
            if (hide) {
                for (Long id : dialogIds) {
                    if (!list.contains(id)) {
                        list.add(id);
                    }
                }
            } else {
                list.removeAll(dialogIds);
            }
            final long[] ids = new long[list.size()];
            for (int i = 0; i < ids.length; i++) {
                ids[i] = list.get(i);
            }
            Arrays.sort(ids);
            final long[] users;
            final long[][] all;
            if (index >= 0) {
                users = s.users;
                all = s.ids.clone();
                all[index] = ids;
            } else {
                users = Arrays.copyOf(s.users, s.users.length + 1);
                users[s.users.length] = user;
                all = Arrays.copyOf(s.ids, s.ids.length + 1);
                all[s.ids.length] = ids;
            }
            state = new State(users, all, s.notify, s.gesture);
            save();
        }
        applied(account);
    }

    /** Every list, counter and suggestion of the account follows the change at once. */
    private static void applied(int account) {
        final MessagesController controller = MessagesController.getInstance(account);
        controller.sortDialogs(null);
        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogsNeedReload);
        // the unread counters of the chat list, the folders and the archive are counted again without them
        final MessagesStorage storage = MessagesStorage.getInstance(account);
        storage.getStorageQueue().postRunnable(() -> storage.resetAllUnreadCounters(false));
        NotificationsController.getInstance(account).updateBadge();
        final MediaDataController media = MediaDataController.getInstance(account);
        filterHints(account, media.hints);
        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.reloadHints);
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.solegramSettingsChanged, "hiddenChats");
    }

    /** Search suggestions (top peers) without the hidden chats. */
    public static void filterHints(int account, ArrayList<TLRPC.TL_topPeer> hints) {
        final long[] ids = idsFor(account);
        if (ids == null || hints == null) {
            return;
        }
        for (int i = hints.size() - 1; i >= 0; i--) {
            final TLRPC.TL_topPeer peer = hints.get(i);
            if (peer != null && peer.peer != null && contains(ids, DialogObject.getPeerDialogId(peer.peer))) {
                hints.remove(i);
            }
        }
    }
    // endregion

    // region File

    private static File file() {
        final Context context = ApplicationLoader.applicationContext;
        return new File(context.getNoBackupFilesDir(), FILE);
    }

    private static State read() {
        try {
            final File file = file();
            if (file.exists()) {
                final byte[] bytes = new byte[(int) file.length()];
                try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
                    int off = 0;
                    while (off < bytes.length) {
                        final int n = in.read(bytes, off, bytes.length - off);
                        if (n < 0) {
                            break;
                        }
                        off += n;
                    }
                }
                final JSONObject root = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
                final JSONObject byUser = root.optJSONObject("users");
                final ArrayList<Long> users = new ArrayList<>();
                final ArrayList<long[]> ids = new ArrayList<>();
                if (byUser != null) {
                    for (Iterator<String> it = byUser.keys(); it.hasNext(); ) {
                        final String key = it.next();
                        final JSONArray array = byUser.optJSONArray(key);
                        if (array == null) {
                            continue;
                        }
                        final long[] list = new long[array.length()];
                        for (int i = 0; i < list.length; i++) {
                            list[i] = array.optLong(i);
                        }
                        Arrays.sort(list);
                        users.add(Long.parseLong(key));
                        ids.add(list);
                    }
                }
                final long[] userArray = new long[users.size()];
                for (int i = 0; i < userArray.length; i++) {
                    userArray[i] = users.get(i);
                }
                return new State(userArray, ids.toArray(new long[0][]), root.optInt("notify", NOTIFY_NORMAL), root.optBoolean("gesture", true));
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return new State(new long[0], new long[0][], NOTIFY_NORMAL, true);
    }

    /** Written in the background, in order; the whole file is replaced at once. Under the class lock. */
    private static void save() {
        final State s = state;
        final String json;
        try {
            final JSONObject root = new JSONObject();
            root.put("version", 1);
            root.put("notify", s.notify);
            root.put("gesture", s.gesture);
            final JSONObject byUser = new JSONObject();
            for (int i = 0; i < s.users.length; i++) {
                if (s.ids[i].length == 0) {
                    continue;
                }
                final JSONArray array = new JSONArray();
                for (long id : s.ids[i]) {
                    array.put(id);
                }
                byUser.put(String.valueOf(s.users[i]), array);
            }
            root.put("users", byUser);
            json = root.toString();
        } catch (Throwable e) {
            FileLog.e(e);
            return;
        }
        Utilities.globalQueue.postRunnable(() -> {
            try {
                final File file = file();
                final File tmp = new File(file.getParentFile(), FILE + ".tmp");
                try (FileOutputStream out = new FileOutputStream(tmp)) {
                    out.write(json.getBytes(StandardCharsets.UTF_8));
                    out.getFD().sync();
                }
                if (!tmp.renameTo(file)) {
                    FileLog.e("solegram hidden chats: could not save");
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
        });
    }
    // endregion

    // region Unlocking

    private static final int AUTHENTICATORS = BiometricManager.Authenticators.BIOMETRIC_WEAK | BiometricManager.Authenticators.DEVICE_CREDENTIAL;

    /** Asks for the fingerprint, the face or the screen lock, then opens the hidden chats. */
    public static void open(BaseFragment from) {
        unlock(from, () -> from.presentFragment(new SolegramHiddenChatsActivity()));
    }

    /**
     * The phone's own lock (BiometricPrompt with the screen lock as the other way in); where the phone has
     * none, Telegram's passcode; where there is neither, a word about setting one.
     */
    public static void unlock(BaseFragment from, Runnable onUnlocked) {
        final Activity activity = from != null ? from.getParentActivity() : null;
        if (!(activity instanceof FragmentActivity)) {
            return;
        }
        int can;
        try {
            can = BiometricManager.from(activity).canAuthenticate(AUTHENTICATORS);
        } catch (Throwable e) {
            can = BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE;
        }
        if (can != BiometricManager.BIOMETRIC_SUCCESS) {
            fallback(from, onUnlocked);
            return;
        }
        try {
            final BiometricPrompt prompt = new BiometricPrompt((FragmentActivity) activity, ContextCompat.getMainExecutor(activity),
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                        if (from.getParentActivity() != null && !from.isFinished) {
                            onUnlocked.run();
                        }
                    }

                    @Override
                    public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                        switch (errorCode) {
                            case BiometricPrompt.ERROR_HW_NOT_PRESENT:
                            case BiometricPrompt.ERROR_HW_UNAVAILABLE:
                            case BiometricPrompt.ERROR_NO_BIOMETRICS:
                            case BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL:
                                if (from.getParentActivity() != null) {
                                    fallback(from, onUnlocked);
                                }
                                break;
                            default:
                                break; // cancelled by the user, or locked out: nothing opens
                        }
                    }
                });
            final BiometricPrompt.PromptInfo info = new BiometricPrompt.PromptInfo.Builder()
                .setTitle(LocaleController.getString(R.string.SG_HiddenChats))
                .setSubtitle(LocaleController.getString(R.string.SG_HiddenAuthSubtitle))
                .setAllowedAuthenticators(AUTHENTICATORS)
                .setConfirmationRequired(false)
                .build();
            prompt.authenticate(info);
        } catch (Throwable e) {
            FileLog.e(e);
            fallback(from, onUnlocked);
        }
    }

    private static void fallback(BaseFragment from, Runnable onUnlocked) {
        if (SharedConfig.passcodeHash.length() > 0) {
            askPasscode(from, onUnlocked);
        } else {
            showNoLock(from);
        }
    }

    /** Telegram's own passcode, with Telegram's waits after wrong tries. */
    private static void askPasscode(BaseFragment from, Runnable onUnlocked) {
        final Context context = from.getParentActivity();
        if (context == null) {
            return;
        }
        final Theme.ResourcesProvider resources = from.getResourceProvider();
        final EditTextBoldCursor field = new EditTextBoldCursor(context);
        field.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        field.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resources));
        field.setHintText(LocaleController.getString(R.string.SG_HiddenPasscodeHint));
        field.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint, resources));
        field.setSingleLine(true);
        field.setInputType(SharedConfig.passcodeType == SharedConfig.PASSCODE_TYPE_PIN
            ? InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD
            : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.setTransformationMethod(PasswordTransformationMethod.getInstance());
        field.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        field.setLineColors(Theme.getColor(Theme.key_windowBackgroundWhiteInputField, resources),
            Theme.getColor(Theme.key_windowBackgroundWhiteInputFieldActivated, resources), Theme.getColor(Theme.key_text_RedRegular, resources));
        field.setBackground(null);
        final FrameLayout container = new FrameLayout(context);
        container.addView(field, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 36, Gravity.TOP, 24, 8, 24, 8));

        final AlertDialog[] dialog = new AlertDialog[1];
        final Runnable check = () -> {
            final long now = SystemClock.elapsedRealtime();
            if (now > SharedConfig.lastUptimeMillis) {
                SharedConfig.passcodeRetryInMs = Math.max(0, SharedConfig.passcodeRetryInMs - (now - SharedConfig.lastUptimeMillis));
            }
            SharedConfig.lastUptimeMillis = now;
            if (SharedConfig.passcodeRetryInMs > 0) {
                final int seconds = Math.max(1, (int) Math.ceil(SharedConfig.passcodeRetryInMs / 1000.0));
                field.setErrorText(LocaleController.formatString(R.string.TooManyTries, LocaleController.formatPluralString("Seconds", seconds)));
                AndroidUtilities.shakeViewSpring(field, -6);
                SharedConfig.saveConfig();
                return;
            }
            final String code = field.getText() == null ? "" : field.getText().toString();
            if (code.length() > 0 && SharedConfig.checkPasscode(code)) {
                SharedConfig.badPasscodeTries = 0;
                SharedConfig.saveConfig();
                AndroidUtilities.hideKeyboard(field);
                if (dialog[0] != null) {
                    dialog[0].dismiss();
                }
                onUnlocked.run();
            } else {
                SharedConfig.increaseBadPasscodeTries();
                field.setText("");
                field.setErrorText(LocaleController.getString(R.string.SG_HiddenPasscodeWrong));
                AndroidUtilities.shakeViewSpring(field, -6);
                com.solegram.messenger.ui.SolegramMotion.haptic(field);
            }
        };
        field.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                check.run();
                return true;
            }
            return false;
        });
        final AlertDialog.Builder builder = new AlertDialog.Builder(context, resources);
        builder.setTitle(LocaleController.getString(R.string.SG_HiddenChats));
        builder.setMessage(LocaleController.getString(R.string.SG_HiddenPasscodeText));
        builder.setView(container);
        // a wrong code keeps the dialog open: the buttons close it themselves
        builder.setPositiveButton(LocaleController.getString(R.string.OK), (d, w) -> check.run());
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), (d, w) -> d.dismiss());
        dialog[0] = builder.create();
        dialog[0].setDismissDialogByButtons(false);
        dialog[0].setOnShowListener(d -> AndroidUtilities.runOnUIThread(() -> {
            field.requestFocus();
            AndroidUtilities.showKeyboard(field);
        }, 150));
        from.showDialog(dialog[0]);
    }

    /** Nothing locks this phone: hidden chats would open for anyone, so they do not open. */
    private static void showNoLock(BaseFragment from) {
        final Activity activity = from.getParentActivity();
        if (activity == null) {
            return;
        }
        final AlertDialog.Builder builder = new AlertDialog.Builder(activity, from.getResourceProvider());
        builder.setTitle(LocaleController.getString(R.string.SG_HiddenNoLockTitle));
        builder.setMessage(LocaleController.getString(R.string.SG_HiddenNoLockText));
        builder.setPositiveButton(LocaleController.getString(R.string.SG_HiddenSetPhoneLock), (d, w) -> {
            try {
                activity.startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS));
            } catch (Throwable e) {
                try {
                    activity.startActivity(new Intent(Settings.ACTION_SETTINGS));
                } catch (Throwable ignore) {
                }
            }
        });
        builder.setNeutralButton(LocaleController.getString(R.string.SG_HiddenSetPasscode), (d, w) -> from.presentFragment(PasscodeActivity.determineOpenFragment()));
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        from.showDialog(builder.create());
    }
    // endregion

    // region One visit

    private static WeakReference<SolegramHiddenChatsActivity> visit;
    private static boolean watching;

    /**
     * An unlock lasts one visit: when the app leaves the screen (home, another app, the screen goes off) the
     * hidden chats close, with everything opened from them, and the next look asks again. Main thread.
     */
    public static void startVisit(SolegramHiddenChatsActivity screen) {
        visit = new WeakReference<>(screen);
        if (watching || !(ApplicationLoader.applicationContext instanceof Application)) {
            return;
        }
        watching = true;
        ((Application) ApplicationLoader.applicationContext).registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityStopped(@NonNull Activity activity) {
                final SolegramHiddenChatsActivity screen = visit != null ? visit.get() : null;
                if (screen != null && screen.getParentActivity() == activity && !activity.isChangingConfigurations()) {
                    visit = null;
                    screen.lock();
                }
            }

            @Override
            public void onActivityCreated(@NonNull Activity activity, Bundle savedInstanceState) {
            }

            @Override
            public void onActivityStarted(@NonNull Activity activity) {
            }

            @Override
            public void onActivityResumed(@NonNull Activity activity) {
            }

            @Override
            public void onActivityPaused(@NonNull Activity activity) {
            }

            @Override
            public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {
            }

            @Override
            public void onActivityDestroyed(@NonNull Activity activity) {
            }
        });
    }

    public static void endVisit(SolegramHiddenChatsActivity screen) {
        if (visit != null && visit.get() == screen) {
            visit = null;
        }
    }
    // endregion
}
