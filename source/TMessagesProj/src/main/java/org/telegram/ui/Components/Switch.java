/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.ui.Components;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.util.StateSet;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.annotation.Keep;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.BaseCell;

import me.vkryl.android.animator.BoolAnimator;

public class Switch extends View {
    private final BoolAnimator animatorIconVisibility = new BoolAnimator(this, CubicBezierInterpolator.EASE_OUT_QUINT, 380L, true);

    private RectF rectF;

    private float progress;
    private ObjectAnimator checkAnimator;
    private ObjectAnimator iconAnimator;

    private boolean attachedToWindow;
    private boolean isChecked;
    private Paint paint;
    private Paint paint2;

    private int drawIconType;
    private float iconProgress = 1.0f;

    private OnCheckedChangeListener onCheckedChangeListener;

    private int trackColorKey = Theme.key_fill_RedNormal;
    private int trackCheckedColorKey = Theme.key_switch2TrackChecked;
    private int thumbColorKey = Theme.key_windowBackgroundWhite;
    private int thumbCheckedColorKey = Theme.key_windowBackgroundWhite;

    private Drawable iconDrawable;
    private int lastIconColor;

    private boolean drawRipple;
    private RippleDrawable rippleDrawable;
    private Paint ripplePaint;
    private int[] pressedState = new int[]{android.R.attr.state_enabled, android.R.attr.state_pressed};
    private int colorSet;

    private boolean bitmapsCreated;
    private Bitmap[] overlayBitmap;
    private Canvas[] overlayCanvas;
    private Bitmap overlayMaskBitmap;
    private Canvas overlayMaskCanvas;
    private float overlayCx;
    private float overlayCy;
    private float overlayRad;
    private Paint overlayEraserPaint;
    private Paint overlayMaskPaint;

    private Theme.ResourcesProvider resourcesProvider;

    private int overrideColorProgress;

    public interface OnCheckedChangeListener {
        void onCheckedChanged(Switch view, boolean isChecked);
    }

    public Switch(Context context) {
        this(context, null);
    }

    public Switch(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        rectF = new RectF();

        paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint2 = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint2.setStyle(Paint.Style.STROKE);
        paint2.setStrokeCap(Paint.Cap.ROUND);
        paint2.setStrokeWidth(AndroidUtilities.dp(2));

        setHapticFeedbackEnabled(true);
    }

    @Keep
    public void setProgress(float value) {
        if (progress == value) {
            return;
        }
        progress = value;
        invalidate();
    }

    @Keep
    public float getProgress() {
        return progress;
    }

    @Keep
    public void setIconProgress(float value) {
        if (iconProgress == value) {
            return;
        }
        iconProgress = value;
        invalidate();
    }

    @Keep
    public float getIconProgress() {
        return iconProgress;
    }

    private void cancelCheckAnimator() {
        if (checkAnimator != null) {
            checkAnimator.cancel();
            checkAnimator = null;
        }
    }

    private void cancelIconAnimator() {
        if (iconAnimator != null) {
            iconAnimator.cancel();
            iconAnimator = null;
        }
    }

    public void setDrawIconType(int type) {
        drawIconType = type;
    }

    public void setDrawRipple(boolean value) {
        if (Build.VERSION.SDK_INT < 21 || value == drawRipple) {
            return;
        }
        drawRipple = value;

        if (rippleDrawable == null) {
            ripplePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            ripplePaint.setColor(0xffffffff);
            Drawable maskDrawable;
            if (Build.VERSION.SDK_INT >= 23) {
                maskDrawable = null;
            } else {
                maskDrawable = new Drawable() {
                    @Override
                    public void draw(Canvas canvas) {
                        android.graphics.Rect bounds = getBounds();
                        canvas.drawCircle(bounds.centerX(), bounds.centerY(), AndroidUtilities.dp(18), ripplePaint);
                    }

                    @Override
                    public void setAlpha(int alpha) {

                    }

                    @Override
                    public void setColorFilter(ColorFilter colorFilter) {

                    }

                    @Override
                    public int getOpacity() {
                        return PixelFormat.UNKNOWN;
                    }
                };
            }
            ColorStateList colorStateList = new ColorStateList(
                new int[][]{StateSet.WILD_CARD},
                new int[]{0}
            );
            rippleDrawable = new BaseCell.RippleDrawableSafe(colorStateList, null, maskDrawable);
            if (Build.VERSION.SDK_INT >= 23) {
                rippleDrawable.setRadius(AndroidUtilities.dp(18));
            }
            rippleDrawable.setCallback(this);
        }
        if (isChecked && colorSet != 2 || !isChecked && colorSet != 1) {
            int color = Theme.getColor(isChecked ? Theme.key_switchTrackBlueSelectorChecked : Theme.key_switchTrackBlueSelector, resourcesProvider);
            color = processColor(color);
            ColorStateList colorStateList = new ColorStateList(
                new int[][]{StateSet.WILD_CARD},
                new int[]{color}
            );
            rippleDrawable.setColor(colorStateList);
            colorSet = isChecked ? 2 : 1;
        }
        if (Build.VERSION.SDK_INT >= 28 && value) {
            rippleDrawable.setHotspot(isChecked ? 0 : AndroidUtilities.dp(100), AndroidUtilities.dp(18));
        }
        rippleDrawable.setState(value ? pressedState : StateSet.NOTHING);
        invalidate();
    }

