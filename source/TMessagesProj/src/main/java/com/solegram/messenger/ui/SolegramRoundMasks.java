/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.ui;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

import com.solegram.messenger.SolegramRoundFx;

import org.telegram.messenger.Emoji;
import org.telegram.messenger.R;

import java.util.HashMap;

/**
 * Face masks for round videos, in the spirit of the ones in Snapchat and TikTok: ears, noses, glasses, crowns, horns
 * and halos that follow the face. Each one is drawn in the face's own space (the eyes 100 units apart, at (-50, 0)
 * and (50, 0), the tip of the nose at about (0, 66), the top of the head at about (0, -170)), so it turns and grows with the head.
 */
public final class SolegramRoundMasks {

    public static final int NONE = 0, DOG = 1, CAT = 2, BUNNY = 3, GLASSES = 4, HEARTS = 5, CROWN = 6, DEVIL = 7,
        HALO = 8, FLOWERS = 9, CLOWN = 10, MUSTACHE = 11, STARS = 12;
    public static final int COUNT = 13;

    private SolegramRoundMasks() {
    }

    public static int nameOf(int mask) {
        switch (mask) {
            case DOG: return R.string.SG_RoundMaskDog;
            case CAT: return R.string.SG_RoundMaskCat;
            case BUNNY: return R.string.SG_RoundMaskBunny;
            case GLASSES: return R.string.SG_RoundMaskGlasses;
            case HEARTS: return R.string.SG_RoundMaskHearts;
            case CROWN: return R.string.SG_RoundMaskCrown;
            case DEVIL: return R.string.SG_RoundMaskDevil;
            case HALO: return R.string.SG_RoundMaskHalo;
            case FLOWERS: return R.string.SG_RoundMaskFlowers;
            case CLOWN: return R.string.SG_RoundMaskClown;
            case MUSTACHE: return R.string.SG_RoundMaskMustache;
            case STARS: return R.string.SG_RoundMaskStars;
            default: return R.string.SG_RoundNone;
        }
    }

    /** The emoji that stands for the mask in the list. */
    public static String iconOf(int mask) {
        switch (mask) {
            case DOG: return "🐶";
            case CAT: return "🐱";
            case BUNNY: return "🐰";
            case GLASSES: return "😎";
            case HEARTS: return "😍";
            case CROWN: return "👑";
            case DEVIL: return "😈";
            case HALO: return "😇";
            case FLOWERS: return "🌸";
            case CLOWN: return "🤡";
            case MUSTACHE: return "🥸";
            case STARS: return "💫";
            default: return null;
        }
    }

    // region Sprites

    private static final String HEART = "❤️", CROWN_E = "👑", STAR = "⭐", SPARKLES = "✨",
        BLOSSOM = "🌸", DAISY = "🌼", HIBISCUS = "🌺";

    /** The emoji pictures a mask is made of, drawn once (Telegram's emoji, the phone's own until those are loaded). */
    public static final class Sprites {
        private final HashMap<String, Bitmap> cache = new HashMap<>();

        public synchronized Bitmap get(String emoji) {
            Bitmap b = cache.get(emoji);
            if (b != null) {
                return b;
            }
            final int size = 160;
            b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            final Canvas c = new Canvas(b);
            boolean loaded = false;
            try {
                final Drawable d = Emoji.getEmojiBigDrawable(emoji);
                if (d instanceof Emoji.EmojiDrawable && ((Emoji.EmojiDrawable) d).isLoaded()) {
                    d.setBounds(0, 0, size, size);
                    d.draw(c);
                    loaded = true;
                } else if (d instanceof Emoji.EmojiDrawable) {
                    ((Emoji.EmojiDrawable) d).preload();
                }
            } catch (Throwable ignore) {
            }
            if (!loaded) {
                final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                p.setTextSize(size * 0.8f);
                p.setTextAlign(Paint.Align.CENTER);
                final Paint.FontMetrics fm = p.getFontMetrics();
                c.drawText(emoji, size / 2f, size / 2f - (fm.ascent + fm.descent) / 2f, p);
                return b; // not kept: the next frame tries Telegram's emoji again
            }
            cache.put(emoji, b);
            return b;
        }

        /** Loads what the mask needs ahead of time (on the main thread, where emoji load). */
        public void prepare(int mask) {
            for (String e : new String[]{HEART, CROWN_E, STAR, SPARKLES, BLOSSOM, DAISY, HIBISCUS}) {
                if (uses(mask, e)) {
                    get(e);
                }
            }
        }

