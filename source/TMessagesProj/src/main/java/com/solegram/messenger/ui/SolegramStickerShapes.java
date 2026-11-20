/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;

import org.telegram.messenger.R;
import org.telegram.messenger.LocaleController;

/**
 * Solegram: the shape of a sticker made from a photo. Telegram cuts a sticker to a rounded square; here it can be a
 * circle, a heart, a star or a soft square too. The editor shows the chosen shape over the photo (the rest dimmed) and
 * the sticker is cut to it when it is made.
 */
public final class SolegramStickerShapes {

    public static final int ROUNDED = 0, CIRCLE = 1, HEART = 2, STAR = 3, SQUIRCLE = 4;
    /** Material You shapes: the loading indicator's ({@link SolegramShapeProgress}, 100 + its index) and more (200+). */
    private static final int LOADING = 100, MORE = 200;
    private static final int COOKIE_6 = MORE, COOKIE_12 = MORE + 1, CLOVER_8 = MORE + 2, VERY_SUNNY = MORE + 3,
        DIAMOND = MORE + 4, GEM = MORE + 5, TRIANGLE = MORE + 6, OVAL = MORE + 7;
    private static final int[] ORDER;

    static {
        final int loading = SolegramShapeProgress.shapeCount();
        final int[] more = {COOKIE_6, COOKIE_12, CLOVER_8, VERY_SUNNY, DIAMOND, GEM, TRIANGLE, OVAL};
        ORDER = new int[5 + loading + more.length];
        int k = 0;
        for (int shape : new int[]{ROUNDED, CIRCLE, HEART, STAR, SQUIRCLE}) {
            ORDER[k++] = shape;
        }
        for (int i = 0; i < loading; i++) {
            ORDER[k++] = LOADING + i;
        }
        for (int shape : more) {
            ORDER[k++] = shape;
        }
    }

    // region Material You shapes: the radius at evenly spread angles (0 = to the right, clockwise), the largest 1

    private static final int RADIAL_POINTS = 240;
    private static final android.util.SparseArray<float[]> radials = new android.util.SparseArray<>();

    private interface Radius {
        double at(double a);
    }

    private static float[] radial(Radius f, double rot) {
        final float[] r = new float[RADIAL_POINTS];
        float max = 0;
        for (int i = 0; i < RADIAL_POINTS; i++) {
            r[i] = (float) f.at(i * 2 * Math.PI / RADIAL_POINTS - rot);
            max = Math.max(max, r[i]);
        }
        for (int i = 0; i < RADIAL_POINTS; i++) {
            r[i] /= max;
        }
        return r;
    }

    /** A superformula outline (rounded polygons). */
    private static Radius superformula(double m, double n1, double n2, double n3) {
        return a -> Math.pow(Math.pow(Math.abs(Math.cos(m * a / 4)), n2) + Math.pow(Math.abs(Math.sin(m * a / 4)), n3), -1 / n1);
    }

    /** A regular polygon with its corners rounded by smoothing its radius round the circle. */
    private static float[] smoothPolygon(int sides, double rot, int window) {
        final double seg = 2 * Math.PI / sides;
        float[] r = new float[RADIAL_POINTS];
        for (int i = 0; i < RADIAL_POINTS; i++) {
            final double a = i * 2 * Math.PI / RADIAL_POINTS - rot;
            final double t = ((a % seg) + seg) % seg - seg / 2;
            r[i] = (float) (Math.cos(seg / 2) / Math.cos(t));
        }
        for (int pass = 0; pass < 3; pass++) {
            final float[] next = new float[RADIAL_POINTS];
            for (int i = 0; i < RADIAL_POINTS; i++) {
                float sum = 0;
                for (int k = -window; k <= window; k++) {
                    sum += r[Math.floorMod(i + k, RADIAL_POINTS)];
                }
                next[i] = sum / (2 * window + 1);
            }
            r = next;
        }
        float max = 0;
        for (float v : r) {
            max = Math.max(max, v);
        }
        for (int i = 0; i < RADIAL_POINTS; i++) {
            r[i] /= max;
        }
        return r;
    }

    /** The radius array of a Material shape, or null for the other shapes. */
    private static float[] radialOf(int shape) {
        if (shape < LOADING) {
            return null;
        }
        float[] r = radials.get(shape);
        if (r != null) {
            return r;
        }
        if (shape < MORE) {
            r = SolegramShapeProgress.shape(shape - LOADING);
        } else {
            final double up = -Math.PI / 2;
            switch (shape) {
                case COOKIE_6: r = radial(a -> 1 + 0.10 * Math.cos(6 * a), up); break;
                case COOKIE_12: r = radial(a -> 1 + 0.055 * Math.cos(12 * a), up); break;
                case CLOVER_8: r = radial(a -> 0.78 + 0.22 * Math.pow(Math.abs(Math.cos(4 * a)), 0.6), up); break;
                case VERY_SUNNY: r = radial(a -> 1 + 0.045 * Math.cos(16 * a), up); break;
                case DIAMOND: r = radial(a -> Math.pow(Math.pow(Math.abs(Math.cos(a)), 1.35) + Math.pow(Math.abs(Math.sin(a)), 1.35), -1 / 1.35), 0); break;
                case GEM: r = smoothPolygon(6, up, 6); break;
                case TRIANGLE: r = radial(superformula(3, 4.5, 8, 8), Math.PI / 2); break;
                case OVAL: r = radial(a -> 1 / Math.sqrt(Math.pow(Math.cos(a - Math.PI / 4), 2) + Math.pow(Math.sin(a - Math.PI / 4) / 0.72, 2)), 0); break;
                default: return null;
            }
        }
        radials.put(shape, r);
        return r;
    }

