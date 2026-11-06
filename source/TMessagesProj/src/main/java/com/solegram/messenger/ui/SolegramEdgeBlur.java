package com.solegram.messenger.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RecordingCanvas;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.os.Build;
import android.view.View;

import androidx.annotation.RequiresApi;
import androidx.core.graphics.ColorUtils;

/**
 * A progressive blur at the edge of a screen, in place of the old fade into the background colour: what scrolls
 * under the header (or above the tab bar) gets softly out of focus, more and more towards the edge, instead of
 * being darkened.
 *
 * The content is recorded into a render node (its child views as live references, so a scroll shows at once),
 * then drawn twice, through a light and a strong blur, each faded in by its own smooth ramp: a light blur first,
 * then the strong one right at the edge. A thin veil of the background colour stays only at the very edge, so the
 * status bar and the title stay readable on a busy picture.
 *
 * Android 12+ with a hardware canvas; elsewhere {@link #draw} returns false and the caller keeps its plain fade.
 */
public class SolegramEdgeBlur {

    private static final int STOPS = 8;

    private RenderNode content, light, strong;
    private final Paint maskPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint veilPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float lightRadius = -1, strongRadius = -1;

    private int shaderHeight, shaderVeilColor;
    private boolean shaderFromTop;
    private LinearGradient lightMask, strongMask, veil;

    public SolegramEdgeBlur() {
        maskPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
    }

