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
        draw(canvas, actionBar, width, color, alpha, null);
    }

    private SolegramEdgeBlur blur;
    private View[] siblings = new View[0];

    /**
     * Same, with the views to blur under the header given (null: the header's siblings drawn before it). On
     * Android 12+ the list blurs out of focus under the header; the plain fade stays for older versions.
     * The canvas is in the coordinates of the header's parent.
     */
    public void draw(Canvas canvas, View actionBar, int width, int color, float alpha, @Nullable View[] contents) {
        if (alpha <= 0f || actionBar == null || width <= 0) {
            return;
        }
        final int height = actionBar.getMeasuredHeight();
        if (height <= 0) {
            return;
        }
        if (SolegramEdgeBlur.supported(canvas)) {
            View[] views = contents;
            if (views == null && actionBar.getParent() instanceof ViewGroup) {
                final ViewGroup parent = (ViewGroup) actionBar.getParent();
                final int index = parent.indexOfChild(actionBar);
                if (siblings.length != Math.max(0, index)) {
                    siblings = new View[Math.max(0, index)];
                }
                for (int i = 0; i < siblings.length; i++) {
                    siblings[i] = parent.getChildAt(i);
                }
                views = siblings;
            }
            if (blur == null) {
                blur = new SolegramEdgeBlur();
            }
            final boolean drawn = blur.draw(canvas, views, width, actionBar.getY(), height, true, color, 0, Utilities.clamp(alpha, 1f, 0f));
            if (views == siblings) {
                java.util.Arrays.fill(siblings, null);
            }
            if (drawn) {
                return;
            }
        }
        // no blur here (Android 11 and older, or a software canvas such as a glass capture): nothing at all
        // rather than a darkening
    }
}
