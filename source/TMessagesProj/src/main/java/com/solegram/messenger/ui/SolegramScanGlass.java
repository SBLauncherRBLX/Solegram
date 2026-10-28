/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.ui;

import static org.telegram.messenger.AndroidUtilities.dpf2;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import androidx.annotation.Nullable;

import com.solegram.messenger.SolegramGlass;

import org.telegram.messenger.camera.CameraView;
import org.telegram.messenger.utils.RenderNodeEffects;
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory;
import org.telegram.ui.Components.blur3.RenderNodeWithHash;
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundProvider;
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundProviderBuilder;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceRenderNode;
import org.telegram.ui.Components.chat.ViewPositionWatcher;

/**
 * The QR scanner's round buttons as the camera's: liquid glass that refracts what the camera sees (Android 13+
 * with liquid glass on), otherwise the camera's clean dark circles. The camera's picture is recorded into the
 * glass before every frame while the scanner is on screen.
 */
public final class SolegramScanGlass implements ViewTreeObserver.OnPreDrawListener, View.OnAttachStateChangeListener {

    /** Where the scanner's camera is now (it is made after the buttons). */
    public interface CameraSource {
        @Nullable
        CameraView camera();
    }

    private final ViewGroup root;
    private final CameraSource cameraSource;
    @Nullable
    private BlurredBackgroundSourceRenderNode source;
    @Nullable
    private BlurredBackgroundDrawableViewFactory factory;
    @Nullable
    private BlurredBackgroundProvider provider;
    private final Matrix tmpMatrix = new Matrix();
    private boolean listening;

    private SolegramScanGlass(ViewGroup root, CameraSource cameraSource) {
        this.root = root;
        this.cameraSource = cameraSource;
    }

    /** Gives the buttons the camera's glass (or its plain circles) and keeps the glass fed while the root is shown. */
    public static void apply(ViewGroup root, CameraSource camera, View... buttons) {
        final SolegramScanGlass glass = new SolegramScanGlass(root, camera);
        final boolean liquid = SolegramCameraUi.liquid();
        if (liquid) {
            glass.build();
        }
        for (View button : buttons) {
            if (button == null) {
                continue;
            }
            final Drawable body;
            if (liquid && glass.factory != null) {
                body = new LayerDrawable(new Drawable[]{glass.factory.create(button, glass.provider), new SolegramCameraUi.Sheen(0x2EFFFFFF, 0x0DFFFFFF)});
            } else {
                body = new LayerDrawable(new Drawable[]{new SolegramCameraUi.PlainPill(0x61232326, 0x2EFFFFFF), new SolegramCameraUi.Sheen(0x1FFFFFFF, 0)});
            }
            button.setBackground(body);
            SolegramMotion.pressable(button, SolegramMotion.PRESS_BUTTON);
        }
        if (liquid) {
            root.addOnAttachStateChangeListener(glass);
            if (root.isAttachedToWindow()) {
                glass.onViewAttachedToWindow(root);
            }
        }
    }

    private void build() {
        source = new BlurredBackgroundSourceRenderNode(null);
        source.setupRenderer(new RenderNodeWithHash.Renderer() {
            @Override
            public void renderNodeUpdateDisplayList(Canvas canvas) {
                drawPreview(canvas);
            }
        });
        final float radius = dpf2(9f) * SolegramGlass.blurScale();
        if (SolegramGlass.vibrancy()) {
            source.setBlur(radius, RenderNodeEffects.getSaturationX2RenderEffect());
        } else {
            source.setBlur(radius);
        }
        factory = new BlurredBackgroundDrawableViewFactory(source);
        factory.setSourceRootView(new ViewPositionWatcher(root), root);
        factory.setLiquidGlassEffectAllowed(true);
        provider = new BlurredBackgroundProviderBuilder(null)
            .setBackgroundColor((r, isDark) -> Color.argb(Math.round(255 * Math.max(0.22f, Math.min(0.42f, 0.36f * SolegramGlass.alpha(true) / 0.85f))), 0x5C, 0x5C, 0x63))
            .setStrokeColorTop(0x6BFFFFFF, 0x6BFFFFFF)
            .setStrokeColorBottom(0x24FFFFFF, 0x24FFFFFF)
            .setShadowColor(0x38000000, 0x38000000)
            .setShadowLayer(dpf2(6f), 0, dpf2(1.5f))
            .setStrokeWidth(dpf2(1f), dpf2(0.8f))
            .build();
    }

    @Override
    public void onViewAttachedToWindow(View v) {
        if (!listening) {
            listening = true;
            root.getViewTreeObserver().addOnPreDrawListener(this);
        }
    }

    @Override
    public void onViewDetachedFromWindow(View v) {
        if (listening) {
            listening = false;
            root.getViewTreeObserver().removeOnPreDrawListener(this);
        }
    }

    @Override
    public boolean onPreDraw() {
        if (source != null && root.getWidth() > 0 && root.getHeight() > 0) {
            source.setSize(root.getWidth(), root.getHeight());
            source.updateDisplayListIfNeeded();
        }
        return true;
    }

    private void drawPreview(Canvas canvas) {
        final CameraView camera = cameraSource.camera();
        if (camera == null || camera.getVisibility() != View.VISIBLE || camera.getParent() != root) {
            return;
        }
        final TextureView texture = camera.getTextureView();
        if (texture == null || texture.getParent() != camera || texture.getVisibility() != View.VISIBLE || !texture.isAvailable()) {
            return;
        }
        final int count = canvas.save();
        canvas.translate(camera.getX() + texture.getLeft(), camera.getY() + texture.getTop());
        final Matrix matrix = texture.getMatrix();
        if (!matrix.isIdentity()) {
            tmpMatrix.set(matrix);
            canvas.concat(tmpMatrix);
        }
        final float alpha = camera.getAlpha() * texture.getAlpha();
        if (alpha < 1f) {
            canvas.saveLayerAlpha(0, 0, texture.getWidth(), texture.getHeight(), (int) (255 * alpha));
        }
        texture.draw(canvas);
        canvas.restoreToCount(count);
    }
}
