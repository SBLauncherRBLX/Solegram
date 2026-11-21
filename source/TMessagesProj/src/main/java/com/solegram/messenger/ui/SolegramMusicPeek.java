/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.dpf2;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.ImageReceiver;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.PlayPauseDrawable;
import org.telegram.ui.Components.Text;

/**
 * Solegram: the profile's music line opens into a small card of the song before the player: its cover, title,
 * artist, how far it played and a play/pause button. It grows out of the line and goes back into it. A tap on the
 * card opens the player (or the track on stats.fm), a tap anywhere else closes it. Over the profile, in its window.
 */
@SuppressLint("ViewConstructor")
public class SolegramMusicPeek extends View {

    /** What the card shows and does. */
    public interface Source {
        String title();

        String artist();

        /** 0..1, negative when not known. */
        float progress();

        /** Length in ms, 0 when not known. */
        long durationMs();

        boolean playing();

        /** The button plays and pauses here (Telegram's music); false: it opens the track elsewhere (stats.fm). */
        boolean controls();

        void playPause();

        /** The card was tapped: the player (or the track's page). */
        void open();

        /** The cover now, if already loaded. */
        @Nullable
        Bitmap cover();

        /** The cover's address on the web, when there is no bitmap. */
        @Nullable
        String coverUrl();

        /** A small line over the title ("Playing now"), or null. */
        @Nullable
        CharSequence badge();
    }

    private static final int CARD_HEIGHT = 108;
    private static final int PAD = 14;
    private static final int COVER = 80;
    private static final int BUTTON = 46;

    private final Source source;
    private final RectF fromOnScreen = new RectF();
    private final RectF from = new RectF();
    private final RectF to = new RectF();
    private final RectF card = new RectF();
    private final float fromRadius;
    private final Runnable onClosed;

    private float progress; // 0: the line, 1: the card
    private boolean closing;
    private ValueAnimator animator;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dim = new Paint();
    private final Paint barBack = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barFront = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint buttonPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final ImageReceiver coverImage = new ImageReceiver(this);
    private final PlayPauseDrawable playPause = new PlayPauseDrawable(16);
    private final Drawable openIcon;
    private Text titleText, artistText, badgeText, timeLeft, timeRight;
    private float textWidth = -1;
    private int coverColor = 0xFF3A3A3C;
    private Bitmap shownCover, setCover;
    private final RectF buttonRect = new RectF();
    private boolean downOnCard, downOnButton;

    private SolegramMusicPeek(Context context, Source source, RectF fromOnScreen, float fromRadius, Runnable onClosed) {
        super(context);
        this.source = source;
        this.fromRadius = fromRadius;
        this.onClosed = onClosed;
        this.fromOnScreen.set(fromOnScreen);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(dpf2(1));
        barBack.setColor(0x33FFFFFF);
        barFront.setColor(Color.WHITE);
        buttonPaint.setColor(Color.WHITE);
        playPause.setColor(0xFF111111);
        playPause.setCallback(this);
        openIcon = context.getResources().getDrawable(R.drawable.msg_openin).mutate();
        openIcon.setColorFilter(new android.graphics.PorterDuffColorFilter(0xFF111111, android.graphics.PorterDuff.Mode.SRC_IN));
        coverImage.setRoundRadius(dp(14));
        coverImage.setCrossfadeWithOldImage(true);
        setLayerType(LAYER_TYPE_NONE, null);
    }

