/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.soledrop;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.dpf2;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RecordingCanvas;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;

import com.solegram.messenger.ui.SolegramMotion;

import org.telegram.ui.ActionBar.Theme;

/**
 * Everything of SoleDrop's screen that is light rather than a view: the page with its sunset haze at the top
 * edge (where the phones touch), the breathing aura around the card, the ripple of the wave where the shader
 * wave is not available, and the light trail and sparks behind a card that flies.
 * <p>
 * All paints, shaders and arrays are made once (shaders again only when the size or the theme changes): a frame
 * only moves numbers.
 */
@SuppressLint("ViewConstructor")
public class SoleDropStage extends View {

    private final Theme.ResourcesProvider resourcesProvider;
    private final Paint bgPaint = new Paint();
    private final Paint hazePaint = new Paint();
    private final Paint auraPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sparkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix matrix = new Matrix();
    private final RectF rect = new RectF();
    private SweepGradient auraShader;
    private RenderNode auraNode;
    private RenderEffect auraEffect;
    private BlurMaskFilter auraBlur;
    private float auraBlurRadius;
    private int builtWidth, builtHeight, builtBg, builtAccent;
    private boolean dark;

    /** The card the aura breathes around (null: no aura) and how much of the aura shows (0..1). */
    @Nullable
    private View auraTarget;
    private float auraAlpha = 1f;
    private float auraBoost;
    private final long startTime = SystemClock.elapsedRealtime();

    /** The wave's progress 0..1 (-1: none), for the drawn ripple; the shader wave is {@link SoleDropWave}. */
    private float wave = -1;
    private boolean drawnRipple;

    // light trail and sparks: up to two emitters (a flying card, or both cards of a contact exchange)
    private static final int TRAIL = 16, SPARKS = 72;
    private final View[] emitters = new View[2];
    private final float[][] trailX = new float[2][TRAIL], trailY = new float[2][TRAIL], trailR = new float[2][TRAIL];
    private final int[] trailCount = new int[2], trailHead = new int[2];
    private final float[] sparkX = new float[SPARKS], sparkY = new float[SPARKS], sparkVx = new float[SPARKS], sparkVy = new float[SPARKS];
    private final float[] sparkLife = new float[SPARKS], sparkSize = new float[SPARKS];
    private final int[] sparkColor = new int[SPARKS];
    private int sparkNext;
    private long lastFrame;
    private boolean emitting;
    private final java.util.Random random = new java.util.Random();

    /** Changes whenever the picture under the glass changes (the glass records it again then). */
    public int glassVersion;

