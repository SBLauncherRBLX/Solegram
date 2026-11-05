/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.ui;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;

/**
 * The Music tab's icon, in the style of the other tabs: two beamed notes with hollow heads; when the tab is
 * selected the heads fill from their middle. On opening the notes sway on a damped spring with a light squash on
 * the beat, and two thin sound waves ring out at the top right. Drawn in a 24-unit box, in the tab's colour.
 */
public class SolegramMusicTabIcon extends Drawable {

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path beam = new Path();
    private final RectF oval = new RectF();
    private final int size;
    private int alpha = 255;
    private boolean selected;
    /** 0..1 while opening; -1 at rest. */
    private float t = -1f;

    private static final float HEAD_RX = 3.05f, HEAD_RY = 2.35f, HEAD_STROKE = 1.8f, STEM_W = 1.9f, BEAM_T = 2.6f;
    private static final float EDGE_X = 2.962f, EDGE_Y = 0.443f;
    private static final float HEAD1_X = 7.1f, HEAD1_Y = 17.6f, HEAD2_X = 16.0f, HEAD2_Y = 15.4f;
    private static final float STEM1 = HEAD1_X + EDGE_X + HEAD_STROKE / 2f - STEM_W / 2f;
    private static final float STEM2 = HEAD2_X + EDGE_X + HEAD_STROKE / 2f - STEM_W / 2f;
    private static final float BEAM1 = 6.6f, BEAM2 = BEAM1 - (HEAD1_Y - HEAD2_Y);

    public SolegramMusicTabIcon(int sizeDp) {
        this.size = AndroidUtilities.dp(sizeDp);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        // the stems sit flush on the heads' right edges (a rotated ellipse reaches EDGE_X to the right, at EDGE_Y);
        // the beam spans exactly the two stems, so nothing sticks out
        beam.moveTo(STEM1 - STEM_W / 2f, BEAM1);
        beam.lineTo(STEM2 + STEM_W / 2f, BEAM2);
        beam.lineTo(STEM2 + STEM_W / 2f, BEAM2 + BEAM_T);
        beam.lineTo(STEM1 - STEM_W / 2f, BEAM1 + BEAM_T);
        beam.close();
    }

    public void setSelectedState(boolean selected) {
        if (this.selected != selected) {
            this.selected = selected;
            if (!selected) {
                t = -1f;
            }
            invalidateSelf();
        }
    }

    /** One frame of the opening (0..1); 1 ends it. */
    public void setProgress(float t) {
        this.t = t >= 1f ? -1f : t;
        invalidateSelf();
    }

    private static float clamp(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    /** A damped sway: about -12°, +6°, -2°, back to 0. */
    private static float sway(float t) {
        if (t <= 0f || t >= 1f) {
            return 0f;
        }
        return (float) (-13 * Math.exp(-4.2 * t) * Math.sin(t * Math.PI * 3.2));
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        final Rect b = getBounds();
        final float k = Math.min(b.width(), b.height()) / 24f;
        final float ox = b.left + (b.width() - 24 * k) / 2f, oy = b.top + (b.height() - 24 * k) / 2f;
        final boolean playing = t >= 0f;
        final float fillAmount = selected ? (playing ? 1f - (float) Math.pow(1f - clamp(t / 0.38f), 3) : 1f) : 0f;
        final float rot = playing ? sway(t) : 0f;
        final float squash = playing ? (float) (Math.exp(-6 * t) * Math.sin(t * Math.PI * 4) * 0.06) : 0f;
        fill.setAlpha(alpha);
        stroke.setAlpha(alpha);

        canvas.save();
        canvas.translate(ox, oy);
        canvas.scale(k, k);
        canvas.translate(12f, 19.5f);
        canvas.rotate(rot);
        canvas.scale(1f + squash, 1f - squash);
        canvas.translate(-12f, -19.5f);

        canvas.drawPath(beam, fill);
        // the stems, from inside the beam to the heads' edges
        stroke.setStrokeCap(Paint.Cap.BUTT);
        stroke.setStrokeWidth(STEM_W);
        canvas.drawLine(STEM1, BEAM1 + BEAM_T * 0.5f, STEM1, HEAD1_Y + EDGE_Y, stroke);
        canvas.drawLine(STEM2, BEAM2 + BEAM_T * 0.5f, STEM2, HEAD2_Y + EDGE_Y, stroke);
        // the heads: hollow, filled from their middle when selected
        drawHead(canvas, HEAD1_X, HEAD1_Y, fillAmount);
        drawHead(canvas, HEAD2_X, HEAD2_Y, fillAmount);
        canvas.restore();

        // two thin sound waves ring out at the top right while it opens
        if (playing && selected) {
            canvas.save();
            canvas.translate(ox, oy);
            canvas.scale(k, k);
            stroke.setStrokeCap(Paint.Cap.ROUND);
            stroke.setStrokeWidth(1.4f);
            for (int i = 0; i < 2; i++) {
                final float p = clamp((t - 0.28f - i * 0.14f) / 0.5f);
                if (p <= 0f || p >= 1f) {
                    continue;
                }
                final float r = 1.6f + 2.6f * p;
                stroke.setAlpha((int) (alpha * Math.sin(Math.PI * p) * 0.9f));
                oval.set(20.4f - r, 3.2f - r, 20.4f + r, 3.2f + r);
                canvas.drawArc(oval, -70, 90, false, stroke);
            }
            stroke.setAlpha(alpha);
            canvas.restore();
        }
    }

    private void drawHead(Canvas canvas, float cx, float cy, float fillAmount) {
        canvas.save();
        canvas.rotate(-22f, cx, cy);
        stroke.setStrokeWidth(HEAD_STROKE);
        oval.set(cx - HEAD_RX, cy - HEAD_RY, cx + HEAD_RX, cy + HEAD_RY);
        canvas.drawOval(oval, stroke);
        if (fillAmount > 0f) {
            oval.set(cx - HEAD_RX * fillAmount, cy - HEAD_RY * fillAmount, cx + HEAD_RX * fillAmount, cy + HEAD_RY * fillAmount);
            canvas.drawOval(oval, fill);
        }
        canvas.restore();
    }

    @Override
    public void setAlpha(int alpha) {
        this.alpha = alpha;
        invalidateSelf();
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        fill.setColorFilter(colorFilter);
        stroke.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    @Override
    public int getIntrinsicWidth() {
        return size;
    }

    @Override
    public int getIntrinsicHeight() {
        return size;
    }
}
