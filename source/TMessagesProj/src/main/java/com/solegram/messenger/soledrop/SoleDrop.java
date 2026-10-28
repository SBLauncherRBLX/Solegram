/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.soledrop;

import android.app.KeyguardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.nfc.NfcAdapter;
import android.os.Build;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.HapticFeedbackConstants;
import android.view.View;

import androidx.annotation.Nullable;

import com.solegram.messenger.SolegramConfig;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.LaunchActivity;

import java.lang.ref.WeakReference;

/**
 * SoleDrop: AirDrop / NameDrop between two Solegram phones held together. NFC only introduces the phones
 * (who sends what to whom, see {@link SoleDropProtocol}); the transfer itself goes through Telegram from the
 * sending phone, so money always passes Telegram's own confirmation and nothing big crosses NFC.
 * <p>
 * This class holds the settings, decides whether this phone answers a tap now, and brings what came in to the
 * screen: to an open SoleDrop screen (two phones exchanging contacts), over the open app, or (receiving
 * "always") by starting the app, which the system allows for a card emulation service.
 */
public final class SoleDrop {

    private SoleDrop() {
    }

    // region Settings

    public static final int RECEIVE_OFF = 0;
    /** Only while Solegram is on the screen (the default). */
    public static final int RECEIVE_OPEN = 1;
    /** Whenever the phone is unlocked. */
    public static final int RECEIVE_ALWAYS = 2;

    private static SharedPreferences prefs() {
        return SolegramConfig.prefs();
    }

    public static int receiveMode() {
        final int mode = prefs().getInt("soleDropMode", RECEIVE_OPEN);
        return mode < RECEIVE_OFF || mode > RECEIVE_ALWAYS ? RECEIVE_OPEN : mode;
    }

    public static void setReceiveMode(int mode) {
        prefs().edit().putInt("soleDropMode", mode).apply();
        applyServiceState();
        if (mode != RECEIVE_OFF) {
            SoleDropIdentity.refresh(receiveAccount(), null);
        }
    }

    /** The account that receives: a chosen one, or -1 for the account open in the app. */
    public static int receiveAccountSetting() {
        return prefs().getInt("soleDropAccount", -1);
    }

    public static void setReceiveAccount(int account) {
        prefs().edit().putInt("soleDropAccount", account).apply();
        SoleDropIdentity.refresh(receiveAccount(), null);
    }

    public static int receiveAccount() {
        final int chosen = receiveAccountSetting();
        if (chosen >= 0 && chosen < UserConfig.MAX_ACCOUNT_COUNT && UserConfig.getInstance(chosen).isClientActivated()) {
            return chosen;
        }
        return UserConfig.selectedAccount;
    }

    /** Name and username go over NFC (the other phone shows them at once); off: only after Telegram finds the account. */
    public static boolean shareName() {
        return prefs().getBoolean("soleDropShareName", true);
    }

    public static void setShareName(boolean share) {
        prefs().edit().putBoolean("soleDropShareName", share).apply();
    }
    // endregion

    // region NFC

    @Nullable
    public static NfcAdapter adapter() {
        try {
            return NfcAdapter.getDefaultAdapter(ApplicationLoader.applicationContext);
        } catch (Throwable e) {
            return null;
        }
    }

