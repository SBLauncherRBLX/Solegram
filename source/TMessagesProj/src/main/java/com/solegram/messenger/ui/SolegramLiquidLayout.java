/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * A layer of liquid glass with views inside its drops: the group's outline is drawn as glass under the
 * children, and every child bound to a drop ({@link SolegramLiquid.Blob#view}) rides on it (centred on the
 * drop, faded and scaled as the drop comes out).
 * <p>
 * Children are laid out at their drop's resting place (left/top from the drop's resting centre); while the
 * drop moves they follow through translation. Taps that hit no child go to {@link #setOnOutsideTap}.
 */
public class SolegramLiquidLayout extends FrameLayout {

    public final SolegramLiquid.Group group = new SolegramLiquid.Group();
    @Nullable
    private SolegramLiquid.GlassFill fill;
    @Nullable
    private Runnable onOutsideTap;
    private float glassAlpha = 1f;
    private boolean clipChildren = true;

    public SolegramLiquidLayout(@NonNull Context context) {
        super(context);
        setWillNotDraw(false);
        setClipChildren(false);
        setClipToPadding(false);
    }

    public void setGlassFill(@Nullable SolegramLiquid.GlassFill fill) {
        this.fill = fill;
        invalidate();
    }

    public void setOnOutsideTap(@Nullable Runnable onOutsideTap) {
        this.onOutsideTap = onOutsideTap;
    }

    public void setGlassAlpha(float alpha) {
        glassAlpha = alpha;
        invalidate();
    }

    /** Children are drawn inside the outline only (content can't stick out of a drop that is still small). */
    public void setClipToOutline(boolean clip) {
        clipChildren = clip;
        invalidate();
    }

    /** Puts a view in a drop: it is laid out at the drop's resting place and follows the drop. */
    public void bind(SolegramLiquid.Blob blob, View view, int width, int height) {
        blob.view = view;
        view.setTag(blob);
        if (view.getParent() != this) {
            if (view.getParent() instanceof ViewGroup) {
                ((ViewGroup) view.getParent()).removeView(view);
            }
            addView(view, new LayoutParams(width, height));
        }
        view.setAlpha(blob.reveal);
        view.setVisibility(blob.reveal > 0.01f ? VISIBLE : INVISIBLE);
        requestLayout();
    }

    /** Kicks the animation (after changing drops from outside). */
    public void animateLiquid() {
        postInvalidateOnAnimation();
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        for (int i = 0; i < getChildCount(); i++) {
            final View child = getChildAt(i);
            if (child.getVisibility() == GONE) {
                continue;
            }
            final Object tag = child.getTag();
            final int w = child.getMeasuredWidth(), h = child.getMeasuredHeight();
            if (tag instanceof SolegramLiquid.Blob) {
                final SolegramLiquid.Blob b = (SolegramLiquid.Blob) tag;
                final int l = Math.round(b.finX - w / 2f), t = Math.round(b.finY - h / 2f);
                child.layout(l, t, l + w, t + h);
            } else {
                child.layout(0, 0, w, h);
            }
        }
    }

    @Override
    protected void dispatchDraw(@NonNull Canvas canvas) {
        final boolean moving = group.step(System.nanoTime());
        // content rides on its drop
        for (int i = 0; i < getChildCount(); i++) {
            final View child = getChildAt(i);
            final Object tag = child.getTag();
            if (tag instanceof SolegramLiquid.Blob) {
                final SolegramLiquid.Blob b = (SolegramLiquid.Blob) tag;
                child.setTranslationX(b.x.value - b.finX);
                child.setTranslationY(b.y.value - b.finY);
            }
        }
        group.draw(canvas, fill, glassAlpha);
        if (clipChildren && !group.path.isEmpty()) {
            canvas.save();
            canvas.clipPath(group.path);
            super.dispatchDraw(canvas);
            canvas.restore();
        } else {
            super.dispatchDraw(canvas);
        }
        if (moving) {
            postInvalidateOnAnimation();
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // reached here: no child took it
        if (onOutsideTap != null) {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                return true;
            }
            if (event.getAction() == MotionEvent.ACTION_UP) {
                onOutsideTap.run();
                return true;
            }
            return true;
        }
        return super.onTouchEvent(event);
    }
}
