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
        return SolegramConfig.fluidTouch && SolegramMotion.rich();
    }

    /** The stretch of a held view, for whoever else sets its scale (the press spring keeps it). */
    public static float stretchX(View view) {
        return view == target ? stretchX : 1f;
    }

    public static float stretchY(View view) {
        return view == target ? stretchY : 1f;
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

    @Nullable
    private static View find(View root, float rawX, float rawY) {
        final View deepest = deepestAt(root, rawX, rawY);
        // climb to the element: a clickable view, or a row of a list (lists handle clicks for their rows)
        View v = deepest;
        while (v != null && v != root) {
            if (v instanceof EditText || v.getClass().getName().contains("SeekBar") || v.getClass().getName().contains("SlideChooseView")
                    || v.getClass().getName().contains("SlideIntChooseView") || v instanceof org.telegram.ui.Cells.ChatMessageCell
                    || v instanceof org.telegram.ui.Cells.ChatActionCell || v instanceof org.telegram.ui.Cells.DialogCell
                    || v instanceof org.telegram.ui.MainTabsLayout) {
                return null; // things that are dragged or edited, and messages, keep still
            }
            final ViewGroup parent = v.getParent() instanceof ViewGroup ? (ViewGroup) v.getParent() : null;
            if (parent instanceof org.telegram.ui.MainTabsLayout) {
                return null; // the tab bar has its own liquid lens under the finger
            }
            if (parent instanceof RecyclerView || parent instanceof AbsListView) {
                return fits(v) ? v : null;
            }
            if ((v.isClickable() || v.isLongClickable()) && v.isEnabled()) {
                return fits(v) ? v : null;
            }
            v = parent;
        }
        return null;
    }

    @Nullable
    private static View deepestAt(View view, float rawX, float rawY) {
        if (view.getVisibility() != View.VISIBLE || view.getAlpha() <= 0.01f) {
            return null;
        }
        view.getLocationOnScreen(location);
        if (rawX < location[0] || rawY < location[1] || rawX > location[0] + view.getWidth() * Math.abs(view.getScaleX())
                || rawY > location[1] + view.getHeight() * Math.abs(view.getScaleY())) {
            return null;
        }
        if (view instanceof ViewGroup) {
            final ViewGroup group = (ViewGroup) view;
            for (int i = group.getChildCount() - 1; i >= 0; i--) {
                final View hit = deepestAt(group.getChildAt(i), rawX, rawY);
                if (hit != null) {
                    return hit;
                }
            }
        }
        return view;
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
        private float x, y, alpha;
        @Nullable
        private ValueAnimator fade;

        Glow(boolean dark) {
            strength = dark ? 0.20f : 0.42f;
        }

        @Override
        protected void onBoundsChange(@NonNull Rect bounds) {
            super.onBoundsChange(bounds);
            // the light's size follows the element: a button gets a halo, a row a soft spot
            final float r = Math.max(dp(40), Math.min(dp(120), Math.max(bounds.width(), bounds.height()) * 0.55f));
            paint.setShader(new RadialGradient(0, 0, r, new int[]{0xFFFFFFFF, 0x80FFFFFF, 0x00FFFFFF}, new float[]{0f, 0.35f, 1f}, Shader.TileMode.CLAMP));
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
            canvas.clipRect(getBounds());
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
