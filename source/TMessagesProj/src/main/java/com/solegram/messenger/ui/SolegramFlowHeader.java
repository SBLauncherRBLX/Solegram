/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Build;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LiteMode;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

import java.io.InputStream;

/**
 * Banner of the Updates and About pages: logo, name and version over a haze of the theme colours
 * that slowly flows and curls like ink in water (only the colours move), fading into the page.
 * Android 13+: a GPU shader; older versions: soft colour blobs drifting over a gradient.
 */
@SuppressLint("ViewConstructor")
public class SolegramFlowHeader extends FrameLayout {

    private static final int HEIGHT_DP = 220;
    private static String shaderSource;
    private static boolean shaderFailed;

    private final Paint flowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint blobPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fadePaint = new Paint();
    private final int[] palette = new int[4];
    private final TextView titleView, subtitleView;
    // one clock for every banner (with a random phase per app start): a banner that is created again
    // while a page refreshes continues the same picture instead of jumping to another one
    private static final long startTime = SystemClock.elapsedRealtime() - (long) (Math.random() * 600_000);
    private Object runtimeShader; // android.graphics.RuntimeShader, API 33+
    private int paletteAccent, paletteBackground, fadeHeight;

    public SolegramFlowHeader(Context context, CharSequence title, CharSequence subtitle, int iconBackground, int iconForeground) {
        super(context);
        setWillNotDraw(false);

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);