    /** A radius array as a closed path in the square {@code r} (smoothed through the points' midpoints). */
    private static void radialPath(float[] radius, RectF r, Path out) {
        final int n = radius.length;
        final float cx = r.centerX(), cy = r.centerY(), half = Math.min(r.width(), r.height()) / 2f;
        final float[] xs = new float[n], ys = new float[n];
        for (int i = 0; i < n; i++) {
            final double a = i * 2 * Math.PI / n;
            xs[i] = cx + (float) Math.cos(a) * radius[i] * half;
            ys[i] = cy + (float) Math.sin(a) * radius[i] * half;
        }
        out.moveTo((xs[n - 1] + xs[0]) / 2f, (ys[n - 1] + ys[0]) / 2f);
        for (int i = 0; i < n; i++) {
            final int next = (i + 1) % n;
            out.quadTo(xs[i], ys[i], (xs[i] + xs[next]) / 2f, (ys[i] + ys[next]) / 2f);
        }
        out.close();
    }

    // endregion

    /** The shape of the sticker being made now. */
    public static int current = ROUNDED;

    private SolegramStickerShapes() {
    }

    /** Whether a shape other than Telegram's own (the rounded square) is chosen. */
    public static boolean custom() {
        return current != ROUNDED;
    }

    /** The outline of {@code shape} in {@code r} (a square); {@code roundedRadius} is the rounded square's corner. */
    public static void path(int shape, RectF r, float roundedRadius, Path out) {
        out.rewind();
        final float[] radius = radialOf(shape);
        if (radius != null) {
            radialPath(radius, r, out);
            return;
        }
        final float w = r.width(), h = r.height();
        final float cx = r.centerX(), cy = r.centerY();
        switch (shape) {
            case CIRCLE:
                out.addCircle(cx, cy, Math.min(w, h) / 2f, Path.Direction.CW);
                break;
            case HEART: {
                // two lobes and a point, drawn with cubic curves inside the square
                final float x = r.left, y = r.top;
                out.moveTo(x + w * 0.5f, y + h * 0.94f);
                out.cubicTo(x + w * 0.18f, y + h * 0.72f, x - w * 0.02f, y + h * 0.48f, x + w * 0.06f, y + h * 0.28f);
                out.cubicTo(x + w * 0.13f, y + h * 0.10f, x + w * 0.37f, y + h * 0.02f, x + w * 0.5f, y + h * 0.22f);
                out.cubicTo(x + w * 0.63f, y + h * 0.02f, x + w * 0.87f, y + h * 0.10f, x + w * 0.94f, y + h * 0.28f);
                out.cubicTo(x + w * 1.02f, y + h * 0.48f, x + w * 0.82f, y + h * 0.72f, x + w * 0.5f, y + h * 0.94f);
                out.close();
                break;
            }
            case STAR: {
                // a five-pointed star with softly rounded tips
                final float outer = Math.min(w, h) / 2f, inner = outer * 0.5f;
                final float[] px = new float[10], py = new float[10];
                for (int i = 0; i < 10; i++) {
                    final double a = -Math.PI / 2 + i * Math.PI / 5;
                    final float rad = i % 2 == 0 ? outer : inner;
                    px[i] = cx + (float) Math.cos(a) * rad;
                    py[i] = cy + (float) Math.sin(a) * rad * 1.02f + outer * 0.06f;
                }
                final float k = 0.18f;
                out.moveTo(lerp(px[9], px[0], 1f - k), lerp(py[9], py[0], 1f - k));
                for (int i = 0; i < 10; i++) {
                    final int next = (i + 1) % 10;
                    out.quadTo(px[i], py[i], lerp(px[i], px[next], k), lerp(py[i], py[next], k));
                    out.lineTo(lerp(px[i], px[next], 1f - k), lerp(py[i], py[next], 1f - k));
                }
                out.close();
                break;
            }
            case SQUIRCLE: {
                // a superellipse: softer corners than the rounded square
                final int steps = 72;
                final float a = w / 2f, b = h / 2f, n = 4.2f;
                for (int i = 0; i <= steps; i++) {
                    final double t = i * 2 * Math.PI / steps;
                    final double c = Math.cos(t), s = Math.sin(t);
                    final float x = cx + (float) (a * Math.signum(c) * Math.pow(Math.abs(c), 2.0 / n));
                    final float y = cy + (float) (b * Math.signum(s) * Math.pow(Math.abs(s), 2.0 / n));
                    if (i == 0) {
                        out.moveTo(x, y);
                    } else {
                        out.lineTo(x, y);
                    }
                }
                out.close();
                break;
            }
            default:
                out.addRoundRect(r, roundedRadius, roundedRadius, Path.Direction.CW);
                break;
        }
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    /** The square a sticker is made in, as the editor shows it (the same as its checked background). */
    public static void editorSquare(View container, RectF out) {
        final float inset = dp(10);
        final float width = container.getMeasuredWidth() - inset * 2;
        final float height = container.getMeasuredHeight() - inset * 2;
        out.set(inset, inset, inset + width, inset + width);
        out.offset(0, (height - out.height()) / 2);
    }

    /** Over the photo in the editor: the chosen shape outlined, the rest dimmed (nothing for the rounded square). */
    @SuppressLint("ViewConstructor")
    public static final class Overlay extends View {
        private final Paint dim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF square = new RectF();
        private final View container;
        private final java.util.function.BooleanSupplier active;

        /** {@code active}: the viewer makes a sticker now (and is in a mode that shows its square). */
        public Overlay(Context context, View container, java.util.function.BooleanSupplier active) {
            super(context);
            this.container = container;
            this.active = active;
            dim.setColor(0x8C000000);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(dp(1.5f));
            stroke.setColor(0xCCFFFFFF);
            setWillNotDraw(false);
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            if (!custom() || !active.getAsBoolean()) {
                return;
            }
            editorSquare(container, square);
            path(current, square, square.width() / 7f, path);
            canvas.save();
            if (Build.VERSION.SDK_INT >= 26) {
                canvas.clipOutPath(path);
            } else {
                canvas.clipPath(path, android.graphics.Region.Op.DIFFERENCE);
            }
            canvas.drawRect(0, 0, getWidth(), getHeight(), dim);
            canvas.restore();
            canvas.drawPath(path, stroke);
        }
    }

    /** The row of shapes to choose from (a translucent pill with a glyph per shape). */
    @SuppressLint("ViewConstructor")
    public static final class Bar extends android.widget.HorizontalScrollView {
        private final Runnable onChanged;
        private final LinearLayout row;

        public Bar(Context context, Runnable onChanged) {
            super(context);
            this.onChanged = onChanged;
            setHorizontalScrollBarEnabled(false);
            setFillViewport(true);
            setOverScrollMode(OVER_SCROLL_NEVER);
            final GradientDrawable bg = new GradientDrawable();
            bg.setColor(0x66000000);
            bg.setCornerRadius(dp(24));
            setBackground(bg);
            row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER);
            row.setPadding(dp(6), dp(4), dp(6), dp(4));
            addView(row, new android.widget.FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT, Gravity.CENTER));
            for (int shape : ORDER) {
                final Chip chip = new Chip(context, shape);
                chip.setOnClickListener(v -> {
                    if (current != shape) {
                        current = shape;
                        for (int i = 0; i < row.getChildCount(); i++) {
                            row.getChildAt(i).invalidate();
                        }
                        try {
                            v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
                        } catch (Throwable ignore) {
                        }
                        onChanged.run();
                    }
                });
                row.addView(chip, new LinearLayout.LayoutParams(dp(40), dp(40)));
            }
        }