        private static boolean uses(int mask, String e) {
            switch (mask) {
                case HEARTS: return e.equals(HEART);
                case CROWN: return e.equals(CROWN_E) || e.equals(SPARKLES);
                case FLOWERS: return e.equals(BLOSSOM) || e.equals(DAISY) || e.equals(HIBISCUS);
                case STARS: return e.equals(STAR) || e.equals(SPARKLES);
                default: return false;
            }
        }
    }

    // endregion

    /** Draws the mask over a frame w × h for the face's pose at that moment (timeMs makes the moving ones move). */
    public static void draw(Canvas canvas, int mask, SolegramRoundFx.Pose pose, float w, float h, long timeMs, Sprites sprites) {
        if (mask == NONE || pose == null || pose.alpha <= 0.02f || pose.eye <= 0) {
            return;
        }
        final int save = pose.alpha < 0.99f
            ? canvas.saveLayerAlpha(0, 0, w, h, (int) (255 * Math.min(1f, pose.alpha)))
            : canvas.save();
        canvas.translate(pose.x * w, pose.y * h);
        canvas.rotate((float) Math.toDegrees(pose.roll));
        final float s = pose.eye * w / 100f;
        canvas.scale(s, s);
        final float t = timeMs / 1000f;
        switch (mask) {
            case DOG: dog(canvas, t); break;
            case CAT: cat(canvas); break;
            case BUNNY: bunny(canvas, t); break;
            case GLASSES: glasses(canvas); break;
            case HEARTS: hearts(canvas, t, sprites); break;
            case CROWN: crown(canvas, t, sprites); break;
            case DEVIL: devil(canvas); break;
            case HALO: halo(canvas, t); break;
            case FLOWERS: flowers(canvas, sprites); break;
            case CLOWN: clown(canvas); break;
            case MUSTACHE: mustache(canvas); break;
            case STARS: stars(canvas, t, sprites); break;
        }
        canvas.restoreToCount(save);
    }

    private static Paint fill(int color) {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        return p;
    }

    private static Paint stroke(int color, float width) {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(width);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setColor(color);
        return p;
    }

    private static void sprite(Canvas c, Sprites sprites, String emoji, float cx, float cy, float size, float rotation, int alpha) {
        if (sprites == null) {
            return;
        }
        final Bitmap b = sprites.get(emoji);
        if (b == null) {
            return;
        }
        final Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        p.setAlpha(alpha);
        c.save();
        c.translate(cx, cy);
        c.rotate(rotation);
        c.drawBitmap(b, null, new RectF(-size / 2, -size / 2, size / 2, size / 2), p);
        c.restore();
    }

    private static void nose(Canvas c, float cy, float w, float h, int color) {
        c.drawOval(new RectF(-w / 2, cy - h / 2, w / 2, cy + h / 2), fill(color));
        final Paint shine = fill(0x80FFFFFF);
        c.drawOval(new RectF(-w * 0.28f, cy - h * 0.36f, -w * 0.02f, cy - h * 0.08f), shine);
    }

    private static void dog(Canvas c, float t) {
        for (int s = -1; s <= 1; s += 2) {
            c.save();
            c.rotate(s * (float) Math.sin(t * 3.2f) * 4f, s * 70, -140);
            final Path ear = new Path();
            ear.moveTo(s * 55, -142);
            ear.cubicTo(s * 120, -182, s * 182, -112, s * 168, -18);
            ear.cubicTo(s * 162, 28, s * 118, 34, s * 104, 2);
            ear.cubicTo(s * 94, -40, s * 78, -96, s * 55, -142);
            ear.close();
            final Paint brown = fill(0xFF8B5A2B);
            brown.setShader(new LinearGradient(0, -170, 0, 20, 0xFF9C6634, 0xFF6E4220, Shader.TileMode.CLAMP));
            c.drawPath(ear, brown);
            final Path inner = new Path();
            inner.moveTo(s * 72, -128);
            inner.cubicTo(s * 116, -150, s * 152, -100, s * 146, -36);
            inner.cubicTo(s * 140, -6, s * 120, -6, s * 112, -24);
            inner.cubicTo(s * 104, -60, s * 92, -98, s * 72, -128);
            inner.close();
            c.drawPath(inner, fill(0xFFC98B5A));
            c.restore();
        }
        nose(c, 70, 58, 40, 0xFF1E1A1A);
    }

