package com.solegram.messenger.ui;

import android.graphics.Path;

import com.solegram.messenger.SolegramConfig;

import org.telegram.messenger.AndroidUtilities;

/**
 * Solegram: the shape of floating glass (pills, buttons, menus) as on iOS - corners that run smoothly into the
 * sides (a superellipse, no sudden start of the curve) and sides that are slightly puffed out instead of straight.
 * <p>
 * The shape is a rect with superellipse corners ("base", a little smaller than the bounds), stretched so that the
 * middle of each side reaches the bounds: a point (x, y) of the base goes to
 * (x * (1 + gamma * (1 - (y / h0)^2)), y * (1 + beta * (1 - (x / a0)^2))). The liquid glass shader
 * (solegram_glass_shader.agsl, sdfShape) undoes the same stretch, so the lens and the outline are one shape.
 */
public final class SolegramSquircle {

    /**
     * Pills: the ends are not half circles but stretched along the length (the curve starts earlier on the long sides
     * and runs smoothly into them), as iOS draws its capsules.
     */
    public static final float PILL_STRETCH = 1.12f;
    public static final float PILL_EXPONENT = 2.1f;
    /** Other shapes: corners that run a little more smoothly into the sides than a circle's quarter. */
    public static final float CORNER_EXPONENT = 2.1f;
    /** A superellipse corner looks smaller than a circle of the same radius: it is this much larger to look the same. */
    private static final float RADIUS_SCALE = 1.15f;

    private SolegramSquircle() {
    }

    public static boolean enabled() {
        return SolegramConfig.glassSquircle;
    }

    /** The shape of a rect of the given size and corner radius, in pixels. */
    public static final class Shape {
        /** Half the size of the base (before the sides are puffed out). */
        public float a0, h0;
        /** The corners' radii along x and along y. */
        public float rx, ry;
        /** The smaller of the two radii. */
        public float radius;
        /** How much the top and bottom (beta) and the left and right (gamma) are puffed out, relative to the base. */
        public float beta, gamma;
        /** The corners' superellipse exponent (2: an ellipse's quarter). */
        public float exponent = 2;

        public boolean isEmpty() {
            return a0 <= 0 || h0 <= 0;
        }

        public void set(float width, float height, float cornerRadius) {
            final float a = width / 2f, h = height / 2f;
            if (a <= 0 || h <= 0) {
                a0 = h0 = rx = ry = radius = beta = gamma = 0;
                return;
            }
            // the sides are just a little puffed out (more read as a bent shape on a long element)
            final float max = AndroidUtilities.dpf2(0.7f);
            final float bulgeY = Math.min(max, Math.min(width * 0.004f, height * 0.015f));
            final float bulgeX = Math.min(max, Math.min(height * 0.004f, width * 0.015f));
            a0 = a - bulgeX;
            h0 = h - bulgeY;
            beta = bulgeY / h0;
            gamma = bulgeX / a0;
            final float half = Math.min(a0, h0);
            if (cornerRadius >= Math.min(a, h) - 0.5f || cornerRadius * RADIUS_SCALE >= half) {
                // a pill: the ends stretched along its length
                // a short pill keeps round ends (a capsule's): the stretch and the fuller shoulders come with its length
                final float ratio = Math.max(a0, h0) / half;
                final float k = Math.max(0f, Math.min(1f, (ratio - 1.3f) / 1.7f));
                final float along = Math.min(half * (1f + (PILL_STRETCH - 1f) * k), Math.max(a0, h0));
                if (a0 >= h0) {
                    rx = along;
                    ry = h0;
                } else {
                    rx = a0;
                    ry = along;
                }
                exponent = 2f + (PILL_EXPONENT - 2f) * k;
            } else {
                rx = ry = Math.max(0, cornerRadius * RADIUS_SCALE);
                exponent = CORNER_EXPONENT;
            }
            radius = Math.min(rx, ry);
        }
    }

    private static final int MAX_STEPS = 32;

    /** Adds the shape of the rect (left, top, right, bottom) with the corner radius to the path, clockwise. */
    public static void addTo(Path path, float left, float top, float right, float bottom, float cornerRadius, Shape shape) {
        shape.set(right - left, bottom - top, cornerRadius);
        if (shape.isEmpty()) {
            return;
        }
        final float cx = (left + right) / 2f, cy = (top + bottom) / 2f;
        final float rx = shape.rx, ry = shape.ry;
        final int steps = Math.max(6, Math.min(MAX_STEPS, Math.round(Math.max(rx, ry) / AndroidUtilities.dpf2(1.5f))));
        final float[] c = new float[(steps + 1) * 2];
        // a quarter of the superellipse: cos(t)^(2/n), sin(t)^(2/n), from the vertical side to the horizontal one
        final double e = 2.0 / shape.exponent;
        for (int i = 0; i <= steps; i++) {
            final double t = Math.PI / 2 * i / steps;
            c[i * 2] = (float) Math.pow(Math.max(0, Math.cos(t)), e);
            c[i * 2 + 1] = (float) Math.pow(Math.max(0, Math.sin(t)), e);
        }
        final float ix = shape.a0 - rx, iy = shape.h0 - ry;
        // the straight parts of the base become arcs once puffed out: a few points along them
        final int sideStepsX = shape.beta > 0 ? Math.max(1, Math.min(16, Math.round(ix * 2 / AndroidUtilities.dpf2(12)))) : 1;
        final int sideStepsY = shape.gamma > 0 ? Math.max(1, Math.min(16, Math.round(iy * 2 / AndroidUtilities.dpf2(12)))) : 1;
        boolean first = true;
        // quadrants clockwise from the right side: bottom right, bottom left, top left, top right (y down)
        for (int q = 0; q < 4; q++) {
            final float sx = q == 0 || q == 3 ? 1 : -1;
            final float sy = q == 0 || q == 1 ? 1 : -1;
            // the side before this corner
            if (q == 0 || q == 2) {
                for (int i = 0; i < sideStepsY; i++) {
                    final float y = -sy * iy + 2 * sy * iy * i / sideStepsY;
                    first = point(path, first, cx, cy, sx * shape.a0, y, shape);
                }
            } else {
                for (int i = 0; i < sideStepsX; i++) {
                    final float x = -sx * ix + 2 * sx * ix * i / sideStepsX;
                    first = point(path, first, cx, cy, x, sy * shape.h0, shape);
                }
            }
            // the corner: from the vertical side to the horizontal one (q 0, 2) or the other way round (q 1, 3)
            for (int i = 0; i <= steps; i++) {
                final int k = q == 0 || q == 2 ? i : steps - i;
                final float x = sx * (ix + rx * c[k * 2]);
                final float y = sy * (iy + ry * c[k * 2 + 1]);
                first = point(path, first, cx, cy, x, y, shape);
            }
        }
        path.close();
    }

    private static boolean point(Path path, boolean first, float cx, float cy, float x, float y, Shape shape) {
        final float tx = Math.min(1f, x * x / (shape.a0 * shape.a0));
        final float ty = Math.min(1f, y * y / (shape.h0 * shape.h0));
        final float px = cx + x * (1 + shape.gamma * (1 - ty));
        final float py = cy + y * (1 + shape.beta * (1 - tx));
        if (first) {
            path.moveTo(px, py);
        } else {
            path.lineTo(px, py);
        }
        return false;
    }
}
