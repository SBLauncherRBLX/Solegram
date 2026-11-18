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

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DocumentObject;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LiteMode;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.SvgHelper;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RLottieDrawable;

/**
 * The top of a Solegram settings page, as Telegram's own pages have it: one of Telegram's yellow duck stickers (the
 * pack Telegram's empty screens use) and a line about what the page is for, on the page's background. The duck is
 * the one whose emoji fits the page, else a given one from the pack. It plays when the page opens and again when
 * tapped; until the pack is loaded its outline shows.
 */
@SuppressLint("ViewConstructor")
public class SolegramPageHeader extends LinearLayout implements NotificationCenter.NotificationCenterDelegate {

    private static final String PACK = AndroidUtilities.STICKERS_PLACEHOLDER_PACK_NAME;

    private final String[] emoji;
    private final int fallbackIndex;
    private final BackupImageView sticker;
    private final int account = UserConfig.selectedAccount;
    private boolean hasSticker;

    /**
     * @param emoji the emoji of the duck that fits the page, the best first
     * @param fallbackIndex the duck of the pack to show when none of them is in it
     */
    public SolegramPageHeader(Context context, String[] emoji, int fallbackIndex, CharSequence description, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.emoji = emoji;
        this.fallbackIndex = fallbackIndex;
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER_HORIZONTAL);
        setPadding(dp(24), dp(10), dp(24), dp(18));

        sticker = new BackupImageView(context);
        sticker.setAspectFit(true);
        addView(sticker, LayoutHelper.createLinear(120, 120, Gravity.CENTER_HORIZONTAL));

        final TextView text = new TextView(context);
        text.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        text.setGravity(Gravity.CENTER);
        text.setLineSpacing(dp(2), 1f);
        text.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText4, resourcesProvider));
        text.setText(description);
        addView(text, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 8, 0, 0));

        setOnClickListener(v -> replay());
        loadSticker();
    }

    private TLRPC.TL_messages_stickerSet pack() {
        final MediaDataController data = MediaDataController.getInstance(account);
        TLRPC.TL_messages_stickerSet set = data.getStickerSetByName(PACK);
        if (set == null) {
            set = data.getStickerSetByEmojiOrName(PACK);
        }
        return set;
    }

    /** The duck for the page: the first one with one of the page's emoji, else the given one. */
    private TLRPC.Document pick(TLRPC.TL_messages_stickerSet set) {
        if (set == null || set.documents == null || set.documents.isEmpty()) {
            return null;
        }
        for (String e : emoji) {
            for (TLRPC.Document d : set.documents) {
                for (TLRPC.DocumentAttribute a : d.attributes) {
                    if (a instanceof TLRPC.TL_documentAttributeSticker && a.alt != null && a.alt.contains(e)) {
                        return d;
                    }
                }
            }
        }
        return set.documents.get(Math.max(0, Math.min(set.documents.size() - 1, fallbackIndex)));
    }

    private void loadSticker() {
        final TLRPC.TL_messages_stickerSet set = pack();
        final TLRPC.Document document = pick(set);
        if (document == null) {
            // the pack is not here yet: asked for, the duck comes when it is
            MediaDataController.getInstance(account).loadStickersByEmojiOrName(PACK, false, true);
            return;
        }
        hasSticker = true;
        final SvgHelper.SvgDrawable thumb = DocumentObject.getSvgThumb(document.thumbs, Theme.key_emptyListPlaceholder, 0.2f);
        if (thumb != null) {
            thumb.overrideWidthAndHeight(512, 512);
        }
        String filter = "120_120";
        if (!LiteMode.isEnabled(LiteMode.FLAGS_ANIMATED_STICKERS)) {
            filter += "_firstframe";
        }
        sticker.setImage(ImageLocation.getForDocument(document), filter, "tgs", thumb, set);
        sticker.getImageReceiver().setAutoRepeat(2);
        sticker.getImageReceiver().setAllowStartLottieAnimation(true);
    }

    /** Plays the duck from its start (a tap on the header). */
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
        if (!hasSticker) {
            loadSticker();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        NotificationCenter.getInstance(account).removeObserver(this, NotificationCenter.diceStickersDidLoad);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        // the duck pack came
        if (!hasSticker && args.length > 0 && PACK.equals(args[0])) {
            loadSticker();
        }
    }
}
