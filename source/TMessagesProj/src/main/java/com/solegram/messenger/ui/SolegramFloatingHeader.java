/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.ui;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;

import com.solegram.messenger.SolegramConfig;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LiteMode;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory;
import org.telegram.ui.Components.blur3.drawable.color.impl.BlurredBackgroundProviderImpl;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSource;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceColor;
import org.telegram.ui.Components.chat.ViewPositionWatcher;

/**
 * Solegram "floating header" for the main screens: no panel behind the title - the list goes on under it
 * and fades into the background towards the top of the screen (as it does under the tab bar at the bottom),
 * and the header's buttons sit in a bubble of the app's glass.
 */
public class SolegramFloatingHeader {

    public static boolean enabled() {
        return SolegramConfig.floatingHeader;
    }

    /**
     * Glass for the header's bubbles that sees what scrolls under the header.
     *
     * @param glass    the screen's glass source (the picture of its list), null where there is none (Android 11-)
     * @param fallback colour of the glass without a picture
     * @param root     the screen's root view; the bubbles follow their position in it
     */
    public static BlurredBackgroundDrawableViewFactory factory(@Nullable BlurredBackgroundSource glass, int fallback, ViewGroup root) {
        final BlurredBackgroundSource source;
        if (glass != null) {
            source = glass;
        } else {
            final BlurredBackgroundSourceColor color = new BlurredBackgroundSourceColor();
            color.setColor(fallback);
            source = color;
        }
        final BlurredBackgroundDrawableViewFactory factory = new BlurredBackgroundDrawableViewFactory(source);
        factory.setLiquidGlassEffectAllowed(LiteMode.isEnabled(LiteMode.FLAG_LIQUID_GLASS));
        factory.setSourceRootView(new ViewPositionWatcher(root), root);
        return factory;
    }

    /** The back button and the menu in bubbles of that glass; the title stays plain text. */
    public static void setupButtons(ActionBar actionBar, BlurredBackgroundDrawableViewFactory factory, Theme.ResourcesProvider resourcesProvider) {
        actionBar.solegramSetupButtonsGlass(factory, BlurredBackgroundProviderImpl.topPanel(resourcesProvider));
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint solidPaint = new Paint();

    /** A plain panel behind the header (the search keeps one, its results would show through the title row). */
    public void drawSolid(Canvas canvas, View actionBar, int width, int color, float alpha) {
        if (alpha <= 0f || actionBar == null || width <= 0) {
            return;
        }
        solidPaint.setColor(color);
        solidPaint.setAlpha((int) (Color.alpha(color) * Utilities.clamp(alpha, 1f, 0f)));
        canvas.drawRect(0, actionBar.getY(), width, actionBar.getY() + actionBar.getMeasuredHeight(), solidPaint);
    }
    private int color, height, status;
    private boolean hasShader;

    /**
     * The fade: nearly solid under the status bar, then down to nothing at the bottom of the header.
     *
     * @param actionBar the header; the fade covers it from its top to its bottom
     * @param color     the screen's background colour (darkens in a dark theme, lightens in a light one)
     */
    public void draw(Canvas canvas, View actionBar, int width, int color, float alpha) {
        if (alpha <= 0f || actionBar == null || width <= 0) {
            return;
        }
        final int height = actionBar.getMeasuredHeight();
        if (height <= 0) {
            return;
        }
        final int status = actionBar instanceof ActionBar && ((ActionBar) actionBar).getOccupyStatusBar() ? AndroidUtilities.statusBarHeight : 0;
        if (!hasShader || this.color != color || this.height != height || this.status != status) {
            this.color = color;
            this.height = height;
            this.status = status;
            hasShader = true;
            final float statusPart = Utilities.clamp(status / (float) height, 0.6f, 0f);
            final float rest = 1f - statusPart;
            paint.setShader(new LinearGradient(0, 0, 0, height,
                new int[]{
                    ColorUtils.setAlphaComponent(color, 0xF2),
                    ColorUtils.setAlphaComponent(color, 0xE0),
                    ColorUtils.setAlphaComponent(color, 0xA8),
                    ColorUtils.setAlphaComponent(color, 0x50),
                    ColorUtils.setAlphaComponent(color, 0)
                },
                new float[]{0f, statusPart, statusPart + rest * 0.4f, statusPart + rest * 0.72f, 1f},
                Shader.TileMode.CLAMP));
        }
        paint.setAlpha((int) (255 * Utilities.clamp(alpha, 1f, 0f)));
        canvas.save();
        canvas.translate(0, actionBar.getY());
        canvas.drawRect(0, 0, width, height, paint);
        canvas.restore();
    }
}