        private static final class Chip extends View {
            private final int shape;
            private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Path glyph = new Path();
            private final RectF rect = new RectF();

            Chip(Context context, int shape) {
                super(context);
                this.shape = shape;
                ring.setStyle(Paint.Style.STROKE);
                ring.setStrokeWidth(dp(1.5f));
                ring.setColor(0xFFFFFFFF);
                setContentDescription(LocaleController.getString(name(shape)));
            }

            @Override
            protected void onDraw(@NonNull Canvas canvas) {
                final boolean selected = current == shape;
                final float s = dp(selected ? 22 : 20);
                rect.set(getWidth() / 2f - s / 2f, getHeight() / 2f - s / 2f, getWidth() / 2f + s / 2f, getHeight() / 2f + s / 2f);
                path(shape, rect, s / 4f, glyph);
                fill.setColor(selected ? 0xFFFFFFFF : 0x66FFFFFF);
                canvas.drawPath(glyph, fill);
                if (selected) {
                    final float r = Math.min(getWidth(), getHeight()) / 2f - dp(2);
                    canvas.drawCircle(getWidth() / 2f, getHeight() / 2f, r, ring);
                }
            }
        }
    }

    private static int name(int shape) {
        switch (shape) {
            case CIRCLE: return R.string.SG_StickerShapeCircle;
            case HEART: return R.string.SG_StickerShapeHeart;
            case STAR: return R.string.SG_StickerShapeStar;
            case SQUIRCLE: return R.string.SG_StickerShapeSquircle;
            case ROUNDED: return R.string.SG_StickerShapeRounded;
            default: return R.string.SG_StickerShapeMaterial;
        }
    }
}
