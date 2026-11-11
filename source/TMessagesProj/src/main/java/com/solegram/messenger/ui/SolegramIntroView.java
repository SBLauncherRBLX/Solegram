/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;
import android.view.animation.OvershootInterpolator;

import androidx.core.graphics.ColorUtils;

import org.telegram.ui.Components.CubicBezierInterpolator;

/**
 * The picture of the welcome screen: Solegram's mark, alive. The app's tile floats in a soft glow of its own
 * colour; on it the half sun rises from behind the two message lines, which draw themselves from the middle
 * outwards, and short rays open above it. Swiping between the pages lets the sun set a little and rise again
 * in the colour of the next page, and tilts the tile after the finger.
 */
public class SolegramIntroView extends View {

    /** Top and bottom colour of the tile for each page. */
    private static final int[][] COLORS = {
        {0xFFFFA24A, 0xFF8A3CF0},   // Solegram
        {0xFF6FD3FF, 0xFF5B6CFF},   // glass
        {0xFF5EE2A0, 0xFF1FA391},   // chats
        {0xFFFF8FA3, 0xFFE8506B},   // music, profile
        {0xFF4FC3F7, 0xFF3F51B5},   // plugins
        {0xFFB57BFF, 0xFF5E5CE6},   // protection
    };

    private final Paint tilePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rayPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path tilePath = new Path();
    private final Path sunPath = new Path();
    private final RectF rect = new RectF();
    private final OvershootInterpolator overshoot = new OvershootInterpolator(1.6f);

    private final long start = SystemClock.uptimeMillis();
    private float page;
    private int lastTop, lastBottom;
    private float lastSize;

    public SolegramIntroView(Context context) {
        super(context);
        markPaint.setColor(0xFFFFFFFF);
        rayPaint.setColor(0xFFFFFFFF);
        rayPaint.setStyle(Paint.Style.STROKE);
        rayPaint.setStrokeCap(Paint.Cap.ROUND);
    }

