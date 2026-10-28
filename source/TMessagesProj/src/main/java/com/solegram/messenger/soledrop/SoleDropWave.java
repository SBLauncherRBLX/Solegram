/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.soledrop;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.RenderEffect;
import android.graphics.RuntimeShader;
import android.os.Build;
import android.view.View;

import androidx.annotation.Nullable;

import com.solegram.messenger.ui.SolegramMotion;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LiteMode;
import org.telegram.messenger.R;
import org.telegram.ui.Components.CubicBezierInterpolator;

/**
 * The wave that runs over the whole screen from the top edge at the moment of contact. Android 13+: a runtime
 * shader over the screen's own picture (it refracts the cards and text under it, see soledrop_wave.agsl);
 * elsewhere, in power saving or when the shader cannot be made, {@link SoleDropStage} draws rings of light.
 * Without animations there is no wave at all.
 * <p>
 * A RenderEffect keeps the uniforms it was made with, so one is made per frame of the wave (about a second),
 * as Telegram's own ripple does; nothing else is allocated.
 */
public final class SoleDropWave {

    private static String source;
    private static boolean failed;
    private static int active;

    private final View target;
    private final SoleDropStage stage;
    @Nullable
    private Object shader; // RuntimeShader
    private ValueAnimator animator;

    public SoleDropWave(View target, SoleDropStage stage) {
        this.target = target;
        this.stage = stage;
    }

    /** The shader draws a wave right now (the stage then draws no rings). */
    static boolean shaderActive() {
        return active > 0;
    }

    private boolean useShader() {
        return Build.VERSION.SDK_INT >= 33 && !failed && SolegramMotion.rich() && LiteMode.isEnabled(LiteMode.FLAG_CHAT_BACKGROUND);
    }

    public void start() {
        cancel();
        if (!SolegramMotion.enabled()) {
            return;
        }
        final boolean shaderWave = useShader() && prepare();
        if (shaderWave) {
            active++;
        }
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(shaderWave ? 1150 : 950);
        animator.setInterpolator(CubicBezierInterpolator.DEFAULT);
        final boolean[] shaderOn = {shaderWave};
        animator.addUpdateListener(a -> {
            final float t = (float) a.getAnimatedValue();
            stage.setWave(t);
            if (shaderOn[0] && Build.VERSION.SDK_INT >= 33) {
                if (!apply(t)) {
                    shaderOn[0] = false;
                    active--;
                }
            }
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                stage.setWave(-1);
                if (shaderOn[0]) {
                    shaderOn[0] = false;
                    active--;
                    if (Build.VERSION.SDK_INT >= 31) {
                        target.setRenderEffect(null);
                    }
                }
            }
        });
        animator.start();
    }

    public void cancel() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
    }

    private boolean prepare() {
        if (Build.VERSION.SDK_INT < 33) {
            return false;
        }
        if (shader != null) {
            return true;
        }
        try {
            if (source == null) {
                source = AndroidUtilities.readRes(R.raw.soledrop_wave);
            }
            final RuntimeShader s = new RuntimeShader(source);
            s.setColorUniform("tint", SoleDropPalette.PINK);
            shader = s;
            return true;
        } catch (Throwable e) {
            FileLog.e(e);
            failed = true;
            return false;
        }
    }

    private boolean apply(float t) {
        if (Build.VERSION.SDK_INT < 33 || shader == null) {
            return false;
        }
        try {
            final RuntimeShader s = (RuntimeShader) shader;
            final int w = target.getWidth(), h = target.getHeight();
            if (w <= 0 || h <= 0) {
                return false;
            }
            final float originY = -dp(40);
            final float max = (float) Math.hypot(w / 2f, h - originY) + dp(120);
            // fast at the edge where the phones touch, slowing as it spreads, like water
            final float eased = 1f - (float) Math.pow(1f - t, 2.2);
            s.setFloatUniform("origin", w / 2f, originY);
            s.setFloatUniform("radius", eased * max);
            s.setFloatUniform("band", dp(46) + dp(30) * t);
            s.setFloatUniform("amp", dp(26) * (float) Math.pow(1f - t, 1.3));
            s.setFloatUniform("glow", 1f - t * t);
            s.setColorUniform("tint", androidx.core.graphics.ColorUtils.blendARGB(SoleDropPalette.PINK, SoleDropPalette.VIOLET, t));
            target.setRenderEffect(RenderEffect.createRuntimeShaderEffect(s, "content"));
            return true;
        } catch (Throwable e) {
            FileLog.e(e);
            failed = true;
            target.setRenderEffect(null);
            return false;
        }
    }
}
