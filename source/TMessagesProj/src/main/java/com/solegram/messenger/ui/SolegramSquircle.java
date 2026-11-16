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

    /** The corners' superellipse exponent (2 is a circle). */
    public static final float EXPONENT = 2.5f;
    /** A superellipse corner looks smaller than a circle of the same radius: it is this much larger to look the same. */
    private static final float RADIUS_SCALE = 1.2f;

    private SolegramSquircle() {
    }

    public static boolean enabled() {
        return SolegramConfig.glassSquircle;
    }

    /** The shape of a rect of the given size and corner radius, in pixels. */
    public static final class Shape {
        /** Half the size of the base (before the sides are puffed out). */
        public float a0, h0;
        /** The base's corner radius. */
        public float radius;
        /** How much the top and bottom (beta) and the left and right (gamma) are puffed out, relative to the base. */
        public float beta, gamma;

        public boolean isEmpty() {
            return a0 <= 0 || h0 <= 0;
        }

        public void set(float width, float height, float cornerRadius) {
            final float a = width / 2f, h = height / 2f;
            if (a <= 0 || h <= 0) {
                a0 = h0 = radius = beta = gamma = 0;
                return;
            }
            // the longer a side, the more it is puffed out, but never more than a hair (2.2dp) or a bit of the other side
            final float max = AndroidUtilities.dpf2(2.2f);
            final float bulgeY = Math.min(max, Math.min(width * 0.01f, height * 0.05f));
            final float bulgeX = Math.min(max, Math.min(height * 0.01f, width * 0.05f));
            a0 = a - bulgeX;
            h0 = h - bulgeY;
            beta = bulgeY / h0;
            gamma = bulgeX / a0;
            radius = Math.max(0, Math.min(cornerRadius * RADIUS_SCALE, Math.min(a0, h0)));
        }
    }

    private static final int MAX_STEPS = 24;
    private static final float[][] CORNERS = new float[MAX_STEPS + 1][];

    /** A quarter of the superellipse in {@code steps} steps: cos(t)^(2/n), sin(t)^(2/n) pairs, from the side to the top. */
    private static float[] corner(int steps) {
        float[] c = CORNERS[steps];
        if (c == null) {
            c = new float[(steps + 1) * 2];
            final double e = 2.0 / EXPONENT;
            for (int i = 0; i <= steps; i++) {
                final double t = Math.PI / 2 * i / steps;
                c[i * 2] = (float) Math.pow(Math.max(0, Math.cos(t)), e);
                c[i * 2 + 1] = (float) Math.pow(Math.max(0, Math.sin(t)), e);
            }
            CORNERS[steps] = c;
        }
        return c;
    }

    /** Adds the shape of the rect (left, top, right, bottom) with the corner radius to the path, clockwise. */
    public static void addTo(Path path, float left, float top, float right, float bottom, float cornerRadius, Shape shape) {
        shape.set(right - left, bottom - top, cornerRadius);
        if (shape.isEmpty()) {
            return;
        }
        final float cx = (left + right) / 2f, cy = (top + bottom) / 2f;
        final float r = shape.radius;
        final int steps = Math.max(6, Math.min(MAX_STEPS, Math.round(r / AndroidUtilities.dpf2(1.5f))));
        final float[] c = corner(steps);
        final float ix = shape.a0 - r, iy = shape.h0 - r;
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
                // a vertical side, from the other end to this corner's start
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
                final float x = sx * (ix + r * c[k * 2]);
                final float y = sy * (iy + r * c[k * 2 + 1]);
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
