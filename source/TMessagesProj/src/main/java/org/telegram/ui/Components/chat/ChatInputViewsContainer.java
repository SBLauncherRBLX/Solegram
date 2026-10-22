package org.telegram.ui.Components.chat;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Build;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.RoundedCorner;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.telegram.ui.Components.AnimatedFloat;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.blur3.BlurredBackgroundWithFadeDrawable;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;
import org.telegram.ui.Components.inset.InAppKeyboardInsetView;
import org.telegram.ui.Components.inset.WindowInsetsProvider;

public class ChatInputViewsContainer extends FrameLayout {
    public static final int INPUT_BUBBLE_RADIUS = 22;
    public static final int INPUT_KEYBOARD_RADIUS = 29;

    public static final int INPUT_BUBBLE_BOTTOM = 9;

    private WindowInsetsProvider windowInsetsProvider;

    private final View fadeView;
    private final FrameLayout inputIslandBubbleContainer;
    private final FrameLayout inAppKeyboardBubbleContainer;

    public ChatInputViewsContainer(@NonNull Context context) {
        super(context);

        inputIslandBubbleContainer = new FrameLayout(context);
        addView(inputIslandBubbleContainer,
            LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM));

        inAppKeyboardBubbleContainer = new FrameLayout(context) {
            @Override
            public void addView(View child, int width, int height) {
                super.addView(child, width, height);
                checkViewsPositions();
            }
        };
        addView(inAppKeyboardBubbleContainer,
            LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM));

        fadeView = new View(context) {
            @Override
            protected void dispatchDraw(@NonNull Canvas canvas) {
                if (backgroundWithFadeDrawable != null) {
                    backgroundWithFadeDrawable.draw(canvas);
                }
                super.dispatchDraw(canvas);
            }
        };
    }

    public View getFadeView() {
        return fadeView;
    }

    public void setWindowInsetsProvider(WindowInsetsProvider windowInsetsProvider) {
        this.windowInsetsProvider = windowInsetsProvider;
    }



    public boolean drawInputBackground = true;
    public BlurredBackgroundDrawable blurredBackgroundDrawable;
    private BlurredBackgroundDrawable underKeyboardBackgroundDrawable;
    public void setInputIslandBubbleDrawable(BlurredBackgroundDrawable drawable) {
        blurredBackgroundDrawable = drawable;
        blurredBackgroundDrawable.setPadding(dp(7));
        blurredBackgroundDrawable.setRadius(dp(INPUT_BUBBLE_RADIUS));
    }

    public void setUnderKeyboardBackgroundDrawable(BlurredBackgroundDrawable drawable) {
        underKeyboardBackgroundDrawable = drawable;
        underKeyboardBackgroundDrawable.enableInAppKeyboardOptimization();
        underKeyboardBackgroundDrawable.setRadius(dp(INPUT_KEYBOARD_RADIUS), dp(INPUT_KEYBOARD_RADIUS), 0, 0);
        underKeyboardBackgroundDrawable.setThickness(dp(32));
        underKeyboardBackgroundDrawable.setIntensity(0.4f);
    }

    public void updateColors() {
        blurredBackgroundDrawable.updateColors();
        underKeyboardBackgroundDrawable.updateColors();
        solegramUpdateColors();
        invalidate();
    }


    @NonNull
    public FrameLayout getInputIslandBubbleContainer() {
        return inputIslandBubbleContainer;
    }

    @NonNull
    public FrameLayout getInAppKeyboardBubbleContainer() {
        return inAppKeyboardBubbleContainer;
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        checkViewsPositions();
        checkInAppKeyboardChild();
    }



    private void checkInAppKeyboardViewHeight() {
        LayoutParams lp = (LayoutParams) inAppKeyboardBubbleContainer.getLayoutParams();

        final int oldHeight = lp.height;
        final int newHeight = windowInsetsProvider.getInAppKeyboardRecommendedViewHeight();

        if (oldHeight != newHeight) {
            lp.height = newHeight;
            requestLayout();
        }
    }

    private final Path underKeyboardPath = new Path();

    private int currentBlurredHeight;
    private void checkBlurredHeight(boolean force) {
        checkViewsPositions();

        final int blurredHeight = inputBubbleHeightRound + dp(INPUT_BUBBLE_BOTTOM) + Math.round(maxBottomInset);
        if (currentBlurredHeight != blurredHeight || force) {
            currentBlurredHeight = blurredHeight;

            final int r = dp(INPUT_KEYBOARD_RADIUS);
            tmpRectF.set(0, getMeasuredHeight() - imeBottomInset, getMeasuredWidth(), getMeasuredHeight());
            underKeyboardPath.rewind();
            underKeyboardPath.addRoundRect(tmpRectF, new float[] {r, r, r, r, 0, 0, 0, 0}, Path.Direction.CW);
            underKeyboardPath.close();
            invalidate();
        }
    }

    private float maxBottomInset;
    private float imeBottomInset;
    private boolean needDrawInAppKeyboard;

    public void checkInsets() {
        maxBottomInset = windowInsetsProvider.getAnimatedMaxBottomInset();
        imeBottomInset = windowInsetsProvider.getAnimatedImeBottomInset();

        needDrawInAppKeyboard = windowInsetsProvider.inAppViewIsVisible();

        if ((inAppKeyboardBubbleContainer.getVisibility() == VISIBLE) != needDrawInAppKeyboard) {
            inAppKeyboardBubbleContainer.setVisibility(needDrawInAppKeyboard ? VISIBLE : GONE);
        }

        checkInAppKeyboardViewHeight();
        checkBlurredHeight(false);
        checkInAppKeyboardChild();

        if (underKeyboardBackgroundDrawable != null) {
            int leftBottomRadius = 0;
            int rightBottomRadius = 0;
            if (Build.VERSION.SDK_INT >= 31) {
                final WindowInsets insets = getRootWindowInsets();
                if (insets != null) {
                    final RoundedCorner bottomLeft = insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT);
                    final RoundedCorner bottomRight = insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT);
                    leftBottomRadius = bottomLeft == null ? 0 : bottomLeft.getRadius();
                    rightBottomRadius = bottomRight == null ? 0 : bottomRight.getRadius();
                }
            }
            underKeyboardBackgroundDrawable.setRadius(dp(INPUT_KEYBOARD_RADIUS), dp(INPUT_KEYBOARD_RADIUS), rightBottomRadius, leftBottomRadius, true);
        }
    }

    private void checkViewsPositions() {
        inputIslandBubbleContainer.setTranslationY(-maxBottomInset - dp(INPUT_BUBBLE_BOTTOM));
        inAppKeyboardBubbleContainer.setTranslationY(inAppKeyboardBubbleContainer.getMeasuredHeight() - imeBottomInset);
    }


    private void checkInAppKeyboardChild() {
        final int navbarHeight = windowInsetsProvider.getCurrentNavigationBarInset();
        final float keyboardHeight = windowInsetsProvider.getAnimatedImeBottomInset();

        for (int a = 0, N = inAppKeyboardBubbleContainer.getChildCount(); a < N; a++) {
            final View child = inAppKeyboardBubbleContainer.getChildAt(a);
            if (child instanceof InAppKeyboardInsetView) {
                InAppKeyboardInsetView insetView = (InAppKeyboardInsetView) child;
                insetView.applyNavigationBarHeight(navbarHeight);
                insetView.applyInAppKeyboardAnimatedHeight(keyboardHeight);
            }
        }
    }



    /* */

    private float inputBubbleOffsetLeft;
    private float inputBubbleOffsetRight;

    private float inputBubbleHeight;
    private int inputBubbleHeightRound;
    public void setInputBubbleHeight(float height) {
        inputBubbleHeight = height;
        inputBubbleHeightRound = Math.round(inputBubbleHeight);
        checkBlurredHeight(false);
    }

    public void setInputBubbleOffsets(float left, float right) {
        inputBubbleOffsetLeft = left;
        inputBubbleOffsetRight = right;
        invalidate();
    }

    public float getInputBubbleHeight() {
        return inputBubbleHeight;
    }

    public float getInputBubbleTop() {
        return getInputBubbleBottom() - getInputBubbleHeight();
    }

    public float getInputBubbleBottom() {
        return getMeasuredHeight() - maxBottomInset - dp(INPUT_BUBBLE_BOTTOM);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        checkBlurredHeight(true);
        checkDrawableBounds();
        checkViewsPositions();
        checkInAppKeyboardChild();
    }

    /* Render */

    private final Rect tmpRect = new Rect();
    private final RectF tmpRectF = new RectF();

    @Override
    protected void dispatchDraw(@NonNull Canvas canvas) {
        underKeyboardBackgroundDrawable.setBounds(
            0,
            getMeasuredHeight() - (int) imeBottomInset,
            getMeasuredWidth(),
            Math.max(getMeasuredHeight(), getMeasuredHeight() - (int) imeBottomInset + dp(INPUT_KEYBOARD_RADIUS * 2))
        );

        final int blurTop = getMeasuredHeight() - currentBlurredHeight;

        tmpRect.set(
            Math.round(inputBubbleOffsetLeft),
            0,
            getMeasuredWidth() - Math.round(inputBubbleOffsetRight),
            inputBubbleHeightRound
        );
        tmpRect.inset(0, -dp(7));
        tmpRect.offset(0, blurTop + (int) bubbleInputTranlationY);

        if (solegramBarSource != null) {
            // Solegram: the iOS-style bar: round glass buttons beside the capsule
            if (drawInputBackground) {
                solegramDrawBar(canvas);
            }
        } else {
            blurredBackgroundDrawable.setBounds(tmpRect);
            if (drawInputBackground)
                blurredBackgroundDrawable.draw(canvas);
        }

        if (needDrawInAppKeyboard) {
            underKeyboardBackgroundDrawable.draw(canvas);
        }

        super.dispatchDraw(canvas);
    }

    @Override
    protected boolean drawChild(@NonNull Canvas canvas, View child, long drawingTime) {
        final boolean needClip = child == inAppKeyboardBubbleContainer;
        if (needClip) {
            canvas.save();
            canvas.clipPath(underKeyboardBackgroundDrawable.getPath());
        }

        final boolean result = super.drawChild(canvas, child, drawingTime);
        if (needClip) {
            canvas.restore();
        }

        return result;
    }





    private BlurredBackgroundWithFadeDrawable backgroundWithFadeDrawable;

    public void setBackgroundWithFadeDrawable(BlurredBackgroundWithFadeDrawable backgroundWithFadeDrawable) {
        this.backgroundWithFadeDrawable = backgroundWithFadeDrawable;
    }

    private float blurredBottomHeight;
    public void setBlurredBottomHeight(float height) {
        if (blurredBottomHeight != height) {
            blurredBottomHeight = height;
            checkDrawableBounds();
        }
    }

    private float bubbleInputTranlationY;
    public void setInputBubbleTranslationY(float translationY) {
        this.bubbleInputTranlationY = translationY;
        invalidate();
    }

    public void setInputBubbleAlpha(int alpha) {
        solegramBarAlpha = alpha; // Solegram: the round buttons of the iOS-style bar fade with the bubble
        if (blurredBackgroundDrawable != null) {
            blurredBackgroundDrawable.setAlpha(alpha);
        }

    }

    private void checkDrawableBounds() {
        if (backgroundWithFadeDrawable == null) {
            return;
        }

        final int oldBound = backgroundWithFadeDrawable.getBounds().top;
        final int newBound = getMeasuredHeight() - Math.round(blurredBottomHeight);

        if (oldBound != newBound) {
            backgroundWithFadeDrawable.setBounds(0, newBound, getMeasuredWidth(), getMeasuredHeight());
            fadeView.invalidate(0, Math.max(0, Math.min(oldBound, newBound)), getMeasuredWidth(), getMeasuredHeight());
            invalidate(0, Math.max(0, Math.min(oldBound, newBound)), getMeasuredWidth(), getMeasuredHeight());
        }
    }


    private boolean captured;

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        final int action = event.getAction();

        if (action == MotionEvent.ACTION_DOWN) {
            final int x = (int) event.getX();
            final int y = (int) event.getY();

            if (solegramBarSource != null) {
                solegramFullRect(tmpRectF);
                tmpRectF.inset(-dp(7), -dp(7));
                captured = solegramBarAlpha == 255 && tmpRectF.contains(x, y)
                    || underKeyboardBackgroundDrawable != null && underKeyboardBackgroundDrawable.getBounds().contains(x, y);
            } else
            captured = blurredBackgroundDrawable != null && blurredBackgroundDrawable.getAlpha() == 255 && blurredBackgroundDrawable.getBounds().contains(x, y)
                || underKeyboardBackgroundDrawable != null && underKeyboardBackgroundDrawable.getBounds().contains(x, y);

        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            captured = false;
        }

        return captured;
    }

    /* Solegram: the iOS-style input bar */

    /** Gap between the capsule and the round buttons beside it, dp. */
    public static final int SOLEGRAM_BAR_GAP = 4;

    /**
     * What the bar should look like now (filled by the input on every frame drawn). Everything but {@code split} is
     * read from the buttons' own animated properties, so the glass moves in the same frame and with the same easing
     * as the icons it holds: it has no animation of its own that could start late or run at another pace.
     */
    public static final class SolegramBarSpec {
        /** Round buttons beside the capsule; false: one full-width capsule (the buttons melt into it). */
        public boolean split;
        /** How much of each round button is there now, 0..1 (its icon appearing or leaving with the voice recording). */
        public float leftShown = 1, rightShown = 1;
        /** Sizes of the side buttons now, px (0: none). They sit at the bottom of the capsule. */
        public float leftWidth, leftHeight, rightWidth, rightHeight;

        boolean same(SolegramBarSpec o) {
            return split == o.split && leftShown == o.leftShown && rightShown == o.rightShown
                && leftWidth == o.leftWidth && leftHeight == o.leftHeight
                && rightWidth == o.rightWidth && rightHeight == o.rightHeight;
        }
    }

    public interface SolegramBarSource {
        void solegramFillBar(SolegramBarSpec spec);
    }

    @Nullable
    private SolegramBarSource solegramBarSource;
    /** The spec the bar was last drawn with, and a scratch one to see before a frame whether it changed. */
    private final SolegramBarSpec solegramSpec = new SolegramBarSpec(), solegramProbe = new SolegramBarSpec();
    /** Telegram's own glass (same factory as the bubble) for the round buttons: rim, shadow and refraction each. */
    @Nullable
    private BlurredBackgroundDrawable solegramLeftDrawable, solegramRightDrawable;
    private int solegramBarAlpha = 255;
    private final RectF solegramRect = new RectF(), solegramMidRect = new RectF();
    /** Only for changes no icon animates (another panel takes the bubble, a recorded voice): Telegram's 220ms easing. */
    private final AnimatedFloat solegramSplit = new AnimatedFloat(this, 0, 220, CubicBezierInterpolator.EASE_OUT_QUINT);
    private boolean solegramPlaced;
    /**
     * The icons animate their own properties (alpha, scale), which redraw only themselves, not this view: before
     * every frame the bar is asked again and redrawn in that same frame when it changed.
     */
    private final ViewTreeObserver.OnPreDrawListener solegramPreDraw = () -> {
        final SolegramBarSource source = solegramBarSource;
        if (source != null && solegramPlaced && drawInputBackground) {
            source.solegramFillBar(solegramProbe);
            if (!solegramProbe.same(solegramSpec)) {
                invalidate();
            }
        }
        return true;
    };
    private boolean solegramPreDrawAdded;

    private void solegramCheckPreDraw() {
        final boolean want = solegramBarSource != null && isAttachedToWindow();
        if (want != solegramPreDrawAdded) {
            solegramPreDrawAdded = want;
            if (want) {
                getViewTreeObserver().addOnPreDrawListener(solegramPreDraw);
            } else {
                getViewTreeObserver().removeOnPreDrawListener(solegramPreDraw);
            }
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        solegramCheckPreDraw();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        solegramPreDrawAdded = false;
        getViewTreeObserver().removeOnPreDrawListener(solegramPreDraw);
    }

    /**
     * Glass for the two round buttons of the iOS-style bar, made by the chat with the same factory and colours as the
     * bubble (so the blur source knows about them). Without them the bar stays Telegram's single capsule.
     */
    public void setSolegramSideDrawables(@Nullable BlurredBackgroundDrawable left, @Nullable BlurredBackgroundDrawable right) {
        solegramLeftDrawable = left;
        solegramRightDrawable = right;
        for (BlurredBackgroundDrawable d : new BlurredBackgroundDrawable[]{left, right}) {
            if (d != null) {
                d.setPadding(dp(7));
                d.setRadius(dp(INPUT_BUBBLE_RADIUS));
                d.setBounds(0, 0, 0, 0);
            }
        }
        invalidate();
    }

    public boolean hasSolegramSideDrawables() {
        return solegramLeftDrawable != null && solegramRightDrawable != null;
    }

    /** The input asks for the iOS-style bar (null: the usual capsule). */
    public void setSolegramBarSource(@Nullable SolegramBarSource source) {
        if (solegramBarSource == source) {
            return;
        }
        solegramBarSource = source;
        solegramPlaced = false;
        if (source == null) {
            // the side glass takes no room in the blur source while unused
            if (solegramLeftDrawable != null) {
                solegramLeftDrawable.setBounds(0, 0, 0, 0);
            }
            if (solegramRightDrawable != null) {
                solegramRightDrawable.setBounds(0, 0, 0, 0);
            }
        }
        solegramCheckPreDraw();
        invalidate();
    }

    public boolean hasSolegramBar() {
        return solegramBarSource != null;
    }

    /**
     * The input capsule in this view's coordinates: the middle one of the iOS-style bar, else the whole bubble.
     * ({@code target} is kept for callers; the capsule has no separate resting place now.)
     */
    public void getSolegramCapsuleRect(RectF out, boolean target) {
        if (solegramBarSource != null && solegramPlaced) {
            out.set(solegramMidRect);
        } else {
            solegramFullRect(out);
        }
    }

    /** {@link #getSolegramCapsuleRect(RectF, boolean)} in an ancestor's coordinates; false if it is not one. */
    public boolean getSolegramCapsuleRect(View ancestor, RectF out, boolean target) {
        getSolegramCapsuleRect(out, target);
        float dx = 0, dy = 0;
        View v = this;
        while (v != ancestor) {
            dx += v.getLeft() + v.getTranslationX();
            dy += v.getTop() + v.getTranslationY();
            if (!(v.getParent() instanceof View)) {
                return false;
            }
            v = (View) v.getParent();
            dx -= v.getScrollX();
            dy -= v.getScrollY();
        }
        out.offset(dx, dy);
        return true;
    }

    /** The whole bubble as Telegram draws it (the drawable's bounds less its padding). */
    private void solegramFullRect(RectF out) {
        final float top = getMeasuredHeight() - currentBlurredHeight + (int) bubbleInputTranlationY;
        out.set(
            Math.round(inputBubbleOffsetLeft) + dp(7),
            top,
            getMeasuredWidth() - Math.round(inputBubbleOffsetRight) - dp(7),
            top + inputBubbleHeightRound
        );
    }

    private void solegramUpdateColors() {
        if (solegramLeftDrawable != null) {
            solegramLeftDrawable.updateColors();
        }
        if (solegramRightDrawable != null) {
            solegramRightDrawable.updateColors();
        }
    }

    /**
     * Three pieces of Telegram's glass: the round buttons and the capsule between them, each its own drawable with
     * its own bounds (rim, shadow and, on Android 13+, refraction). Sizes and appearance follow the icons frame by
     * frame (see {@link SolegramBarSpec}); only a split no icon animates eases on its own, with Telegram's easing.
     */
    private void solegramDrawBar(Canvas canvas) {
        final SolegramBarSource source = solegramBarSource;
        final BlurredBackgroundDrawable mid = blurredBackgroundDrawable;
        if (source == null || mid == null) {
            return;
        }
        final RectF f = solegramRect;
        solegramFullRect(f);
        final SolegramBarSpec s = solegramSpec;
        source.solegramFillBar(s);
        final boolean instant = !solegramPlaced;
        solegramPlaced = true;

        final float split = solegramSplit.set(s.split ? 1 : 0, instant);
        // each round button is there only as much as both the split and its own icon allow (the later of the two
        // wins, so the glass never shows empty while its icon waits to come back, nor stays after the icon left)
        final float left = Math.min(split, Math.max(0, Math.min(1, s.leftShown)));
        final float right = Math.min(split, Math.max(0, Math.min(1, s.rightShown)));
        final float gap = dp(SOLEGRAM_BAR_GAP);
        final int pad = dp(7);

        // the capsule: its ends move in as the buttons appear, a constant gap from them once they are there (the
        // widths are the buttons' current ones, so a widening button pushes the end in the same frame)
        solegramMidRect.set(
            f.left + (s.leftWidth + gap) * left,
            f.top,
            f.right - (s.rightWidth + gap) * right,
            f.bottom
        );
        if (solegramMidRect.width() < 1) {
            solegramMidRect.right = solegramMidRect.left + 1;
        }
        mid.setBounds(
            Math.round(solegramMidRect.left) - pad, Math.round(solegramMidRect.top) - pad,
            Math.round(solegramMidRect.right) + pad, Math.round(solegramMidRect.bottom) + pad
        );
        mid.draw(canvas);

        // the round buttons: grow in (scale and fade) beside it
        solegramDrawSide(canvas, solegramLeftDrawable, f.left, f.left + s.leftWidth, f.bottom - s.leftHeight, f.bottom, left, pad);
        solegramDrawSide(canvas, solegramRightDrawable, f.right - s.rightWidth, f.right, f.bottom - s.rightHeight, f.bottom, right, pad);
    }

    private void solegramDrawSide(Canvas canvas, @Nullable BlurredBackgroundDrawable d, float l, float r, float t, float b, float shown, int pad) {
        if (d == null) {
            return;
        }
        if (shown <= 0.01f || r - l < 1 || b - t < 1) {
            if (!d.getBounds().isEmpty()) {
                d.setBounds(0, 0, 0, 0);
            }
            return;
        }
        d.setBounds(Math.round(l) - pad, Math.round(t) - pad, Math.round(r) + pad, Math.round(b) + pad);
        final int alpha = (int) (solegramBarAlpha * shown);
        if (d.getAlpha() != alpha) {
            d.setAlpha(alpha);
        }
        final float scale = 0.6f + 0.4f * shown;
        canvas.save();
        canvas.scale(scale, scale, (l + r) / 2f, (t + b) / 2f);
        d.draw(canvas);
        canvas.restore();
    }
}
