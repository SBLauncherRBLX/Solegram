/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.ui;

import static org.telegram.messenger.AndroidUtilities.dpf2;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.BlendMode;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.os.Build;

import com.solegram.messenger.SolegramGlass;

/**
 * The surface of Solegram's glass, after the glass of iOS (as Telegram for iOS builds it for itself:
 * GlassBackgroundComponent): over the refracted, lightly blurred picture
 * <ul>
 * <li>a faint body: lighter towards the top, a touch darker at the bottom, and a fine grain, so the glass reads as
 * a material with some thickness and not as a flat tint;</li>
 * <li>the edge: a 0.8dp line that is strong at the top left and at the bottom right and nearly gone at the other
 * two corners (light from the top left and its reflection), drawn so that it takes its colour from what is under
 * and next to it, as an edge of real glass does, instead of being always white.</li>
 * </ul>
 */
public final class SolegramGlassLook {

    private static final Paint bodyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint grainPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private static final Paint edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Path path = new Path();
    private static final RectF rect = new RectF();
    private static final float[] inset = new float[8];
    private static BitmapShader grain;

    static {
        edgePaint.setStyle(Paint.Style.STROKE);
    }

    private SolegramGlassLook() {
    }

    private static BitmapShader grain() {
        if (grain == null) {
            // a fixed fine noise: light and dark specks, each faint
            final int n = 96;
            final int[] px = new int[n * n];
            long s = 0x5DEECE66DL;
            for (int i = 0; i < px.length; i++) {
                s = s * 6364136223846793005L + 1442695040888963407L;
                final int v = (int) ((s >>> 33) & 0xFF);
                final int a = Math.abs(v - 128) * 2 * 40 / 255;      // up to ~16% here; the paint scales it down
                px[i] = (a << 24) | (v > 128 ? 0xFFFFFF : 0x000000);
            }
            final Bitmap b = Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888);
            grain = new BitmapShader(b, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
            // a speck is about two thirds of a dp, so the grain looks the same on every screen
            final android.graphics.Matrix matrix = new android.graphics.Matrix();
            final float k = Math.max(1f, org.telegram.messenger.AndroidUtilities.density / 1.5f);
            matrix.setScale(k, k);
            grain.setLocalMatrix(matrix);
        }
        return grain;
    }

    /**
     * The body and the edge of a piece of glass that fills (0, 0, width, height) of the canvas.
     *
     * @param radii  the corner radii as for Path.addRoundRect
     * @param dark   dark glass: its lights are quieter
     * @param edge   whether the piece has an outline at all (floating glass has; bars across the screen do not)
     * @param liquid the liquid glass shader has drawn its own lit, coloured rim already: the line here only firms it up
     */
    public static void draw(Canvas canvas, float width, float height, float[] radii, boolean dark, boolean edge, boolean liquid) {
        if (width <= 0 || height <= 0) {
            return;
        }
        rect.set(0, 0, width, height);
        path.rewind();
        path.addRoundRect(rect, radii, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(path);

        // body: light gathers at the top, a little shade at the bottom
        final int top = (int) (255 * (dark ? 0.05f : 0.09f));
        final int bottom = (int) (255 * (dark ? 0.06f : 0.03f));
        bodyPaint.setShader(new LinearGradient(0, 0, 0, height,
            new int[]{(top << 24) | 0xFFFFFF, 0x00FFFFFF, 0x00000000, bottom << 24},
            new float[]{0f, 0.42f, 0.62f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRect(rect, bodyPaint);
        bodyPaint.setShader(null);

        // grain
        grainPaint.setShader(grain());
        grainPaint.setAlpha((int) (255 * (dark ? 0.24f : 0.30f)));
        canvas.drawRect(rect, grainPaint);
        canvas.restore();

        if (!edge) {
            return;
        }
        // The edge: brightest at the top left corner, a reflection of it at the bottom right, nearly gone at the
        // other two - a sweep around the centre with its stops on the real corners, so a long pill is lit the same
        // way as a round button.
        final float w = Math.max(1f, dpf2(0.8f) * (SolegramGlass.clear() ? 1.5f : 1f));
        rect.inset(w / 2f, w / 2f);
        for (int i = 0; i < 8; i++) {
            inset[i] = Math.max(0f, radii[i] - w / 2f);
        }
        path.rewind();
        path.addRoundRect(rect, inset, Path.Direction.CW);
        edgePaint.setStrokeWidth(w);
        final float base = dark ? 0.46f : 0.66f;
        final int bright = (int) (255 * base), reflex = (int) (255 * base * 0.74f), dim = (int) (255 * base * 0.3f);
        final int side = (reflex + dim) / 2;
        final float corner = (float) (Math.atan2(height, width) / (2 * Math.PI));     // 0 = 3 o'clock, clockwise
        edgePaint.setShader(new SweepGradient(width / 2f, height / 2f,
            new int[]{(side << 24) | 0xFFFFFF, (reflex << 24) | 0xFFFFFF, (dim << 24) | 0xFFFFFF, (bright << 24) | 0xFFFFFF, (dim << 24) | 0xFFFFFF, (side << 24) | 0xFFFFFF},
            new float[]{0f, corner, 0.5f - corner, 0.5f + corner, 1f - corner, 1f}));
        if (Build.VERSION.SDK_INT >= 29) {
            // overlay: the line lightens what is under it, channel by channel, so it comes out in that colour
            edgePaint.setBlendMode(BlendMode.OVERLAY);
            edgePaint.setAlpha(255);
            canvas.drawPath(path, edgePaint);
            edgePaint.setBlendMode(null);
            // and a plain share, so the edge shows on black and on white too
            edgePaint.setAlpha(liquid ? (dark ? 54 : 46) : (dark ? 128 : 112));
        } else {
            edgePaint.setAlpha(255);
        }
        canvas.drawPath(path, edgePaint);
        edgePaint.setShader(null);
    }
}
