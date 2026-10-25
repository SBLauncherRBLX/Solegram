/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Build;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;

import com.solegram.messenger.FeedController;
import com.solegram.messenger.SolegramConfig;
import com.solegram.messenger.SolegramTranslator;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.Emoji;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.ImageReceiver;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.TranslateAlert2;

/**
 * One post of the Feed: channel avatar, name and time, the text (folded after a few lines),
 * the first photo or video of the post, and the view counter. Drawn by hand to stay light.
 */
@SuppressLint("ViewConstructor")
public class FeedPostCell extends View {

    public interface Delegate {
        void onOpenPost(FeedController.Post post);

        void onOpenChannel(FeedController.Post post);
    }

    private static final int MAX_LINES = 7;

    private final Delegate delegate;
    private final ImageReceiver avatar = new ImageReceiver(this);
    private final AvatarDrawable avatarDrawable = new AvatarDrawable();
    private final ImageReceiver media = new ImageReceiver(this);
    private final TextPaint namePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint timePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint morePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint badgePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cardPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint overlayPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF card = new RectF();
    private final RectF mediaRect = new RectF();
    private final Path playPath = new Path();

    private FeedController.Post post;
    private StaticLayout nameLayout, timeLayout, textLayout, footerLayout, fileLayout;
    private boolean truncated, video;
    private float mediaAspect;
    private int extraItems;
    private float textTop, mediaTop, footerTop;
    private String translateLabel;
    private float translateWidth;
    private String summaryLabel;
    private float summaryWidth;
    private final android.graphics.RectF translateRect = new android.graphics.RectF();
    private final android.graphics.RectF summaryRect = new android.graphics.RectF();

    public FeedPostCell(Context context, Delegate delegate) {
        super(context);
        this.delegate = delegate;
        avatar.setRoundRadius(dp(20));
        media.setRoundRadius(dp(14));
        namePaint.setTypeface(AndroidUtilities.bold());
        namePaint.setTextSize(dp(15));
        timePaint.setTextSize(dp(12.5f));
        textPaint.setTextSize(dp(15));
        morePaint.setTextSize(dp(14));
        morePaint.setTypeface(AndroidUtilities.bold());
        badgePaint.setTextSize(dp(13));
        badgePaint.setTypeface(AndroidUtilities.bold());
        badgePaint.setColor(0xFFFFFFFF);
        if (Build.VERSION.SDK_INT >= 28) {
            cardPaint.setShadowLayer(dp(6), 0, dp(1), Theme.isCurrentThemeDark() ? 0x44000000 : 0x10000000);
        }
    }

    public void setPost(FeedController.Post post) {
        this.post = post;
        final TLRPC.Chat chat = post.channel;
        avatarDrawable.setInfo(chat);
        avatar.setForUserOrChat(chat, avatarDrawable);
        setupMedia();
        requestLayout();
        invalidate();
    }

    private void setupMedia() {
        final MessageObject mo = mediaMessage();
        extraItems = Math.max(0, post.group.size() - 1);
        video = false;
        mediaAspect = 0;
        fileLayout = null;
        if (mo == null) {
            media.setImageBitmap((android.graphics.drawable.Drawable) null);
            return;
        }
        final TLRPC.MessageMedia m = mo.messageOwner.media;
        if (m instanceof TLRPC.TL_messageMediaPhoto && m.photo != null) {
            final TLRPC.PhotoSize big = FileLoader.getClosestPhotoSizeWithSize(m.photo.sizes, AndroidUtilities.getPhotoSize());
            final TLRPC.PhotoSize small = FileLoader.getClosestPhotoSizeWithSize(m.photo.sizes, 40);
            if (big != null) {
                mediaAspect = big.w > 0 ? (float) big.h / big.w : 0.75f;
                media.setImage(ImageLocation.getForObject(big, m.photo), sizeFilter(), ImageLocation.getForObject(small, m.photo), "b", big.size, null, mo, 0);
                return;
            }
        } else if (m instanceof TLRPC.TL_messageMediaDocument && m.document != null) {
            final TLRPC.Document doc = m.document;
            if (MessageObject.isVideoDocument(doc) || MessageObject.isGifDocument(doc)) {
                final TLRPC.PhotoSize thumb = FileLoader.getClosestPhotoSizeWithSize(doc.thumbs, 320);
                final TLRPC.PhotoSize small = FileLoader.getClosestPhotoSizeWithSize(doc.thumbs, 40);
                if (thumb != null) {
                    video = true;
                    mediaAspect = thumb.w > 0 ? (float) thumb.h / thumb.w : 0.5625f;
                    for (TLRPC.DocumentAttribute a : doc.attributes) {
                        if (a instanceof TLRPC.TL_documentAttributeVideo && a.w > 0) {
                            mediaAspect = (float) a.h / a.w;
                        }
                    }
                    media.setImage(ImageLocation.getForDocument(thumb, doc), sizeFilter(), ImageLocation.getForDocument(small, doc), "b", 0, null, mo, 0);
                    return;
                }
            }
        }
        media.setImageBitmap((android.graphics.drawable.Drawable) null);
    }

