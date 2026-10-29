/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ScrollView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.widget.NestedScrollView;
import androidx.recyclerview.widget.RecyclerView;

import com.solegram.messenger.SolegramConfig;

import org.telegram.ui.ActionBar.Theme;

/**
 * Fluid touch, as ColorOS's fluid motion: whatever the finger holds (a button, a row, a tab, a switch's row, a
 * glass bubble) follows it a little, with resistance, and stretches toward it; a soft white light sits under the
 * finger and goes with it. Let go, and it springs back into place.
 * <p>
 * It works for the whole window from one place ({@link #onTouch}, called by the activity before the event is
 * dispatched), so no view needs its own code: the element is found under the finger. It never takes the touch
 * away: clicks, long presses and scrolling work as before, and as soon as a list or a pager starts to scroll
 * under the finger the element is let go.
 */
public final class SolegramFluidTouch {

    private SolegramFluidTouch() {
    }

    /** The element being held, null when none. */
    @Nullable
    private static View target;
    private static float downX, downY;
    /** Where the element was before it was taken (it may already be moved by its own layout or animation). */
    private static float baseTX, baseTY;
    private static boolean released = true;
    private static int touchSlop;
    /** How far the element may go (px) and how it stretches, set for its size when it is taken. */
    private static float maxShiftX, maxShiftY;
    private static float stretchX = 1f, stretchY = 1f;
    /** A view whose scale a state list animator drives (a pressable button) only moves, it is not stretched. */
    private static boolean canStretch;
    @Nullable
    private static Glow glow;
    @Nullable
    private static ValueAnimator back;
    private static final int[] location = new int[2];

    public static boolean enabled() {
        return SolegramConfig.fluidTouch && SolegramMotion.enabled();
    }

    /** The stretch of a held view, for whoever else sets its scale (the press spring keeps it). */
    public static float stretchX(View view) {
        return view == target ? stretchX : 1f;
    }

    public static float stretchY(View view) {
        return view == target ? stretchY : 1f;
    }

    /**
     * For a parent that draws a held child's glass itself (the header draws its buttons' bubbles): moves and
     * stretches the canvas the way the child is moved and stretched now, around the child's centre. Returns
     * false (and leaves the canvas alone) when the child is not held.
     */
    public static boolean applyTo(android.graphics.Canvas canvas, @Nullable View child) {
        if (child == null || child != target) {
            return false;
        }
        final float px = child.getLeft() + child.getWidth() / 2f, py = child.getTop() + child.getHeight() / 2f;
        canvas.translate(child.getTranslationX() - baseTX, child.getTranslationY() - baseTY);
        canvas.scale(child.getScaleX(), child.getScaleY(), px, py);
        return true;
    }

