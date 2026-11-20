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
    private static final int[] ORDER = {ROUNDED, CIRCLE, HEART, STAR, SQUIRCLE};

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
    public static final class Bar extends LinearLayout {
        private final Runnable onChanged;

        public Bar(Context context, Runnable onChanged) {
            super(context);
            this.onChanged = onChanged;
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER);
            setPadding(dp(6), dp(4), dp(6), dp(4));
            final GradientDrawable bg = new GradientDrawable();
            bg.setColor(0x66000000);
            bg.setCornerRadius(dp(24));
            setBackground(bg);
            for (int shape : ORDER) {
                final Chip chip = new Chip(context, shape);
                chip.setOnClickListener(v -> {
                    if (current != shape) {
                        current = shape;
                        for (int i = 0; i < getChildCount(); i++) {
                            getChildAt(i).invalidate();
                        }
                        try {
                            v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
                        } catch (Throwable ignore) {
                        }
                        onChanged.run();
                    }
                });
                addView(chip, new LayoutParams(dp(40), dp(40)));
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
            default: return R.string.SG_StickerShapeRounded;
        }
    }
}
