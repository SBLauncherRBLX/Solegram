/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.solegram.messenger.SolegramProfileCard;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.Emoji;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;

import java.util.List;

/** Posts of the user's channel picked for the profile: a row of cards with the picture and the text, tap opens the post. */
public class SolegramProfilePostsView extends LinearLayout {

    public interface Delegate {
        void open(MessageObject post);
    }

    private final Theme.ResourcesProvider rp;
    private final LinearLayout row;
    private Delegate delegate;

    public SolegramProfilePostsView(Context context, Theme.ResourcesProvider rp) {
        super(context);
        this.rp = rp;
        setOrientation(VERTICAL);
        final TextView title = new TextView(context);
        title.setText(LocaleController.getString(R.string.SG_TilePosts));
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        title.setTypeface(AndroidUtilities.bold());
        title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader, rp));
        addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 21, 12, 21, 8));
        final HorizontalScrollView scroll = new HorizontalScrollView(context);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setPadding(dp(14), 0, dp(14), 0);
        row = new LinearLayout(context);
        row.setOrientation(HORIZONTAL);
        scroll.addView(row);
        addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 14));
    }

    public void setDelegate(Delegate delegate) {
        this.delegate = delegate;
    }

    public void setPosts(List<MessageObject> posts) {
        row.removeAllViews();
        if (posts == null) {
            return;
        }
        for (MessageObject p : posts) {
            row.addView(card(p), LayoutHelper.createLinear(172, 204, 5, 0, 5, 0));
        }
    }

    private FrameLayout card(MessageObject p) {
        final Context context = getContext();
        final int text = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, rp);
        final FrameLayout card = new FrameLayout(context);
        card.setBackground(Theme.createRoundRectDrawable(dp(16), Theme.multAlpha(text, 0.06f)));
        card.setClipToOutline(true);
        card.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(android.view.View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(16));
            }
        });

        boolean hasImage = false;
        final BackupImageView image = new BackupImageView(context);
        final TLRPC.MessageMedia media = p.messageOwner == null ? null : p.messageOwner.media;
        if (media instanceof TLRPC.TL_messageMediaPhoto && p.photoThumbs != null) {
            final TLRPC.PhotoSize big = FileLoader.getClosestPhotoSizeWithSize(p.photoThumbs, 480);
            final TLRPC.PhotoSize small = FileLoader.getClosestPhotoSizeWithSize(p.photoThumbs, 50);
            image.setImage(ImageLocation.getForObject(big, p.photoThumbsObject), "240_150", ImageLocation.getForObject(small, p.photoThumbsObject), "240_150_b", 0, p);
            hasImage = big != null;
        } else if (p.getDocument() != null && p.getDocument().thumbs != null && !p.getDocument().thumbs.isEmpty()) {
            final TLRPC.PhotoSize thumb = FileLoader.getClosestPhotoSizeWithSize(p.getDocument().thumbs, 320);
            image.setImage(ImageLocation.getForDocument(thumb, p.getDocument()), "240_150", (ImageLocation) null, null, 0, p);
            hasImage = thumb != null;
        }
        if (hasImage) {
            card.addView(image, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 106, Gravity.TOP));
        }

        final TextView body = new TextView(context);
        body.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        body.setTextColor(text);
        body.setLineSpacing(dp(1), 1f);
        body.setEllipsize(TextUtils.TruncateAt.END);
        body.setMaxLines(hasImage ? 3 : 7);
        String caption = SolegramProfileCard.strip(p.messageOwner == null ? null : p.messageOwner.message).trim();
        if (TextUtils.isEmpty(caption)) {
            caption = LocaleController.getString(hasImage ? R.string.AttachPhoto : R.string.SG_TilePosts);
        }
        body.setText(Emoji.replaceEmoji(caption.replace('\n', ' '), body.getPaint().getFontMetricsInt(), false));
        card.addView(body, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, 12, hasImage ? 114 : 12, 12, 0));

        final TextView date = new TextView(context);
        date.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        date.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, rp));
        date.setText(p.messageOwner == null ? "" : LocaleController.formatDateAudio(p.messageOwner.date, false));
        card.addView(date, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM, 12, 0, 12, 10));

        card.setOnClickListener(v -> {
            if (delegate != null) {
                delegate.open(p);
            }
        });
        SolegramMotion.pressable(card, SolegramMotion.PRESS_CARD);
        return card;
    }
}