    /** Opens the card out of {@code lineOnScreen} (the line's pill, on the screen) over {@code root}. */
    public static SolegramMusicPeek show(ViewGroup root, RectF lineOnScreen, float lineRadius, Source source, Runnable onClosed) {
        final SolegramMusicPeek peek = new SolegramMusicPeek(root.getContext(), source, lineOnScreen, lineRadius, onClosed);
        root.addView(peek, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        peek.animateTo(1f, null);
        return peek;
    }

    public boolean isClosing() {
        return closing;
    }

    /** Back into the line (a tap outside, the back button); {@code then} after it. */
    public void close(@Nullable Runnable then) {
        if (closing) {
            return;
        }
        closing = true;
        animateTo(0f, () -> {
            if (getParent() instanceof ViewGroup) {
                ((ViewGroup) getParent()).removeView(this);
            }
            onClosed.run();
            if (then != null) {
                then.run();
            }
        });
    }

    /** Gone at once (the player opened over it). */
    public void dismissNow() {
        if (animator != null) {
            animator.cancel();
        }
        closing = true;
        if (getParent() instanceof ViewGroup) {
            ((ViewGroup) getParent()).removeView(this);
        }
        onClosed.run();
    }

    private void animateTo(float target, @Nullable Runnable end) {
        if (animator != null) {
            animator.cancel();
        }
        animator = ValueAnimator.ofFloat(progress, target);
        animator.addUpdateListener(a -> {
            progress = (float) a.getAnimatedValue();
            invalidate();
        });
        animator.setDuration(target > progress ? 420 : 320);
        animator.setInterpolator(target > progress ? CubicBezierInterpolator.EASE_OUT_QUINT : CubicBezierInterpolator.EASE_OUT);
        animator.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled;

            @Override
            public void onAnimationCancel(Animator animation) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (!cancelled && end != null) {
                    end.run();
                }
            }
        });
        animator.start();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        coverImage.onAttachedToWindow();
        final Bitmap bitmap = source.cover();
        if (bitmap == null && !TextUtils.isEmpty(source.coverUrl())) {
            coverImage.setImage(ImageLocation.getForPath(source.coverUrl()), "200_200", null, null, null, 0, null, null, 1);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        coverImage.onDetachedFromWindow();
        if (animator != null) {
            animator.cancel();
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        final int[] loc = new int[2];
        getLocationOnScreen(loc);
        // the line's place in this view
        from.set(fromOnScreen);
        from.offset(-loc[0], -loc[1]);
        final float width = Math.min(w - dp(24), dp(440));
        final float height = dp(CARD_HEIGHT);
        final float left = (w - width) / 2f;
        float top = from.centerY() - height / 2f;
        top = Math.max(AndroidUtilities.statusBarHeight + dp(8), Math.min(h - height - dp(8), top));
        to.set(left, top, left + width, top + height);
        textWidth = -1;
    }

    private void layoutTexts() {
        final float w = to.width() - dp(PAD) * 2 - dp(COVER) - dp(12) - dp(BUTTON) - dp(12);
        if (w == textWidth && titleText != null) {
            return;
        }
        textWidth = w;
        titleText = new Text(source.title(), 16, AndroidUtilities.bold()).ellipsize(w);
        artistText = new Text(source.artist(), 14).ellipsize(w);
        final CharSequence badge = source.badge();
        badgeText = badge != null ? new Text(badge.toString().toUpperCase(), 11, AndroidUtilities.bold()).ellipsize(w) : null;
    }

    private static String time(long ms) {
        return AndroidUtilities.formatShortDuration((int) (Math.max(0, ms) / 1000));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (to.isEmpty()) {
            return;
        }
        final float p = Math.max(0f, Math.min(1.05f, progress));
        // the rest of the profile goes a little darker under the card
        dim.setColor(Theme.multAlpha(0xFF000000, 0.35f * Math.min(1f, p)));
        canvas.drawRect(0, 0, getWidth(), getHeight(), dim);

        card.set(
            AndroidUtilities.lerp(from.left, to.left, p),
            AndroidUtilities.lerp(from.top, to.top, p),
            AndroidUtilities.lerp(from.right, to.right, p),
            AndroidUtilities.lerp(from.bottom, to.bottom, p));
        final float radius = AndroidUtilities.lerp(fromRadius, dp(26), Math.min(1f, p));

        // the card takes the cover's colour: a dark gradient of it
        final Bitmap bitmap = source.cover() != null ? source.cover() : coverImage.getBitmap();
        if (bitmap != shownCover && bitmap != null && !bitmap.isRecycled()) {
            shownCover = bitmap;
            try {
                coverColor = com.solegram.messenger.SolegramNowPlaying.mainColor(bitmap);
            } catch (Throwable ignore) {
            }
        }
        final int top = ColorUtils.blendARGB(coverColor, 0xFF000000, 0.45f);
        final int bottom = ColorUtils.blendARGB(coverColor, 0xFF000000, 0.72f);
        fill.setShader(new LinearGradient(card.left, card.top, card.right, card.bottom, Theme.multAlpha(top, 0.96f), Theme.multAlpha(bottom, 0.96f), Shader.TileMode.CLAMP));
        canvas.drawRoundRect(card, radius, radius, fill);
        rim.setShader(new LinearGradient(0, card.top, 0, card.bottom, 0x40FFFFFF, 0x10FFFFFF, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(card, radius, radius, rim);

        final float contentAlpha = Math.max(0f, Math.min(1f, (p - 0.35f) / 0.5f));
        if (contentAlpha <= 0f) {
            return;
        }
        layoutTexts();
        canvas.save();
        clip.rewind();
        clip.addRoundRect(card, radius, radius, Path.Direction.CW);
        canvas.clipPath(clip);
        // the content sits in the card's final place, sliding with its growth
        canvas.translate(card.left - to.left, card.top - to.top);

        // cover
        final float cx = to.left + dp(PAD), cy = to.top + dp(PAD);
        if (source.cover() != null && source.cover() != setCover) {
            setCover = source.cover();
            coverImage.setImageBitmap(setCover);
        }
        coverImage.setImageCoords(cx, cy, dp(COVER), dp(COVER));
        coverImage.setAlpha(contentAlpha);
        if (coverImage.hasImageSet() || source.cover() != null) {
            coverImage.draw(canvas);
        } else {
            fill.setShader(null);
            fill.setColor(Theme.multAlpha(0xFFFFFFFF, 0.12f * contentAlpha));
            AndroidUtilities.rectTmp.set(cx, cy, cx + dp(COVER), cy + dp(COVER));
            canvas.drawRoundRect(AndroidUtilities.rectTmp, dp(14), dp(14), fill);
        }

        // texts
        final float tx = cx + dp(COVER) + dp(12);
        float y = cy + dp(10);
        if (badgeText != null) {
            badgeText.draw(canvas, tx, y, 0xFFFFFFFF, 0.7f * contentAlpha);
            y += dp(19);
        } else {
            y += dp(4);
        }
        titleText.draw(canvas, tx, y, 0xFFFFFFFF, contentAlpha);
        y += dp(20);
        artistText.draw(canvas, tx, y, 0xFFFFFFFF, 0.7f * contentAlpha);

        // how far it played, and the times
        final float barY = cy + dp(COVER) - dp(16);
        final float barRight = to.right - dp(PAD) - dp(BUTTON) - dp(12);
        final float played = source.progress();
        barBack.setAlpha((int) (0x33 * contentAlpha));
        AndroidUtilities.rectTmp.set(tx, barY - dp(2), barRight, barY + dp(2));
        canvas.drawRoundRect(AndroidUtilities.rectTmp, dp(2), dp(2), barBack);
        if (played >= 0) {
            barFront.setAlpha((int) (0xFF * contentAlpha));
            AndroidUtilities.rectTmp.set(tx, barY - dp(2), tx + (barRight - tx) * Math.min(1f, played), barY + dp(2));
            canvas.drawRoundRect(AndroidUtilities.rectTmp, dp(2), dp(2), barFront);
            final long duration = source.durationMs();
            if (duration > 0) {
                timeLeft = new Text(time((long) (duration * played)), 11);
                timeRight = new Text(time(duration), 11);
                timeLeft.draw(canvas, tx, barY + dp(12), 0xFFFFFFFF, 0.6f * contentAlpha);
                timeRight.draw(canvas, barRight - timeRight.getWidth(), barY + dp(12), 0xFFFFFFFF, 0.6f * contentAlpha);
            }
        }

        // play / pause (or open the track's page)
        final float bx = to.right - dp(PAD) - dp(BUTTON), by = to.centerY() - dp(BUTTON) / 2f;
        buttonRect.set(bx, by, bx + dp(BUTTON), by + dp(BUTTON));
        buttonPaint.setAlpha((int) (0xFF * contentAlpha));
        canvas.drawCircle(buttonRect.centerX(), buttonRect.centerY(), dp(BUTTON) / 2f, buttonPaint);
        if (source.controls()) {
            playPause.setPause(source.playing(), true);
            playPause.setAlpha((int) (0xFF * contentAlpha));
            final int s = dp(28);
            playPause.setBounds((int) buttonRect.centerX() - s / 2, (int) buttonRect.centerY() - s / 2, (int) buttonRect.centerX() + s / 2, (int) buttonRect.centerY() + s / 2);
            playPause.draw(canvas);
        } else {
            final int s = dp(24);
            openIcon.setAlpha((int) (0xFF * contentAlpha));
            openIcon.setBounds((int) buttonRect.centerX() - s / 2, (int) buttonRect.centerY() - s / 2, (int) buttonRect.centerX() + s / 2, (int) buttonRect.centerY() + s / 2);
            openIcon.draw(canvas);
        }
        canvas.restore();
        if (!closing && progress >= 1f && source.playing()) {
            postInvalidateDelayed(100); // the bar moves
        }
    }

    @Override
    protected boolean verifyDrawable(Drawable who) {
        return who == playPause || super.verifyDrawable(who);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (closing) {
            return true;
        }
        final float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downOnButton = progress > 0.6f && buttonRect.contains(x, y);
                downOnCard = card.contains(x, y);
                return true;
            case MotionEvent.ACTION_UP:
                if (downOnButton && buttonRect.contains(x, y)) {
                    if (source.controls()) {
                        source.playPause();
                        invalidate();
                    } else {
                        source.open();
                    }
                } else if (downOnCard && card.contains(x, y)) {
                    source.open();
                } else if (!downOnCard) {
                    close(null);
                }
                downOnButton = downOnCard = false;
                return true;
            case MotionEvent.ACTION_CANCEL:
                downOnButton = downOnCard = false;
                return true;
        }
        return true;
    }
}
