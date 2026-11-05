/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;

/**
 * The player's progress line with a comet: while the track plays a bright tail grows behind the playhead, and on
 * pause it fades and pulls back into the head. Dragging seeks. Style LINE draws a plain line instead.
 */
public class SolegramCometProgress extends View {

    public static final int STYLE_COMET = 0, STYLE_LINE = 1;

    public interface Delegate {
        void onSeek(float progress, boolean done);
    }

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tail = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint head = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fadeAhead = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.BlurMaskFilter blur = new android.graphics.BlurMaskFilter(AndroidUtilities.dp(5), android.graphics.BlurMaskFilter.Blur.NORMAL);
    private int glowColor = 0xFFB9C2FF;
    private final Paint cone = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path conePath = new Path();
    private final android.graphics.BlurMaskFilter wideBlur = new android.graphics.BlurMaskFilter(AndroidUtilities.dp(20), android.graphics.BlurMaskFilter.Blur.NORMAL);
    private final android.graphics.BlurMaskFilter coreBlur = new android.graphics.BlurMaskFilter(AndroidUtilities.dp(10), android.graphics.BlurMaskFilter.Blur.NORMAL);
    private Delegate delegate;
    private int color = 0xFFFFFFFF;
    private int style = STYLE_COMET;
    private float progress, buffered;
    private boolean playing;
    /** 0..1: how grown the tail is. */
    private float tailAmount;
    private float press;
    private boolean dragging;
    private float dragProgress;
    private long lastFrame;

