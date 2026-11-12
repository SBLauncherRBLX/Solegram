/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.wallet.tap;

import android.nfc.cardemulation.HostApduService;
import android.os.Bundle;

import org.telegram.messenger.FileLog;

/**
 * The receiving side of a SoleWallet tap: the phone answers as a card under the wallet's private AID while SoleWallet is open
 * (the system only routes the AID to an unlocked phone with the screen on, see res/xml/wallet_tap_apdu_service.xml).
 * Commands come on the main thread and are answered at once from memory and the stored identity.
 */
public class WalletTapService extends HostApduService {

    private WalletTapProtocol.Card card;

    @Override
    public void onCreate() {
        super.onCreate();
        card = new WalletTapProtocol.Card(WalletTap.CARD_HOST);
    }

    @Override
    public byte[] processCommandApdu(byte[] commandApdu, Bundle extras) {
        try {
            if (card == null) {
                card = new WalletTapProtocol.Card(WalletTap.CARD_HOST);
            }
            return card.process(commandApdu);
        } catch (Throwable e) {
            FileLog.e(e);
            return WalletTapProtocol.status(WalletTapProtocol.SW_WRONG_DATA);
        }
    }

    @Override
    public void onDeactivated(int reason) {
        if (card != null) {
            try {
                card.deactivated();
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
    }
}
