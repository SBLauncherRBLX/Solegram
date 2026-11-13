/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.ui;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.SystemClock;

import com.solegram.messenger.SolegramStreak;

import org.telegram.messenger.LiteMode;
import org.telegram.ui.Components.CubicBezierInterpolator;

import java.util.ArrayList;
import java.util.Random;

/**
 * The streak's flame alive, for the big places (the streak sheet, the profile tile): it lights up from its base, a
 * soft halo breathes round it, its body and its core flicker each in their own time, embers rise from it and fade,
 * the magic fire throws little stars and the eternal one sparks round it. A streak at risk only smoulders.
 */
public class SolegramFlameRenderer {

    private static final class Ember {
        float x, y, vx, vy, life, maxLife, r, phase;
        int color;
        boolean star;
    }

    private final ArrayList<Ember> embers = new ArrayList<>();
    private final Random random = new Random();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint emberPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint haloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private RadialGradient halo;
    private int haloColor, haloSize;
    private long born, lastFrame;
    private float spawnDebt;

    /** A tap: a puff of embers. */
    public void burst() {
        spawnDebt += 14f;
        burstPending = true;
    }

    private boolean burstPending;

    /** Starts the lighting-up again (the sheet opened, the level changed). */
    public void ignite() {
        born = 0;
        embers.clear();
    }

    /**
     * Draws the flame of a streak standing on (baseX, baseY), {@code size} high, and returns whether it wants the
     * next frame (it always does while animations are on).
     */
    public boolean draw(Canvas canvas, float baseX, float baseY, int size, int color, int days, boolean atRisk, float grow) {
        if (size <= 0 || days <= 0) {
            return false;
        }
        final boolean animate = LiteMode.isEnabled(LiteMode.FLAGS_ANIMATED_EMOJI);
        final long now = SystemClock.elapsedRealtime();
        if (born == 0) {
            born = now;
            lastFrame = now;
        }
        final float t = (now - born) / 1000f;
        final float dt = Math.min(0.05f, (now - lastFrame) / 1000f);
        lastFrame = now;
        final int level = SolegramStreak.level(days);
        final int[] colors = SolegramStreak.flameColors(color, days, atRisk);
        final float cx = baseX, top = baseY - size;

        if (!animate) {
            canvas.drawBitmap(SolegramStreak.flameLayer(color, days, size, atRisk, SolegramStreak.LAYER_FULL), cx - size / 2f, top, paint);
            return false;
        }

        // lighting up: the flame grows out of its base with a little overshoot
        final float ignite = t < 0.7f ? CubicBezierInterpolator.EASE_OUT_BACK.getInterpolation(t / 0.7f) : 1f;
        final float scale = grow * Math.max(0.01f, ignite);

        // the halo, breathing
        final float haloR = size * 0.62f;
        if (halo == null || haloColor != colors[1] || haloSize != size) {
            haloColor = colors[1];
            haloSize = size;
            halo = new RadialGradient(0, 0, haloR, new int[]{(colors[1] & 0x00FFFFFF) | (atRisk ? 0x22000000 : 0x55000000), colors[1] & 0x00FFFFFF},
                null, Shader.TileMode.CLAMP);
        }
        final float breath = 0.82f + 0.18f * (float) Math.sin(t * 2.2f);
        canvas.save();
        canvas.translate(cx, baseY - size * 0.42f);
        canvas.scale(breath * scale, breath * scale);
        haloPaint.setShader(halo);
        canvas.drawCircle(0, 0, haloR, haloPaint);
        canvas.restore();

        // embers behind the flame's body
        if (!atRisk) {
            final float rate = new float[]{3f, 5f, 8f, 10f, 12f}[Math.min(4, level)];
            spawnDebt += rate * dt;
            while (spawnDebt >= 1f && embers.size() < 40) {
                spawnDebt -= 1f;
                spawn(cx, baseY, size, colors, level);
            }
            if (spawnDebt > 1f && !burstPending) {
                spawnDebt = 0f;
            }
            burstPending = false;
        }
        step(dt);
        drawEmbers(canvas, t);

        // the body: a slow sway and stretch from the base
        final float speed = atRisk ? 0.45f : 1f;
        canvas.save();
        canvas.scale(scale, scale, cx, baseY);
        SolegramStreak.flicker(canvas, t * speed, cx, baseY, 1f);
        canvas.drawBitmap(SolegramStreak.flameLayer(color, days, size, atRisk, SolegramStreak.LAYER_BODY), cx - size / 2f, top, paint);
        canvas.restore();

        // the core: quicker, taller licks of its own
        canvas.save();
        canvas.scale(scale, scale, cx, baseY);
        final float lick = 1f + (atRisk ? 0.02f : 0.07f) * (float) Math.sin(t * 7.3f * speed) + (atRisk ? 0f : 0.035f) * (float) Math.sin(t * 13.1f);
        final float lean = (atRisk ? 0.01f : 0.04f) * (float) Math.sin(t * 4.6f * speed + 1.1f);
        canvas.scale(1f - (lick - 1f) * 0.4f, lick, cx, baseY);
        canvas.skew(lean, 0);
        canvas.translate(-lean * baseY, 0);
        canvas.drawBitmap(SolegramStreak.flameLayer(color, days, size, atRisk, SolegramStreak.LAYER_CORE), cx - size / 2f, top, paint);
        canvas.restore();

        // the eternal flame's sparks twinkle and turn
        if (level >= 4 && !atRisk) {
            emberPaint.setColor(0xFF6FD3FF);
            final float[][] sparks = {{88, 14, 9, 0f}, {10, 30, 6, 1.7f}, {92, 86, 5, 3.1f}};
            final float unit = size / 100f;
            for (float[] sp : sparks) {
                final float tw = 0.55f + 0.45f * (float) Math.sin(t * 3.2f + sp[3]);
                final float x = cx - size / 2f + sp[0] * unit, y = top + sp[1] * unit;
                canvas.save();
                canvas.translate(x, y);
                canvas.rotate((t * 40f + sp[3] * 50f) % 360f);
                canvas.scale(tw * scale, tw * scale);
                emberPaint.setAlpha((int) (255 * (0.5f + 0.5f * tw)));
                SolegramStreak.spark(canvas, emberPaint, 0, 0, sp[2] * unit);
                canvas.restore();
            }
            emberPaint.setAlpha(255);
        }
        return true;
    }

