/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.wallet.tap;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;

/**
 * Sending from SoleWallet by a tap of the phones: NFC reader mode while the sheet is open; the other phone (with
 * SoleWallet open) gives its contact token, and the sheet hands it to the wallet, which finds the person and opens
 * Telegram's gift sheet. Without NFC (or with it off) the QR code is the way.
 */
public class WalletTapSheet extends BottomSheet implements WalletTapReader.Listener {

    public interface Callback {
        /** The other phone answered with its identity (UI thread); the sheet is closed. */
        void onFound(WalletTapProtocol.Packet identity);

        /** The person would rather scan a QR code. */
        void onScanInstead();
    }

    private final BaseFragment fragment;
    private final int account;
    private final int item;
    private final Callback callback;
    private final WalletTapReader reader = new WalletTapReader(this);
    private final TextView status;
    private final ButtonWithCounterView button;
    private final Contactless mark;
    private boolean done;

    /** @param item {@link WalletTapProtocol#ITEM_STARS} or {@link WalletTapProtocol#ITEM_GIFT} */
    public static void show(BaseFragment fragment, int item, Callback callback) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        fragment.showDialog(new WalletTapSheet(fragment, item, callback));
    }

    private WalletTapSheet(BaseFragment fragment, int item, Callback callback) {
        super(fragment.getParentActivity(), false, fragment.getResourceProvider());
        this.fragment = fragment;
        this.account = fragment.getCurrentAccount();
        this.item = item;
        this.callback = callback;
        final Context context = fragment.getParentActivity();
        fixNavigationBar();

        final LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        column.setPadding(dp(24), dp(20), dp(24), dp(16));

        mark = new Contactless(context);
        column.addView(mark, LayoutHelper.createLinear(96, 96, Gravity.CENTER_HORIZONTAL, 0, 4, 0, 14));

        final TextView title = new TextView(context);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        title.setTypeface(AndroidUtilities.bold());
        title.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
        title.setGravity(Gravity.CENTER);
        title.setText(getString(R.string.SG_WalletTapTitle));
        column.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final TextView info = new TextView(context);
        info.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        info.setTextColor(getThemedColor(Theme.key_dialogTextGray2));
        info.setGravity(Gravity.CENTER);
        info.setText(getString(R.string.SG_WalletTapInfo));
        column.addView(info, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 6, 0, 0));

        status = new TextView(context);
        status.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        status.setTypeface(AndroidUtilities.bold());
        status.setTextColor(getThemedColor(Theme.key_featuredStickers_addButton));
        status.setGravity(Gravity.CENTER);
        column.addView(status, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 16, 0, 0));

        button = new ButtonWithCounterView(context, resourcesProvider);
        column.addView(button, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 0, 18, 0, 0));

        setCustomView(column);
        refreshState();
    }

    /** NFC missing, off or ready: what the sheet says and offers. */
    private void refreshState() {
        if (!WalletTap.hasNfc()) {
            status.setText(getString(R.string.SG_WalletTapNoNfc));
            mark.setActive(false);
            button.setText(getString(R.string.SG_WalletTapScanQr), false);
            button.setOnClickListener(v -> scanInstead());
            return;
        }
        if (!WalletTap.nfcEnabled()) {
            status.setText(getString(R.string.SG_WalletTapNfcOff));
            mark.setActive(false);
            button.setText(getString(R.string.SG_WalletTapTurnOnNfc), false);
            button.setOnClickListener(v -> {
                try {
                    getContext().startActivity(WalletTap.nfcSettingsIntent());
                } catch (Throwable ignore) {
                }
            });
            return;
        }
        status.setText(getString(R.string.SG_WalletTapWaiting));
        mark.setActive(true);
        button.setText(getString(R.string.SG_WalletTapScanQr), false);
        button.setOnClickListener(v -> scanInstead());
        arm();
    }

    private void scanInstead() {
        done = true;
        dismiss();
        callback.onScanInstead();
    }

    /** The offer of the next tap: this account, what is sent, a new session. */
    private void arm() {
        WalletIdentity.refresh(account, () -> {
            final WalletTapProtocol.Packet offer = WalletTapProtocol.Packet.offer(Utilities.random.nextInt(), item, "", "");
            if (!WalletIdentity.fill(account, offer)) {
                reader.setOffer(null);
                return;
            }
            reader.setOffer(offer);
            reader.setArmed(true);
        });
    }

    @Override
    public void show() {
        super.show();
        if (WalletTap.hasNfc() && fragment.getParentActivity() != null) {
            reader.start(fragment.getParentActivity());
        }
    }

    @Override
    public void dismissInternal() {
        reader.stop();
        mark.setActive(false);
        super.dismissInternal();
    }

    @Override
    public void onContact() {
        WalletTap.hapticContact(mark);
        status.setText(getString(R.string.SG_WalletTapContact));
    }

    @Override
    public void onExchanged(WalletTapProtocol.Packet identity) {
        if (done) {
            return;
        }
        done = true;
        dismiss();
        callback.onFound(identity);
    }

    @Override
    public void onFailed(int reason, boolean contactMade) {
        status.setText(getString(reason == WalletTapReader.FAIL_REFUSED || reason == WalletTapReader.FAIL_CONFIRM
            ? R.string.SG_WalletTapRefused : R.string.SG_WalletTapHoldLonger));
        // the next tap
        AndroidUtilities.runOnUIThread(() -> {
            if (!done && isShowing()) {
                status.setText(getString(R.string.SG_WalletTapWaiting));
                arm();
            }
        }, 1800);
    }

    /** The contactless sign: three arcs, softly pulsing outwards while the sheet waits for a phone. */
    @SuppressLint("ViewConstructor")
    private static final class Contactless extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final ValueAnimator pulse = ValueAnimator.ofFloat(0, 1);
        private float phase;

        Contactless(Context context) {
            super(context);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            pulse.setDuration(1400);
            pulse.setRepeatCount(ValueAnimator.INFINITE);
            pulse.setInterpolator(CubicBezierInterpolator.EASE_OUT);
            pulse.addUpdateListener(a -> {
                phase = (float) a.getAnimatedValue();
                invalidate();
            });
        }

        void setActive(boolean active) {
            if (active && !pulse.isRunning()) {
                pulse.start();
            } else if (!active) {
                pulse.cancel();
                phase = 0;
                invalidate();
            }
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            pulse.cancel();
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            final int accent = Theme.getColor(Theme.key_featuredStickers_addButton);
            final float w = getWidth(), h = getHeight(), s = Math.min(w, h);
            bg.setColor(Theme.multAlpha(accent, 0.12f));
            canvas.drawCircle(w / 2f, h / 2f, s / 2f, bg);
            paint.setColor(accent);
            paint.setStrokeWidth(s * 0.06f);
            final float cx = w / 2f - s * 0.16f, cy = h / 2f;
            for (int i = 0; i < 3; i++) {
                final float r = s * (0.12f + 0.11f * i);
                // each arc lights up as the pulse passes it
                final float lit = 1f - Math.min(1f, Math.abs(phase * 3f - i - 0.5f));
                paint.setAlpha((int) (255 * (0.45f + 0.55f * Math.max(0f, lit))));
                rect.set(cx - r, cy - r, cx + r, cy + r);
                canvas.drawArc(rect, -45, 90, false, paint);
            }
        }
    }
}