    @Override
    protected boolean verifyDrawable(Drawable who) {
        return super.verifyDrawable(who) || rippleDrawable != null && who == rippleDrawable;
    }

    protected int processColor(int color) {
        return color;
    }

    public void setColors(int track, int trackChecked, int thumb, int thumbChecked) {
        trackColorKey = track;
        trackCheckedColorKey = trackChecked;
        thumbColorKey = thumb;
        thumbCheckedColorKey = thumbChecked;
    }

    // Solegram: liquid glass switch (see com.solegram.messenger.ui.LiquidControls)
    private final AnimatedFloat solegramActive = new AnimatedFloat(this, 0, 300, CubicBezierInterpolator.EASE_OUT_BACK);
    private final RectF solegramTrack = new RectF();

    private final android.graphics.Path solegramWave = new android.graphics.Path();

    private boolean solegramLiquid() {
        // also with icons and with TextCheckCell's colour wave (setOverrideColor): a master toggle that
        // once ran setBackgroundColorAnimated kept the override for good and fell back to the stock switch
        return com.solegram.messenger.ui.LiquidControls.enabled();
    }

    private com.solegram.messenger.ui.LiquidControls.Lens solegramLens;
    // liquid stretch: the thumb draws out along its way in proportion to its speed, and rounds up as it stops
    private float solegramStretch, solegramLastProgress = -1;
    private long solegramLastFrame;
    private boolean solegramRich;
    // what the glass thumb refracts: the track (one instance, nothing is made per frame)
    private final com.solegram.messenger.ui.LiquidControls.LensContent solegramLensContent = c -> solegramDrawTrack(c, solegramTrack.height() / 2f);

    /** Stretch (0..0.3) of the thumb for this frame, from how fast {@link #progress} moves. */
    private float solegramStretchStep() {
        final long now = android.view.animation.AnimationUtils.currentAnimationTimeMillis();
        final float dt = solegramLastFrame == 0 ? 0 : Math.min(0.05f, (now - solegramLastFrame) / 1000f);
        float target = 0;
        if (checkAnimator != null && solegramRich && dt > 0 && solegramLastProgress >= 0) {
            target = Math.min(0.3f, Math.abs(progress - solegramLastProgress) / dt * 0.05f);
        }
        solegramLastFrame = checkAnimator != null || solegramStretch > 0.002f ? now : 0;
        solegramLastProgress = progress;
        // a little lag, so the shape flows instead of following every frame
        solegramStretch += (target - solegramStretch) * (dt > 0 ? Math.min(1f, dt * 22f) : 0f);
        if (solegramStretch < 0.002f && target == 0) {
            solegramStretch = 0;
        } else if (checkAnimator == null) {
            invalidate(); // settling back into a pill after the thumb stopped
        }
        return solegramStretch;
    }

    private int solegramTrackColor(float colorProgress) {
        final int offColor = processColor(Theme.getColor(trackColorKey, resourcesProvider));
        final int onColor = processColor(Theme.getColor(trackCheckedColorKey, resourcesProvider));
        return androidx.core.graphics.ColorUtils.blendARGB(offColor, onColor, Math.max(0f, Math.min(1f, colorProgress)));
    }