    private void spawn(float cx, float baseY, int size, int[] colors, int level) {
        final Ember e = new Ember();
        e.x = cx + (random.nextFloat() - 0.5f) * size * 0.36f;
        e.y = baseY - size * (0.25f + random.nextFloat() * 0.25f);
        e.vx = (random.nextFloat() - 0.5f) * size * 0.25f;
        e.vy = -size * (0.45f + random.nextFloat() * 0.45f);
        e.maxLife = e.life = 0.8f + random.nextFloat() * 0.7f;
        e.r = size * (0.018f + random.nextFloat() * 0.022f);
        e.phase = random.nextFloat() * 6.28f;
        e.star = level >= 3 && random.nextInt(3) == 0;
        e.color = random.nextInt(3) == 0 ? colors[2] : random.nextBoolean() ? colors[0] : colors[1];
        if (level >= 4 && random.nextInt(3) == 0) {
            e.color = 0xFFFFFFFF;
        }
        embers.add(e);
    }

    private void step(float dt) {
        for (int i = embers.size() - 1; i >= 0; i--) {
            final Ember e = embers.get(i);
            e.life -= dt;
            if (e.life <= 0) {
                embers.remove(i);
                continue;
            }
            e.x += e.vx * dt;
            e.y += e.vy * dt;
            e.vy *= 0.985f;
        }
    }

    private void drawEmbers(Canvas canvas, float t) {
        for (Ember e : embers) {
            final float k = e.life / e.maxLife;
            final float wobble = (float) Math.sin(t * 6f + e.phase) * e.r * 1.5f;
            emberPaint.setColor(e.color);
            emberPaint.setAlpha((int) (255 * Math.min(1f, k * 1.6f)));
            final float r = e.r * (0.4f + 0.6f * k);
            if (e.star) {
                canvas.save();
                canvas.translate(e.x + wobble, e.y);
                canvas.rotate(t * 90f + e.phase * 57f);
                SolegramStreak.spark(canvas, emberPaint, 0, 0, r * 2.2f);
                canvas.restore();
            } else {
                canvas.drawCircle(e.x + wobble, e.y, r, emberPaint);
            }
        }
        emberPaint.setAlpha(255);
    }

    /** The plain picture, for places that do not animate. */
    public static Bitmap still(int color, int days, int size, boolean atRisk) {
        return SolegramStreak.flame(color, days, size, atRisk);
    }
}
