/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.ui.Components;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.DecelerateInterpolator;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;

import androidx.annotation.Keep;

public class ShutterButton extends View {

    public enum State {
        DEFAULT,
        RECORDING
    }

    private final static int LONG_PRESS_TIME = 800;

    private Drawable shadowDrawable;

    private DecelerateInterpolator interpolator = new DecelerateInterpolator();
    private Paint whitePaint;
    private Paint redPaint;
    private ShutterButtonDelegate delegate;
    private State state;
    private boolean pressed;
    private float redProgress;
    private long lastUpdateTime;
    private long totalTime;
    private boolean processRelease;

    private Runnable longPressed = new Runnable() {
        public void run() {
            if (delegate != null) {
                if (!delegate.shutterLongPressed()) {
                    processRelease = false;
                }
            }
        }
    };

    public interface ShutterButtonDelegate {
        boolean shutterLongPressed();
        void shutterReleased();
        void shutterCancel();
        boolean onTranslationChanged(float x, float y);

        /** Solegram: a tap in video mode starts a recording (a second tap stops it through {@link #shutterReleased}). */
        default void shutterVideoTapped() {
            shutterLongPressed();
        }
    }

    // region Solegram: the shutter of a real camera - a white ring, a disc that sinks while pressed,
    // red for video, a rounded square while recording or while the self-timer counts down

    private boolean solegramStyle;
    private boolean videoMode;
    private boolean timerRunning;
    private Paint ringPaint;
    private Paint innerPaint;
    private final RectF innerRect = new RectF();
    private final AnimatedFloat pressT = new AnimatedFloat(this, 0, 160, CubicBezierInterpolator.EASE_OUT_QUINT);
    private final AnimatedFloat videoT = new AnimatedFloat(this, 0, 320, CubicBezierInterpolator.EASE_OUT_QUINT);
    private final AnimatedFloat squareT = new AnimatedFloat(this, 0, 320, CubicBezierInterpolator.EASE_OUT_QUINT);

    public void setSolegramStyle(boolean value) {
        if (solegramStyle == value) {
            return;
        }
        solegramStyle = value;
        if (value && ringPaint == null) {
            ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            ringPaint.setStyle(Paint.Style.STROKE);
            ringPaint.setColor(0xffffffff);
            ringPaint.setStrokeWidth(AndroidUtilities.dp(3.5f));
            ringPaint.setShadowLayer(AndroidUtilities.dp(2), 0, 0, 0x30000000);
            innerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            innerPaint.setStyle(Paint.Style.FILL);
        }
        invalidate();
    }

    public boolean isSolegramStyle() {
        return solegramStyle;
    }

    /** Video mode: taps start and stop recordings, the disc is red. */
    public void setVideoMode(boolean value, boolean animated) {
        videoMode = value;
        if (!animated) {
            videoT.set(value ? 1 : 0, true);
        }
        invalidate();
    }

    public boolean isVideoMode() {
        return solegramStyle && videoMode;
    }

    /** The self-timer is counting: a tap on the shutter stops it. */
    public void setTimerRunning(boolean value) {
        timerRunning = value;
        invalidate();
    }

    private void drawSolegram(Canvas canvas, int cx, int cy) {
        final float press = pressT.set(pressed && state != State.RECORDING ? 1 : 0);
        final float video = videoT.set(videoMode || state == State.RECORDING ? 1 : 0);
        final float square = squareT.set(state == State.RECORDING || timerRunning ? 1 : 0);
        canvas.drawCircle(cx, cy, AndroidUtilities.dp(34.5f), ringPaint);
        // the disc: white for photos, red for video, sinks while pressed, a rounded square while recording
        innerPaint.setColor(androidx.core.graphics.ColorUtils.blendARGB(0xffffffff, 0xffff3b30, timerRunning ? 0 : video));
        final float radius = AndroidUtilities.dpf2(29f) * (1f - 0.1f * press);
        final float half = AndroidUtilities.lerp(radius, AndroidUtilities.dpf2(14f), square);
        final float corner = AndroidUtilities.lerp(radius, AndroidUtilities.dpf2(6f), square);
        innerRect.set(cx - half, cy - half, cx + half, cy + half);
        canvas.drawRoundRect(innerRect, corner, corner, innerPaint);
    }
    // endregion

    public ShutterButton(Context context) {
        super(context);
        shadowDrawable = getResources().getDrawable(R.drawable.camera_btn);
        whitePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        whitePaint.setStyle(Paint.Style.FILL);
        whitePaint.setColor(0xffffffff);
        redPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        redPaint.setStyle(Paint.Style.FILL);
        redPaint.setColor(0xffcd4747);
        state = State.DEFAULT;
    }

    public void setDelegate(ShutterButtonDelegate shutterButtonDelegate) {
        delegate = shutterButtonDelegate;
    }

    public ShutterButtonDelegate getDelegate() {
        return delegate;
    }