    private void solegramDrawTrack(Canvas canvas, float r) {
        if (overrideColorProgress == 0) {
            paint.setColor(solegramTrackColor(progress));
            canvas.drawRoundRect(solegramTrack, r, r, paint);
            return;
        }
        // the cell's colour wave, as the stock switch draws it with bitmaps: the old colour outside
        // the growing circle, the new one inside
        final boolean toChecked = overrideColorProgress == 1;
        paint.setColor(solegramTrackColor(toChecked ? 0 : 1));
        canvas.drawRoundRect(solegramTrack, r, r, paint);
        if (overlayRad > 0) {
            canvas.save();
            solegramWave.rewind();
            solegramWave.addCircle(overlayCx - getX(), overlayCy - getY(), overlayRad, android.graphics.Path.Direction.CW);
            canvas.clipPath(solegramWave);
            paint.setColor(solegramTrackColor(toChecked ? 1 : 0));
            canvas.drawRoundRect(solegramTrack, r, r, paint);
            canvas.restore();
        }
    }

    private void solegramDrawLiquid(Canvas canvas) {
        final float w = getMeasuredWidth(), h = getMeasuredHeight();
        // the footprint of Telegram's own switch (37 x 20): a short, tall track and a big thumb
        final float trackW = Math.min(w - AndroidUtilities.dp(1), AndroidUtilities.dp(36));
        final float trackH = Math.min(h * 0.9f, trackW / 1.95f);
        final float left = (w - trackW) / 2f, top = (h - trackH) / 2f;
        solegramTrack.set(left, top, left + trackW, top + trackH);

        final float active = solegramActive.set(checkAnimator != null ? 1f : 0f);
        // at rest the thumb fills the track height (a slightly wide pill); as glass it swells,
        // but never past the view, which is no taller than the old switch
        final float inset = AndroidUtilities.dpf2(2);
        final float restH = trackH - inset * 2f, restW = restH * 1.42f;
        final float from = left + inset + restW / 2f, to = left + trackW - inset - restW / 2f;
        // as glass the thumb swells, but stays inside the view at both ends (the cell clips the switch)
        final float room = Math.min(h - AndroidUtilities.dpf2(0.5f), restH * 1.3f) / restH;
        final float roomX = (2f * Math.min(from, w - to)) / restW;
        final float maxScale = Math.max(1f, Math.min(room, roomX));
        final float a = Math.max(0f, Math.min(1f, active)); // the spring may overshoot
        final float scale = 1f + (maxScale - 1f) * a;
        // liquid stretch while it travels: longer and a touch thinner, as a drop pulled along
        final float stretch = solegramStretchStep();
        final float thumbW = restW * scale * (1f + stretch), thumbH = restH * scale * (1f - stretch * 0.22f);
        final float halfW = thumbW / 2f;
        final float cx = Math.max(halfW, Math.min(w - halfW, from + (to - from) * progress));
        // the track; while the thumb is glass, not under it - the lens shows it refracted instead
        if (a > 0.01f) {
            canvas.save();
            com.solegram.messenger.ui.LiquidControls.clipOutThumb(canvas, cx, h / 2f, thumbW, thumbH);
            solegramDrawTrack(canvas, trackH / 2f);
            canvas.restore();
        } else {
            solegramDrawTrack(canvas, trackH / 2f);
        }
        if (solegramLens == null) {
            solegramLens = new com.solegram.messenger.ui.LiquidControls.Lens();
        }
        com.solegram.messenger.ui.LiquidControls.drawThumb(canvas, cx, h / 2f, thumbW, thumbH, a, solegramLensContent,
            solegramLens, getMeasuredWidth(), getMeasuredHeight());
        solegramDrawIcon(canvas, cx, h / 2f, thumbH, 1f - a);
    }

    /** The lock / tick / cross of the stock thumb, on the white pill; it fades while the thumb is clear glass. */
    private void solegramDrawIcon(Canvas canvas, float cx, float cy, float thumbH, float alpha) {
        if (alpha < 0.02f || iconDrawable == null && drawIconType == 0 && iconAnimator == null) {
            return;
        }
        final int color = solegramTrackColor(progress);
        paint2.setColor(color);
        if (iconDrawable != null) {
            final int iconColor = solegramTrackColor(isChecked ? 1 : 0);
            if (lastIconColor != iconColor) {
                iconDrawable.setColorFilter(new PorterDuffColorFilter(lastIconColor = iconColor, PorterDuff.Mode.MULTIPLY));
            }
        }
        // the stock glyphs are laid out for a 16dp thumb
        final float s = thumbH / AndroidUtilities.dp(16);
        final int restore = alpha < 0.98f
            ? canvas.saveLayerAlpha(cx - thumbH, cy - thumbH, cx + thumbH, cy + thumbH, (int) (255 * alpha))
            : canvas.save();
        canvas.scale(s, s, cx, cy);
        drawThumbIcon(canvas, (int) cx, (int) cy);
        canvas.restoreToCount(restore);
    }

