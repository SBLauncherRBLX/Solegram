/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.wallet.tap;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.google.zxing.EncodeHintType;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.solegram.messenger.ui.SolegramMotion;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.TelegramQRCodeWriter;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RadialProgressView;

import java.util.HashMap;

/**
 * The SoleWallet code, for receiving a gift or Stars: the account's Telegram contact link (t.me/contact/...) as a
 * QR code in the theme's colour; the sender scans it in SoleWallet and sends just as after a tap of the phones. The
 * link runs out with its token and is renewed while the sheet is open. The card number is under it: a gift can be
 * sent by it too.
 */
public class WalletQrSheet extends BottomSheet {

    private final int account;
    private final QrView qrView;
    private final RadialProgressView progress;
    private final TextView expiresView;

    public static void show(BaseFragment fragment, int account) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        fragment.showDialog(new WalletQrSheet(fragment.getParentActivity(), account, fragment.getResourceProvider()));
    }

    private WalletQrSheet(Context context, int account, Theme.ResourcesProvider rp) {
        super(context, false, rp);
        this.account = account;
        fixNavigationBar();
        SolegramMotion.springSheet(this);

        final LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        column.setPadding(0, dp(20), 0, dp(16));

        final TextView title = new TextView(context);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        title.setTypeface(AndroidUtilities.bold());
        title.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, rp));
        title.setGravity(Gravity.CENTER);
        title.setText(getString(R.string.SG_WalletQrTitle));
        column.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 0, 24, 0));

        final TextView info = new TextView(context);
        info.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        info.setTextColor(Theme.getColor(Theme.key_dialogTextGray2, rp));
        info.setGravity(Gravity.CENTER);
        info.setText(getString(R.string.SG_WalletQrInfo));
        column.addView(info, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 32, 6, 32, 18));

        final FrameLayout frame = new FrameLayout(context);
        qrView = new QrView(context);
        frame.addView(qrView, LayoutHelper.createFrame(260, 300, Gravity.CENTER));
        progress = new RadialProgressView(context, rp);
        progress.setProgressColor(Theme.getColor(Theme.key_featuredStickers_addButton, rp));
        frame.addView(progress, LayoutHelper.createFrame(44, 44, Gravity.CENTER));
        column.addView(frame, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 300));

        final TextView cardView = new TextView(context);
        cardView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        cardView.setTypeface(AndroidUtilities.bold());
        cardView.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, rp));
        cardView.setGravity(Gravity.CENTER);
        cardView.setText(LocaleController.formatString(R.string.SG_WalletQrCard, com.solegram.messenger.wallet.SoleWallet.cardNumberGrouped(account, false)));
        column.addView(cardView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 14, 24, 0));

        expiresView = new TextView(context);
        expiresView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        expiresView.setTextColor(Theme.getColor(Theme.key_dialogTextGray3, rp));
        expiresView.setGravity(Gravity.CENTER);
        column.addView(expiresView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 14, 24, 0));

        setCustomView(column);
        load(false);
    }

    private void load(boolean force) {
        final String link = WalletIdentity.link(account);
        // a code shown on the screen must still be valid when the friend scans it a while later: a fresh one when
        // the cached one has less than half an hour left
        if (link != null && !force && WalletIdentity.expiresIn(account) > 30 * 60) {
            render(link);
            return;
        }
        progress.setVisibility(View.VISIBLE);
        qrView.setAlpha(0.15f);
        expiresView.setOnClickListener(null);
        WalletIdentity.refresh(account, () -> {
            final String fresh = WalletIdentity.link(account);
            if (fresh != null) {
                render(fresh);
            } else {
                failed();
            }
        });
    }

    /** No code: say why and let a tap try again. */
    private void failed() {
        progress.setVisibility(View.GONE);
        qrView.setAlpha(0.15f);
        final String why = WalletIdentity.lastError;
        expiresView.setText(getString(R.string.SG_WalletQrFailed) + (TextUtils.isEmpty(why) ? "" : " (" + why + ")") + "\n" + getString(R.string.SG_WalletQrRetry));
        expiresView.setOnClickListener(v -> load(true));
    }

    private final Runnable renew = () -> load(true);

    private void render(String link) {
        final TLRPC.User self = UserConfig.getInstance(account).getCurrentUser();
        final String name = self != null ? UserObject.getUserName(self) : "";
        final String username = self != null ? UserObject.getPublicUsername(self) : null;
        final int size = dp(220);
        // a plain code: the hole the writer leaves in its middle stays empty (null would put Telegram's logo there)
        final Drawable center = new android.graphics.drawable.ColorDrawable(0);
        Utilities.globalQueue.postRunnable(() -> {
            Bitmap bitmap = null;
            // the same steps as Telegram's own QR screen: a small fixed version first (room for the logo), then any
            final HashMap<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.MARGIN, 0);
            for (int version = 3; version <= 6 && bitmap == null; version++) {
                try {
                    hints.put(EncodeHintType.QR_VERSION, version);
                    final TelegramQRCodeWriter writer = new TelegramQRCodeWriter();
                    writer.setCenterDrawable(center);
                    bitmap = writer.encode(link, size, size, hints, null, 0.75f, 0xFFFFFFFF, 0xFF000000);
                } catch (Throwable ignore) {
                }
            }
            if (bitmap == null) {
                try {
                    hints.remove(EncodeHintType.QR_VERSION);
                    final TelegramQRCodeWriter writer = new TelegramQRCodeWriter();
                    writer.setCenterDrawable(center);
                    bitmap = writer.encode(link, size, size, hints, null, 0.75f, 0xFFFFFFFF, 0xFF000000);
                } catch (Throwable e) {
                    org.telegram.messenger.FileLog.e(e);
                }
            }
            final Bitmap qr = bitmap;
            AndroidUtilities.runOnUIThread(() -> {
                if (qr == null) {
                    failed();
                    return;
                }
                progress.setVisibility(View.GONE);
                expiresView.setOnClickListener(null);
                qrView.set(qr, name, username != null ? "@" + username : null);
                qrView.animate().alpha(1f).setDuration(SolegramMotion.NORMAL).start();
                final int left = WalletIdentity.expiresIn(account);
                expiresView.setText(getString(R.string.SG_WalletQrRenews));
                AndroidUtilities.cancelRunOnUIThread(renew);
                if (left > 0) {
                    AndroidUtilities.runOnUIThread(renew, Math.max(30, left - 30) * 1000L);
                }
            });
        });
    }

    @Override
    public void dismissInternal() {
        AndroidUtilities.cancelRunOnUIThread(renew);
        super.dismissInternal();
    }

    /** The code on a white card with a frame in the theme's colour, the name under it. */
    @SuppressLint("ViewConstructor")
    private static final class QrView extends View {
        private final Paint frame = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint white = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint codePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint namePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint subPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        @Nullable
        private Bitmap code;
        private String name = "";
        @Nullable
        private String sub;
        private int built;

        QrView(Context context) {
            super(context);
            white.setColor(0xFFFFFFFF);
            namePaint.setColor(0xFF000000);
            namePaint.setTextSize(dp(17));
            namePaint.setTypeface(AndroidUtilities.bold());
            namePaint.setTextAlign(Paint.Align.CENTER);
            subPaint.setColor(0xFF8E8E93);
            subPaint.setTextSize(dp(13));
            subPaint.setTextAlign(Paint.Align.CENTER);
        }

        void set(@Nullable Bitmap code, String name, @Nullable String sub) {
            this.code = code;
            this.name = name;
            this.sub = sub;
            setContentDescription(getString(R.string.SG_WalletQrTitle) + ", " + name);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final int w = getWidth(), h = getHeight();
            if (built != w) {
                built = w;
                codePaint.setShader(null);
                frame.setShader(null);
                frame.setColor(Theme.getColor(Theme.key_featuredStickers_addButton));
            }
            rect.set(0, 0, w, h);
            canvas.drawRoundRect(rect, dp(30), dp(30), frame);
            rect.inset(dp(4), dp(4));
            canvas.drawRoundRect(rect, dp(26), dp(26), white);
            if (code != null) {
                final float left = (w - code.getWidth()) / 2f, top = dp(20);
                canvas.drawBitmap(code, left, top, codePaint);
                final float textTop = top + code.getHeight() + dp(28);
                canvas.drawText(TextUtils.ellipsize(name, new android.text.TextPaint(namePaint), w - dp(32), TextUtils.TruncateAt.END).toString(), w / 2f, textTop, namePaint);
                if (sub != null) {
                    canvas.drawText(sub, w / 2f, textTop + dp(20), subPaint);
                }
            }
        }
    }
}
