/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.SystemClock;
import android.view.View;

import java.util.Random;

/**
 * The dots of a code being typed, as a Pixel phone has them: every place is a soft filled dot; a typed digit turns
 * its dot into a random Material shape (the shapes of the "bud" loading indicator) in the accent colour, with a
 * springy morph and a little hop; a deleted one rounds back into a dot. The shapes never repeat side by side.
 */
public class SolegramPinDots extends View {

    private static final float TAU = (float) (Math.PI * 2);

    private int length;
    private int filled;
    private int emptyColor = 0x4DFFFFFF, filledColor = 0xFF3390EC;
    private Slot[] slots = new Slot[0];
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Random random = new Random();
    private long lastFrame;

    private static final class Slot {
        // morph: from the radii it had to the ones it goes to (null: a dot), springing
        float[] from, to;
        float morph = 1f;
        float hop = 1f;      // 0..1 time of the hop after a change
        float rotation, rotationFrom, rotationTo;
        boolean on;          // a typed digit: the shape and the accent colour
        float colorT;        // 0 dot colour .. 1 accent
        int shape = -1;
    }

    public SolegramPinDots(Context context, int length) {
        super(context);
        setLength(length);
    }

    public void setLength(int length) {
        this.length = length;
        slots = new Slot[length];
        for (int i = 0; i < length; i++) {
            slots[i] = new Slot();
        }
        filled = 0;
        requestLayout();
        invalidate();
    }

    public void setColors(int empty, int full) {
        emptyColor = empty;
        filledColor = full;
        invalidate();
    }

    /** How many digits are typed; the dots that changed hop into their new shape. */
    public void setCount(int count, boolean animated) {
        count = Math.max(0, Math.min(length, count));
        for (int i = 0; i < length; i++) {
            final boolean on = i < count;
            final Slot s = slots[i];
            if (s.on == on) {
                continue;
            }
            s.on = on;
            s.from = current(s);
            s.rotationFrom = s.rotation;
            if (on) {
                s.shape = pickShape(i);
                s.to = SolegramShapeProgress.shape(s.shape);
                s.rotationTo = s.rotation + (random.nextBoolean() ? 1 : -1) * (float) Math.PI / 5f;
            } else {
                s.to = null;
                s.shape = -1;
                s.rotationTo = s.rotation - (float) Math.PI / 8f;
            }
            if (animated) {
                s.morph = 0f;
                s.hop = 0f;
            } else {
                s.morph = 1f;
                s.hop = 1f;
                s.colorT = on ? 1f : 0f;
                s.rotation = s.rotationTo;
            }
        }
        filled = count;
        invalidate();
    }

    /** A shape not the same as its neighbours'. */
    private int pickShape(int i) {
        final int n = SolegramShapeProgress.shapeCount();
        for (int tries = 0; tries < 12; tries++) {
            final int k = random.nextInt(n);
            if ((i == 0 || slots[i - 1].shape != k) && (i + 1 >= length || slots[i + 1].shape != k)) {
                return k;
            }
        }
        return random.nextInt(n);
    }

    /** The radii as drawn now (a dot when it has none). */
    private float[] current(Slot s) {
        final int n = SolegramShapeProgress.pointCount();
        final float[] out = new float[n];
        final float k = spring(s.morph);
        for (int i = 0; i < n; i++) {
            final float a = s.from == null ? 1f : s.from[i];
            final float b = s.to == null ? 1f : s.to[i];
            out[i] = a + (b - a) * k;
        }
        return out;
    }

    private static float spring(float t) {
        if (t >= 1) {
            return 1;
        }
        final double z = 0.45, w = 22;
        return (float) (1 - Math.exp(-w * z * t) * Math.cos(w * Math.sqrt(1 - z * z) * t));
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final int w = MeasureSpec.getSize(widthMeasureSpec);
        setMeasuredDimension(w, dp(34));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final long now = SystemClock.elapsedRealtime();
        final float dt = lastFrame == 0 ? 0.016f : Math.min(0.05f, (now - lastFrame) / 1000f);
        lastFrame = now;
        boolean animating = false;

        final float gap = dp(28), dotR = dp(6), shapeR = dp(10);
        final float total = gap * (length - 1);
        final float startX = getWidth() / 2f - total / 2f, cy = getHeight() / 2f;
        final int n = SolegramShapeProgress.pointCount();
        for (int i = 0; i < length; i++) {
            final Slot s = slots[i];
            if (s.morph < 1f) {
                s.morph = Math.min(1f, s.morph + dt / 0.55f);
                animating = true;
            }
            if (s.hop < 1f) {
                s.hop = Math.min(1f, s.hop + dt / 0.36f);
                animating = true;
            }
            final float targetColor = s.on ? 1f : 0f;
            if (s.colorT != targetColor) {
                s.colorT = s.on ? Math.min(1f, s.colorT + dt / 0.18f) : Math.max(0f, s.colorT - dt / 0.22f);
                animating = true;
            }
            final float k = spring(s.morph);
            s.rotation = s.rotationFrom + (s.rotationTo - s.rotationFrom) * k;
            // the size: a dot grows into a shape, a shape shrinks back into a dot
            final float sizeFrom = s.from == null ? dotR : shapeR, sizeTo = s.to == null ? dotR : shapeR;
            final float R = sizeFrom + (sizeTo - sizeFrom) * k;
            // the hop: up and back, with a small squash as it lands
            final float h = s.hop >= 1f ? 0f : (float) Math.sin(s.hop * Math.PI);
            final float y = cy - dp(7) * h;
            final float x = startX + gap * i;
            path.rewind();
            for (int j = 0; j < n; j++) {
                final float a = s.from == null ? 1f : s.from[j];
                final float b = s.to == null ? 1f : s.to[j];
                final float r = R * (a + (b - a) * k);
                final double ang = j / (double) n * TAU + s.rotation;
                final float px = x + (float) Math.cos(ang) * r, py = y + (float) Math.sin(ang) * r;
                if (j == 0) {
                    path.moveTo(px, py);
                } else {
                    path.lineTo(px, py);
                }
            }
            path.close();
            paint.setColor(androidx.core.graphics.ColorUtils.blendARGB(emptyColor, filledColor, s.colorT));
            canvas.drawPath(path, paint);
        }
        if (animating) {
            postInvalidateOnAnimation();
        } else {
            lastFrame = 0;
        }
    }
}
