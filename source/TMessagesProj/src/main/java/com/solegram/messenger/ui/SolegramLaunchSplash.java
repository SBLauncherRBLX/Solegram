package com.solegram.messenger.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

import androidx.core.graphics.ColorUtils;
import androidx.core.graphics.PathParser;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;

/**
 * Solegram: the launch animation in the theme's colours. Android draws its own splash screen before the app runs,
 * from static resources, so it only shows a background there (the nearest of a light, a dark and a black one, picked
 * for the next launch on Android 13+); this view then plays the Solegram mark on the theme's background in its
 * accent colour, a little muted - the sun rising from behind its horizon, the message lines opening out - and fades
 * away over the app, which loads under it.
 */
@SuppressLint("ViewConstructor")
public final class SolegramLaunchSplash extends View {

    // the mark, in the 108-unit canvas of the launcher icon
    private static final String SUN = "M35.4,55.395 A18.6,18.6 0 0 1 72.6,55.395 A1.24,1.24 0 0 1 71.36,56.635 L36.64,56.635 A1.24,1.24 0 0 1 35.4,55.395 Z";
    private static final String LINE_TOP = "M38.345,59.115 L69.655,59.115 A2.325,2.325 0 0 1 69.655,63.765 L38.345,63.765 A2.325,2.325 0 0 1 38.345,59.115 Z";
    private static final String LINE_BOTTOM = "M45.165,66.555 L62.835,66.555 A2.325,2.325 0 0 1 62.835,71.205 L45.165,71.205 A2.325,2.325 0 0 1 45.165,66.555 Z";
    private static final float HORIZON = 56.7f;

    private static final long DURATION = 720, HOLD = 80, FADE = 260;

    /** Shows the launch animation over the activity on a plain launch from the launcher (not a restore, not a share). */
    public static void maybeShow(Activity activity, Bundle savedInstanceState) {
        try {
            pickSystemSplash(activity);
            if (savedInstanceState != null || activity.getWindow() == null) {
                return;
            }
            final Intent intent = activity.getIntent();
            if (intent == null || !Intent.ACTION_MAIN.equals(intent.getAction()) || intent.getCategories() == null
                    || !intent.getCategories().contains(Intent.CATEGORY_LAUNCHER)) {
                return;
            }
            final View decor = activity.getWindow().getDecorView();
            if (!(decor instanceof ViewGroup)) {
                return;
            }
            ((ViewGroup) decor).addView(new SolegramLaunchSplash(activity), new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /** Android 13+: the system splash of the next launch gets the background nearest to the theme's. */
    private static void pickSystemSplash(Activity activity) {
        if (Build.VERSION.SDK_INT < 33) {
            return;
        }
        final int bg = Theme.getColor(Theme.key_windowBackgroundWhite) | 0xFF000000;
        final double l = ColorUtils.calculateLuminance(bg);
        final int style = l > 0.45 ? R.style.SolegramSplash_Light : l < 0.012 ? R.style.SolegramSplash_Black : R.style.SolegramSplash_Dark;
        activity.getSplashScreen().setSplashScreenTheme(style);
    }

    private final Path sun, lineTop, lineBottom;
    private final Paint sunPaint = new Paint(Paint.ANTI_ALIAS_FLAG), linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int background;
    private final Interpolator ease = new PathInterpolator(0.4f, 0f, 0.2f, 1f);
    private long start = -1;

    private SolegramLaunchSplash(Context context) {
        super(context);
        sun = PathParser.createPathFromPathData(SUN);
        lineTop = PathParser.createPathFromPathData(LINE_TOP);
        lineBottom = PathParser.createPathFromPathData(LINE_BOTTOM);
        background = Theme.getColor(Theme.key_windowBackgroundWhite) | 0xFF000000;
        final int accent = Theme.getColor(Theme.key_chats_actionBackground) | 0xFF000000;
        // the accent, a little muted: less saturated and a touch towards the background
        final float[] hsl = new float[3];
        ColorUtils.colorToHSL(accent, hsl);
        hsl[1] *= 0.78f;
        final int muted = ColorUtils.blendARGB(ColorUtils.HSLToColor(hsl), background, 0.14f);
        sunPaint.setColor(muted);
        linePaint.setColor(ColorUtils.blendARGB(muted, background, 0.38f));
        setClickable(true);
    }

    private float phase(long t, long from, long length) {
        return ease.getInterpolation(Math.max(0f, Math.min(1f, (t - from) / (float) length)));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final long now = SystemClock.uptimeMillis();
        if (start < 0) {
            start = now;
        }
        final long t = now - start;
        final float fade = 1f - Math.max(0f, Math.min(1f, (t - DURATION - HOLD) / (float) FADE));
        if (fade <= 0f) {
            post(() -> {
                if (getParent() instanceof ViewGroup) {
                    ((ViewGroup) getParent()).removeView(this);
                }
            });
            return;
        }
        if (fade < 1f) {
            setClickable(false); // the app takes the touches back while the splash fades
        }
        final int count = canvas.saveLayerAlpha(0, 0, getWidth(), getHeight(), (int) (255 * fade));
        canvas.drawColor(background);

        // the mark about 96dp wide (as the system splash shows it), centred; it settles from 0.82 to full size
        final float unit = dp(96) / 37.2f;
        final float pop = 0.82f + 0.18f * phase(t, 40, 560);
        canvas.translate(getWidth() / 2f, getHeight() / 2f);
        canvas.scale(unit * pop, unit * pop);
        canvas.translate(-54f, -54f);

        // the sun rises from behind its horizon
        final float rise = 21f * (1f - phase(t, 60, 520));
        final float sunAlpha = Math.max(0f, Math.min(1f, (t - 60) / 160f));
        canvas.save();
        canvas.clipRect(0, 0, 108, HORIZON);
        canvas.translate(0, rise);
        sunPaint.setAlpha((int) (255 * sunAlpha));
        canvas.drawPath(sun, sunPaint);
        canvas.restore();

        // the lines open out from the middle, one after the other
        drawLine(canvas, lineTop, 61.44f, phase(t, 240, 380));
        drawLine(canvas, lineBottom, 68.88f, phase(t, 330, 370));

        canvas.restoreToCount(count);
        invalidate();
    }

    private void drawLine(Canvas canvas, Path line, float pivotY, float scale) {
        if (scale <= 0f) {
            return;
        }
        canvas.save();
        canvas.scale(scale, 1f, 54f, pivotY);
        canvas.drawPath(line, linePaint);
        canvas.restore();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return isClickable() || super.onTouchEvent(event);
    }
}
