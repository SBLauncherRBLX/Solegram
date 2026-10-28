/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.soledrop;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.dpf2;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.solegram.messenger.SolegramGlass;

import org.telegram.messenger.LiteMode;
import org.telegram.messenger.utils.RenderNodeEffects;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory;
import org.telegram.ui.Components.blur3.RenderNodeWithHash;
import org.telegram.ui.Components.blur3.capture.IBlur3Hash;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundProvider;
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundProviderBuilder;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceRenderNode;
import org.telegram.ui.Components.chat.ViewPositionWatcher;

/**
 * The glass of SoleDrop's bubbles and buttons: the app's own glass (blur3, as the call sheet and the tab bar
 * use) over a live picture of the stage, liquid where the user turned liquid glass on (Android 13+), frosted on
 * Android 12; older phones get a plain veil with a light rim. Density, blur, tint, outline and shadow follow the
 * glass settings.
 */
final class SoleDropGlass {

    @Nullable
    private final BlurredBackgroundSourceRenderNode source;
    @Nullable
    private final BlurredBackgroundDrawableViewFactory factory;
    private final SoleDropStage stage;
    private final ViewGroup root;
    private final Theme.ResourcesProvider resourcesProvider;
    private final BlurredBackgroundProvider provider;

    SoleDropGlass(ViewGroup root, SoleDropStage stage, Theme.ResourcesProvider resourcesProvider) {
        this.root = root;
        this.stage = stage;
        this.resourcesProvider = resourcesProvider;
        final boolean liquid = LiteMode.isEnabled(LiteMode.FLAG_LIQUID_GLASS);
        provider = new BlurredBackgroundProviderBuilder(resourcesProvider)
            .setBackgroundColor((r, isDark) -> {
                final float alpha = SolegramGlass.alpha(LiteMode.isEnabled(LiteMode.FLAG_LIQUID_GLASS));
                return Theme.multAlpha(isDark ? 0xFF2A2A31 : 0xFFFFFFFF, (isDark ? 0.5f : 0.62f) * alpha);
            })
            .setStrokeColorTop(SolegramGlass.MENU_RIM_TOP_LIGHT, SolegramGlass.MENU_RIM_TOP_DARK)
            .setStrokeColorBottom(SolegramGlass.MENU_LOW_EDGE_LIGHT, SolegramGlass.MENU_RIM_BOTTOM_DARK)
            .setShadowColor(SolegramGlass.MENU_SHADOW_LIGHT, SolegramGlass.MENU_SHADOW_DARK)
            .setShadowLayer(dpf2(8f), 0, dpf2(2f))
            .setStrokeWidth(dpf2(1f), dpf2(0.8f))
            .build();
        if (Build.VERSION.SDK_INT >= 31) {
            source = new BlurredBackgroundSourceRenderNode(null);
            source.setupRenderer(new RenderNodeWithHash.Renderer() {
                @Override
                public void renderNodeUpdateDisplayList(Canvas canvas) {
                    stage.drawBackground(canvas);
                }

                @Override
                public void renderNodeCalculateHash(IBlur3Hash hash) {
                    hash.add(stage.glassVersion);
                    hash.add(stage.getWidth());
                    hash.add(stage.getHeight());
                }
            });
            // as the app's other glass sources: liquid glass follows the "blur under glass" setting (the lens
            // refracts the picture), frosted glass keeps its full blur so what is on it stays readable
            final float radius = liquid ? dpf2(30f) * SolegramGlass.blurScale() : dpf2(40f);
            if (radius >= 0.5f) {
                if (!liquid || SolegramGlass.vibrancy()) {
                    source.setBlur(radius, RenderNodeEffects.getSaturationX3RenderEffect());
                } else {
                    source.setBlur(radius);
                }
            }
            factory = new BlurredBackgroundDrawableViewFactory(source);
            factory.setSourceRootView(new ViewPositionWatcher(root), root);
            factory.setLiquidGlassEffectAllowed(liquid);
        } else {
            source = null;
            factory = null;
        }
    }

    /** Glass behind a view; {@code padding} is room around it for the shadow (the view's own padding grows by it). */
    Drawable create(View view, float radius, int padding) {
        if (factory != null) {
            final BlurredBackgroundDrawable drawable = factory.create(view, provider);
            drawable.setRadius(radius);
            drawable.setPadding(padding);
            return drawable;
        }
        return new Veil(radius, padding, resourcesProvider);
    }

    /** Before every frame: the picture the glass shows (recorded again only when the stage changed). */
    void sync() {
        if (source == null || stage.getWidth() <= 0) {
            return;
        }
        source.setSize(root.getWidth(), root.getHeight());
        source.updateDisplayListIfNeeded();
    }

    /** The plain look: a rounded veil with a light rim and a soft shadow. */
    static final class Veil extends Drawable {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final float radius;
        private final int padding;
        private final Theme.ResourcesProvider resourcesProvider;
        private int alpha = 255;

        Veil(float radius, int padding, Theme.ResourcesProvider resourcesProvider) {
            this.radius = radius;
            this.padding = padding;
            this.resourcesProvider = resourcesProvider;
            rim.setStyle(Paint.Style.STROKE);
            rim.setStrokeWidth(dpf2(1f));
        }

        @Override
        public boolean getPadding(@NonNull android.graphics.Rect out) {
            out.set(padding, padding, padding, padding);
            return padding > 0;
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            rect.set(getBounds());
            rect.inset(padding, padding);
            final float r = Math.min(radius, Math.min(rect.width(), rect.height()) / 2f);
            final boolean dark = SoleDropPalette.dark(resourcesProvider);
            if (padding > 0) {
                shadow.setColor(0);
                shadow.setShadowLayer(Math.min(dp(8), padding / 1.6f), 0, dp(2), Theme.multAlpha(dark ? 0x60000000 : 0x26000000, alpha / 255f));
                canvas.drawRoundRect(rect, r, r, shadow);
            }
            fill.setColor(Theme.multAlpha(SoleDropPalette.veil(resourcesProvider), alpha / 255f));
            canvas.drawRoundRect(rect, r, r, fill);
            rim.setColor(Theme.multAlpha(SoleDropPalette.rim(resourcesProvider), alpha / 255f));
            canvas.drawRoundRect(rect, r, r, rim);
        }

        @Override
        public void setAlpha(int alpha) {
            this.alpha = alpha;
            invalidateSelf();
        }

        @Override
        public int getAlpha() {
            return alpha;
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
