/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.Emoji;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LiteMode;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RLottieDrawable;
import org.telegram.ui.Components.RLottieImageView;

/**
 * The top of a Solegram settings page, as Telegram's own pages have it (chat folders, devices): an animation that
 * fits the page and a line about what the page is for, on the page's background. The animation is one of
 * Telegram's own (its duck painting, the duck with photos, the diamond...) or, where none fits, Telegram's animated
 * emoji of the page's topic. It plays when the page opens and again when tapped.
 */
@SuppressLint("ViewConstructor")
public class SolegramPageHeader extends LinearLayout implements NotificationCenter.NotificationCenterDelegate {

    private static final int SIZE = 120;

    /** Telegram's animation from the app (0: none), else its animated emoji. */
    private final int rawRes;
    private final String emoji;
    private final int account = UserConfig.selectedAccount;
    private RLottieImageView lottieView;
    private BackupImageView emojiView;
    private TextView emojiFallback;
    private boolean hasEmojiSticker;

    public static SolegramPageHeader ofAnimation(Context context, int rawRes, CharSequence description, Theme.ResourcesProvider rp) {
        return new SolegramPageHeader(context, rawRes, null, description, rp);
    }

    public static SolegramPageHeader ofEmoji(Context context, String emoji, CharSequence description, Theme.ResourcesProvider rp) {
        return new SolegramPageHeader(context, 0, emoji, description, rp);
    }

    private SolegramPageHeader(Context context, int rawRes, String emoji, CharSequence description, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.rawRes = rawRes;
        this.emoji = emoji;
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER_HORIZONTAL);
        setPadding(dp(24), dp(10), dp(24), dp(18));

        if (rawRes != 0) {
            lottieView = new RLottieImageView(context);
            lottieView.setAutoRepeat(false);
            lottieView.setAnimation(rawRes, SIZE, SIZE);
            addView(lottieView, LayoutHelper.createLinear(SIZE, SIZE, Gravity.CENTER_HORIZONTAL));
        } else {
            emojiView = new BackupImageView(context);
            emojiView.setAspectFit(true);
            addView(emojiView, LayoutHelper.createLinear(SIZE - 20, SIZE - 20, Gravity.CENTER_HORIZONTAL, 0, 10, 0, 10));
            emojiFallback = new TextView(context);
            emojiFallback.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 70);
            emojiFallback.setGravity(Gravity.CENTER);
            emojiFallback.setText(Emoji.replaceEmoji(emoji, emojiFallback.getPaint().getFontMetricsInt(), false));
            addView(emojiFallback, LayoutHelper.createLinear(SIZE, SIZE, Gravity.CENTER_HORIZONTAL));
            loadEmoji();
        }

        final TextView text = new TextView(context);
        text.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        text.setGravity(Gravity.CENTER);
        text.setLineSpacing(dp(2), 1f);
        text.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText4, resourcesProvider));
        text.setText(description);
        addView(text, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 8, 0, 0));

        setOnClickListener(v -> play());
    }

    private void loadEmoji() {
        final TLRPC.Document document = MediaDataController.getInstance(account).getEmojiAnimatedSticker(emoji);
        if (document == null) {
            emojiView.setVisibility(GONE);
            emojiFallback.setVisibility(VISIBLE);
            return;
        }
        hasEmojiSticker = true;
        emojiView.setVisibility(VISIBLE);
        emojiFallback.setVisibility(GONE);
        String filter = (SIZE - 20) + "_" + (SIZE - 20);
        if (!LiteMode.isEnabled(LiteMode.FLAGS_ANIMATED_STICKERS)) {
            filter += "_firstframe";
        }
        emojiView.setImage(ImageLocation.getForDocument(document), filter, "tgs", null, document);
        emojiView.getImageReceiver().setAutoRepeat(2);
        emojiView.getImageReceiver().setAllowStartLottieAnimation(true);
    }

    /** Plays the animation from its start (when the page opens and on a tap). */
    private void play() {
        if (lottieView != null) {
            final RLottieDrawable d = lottieView.getAnimatedDrawable();
            if (d != null) {
                d.setCurrentFrame(0, false);
            }
            lottieView.playAnimation();
        } else if (hasEmojiSticker) {
            final RLottieDrawable d = emojiView.getImageReceiver().getLottieAnimation();
            if (d != null) {
                d.setCurrentFrame(0, false);
                d.restart(true);
            }
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (emojiView != null) {
            NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.stickersDidLoad);
            if (!hasEmojiSticker) {
                loadEmoji();
            }
        }
        if (lottieView != null) {
            postDelayed(this::play, 150);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        NotificationCenter.getInstance(account).removeObserver(this, NotificationCenter.stickersDidLoad);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        // Telegram's animated emoji came: the animation takes the plain emoji's place
        if (emojiView != null && !hasEmojiSticker) {
            loadEmoji();
        }
    }
}