    public SolegramCometProgress(Context context) {
        super(context);
        track.setStrokeCap(Paint.Cap.ROUND);
        fill.setStrokeCap(Paint.Cap.ROUND);
        tail.setStrokeCap(Paint.Cap.ROUND);
        fadeAhead.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN));
    }

    public void setDelegate(Delegate delegate) {
        this.delegate = delegate;
    }

    public void setColor(int color) {
        this.color = color;
        invalidate();
    }

    /** The comet's light (the cover's colour, made light). */
    public void setGlowColor(int glowColor) {
        this.glowColor = glowColor;
        invalidate();
    }

    public void setStyle(int style) {
        this.style = style;
        invalidate();
    }

    public void setProgress(float progress, float buffered) {
        if (!dragging) {
            this.progress = Math.max(0, Math.min(1, progress));
        }
        this.buffered = Math.max(0, Math.min(1, buffered));
        invalidate();
    }

    public void setPlaying(boolean playing) {
        if (this.playing != playing) {
            this.playing = playing;
            invalidate();
        }
    }

    public boolean isDragging() {
        return dragging;
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        final float pad = dp(10);
        final float w = getWidth() - pad * 2;
        final float p = Math.max(0, Math.min(1, (e.getX() - pad) / w));
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragging = true;
                getParent().requestDisallowInterceptTouchEvent(true);
                dragProgress = p;
                progress = p;
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (dragging) {
                    dragProgress = p;
                    progress = p;
                    if (delegate != null) {
                        delegate.onSeek(p, false);
                    }
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (dragging) {
                    dragging = false;
                    if (delegate != null && e.getActionMasked() == MotionEvent.ACTION_UP) {
                        delegate.onSeek(dragProgress, true);
                    }
                    invalidate();
                }
                return true;
        }
        return super.onTouchEvent(e);
    }

    /** A cone of light from the tip back along the line: narrow at the tip, widest towards its far end. */
    private void drawCone(Canvas canvas, float tip, float cy, float len, float top, float bottom, float line,
                          android.graphics.BlurMaskFilter blur, int[] colors, float[] stops) {
        if (len < 1) {
            return;
        }
        conePath.rewind();
        conePath.moveTo(tip, cy - line * 0.45f);
        conePath.cubicTo(tip - len * 0.25f, cy - top * 0.55f, tip - len * 0.6f, cy - top, tip - len, cy - top * 0.8f);
        conePath.lineTo(tip - len, cy + bottom * 0.8f);
        conePath.cubicTo(tip - len * 0.6f, cy + bottom, tip - len * 0.25f, cy + bottom * 0.55f, tip, cy + line * 0.45f);
        conePath.close();
        cone.setShader(new LinearGradient(tip, 0, tip - len, 0, colors, stops, Shader.TileMode.CLAMP));
        cone.setMaskFilter(blur);
        canvas.drawPath(conePath, cone);
        cone.setMaskFilter(null);
        cone.setShader(null);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final long now = SystemClock.elapsedRealtime();
        final float dt = lastFrame == 0 ? 16 : Math.min(64, now - lastFrame);
        lastFrame = now;
        boolean animating = false;

        // the tail grows over ~1.4 s of playing and fades in ~0.6 s on pause; the head swells while dragged
        final float targetTail = playing && !dragging && style == STYLE_COMET ? 1f : 0f;
        if (tailAmount != targetTail) {
            final float step = dt / (targetTail > tailAmount ? 1400f : 600f);
            tailAmount = targetTail > tailAmount ? Math.min(targetTail, tailAmount + step) : Math.max(targetTail, tailAmount - step);
            animating = true;
        }
        final float targetPress = dragging ? 1f : 0f;
        if (press != targetPress) {
            press = targetPress > press ? Math.min(1, press + dt / 120f) : Math.max(0, press - dt / 200f);
            animating = true;
        }

        final float pad = dp(10);
        final float cy = getHeight() / 2f;
        final float w = getWidth() - pad * 2;
        final float x = pad + w * progress;
        final float line = dp(5.5f) + dp(1.5f) * press;

        final float eased = 1f - (1f - tailAmount) * (1f - tailAmount);

        // the track ahead of the head
        track.setStrokeWidth(line);
        track.setColor(ColorUtils.setAlphaComponent(color, 0x33));
        canvas.drawLine(Math.max(pad, x), cy, pad + w, cy, track);
        if (buffered > progress) {
            track.setColor(ColorUtils.setAlphaComponent(color, 0x24));
            canvas.drawLine(x, cy, pad + w * buffered, cy, track);
        }

        if (style == STYLE_COMET && eased > 0.01f && x > pad) {
            // the comet's tail: a cone of light from the head, widening backwards, blurred into a haze, with a whiter
            // core near the head
            final float L = dp(230) * eased, top = dp(34) * eased, bottom = dp(20) * eased;
            final float tip = x + dp(3);
            final float spread = dp(28);
            final int layer = canvas.saveLayer(tip - L - spread, cy - top - spread, x + dp(24), cy + bottom + spread, null);
            drawCone(canvas, tip, cy, L, top, bottom, line, wideBlur,
                new int[]{ColorUtils.setAlphaComponent(glowColor, (int) (205 * eased)), ColorUtils.setAlphaComponent(glowColor, (int) (107 * eased)),
                    ColorUtils.setAlphaComponent(glowColor, (int) (31 * eased)), ColorUtils.setAlphaComponent(glowColor, 0)},
                new float[]{0f, 0.25f, 0.6f, 1f});
            drawCone(canvas, tip, cy, L * 0.35f, top * 0.5f, bottom * 0.45f, line, coreBlur,
                new int[]{ColorUtils.setAlphaComponent(0xFFFFFFFF, (int) (190 * eased)), ColorUtils.setAlphaComponent(glowColor, (int) (77 * eased)), ColorUtils.setAlphaComponent(glowColor, 0)},
                new float[]{0f, 0.5f, 1f});
            // the glow reaches the head and dies away just past it, softly
            final float a0 = x - dp(2), a1 = x + dp(18);
            fadeAhead.setShader(new LinearGradient(a0, 0, a1, 0, new int[]{0xFF000000, 0x99000000, 0x33000000, 0x00000000}, new float[]{0f, 0.35f, 0.7f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawRect(tip - L - spread, cy - top - spread, x + dp(24), cy + bottom + spread, fadeAhead);
            canvas.restoreToCount(layer);
        }

        // the played part: the comet's colour, turning white towards the head, ending round
        if (x > pad) {
            fill.setStrokeWidth(line);
            if (style == STYLE_COMET) {
                fill.setShader(new LinearGradient(pad, 0, x, 0, new int[]{glowColor, ColorUtils.blendARGB(glowColor, 0xFFFFFFFF, 0.5f), 0xFFFFFFFF},
                    new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
            } else {
                fill.setShader(null);
                fill.setColor(color);
            }
            canvas.drawLine(pad, cy, x, cy, fill);
            fill.setShader(null);
        }

        // the head: the line's round end; a knob grows out of it while dragged
        if (press > 0.01f || x <= pad) {
            head.setColor(color);
            canvas.drawCircle(x, cy, line / 2f + dp(5) * press, head);
        }

        if (animating || playing && style == STYLE_COMET) {
            postInvalidateOnAnimation();
        } else {
            lastFrame = 0;
        }
    }
}
