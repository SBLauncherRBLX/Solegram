/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.soledrop;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.dpf2;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

import androidx.core.graphics.ColorUtils;

import com.solegram.messenger.ui.SolegramMotion;

import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.CubicBezierInterpolator;

/**
 * "Hold the phones together", drawn: two phones lying top to top, the friend's one glides in, the edges touch,
 * light pulses out from where they meet and this phone's screen lights up, then it glides away again.
 * Without animations it shows the moment of contact.
 */
@SuppressLint("ViewConstructor")
public class SoleDropPhonesView extends View {

    private static final long CYCLE = 2800;

    private final Theme.ResourcesProvider resourcesProvider;
    private final Paint body = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint frame = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint screen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint notch = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final long start = SystemClock.elapsedRealtime();
    private int builtHeight, builtText;

    public SoleDropPhonesView(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        frame.setStyle(Paint.Style.STROKE);
        frame.setStrokeWidth(dpf2(2f));
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeCap(Paint.Cap.ROUND);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(dp(260), dp(96));
    }

    private void build() {
        final int text = SoleDropPalette.text(resourcesProvider);
        if (builtHeight == getHeight() && builtText == text) {
            return;
        }
        builtHeight = getHeight();
        builtText = text;
        final boolean dark = SoleDropPalette.dark(resourcesProvider);
        frame.setColor(ColorUtils.setAlphaComponent(text, dark ? 0xA0 : 0x90));
        notch.setColor(ColorUtils.setAlphaComponent(text, 0x70));
        body.setColor(SoleDropPalette.surface(resourcesProvider));
        screen.setColor(ColorUtils.setAlphaComponent(text, dark ? 0x14 : 0x0C));
        glow.setShader(new LinearGradient(0, 0, dp(84), 0,
            new int[]{SoleDropPalette.VIOLET, SoleDropPalette.ORANGE, SoleDropPalette.PINK}, null, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        build();
        final float w = getWidth(), h = getHeight();
        final boolean moving = SolegramMotion.rich();
        final float t = moving ? ((SystemClock.elapsedRealtime() - start) % CYCLE) / (float) CYCLE : 0.5f;
        // the friend's phone comes in, stays, goes back
        final float in;
        if (t < 0.32f) {
            in = CubicBezierInterpolator.EASE_BOTH.getInterpolation(t / 0.32f);
        } else if (t < 0.74f) {
            in = 1f;
        } else {
            in = 1f - CubicBezierInterpolator.EASE_BOTH.getInterpolation((t - 0.74f) / 0.26f);
        }
        final float contact = t >= 0.32f && t < 0.74f ? (t - 0.32f) / 0.42f : -1f;
        final float phoneW = dp(84), phoneH = dp(46);
        final float cy = h / 2f;
        final float gap = dp(30) * (1f - in);
        // the whole scene (this phone, the gap at its widest, the friend's phone) sits in the middle of the view,
        // so neither phone nor the light from the meeting edges is cut at a side
        final float meet = w / 2f - dp(15);

        // this phone: its top edge to the right, the screen lights up while touching
        final float light = contact >= 0f ? (float) Math.sin(Math.min(1f, contact * 1.6f) * Math.PI / 2) * (1f - Math.max(0f, contact - 0.7f) / 0.3f) : 0f;
        drawPhone(canvas, meet - phoneW - dpf2(1), cy, phoneW, phoneH, true, light);
        // the friend's phone: its top edge to the left
        drawPhone(canvas, meet + gap + dpf2(1), cy, phoneW, phoneH, false, 0f);

        if (contact >= 0f) {
            // light pulses out from the meeting edges, up and down
            for (int i = 0; i < 3; i++) {
                final float p = contact * 1.25f - i * 0.18f;
                if (p <= 0f || p >= 1f) {
                    continue;
                }
                final float r = dp(10) + dp(30) * p;
                arc.setStrokeWidth(dpf2(2.4f) * (1f - p) + dpf2(0.8f));
                arc.setColor(SoleDropPalette.SWEEP[i]);
                arc.setAlpha((int) (230 * (1f - p)));
                rect.set(meet - r, cy - r, meet + r, cy + r);
                canvas.drawArc(rect, -150, 120, false, arc);
                canvas.drawArc(rect, 30, 120, false, arc);
            }
        }
        if (moving && isAttachedToWindow() && getVisibility() == VISIBLE && getAlpha() > 0f) {
            postInvalidateOnAnimation();
        }
    }

    private void drawPhone(Canvas canvas, float left, float cy, float pw, float ph, boolean topRight, float light) {
        final float radius = dp(10);
        rect.set(left, cy - ph / 2f, left + pw, cy + ph / 2f);
        canvas.drawRoundRect(rect, radius, radius, body);
        // the screen
        final float inset = dp(4);
        rect.inset(inset, inset);
        canvas.drawRoundRect(rect, radius - inset, radius - inset, screen);
        if (light > 0f) {
            canvas.save();
            canvas.translate(rect.left, 0);
            glow.setAlpha((int) (255 * 0.85f * light));
            rect.offset(-rect.left, 0);
            canvas.drawRoundRect(rect, radius - inset, radius - inset, glow);
            canvas.restore();
        }
        rect.set(left, cy - ph / 2f, left + pw, cy + ph / 2f);
        canvas.drawRoundRect(rect, radius, radius, frame);
        // the camera notch at the top edge
        final float nx = topRight ? rect.right - dp(8) : rect.left + dp(8);
        rect.set(nx - dpf2(1.6f), cy - dp(6), nx + dpf2(1.6f), cy + dp(6));
        canvas.drawRoundRect(rect, dp(2), dp(2), notch);
    }
}
