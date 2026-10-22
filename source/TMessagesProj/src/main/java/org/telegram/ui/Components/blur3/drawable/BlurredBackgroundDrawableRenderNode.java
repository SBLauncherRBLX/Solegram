package org.telegram.ui.Components.blur3.drawable;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RenderNode;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.blur3.LiquidGlassEffect;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSource;

@RequiresApi(api = Build.VERSION_CODES.Q)
public class BlurredBackgroundDrawableRenderNode extends BlurredBackgroundDrawable {
    private final BlurredBackgroundSource source;
    private final Outline outline = new Outline();
    private final Rect outlineRect = new Rect();

    private final RenderNode renderNode;
    private final RenderNode renderNodeFill;

    private final Paint paintShadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintStrokeTop = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintStrokeBottom = new Paint(Paint.ANTI_ALIAS_FLAG);

    private boolean renderNodeInvalidated;

    public BlurredBackgroundDrawableRenderNode(BlurredBackgroundSource source) {
        this.renderNode = new RenderNode("BlurredNode");
        this.renderNodeFill = new RenderNode("BlurredFill");
        this.renderNode.setClipToOutline(true);
        this.renderNode.setClipToBounds(true);

        this.source = source;

        this.paintShadow.setColor(0);
        this.paintStrokeTop.setStyle(Paint.Style.STROKE);
        this.paintStrokeBottom.setStyle(Paint.Style.STROKE);
    }

    @Override
    public BlurredBackgroundDrawable setClipToOutline(boolean clipToOutline) {
        renderNode.setClipToOutline(clipToOutline);
        return super.setClipToOutline(clipToOutline);
    }

    private LiquidGlassEffect liquidGlassEffect;

    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    public void setLiquidGlassEffectAllowed() {
        liquidGlassEffect = new LiquidGlassEffect(renderNodeFill);
    }


    @Override
    public BlurredBackgroundSource getSource() {
        return source;
    }

    @Override
    protected void onBoundPropsChanged() {
        super.onBoundPropsChanged();

        paintStrokeTop.setStrokeWidth(boundProps.strokeWidthTop);
        paintStrokeBottom.setStrokeWidth(boundProps.strokeWidthBottom);

        outlineRect.set(0, 0,
            boundProps.boundsWithPadding.width(),
            boundProps.boundsWithPadding.height()
        );
        getOutline(outline, outlineRect, boundProps.radii);
        outline.setAlpha(1);

        if (!boundProps.boundsWithPadding.isEmpty()) {
            renderNodeFill.setPosition(0, 0, boundProps.boundsWithPadding.width(), boundProps.boundsWithPadding.height());
            renderNode.setPosition(0, 0, boundProps.boundsWithPadding.width(), boundProps.boundsWithPadding.height());
            renderNode.setOutline(outline);

            renderNodeInvalidated = true;
        }
    }

    @Override
    protected void onSourceOffsetChange(float sourceOffsetX, float sourceOffsetY) {
        super.onSourceOffsetChange(sourceOffsetX, sourceOffsetY);
        renderNodeInvalidated = true;
    }

    @Override
    public boolean hasDisplayList() {
        return renderNode.hasDisplayList();
    }

    @Override
    public void updateDisplayList() {
        final float offsetX = sourceOffsetX;
        final float offsetY = sourceOffsetY;

        Canvas c;

        final float sL = boundProps.boundsWithPadding.left + offsetX;
        final float sT = boundProps.boundsWithPadding.top + offsetY;
        final float sR = boundProps.boundsWithPadding.right + offsetX;
        final float sB = boundProps.boundsWithPadding.bottom + offsetY;

        c = renderNodeFill.beginRecording();
        c.save();
        c.translate(-sL, -sT);
        if (liquidGlassEffect != null && Build.VERSION.SDK_INT >= 33) {
            // Solegram: thickness, refraction and index follow the glass settings.
            final int minSide = Math.min(boundProps.boundsWithPadding.width(), boundProps.boundsWithPadding.height());
            final int thickness = Math.max((int) Math.min(
                com.solegram.messenger.SolegramGlass.thickness(boundProps.liquidThickness <= 0 ? dp(11) : boundProps.liquidThickness),
                com.solegram.messenger.SolegramGlass.thicknessLimit(minSide)), 1);

            liquidGlassEffect.update(
                0, 0, boundProps.boundsWithPadding.width(), boundProps.boundsWithPadding.height(),
                boundProps.shaderRadii[0], boundProps.shaderRadii[2], boundProps.shaderRadii[4], boundProps.shaderRadii[6],
                thickness,
                com.solegram.messenger.SolegramGlass.intensity(boundProps.liquidIntensity),
                com.solegram.messenger.SolegramGlass.index(boundProps.liquidIndex),
                backgroundColor
            );
        }
        source.draw(c, sL, sT, sR, sB);
        c.save();
        renderNodeFill.endRecording();


        c = renderNode.beginRecording();
        if (Color.alpha(backgroundColor) == 255) {
            c.drawColor(backgroundColor);
        } else {
            c.drawRenderNode(renderNodeFill);
            if (liquidGlassEffect == null && Color.alpha(backgroundColor) != 0) {
                c.drawColor(backgroundColor);
            }
        }
        if (strokeColorTop != 0 && !solegramStrokeHidden) { // Solegram: see solegramSetStrokeHidden
            drawStroke(c, 0, 0, boundProps.boundsWithPadding.width(),
                    boundProps.boundsWithPadding.height(), boundProps.radii,
                    boundProps.strokeWidthTop, true, paintStrokeTop);
        }
        if (strokeColorBottom != 0 && !solegramStrokeHidden) {
            drawStroke(c, 0, 0, boundProps.boundsWithPadding.width(),
                    boundProps.boundsWithPadding.height(), boundProps.radii,
                    boundProps.strokeWidthBottom, false, paintStrokeBottom);
        }
        renderNode.endRecording();
    }

