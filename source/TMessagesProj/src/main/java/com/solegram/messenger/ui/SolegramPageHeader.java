/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.Emoji;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RLottieDrawable;

/**
 * The top of a Solegram settings page, as Telegram's own pages have it (chat folders, devices): an animated sticker
 * of the page's emoji and a line about what the page is for, on the page's background. The sticker plays once when
 * the page opens and again when tapped. Until Telegram's animated emoji are loaded it shows the plain emoji.
 */
@SuppressLint("ViewConstructor")
public class SolegramPageHeader extends LinearLayout implements NotificationCenter.NotificationCenterDelegate {

    private final String emoji;
    private final BackupImageView sticker;
    private final TextView fallback;
    private final TextView text;
    private final int account = UserConfig.selectedAccount;
    private boolean hasSticker;

    public SolegramPageHeader(Context context, String emoji, CharSequence description, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.emoji = emoji;
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER_HORIZONTAL);
        setPadding(dp(24), dp(14), dp(24), dp(18));

        sticker = new BackupImageView(context);
        sticker.setAspectFit(true);
        addView(sticker, LayoutHelper.createLinear(104, 104, Gravity.CENTER_HORIZONTAL));
        fallback = new TextView(context);
        fallback.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 64);
        fallback.setGravity(Gravity.CENTER);
        fallback.setText(Emoji.replaceEmoji(emoji, fallback.getPaint().getFontMetricsInt(), false));
        addView(fallback, LayoutHelper.createLinear(104, 104, Gravity.CENTER_HORIZONTAL));

        text = new TextView(context);
        text.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        text.setGravity(Gravity.CENTER);
        text.setLineSpacing(dp(2), 1f);
        text.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText4, resourcesProvider));
        text.setText(description);
        text.setEllipsize(TextUtils.TruncateAt.END);
        addView(text, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 10, 0, 0));

        setOnClickListener(v -> replay());
        loadSticker();
    }

    private void loadSticker() {
        final TLRPC.Document document = MediaDataController.getInstance(account).getEmojiAnimatedSticker(emoji);
        if (document == null) {
            sticker.setVisibility(GONE);
            fallback.setVisibility(VISIBLE);
            return;
        }
        hasSticker = true;
        sticker.setVisibility(VISIBLE);
        fallback.setVisibility(GONE);
        sticker.setImage(ImageLocation.getForDocument(document), "104_104", "tgs", null, document);
        sticker.getImageReceiver().setAutoRepeat(2);
        sticker.getImageReceiver().setAllowStartLottieAnimation(true);
    }

    /** Plays the sticker from its start (a tap on the header). */
    private void replay() {
        if (!hasSticker) {
            return;
        }
        final RLottieDrawable lottie = sticker.getImageReceiver().getLottieAnimation();
        if (lottie != null) {
            lottie.setCurrentFrame(0, false);
            lottie.restart(true);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.diceStickersDidLoad);
        NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.stickersDidLoad);
        if (!hasSticker) {
            loadSticker();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        NotificationCenter.getInstance(account).removeObserver(this, NotificationCenter.diceStickersDidLoad);
        NotificationCenter.getInstance(account).removeObserver(this, NotificationCenter.stickersDidLoad);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        // Telegram's animated emoji came: the sticker takes the plain emoji's place
        if (!hasSticker) {
            loadSticker();
        }
    }
}
