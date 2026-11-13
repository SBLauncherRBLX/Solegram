/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.solegram.messenger.SolegramBadges;

/**
 * A Solegram badge next to a name. The developer: an orange scalloped badge with the Solegram mark (the half sun over
 * two lines) in white inside. The official channel: the same orange scalloped badge with a white tick, the way a
 * verified badge looks. A tester: the Solegram mark in violet.
 */
public class SolegramBadgeDrawable extends Drawable {

    private static Path markPath;
    private static Path cookiePath;
    private static Path tickPath;

    private final int role;
    private final int size;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Path inner = new Path();
    private final Matrix matrix = new Matrix();
    private int alpha = 255;
    private int lastW = -1, lastH = -1;

    public SolegramBadgeDrawable(int role, int sizePx) {
        this.role = role;
        this.size = sizePx;
    }

    /** The badge for a user or channel, or null when they have none. */
    @Nullable
    public static Drawable forDialog(long dialogId, int sizePx) {
        final int role = SolegramBadges.roleOf(dialogId);
        return role == SolegramBadges.NONE ? null : new SolegramBadgeDrawable(role, sizePx);
    }

    public int getRole() {
        return role;
    }

    public static int colorOf(int role) {
        return role == SolegramBadges.TESTER ? SolegramBadges.COLOR_VIOLET : SolegramBadges.COLOR_ORANGE;
    }

    /** The mark in a 1 x 1 box (the half sun over two lines, from the app's own vector). */
    static Path mark() {
        if (markPath == null) {
            final Path p = new Path();
            // the dome: a half circle on a base with rounded corners
            p.addArc(new RectF(35.4f, 36.795f, 72.6f, 73.995f), 180, 180);
            p.lineTo(72.6f, 55.395f);
            p.quadTo(72.6f, 56.635f, 71.36f, 56.635f);
            p.lineTo(36.64f, 56.635f);
            p.quadTo(35.4f, 56.635f, 35.4f, 55.395f);
            p.close();
            p.addRoundRect(new RectF(36.02f, 59.115f, 71.98f, 63.765f), 2.325f, 2.325f, Path.Direction.CW);
            p.addRoundRect(new RectF(42.84f, 66.555f, 65.16f, 71.205f), 2.325f, 2.325f, Path.Direction.CW);
            // into a unit box, centred, keeping the proportions
            final float w = 72.6f - 35.4f, h = 71.205f - 36.795f, s = 1f / Math.max(w, h);
            final Matrix m = new Matrix();
            m.setTranslate(-35.4f, -36.795f);
            m.postScale(s, s);
            m.postTranslate((1f - w * s) / 2f, (1f - h * s) / 2f);
            p.transform(m);
            markPath = p;
        }
        return markPath;
    }

    /** A tick, as a filled shape, in a 1 x 1 box. */
    static Path tick() {
        if (tickPath == null) {
            final Path p = new Path();
            // stroked at a size of 100 (the stroker is coarse on tiny paths), then brought to the unit box
            final float ax = 16f, ay = 52f, cx = 40f, cy = 76f, bx = 86f, by = 28f;
            final Path stroke = new Path();
            stroke.moveTo(ax, ay);
            stroke.lineTo(cx, cy);
            stroke.lineTo(bx, by);
            final Paint sp = new Paint();
            sp.setStyle(Paint.Style.STROKE);
            sp.setStrokeWidth(15f);
            sp.setStrokeCap(Paint.Cap.ROUND);
            sp.setStrokeJoin(Paint.Join.ROUND);
            sp.getFillPath(stroke, p);
            final Matrix m = new Matrix();
            m.setScale(0.01f, 0.01f);
            p.transform(m);
            tickPath = p;
        }
        return tickPath;
    }

    /** The scalloped badge: a circle with nine soft waves round its edge, in a 1 x 1 box. */
    static Path cookie() {
        if (cookiePath == null) {
            final Path p = new Path();
            final int n = 180;
            for (int i = 0; i <= n; i++) {
                final double a = Math.PI * 2 * i / n - Math.PI / 2;
                final double r = 0.5 * (0.92 + 0.08 * Math.cos(9 * (a + Math.PI / 2)));
                final float x = (float) (0.5 + Math.cos(a) * r), y = (float) (0.5 + Math.sin(a) * r);
                if (i == 0) {
                    p.moveTo(x, y);
                } else {
                    p.lineTo(x, y);
                }
            }
            p.close();
            cookiePath = p;
        }
        return cookiePath;
    }

    private void layout(Rect b) {
        if (b.width() == lastW && b.height() == lastH) {
            return;
        }
        lastW = b.width();
        lastH = b.height();
        final float s = Math.min(b.width(), b.height());
        if (role == SolegramBadges.OFFICIAL) {
            matrix.setScale(s, s);
            cookie().transform(matrix, path);
            final float m = s * 0.52f;
            matrix.setScale(m, m);
            matrix.postTranslate((s - m) / 2f, (s - m) / 2f);
            tick().transform(matrix, inner);
        } else if (role == SolegramBadges.DEVELOPER) {
            matrix.setScale(s, s);
            cookie().transform(matrix, path);
            final float m = s * 0.56f;
            matrix.setScale(m, m);
            matrix.postTranslate((s - m) / 2f, (s - m) / 2f + s * 0.01f);
            mark().transform(matrix, inner);
        } else {
            final float m = s * 0.92f;
            matrix.setScale(m, m);
            matrix.postTranslate((s - m) / 2f, (s - m) / 2f);
            mark().transform(matrix, path);
        }
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        final Rect b = getBounds();
        if (b.isEmpty()) {
            return;
        }
        layout(b);
        final float s = Math.min(b.width(), b.height());
        canvas.save();
        canvas.translate(b.left + (b.width() - s) / 2f, b.top + (b.height() - s) / 2f);
        paint.setColor(colorOf(role));
        paint.setAlpha(alpha);
        canvas.drawPath(path, paint);
        if (role == SolegramBadges.OFFICIAL || role == SolegramBadges.DEVELOPER) {
            paint.setColor(0xFFFFFFFF);
            paint.setAlpha(alpha);
            canvas.drawPath(inner, paint);
        }
        canvas.restore();
    }

    @Override
    public int getIntrinsicWidth() {
        return size;
    }

    @Override
    public int getIntrinsicHeight() {
        return size;
    }

    @Override
    public void setAlpha(int alpha) {
        this.alpha = alpha;
        invalidateSelf();
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    /** The default size next to a name in a list. */
    public static int listSize() {
        return dp(17);
    }
}