    /** The phone can read (send). */
    public static boolean hasNfc() {
        return ApplicationLoader.applicationContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_NFC) && adapter() != null;
    }

    /** The phone can be read (receive): host card emulation. */
    public static boolean hasHce() {
        return ApplicationLoader.applicationContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION);
    }

    public static boolean nfcEnabled() {
        final NfcAdapter adapter = adapter();
        try {
            return adapter != null && adapter.isEnabled();
        } catch (Throwable e) {
            return false;
        }
    }

    public static Intent nfcSettingsIntent() {
        return new Intent(android.provider.Settings.ACTION_NFC_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    /**
     * With receiving off the card service is switched off as a component: the phone does not answer SoleDrop
     * at all (no one can even tell the app is there).
     */
    public static void applyServiceState() {
        if (!hasHce()) {
            return;
        }
        try {
            final Context context = ApplicationLoader.applicationContext;
            final ComponentName component = new ComponentName(context, SoleDropHceService.class);
            final int want = receiveMode() == RECEIVE_OFF ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED : PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
            final PackageManager pm = context.getPackageManager();
            if (pm.getComponentEnabledSetting(component) != want) {
                pm.setComponentEnabledSetting(component, want, PackageManager.DONT_KILL_APP);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }
    // endregion

    // region Lifecycle

    /** The app came to the screen: tokens are made fresh for the next tap, the service follows the setting. */
    public static void onAppResumed() {
        AndroidUtilities.runOnUIThread(() -> {
            try {
                applyServiceState();
                if (receiveMode() != RECEIVE_OFF) {
                    SoleDropIdentity.refresh(receiveAccount(), null);
                }
                if (UserConfig.selectedAccount != receiveAccount()) {
                    SoleDropIdentity.refresh(UserConfig.selectedAccount, null);
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }, 1500);
    }

    /** Opens SoleDrop with something to send (null: the SoleDrop screen with its tiles, "My profile" chosen). */
    public static void open(@Nullable BaseFragment fragment, @Nullable SoleDropItem item) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        fragment.presentFragment(SoleDropActivity.send(item));
    }
    // endregion

    // region Receiving

    private static final SoleDropProtocol.RateLimit taps = new SoleDropProtocol.RateLimit(8, 60_000);
    private static final SoleDropProtocol.RateLimit shows = new SoleDropProtocol.RateLimit(4, 60_000);
    private static final int[] recentSessions = new int[8];
    private static final long[] recentTimes = new long[8];
    private static int recentHead;
    private static final SoleDropProtocol.Packet[] pending = new SoleDropProtocol.Packet[4];
    private static final boolean[] pendingConfirm = new boolean[4];
    private static int pendingHead;

    // receiving "always" with the app closed: whom the owner accepted, for a second tap within a minute
    private static final long APPROVAL_TIME = 60_000;
    private static long approvedUserId;
    @Nullable
    private static String approvedToken;
    private static long approvedUntil;

    /**
     * The owner accepted a SoleDrop asked about on a first tap: the same sender's next tap within a minute gets
     * this phone's identity. Nothing else is opened by it.
     */
    static void approve(SoleDropProtocol.Packet offer) {
        approvedUserId = offer.userId;
        approvedToken = offer.token;
        approvedUntil = SystemClock.elapsedRealtime() + APPROVAL_TIME;
    }

    static void clearApproval() {
        approvedUserId = 0;
        approvedToken = null;
        approvedUntil = 0;
    }

    private static boolean isApproved(SoleDropProtocol.Packet offer) {
        return approvedUntil > SystemClock.elapsedRealtime() && offer.userId != 0 && offer.userId == approvedUserId
            && android.text.TextUtils.equals(offer.token, approvedToken);
    }

    /** The open SoleDrop screen, which takes a tap from another phone itself (two phones exchanging contacts). */
    private static WeakReference<SoleDropActivity> activeScreen;

    static void setActiveScreen(@Nullable SoleDropActivity screen) {
        activeScreen = screen == null ? null : new WeakReference<>(screen);
    }

    @Nullable
    private static SoleDropActivity activeScreen() {
        return activeScreen == null ? null : activeScreen.get();
    }

    /** Whether this phone answers a tap right now (on the main thread, from the card service). */
    static boolean acceptsNow() {
        final int mode = receiveMode();
        if (mode == RECEIVE_OFF || !UserConfig.getInstance(receiveAccount()).isClientActivated()) {
            return false;
        }
        // Telegram's own passcode closes SoleDrop too
        if (SharedConfig.passcodeHash.length() > 0 && SharedConfig.appLocked) {
            return false;
        }
        final Context context = ApplicationLoader.applicationContext;
        final KeyguardManager keyguard = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
        if (keyguard != null && keyguard.isKeyguardLocked()) {
            return false;
        }
        if (mode == RECEIVE_OPEN && ApplicationLoader.mainInterfacePaused) {
            return false;
        }
        return taps.tryAcquire(SystemClock.elapsedRealtime());
    }

    /** The card service's side of the exchange. */
    static final SoleDropProtocol.CardHost CARD_HOST = new SoleDropProtocol.CardHost() {
        @Override
        public boolean acceptsNow() {
            return SoleDrop.acceptsNow();
        }

        @Override
        public int gate(SoleDropProtocol.Packet offer) {
            if (offer.item < 1 || offer.item > SoleDropProtocol.ITEM_MAX || offer.userId == 0) {
                return SoleDropProtocol.GATE_DENY;
            }
            // while the app is on the screen the owner is there to see it; with the app closed ("always"), a tap
            // reveals nothing until the owner accepts the prompt it brings up
            if (ApplicationLoader.mainInterfacePaused && !isApproved(offer)) {
                return receiveMode() == RECEIVE_ALWAYS ? SoleDropProtocol.GATE_CONFIRM : SoleDropProtocol.GATE_DENY;
            }
            return SoleDropProtocol.GATE_ALLOW;
        }

        @Override
        public void onConfirmationNeeded(SoleDropProtocol.Packet offer) {
            AndroidUtilities.runOnUIThread(() -> deliver(offer, true));
        }

        @Override
        public byte[] identity(SoleDropProtocol.Packet offer) {
            final int account = receiveAccount();
            final SoleDropProtocol.Packet id = SoleDropProtocol.Packet.identity(offer.session);
            if (!SoleDropIdentity.fill(account, id)) {
                return null;
            }
            id.flags = SoleDropProtocol.FLAG_ACCEPTING;
            final SoleDropActivity screen = activeScreen();
            if (screen != null && screen.isNameDropSender()) {
                id.flags |= SoleDropProtocol.FLAG_NAMEDROP;
            }
            if (id.token == null) {
                // the token ran out while the app slept: the username still finds the account, and the next tap has a token
                AndroidUtilities.runOnUIThread(() -> SoleDropIdentity.refresh(account, null));
            }
            return id.encode();
        }

        @Override
        public void onExchanged(SoleDropProtocol.Packet offer, boolean acked) {
            AndroidUtilities.runOnUIThread(() -> deliver(offer, false));
        }
    };

    /**
     * A whole offer came in: shown once per tap (phones held together connect again and again).
     *
     * @param confirm the first tap of receiving "always": the owner is asked, the other phone got nothing yet
     */
    private static void deliver(SoleDropProtocol.Packet offer, boolean confirm) {
        final long now = SystemClock.elapsedRealtime();
        for (int i = 0; i < recentSessions.length; i++) {
            if (recentSessions[i] == offer.session && now - recentTimes[i] < 120_000) {
                return;
            }
        }
        recentSessions[recentHead] = offer.session;
        recentTimes[recentHead] = now;
        recentHead = (recentHead + 1) % recentSessions.length;

        final int account = receiveAccount();
        if (offer.userId != 0 && offer.userId == UserConfig.getInstance(account).getClientUserId()) {
            return; // two phones of the same account
        }
        if (offer.item < 1 || offer.item > SoleDropProtocol.ITEM_MAX) {
            return;
        }
        final SoleDropActivity screen = activeScreen();
        if (!confirm && screen != null && screen.takeIncoming(offer)) {
            return;
        }
        final BaseFragment last = LaunchActivity.getSafeLastFragment();
        if (last instanceof SoleDropActivity && ((SoleDropActivity) last).takeIncoming(offer)) {
            return; // the prompt for this sender is already open (or waits for this tap)
        }
        if (!shows.tryAcquire(now)) {
            return;
        }
        if (!ApplicationLoader.mainInterfacePaused) {
            show(account, offer, confirm);
            return;
        }
        if (receiveMode() != RECEIVE_ALWAYS) {
            return;
        }
        // receiving "always": the app is started (a card emulation service may start an activity)
        pending[pendingHead] = offer;
        pendingConfirm[pendingHead] = confirm;
        pendingHead = (pendingHead + 1) % pending.length;
        try {
            final Context context = ApplicationLoader.applicationContext;
            final Intent intent = new Intent(context, LaunchActivity.class);
            intent.setAction(ACTION_RECEIVE);
            intent.putExtra(EXTRA_SESSION, offer.session);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            context.startActivity(intent);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private static void show(int account, SoleDropProtocol.Packet offer, boolean confirm) {
        final BaseFragment last = LaunchActivity.getSafeLastFragment();
        if (last == null || last.getParentActivity() == null) {
            return;
        }
        if (last instanceof SoleDropActivity && ((SoleDropActivity) last).takeIncoming(offer)) {
            return;
        }
        last.presentFragment(SoleDropActivity.receive(account, offer, confirm));
    }

    private static final String ACTION_RECEIVE = "com.solegram.messenger.SOLEDROP_RECEIVE";
    private static final String EXTRA_SESSION = "soledrop_session";

    /**
     * LaunchActivity's intents: a tap that started the app. The intent only names the session; the offer is
     * looked up in memory, so another app cannot make up a SoleDrop card with an intent of its own.
     */
    public static void handleIntent(@Nullable Intent intent) {
        if (intent == null || !ACTION_RECEIVE.equals(intent.getAction())) {
            return;
        }
        final int session = intent.getIntExtra(EXTRA_SESSION, 0);
        intent.setAction(null);
        SoleDropProtocol.Packet found = null;
        boolean confirm = false;
        for (int i = 0; i < pending.length; i++) {
            if (pending[i] != null && pending[i].session == session) {
                found = pending[i];
                confirm = pendingConfirm[i];
                pending[i] = null;
            }
        }
        if (found == null) {
            return;
        }
        final SoleDropProtocol.Packet offer = found;
        final boolean ask = confirm;
        // after the activity has its screens (a cold start builds them right after this intent)
        AndroidUtilities.runOnUIThread(() -> show(receiveAccount(), offer, ask), 350);
    }
    // endregion

    // region Haptics

    /** The phones touched: a firm, short knock (iOS's "thunk" of NameDrop). */
    public static void hapticContact(@Nullable View view) {
        if (SolegramConfig.disableVibration) {
            return;
        }
        try {
            final Vibrator vibrator = (Vibrator) ApplicationLoader.applicationContext.getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator != null && vibrator.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= 29) {
                    vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK));
                } else if (Build.VERSION.SDK_INT >= 26) {
                    vibrator.vibrate(VibrationEffect.createOneShot(28, 255));
                } else {
                    vibrator.vibrate(28);
                }
                return;
            }
        } catch (Throwable ignore) {
        }
        if (view != null) {
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
        }
    }

    /** It went through: the double tick of a confirmation. */
    public static void hapticSuccess(@Nullable View view) {
        if (SolegramConfig.disableVibration || view == null) {
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                view.performHapticFeedback(HapticFeedbackConstants.CONFIRM, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
            } else {
                final Vibrator vibrator = (Vibrator) ApplicationLoader.applicationContext.getSystemService(Context.VIBRATOR_SERVICE);
                if (vibrator != null && Build.VERSION.SDK_INT >= 26) {
                    vibrator.vibrate(VibrationEffect.createWaveform(new long[]{0, 18, 70, 26}, new int[]{0, 160, 0, 255}, -1));
                } else {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
                }
            }
        } catch (Throwable ignore) {
        }
    }

    public static void hapticTick(@Nullable View view) {
        if (SolegramConfig.disableVibration || view == null) {
            return;
        }
        try {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
        } catch (Throwable ignore) {
        }
    }
    // endregion
}
