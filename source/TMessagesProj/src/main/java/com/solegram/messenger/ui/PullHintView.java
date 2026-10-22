/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.View;

import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;

import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedFloat;
import org.telegram.ui.Components.CubicBezierInterpolator;

/**
 * A round bubble that slides in from the screen edge while the user pulls, like the "back" hint of
 * gesture navigation. Once the pull passes the commit point the bubble fills with the accent colour.
 */
public class PullHintView extends View {

    public static final float COMMIT = 0.33f;
    private static final int SIZE_DP = 48;

    private final Paint circlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Drawable icon;
    private final AnimatedFloat armed = new AnimatedFloat(this, 0, 220, CubicBezierInterpolator.EASE_OUT_QUINT);
    private float progress;

    public PullHintView(Context context, int iconRes) {
        super(context);
        icon = ContextCompat.getDrawable(context, iconRes).mutate();
        if (Build.VERSION.SDK_INT >= 28) {
            circlePaint.setShadowLayer(dp(6), 0, dp(2), 0x33000000);
        }
        setVisibility(GONE);
    }

    /** @param progress pull progress from 0 to 1, where {@link #COMMIT} is the point of no return */
    public void setProgress(float progress) {
        this.progress = progress;
        setVisibility(progress > 0.001f ? VISIBLE : GONE);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        // the bubble follows the finger until the commit point, then rests in place
        final float t = Math.min(1f, progress / COMMIT);
        final float eased = CubicBezierInterpolator.EASE_OUT.getInterpolation(t);
        final float a = armed.set(progress >= COMMIT);
        final float r = dp(SIZE_DP) / 2f * (0.55f + 0.45f * eased) * (1f + 0.08f * a * (1f - a) * 4f);
        final float cx = -dp(SIZE_DP) / 2f + (dp(SIZE_DP) / 2f + dp(14)) * eased + getPaddingLeft();
        final float cy = getHeight() / 2f;

        final int surface = Theme.getColor(Theme.key_windowBackgroundWhite);
        final int accent = Theme.getColor(Theme.key_featuredStickers_addButton);
        circlePaint.setColor(ColorUtils.blendARGB(surface, accent, a));
        circlePaint.setAlpha((int) (255 * Math.min(1f, t * 1.6f)));
        canvas.drawCircle(cx, cy, r, circlePaint);

        final int iconColor = ColorUtils.blendARGB(Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon), 0xFFFFFFFF, a);
        icon.setColorFilter(new PorterDuffColorFilter(iconColor, PorterDuff.Mode.SRC_IN));
        final int half = (int) (dp(12) * (0.6f + 0.4f * eased));
        icon.setBounds((int) cx - half, (int) cy - half, (int) cx + half, (int) cy + half);
        icon.setAlpha((int) (255 * t));
        icon.draw(canvas);
    }
}