    /** Called for every touch event of the activity's window before it is dispatched; it only watches. */
    public static void onTouch(Activity activity, MotionEvent event) {
        if (activity == null || event == null) {
            return;
        }
        final int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            release(false);
            if (!enabled()) {
                return;
            }
            final View root = activity.getWindow() != null ? activity.getWindow().getDecorView() : null;
            final View found = root != null ? find(root, event.getRawX(), event.getRawY()) : null;
            if (found != null) {
                take(found, event.getRawX(), event.getRawY());
            }
            return;
        }
        if (target == null || released) {
            return;
        }
        switch (action) {
            case MotionEvent.ACTION_MOVE:
                follow(event.getRawX(), event.getRawY());
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                release(true);
                break;
        }
    }

    // region Finding the element under the finger

    /** Returned from the search when the finger is on something that must keep still: nothing below is taken. */
    private static final View KEEP_STILL = new View(org.telegram.messenger.ApplicationLoader.applicationContext);

    @Nullable
    private static View find(View root, float rawX, float rawY) {
        final View found = findIn(root, rawX, rawY);
        return found == KEEP_STILL ? null : found;
    }

    /**
     * The element under the finger, searched the way Android hands out a touch: the topmost child first, and when
     * nothing in it can be held (a transparent glass layer, a decoration on top), the next one below.
     */
    @Nullable
    private static View findIn(View view, float rawX, float rawY) {
        if (view.getVisibility() != View.VISIBLE || view.getAlpha() <= 0.01f) {
            return null;
        }
        view.getLocationOnScreen(location);
        if (rawX < location[0] || rawY < location[1] || rawX > location[0] + view.getWidth() * Math.abs(view.getScaleX())
                || rawY > location[1] + view.getHeight() * Math.abs(view.getScaleY())) {
            return null;
        }
        final String name = view.getClass().getName();
        if (view instanceof EditText || name.contains("SeekBar") || name.contains("SlideChooseView") || name.contains("SlideIntChooseView")
                || view instanceof org.telegram.ui.Cells.ChatMessageCell || view instanceof org.telegram.ui.Cells.ChatActionCell
                || view instanceof org.telegram.ui.Cells.DialogCell || view instanceof org.telegram.ui.MainTabsLayout) {
            return KEEP_STILL; // dragged or edited things, messages, chat rows (they swipe) and the tab bar (its own lens)
        }
        if (view instanceof ViewGroup) {
            final ViewGroup group = (ViewGroup) view;
            for (int i = group.getChildCount() - 1; i >= 0; i--) {
                final View hit = findIn(group.getChildAt(i), rawX, rawY);
                if (hit != null) {
                    return hit;
                }
            }
        }
        final ViewGroup parent = view.getParent() instanceof ViewGroup ? (ViewGroup) view.getParent() : null;
        // a row of a list (lists handle the clicks of their rows), or anything clickable
        if (parent instanceof RecyclerView || parent instanceof AbsListView || (view.isClickable() || view.isLongClickable()) && view.isEnabled()) {
            return fits(view) ? view : KEEP_STILL; // a whole screen or a big card that takes the touch stays still
        }
        return null;
    }

    /** Only elements of a hand's size: whole screens, big cards and media keep still. */
    private static boolean fits(View v) {
        final int w = v.getWidth(), h = v.getHeight();
        return w > 0 && h > 0 && h <= dp(150) && w <= v.getRootView().getWidth() + 1;
    }
    // endregion

    // region Holding

    private static void take(View view, float rawX, float rawY) {
        target = view;
        released = false;
        downX = rawX;
        downY = rawY;
        stretchX = stretchY = 1f;
        canStretch = view.getStateListAnimator() == null || SolegramMotion.pressDriven(view);
        baseTX = view.getTranslationX();
        baseTY = view.getTranslationY();
        touchSlop = ViewConfiguration.get(view.getContext()).getScaledTouchSlop();
        // small things move further for their size, wide rows only a little
        maxShiftX = Math.min(dp(9), Math.max(dp(4), view.getWidth() * 0.06f));
        maxShiftY = Math.min(dp(7), Math.max(dp(3), view.getHeight() * 0.10f));
        view.getLocationOnScreen(location);
        glow = new Glow(Theme.isCurrentThemeDark());
        glow.setBounds(0, 0, view.getWidth(), view.getHeight());
        glow.setShape(view);
        glow.moveTo(rawX - location[0], rawY - location[1]);
        try {
            view.getOverlay().add(glow);
        } catch (Exception e) {
            glow = null;
        }
        if (glow != null) {
            glow.fadeTo(1f, 140);
        }
    }

    private static void follow(float rawX, float rawY) {
        final View view = target;
        if (view == null || !view.isAttachedToWindow()) {
            release(false);
            return;
        }
        final float dx = rawX - downX, dy = rawY - downY;
        // a list, a pager or a scrolling row takes over: the element lets go at once
        if (Math.abs(dy) > touchSlop && Math.abs(dy) > Math.abs(dx) && scrollsUnder(view, false)
                || Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy) && scrollsUnder(view, true)) {
            release(true);
            return;
        }
        // rubber band: it follows the finger less and less the further it goes
        final float k = dp(70);
        final float sx = maxShiftX * dx / (Math.abs(dx) + k);
        final float sy = maxShiftY * dy / (Math.abs(dy) + k);
        view.setTranslationX(baseTX + sx);
        view.setTranslationY(baseTY + sy);
        invalidateOwner(view);
        // and stretches toward it, a little thinner across (as a drop of liquid pulled by a finger)
        final float nx = Math.abs(sx) / maxShiftX, ny = Math.abs(sy) / maxShiftY;
        final float pullX = dp(7) / (float) Math.max(view.getWidth(), dp(40));
        final float pullY = dp(6) / (float) Math.max(view.getHeight(), dp(30));
        stretchX = 1f + pullX * nx - 0.35f * pullY * ny;
        stretchY = 1f + pullY * ny - 0.35f * pullX * nx;
        applyScale(view);
        if (glow != null) {
            view.getLocationOnScreen(location);
            // the light stays under the finger, in the view's own (moved, scaled) frame
            glow.moveTo((rawX - location[0]) / Math.max(0.01f, view.getScaleX()), (rawY - location[1]) / Math.max(0.01f, view.getScaleY()));
        }
    }

    private static void applyScale(View view) {
        if (!canStretch) {
            return;
        }
        final float base = SolegramMotion.pressValue(view);
        view.setScaleX(base * stretchX);
        view.setScaleY(base * stretchY);
    }

    /** The parent may draw the element's glass (a header's bubbles): it is drawn again with the element. */
    private static void invalidateOwner(View view) {
        // the button's own bar or its grandparent (a header's menu) may draw the glass behind it
        if (view.getParent() instanceof View) {
            final View parent = (View) view.getParent();
            parent.invalidate();
            if (parent.getParent() instanceof View) {
                ((View) parent.getParent()).invalidate();
            }
        }
    }

    /** Is there something under the element that scrolls this way (it would take the gesture)? */
    private static boolean scrollsUnder(View view, boolean horizontal) {
        View p = view.getParent() instanceof View ? (View) view.getParent() : null;
        while (p != null) {
            if (horizontal) {
                if ((p instanceof RecyclerView || p instanceof HorizontalScrollView || p.getClass().getName().contains("ViewPager"))
                        && (p.canScrollHorizontally(1) || p.canScrollHorizontally(-1))) {
                    return true;
                }
            } else if ((p instanceof RecyclerView || p instanceof ScrollView || p instanceof NestedScrollView || p instanceof AbsListView)
                    && (p.canScrollVertically(1) || p.canScrollVertically(-1))) {
                return true;
            }
            p = p.getParent() instanceof View ? (View) p.getParent() : null;
        }
        return false;
    }

    /** Lets the element go: it springs back to its place and shape, the light fades. */
    private static void release(boolean animated) {
        final View view = target;
        if (view == null || released) {
            target = null;
            released = true;
            return;
        }
        released = true;
        if (back != null) {
            back.cancel();
        }
        final float bx = baseTX, by = baseTY;
        final float fromX = view.getTranslationX() - bx, fromY = view.getTranslationY() - by;
        final float fromSX = stretchX, fromSY = stretchY;
        final Glow light = glow;
        glow = null;
        if (light != null) {
            light.fadeTo(0f, animated ? 320 : 0, () -> {
                try {
                    view.getOverlay().remove(light);
                } catch (Exception ignore) {
                }
            });
        }
        if (!animated || !view.isAttachedToWindow()) {
            view.setTranslationX(bx);
            view.setTranslationY(by);
            stretchX = stretchY = 1f;
            applyScale(view);
            target = null;
            return;
        }
        final ValueAnimator a = ValueAnimator.ofFloat(1f, 0f);
        a.addUpdateListener(an -> {
            final float t = (float) an.getAnimatedValue();
            view.setTranslationX(bx + fromX * t);
            view.setTranslationY(by + fromY * t);
            invalidateOwner(view);
            if (target == view) {
                stretchX = 1f + (fromSX - 1f) * t;
                stretchY = 1f + (fromSY - 1f) * t;
                applyScale(view);
            }
        });
        a.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (target == view) {
                    stretchX = stretchY = 1f;
                    target = null;
                }
                if (back == a) {
                    back = null;
                }
            }
        });
        // the springy way back, as a drop settling (the interpolator overshoots past 0 and returns)
        a.setDuration(520);
        a.setInterpolator(SolegramMotion.SPRING_BOUNCY);
        back = a;
        a.start();
    }
    // endregion

    /** The soft white light under the finger, clipped to the element. Made once per hold, nothing per frame. */
    private static final class Glow extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Matrix matrix = new Matrix();
        private final float strength;
        private final android.graphics.Path clipPath = new android.graphics.Path();
        private float x, y, alpha;
        @Nullable
        private ValueAnimator fade;

        Glow(boolean dark) {
            strength = dark ? 0.20f : 0.42f;
        }

        @Override
        protected void onBoundsChange(@NonNull Rect bounds) {
            super.onBoundsChange(bounds);
            // one soft spot of the same size on every element: big enough to read under a finger, never a floodlight
            final float r = dp(46);
            paint.setShader(new RadialGradient(0, 0, r, new int[]{0xFFFFFFFF, 0x99FFFFFF, 0x33FFFFFF, 0x00FFFFFF}, new float[]{0f, 0.3f, 0.65f, 1f}, Shader.TileMode.CLAMP));
        }

        /**
         * The light only on the element's own shape: its glass (the path the glass is drawn in), its outline (a
         * rounded button), a circle for a small icon button whose bubble a parent draws, or else its box.
         */
        void setShape(View view) {
            clipPath.rewind();
            final Rect b = getBounds();
            final Drawable bg = view.getBackground();
            final android.graphics.Path glass = glassPath(bg);
            if (glass != null && !glass.isEmpty()) {
                clipPath.set(glass);
                return;
            }
            final android.graphics.Outline outline = new android.graphics.Outline();
            final android.view.ViewOutlineProvider provider = view.getOutlineProvider();
            final Rect rect = new Rect();
            if (provider != null) {
                try {
                    provider.getOutline(view, outline);
                    if (outline.getRect(rect) && !rect.isEmpty()) {
                        final float radius = Math.max(0, outline.getRadius());
                        clipPath.addRoundRect(rect.left, rect.top, rect.right, rect.bottom, radius, radius, android.graphics.Path.Direction.CW);
                        return;
                    }
                } catch (Exception ignore) {
                }
            }
            if (b.height() <= dp(72) && b.width() <= b.height() * 1.4f) {
                // an icon button (a header's, a toolbar's): its bubble is a circle round its middle
                final float r = Math.min(b.width(), b.height()) / 2f;
                clipPath.addCircle(b.exactCenterX(), b.exactCenterY(), r, android.graphics.Path.Direction.CW);
            } else {
                clipPath.addRect(b.left, b.top, b.right, b.bottom, android.graphics.Path.Direction.CW);
            }
        }

        @Nullable
        private static android.graphics.Path glassPath(@Nullable Drawable bg) {
            if (bg instanceof org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable) {
                return ((org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable) bg).getPath();
            }
            if (bg instanceof android.graphics.drawable.LayerDrawable) {
                final android.graphics.drawable.LayerDrawable layers = (android.graphics.drawable.LayerDrawable) bg;
                for (int i = 0; i < layers.getNumberOfLayers(); i++) {
                    final android.graphics.Path p = glassPath(layers.getDrawable(i));
                    if (p != null && !p.isEmpty()) {
                        return p;
                    }
                }
            }
            return null;
        }

        void moveTo(float x, float y) {
            this.x = x;
            this.y = y;
            invalidateSelf();
        }

        void fadeTo(float to, long duration) {
            fadeTo(to, duration, null);
        }

        void fadeTo(float to, long duration, @Nullable Runnable done) {
            if (fade != null) {
                fade.cancel();
            }
            if (duration <= 0) {
                alpha = to;
                invalidateSelf();
                if (done != null) {
                    done.run();
                }
                return;
            }
            fade = ValueAnimator.ofFloat(alpha, to);
            fade.addUpdateListener(a -> {
                alpha = (float) a.getAnimatedValue();
                invalidateSelf();
            });
            if (done != null) {
                fade.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        done.run();
                    }
                });
            }
            fade.setDuration(duration);
            fade.setInterpolator(SolegramMotion.EASE_OUT);
            fade.start();
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            if (alpha <= 0f || paint.getShader() == null) {
                return;
            }
            final int count = canvas.save();
            if (!clipPath.isEmpty()) {
                canvas.clipPath(clipPath);
            } else {
                canvas.clipRect(getBounds());
            }
            matrix.setTranslate(x, y);
            paint.getShader().setLocalMatrix(matrix);
            paint.setAlpha((int) (255 * strength * alpha));
            canvas.drawRect(getBounds(), paint);
            canvas.restoreToCount(count);
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