        ImageView logo = new ImageView(context);
        Drawable bg = ContextCompat.getDrawable(context, iconBackground);
        Drawable fg = ContextCompat.getDrawable(context, iconForeground);
        logo.setImageDrawable(new LayerDrawable(new Drawable[]{bg, fg}));
        logo.setScaleType(ImageView.ScaleType.CENTER_CROP);
        logo.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                // app-icon squircle approximation, as on the home screen
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), view.getWidth() * 0.28f);
            }
        });
        logo.setClipToOutline(true);
        logo.setElevation(dp(6));
        content.addView(logo, LayoutHelper.createLinear(76, 76, Gravity.CENTER_HORIZONTAL));

        titleView = new TextView(context);
        titleView.setText(title);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 24);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setGravity(Gravity.CENTER);
        content.addView(titleView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 14, 0, 0));

        subtitleView = new TextView(context);
        subtitleView.setText(subtitle);
        subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        subtitleView.setGravity(Gravity.CENTER);
        content.addView(subtitleView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 24, 4, 24, 0));

        // a little above the middle: the lower part of the banner dissolves into the page
        addView(content, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 0, 0, 0, 28));

        if (Build.VERSION.SDK_INT >= 33) {
            createShader();
        }
        updatePalette();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(dp(HEIGHT_DP), MeasureSpec.EXACTLY));
    }

    // region open top: the haze reaches up to the top of the list (behind the header, and when the
    // page is pulled down) instead of stopping at the banner's own edge
    private float drawnGap = -1;
    private final ViewTreeObserver.OnPreDrawListener gapWatcher = () -> {
        // the banner is there at once: fading in with the list's rows, it let the bare page show under the
        // header for a moment, like the old solid header
        if (getParent() instanceof View && ((View) getParent()).getAnimation() != null) {
            ((View) getParent()).clearAnimation();
            ((View) getParent()).invalidate();
        }
        if (gapAbove() != drawnGap) {
            invalidate();
        }
        return true;
    };

    /** Free space between the top of the list and the banner, which the haze fills too. */
    private float gapAbove() {
        float top = getTop() + getTranslationY();
        // the list wraps the banner in a cell: count up to the list itself
        for (android.view.ViewParent p = getParent(); p instanceof View && !(p instanceof androidx.recyclerview.widget.RecyclerView); p = p.getParent()) {
            final View v = (View) p;
            top += v.getTop() + v.getTranslationY();
        }
        return Math.max(0f, top);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        // so the banner may paint above itself: its cell (see UItem.setChecked at the call sites) and the list
        for (android.view.ViewParent p = getParent(); p instanceof ViewGroup; p = p.getParent()) {
            ((ViewGroup) p).setClipChildren(false);
            if (p instanceof androidx.recyclerview.widget.RecyclerView) {
                break;
            }
        }
        getViewTreeObserver().addOnPreDrawListener(gapWatcher);
    }

    @Override
    protected void onDetachedFromWindow() {
        getViewTreeObserver().removeOnPreDrawListener(gapWatcher);
        super.onDetachedFromWindow();
    }
    // endregion

    // region colours

    /** Four shades around the theme accent: the accent itself, its neighbours on the colour wheel and a light tone. */
    private void updatePalette() {
        final int accent = Theme.getColor(Theme.key_featuredStickers_addButton);
        final int background = Theme.getColor(Theme.key_windowBackgroundGray);
        if (accent == paletteAccent && background == paletteBackground && fadePaint.getShader() != null) {
            return;
        }
        paletteAccent = accent;
        paletteBackground = background;
        final boolean dark = Theme.isCurrentThemeDark();
        palette[0] = shade(accent, 0, dark ? -0.05f : 0f);
        palette[1] = shade(accent, 40, 0.05f);
        palette[2] = shade(accent, -35, dark ? -0.08f : -0.02f);
        palette[3] = shade(accent, 15, dark ? 0f : 0.12f);

        // white text unless the haze is very light
        float luminance = 0;
        for (int c : palette) {
            luminance += (float) ColorUtils.calculateLuminance(c) / palette.length;
        }
        final boolean lightHaze = luminance > 0.62f;
        titleView.setTextColor(lightHaze ? Theme.getColor(Theme.key_windowBackgroundWhiteBlackText) : Color.WHITE);
        subtitleView.setTextColor(lightHaze ? Theme.getColor(Theme.key_windowBackgroundWhiteGrayText) : 0xD9FFFFFF);
        titleView.setShadowLayer(lightHaze ? 0 : dp(8), 0, dp(1), 0x40000000);
        subtitleView.setShadowLayer(lightHaze ? 0 : dp(6), 0, dp(1), 0x33000000);
        fadeHeight = 0; // the fade is rebuilt for the new background
        invalidate();
    }

    private static int shade(int color, float hueShift, float lightness) {
        final float[] hsl = new float[3];
        ColorUtils.colorToHSL(color, hsl);
        hsl[0] = (hsl[0] + hueShift + 360f) % 360f;
        hsl[2] = Math.max(0f, Math.min(1f, hsl[2] + lightness));
        return ColorUtils.HSLToColor(hsl);
    }
    // endregion

    // region drawing

    @androidx.annotation.RequiresApi(33)
    private void createShader() {
        if (shaderFailed) {
            return;
        }
        try {
            if (shaderSource == null) {
                try (InputStream in = ApplicationLoader.applicationContext.getResources().openRawResource(R.raw.solegram_flow_shader)) {
                    final byte[] bytes = new byte[in.available()];
                    int read = 0;
                    while (read < bytes.length) {
                        final int n = in.read(bytes, read, bytes.length - read);
                        if (n < 0) {
                            break;
                        }
                        read += n;
                    }
                    shaderSource = new String(bytes, 0, read, "UTF-8");
                }
            }
            final android.graphics.RuntimeShader shader = new android.graphics.RuntimeShader(shaderSource);
            runtimeShader = shader;
            flowPaint.setShader(shader);
        } catch (Throwable e) {
            FileLog.e(e);
            shaderFailed = true;
            runtimeShader = null;
            flowPaint.setShader(null);
        }
    }

    private boolean animates() {
        return LiteMode.isEnabled(LiteMode.FLAG_CHAT_BACKGROUND);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        updatePalette();
        final int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        // kept small so float precision stays fine however long the app runs (a jump at most every ~3 hours)
        final float time = (float) (((SystemClock.elapsedRealtime() - startTime) / 1000.0) % 10000.0);
        // the haze is drawn from the top of the list down to the banner's bottom
        final float gap = gapAbove();
        drawnGap = gap;
        final int fullH = (int) Math.ceil(h + gap);
        canvas.save();
        canvas.translate(0, -gap);
        if (Build.VERSION.SDK_INT >= 33 && runtimeShader != null) {
            final android.graphics.RuntimeShader shader = (android.graphics.RuntimeShader) runtimeShader;
            shader.setFloatUniform("size", w, fullH);
            shader.setFloatUniform("time", time);
            shader.setColorUniform("c1", palette[0]);
            shader.setColorUniform("c2", palette[1]);
            shader.setColorUniform("c3", palette[2]);
            shader.setColorUniform("c4", palette[3]);
            canvas.drawRect(0, 0, w, fullH, flowPaint);
        } else {
            drawBlobs(canvas, w, fullH, time);
        }
        canvas.restore();

        // the haze dissolves into the page below it
        if (fadeHeight != h) {
            fadeHeight = h;
            final int bg = paletteBackground;
            fadePaint.setShader(new LinearGradient(0, h * 0.5f, 0, h,
                new int[]{bg & 0x00FFFFFF, ColorUtils.setAlphaComponent(bg, 0x55), ColorUtils.setAlphaComponent(bg, 0xC0), bg},
                new float[]{0f, 0.45f, 0.78f, 1f}, Shader.TileMode.CLAMP));
        }
        canvas.drawRect(0, h * 0.5f, w, h, fadePaint);

        if (animates() && isAttachedToWindow()) {
            postInvalidateOnAnimation();
        }
    }

    /** Older Android: large soft blobs of the palette drift slowly over a diagonal gradient. */
    // the blobs animate every frame: their gradients are made once per palette (a unit circle moved and scaled
    // by a matrix) instead of four new shaders per frame
    private LinearGradient blobBase;
    private int blobBaseW, blobBaseH, blobBaseC1, blobBaseC2;
    private final RadialGradient[] blobShaders = new RadialGradient[3];
    private final int[] blobColors = new int[3];
    private final android.graphics.Matrix blobMatrix = new android.graphics.Matrix();

    private void drawBlobs(Canvas canvas, int w, int h, float t) {
        if (blobBase == null || blobBaseW != w || blobBaseH != h || blobBaseC1 != palette[0] || blobBaseC2 != palette[2]) {
            blobBaseW = w;
            blobBaseH = h;
            blobBaseC1 = palette[0];
            blobBaseC2 = palette[2];
            blobBase = new LinearGradient(0, 0, w, h, palette[0], palette[2], Shader.TileMode.CLAMP);
        }
        blobPaint.setShader(blobBase);
        canvas.drawRect(0, 0, w, h, blobPaint);
        drawBlob(canvas, 0, w * (0.25f + 0.2f * (float) Math.sin(t * 0.21f)), h * (0.35f + 0.2f * (float) Math.cos(t * 0.17f)), w * 0.6f, palette[1]);
        drawBlob(canvas, 1, w * (0.75f + 0.2f * (float) Math.cos(t * 0.15f + 1.3f)), h * (0.45f + 0.25f * (float) Math.sin(t * 0.19f + 0.4f)), w * 0.55f, palette[3]);
        drawBlob(canvas, 2, w * (0.5f + 0.3f * (float) Math.sin(t * 0.12f + 2.1f)), h * (0.8f + 0.15f * (float) Math.cos(t * 0.23f + 1.0f)), w * 0.5f, palette[0]);
    }

    private void drawBlob(Canvas canvas, int index, float cx, float cy, float r, int color) {
        RadialGradient shader = blobShaders[index];
        if (shader == null || blobColors[index] != color) {
            blobColors[index] = color;
            blobShaders[index] = shader = new RadialGradient(0, 0, 1, new int[]{color, ColorUtils.setAlphaComponent(color, 0x80), color & 0x00FFFFFF},
                new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP);
        }
        blobMatrix.setScale(r, r);
        blobMatrix.postTranslate(cx, cy);
        shader.setLocalMatrix(blobMatrix);
        blobPaint.setShader(shader);
        canvas.drawCircle(cx, cy, r, blobPaint);
    }
    // endregion
}
