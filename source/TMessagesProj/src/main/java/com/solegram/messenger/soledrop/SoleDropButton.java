/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.soledrop;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.dpf2;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.solegram.messenger.ui.SolegramMotion;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;

/**
 * SoleDrop's buttons: the main one as Solegram's own (the theme's accent, flat), the others in glass. They
 * give in when pressed and spring back, as the rest of Solegram.
 */
@SuppressLint("ViewConstructor")
public class SoleDropButton extends TextView {

    private final boolean primary;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private int builtWidth, builtHeight;
    private final Theme.ResourcesProvider resourcesProvider;

    /** @param glass the glass behind a secondary button (null for the main one) */
    public SoleDropButton(Context context, boolean primary, @Nullable Drawable glass, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.primary = primary;
        this.resourcesProvider = resourcesProvider;
        setGravity(Gravity.CENTER);
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        setTypeface(AndroidUtilities.bold());
        setSingleLine(true);
        setEllipsize(TextUtils.TruncateAt.END);
        setPadding(dp(18), 0, dp(18), 0);
        if (primary) {
            setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText, resourcesProvider));
            setWillNotDraw(false);
            glow.setColor(Theme.getColor(Theme.key_featuredStickers_addButton, resourcesProvider)); // hidden under the fill: only its shadow shows
        } else {
            setTextColor(SoleDropPalette.text(resourcesProvider));
            if (glass != null) {
                setBackground(glass);
            }
        }
        SolegramMotion.pressable(this, SolegramMotion.PRESS_BUTTON);
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        SolegramMotion.fadeTo(this, enabled ? 1f : 0.45f);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (primary) {
            final int w = getWidth(), h = getHeight();
            if (w != builtWidth || h != builtHeight) {
                builtWidth = w;
                builtHeight = h;
                // Solegram's own button: the theme's accent, flat, with a quiet shadow (no coloured glow, no shine)
                fill.setShader(null);
                fill.setColor(Theme.getColor(Theme.key_featuredStickers_addButton, resourcesProvider));
                glow.setShadowLayer(dp(8), 0, dp(3), 0x30000000);
            }
            final float inset = dp(6);
            rect.set(inset, inset, w - inset, h - inset);
            final float r = dp(16);
            canvas.drawRoundRect(rect, r, r, glow);
            canvas.drawRoundRect(rect, r, r, fill);
        }
        super.onDraw(canvas);
    }
}