    /** Decode pictures at the size of the card (in dp), not at their full resolution. */
    private String sizeFilter() {
        final int w = (int) ((AndroidUtilities.displaySize.x - dp(52)) / AndroidUtilities.density);
        final int h = (int) Math.max(120, Math.min(380, w * mediaAspect));
        return w + "_" + h;
    }

    /** The album item that carries a picture, or the post itself. */
    private MessageObject mediaMessage() {
        for (MessageObject mo : post.group) {
            final TLRPC.MessageMedia m = mo.messageOwner.media;
            if (m instanceof TLRPC.TL_messageMediaPhoto || m instanceof TLRPC.TL_messageMediaDocument) {
                return mo;
            }
        }
        return null;
    }

    private CharSequence buildText() {
        final TLRPC.Message msg = post.message.messageOwner;
        if (TextUtils.isEmpty(msg.message)) {
            return null;
        }
        if (post.showSummary && post.summaryText != null) {
            final SpannableStringBuilder summary = new SpannableStringBuilder(post.summaryText);
            // who made it (Gemini Nano on the phone, or a service), small and gray under the points
            final String source = com.solegram.messenger.SolegramSummarizer.sourceLabel(post.summaryText);
            if (source != null) {
                final int start = summary.length();
                summary.append("\n✦ ").append(source);
                summary.setSpan(new android.text.style.RelativeSizeSpan(0.8f), start, summary.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                summary.setSpan(new android.text.style.ForegroundColorSpan(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText)), start, summary.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            return Emoji.replaceEmoji(summary, textPaint.getFontMetricsInt(), false);
        }
        if (post.showTranslated && post.translatedText != null) {
            return Emoji.replaceEmoji(post.translatedText, textPaint.getFontMetricsInt(), false);
        }
        final SpannableStringBuilder sb = new SpannableStringBuilder(msg.message);
        try {
            MessageObject.addEntitiesToText(sb, msg.entities, false, true, false, false);
        } catch (Throwable ignore) {
        }
        return Emoji.replaceEmoji(sb, textPaint.getFontMetricsInt(), false);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final int width = MeasureSpec.getSize(widthMeasureSpec);
        if (post == null) {
            setMeasuredDimension(width, dp(1));
            return;
        }
        final int pad = dp(14);
        final float cardLeft = dp(12), cardRight = width - dp(12);
        final int inner = (int) Math.max(1, cardRight - cardLeft - pad * 2);
        final int text = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
        final int gray = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText);
        namePaint.setColor(text);
        timePaint.setColor(gray);
        textPaint.setColor(text);
        textPaint.linkColor = Theme.getColor(Theme.key_windowBackgroundWhiteLinkText);
        morePaint.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));

        final int headerTextWidth = inner - dp(40 + 10);
        final CharSequence name = TextUtils.ellipsize(post.channel != null ? post.channel.title : "", namePaint, headerTextWidth, TextUtils.TruncateAt.END);
        nameLayout = new StaticLayout(name, namePaint, Math.max(1, headerTextWidth), Layout.Alignment.ALIGN_NORMAL, 1f, 0, false);
        timeLayout = new StaticLayout(LocaleController.formatDateAudio(post.date, true), timePaint, Math.max(1, headerTextWidth), Layout.Alignment.ALIGN_NORMAL, 1f, 0, false);