    private static void cat(Canvas c) {
        for (int s = -1; s <= 1; s += 2) {
            final Path ear = new Path();
            ear.moveTo(s * 32, -118);
            ear.lineTo(s * 96, -218);
            ear.lineTo(s * 122, -100);
            ear.close();
            final Paint outer = fill(0xFF5E5E66);
            outer.setPathEffect(new android.graphics.CornerPathEffect(14));
            c.drawPath(ear, outer);
            final Path in = new Path();
            in.moveTo(s * 50, -118);
            in.lineTo(s * 94, -190);
            in.lineTo(s * 108, -112);
            in.close();
            final Paint pink = fill(0xFFFF9EB8);
            pink.setPathEffect(new android.graphics.CornerPathEffect(10));
            c.drawPath(in, pink);
            final Paint whisker = stroke(0xE6FFFFFF, 3.5f);
            for (int k = -1; k <= 1; k++) {
                c.drawLine(s * 34, 80 + k * 9, s * 128, 68 + k * 24, whisker);
            }
        }
        final Path nose = new Path();
        nose.moveTo(-17, 58);
        nose.lineTo(17, 58);
        nose.lineTo(0, 78);
        nose.close();
        final Paint pink = fill(0xFFFF7FA3);
        pink.setPathEffect(new android.graphics.CornerPathEffect(8));
        c.drawPath(nose, pink);
    }

    private static void bunny(Canvas c, float t) {
        for (int s = -1; s <= 1; s += 2) {
            c.save();
            c.rotate(s * (14 + (float) Math.sin(t * 2.6f + s) * 4f), s * 45, -120);
            final RectF outer = new RectF(s * 45 - 34, -320, s * 45 + 34, -110);
            final Paint white = fill(0xFFFAFAFA);
            white.setShadowLayer(6, 0, 2, 0x40000000);
            c.drawOval(outer, white);
            c.drawOval(new RectF(s * 45 - 17, -296, s * 45 + 17, -128), fill(0xFFFFB3C8));
            c.restore();
        }
        for (int s = -1; s <= 1; s += 2) {
            final Paint cheek = fill(0);
            cheek.setShader(new RadialGradient(s * 72, 78, 30, 0x80FF8FAF, 0x00FF8FAF, Shader.TileMode.CLAMP));
            c.drawCircle(s * 72, 78, 30, cheek);
        }
        nose(c, 66, 30, 20, 0xFFFF8FAF);
    }

    private static void glasses(Canvas c) {
        final Paint frame = stroke(0xFF101012, 6);
        final Paint temple = stroke(0xFF101012, 7);
        c.drawLine(-100, -8, -128, -14, temple);
        c.drawLine(100, -8, 128, -14, temple);
        final Path bridge = new Path();
        bridge.moveTo(-10, -8);
        bridge.quadTo(0, -18, 10, -8);
        c.drawPath(bridge, stroke(0xFF101012, 7));
        for (int s = -1; s <= 1; s += 2) {
            final RectF lens = new RectF(s * 54 - 46, -30, s * 54 + 46, 34);
            final Paint glass = fill(0);
            glass.setShader(new LinearGradient(0, -30, 0, 34, 0xF0202024, 0xF0484852, Shader.TileMode.CLAMP));
            c.drawRoundRect(lens, 22, 22, glass);
            c.save();
            final Path clip = new Path();
            clip.addRoundRect(lens, 22, 22, Path.Direction.CW);
            c.clipPath(clip);
            final Paint shine = stroke(0x40FFFFFF, 10);
            c.drawLine(s * 54 - 30, 30, s * 54 + 10, -34, shine);
            c.drawLine(s * 54 - 8, 34, s * 54 + 26, -26, stroke(0x26FFFFFF, 5));
            c.restore();
            c.drawRoundRect(lens, 22, 22, frame);
        }
    }

    private static void hearts(Canvas c, float t, Sprites sprites) {
        final float pulse = 1f + 0.09f * (float) Math.sin(t * 6.5f);
        sprite(c, sprites, HEART, -50, 0, 82 * pulse, -8, 255);
        sprite(c, sprites, HEART, 50, 0, 82 * pulse, 8, 255);
        for (int i = 0; i < 4; i++) {
            final float phase = (t / 1.8f + i / 4f) % 1f;
            final float x = -105 + i * 70 + (float) Math.sin((t + i) * 2.4f) * 12;
            final float y = -110 - phase * 150;
            sprite(c, sprites, HEART, x, y, 34 * (1f - phase * 0.35f), (float) Math.sin(t * 3 + i) * 15, (int) (255 * (1f - phase)));
        }
    }

    private static void crown(Canvas c, float t, Sprites sprites) {
        final float bob = (float) Math.sin(t * 2.2f) * 4;
        sprite(c, sprites, CROWN_E, 0, -215 + bob, 175, 0, 255);
        for (int i = 0; i < 3; i++) {
            final float phase = (t / 1.4f + i / 3f) % 1f;
            final float a = (float) Math.sin(phase * Math.PI);
            sprite(c, sprites, SPARKLES, -100 + i * 100, -280 + bob - i % 2 * 24, 34 + 10 * a, 0, (int) (255 * a));
        }
    }

