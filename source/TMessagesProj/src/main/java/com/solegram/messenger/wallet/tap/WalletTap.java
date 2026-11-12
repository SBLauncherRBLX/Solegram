/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.wallet.tap;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
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
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.LaunchActivity;

/**
 * SoleWallet by a tap of the phones: the sender's phone reads the receiver's (NFC reader mode against host card
 * emulation), the receiver's phone gives its Telegram contact token, and the sender's Telegram then sends the gift
 * or the Stars in its own sheet. Nothing but the contact token goes over the air.
 * <p>
 * A phone answers a tap only while SoleWallet is open and unlocked on it (the person is there to see it), the app
 * is on the screen and the phone is unlocked; then only offers of Stars or a gift.
 */
public final class WalletTap {

    private WalletTap() {
    }

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
    // endregion

    // region Receiving

    private static int receivingAccount = -1;

    /** SoleWallet is on the screen for this account (-1: it is not): taps are answered for it. */
    public static void setReceiving(int account) {
        receivingAccount = account;
        if (account >= 0) {
            WalletIdentity.refresh(account, null);
        }
    }

    /** SoleWallet is open: the contact token is kept fresh, a tap gets an answer. */
    public static boolean receiving() {
        return receivingAccount >= 0;
    }

    private static final WalletTapProtocol.RateLimit taps = new WalletTapProtocol.RateLimit(8, 60_000);
    private static final int[] recentSessions = new int[8];
    private static final long[] recentTimes = new long[8];
    private static int recentHead;

    private static boolean acceptsNow() {
        final int account = receivingAccount;
        if (account < 0 || !UserConfig.getInstance(account).isClientActivated() || ApplicationLoader.mainInterfacePaused) {
            return false;
        }
        // the wallet behind its code: a phone held to the lock screen of SoleWallet learns nothing
        if (!com.solegram.messenger.wallet.SoleWallet.isUnlocked(account)) {
            return false;
        }
        // Telegram's own passcode closes it too
        if (SharedConfig.passcodeHash.length() > 0 && SharedConfig.appLocked) {
            return false;
        }
        final KeyguardManager keyguard = (KeyguardManager) ApplicationLoader.applicationContext.getSystemService(Context.KEYGUARD_SERVICE);
        if (keyguard != null && keyguard.isKeyguardLocked()) {
            return false;
        }
        return taps.tryAcquire(SystemClock.elapsedRealtime());
    }

    /** The card service's side of the exchange. */
    static final WalletTapProtocol.CardHost CARD_HOST = new WalletTapProtocol.CardHost() {
        @Override
        public boolean acceptsNow() {
            return WalletTap.acceptsNow();
        }

        @Override
        public int gate(WalletTapProtocol.Packet offer) {
            if (offer.userId == 0 || offer.item != WalletTapProtocol.ITEM_STARS && offer.item != WalletTapProtocol.ITEM_GIFT) {
                return WalletTapProtocol.GATE_DENY;
            }
            return receiving() && !ApplicationLoader.mainInterfacePaused ? WalletTapProtocol.GATE_ALLOW : WalletTapProtocol.GATE_DENY;
        }

        @Override
        public void onConfirmationNeeded(WalletTapProtocol.Packet offer) {
        }

        @Override
        public byte[] identity(WalletTapProtocol.Packet offer) {
            final int account = receivingAccount;
            if (account < 0) {
                return null;
            }
            final WalletTapProtocol.Packet id = WalletTapProtocol.Packet.identity(offer.session);
            if (!WalletIdentity.fill(account, id)) {
                return null;
            }
            id.flags = WalletTapProtocol.FLAG_ACCEPTING;
            if (id.token == null) {
                // the token ran out while the app slept: the username still finds the account, the next tap has a token
                AndroidUtilities.runOnUIThread(() -> WalletIdentity.refresh(account, null));
            }
            return id.encode();
        }

        @Override
        public void onExchanged(WalletTapProtocol.Packet offer, boolean acked) {
            AndroidUtilities.runOnUIThread(() -> announce(offer));
        }
    };

    /** The receiver learns who is about to send what (once per tap: phones held together connect again and again). */
    private static void announce(WalletTapProtocol.Packet offer) {
        final long now = SystemClock.elapsedRealtime();
        for (int i = 0; i < recentSessions.length; i++) {
            if (recentSessions[i] == offer.session && now - recentTimes[i] < 120_000) {
                return;
            }
        }
        recentSessions[recentHead] = offer.session;
        recentTimes[recentHead] = now;
        recentHead = (recentHead + 1) % recentSessions.length;
        final BaseFragment last = LaunchActivity.getSafeLastFragment();
        if (last == null || last.getParentActivity() == null) {
            return;
        }
        hapticContact(last.getFragmentView());
        final String who = android.text.TextUtils.isEmpty(offer.name) ? LocaleController.getString(R.string.SG_WalletTapSomeone) : offer.name;
        BulletinFactory.of(last).createSimpleBulletin(R.raw.gift, LocaleController.formatString(
            offer.item == WalletTapProtocol.ITEM_GIFT ? R.string.SG_WalletTapIncomingGift : R.string.SG_WalletTapIncomingStars, who)).show();
    }

    /** The app came to the screen: a fresh contact token for the next tap. */
    public static void onAppResumed() {
        AndroidUtilities.runOnUIThread(() -> {
            try {
                // what SoleDrop (gone in 3.3) kept on the phone: its settings and the old contact tokens
                if (Build.VERSION.SDK_INT >= 24 && !SolegramConfig.prefs().getBoolean("soleDropCleared", false)) {
                    ApplicationLoader.applicationContext.deleteSharedPreferences("solegram_soledrop");
                    SolegramConfig.prefs().edit().remove("soleDropMode").remove("soleDropAccount").remove("soleDropShareName")
                        .putBoolean("soleDropCleared", true).apply();
                }
                if (UserConfig.getInstance(UserConfig.selectedAccount).isClientActivated()) {
                    WalletIdentity.refresh(UserConfig.selectedAccount, null);
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }, 1500);
    }
    // endregion

    // region Haptics

    /** The phones touched: a firm, short knock. */
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
    // endregion
}
