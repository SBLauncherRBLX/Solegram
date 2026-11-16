/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.ui;

import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Color;
import android.graphics.CornerPathEffect;
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
        // a turned head: the eyes look closer, the face is as tall as ever; across it the mask is pressed as much
        final float across = (float) Math.max(0.5, Math.cos(pose.yaw));
        final float s = pose.eye * w / 100f / across;
        canvas.scale(s * across, s);
        final float t = timeMs / 1000f;
        final Face f = new Face(pose);
        switch (mask) {
            case DOG: dog(canvas, f, t); break;
            case CAT: cat(canvas, f, t); break;
            case BUNNY: bunny(canvas, f, t); break;
            case GLASSES: glasses(canvas, f, t); break;
            case HEARTS: hearts(canvas, f, t, sprites); break;
            case CROWN: crown(canvas, f, t, sprites); break;
            case DEVIL: devil(canvas, f, t); break;
            case HALO: halo(canvas, f, t); break;
            case FLOWERS: flowers(canvas, f, sprites); break;
            case CLOWN: clown(canvas, f, t); break;
            case MUSTACHE: mustache(canvas, f); break;
            case STARS: stars(canvas, f, t, sprites); break;
        }
        canvas.restoreToCount(save);
    }

    /** The face's points for the drawing, and how far the parts at each depth slide when the head turns. */
    private static final class Face {
        final float noseX, noseY, mouthY, open;
        /** sideways shift of what is on the top of the head (behind the face's plane) */
        final float back;

        Face(SolegramRoundFx.Pose p) {
            noseX = p.noseX;
            noseY = p.noseY;
            mouthY = p.mouthY;
            open = p.mouthOpen;
            back = -p.noseX * 0.45f;
        }
    }

    // region Painting helpers

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

    private static Paint vertical(float top, float bottom, int from, int to) {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, top, 0, bottom, from, to, Shader.TileMode.CLAMP));
        return p;
    }

    private static Paint radial(float cx, float cy, float r, int inner, int outer) {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new RadialGradient(cx, cy, Math.max(1, r), inner, outer, Shader.TileMode.CLAMP));
        return p;
    }

    /** A soft shadow under a shape, as if it sat a little in front of the face. */
    private static void shadow(Canvas c, Path path, float dy, float blur, int alpha) {
        final Paint p = fill(alpha << 24);
        p.setMaskFilter(new BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL));
        c.save();
        c.translate(0, dy);
        c.drawPath(path, p);
        c.restore();
    }

    private static void glow(Canvas c, Path path, int color, float blur) {
        final Paint p = fill(color);
        p.setMaskFilter(new BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL));
        c.drawPath(path, p);
    }

    private static void sprite(Canvas c, Sprites sprites, String emoji, float cx, float cy, float size, float rotation, int alpha) {
        if (sprites == null || alpha <= 0) {
            return;
        }
        final Bitmap b = sprites.get(emoji);
        if (b == null) {
            return;
        }
        final Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        p.setAlpha(Math.min(255, alpha));
        c.save();
        c.translate(cx, cy);
        c.rotate(rotation);
        c.drawBitmap(b, null, new RectF(-size / 2, -size / 2, size / 2, size / 2), p);
        c.restore();
    }

    /** A four-pointed glint. */
    private static Path glint(float cx, float cy, float r) {
        final Path p = new Path();
        p.moveTo(cx, cy - r);
        p.quadTo(cx, cy, cx + r, cy);
        p.quadTo(cx, cy, cx, cy + r);
        p.quadTo(cx, cy, cx - r, cy);
        p.quadTo(cx, cy, cx, cy - r);
        p.close();
        return p;
    }

    /** A glossy animal nose (a rounded triangle), with its nostrils and a highlight. */
    private static void animalNose(Canvas c, float cx, float cy, float w, float h, int light, int dark, boolean nostrils) {
        final Path n = new Path();
        n.moveTo(cx - w / 2, cy - h * 0.25f);
        n.cubicTo(cx - w / 2, cy - h * 0.62f, cx + w / 2, cy - h * 0.62f, cx + w / 2, cy - h * 0.25f);
        n.cubicTo(cx + w / 2, cy + h * 0.1f, cx + w * 0.16f, cy + h * 0.5f, cx, cy + h * 0.5f);
        n.cubicTo(cx - w * 0.16f, cy + h * 0.5f, cx - w / 2, cy + h * 0.1f, cx - w / 2, cy - h * 0.25f);
        n.close();
        shadow(c, n, h * 0.18f, h * 0.22f, 0x55);
        c.drawPath(n, radial(cx - w * 0.12f, cy - h * 0.3f, w * 0.75f, light, dark));
        if (nostrils) {
            final Paint hole = fill(0x99000000);
            c.drawOval(new RectF(cx - w * 0.32f, cy + h * 0.02f, cx - w * 0.1f, cy + h * 0.22f), hole);
            c.drawOval(new RectF(cx + w * 0.1f, cy + h * 0.02f, cx + w * 0.32f, cy + h * 0.22f), hole);
        }
        c.drawOval(new RectF(cx - w * 0.3f, cy - h * 0.46f, cx - w * 0.02f, cy - h * 0.22f), fill(0xA6FFFFFF));
        c.drawCircle(cx + w * 0.16f, cy - h * 0.34f, h * 0.06f, fill(0x80FFFFFF));
    }

    private static void cheeks(Canvas c, float y, int color) {
        for (int s = -1; s <= 1; s += 2) {
            c.drawCircle(s * 74, y, 34, radial(s * 74, y, 34, color, color & 0x00FFFFFF));
        }
    }

    // endregion

    // region The masks

    private static void dog(Canvas c, Face f, float t) {
        c.save();
        c.translate(f.back, 0);
        for (int s = -1; s <= 1; s += 2) {
            c.save();
            c.rotate(s * ((float) Math.sin(t * 3.2f) * 4f + f.open * 10f), s * 70, -150);
            final Path ear = new Path();
            ear.moveTo(s * 48, -150);
            ear.cubicTo(s * 120, -200, s * 196, -120, s * 182, -12);
            ear.cubicTo(s * 176, 40, s * 126, 52, s * 108, 10);
            ear.cubicTo(s * 96, -34, s * 76, -100, s * 48, -150);
            ear.close();
            shadow(c, ear, 8, 12, 0x50);
            c.drawPath(ear, vertical(-190, 40, 0xFFA8703A, 0xFF5A3514));
            final Path inner = new Path();
            inner.moveTo(s * 70, -134);
            inner.cubicTo(s * 120, -162, s * 162, -104, s * 156, -32);
            inner.cubicTo(s * 150, 4, s * 128, 6, s * 118, -18);
            inner.cubicTo(s * 108, -58, s * 94, -100, s * 70, -134);
            inner.close();
            c.drawPath(inner, vertical(-160, 0, 0xFFE9B489, 0xFFB47648));
            // fur
            final Paint fur = stroke(0x66FFE0C0, 3f);
            for (int k = 0; k < 4; k++) {
                final Path line = new Path();
                line.moveTo(s * (132 + k * 9), -120 + k * 22);
                line.quadTo(s * (150 + k * 8), -80 + k * 22, s * (146 + k * 8), -50 + k * 22);
                c.drawPath(line, fur);
            }
            c.drawPath(ear, stroke(0x804A2A0E, 3f));
            c.restore();
        }
        c.restore();
        animalNose(c, f.noseX, f.noseY - 8, 64, 46, 0xFF4A4242, 0xFF0B0909, true);
        if (f.open > 0.2f) {
            // the tongue hangs out of an open mouth
            final float k = Math.min(1f, (f.open - 0.2f) / 0.6f);
            final float top = f.mouthY + 2, len = 34 + 62 * k, wd = 44;
            final float sway = (float) Math.sin(t * 7f) * 3f * k;
            final Path tongue = new Path();
            tongue.moveTo(f.noseX * 0.6f - wd / 2, top);
            tongue.lineTo(f.noseX * 0.6f - wd / 2 + sway, top + len - wd / 2);
            tongue.cubicTo(f.noseX * 0.6f - wd / 2 + sway, top + len + wd * 0.2f, f.noseX * 0.6f + wd / 2 + sway, top + len + wd * 0.2f, f.noseX * 0.6f + wd / 2 + sway, top + len - wd / 2);
            tongue.lineTo(f.noseX * 0.6f + wd / 2, top);
            tongue.close();
            shadow(c, tongue, 6, 8, 0x40);
            c.drawPath(tongue, vertical(top, top + len, 0xFFFF8FA6, 0xFFE24A68));
            c.drawLine(f.noseX * 0.6f, top + 6, f.noseX * 0.6f + sway, top + len - 10, stroke(0x99B83250, 3.5f));
            c.drawOval(new RectF(f.noseX * 0.6f - wd * 0.34f + sway, top + len * 0.45f, f.noseX * 0.6f - wd * 0.08f + sway, top + len * 0.7f), fill(0x55FFFFFF));
        }
    }

    private static void cat(Canvas c, Face f, float t) {
        c.save();
        c.translate(f.back, 0);
        for (int s = -1; s <= 1; s += 2) {
            c.save();
            c.rotate(s * (float) Math.sin(t * 2.1f + s) * 3f, s * 80, -120);
            final Path ear = new Path();
            ear.moveTo(s * 26, -122);
            ear.quadTo(s * 60, -200, s * 100, -232);
            ear.quadTo(s * 120, -170, s * 132, -96);
            ear.close();
            final Paint round = vertical(-232, -96, 0xFFF4F4F7, 0xFFB7B7C2);
            round.setPathEffect(new CornerPathEffect(14));
            shadow(c, ear, 6, 10, 0x45);
            c.drawPath(ear, round);
            final Path in = new Path();
            in.moveTo(s * 50, -122);
            in.quadTo(s * 72, -176, s * 98, -200);
            in.quadTo(s * 110, -156, s * 116, -110);
            in.close();
            final Paint pink = vertical(-200, -110, 0xFFFFC4D3, 0xFFFF86A8);
            pink.setPathEffect(new CornerPathEffect(10));
            c.drawPath(in, pink);
            final Paint tuft = stroke(0xCCFFFFFF, 2.5f);
            for (int k = 0; k < 3; k++) {
                c.drawLine(s * (72 + k * 10), -126, s * (80 + k * 12), -150 - k * 6, tuft);
            }
            c.restore();
        }
        c.restore();
        cheeks(c, f.noseY + 12, 0x66FF8FAF);
        // whiskers, with a dark line under each so they read on any background
        for (int s = -1; s <= 1; s += 2) {
            for (int k = -1; k <= 1; k++) {
                final Path wsk = new Path();
                final float sx = f.noseX + s * 28, sy = f.noseY + 10 + k * 8;
                wsk.moveTo(sx, sy);
                wsk.quadTo(sx + s * 60, sy - 6 + k * 6, sx + s * 112, sy - 2 + k * 20);
                c.drawPath(wsk, stroke(0x40000000, 4.5f));
                c.drawPath(wsk, stroke(0xF2FFFFFF, 2.6f));
            }
        }
        animalNose(c, f.noseX, f.noseY - 6, 34, 24, 0xFFFFB0C6, 0xFFE8608A, false);
        final Path mouth = new Path();
        mouth.moveTo(f.noseX - 15, f.noseY + 14);
        mouth.quadTo(f.noseX - 7, f.noseY + 22, f.noseX, f.noseY + 10);
        mouth.quadTo(f.noseX + 7, f.noseY + 22, f.noseX + 15, f.noseY + 14);
        c.drawPath(mouth, stroke(0xCCB0405F, 3f));
    }

    private static void bunny(Canvas c, Face f, float t) {
        c.save();
        c.translate(f.back, 0);
        for (int s = -1; s <= 1; s += 2) {
            c.save();
            // the ears sway, and the far one flops a little
            c.rotate(s * (12 + (float) Math.sin(t * 2.6f + s) * 5f), s * 44, -130);
            final RectF outer = new RectF(s * 44 - 36, -340, s * 44 + 36, -118);
            final Path ear = new Path();
            ear.addOval(outer, Path.Direction.CW);
            shadow(c, ear, 6, 10, 0x40);
            c.drawPath(ear, vertical(-340, -118, 0xFFFFFFFF, 0xFFE3E3EA));
            c.drawOval(new RectF(s * 44 - 18, -312, s * 44 + 18, -138), vertical(-312, -138, 0xFFFFD0DC, 0xFFFF94B2));
            c.drawOval(new RectF(s * 44 - 26, -330, s * 44 - 8, -250), fill(0x40FFFFFF));
            c.restore();
        }
        c.restore();
        cheeks(c, f.noseY + 12, 0x70FF8FAF);
        if (f.open > 0.15f) {
            final float y = f.mouthY - 4;
            final Paint tooth = vertical(y, y + 26, 0xFFFFFFFF, 0xFFEDEDF0);
            final Paint edge = stroke(0x66808080, 2f);
            final RectF l = new RectF(f.noseX * 0.6f - 14, y, f.noseX * 0.6f - 1, y + 26);
            final RectF r = new RectF(f.noseX * 0.6f + 1, y, f.noseX * 0.6f + 14, y + 26);
            c.drawRoundRect(l, 4, 4, tooth);
            c.drawRoundRect(r, 4, 4, tooth);
            c.drawRoundRect(l, 4, 4, edge);
            c.drawRoundRect(r, 4, 4, edge);
        }
        for (int s = -1; s <= 1; s += 2) {
            for (int k = 0; k <= 1; k++) {
                final float sx = f.noseX + s * 22, sy = f.noseY + 4 + k * 9;
                c.drawLine(sx, sy, sx + s * 70, sy - 8 + k * 14, stroke(0x99FFFFFF, 2f));
            }
        }
        animalNose(c, f.noseX, f.noseY - 6, 30, 22, 0xFFFFB3C6, 0xFFFF6F95, false);
    }

    private static void glasses(Canvas c, Face f, float t) {
        c.save();
        c.translate(f.noseX * 0.2f, 0);
        final Paint temple = stroke(0xFF1A1A1E, 7);
        c.drawLine(-100, -10, -132, -16, temple);
        c.drawLine(100, -10, 132, -16, temple);
        final Path bridge = new Path();
        bridge.moveTo(-12, -10);
        bridge.quadTo(0, -22, 12, -10);
        c.drawPath(bridge, stroke(0xFF2A2A30, 7));
        for (int s = -1; s <= 1; s += 2) {
            final Path lens = new Path();
            lens.moveTo(s * 10, -30);
            lens.lineTo(s * 100, -32);
            lens.quadTo(s * 104, 18, s * 74, 34);
            lens.quadTo(s * 50, 42, s * 26, 32);
            lens.quadTo(s * 6, 20, s * 10, -30);
            lens.close();
            shadow(c, lens, 8, 10, 0x55);
            final Paint glass = new Paint(Paint.ANTI_ALIAS_FLAG);
            glass.setShader(new LinearGradient(0, -32, 0, 40, new int[]{0xF2141826, 0xF0262043, 0xEE5B2E62}, null, Shader.TileMode.CLAMP));
            c.drawPath(lens, glass);
            c.save();
            c.clipPath(lens);
            // the sky in the lens, and two streaks of light
            c.drawOval(new RectF(s * 55 - 70, -60, s * 55 + 70, -6), fill(0x223A8CFF));
            c.drawLine(s * 30, 30, s * 70, -36, stroke(0x40FFFFFF, 12));
            c.drawLine(s * 52, 36, s * 86, -22, stroke(0x24FFFFFF, 6));
            c.restore();
            final Paint frame = stroke(0, 5.5f);
            frame.setShader(new LinearGradient(0, -32, 0, 40, 0xFF5E5E66, 0xFF16161A, Shader.TileMode.CLAMP));
            c.drawPath(lens, frame);
        }
        // a glint that comes and goes
        final float g = (float) Math.max(0, Math.sin(t * 2.4f));
        if (g > 0.05f) {
            c.drawPath(glint(-74, -18, 16 * g), fill(Color.argb((int) (230 * g), 255, 255, 255)));
        }
        c.restore();
    }

    private static void hearts(Canvas c, Face f, float t, Sprites sprites) {
        final float pulse = 1f + 0.1f * (float) Math.sin(t * 6.5f);
        sprite(c, sprites, HEART, -50, 0, 84 * pulse, -8, 255);
        sprite(c, sprites, HEART, 50, 0, 84 * pulse, 8, 255);
        final int n = 4 + Math.round(f.open * 5);
        for (int i = 0; i < n; i++) {
            final float phase = (t / 1.7f + i / (float) n) % 1f;
            final float x = f.back - 120 + i * (240f / Math.max(1, n - 1)) + (float) Math.sin((t + i) * 2.4f) * 14;
            final float y = -100 - phase * 170;
            sprite(c, sprites, HEART, x, y, 36 * (1f - phase * 0.4f), (float) Math.sin(t * 3 + i) * 18, (int) (255 * Math.min(1f, (1f - phase) * 1.6f)));
        }
    }

    private static void crown(Canvas c, Face f, float t, Sprites sprites) {
        final float bob = (float) Math.sin(t * 2.2f) * 4;
        c.save();
        c.translate(f.back, 0);
        final Path halo = new Path();
        halo.addOval(new RectF(-110, -300 + bob, 110, -150 + bob), Path.Direction.CW);
        glow(c, halo, 0x33FFD54F, 30);
        sprite(c, sprites, CROWN_E, 0, -222 + bob, 185, 0, 255);
        for (int i = 0; i < 4; i++) {
            final float phase = (t / 1.3f + i / 4f) % 1f;
            final float a = (float) Math.sin(phase * Math.PI);
            final float x = -120 + i * 80, y = -300 + bob - (i % 2) * 26;
            c.drawPath(glint(x, y, 6 + 12 * a), fill(Color.argb((int) (255 * a), 255, 244, 200)));
        }
        c.restore();
    }

    private static void devil(Canvas c, Face f, float t) {
        c.save();
        c.translate(f.back, 0);
        final float flicker = 0.85f + 0.15f * (float) Math.sin(t * 9f);
        for (int s = -1; s <= 1; s += 2) {
            final Path horn = new Path();
            horn.moveTo(s * 30, -124);
            horn.cubicTo(s * 34, -170, s * 60, -212, s * 112, -232);
            horn.cubicTo(s * 92, -196, s * 92, -160, s * 96, -112);
            horn.close();
            glow(c, horn, Color.argb((int) (150 * flicker), 255, 45, 45), 18);
            final Paint red = new Paint(Paint.ANTI_ALIAS_FLAG);
            red.setShader(new LinearGradient(s * 40, -120, s * 100, -232, new int[]{0xFFB0001E, 0xFFFF3B30, 0xFFFF8A80}, null, Shader.TileMode.CLAMP));
            c.drawPath(horn, red);
            // ridges
            final Paint ridge = stroke(0x55600010, 2.5f);
            for (int k = 1; k <= 3; k++) {
                final float y = -124 - k * 24;
                c.drawLine(s * (32 + k * 6), y, s * (90 - k * 2), y + 4, ridge);
            }
            c.drawLine(s * 46, -140, s * 82, -206, stroke(0x55FFFFFF, 4));
        }
        c.restore();
    }

    private static void halo(Canvas c, Face f, float t) {
        final float bob = (float) Math.sin(t * 2f) * 7;
        c.save();
        c.translate(f.back, 0);
        final RectF ring = new RectF(-84, -266 + bob, 84, -226 + bob);
        final Paint glowPaint = stroke(0x80FFC107, 24);
        glowPaint.setMaskFilter(new BlurMaskFilter(16, BlurMaskFilter.Blur.NORMAL));
        c.drawOval(ring, glowPaint);
        final Paint gold = stroke(0, 11);
        gold.setShader(new LinearGradient(-84, 0, 84, 0, new int[]{0xFFFFB300, 0xFFFFF6C4, 0xFFFFC107, 0xFFFFF6C4, 0xFFFFB300}, null, Shader.TileMode.CLAMP));
        c.drawOval(ring, gold);
        c.drawOval(new RectF(ring.left + 6, ring.top + 3, ring.right - 6, ring.bottom - 3), stroke(0x99FFFFFF, 2.5f));
        for (int i = 0; i < 3; i++) {
            final float phase = (t / 1.6f + i / 3f) % 1f;
            final float a = (float) Math.sin(phase * Math.PI);
            final double ang = Math.PI * (0.15 + 0.35 * i);
            final float x = (float) Math.cos(ang) * 96 * (i == 1 ? -1 : 1), y = -246 + bob - 30 - a * 20;
            c.drawPath(glint(x, y, 5 + 10 * a), fill(Color.argb((int) (240 * a), 255, 250, 220)));
        }
        c.restore();
    }

    private static void flowers(Canvas c, Face f, Sprites sprites) {
        c.save();
        c.translate(f.back, 0);
        final String[] kinds = {BLOSSOM, DAISY, HIBISCUS};
        final int n = 7;
        // leaves under the flowers
        final Paint leaf = new Paint(Paint.ANTI_ALIAS_FLAG);
        leaf.setShader(new LinearGradient(0, -180, 0, -60, 0xFF7BC96F, 0xFF2E7D32, Shader.TileMode.CLAMP));
        for (int i = 0; i < n - 1; i++) {
            final double a = Math.toRadians(-165 + 150.0 * (i + 0.5) / (n - 1));
            final float x = (float) Math.cos(a) * 140, y = -40 + (float) Math.sin(a) * 132;
            c.save();
            c.translate(x, y);
            c.rotate((float) Math.toDegrees(a) + 90 + (i % 2 == 0 ? 25 : -25));
            final Path l = new Path();
            l.moveTo(0, 0);
            l.quadTo(16, -22, 0, -46);
            l.quadTo(-16, -22, 0, 0);
            l.close();
            c.drawPath(l, leaf);
            c.restore();
        }
        for (int i = 0; i < n; i++) {
            final double a = Math.toRadians(-165 + 150.0 * i / (n - 1));
            final float x = (float) Math.cos(a) * 138;
            final float y = -40 + (float) Math.sin(a) * 128;
            sprite(c, sprites, kinds[i % 3], x, y, i % 2 == 0 ? 64 : 52, (i - 3) * 9, 255);
        }
        c.restore();
    }

    private static void clown(Canvas c, Face f, float t) {
        // a rainbow wig, puffs round the top of the head
        c.save();
        c.translate(f.back, 0);
        final int[][] puffs = {{-152, -78, 0xFFFF5252}, {-142, -148, 0xFFFFC107}, {-97, -203, 0xFF40C4FF},
            {-32, -223, 0xFF69F0AE}, {33, -223, 0xFFFF4081}, {97, -203, 0xFFFFC107}, {142, -148, 0xFF40C4FF}, {152, -78, 0xFFFF5252}};
        for (int[] p : puffs) {
            final float r = 46;
            final Path puff = new Path();
            puff.addCircle(p[0], p[1], r, Path.Direction.CW);
            shadow(c, puff, 4, 8, 0x30);
            c.drawCircle(p[0], p[1], r, radial(p[0] - r * 0.35f, p[1] - r * 0.4f, r * 1.4f, lighten(p[2]), p[2]));
        }
        c.restore();
        cheeks(c, f.noseY + 14, 0x80FF5A7A);
        final float cx = f.noseX, cy = f.noseY - 4, r = 30 + 2 * (float) Math.sin(t * 5f);
        final Path nose = new Path();
        nose.addCircle(cx, cy, r, Path.Direction.CW);
        shadow(c, nose, 5, 8, 0x55);
        c.drawCircle(cx, cy, r, radial(cx - r * 0.35f, cy - r * 0.4f, r * 1.3f, 0xFFFF8A80, 0xFFC40000));
        c.drawOval(new RectF(cx - r * 0.55f, cy - r * 0.7f, cx - r * 0.05f, cy - r * 0.3f), fill(0xBFFFFFFF));
    }

    private static int lighten(int color) {
        return Color.rgb(Math.min(255, Color.red(color) + 70), Math.min(255, Color.green(color) + 70), Math.min(255, Color.blue(color) + 70));
    }

    private static void mustache(Canvas c, Face f) {
        final Path m = new Path();
        for (int s = -1; s <= 1; s += 2) {
            m.moveTo(0, 74);
            m.cubicTo(s * 26, 58, s * 56, 58, s * 72, 74);
            m.cubicTo(s * 84, 86, s * 102, 82, s * 106, 64);
            m.cubicTo(s * 112, 94, s * 72, 106, s * 48, 92);
            m.cubicTo(s * 30, 82, s * 12, 88, 0, 90);
            m.close();
        }
        c.save();
        // between the nose and the mouth, as wide as the face is
        final float y = (f.noseY + f.mouthY) / 2f;
        c.translate(f.noseX * 0.8f, y - 80 * 0.86f);
        c.scale(0.86f, 0.86f);
        shadow(c, m, 4, 6, 0x60);
        c.drawPath(m, vertical(56, 104, 0xFF4A3424, 0xFF1C120B));
        final Paint hair = stroke(0x30FFFFFF, 1.6f);
        for (int s = -1; s <= 1; s += 2) {
            for (int k = 0; k < 5; k++) {
                c.drawLine(s * (14 + k * 14), 76 + k, s * (22 + k * 14), 88 - k, hair);
            }
        }
        c.restore();
    }

    private static void stars(Canvas c, Face f, float t, Sprites sprites) {
        c.save();
        c.translate(f.back, 0);
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
                // a short trail behind each one
                for (int k = 1; k <= 3; k++) {
                    final double b = a - k * 0.12;
                    c.drawCircle((float) Math.cos(b) * 150, -175 + (float) Math.sin(b) * 34, 5 - k, fill(Color.argb(140 - k * 35, 255, 236, 150)));
                }
                sprite(c, sprites, i % 2 == 0 ? STAR : SPARKLES, x, y, size, (float) Math.toDegrees(a) * 0.2f, (int) (255 * (0.75f + 0.25f * depth)));
            }
        }
        c.restore();
    }

    // endregion
}