    private static void devil(Canvas c) {
        for (int s = -1; s <= 1; s += 2) {
            final Path horn = new Path();
            horn.moveTo(s * 34, -122);
            horn.quadTo(s * 52, -196, s * 104, -222);
            horn.quadTo(s * 86, -168, s * 90, -112);
            horn.close();
            final Paint red = fill(0);
            red.setShader(new LinearGradient(0, -222, 0, -112, 0xFFFF453A, 0xFFB0001E, Shader.TileMode.CLAMP));
            red.setShadowLayer(14, 0, 0, 0x99FF2D2D);
            c.drawPath(horn, red);
        }
    }

    private static void halo(Canvas c, float t) {
        final float bob = (float) Math.sin(t * 2f) * 7;
        final RectF ring = new RectF(-82, -262 + bob, 82, -224 + bob);
        final Paint glow = stroke(0x66FFC107, 22);
        glow.setMaskFilter(new android.graphics.BlurMaskFilter(14, android.graphics.BlurMaskFilter.Blur.NORMAL));
        c.drawOval(ring, glow);
        final Paint gold = stroke(0xFFFFD54F, 10);
        gold.setShader(new LinearGradient(-82, 0, 82, 0, new int[]{0xFFFFC107, 0xFFFFF3B0, 0xFFFFC107}, null, Shader.TileMode.CLAMP));
        c.drawOval(ring, gold);
    }

    private static void flowers(Canvas c, Sprites sprites) {
        final String[] kinds = {BLOSSOM, DAISY, HIBISCUS};
        final int n = 7;
        for (int i = 0; i < n; i++) {
            final double a = Math.toRadians(-165 + 150.0 * i / (n - 1));
            final float x = (float) Math.cos(a) * 138;
            final float y = -40 + (float) Math.sin(a) * 128;
            final float size = i % 2 == 0 ? 62 : 50;
            sprite(c, sprites, kinds[i % 3], x, y, size, (i - 3) * 9, 255);
        }
    }

    private static void clown(Canvas c) {
        for (int s = -1; s <= 1; s += 2) {
            final Paint cheek = fill(0);
            cheek.setShader(new RadialGradient(s * 74, 80, 32, 0x99FF5A7A, 0x00FF5A7A, Shader.TileMode.CLAMP));
            c.drawCircle(s * 74, 80, 32, cheek);
        }
        final Paint red = fill(0);
        red.setShader(new RadialGradient(-8, 56, 40, 0xFFFF6B6B, 0xFFD50000, Shader.TileMode.CLAMP));
        red.setShadowLayer(6, 0, 3, 0x55000000);
        c.drawCircle(0, 66, 30, red);
        c.drawOval(new RectF(-16, 46, -2, 58), fill(0xB3FFFFFF));
    }

    private static void mustache(Canvas c) {
        final Path m = new Path();
        for (int s = -1; s <= 1; s += 2) {
            m.moveTo(0, 74);
            m.cubicTo(s * 26, 58, s * 56, 58, s * 72, 74);
            m.cubicTo(s * 84, 86, s * 102, 82, s * 106, 64);
            m.cubicTo(s * 112, 94, s * 72, 106, s * 48, 92);
            m.cubicTo(s * 30, 82, s * 12, 88, 0, 90);
            m.close();
        }
        final Paint brown = fill(0xFF2B1D14);
        brown.setShadowLayer(4, 0, 2, 0x55000000);
        c.save();
        c.translate(0, 18); // under the nose, over the lip
        c.scale(0.9f, 0.9f, 0, 80);
        c.drawPath(m, brown);
        c.restore();
    }

    private static void stars(Canvas c, float t, Sprites sprites) {
        final int n = 5;
        // the far half first, the near half over the head's top
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < n; i++) {
                final double a = t * 1.6 + i * Math.PI * 2 / n;
                final float depth = (float) Math.sin(a);
                if ((depth < 0) != (pass == 0)) {
                    continue;
                }
                final float x = (float) Math.cos(a) * 150;
                final float y = -175 + depth * 34;
                final float size = 44 + depth * 12;
                sprite(c, sprites, i % 2 == 0 ? STAR : SPARKLES, x, y, size, (float) Math.toDegrees(a) * 0.2f, (int) (255 * (0.75f + 0.25f * depth)));
            }
        }
    }
}