    /** The pager's position: the page and how far it is swiped towards the next one. */
    public void setPage(float page) {
        this.page = Math.max(0f, Math.min(COLORS.length - 1, page));
        invalidate();
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final float time = (SystemClock.uptimeMillis() - start) / 1000f;
        final float cx = getWidth() / 2f, cy = getHeight() / 2f;
        final float size = Math.min(dp(108), Math.min(getWidth(), getHeight()) * 0.74f);
        if (size <= 0) {
            return;
        }

        // colours of this moment
        final int index = (int) Math.floor(page);
        final int next = Math.min(COLORS.length - 1, index + 1);
        final float between = page - index;
        final int top = ColorUtils.blendARGB(COLORS[index][0], COLORS[next][0], between);
        final int bottom = ColorUtils.blendARGB(COLORS[index][1], COLORS[next][1], between);

        // appearance, breathing, the tilt after the finger
        final float appear = clamp01(time / 0.7f);
        final float breathe = 1f + 0.014f * (float) Math.sin(time * Math.PI / 2.2);
        final float swipe = between <= 0.5f ? between : between - 1f;        // -0.5..0.5 around the nearest page
        final float scale = (0.6f + 0.4f * overshoot.getInterpolation(appear)) * breathe * (1f - 0.06f * Math.abs(swipe) * 2f);
        final float lift = (float) Math.sin(time * Math.PI / 3.1) * dp(2.5f);

        // the glow of the tile's colour under it
        final float glowR = size * 1.05f;
        glowPaint.setShader(new RadialGradient(cx, cy + size * 0.12f, glowR,
            new int[]{ColorUtils.setAlphaComponent(bottom, (int) (86 * appear)), ColorUtils.setAlphaComponent(top, (int) (30 * appear)), ColorUtils.setAlphaComponent(top, 0)},
            new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy + size * 0.12f, glowR, glowPaint);

        canvas.save();
        canvas.translate(cx, cy + lift);
        canvas.rotate(-16f * swipe);
        canvas.scale(scale, scale);
        canvas.translate(-size / 2f, -size / 2f);

        // the tile
        if (lastTop != top || lastBottom != bottom || lastSize != size) {
            tilePaint.setShader(new LinearGradient(0, 0, size, size, top, bottom, Shader.TileMode.CLAMP));
            shinePaint.setShader(new LinearGradient(0, 0, 0, size * 0.55f, 0x38FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            lastTop = top;
            lastBottom = bottom;
            lastSize = size;
            tilePath.rewind();
            rect.set(0, 0, size, size);
            tilePath.addRoundRect(rect, size * 0.27f, size * 0.27f, Path.Direction.CW);
        }
        tilePaint.setAlpha((int) (255 * appear));
        canvas.drawPath(tilePath, tilePaint);
        canvas.drawPath(tilePath, shinePaint);

        // the mark, in the 108 units of the app icon
        canvas.save();
        canvas.clipPath(tilePath);
        final float u = size / 108f;
        canvas.scale(u, u);
        final float markScale = 1.28f;
        canvas.scale(markScale, markScale, 54f, 54f);

        // the two lines draw themselves from the middle
        final float line1 = CubicBezierInterpolator.EASE_OUT_QUINT.getInterpolation(clamp01((time - 0.25f) / 0.6f));
        final float line2 = CubicBezierInterpolator.EASE_OUT_QUINT.getInterpolation(clamp01((time - 0.4f) / 0.6f));
        markPaint.setAlpha(255);
        drawLine(canvas, 54f, 61.44f, 15.655f * line1, 2.325f);
        drawLine(canvas, 54f, 68.88f, 8.835f * line2, 2.325f);

        // the sun rises from behind the upper line; it sets a little while the page is being swiped
        final float rise = CubicBezierInterpolator.EASE_OUT_QUINT.getInterpolation(clamp01((time - 0.45f) / 0.9f));
        final float set = (float) Math.sin(Math.PI * clamp01(Math.abs(swipe) * 2f));
        final float down = (1f - rise) * 22f + set * 9f;
        canvas.save();
        canvas.clipRect(0f, 0f, 108f, 56.9f);
        sunPath.rewind();
        rect.set(54f - 18.6f, 55.395f - 18.6f + down, 54f + 18.6f, 55.395f + 18.6f + down);
        sunPath.addArc(rect, 180f, 180f);
        sunPath.lineTo(54f + 18.6f, 56.9f + down);
        sunPath.lineTo(54f - 18.6f, 56.9f + down);
        sunPath.close();
        markPaint.setAlpha(0xF2);
        canvas.drawPath(sunPath, markPaint);
        canvas.restore();

        // rays open above the risen sun and breathe
        final float rays = clamp01((time - 1.15f) / 0.5f) * (1f - set);
        if (rays > 0f) {
            rayPaint.setStrokeWidth(1.7f);
            for (int i = 0; i < 7; i++) {
                final double angle = Math.PI + Math.PI * (i + 0.5f) / 7f;
                final float pulse = 0.5f + 0.5f * (float) Math.sin(time * 2.2f + i * 0.9f);
                final float from = 22.6f, to = from + (2.2f + 1.8f * pulse) * rays;
                rayPaint.setAlpha((int) (255 * rays * (0.42f + 0.38f * pulse)));
                canvas.drawLine(54f + (float) Math.cos(angle) * from, 55.4f + (float) Math.sin(angle) * from,
                    54f + (float) Math.cos(angle) * to, 55.4f + (float) Math.sin(angle) * to, rayPaint);
            }
        }
        canvas.restore();
        canvas.restore();

        if (isAttachedToWindow()) {
            postInvalidateOnAnimation();
        }
    }

    private void drawLine(Canvas canvas, float cx, float cy, float half, float r) {
        if (half <= 0.05f) {
            return;
        }
        rect.set(cx - half, cy - r, cx + half, cy + r);
        canvas.drawRoundRect(rect, r, r, markPaint);
    }
}