    public SoleDropStage(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        ringPaint.setStyle(Paint.Style.STROKE);
        auraPaint.setStyle(Paint.Style.STROKE);
        auraPaint.setStrokeCap(Paint.Cap.ROUND);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    public void setAuraTarget(@Nullable View target) {
        auraTarget = target;
        invalidate();
    }

    public void setAuraAlpha(float alpha) {
        if (auraAlpha != alpha) {
            auraAlpha = alpha;
            invalidate();
        }
    }

    /** A flash of the aura (the moment of contact), 0..1, fades by itself. */
    public void flashAura() {
        auraBoost = 1f;
        invalidate();
    }

    public void setWave(float progress) {
        wave = progress;
        glassVersion++;
        invalidate();
    }

    /** The light trail and sparks follow these views (null: none) while {@link #setEmitting} is on. */
    public void setEmitters(@Nullable View first, @Nullable View second) {
        emitters[0] = first;
        emitters[1] = second;
        trailCount[0] = trailCount[1] = 0;
    }

    public void setEmitting(boolean emitting) {
        this.emitting = emitting && SolegramMotion.rich();
        invalidate();
    }

    /** A burst of sparks around a point (a card that arrived). */
    public void burst(float x, float y, int count) {
        if (!SolegramMotion.rich()) {
            return;
        }
        for (int i = 0; i < count; i++) {
            final double a = random.nextDouble() * Math.PI * 2;
            final float speed = dp(80) + random.nextFloat() * dp(220);
            spawn(x, y, (float) Math.cos(a) * speed, (float) Math.sin(a) * speed);
        }
        invalidate();
    }

    private void spawn(float x, float y, float vx, float vy) {
        final int i = sparkNext;
        sparkNext = (sparkNext + 1) % SPARKS;
        sparkX[i] = x;
        sparkY[i] = y;
        sparkVx[i] = vx;
        sparkVy[i] = vy;
        sparkLife[i] = 1f;
        sparkSize[i] = dpf2(1.2f) + random.nextFloat() * dpf2(2.4f);
        sparkColor[i] = SoleDropPalette.SWEEP[random.nextInt(3)];
    }

    private void build(int w, int h) {
        final int bg = SoleDropPalette.background(resourcesProvider);
        dark = SoleDropPalette.dark(resourcesProvider);
        if (w == builtWidth && h == builtHeight && bg == builtBg && builtAccent == SoleDropPalette.PINK && auraShader != null) {
            return;
        }
        builtWidth = w;
        builtHeight = h;
        builtBg = bg;
        builtAccent = SoleDropPalette.PINK;
        bgPaint.setColor(bg);
        // the sunset sits at the top edge, where the other phone comes from, and dissolves into the page
        // only a breath of the accent at the very top, where the other phone comes from: the page stays Solegram's
        final float a = dark ? 0.14f : 0.08f;
        hazePaint.setShader(new LinearGradient(0, 0, 0, h * 0.42f,
            new int[]{
                ColorUtils.blendARGB(bg, SoleDropPalette.PINK, a),
                ColorUtils.blendARGB(bg, SoleDropPalette.ORANGE, a * 0.55f),
                ColorUtils.blendARGB(bg, SoleDropPalette.VIOLET, a * 0.35f),
                bg
            },
            new float[]{0f, 0.28f, 0.6f, 1f}, Shader.TileMode.CLAMP));
        auraShader = new SweepGradient(0, 0, SoleDropPalette.SWEEP, null);
        auraPaint.setShader(auraShader);
        glowPaint.setShader(new RadialGradient(0, 0, 1f,
            new int[]{0xFFFFFFFF, ColorUtils.setAlphaComponent(SoleDropPalette.PINK, 0x80), SoleDropPalette.PINK & 0x00FFFFFF},
            new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        glassVersion++;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        glassVersion++;
    }

    /** What lies under the glass: the page, the haze and the ripple (not the aura and sparks, which move all the time). */
    public void drawBackground(Canvas canvas) {
        final int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        build(w, h);
        canvas.drawRect(0, 0, w, h, bgPaint);
        canvas.drawRect(0, 0, w, h * 0.42f, hazePaint);
        drawRipple(canvas, w, h);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final long now = SystemClock.elapsedRealtime();
        final float dt = lastFrame == 0 ? 0.016f : Math.min(0.05f, (now - lastFrame) / 1000f);
        lastFrame = now;
        drawBackground(canvas);
        boolean animating = drawAura(canvas, now, dt);
        animating |= drawTrail(canvas);
        animating |= drawSparks(canvas, dt);
        if (animating && isAttachedToWindow()) {
            postInvalidateOnAnimation();
        } else {
            lastFrame = 0;
        }
    }

    // region Aura

    private boolean drawAura(Canvas canvas, long now, float dt) {
        final View target = auraTarget;
        if (target == null || target.getVisibility() != VISIBLE || auraAlpha <= 0f || target.getWidth() <= 0) {
            return false;
        }
        rect.set(0, 0, target.getWidth(), target.getHeight());
        target.getMatrix().mapRect(rect);
        rect.offset(target.getLeft(), target.getTop());
        final float scale = target.getScaleX();
        final boolean breathing = SolegramMotion.rich();
        final float t = (now - startTime) / 1000f;
        final float breath = breathing ? (float) (0.5 + 0.5 * Math.sin(t * Math.PI * 2 / 3.4)) : 0.6f;
        final float boost = auraBoost;
        if (auraBoost > 0) {
            auraBoost = Math.max(0, auraBoost - dt * 1.6f);
        }
        final float spread = (dp(16) + dp(8) * breath + dp(14) * boost) * scale;
        final float radius = dp(26) * scale;
        final float cx = rect.centerX(), cy = rect.centerY();
        matrix.setRotate(breathing ? (t * 24f) % 360f : 0f);
        matrix.postTranslate(cx, cy);
        auraShader.setLocalMatrix(matrix);
        final float base = (dark ? 0.46f : 0.34f) * (0.7f + 0.3f * breath) + 0.35f * boost;
        final int alpha = (int) (255 * Math.min(1f, base) * auraAlpha);
        // one soft band of light hugging the card, blurred as a whole: no rings, no steps
        final float band = spread * 0.9f;
        final float out = band * 0.5f;
        if (Build.VERSION.SDK_INT >= 31 && canvas.isHardwareAccelerated()) {
            drawAuraNode(canvas, rect, radius, band, out, spread, alpha);
        } else if (Build.VERSION.SDK_INT >= 28 && canvas.isHardwareAccelerated()) {
            final float blur = Math.max(1f, spread * 0.75f);
            if (auraBlur == null || Math.abs(auraBlurRadius - blur) > dp(2)) {
                auraBlurRadius = blur;
                auraBlur = new BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL);
            }
            auraPaint.setMaskFilter(auraBlur);
            auraPaint.setStrokeWidth(band);
            auraPaint.setAlpha(alpha);
            canvas.drawRoundRect(rect.left - out, rect.top - out, rect.right + out, rect.bottom + out, radius + out, radius + out, auraPaint);
            auraPaint.setMaskFilter(null);
        } else {
            // older phones: many thin rings with a gaussian falloff read as one soft glow
            final int layers = 18;
            for (int i = layers - 1; i >= 0; i--) {
                final float f = (i + 0.5f) / layers;
                final float inset = -spread * 1.3f * f;
                final float a = (float) Math.exp(-f * f * 4.5f) * 0.32f;
                auraPaint.setStrokeWidth(spread * 1.3f / layers + 1f);
                auraPaint.setAlpha((int) (alpha * a));
                canvas.drawRoundRect(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset,
                    radius - inset, radius - inset, auraPaint);
            }
        }
        return breathing || auraBoost > 0;
    }
    @android.annotation.TargetApi(31)
    private void drawAuraNode(Canvas canvas, RectF card, float radius, float band, float out, float spread, int alpha) {
        if (auraNode == null) {
            auraNode = new RenderNode("soledrop-aura");
        }
        final float blur = Math.max(1f, spread * 0.8f);
        if (auraEffect == null || Math.abs(auraBlurRadius - blur) > dp(1)) {
            auraBlurRadius = blur;
            auraEffect = RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.DECAL);
            auraNode.setRenderEffect(auraEffect);
        }
        // the node covers the card with room for the light and its blur on every side
        final float pad = out + band + blur * 3f;
        final int left = (int) Math.floor(card.left - pad), top = (int) Math.floor(card.top - pad);
        final int right = (int) Math.ceil(card.right + pad), bottom = (int) Math.ceil(card.bottom + pad);
        auraNode.setPosition(left, top, right, bottom);
        final RecordingCanvas c = auraNode.beginRecording(right - left, bottom - top);
        c.translate(-left, -top);
        auraPaint.setStrokeWidth(band);
        auraPaint.setAlpha(alpha);
        c.drawRoundRect(card.left - out, card.top - out, card.right + out, card.bottom + out, radius + out, radius + out, auraPaint);
        auraNode.endRecording();
        canvas.drawRenderNode(auraNode);
    }
    // endregion

    // region Ripple (where the shader wave is not drawn)

    private boolean rippleDrawn() {
        return wave >= 0f && wave < 1f && !SoleDropWave.shaderActive();
    }

    private void drawRipple(Canvas canvas, int w, int h) {
        drawnRipple = rippleDrawn();
        if (!drawnRipple) {
            return;
        }
        final float ox = w / 2f, oy = -dp(40);
        final float max = (float) Math.hypot(w / 2f, h + dp(40));
        // three rings, the first leading, each a soft band of the sunset that thins out as it goes
        for (int i = 0; i < 3; i++) {
            final float p = wave - i * 0.09f;
            if (p <= 0f) {
                continue;
            }
            final float eased = 1f - (1f - p) * (1f - p) * (1f - p);
            final float r = eased * max;
            final float fade = (1f - p) * (1f - i * 0.25f);
            final int color = SoleDropPalette.SWEEP[i];
            ringPaint.setColor(color);
            ringPaint.setStrokeWidth(dp(26) * (1f - p) + dp(2));
            ringPaint.setAlpha((int) (255 * 0.22f * fade));
            canvas.drawCircle(ox, oy, r, ringPaint);
            ringPaint.setStrokeWidth(dp(4) * (1f - p) + dpf2(1));
            ringPaint.setAlpha((int) (255 * 0.55f * fade));
            canvas.drawCircle(ox, oy, r, ringPaint);
        }
    }
    // endregion

    // region Trail and sparks

    private boolean drawTrail(Canvas canvas) {
        boolean any = false;
        for (int e = 0; e < 2; e++) {
            final View v = emitters[e];
            if (emitting && v != null && v.getVisibility() == VISIBLE && v.getAlpha() > 0.02f) {
                rect.set(0, 0, v.getWidth(), v.getHeight());
                v.getMatrix().mapRect(rect);
                rect.offset(v.getLeft(), v.getTop());
                final int head = trailHead[e];
                trailX[e][head] = rect.centerX();
                trailY[e][head] = rect.centerY();
                trailR[e][head] = Math.min(rect.width(), rect.height()) * 0.42f;
                trailHead[e] = (head + 1) % TRAIL;
                trailCount[e] = Math.min(TRAIL, trailCount[e] + 1);
                // sparks shed from the edges, drifting back along the path
                for (int s = 0; s < 3; s++) {
                    final double a = random.nextDouble() * Math.PI * 2;
                    final float r = trailR[e][head] * (0.6f + random.nextFloat() * 0.5f);
                    spawn(rect.centerX() + (float) Math.cos(a) * r, rect.centerY() + (float) Math.sin(a) * r,
                        (float) Math.cos(a) * dp(40), dp(60) + random.nextFloat() * dp(90));
                }
            } else if (trailCount[e] > 0) {
                trailCount[e]--; // the tail catches up with the head
            }
            final int count = trailCount[e];
            if (count <= 1) {
                continue;
            }
            any = true;
            for (int k = count - 1; k >= 1; k--) {
                final int idx = (trailHead[e] - 1 - k + TRAIL * 2) % TRAIL;
                final float f = 1f - k / (float) TRAIL;
                final float r = trailR[e][idx] * f;
                if (r <= 0.5f) {
                    continue;
                }
                // a wide coloured glow and a bright core, thinner and fainter towards the tail
                matrix.setScale(r * 1.6f, r * 1.6f);
                matrix.postTranslate(trailX[e][idx], trailY[e][idx]);
                glowPaint.getShader().setLocalMatrix(matrix);
                glowPaint.setAlpha((int) (255 * 0.20f * f * f));
                canvas.drawCircle(trailX[e][idx], trailY[e][idx], r * 1.6f, glowPaint);
            }
        }
        if (any) {
            glassVersion++;
        }
        return any || emitting;
    }

    private boolean drawSparks(Canvas canvas, float dt) {
        boolean any = false;
        for (int i = 0; i < SPARKS; i++) {
            if (sparkLife[i] <= 0f) {
                continue;
            }
            any = true;
            sparkLife[i] -= dt * 1.7f;
            if (sparkLife[i] <= 0f) {
                continue;
            }
            sparkVx[i] *= 0.95f;
            sparkVy[i] *= 0.95f;
            sparkX[i] += sparkVx[i] * dt;
            sparkY[i] += sparkVy[i] * dt;
            final float life = sparkLife[i];
            sparkPaint.setColor(sparkColor[i]);
            sparkPaint.setAlpha((int) (255 * Math.min(1f, life * 1.4f)));
            canvas.drawCircle(sparkX[i], sparkY[i], sparkSize[i] * (0.4f + 0.6f * life), sparkPaint);
            sparkPaint.setColor(0xFFFFFFFF);
            sparkPaint.setAlpha((int) (200 * life * life));
            canvas.drawCircle(sparkX[i], sparkY[i], sparkSize[i] * 0.45f * life, sparkPaint);
        }
        return any;
    }
    // endregion
}
