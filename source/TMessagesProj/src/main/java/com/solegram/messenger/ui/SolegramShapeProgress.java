/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.ui;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.SystemClock;

import com.solegram.messenger.SolegramConfig;

import org.telegram.messenger.AndroidUtilities;

import java.util.ArrayList;

/**
 * Solegram: the "bud" loading indicator - Material 3 Expressive shapes (soft burst, cookies, pentagon, pill, sun,
 * oval) that turn into one another with a springy morph and a little kick of rotation.
 * <ul>
 *     <li>Without a percentage the whole shape is filled and a new shape comes every 0.78 s while it turns.</li>
 *     <li>With one, the shape is a soft track and the colour fills it as a sector from the top, clockwise; each
 *     step of the percentage brings the next shape.</li>
 * </ul>
 * Every shape is its radius at {@link #N} angles round its centre, so any two can be blended point by point.
 */
public final class SolegramShapeProgress {

    /** Whether the loading spinners draw as this indicator (Solegram settings, Appearance, Animations). */
    public static boolean enabled() {
        return SolegramConfig.shapeProgress;
    }

    static final int N = 120;
    private static final float TAU = (float) (Math.PI * 2);

    // region the shapes

    private static float[][] shapes;
    private static float[] circle;

    private static void ensureShapes() {
        if (shapes != null) {
            return;
        }
        circle = new float[N];
        java.util.Arrays.fill(circle, 1f);
        shapes = new float[][]{
            toRadii(star(10, 0.76f, 0.16f)),                                         // soft burst
            radial(a -> 1 + 0.07f * Math.cos(9 * a)),                                  // 9-sided cookie
            toRadii(ngon(5, 0.28f)),                                                   // pentagon
            toRadii(rotate(roundedPoly(new float[][]{{-1, -0.48f}, {1, -0.48f}, {1, 0.48f}, {-1, 0.48f}}, 0.48f, 20), -40)), // pill
            toRadii(star(8, 0.82f, 0.16f)),                                            // sunny
            radial(a -> 1 + 0.14f * Math.cos(4 * a + Math.PI / 4)),                    // 4-sided cookie
            toRadii(rotate(ellipse(0.58f), -40)),                                      // oval
        };
    }

    private interface Radius {
        double at(double a);
    }

    private static float[] radial(Radius f) {
        final float[] r = new float[N];
        float max = 0;
        for (int i = 0; i < N; i++) {
            r[i] = (float) f.at(i / (float) N * TAU);
            max = Math.max(max, r[i]);
        }
        for (int i = 0; i < N; i++) {
            r[i] /= max;
        }
        return r;
    }

    private static ArrayList<float[]> ellipse(float ry) {
        final ArrayList<float[]> p = new ArrayList<>();
        for (int i = 0; i < 360; i++) {
            final double a = i / 360.0 * TAU;
            p.add(new float[]{(float) Math.cos(a), (float) (Math.sin(a) * ry)});
        }
        return p;
    }

    /** A polygon with its corners rounded by quadratic arcs of radius {@code rad}. */
    private static ArrayList<float[]> roundedPoly(float[][] verts, float rad, int steps) {
        final ArrayList<float[]> pts = new ArrayList<>();
        final int n = verts.length;
        for (int i = 0; i < n; i++) {
            final float[] P = verts[(i - 1 + n) % n], V = verts[i], Q = verts[(i + 1) % n];
            final float d1 = (float) Math.hypot(P[0] - V[0], P[1] - V[1]), d2 = (float) Math.hypot(Q[0] - V[0], Q[1] - V[1]);
            final float d = Math.min(rad, Math.min(d1 / 2, d2 / 2));
            final float ax = V[0] + (P[0] - V[0]) / d1 * d, ay = V[1] + (P[1] - V[1]) / d1 * d;
            final float bx = V[0] + (Q[0] - V[0]) / d2 * d, by = V[1] + (Q[1] - V[1]) / d2 * d;
            for (int k = 0; k <= steps; k++) {
                final float t = k / (float) steps, u = 1 - t;
                pts.add(new float[]{u * u * ax + 2 * u * t * V[0] + t * t * bx, u * u * ay + 2 * u * t * V[1] + t * t * by});
            }
        }
        return pts;
    }

    private static ArrayList<float[]> star(int count, float inner, float rad) {
        final float[][] v = new float[count * 2][];
        final double rot = -Math.PI / 2;
        for (int i = 0; i < count * 2; i++) {
            final float r = i % 2 == 1 ? inner : 1;
            final double a = rot + i * Math.PI / count;
            v[i] = new float[]{(float) Math.cos(a) * r, (float) Math.sin(a) * r};
        }
        return roundedPoly(v, rad, 10);
    }