    @Override
    public void updateColors() {
        super.updateColors();

        paintShadow.setShadowLayer(shadowLayerRadius, shadowLayerDx, shadowLayerDy, shadowColor);
        paintStrokeTop.setColor(strokeColorTop);
        paintStrokeBottom.setColor(strokeColorBottom);

        renderNodeInvalidated = true;
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        if (boundProps.boundsWithPadding.isEmpty()) {
            return;
        }

        if (!canvas.isHardwareAccelerated()) {
            drawSource(canvas, source);
            return;
        }

        if (!renderNode.hasDisplayList()) {
            source.dispatchOnDrawablesRelativePositionChange();
            updateDisplayList();
        } else if (renderNodeInvalidated) {
            updateDisplayList();
        }
        renderNodeInvalidated = false;

        if (solegramPreviewLook() && boundProps.padding > 0) {
            // the preview's soft floating shadow, kept inside the room (padding) around the element
            final int color = Theme.multAlpha(com.solegram.messenger.SolegramGlass.previewShadowColor(), renderNode.getAlpha() * shadowAlpha);
            if (Color.alpha(color) != 0) {
                // the whole soft edge stays inside that room: a shadow cut off by the element's bounds read as a dark box
                final float radius = Math.min(dp(10), boundProps.padding / 2.1f);
                paintShadow.setShadowLayer(radius, 0, radius * 0.3f, color);
                boundProps.drawShadows(canvas, paintShadow, inAppKeyboardOptimization);
            }
        } else {
            int color = Theme.multAlpha(shadowColor, renderNode.getAlpha() * shadowAlpha);
            if (Color.alpha(color) != 0) {
                paintShadow.setShadowLayer(shadowLayerRadius, shadowLayerDx, shadowLayerDy, color);
                boundProps.drawShadows(canvas, paintShadow, inAppKeyboardOptimization);
            }
        }

        canvas.save();
        canvas.translate(boundProps.boundsWithPadding.left, boundProps.boundsWithPadding.top);
        canvas.drawRenderNode(renderNode);
        canvas.restore();
    }

    // Solegram: the soft floating shadow of the glass preview for rounded glass

    /** Only floating, rounded glass that Telegram outlines or shadows itself; flat bars keep their look. */
    private boolean solegramPreviewLook() {
        if (!com.solegram.messenger.SolegramGlass.previewLook()) {
            return false;
        }
        if (inAppKeyboardOptimization || strokeColorTop == 0 && strokeColorBottom == 0 && Color.alpha(shadowColor) == 0) {
            return false;
        }
        // floating pills and buttons only: panels rounded at the top (keyboards, sheets) keep their look
        for (float r : boundProps.radii) {
            if (r < dp(6)) {
                return false;
            }
        }
        return true;
    }

    public void invalidateDisplayList() {
        renderNodeInvalidated = true;
    }

    @Override
    public void setAlpha(int alpha) {
        final int oldAlpha = getAlpha();

        super.setAlpha(alpha);
        renderNode.setAlpha(alpha / 255f);
        renderNodeInvalidated = true;

        if (oldAlpha == 0 && alpha > 0) {
            source.dispatchOnDrawablesRelativePositionChange();
        }
    }

    @Override
    protected void onSourceRelativePositionChanged(RectF position) {
        super.onSourceRelativePositionChanged(position);
        source.dispatchOnDrawablesRelativePositionChange();
    }
}
