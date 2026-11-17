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
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Region;
import android.graphics.Shader;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;

import org.telegram.messenger.FileLog;
import org.telegram.ui.Components.CubicBezierInterpolator;

/**
 * Solegram: a new theme shows as Telegram's day/night switch shows its theme - a picture of the screen in the old
 * colours, with a circle growing out of the tapped point through which the new colours are seen. Its edge glows
 * for a moment in the new accent.
 */
public final class SolegramThemeReveal {

    private SolegramThemeReveal() {
    }

    /**
     * Takes the picture of the window, runs {@code apply} (the new colours), and reveals them from (x, y) in the
     * window. Without a picture the colours are applied at once.
     */
    public static void start(Activity activity, float x, float y, int glowColor, Runnable apply) {
        if (activity == null || activity.getWindow() == null) {
            apply.run();
            return;
        }
        final View decor = activity.getWindow().getDecorView();
        Bitmap shot = null;
        try {
            if (decor.getWidth() > 0 && decor.getHeight() > 0) {
                shot = Bitmap.createBitmap(decor.getWidth(), decor.getHeight(), Bitmap.Config.ARGB_8888);
                decor.draw(new Canvas(shot));
            }
        } catch (Throwable e) {
            FileLog.e(e);
            shot = null;
        }
        if (shot == null || !(decor instanceof ViewGroup)) {
            apply.run();
            return;
        }
        final RevealView view = new RevealView(activity, shot, x, y, glowColor);
        ((ViewGroup) decor).addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        apply.run();
        // a frame for the screens to draw in the new colours under the picture, then the circle grows
        view.postOnAnimation(view::reveal);
    }

    private static final class RevealView extends View {
        private final Bitmap shot;
        private final float cx, cy, maxRadius;
        private final Path hole = new Path();
        private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int glowColor;
        private float progress;

        RevealView(Context context, Bitmap shot, float cx, float cy, int glowColor) {
            super(context);
            this.shot = shot;
            this.cx = cx;
            this.cy = cy;
            this.glowColor = glowColor | 0xFF000000;
            final float w = shot.getWidth(), h = shot.getHeight();
            maxRadius = (float) Math.max(Math.max(Math.hypot(cx, cy), Math.hypot(w - cx, cy)), Math.max(Math.hypot(cx, h - cy), Math.hypot(w - cx, h - cy)));
            setClickable(true); // nothing under it takes a tap while it runs
        }

        void reveal() {
            final ValueAnimator animator = ValueAnimator.ofFloat(0, 1);
            animator.addUpdateListener(a -> {
                progress = (float) a.getAnimatedValue();
                invalidate();
            });
            animator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    if (getParent() instanceof ViewGroup) {
                        ((ViewGroup) getParent()).removeView(RevealView.this);
                    }
                    shot.recycle();
                }
            });
            animator.setDuration(720);
            animator.setInterpolator(CubicBezierInterpolator.EASE_BOTH);
            animator.start();
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            if (shot.isRecycled()) {
                return;
            }
            final float r = maxRadius * progress;
            canvas.save();
            if (r > 0) {
                hole.rewind();
                hole.addCircle(cx, cy, r, Path.Direction.CW);
                if (Build.VERSION.SDK_INT >= 26) {
                    canvas.clipOutPath(hole);
                } else {
                    canvas.clipPath(hole, Region.Op.DIFFERENCE);
                }
            }
            canvas.drawBitmap(shot, 0, 0, null);
            canvas.restore();
            if (r > 0 && progress < 1) {
                // a soft glow along the edge of the circle, in the new accent, fading as it grows
                final float width = dp(36);
                final float inner = Math.max(0, r - width);
                final float alpha = (1f - progress) * 0.55f;
                final int edge = (Math.round(255 * alpha) << 24) | (glowColor & 0xFFFFFF);
                glow.setShader(new RadialGradient(cx, cy, Math.max(1, r), new int[]{0, 0, edge, 0},
                    new float[]{0, inner / Math.max(1, r), Math.max(inner / Math.max(1, r), 0.999f - 0.0001f), 1f}, Shader.TileMode.CLAMP));
                canvas.drawCircle(cx, cy, r, glow);
            }
        }
    }
}