    private static ArrayList<float[]> ngon(int count, float rad) {
        final float[][] v = new float[count][];
        for (int i = 0; i < count; i++) {
            final double a = -Math.PI / 2 + i * TAU / count;
            v[i] = new float[]{(float) Math.cos(a), (float) Math.sin(a)};
        }
        return roundedPoly(v, rad, 14);
    }

    private static ArrayList<float[]> rotate(ArrayList<float[]> pts, float deg) {
        final double a = Math.toRadians(deg), c = Math.cos(a), s = Math.sin(a);
        for (float[] p : pts) {
            final float x = p[0], y = p[1];
            p[0] = (float) (x * c - y * s);
            p[1] = (float) (x * s + y * c);
        }
        return pts;
    }

    /** Polygon points → the radius at N angles round the area centre, scaled to fit the unit circle. */
    private static float[] toRadii(ArrayList<float[]> pts) {
        final int m = pts.size();
        double A = 0, cx = 0, cy = 0;
        for (int i = 0; i < m; i++) {
            final float[] p0 = pts.get(i), p1 = pts.get((i + 1) % m);
            final double c = p0[0] * p1[1] - p1[0] * p0[1];
            A += c;
            cx += (p0[0] + p1[0]) * c;
            cy += (p0[1] + p1[1]) * c;
        }
        A /= 2;
        cx /= 6 * A;
        cy /= 6 * A;
        final float[] r = new float[N];
        float max = 0;
        for (int i = 0; i < N; i++) {
            final double a = i / (double) N * TAU, dx = Math.cos(a), dy = Math.sin(a);
            double best = 0;
            for (int j = 0; j < m; j++) {
                final float[] q0 = pts.get(j), q1 = pts.get((j + 1) % m);
                final double x0 = q0[0] - cx, y0 = q0[1] - cy, ex = q1[0] - q0[0], ey = q1[1] - q0[1];
                final double den = dx * ey - dy * ex;
                if (Math.abs(den) < 1e-9) {
                    continue;
                }
                final double t = (x0 * ey - y0 * ex) / den, u = (x0 * dy - y0 * dx) / den;
                if (t > 0 && u >= -1e-6 && u <= 1 + 1e-6 && t > best) {
                    best = t;
                }
            }
            r[i] = (float) best;
            max = Math.max(max, r[i]);
        }
        for (int i = 0; i < N; i++) {
            r[i] /= max;
        }
        return r;
    }

    // endregion

    private final float[] cur = new float[N];
    private float[] from, to;
    private float morph = 1f;
    private float rot, rotFrom, rotTo;
    private float pop;
    private int index;
    private int bucket = -1;
    private float nextIndet;
    private float ind = 1f;
    private boolean indeterminate = true;
    private float progress;
    private long lastTime;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Path sector = new Path();

    public SolegramShapeProgress() {
        ensureShapes();
        System.arraycopy(shapes[0], 0, cur, 0, N);
        from = shapes[0];
        to = shapes[0];
        fill.setStyle(Paint.Style.FILL);
    }

    public void setIndeterminate(boolean value) {
        if (indeterminate != value) {
            indeterminate = value;
            bucket = -1;
            nextIndet = 0;
        }
    }

    /** 0..1; the caller animates it, the indicator draws it as it is. */
    public void setProgress(float value) {
        progress = Math.max(0, Math.min(1, value));
    }

    private void morphTo(float[] radii, float dir) {
        from = cur.clone();
        to = radii;
        morph = 0;
        rotFrom = rot;
        rotTo = rot + (float) (Math.PI / 2.4) * dir;
        pop = 1;
    }

    /** A spring that overshoots a little and settles: Material 3 Expressive's spatial spring, roughly. */
    private static float spring(float t) {
        if (t >= 1) {
            return 1;
        }
        final double z = 0.42, w = 20;
        return (float) (1 - Math.exp(-w * z * t) * Math.cos(w * Math.sqrt(1 - z * z) * t));
    }

    private void step(float dt) {
        ind += ((indeterminate ? 1 : 0) - ind) * Math.min(1, dt * 5);
        if (indeterminate) {
            nextIndet -= dt;
            if (nextIndet <= 0) {
                nextIndet = 0.78f;
                index = (index + 1) % shapes.length;
                morphTo(shapes[index], 1);
            }
        } else {
            final int b = Math.min(shapes.length - 1, (int) Math.floor(progress * shapes.length));
            if (b != bucket) {
                final float dir = bucket < 0 || b > bucket ? 1 : -1;
                bucket = b;
                index = b;
                morphTo(shapes[b], dir);
            }
        }
        morph = Math.min(1, morph + dt / 0.62f);
        final float s = spring(morph);
        for (int i = 0; i < N; i++) {
            cur[i] = from[i] + (to[i] - from[i]) * s;
        }
        // a slow turn all the time, quicker without a percentage, plus the kick of each change
        final float drift = dt * (0.22f + 0.9f * ind);
        rotFrom += drift;
        rotTo += drift;
        rot = rotFrom + (rotTo - rotFrom) * s;
        pop = Math.max(0, pop - dt * 2.4f);
    }