        float y = dp(12) + pad + dp(40) + dp(10);
        textLayout = null;
        truncated = false;
        final CharSequence body = buildText();
        if (body != null) {
            StaticLayout full = new StaticLayout(body, textPaint, inner, Layout.Alignment.ALIGN_NORMAL, 1f, dp(2), false);
            if (!post.expanded && full.getLineCount() > MAX_LINES) {
                truncated = true;
                final int end = full.getLineEnd(MAX_LINES - 1);
                full = new StaticLayout(body.subSequence(0, end), textPaint, inner, Layout.Alignment.ALIGN_NORMAL, 1f, dp(2), false);
            }
            textLayout = full;
            textTop = y;
            y += textLayout.getHeight() + dp(truncated ? 30 : 10);
        }
        if (mediaAspect > 0) {
            final float mediaH = Math.max(dp(120), Math.min(dp(380), inner * mediaAspect));
            mediaTop = y;
            mediaRect.set(cardLeft + pad, y, cardLeft + pad + inner, y + mediaH);
            media.setImageCoords(mediaRect.left, mediaRect.top, mediaRect.width(), mediaRect.height());
            y += mediaH + dp(10);
        } else {
            final TLRPC.Document doc = post.message.getDocument();
            if (doc != null) {
                String fileName = FileLoader.getDocumentFileName(doc);
                if (TextUtils.isEmpty(fileName)) {
                    fileName = LocaleController.getString(R.string.AttachDocument);
                }
                fileLayout = new StaticLayout(TextUtils.ellipsize("📎 " + fileName + " · " + AndroidUtilities.formatFileSize(doc.size), timePaint, inner, TextUtils.TruncateAt.MIDDLE),
                    timePaint, inner, Layout.Alignment.ALIGN_NORMAL, 1f, 0, false);
                mediaTop = y;
                y += fileLayout.getHeight() + dp(10);
            }
        }
        final int views = post.message.messageOwner.views;
        final String footer = views > 0 ? "👁 " + LocaleController.formatShortNumber(views, null) : "";
        footerLayout = new StaticLayout(footer, timePaint, inner, Layout.Alignment.ALIGN_NORMAL, 1f, 0, false);
        footerTop = y;
        translateLabel = null;
        if (body != null && SolegramConfig.freeTranslate) {
            translateLabel = LocaleController.getString(post.translating ? R.string.SG_FeedTranslating
                : post.showTranslated ? R.string.SG_FeedShowOriginal : R.string.SG_FeedTranslate);
            translateWidth = morePaint.measureText(translateLabel);
        }
        summaryLabel = null;
        if (body != null && com.solegram.messenger.SolegramSummarizer.canSummarize(post.message)) {
            summaryLabel = LocaleController.getString(post.summarizing ? R.string.SG_FeedSummarizing
                : post.showSummary ? R.string.SG_SummaryShowFull : R.string.SG_Summarize);
            summaryWidth = morePaint.measureText(summaryLabel);
        }
        y += footerLayout.getHeight() + pad;
        card.set(cardLeft, dp(12), cardRight, y);
        setMeasuredDimension(width, (int) (y + dp(2)));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (post == null) {
            return;
        }
        final int pad = dp(14);
        cardPaint.setColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        canvas.drawRoundRect(card, dp(18), dp(18), cardPaint);

        final float left = card.left + pad;
        avatar.setImageCoords(left, card.top + pad, dp(40), dp(40));
        avatar.draw(canvas);
        canvas.save();
        canvas.translate(left + dp(50), card.top + pad + dp(2));
        nameLayout.draw(canvas);
        canvas.translate(0, nameLayout.getHeight() + dp(1));
        timeLayout.draw(canvas);
        canvas.restore();