    private void setHighlighted(boolean value) {
        if (solegramStyle) {
            invalidate(); // the disc sinks instead of the button growing
            return;
        }
        AnimatorSet animatorSet = new AnimatorSet();
        if (value) {
            animatorSet.playTogether(
                    ObjectAnimator.ofFloat(this, View.SCALE_X, 1.06f),
                    ObjectAnimator.ofFloat(this, View.SCALE_Y, 1.06f));
        } else {
            animatorSet.playTogether(
                    ObjectAnimator.ofFloat(this, View.SCALE_X, 1.0f),
                    ObjectAnimator.ofFloat(this, View.SCALE_Y, 1.0f));
            animatorSet.setStartDelay(40);
        }
        animatorSet.setDuration(120);
        animatorSet.setInterpolator(interpolator);
        animatorSet.start();
    }

    @Keep
    @Override
    public void setScaleX(float scaleX) {
        super.setScaleX(scaleX);
        invalidate();
    }

    public State getState() {
        return state;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int cx = getMeasuredWidth() / 2;
        int cy = getMeasuredHeight() / 2;
        if (solegramStyle) {
            drawSolegram(canvas, cx, cy);
            return;
        }

        shadowDrawable.setBounds(cx - AndroidUtilities.dp(36), cy - AndroidUtilities.dp(36), cx + AndroidUtilities.dp(36), cy + AndroidUtilities.dp(36));
        shadowDrawable.draw(canvas);
        if (pressed || getScaleX() != 1.0f) {
            float scale = (getScaleX() - 1.0f) / 0.06f;
            whitePaint.setAlpha((int) (255 * scale));
            canvas.drawCircle(cx, cy, AndroidUtilities.dp(26), whitePaint);

            if (state == State.RECORDING) {
                if (redProgress != 1.0f) {
                    long dt = Math.abs(System.currentTimeMillis() - lastUpdateTime);
                    if (dt > 17) {
                        dt = 17;
                    }
                    totalTime += dt;
                    if (totalTime > 120) {
                        totalTime = 120;
                    }
                    redProgress = interpolator.getInterpolation(totalTime / 120.0f);
                    invalidate();
                }
                canvas.drawCircle(cx, cy, AndroidUtilities.dp(26.5f) * scale * redProgress, redPaint);
            } else if (redProgress != 0) {
                canvas.drawCircle(cx, cy, AndroidUtilities.dp(26.5f) * scale, redPaint);
            }
        } else if (redProgress != 0) {
            redProgress = 0;
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(AndroidUtilities.dp(84), AndroidUtilities.dp(84));
    }

    @Override
    public boolean onTouchEvent(MotionEvent motionEvent) {
        float x = motionEvent.getX();
        float y = motionEvent.getY();
        if (isVideoMode()) {
            // Solegram video mode: a tap starts a recording, the next tap stops it; no long press, no drag
            switch (motionEvent.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    pressed = true;
                    processRelease = true;
                    invalidate();
                    break;
                case MotionEvent.ACTION_UP:
                    pressed = false;
                    invalidate();
                    if (processRelease && x >= -AndroidUtilities.dp(24) && x <= getMeasuredWidth() + AndroidUtilities.dp(24)
                            && y >= -AndroidUtilities.dp(24) && y <= getMeasuredHeight() + AndroidUtilities.dp(24)) {
                        if (state == State.RECORDING || timerRunning) {
                            delegate.shutterReleased();
                        } else {
                            delegate.shutterVideoTapped();
                        }
                    }
                    processRelease = false;
                    break;
                case MotionEvent.ACTION_CANCEL:
                    pressed = false;
                    processRelease = false;
                    invalidate();
                    break;
            }
            return true;
        }
        switch (motionEvent.getAction()) {
            case MotionEvent.ACTION_DOWN:
                AndroidUtilities.runOnUIThread(longPressed, LONG_PRESS_TIME);
                pressed = true;
                processRelease = true;
                setHighlighted(true);
                break;
            case MotionEvent.ACTION_UP:
                if (solegramStyle) {
                    pressed = false;
                }
                setHighlighted(false);
                AndroidUtilities.cancelRunOnUIThread(longPressed);
                if (processRelease) {
                    delegate.shutterReleased();
                }
                break;
            case MotionEvent.ACTION_MOVE:
                float dx = x >= 0 && x <= getMeasuredWidth() ? 0 : x;
                float dy = y >= 0 && y <= getMeasuredHeight() ? 0 : y;
                if (delegate.onTranslationChanged(dx, dy)) {
                    AndroidUtilities.cancelRunOnUIThread(longPressed);
                    if (state == State.RECORDING) {
                        processRelease = false;
                        setHighlighted(false);
                        delegate.shutterCancel();
                        setState(State.DEFAULT, true);
                    }
                }
                break;
            case MotionEvent.ACTION_CANCEL:
                setHighlighted(false);
                pressed = false;
        }
        return true;
    }

    public void setState(State value, boolean animated) {
        if (state != value) {
            state = value;
            if (animated) {
                lastUpdateTime = System.currentTimeMillis();
                totalTime = 0;
                if (state != State.RECORDING) {
                    redProgress = 0.0f;
                }
            } else {
                if (state == State.RECORDING) {
                    redProgress = 1.0f;
                } else {
                    redProgress = 0.0f;
                }
            }
            invalidate();
        }
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName("android.widget.Button");
        info.setClickable(true);
        info.setLongClickable(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            info.addAction(new AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK.getId(), LocaleController.getString(R.string.AccActionTakePicture)));
            info.addAction(new AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_LONG_CLICK.getId(), LocaleController.getString(R.string.AccActionRecordVideo)));
        }
    }
}