    /** 1: the shape as it is; towards 0 it rounds into a circle (a button easing back after its download). */
    private float amount = 1f;

    private void shapePath(float cx, float cy, float R) {
        path.rewind();
        for (int i = 0; i < N; i++) {
            final double a = i / (double) N * TAU + rot;
            final float r = R * (1 + (cur[i] - 1) * amount);
            final float x = cx + (float) Math.cos(a) * r, y = cy + (float) Math.sin(a) * r;
            if (i == 0) {
                path.moveTo(x, y);
            } else {
                path.lineTo(x, y);
            }
        }
        path.close();
    }

    /**
     * Draws the indicator into a {@code size} × {@code size} square round ({@code cx}, {@code cy}), in {@code color}
     * (its alpha is kept). Always wants the next frame: the caller invalidates.
     */
    public void draw(Canvas canvas, float cx, float cy, float size, int color) {
        amount = 1f;
        tick();
        final float scale = 1 + 0.06f * (float) Math.sin(pop * Math.PI);
        final float R = size / 2f * 0.92f * scale;
        final int alpha = color >>> 24;
        shapePath(cx, cy, R);

        if (ind < 0.999f) {
            // the track: the shape itself, soft
            fill.setColor(color);
            fill.setAlpha((int) (alpha * 0.22f * (1 - ind)));
            canvas.drawPath(path, fill);
        }
        fill.setColor(color);
        fill.setAlpha(alpha);
        drawSector(canvas, cx, cy, R);
    }

    /**
     * A file's round download button as the indicator: the button itself takes the shapes ({@code trackColor}), the
     * part loaded is a sector of {@code sectorColor} over it, the button's icon goes on top. {@code shapeAmount}
     * 0..1 rounds it back into the circle as the download ends.
     */
    public void drawButton(Canvas canvas, float cx, float cy, float radius, int trackColor, int sectorColor, float shapeAmount) {
        drawButton(canvas, cx, cy, radius, trackColor, sectorColor, shapeAmount, true);
    }

    /** The current shape alone, with {@code paint} (a cut-out round a badge, say); {@code advance}: step the animation. */
    public void drawShape(Canvas canvas, float cx, float cy, float radius, Paint paint, float shapeAmount, boolean advance) {
        amount = Math.max(0, Math.min(1, shapeAmount));
        if (advance) {
            tick();
        }
        shapePath(cx, cy, radius * (1 + 0.05f * (float) Math.sin(pop * Math.PI) * amount));
        canvas.drawPath(path, paint);
    }

    public void drawButton(Canvas canvas, float cx, float cy, float radius, int trackColor, int sectorColor, float shapeAmount, boolean advance) {
        amount = Math.max(0, Math.min(1, shapeAmount));
        if (advance) {
            tick();
        }
        final float R = radius * (1 + 0.05f * (float) Math.sin(pop * Math.PI) * amount);
        shapePath(cx, cy, R);
        fill.setColor(trackColor);
        canvas.drawPath(path, fill);
        if ((sectorColor >>> 24) == 0) {
            return;
        }
        fill.setColor(sectorColor);
        drawSector(canvas, cx, cy, R);
    }

    private void drawSector(Canvas canvas, float cx, float cy, float R) {
        // the sector: from the top, clockwise, as far as the percentage goes (all of it without one)
        final float sweep = 360f * Math.max(progress, ind);
        if (sweep >= 359.5f) {
            canvas.drawPath(path, fill);
            return;
        }
        if (sweep <= 0.5f) {
            return;
        }
        // the shape and the sector intersected as paths, so the edge stays smooth (a clip would not be anti-aliased)
        final float big = R * 1.6f;
        sector.rewind();
        sector.moveTo(cx, cy);
        sector.arcTo(cx - big, cy - big, cx + big, cy + big, -90, sweep, false);
        sector.close();
        if (sector.op(path, Path.Op.INTERSECT)) {
            canvas.drawPath(sector, fill);
        }
    }

    private void tick() {
        final long now = SystemClock.elapsedRealtime();
        if (lastTime == 0) {
            ind = indeterminate ? 1 : 0; // the first frame starts as it is, not fading in from the other mode
        }
        float dt = lastTime == 0 ? 0.016f : (now - lastTime) / 1000f;
        lastTime = now;
        if (dt > 0.05f) {
            dt = 0.05f; // after a pause it goes on where it was, not with a jump
        }
        step(dt);
    }

    /** The size the shapes take for a spinner of {@code arcSize} (they are solid, so a little smaller reads the same). */
    public static float sizeFor(float arcSize) {
        return Math.max(AndroidUtilities.dp(10), arcSize * 0.9f);
    }
}