    private void animateToCheckedState(boolean newCheckedState) {
        checkAnimator = ObjectAnimator.ofFloat(this, "progress", newCheckedState ? 1 : 0);
        if (solegramLiquid()) {
            solegramRich = com.solegram.messenger.ui.SolegramMotion.rich(); // the stretch is decoration: off in power saving
            // springy travel like the liquid glass demo: overshoot, settle, the thumb glows as glass meanwhile
            checkAnimator.setDuration(420);
            checkAnimator.setInterpolator(CubicBezierInterpolator.EASE_OUT_BACK);
        } else
        checkAnimator.setDuration(200);
        checkAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                checkAnimator = null;
                invalidate(); // lets the liquid thumb turn back into a white pill
            }
        });
        checkAnimator.start();
    }

    private void animateIcon(boolean newCheckedState) {
        iconAnimator = ObjectAnimator.ofFloat(this, "iconProgress", newCheckedState ? 1 : 0);
        iconAnimator.setDuration(200);
        iconAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                iconAnimator = null;
            }
        });
        iconAnimator.start();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attachedToWindow = true;
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        attachedToWindow = false;
    }

    public void setOnCheckedChangeListener(OnCheckedChangeListener listener) {
        onCheckedChangeListener = listener;
    }

    public void setChecked(boolean checked, boolean animated) {
        setChecked(checked, drawIconType, animated);
    }

    public void setChecked(boolean checked, int iconType, boolean animated) {
        if (checked != isChecked) {
            isChecked = checked;
            if (attachedToWindow && animated) {
                animateToCheckedState(checked);
            } else {
                cancelCheckAnimator();
                setProgress(checked ? 1.0f : 0.0f);
            }
            if (onCheckedChangeListener != null) {
                onCheckedChangeListener.onCheckedChanged(this, checked);
            }
        }
        setDrawIconType(iconType, animated);
    }

    public void setIcon(int icon) {
        if (icon != 0) {
            iconDrawable = getResources().getDrawable(icon).mutate();
            if (iconDrawable != null) {
                iconDrawable.setColorFilter(new PorterDuffColorFilter(lastIconColor = Theme.getColor(isChecked ? trackCheckedColorKey : trackColorKey, resourcesProvider), PorterDuff.Mode.MULTIPLY));
            }
        } else {
            iconDrawable = null;
        }
        invalidate();
    }

    public void setIconVisible(boolean visible, boolean animated) {
        animatorIconVisibility.setValue(visible, animated);
    }

    public void setDrawIconType(int iconType, boolean animated) {
        if (drawIconType != iconType) {
            drawIconType = iconType;
            if (attachedToWindow && animated) {
                animateIcon(iconType == 0);
            } else {
                cancelIconAnimator();
                setIconProgress(iconType == 0 ? 1.0f : 0.0f);
            }
        }
    }

    public boolean hasIcon() {
        return iconDrawable != null;
    }

    public boolean isChecked() {
        return isChecked;
    }

    public void setOverrideColor(int override) {
        if (overrideColorProgress == override) {
            return;
        }
        if (overlayBitmap == null) {
            try {
                overlayBitmap = new Bitmap[2];
                overlayCanvas = new Canvas[2];
                for (int a = 0; a < 2; a++) {
                    overlayBitmap[a] = Bitmap.createBitmap(getMeasuredWidth(), getMeasuredHeight(), Bitmap.Config.ARGB_8888);
                    overlayCanvas[a] = new Canvas(overlayBitmap[a]);
                }
                overlayMaskBitmap = Bitmap.createBitmap(getMeasuredWidth(), getMeasuredHeight(), Bitmap.Config.ARGB_8888);
                overlayMaskCanvas = new Canvas(overlayMaskBitmap);

                overlayEraserPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                overlayEraserPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));

                overlayMaskPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                overlayMaskPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
                bitmapsCreated = true;
            } catch (Throwable e) {
                return;
            }
        }
        if (!bitmapsCreated) {
            return;
        }
        overrideColorProgress = override;
        overlayCx = 0;
        overlayCy = 0;
        overlayRad = 0;
        invalidate();
    }

    public void setOverrideColorProgress(float cx, float cy, float rad) {
        overlayCx = cx;
        overlayCy = cy;
        overlayRad = rad;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (getVisibility() != VISIBLE) {
            return;
        }
        if (solegramLiquid()) {
            solegramDrawLiquid(canvas);
            return;
        }

        int width = AndroidUtilities.dp(31);
        int thumb = AndroidUtilities.dp(20);
        int x = (getMeasuredWidth() - width) / 2;
        float y = (getMeasuredHeight() - AndroidUtilities.dpf2(14)) / 2;
        int tx = x + AndroidUtilities.dp(7) + (int) (AndroidUtilities.dp(17) * progress);
        int ty = getMeasuredHeight() / 2;


        int color1;
        int color2;
        float colorProgress;
        int r1;
        int r2;
        int g1;
        int g2;
        int b1;
        int b2;
        int a1;
        int a2;
        int red;
        int green;
        int blue;
        int alpha;
        int color;

        for (int a = 0; a < 2; a++) {
            if (a == 1 && overrideColorProgress == 0) {
                continue;
            }
            Canvas canvasToDraw = a == 0 ? canvas : overlayCanvas[0];

            if (a == 1) {
                overlayBitmap[0].eraseColor(0);
                paint.setColor(0xff000000);
                overlayMaskCanvas.drawRect(0, 0, overlayMaskBitmap.getWidth(), overlayMaskBitmap.getHeight(), paint);
                overlayMaskCanvas.drawCircle(overlayCx - getX(), overlayCy - getY(), overlayRad, overlayEraserPaint);
            }
            if (overrideColorProgress == 1) {
                colorProgress = a == 0 ? 0 : 1;
            } else if (overrideColorProgress == 2) {
                colorProgress = a == 0 ? 1 : 0;
            } else {
                colorProgress = progress;
            }

            color1 = processColor(Theme.getColor(trackColorKey, resourcesProvider));
            color2 = processColor(Theme.getColor(trackCheckedColorKey, resourcesProvider));
            if (a == 0 && iconDrawable != null && lastIconColor != (isChecked ? color2 : color1)) {
                iconDrawable.setColorFilter(new PorterDuffColorFilter(lastIconColor = (isChecked ? color2 : color1), PorterDuff.Mode.MULTIPLY));
            }

            r1 = Color.red(color1);
            r2 = Color.red(color2);
            g1 = Color.green(color1);
            g2 = Color.green(color2);
            b1 = Color.blue(color1);
            b2 = Color.blue(color2);
            a1 = Color.alpha(color1);
            a2 = Color.alpha(color2);

            red = (int) (r1 + (r2 - r1) * colorProgress);
            green = (int) (g1 + (g2 - g1) * colorProgress);
            blue = (int) (b1 + (b2 - b1) * colorProgress);
            alpha = (int) (a1 + (a2 - a1) * colorProgress);
            color = ((alpha & 0xff) << 24) | ((red & 0xff) << 16) | ((green & 0xff) << 8) | (blue & 0xff);
            paint.setColor(color);
            paint2.setColor(color);

            rectF.set(x, y, x + width, y + AndroidUtilities.dpf2(14));
            canvasToDraw.drawRoundRect(rectF, AndroidUtilities.dpf2(7), AndroidUtilities.dpf2(7), paint);
            canvasToDraw.drawCircle(tx, ty, AndroidUtilities.dpf2(10), paint);

            if (a == 0 && rippleDrawable != null) {
                rippleDrawable.setBounds(tx - AndroidUtilities.dp(18), ty - AndroidUtilities.dp(18), tx + AndroidUtilities.dp(18), ty + AndroidUtilities.dp(18));
                rippleDrawable.draw(canvasToDraw);
            } else if (a == 1) {
                canvasToDraw.drawBitmap(overlayMaskBitmap, 0, 0, overlayMaskPaint);
            }
        }
        if (overrideColorProgress != 0) {
            canvas.drawBitmap(overlayBitmap[0], 0, 0, null);
        }

        for (int a = 0; a < 2; a++) {
            if (a == 1 && overrideColorProgress == 0) {
                continue;
            }
            Canvas canvasToDraw = a == 0 ? canvas : overlayCanvas[1];

            if (a == 1) {
                overlayBitmap[1].eraseColor(0);
            }
            if (overrideColorProgress == 1) {
                colorProgress = a == 0 ? 0 : 1;
            } else if (overrideColorProgress == 2) {
                colorProgress = a == 0 ? 1 : 0;
            } else {
                colorProgress = progress;
            }

            color1 = Theme.getColor(thumbColorKey, resourcesProvider);
            color2 = processColor(Theme.getColor(thumbCheckedColorKey, resourcesProvider));
            r1 = Color.red(color1);
            r2 = Color.red(color2);
            g1 = Color.green(color1);
            g2 = Color.green(color2);
            b1 = Color.blue(color1);
            b2 = Color.blue(color2);
            a1 = Color.alpha(color1);
            a2 = Color.alpha(color2);

            red = (int) (r1 + (r2 - r1) * colorProgress);
            green = (int) (g1 + (g2 - g1) * colorProgress);
            blue = (int) (b1 + (b2 - b1) * colorProgress);
            alpha = (int) (a1 + (a2 - a1) * colorProgress);
            paint.setColor(((alpha & 0xff) << 24) | ((red & 0xff) << 16) | ((green & 0xff) << 8) | (blue & 0xff));

            canvasToDraw.drawCircle(tx, ty, AndroidUtilities.dp(8), paint);

            if (a == 0) {
                drawThumbIcon(canvasToDraw, tx, ty); // Solegram: shared with the liquid switch
            }
            if (a == 1) {
                canvasToDraw.drawBitmap(overlayMaskBitmap, 0, 0, overlayMaskPaint);
            }
        }
        if (overrideColorProgress != 0) {
            canvas.drawBitmap(overlayBitmap[1], 0, 0, null);
        }
    }

    // Solegram: the thumb's icon, shared by the stock and the liquid switch
    private void drawThumbIcon(Canvas canvas, int tx, int ty) {
        if (iconDrawable != null) {
            final float factor = animatorIconVisibility.getFloatValue();
            if (factor > 0) {
                final boolean needScale = factor < 1;
                if (needScale) {
                    canvas.save();
                    canvas.scale(factor, factor, tx, ty);
                }
                iconDrawable.setBounds(tx - iconDrawable.getIntrinsicWidth() / 2, ty - iconDrawable.getIntrinsicHeight() / 2, tx + iconDrawable.getIntrinsicWidth() / 2, ty + iconDrawable.getIntrinsicHeight() / 2);
                iconDrawable.draw(canvas);
                if (needScale) {
                    canvas.restore();
                }
            }
        } else if (drawIconType == 1) {
            tx -= AndroidUtilities.dp(10.8f) - AndroidUtilities.dp(1.3f) * progress;
            ty -= AndroidUtilities.dp(8.5f) - AndroidUtilities.dp(0.5f) * progress;
            int startX2 = (int) AndroidUtilities.dpf2(4.6f) + tx;
            int startY2 = (int) (AndroidUtilities.dpf2(9.5f) + ty);
            int endX2 = startX2 + AndroidUtilities.dp(2);
            int endY2 = startY2 + AndroidUtilities.dp(2);

            int startX = (int) AndroidUtilities.dpf2(7.5f) + tx;
            int startY = (int) AndroidUtilities.dpf2(5.4f) + ty;
            int endX = startX + AndroidUtilities.dp(7);
            int endY = startY + AndroidUtilities.dp(7);

            startX = (int) (startX + (startX2 - startX) * progress);
            startY = (int) (startY + (startY2 - startY) * progress);
            endX = (int) (endX + (endX2 - endX) * progress);
            endY = (int) (endY + (endY2 - endY) * progress);
            canvas.drawLine(startX, startY, endX, endY, paint2);

            startX = (int) AndroidUtilities.dpf2(7.5f) + tx;
            startY = (int) AndroidUtilities.dpf2(12.5f) + ty;
            endX = startX + AndroidUtilities.dp(7);
            endY = startY - AndroidUtilities.dp(7);
            canvas.drawLine(startX, startY, endX, endY, paint2);
        } else if (drawIconType == 2 || iconAnimator != null) {
            paint2.setAlpha((int) (255 * (1.0f - iconProgress)));
            canvas.drawLine(tx, ty, tx, ty - AndroidUtilities.dp(5), paint2);
            canvas.save();
            canvas.rotate(-90 * iconProgress, tx, ty);
            canvas.drawLine(tx, ty, tx + AndroidUtilities.dp(4), ty, paint2);
            canvas.restore();
        }
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName("android.widget.Switch");
        info.setCheckable(true);
        info.setChecked(isChecked);
        //info.setContentDescription(isChecked ? LocaleController.getString(R.string.NotificationsOn) : LocaleController.getString(R.string.NotificationsOff));
    }
}
