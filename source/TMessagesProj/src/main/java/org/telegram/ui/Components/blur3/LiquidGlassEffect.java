package org.telegram.ui.Components.blur3;

import android.graphics.Color;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.RuntimeShader;

import androidx.annotation.RequiresApi;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;

@RequiresApi(api = 33)
public class LiquidGlassEffect {

    private final RenderNode node;
    private final RuntimeShader shader;
    private RenderEffect effect;
    // Solegram: extended shader (dispersion, rim light); Telegram's shader is the fallback.
    private final boolean extended;
    private float dispersion = -1, rim = -1;

    public LiquidGlassEffect(RenderNode node) {
        this.node = node;
        RuntimeShader s = null;
        try {
            s = new RuntimeShader(AndroidUtilities.readRes(R.raw.solegram_glass_shader));
        } catch (Throwable e) {
            FileLog.e(e);
        }
        extended = s != null;
        if (s == null) {
            s = new RuntimeShader(AndroidUtilities.readRes(R.raw.liquid_glass_shader));
        }
        shader = s;
        if (extended) {
            shader.setFloatUniform("dispersion", 0);
            shader.setFloatUniform("rim", 0);
        }
        node.setRenderEffect(effect = RenderEffect.createRuntimeShaderEffect(shader, "img"));
    }

    private float resolutionX, resolutionY;
    private float centerX, centerY;
    private float sizeX, sizeY;
    private float radiusLeftTop;
    private float radiusRightTop;
    private float radiusRightBottom;
    private float radiusLeftBottom;
    private float thickness;
    private float intensity;
    private float index;
    private int foregroundColor;

    public void update(
        float left, float top, float right, float bottom,
        float radiusLeftTop, float radiusRightTop, float radiusRightBottom, float radiusLeftBottom,

        float thickness,
        float intensity,
        float index,
        int foregroundColor
    ) {
        float resolutionX = node.getWidth();
        float resolutionY = node.getHeight();
        float centerX = (left + right) / 2;
        float centerY = (top + bottom) / 2;
        float width = right - left, height = bottom - top;
        float sizeX = width / 2;
        float sizeY = height / 2;

        if (radiusLeftTop + radiusLeftBottom > height) {
            float a = radiusLeftTop / (radiusLeftTop + radiusLeftBottom);
            radiusLeftTop = height * a;
            radiusLeftBottom = height * (1.0f - a);
        }
        if (radiusRightTop + radiusRightBottom > height) {
            float a = radiusRightTop / (radiusRightTop + radiusRightBottom);
            radiusRightTop = height * a;
            radiusRightBottom = height * (1.0f - a);
        }

        final float dispersion = extended ? com.solegram.messenger.SolegramGlass.dispersion() : 0;
        final float rim = extended ? com.solegram.messenger.SolegramGlass.rim() : 0;
        if (
            Math.abs(this.dispersion - dispersion) > 0.001f ||
            Math.abs(this.rim - rim) > 0.001f ||
            Math.abs(this.resolutionX - resolutionX) > 0.1f ||
            Math.abs(this.resolutionY - resolutionY) > 0.1f ||
            Math.abs(this.centerX - centerX) > 0.1f ||
            Math.abs(this.centerY - centerY) > 0.1f ||
            Math.abs(this.sizeX - sizeX) > 0.1f ||
            Math.abs(this.sizeY - sizeY) > 0.1f ||
            Math.abs(this.radiusLeftTop - radiusLeftTop) > 0.1f ||
            Math.abs(this.radiusRightTop - radiusRightTop) > 0.1f ||
            Math.abs(this.radiusRightBottom - radiusRightBottom) > 0.1f ||
            Math.abs(this.radiusLeftBottom - radiusLeftBottom) > 0.1f ||
            Math.abs(this.thickness - thickness) > 0.1f ||
            Math.abs(this.intensity - intensity) > 0.1f ||
            Math.abs(this.index - index) > 0.1f ||
            this.foregroundColor != foregroundColor
        ) {
            this.foregroundColor = foregroundColor;

            final float a = Color.alpha(foregroundColor) / 255f;
            final float r = Color.red(foregroundColor) / 255f * a;
            final float g = Color.green(foregroundColor) / 255f * a;
            final float b = Color.blue(foregroundColor) / 255f * a;

            shader.setFloatUniform("resolution", this.resolutionX = resolutionX, this.resolutionY = resolutionY);
            shader.setFloatUniform("center", this.centerX = centerX, this.centerY = centerY);
            shader.setFloatUniform("size", this.sizeX = sizeX, this.sizeY = sizeY);
            shader.setFloatUniform("radius", this.radiusRightBottom = radiusRightBottom, this.radiusRightTop = radiusRightTop, this.radiusLeftBottom = radiusLeftBottom, this.radiusLeftTop = radiusLeftTop);
            shader.setFloatUniform("thickness", this.thickness = thickness);
            shader.setFloatUniform("refract_intensity", this.intensity = intensity);
            shader.setFloatUniform("refract_index", this.index = index);
            shader.setFloatUniform("foreground_color_premultiplied", r, g, b, a);
            this.dispersion = dispersion;
            this.rim = rim;
            if (extended) {
                shader.setFloatUniform("dispersion", dispersion);
                shader.setFloatUniform("rim", rim);
            }
            node.setRenderEffect(effect = RenderEffect.createRuntimeShaderEffect(shader, "img"));
        }
    }

}