        if (textLayout != null) {
            canvas.save();
            canvas.translate(left, textTop);
            textLayout.draw(canvas);
            canvas.restore();
            if (truncated) {
                canvas.drawText(LocaleController.getString(R.string.SG_FeedShowMore), left, textTop + textLayout.getHeight() + dp(20), morePaint);
            }
        }
        if (mediaAspect > 0) {
            media.draw(canvas);
            if (video) {
                final float cx = mediaRect.centerX(), cy = mediaRect.centerY();
                overlayPaint.setColor(0x66000000);
                canvas.drawCircle(cx, cy, dp(24), overlayPaint);
                playPath.rewind();
                playPath.moveTo(cx - dp(7), cy - dp(10));
                playPath.lineTo(cx + dp(11), cy);
                playPath.lineTo(cx - dp(7), cy + dp(10));
                playPath.close();
                overlayPaint.setColor(0xFFFFFFFF);
                canvas.drawPath(playPath, overlayPaint);
            }
            if (extraItems > 0) {
                final String badge = "+" + extraItems;
                final float bw = badgePaint.measureText(badge) + dp(16);
                final RectF b = new RectF(mediaRect.right - bw - dp(8), mediaRect.top + dp(8), mediaRect.right - dp(8), mediaRect.top + dp(34));
                overlayPaint.setColor(0x88000000);
                canvas.drawRoundRect(b, dp(13), dp(13), overlayPaint);
                canvas.drawText(badge, b.left + dp(8), b.bottom - dp(8), badgePaint);
            }
        } else if (fileLayout != null) {
            canvas.save();
            canvas.translate(left, mediaTop);
            fileLayout.draw(canvas);
            canvas.restore();
        }
        if (footerLayout != null) {
            canvas.save();
            canvas.translate(left, footerTop);
            footerLayout.draw(canvas);
            canvas.restore();
        }
        float labelRight = card.right - pad;
        translateRect.setEmpty();
        summaryRect.setEmpty();
        if (translateLabel != null) {
            morePaint.setAlpha(post.translating ? 140 : 255);
            canvas.drawText(translateLabel, labelRight - translateWidth, footerTop - morePaint.ascent() - dp(1), morePaint);
            morePaint.setAlpha(255);
            translateRect.set(labelRight - translateWidth - dp(8), footerTop - dp(10), labelRight + dp(8), footerTop + dp(30));
            labelRight -= translateWidth + dp(18);
        }
        if (summaryLabel != null) {
            morePaint.setAlpha(post.summarizing ? 140 : 255);
            canvas.drawText(summaryLabel, labelRight - summaryWidth, footerTop - morePaint.ascent() - dp(1), morePaint);
            morePaint.setAlpha(255);
            summaryRect.set(labelRight - summaryWidth - dp(8), footerTop - dp(10), labelRight + dp(8), footerTop + dp(30));
        }
    }

    private float downX, downY;
    private boolean pressedHeader;

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (post == null) {
            return false;
        }
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                pressedHeader = downY < card.top + dp(14) + dp(44);
                if (card.contains(downX, downY)) {
                    // the card gives in under the finger, unless the touch turns out to be a scroll
                    removeCallbacks(pressIn);
                    postDelayed(pressIn, android.view.ViewConfiguration.getTapTimeout());
                    return true;
                }
                return false;
            case MotionEvent.ACTION_UP:
                releasePress(true);
                if (Math.abs(e.getX() - downX) < dp(10) && Math.abs(e.getY() - downY) < dp(10)) {
                    if (translateLabel != null && translateRect.contains(downX, downY)) {
                        toggleTranslation();
                    } else if (summaryLabel != null && summaryRect.contains(downX, downY)) {
                        toggleSummary();
                    } else if (truncated && textLayout != null && downY >= textTop && downY <= textTop + textLayout.getHeight() + dp(30)) {
                        post.expanded = true;
                        relayout();
                    } else if (pressedHeader) {
                        delegate.onOpenChannel(post);
                    } else {
                        delegate.onOpenPost(post);
                    }
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                releasePress(false);
                return true;
        }
        return true;
    }

    private boolean pressShown;
    private final Runnable pressIn = () -> {
        if (SolegramConfig.pressScale) {
            pressShown = true;
            SolegramMotion.press(this, true, SolegramMotion.PRESS_CARD);
        }
    };
    private final Runnable pressOut = () -> SolegramMotion.press(this, false, SolegramMotion.PRESS_CARD);

    /** The finger is gone: a quick tap still shows a short dip before the card springs back. */
    private void releasePress(boolean tap) {
        removeCallbacks(pressIn);
        removeCallbacks(pressOut);
        if (!pressShown && tap) {
            pressIn.run();
            if (pressShown) {
                postDelayed(pressOut, 90);
            }
        } else {
            pressOut.run();
        }
        pressShown = false;
    }

    /** The post changed its height (text unfolded, a translation or summary shown): the posts under it glide. */
    private void relayout() {
        requestLayout();
        invalidate();
        SolegramMotion.rowResized(this);
    }

    private void toggleSummary() {
        final FeedController.Post p = post;
        if (p.summarizing) {
            return;
        }
        if (p.summaryText != null) {
            p.showSummary = !p.showSummary;
            if (p.showSummary) {
                p.showTranslated = false;
            }
            relayout();
            return;
        }
        p.summarizing = true;
        relayout();
        final String source = p.message.messageOwner.message;
        final String language = SolegramConfig.aiSummaryLanguage == 1 ? TranslateAlert2.getToLanguage() : null;
        com.solegram.messenger.SolegramSummarizer.summarize(source, language, (result, error) -> {
            p.summarizing = false;
            if (result != null) {
                p.summaryText = result;
                p.summarySource = source;
                p.showSummary = true;
                p.showTranslated = false;
            } else if (error != null && getContext() instanceof android.app.Activity) {
                final org.telegram.ui.ActionBar.BaseFragment fragment = org.telegram.ui.LaunchActivity.getSafeLastFragment();
                if (fragment != null) {
                    org.telegram.ui.Components.BulletinFactory.of(fragment).createErrorBulletin(error).show();
                }
            }
            if (post == p) {
                relayout();
            }
        });
    }

    private void toggleTranslation() {
        final FeedController.Post p = post;
        if (p.translating) {
            return;
        }
        if (p.translatedText != null) {
            p.showTranslated = !p.showTranslated;
            if (p.showTranslated) {
                p.showSummary = false;
            }
            relayout();
            return;
        }
        p.translating = true;
        relayout();
        final String source = p.message.messageOwner.message;
        SolegramTranslator.translate(source, TranslateAlert2.getToLanguage(), result -> {
            p.translating = false;
            if (result != null) {
                p.translatedText = result;
                p.translatedSource = source;
                p.showTranslated = true;
                p.showSummary = false;
            }
            if (post == p) {
                relayout();
            }
        });
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        avatar.onAttachedToWindow();
        media.onAttachedToWindow();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        removeCallbacks(pressIn);
        removeCallbacks(pressOut);
        pressShown = false;
        avatar.onDetachedFromWindow();
        media.onDetachedFromWindow();
    }
}
