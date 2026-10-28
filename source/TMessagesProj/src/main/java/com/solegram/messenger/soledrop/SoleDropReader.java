/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.soledrop;

import android.app.Activity;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.os.Bundle;
import android.os.SystemClock;

import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.Utilities;

import java.io.IOException;

/**
 * The sending side of SoleDrop: NFC reader mode while the SoleDrop screen is open, and the exchange with the
 * other phone when it comes close (on the NFC binder thread; results go to the UI thread).
 * <p>
 * Reader mode turns this phone's own card emulation off. To let two phones that both show "My profile" find
 * each other (each wants to give its contact), the reader then works in turns of a random length: while it
 * pauses, this phone answers as a card, and whichever reads first carries both contacts across.
 */
public final class SoleDropReader implements NfcAdapter.ReaderCallback {

    public interface Listener {
        /** A SoleDrop phone answered: the moment of contact (UI thread). */
        void onContact();

        /** The other phone's identity (UI thread). */
        void onExchanged(SoleDropProtocol.Packet identity);

        /**
         * The tap did not complete (UI thread).
         *
         * @param reason {@link #FAIL_LOST} the phones moved apart, {@link #FAIL_REFUSED} the other phone does not
         *               receive now (its setting, closed app), {@link #FAIL_CONFIRM} its owner is asked first
         */
        void onFailed(int reason, boolean contactMade);
    }

    public static final int FAIL_LOST = 0, FAIL_REFUSED = 1, FAIL_CONFIRM = 2;

    private static final int FLAGS = NfcAdapter.FLAG_READER_NFC_A | NfcAdapter.FLAG_READER_NFC_B
        | NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK | NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS;

    private final Listener listener;
    private Activity activity;
    private volatile SoleDropProtocol.Packet offer;
    private volatile boolean armed;
    private boolean enabled, readerOn, alternate;
    private long lastExchange;

    public SoleDropReader(Listener listener) {
        this.listener = listener;
    }

    /** What the next tap offers (built on the UI thread; a new session for every new tap). */
    public void setOffer(@Nullable SoleDropProtocol.Packet offer) {
        this.offer = offer;
    }

    /** Taps are taken (false while the screen shows a found phone or sends). */
    public void setArmed(boolean armed) {
        this.armed = armed;
    }

    /** Turns with card emulation (both phones on "My profile"). */
    public void setAlternate(boolean alternate) {
        if (this.alternate == alternate) {
            return;
        }
        this.alternate = alternate;
        if (enabled) {
            AndroidUtilities.cancelRunOnUIThread(turn);
            if (alternate) {
                AndroidUtilities.runOnUIThread(turn, nextTurn());
            } else {
                setReader(true);
            }
        }
    }

    public void start(Activity activity) {
        this.activity = activity;
        enabled = true;
        setReader(true);
        if (alternate) {
            AndroidUtilities.runOnUIThread(turn, nextTurn());
        }
    }

    public void stop() {
        enabled = false;
        AndroidUtilities.cancelRunOnUIThread(turn);
        setReader(false);
        activity = null;
    }

    private long nextTurn() {
        // reading somewhat longer than answering; random, so two phones fall out of step
        return readerOn ? 650 + Utilities.random.nextInt(500) : 380 + Utilities.random.nextInt(320);
    }

    private final Runnable turn = new Runnable() {
        @Override
        public void run() {
            if (!enabled || !alternate) {
                return;
            }
            // a tap in progress is never cut: the reader stays until it is over
            if (!readerOn || SystemClock.elapsedRealtime() - lastExchange > 1500) {
                setReader(!readerOn);
            }
            AndroidUtilities.runOnUIThread(this, nextTurn());
        }
    };

    private void setReader(boolean on) {
        final NfcAdapter adapter = SoleDrop.adapter();
        if (adapter == null || activity == null) {
            readerOn = false;
            return;
        }
        try {
            if (on && enabled) {
                final Bundle extras = new Bundle();
                // the other phone is checked often: moving apart is noticed fast and the next tap comes quickly
                extras.putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 250);
                adapter.enableReaderMode(activity, this, FLAGS, extras);
                readerOn = true;
            } else if (readerOn || !on) {
                adapter.disableReaderMode(activity);
                readerOn = false;
            }
        } catch (Throwable e) {
            FileLog.e(e);
            readerOn = false;
        }
    }

    @Override
    public void onTagDiscovered(Tag tag) {
        final SoleDropProtocol.Packet offer = this.offer;
        if (!armed || offer == null) {
            return;
        }
        final IsoDep iso = IsoDep.get(tag);
        if (iso == null) {
            return; // a transit card, a sticker tag: not a phone
        }
        lastExchange = SystemClock.elapsedRealtime();
        final boolean[] contact = {false};
        try {
            iso.connect();
            iso.setTimeout(1200);
            final SoleDropProtocol.Packet identity = SoleDropProtocol.exchange(iso::transceive, offer, () -> {
                contact[0] = true;
                AndroidUtilities.runOnUIThread(listener::onContact);
            });
            armed = false; // one phone per tap; the screen arms again for the next one
            AndroidUtilities.runOnUIThread(() -> listener.onExchanged(identity));
        } catch (SoleDropProtocol.ProtocolException e) {
            final int reason = e.statusWord == SoleDropProtocol.SW_NOT_ACCEPTING ? FAIL_REFUSED
                : e.statusWord == SoleDropProtocol.SW_NEEDS_CONFIRMATION ? FAIL_CONFIRM : FAIL_LOST;
            // a phone without SoleDrop answers "not found": that is not a contact at all
            if (contact[0] || reason != FAIL_LOST) {
                AndroidUtilities.runOnUIThread(() -> listener.onFailed(reason, contact[0]));
            }
        } catch (IOException | RuntimeException e) {
            if (contact[0]) {
                AndroidUtilities.runOnUIThread(() -> listener.onFailed(FAIL_LOST, true));
            }
        } finally {
            try {
                iso.close();
            } catch (Throwable ignore) {
            }
        }
    }
}