    public static boolean supported(Canvas canvas) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && canvas != null && canvas.isHardwareAccelerated();
    }

    /**
     * Draws the blurred band.
     *
     * @param canvas     the parent's canvas, in the parent's coordinates
     * @param contents   the parent's children to blur (their position in the parent is taken as is), nulls skipped
     * @param width      band width
     * @param top        band top, in the parent
     * @param height     band height
     * @param fromTop    true: the edge is at the top of the band (a header); false: at its bottom (a tab bar)
     * @param background the screen's colour, drawn under the content and as the thin veil at the edge
     * @param veilAlpha  strength of that veil right at the edge, 0..255
     * @param alpha      the whole band's opacity, 0..1
     * @return false when the blur is not available here (the caller draws its old fade)
     */
    public boolean draw(Canvas canvas, View[] contents, int width, float top, int height, boolean fromTop, int background, int veilAlpha, float alpha) {
        return draw(canvas, contents, null, width, top, height, fromTop, background, veilAlpha, alpha);
    }

    /** Same, with the contents anywhere under root (the view whose coordinates the canvas is in). */
    public boolean draw(Canvas canvas, View[] contents, View root, int width, float top, int height, boolean fromTop, int background, int veilAlpha, float alpha) {
        if (!supported(canvas)) {
            return false;
        }
        if (alpha <= 0f || width <= 0 || height <= 0) {
            return true;
        }
        return drawImpl(canvas, contents, root, width, top, height, fromTop, background, veilAlpha, alpha);
    }

    private final float[] pos = new float[2];
    private final android.graphics.RectF capturePos = new android.graphics.RectF();

    /** A view's position in root (its parent's coordinates when root is null or not one of its parents). */
    private static void offsetIn(View v, View root, float[] out) {
        float x = v.getLeft() + v.getTranslationX(), y = v.getTop() + v.getTranslationY();
        android.view.ViewParent p = v.getParent();
        while (root != null && p instanceof View && p != root) {
            final View pv = (View) p;
            x += pv.getLeft() + pv.getTranslationX() - pv.getScrollX();
            y += pv.getTop() + pv.getTranslationY() - pv.getScrollY();
            p = pv.getParent();
        }
        if (root != null && p != root) {
            x = v.getLeft() + v.getTranslationX();
            y = v.getTop() + v.getTranslationY();
        }
        out[0] = x;
        out[1] = y;
    }

    @RequiresApi(api = Build.VERSION_CODES.S)
    private boolean drawImpl(Canvas canvas, View[] contents, View root, int width, float top, int height, boolean fromTop, int background, int veilAlpha, float alpha) {
        if (content == null) {
            content = new RenderNode("SolegramEdgeBlurContent");
            light = new RenderNode("SolegramEdgeBlurLight");
            strong = new RenderNode("SolegramEdgeBlurStrong");
        }
        final float lr = dp(6), sr = dp(18);
        if (lightRadius != lr || strongRadius != sr) {
            lightRadius = lr;
            strongRadius = sr;
            light.setRenderEffect(RenderEffect.createBlurEffect(lr, lr, Shader.TileMode.CLAMP));
            strong.setRenderEffect(RenderEffect.createBlurEffect(sr, sr, Shader.TileMode.CLAMP));
        }

        // the content around the band (with a margin, so the blur has something to take in at its edges)
        final int margin = (int) Math.ceil(sr * 2);
        final int bandTop = (int) Math.floor(top);
        // only the inner side gets the margin: past the screen's edge there is no content, and a margin filled with
        // the background colour there was blurred into the edge (it read as a darkening right at the edge); at the
        // edge itself the blur clamps the last row of real content instead
        final int nodeTop = fromTop ? bandTop : bandTop - margin;
        final int nodeHeight = height + margin;
        content.setPosition(0, 0, width, nodeHeight);
        RecordingCanvas rc = content.beginRecording(width, nodeHeight);
        try {
            rc.drawColor(background | 0xFF000000);
            rc.translate(0, -nodeTop);
            if (contents != null) {
                for (View v : contents) {
                    if (v == null || v.getVisibility() != View.VISIBLE || v.getAlpha() <= 0f || v.getWidth() <= 0) {
                        continue;
                    }
                    offsetIn(v, root, pos);
                    final float x = pos[0], y = pos[1];
                    if (y >= nodeTop + nodeHeight || y + v.getHeight() <= nodeTop) {
                        continue;
                    }
                    rc.save();
                    rc.translate(x, y);
                    rc.clipRect(0, 0, v.getWidth(), v.getHeight());
                    if (v.getAlpha() < 1f) {
                        rc.saveLayerAlpha(0, 0, v.getWidth(), v.getHeight(), (int) (255 * v.getAlpha()));
                    }
                    if (v instanceof org.telegram.ui.Components.blur3.capture.IBlur3Capture) {
                        // the way the app's glass sees a list (rows one by one, the cards under them): drawing the
                        // whole list at once gave only the background here
                        capturePos.set(0, nodeTop - y, v.getWidth(), nodeTop + nodeHeight - y);
                        final org.telegram.ui.Components.RecyclerListView list = v instanceof org.telegram.ui.Components.RecyclerListView
                            ? (org.telegram.ui.Components.RecyclerListView) v : null;
                        final boolean sections = list != null && list.solegramCaptureSectionsAllowed();
                        if (list != null) {
                            list.setCaptureSectionsDecoratorAllowed(true);
                        }
                        try {
                            ((org.telegram.ui.Components.blur3.capture.IBlur3Capture) v).capture(rc, capturePos);
                        } catch (Throwable e) {
                            org.telegram.messenger.FileLog.e(e);
                        }
                        if (list != null) {
                            list.setCaptureSectionsDecoratorAllowed(sections);
                        }
                    } else {
                        v.draw(rc);
                    }
                    rc.restore();
                    if (v.getAlpha() < 1f) {
                        rc.restore();
                    }
                }
            }
        } finally {
            content.endRecording();
        }
        record(light, width, nodeHeight);
        record(strong, width, nodeHeight);

        checkShaders(height, fromTop, background, veilAlpha);

        canvas.save();
        canvas.translate(0, bandTop);
        canvas.clipRect(0, 0, width, height);
        final int a = (int) (255 * Math.min(1f, alpha));
        drawLayer(canvas, light, lightMask, width, height, nodeTop - bandTop, a);
        drawLayer(canvas, strong, strongMask, width, height, nodeTop - bandTop, a);
        if (veil != null) {
            veilPaint.setAlpha(a);
            canvas.drawRect(0, 0, width, height, veilPaint);
        }
        canvas.restore();
        return true;
    }

    @RequiresApi(api = Build.VERSION_CODES.S)
    private void record(RenderNode node, int width, int height) {
        node.setPosition(0, 0, width, height);
        final RecordingCanvas c = node.beginRecording(width, height);
        c.drawRenderNode(content);
        node.endRecording();
    }

    @RequiresApi(api = Build.VERSION_CODES.S)
    private void drawLayer(Canvas canvas, RenderNode node, LinearGradient mask, int width, int height, int nodeOffset, int alpha) {
        final int save = canvas.saveLayerAlpha(0, 0, width, height, alpha);
        canvas.save();
        canvas.translate(0, nodeOffset);
        canvas.drawRenderNode(node);
        canvas.restore();
        maskPaint.setShader(mask);
        canvas.drawRect(0, 0, width, height, maskPaint);
        canvas.restoreToCount(save);
    }

    /** Smooth ramps (smoothstep, many stops) so no band edge shows. */
    private void checkShaders(int height, boolean fromTop, int background, int veilAlpha) {
        final int veilColor = veilAlpha <= 0 ? 0 : ColorUtils.setAlphaComponent(background, Math.min(255, veilAlpha));
        if (lightMask != null && shaderHeight == height && shaderFromTop == fromTop && shaderVeilColor == veilColor) {
            return;
        }
        shaderHeight = height;
        shaderFromTop = fromTop;
        shaderVeilColor = veilColor;
        // t: 0 at the inner end of the band, 1 at the screen's edge
        lightMask = ramp(height, fromTop, 0f, 0.5f, Color.BLACK, 255);
        strongMask = ramp(height, fromTop, 0.35f, 1f, Color.BLACK, 255);
        if (veilColor != 0) {
            veil = ramp(height, fromTop, 0.55f, 1f, background, Color.alpha(veilColor));
            veilPaint.setShader(veil);
        } else {
            veil = null;
            veilPaint.setShader(null);
        }
    }

    private static LinearGradient ramp(int height, boolean fromTop, float from, float to, int color, int maxAlpha) {
        final int[] colors = new int[STOPS];
        final float[] positions = new float[STOPS];
        for (int i = 0; i < STOPS; i++) {
            final float t = i / (float) (STOPS - 1);              // along the band, inner end -> edge
            float k = (t - from) / Math.max(0.0001f, to - from);
            k = Math.max(0f, Math.min(1f, k));
            k = k * k * (3f - 2f * k);
            colors[i] = ColorUtils.setAlphaComponent(color, Math.round(maxAlpha * k));
            positions[i] = fromTop ? 1f - t : t;
        }
        if (fromTop) {
            // gradient positions must grow: reverse both arrays
            for (int i = 0, j = STOPS - 1; i < j; i++, j--) {
                final int c = colors[i]; colors[i] = colors[j]; colors[j] = c;
                final float p = positions[i]; positions[i] = positions[j]; positions[j] = p;
            }
        }
        return new LinearGradient(0, 0, 0, height, colors, positions, Shader.TileMode.CLAMP);
    }
}
