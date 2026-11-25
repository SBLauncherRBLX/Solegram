/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.ui;

import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.util.SparseIntArray;

import androidx.annotation.Nullable;

import org.telegram.messenger.SharedConfig;
import org.telegram.ui.ActionBar.MessageDrawable;
import org.telegram.ui.ActionBar.Theme;

import java.util.HashMap;

/**
 * Solegram: a look that is not on, for drawing Telegram's own cells in it (a theme preview, a screenshot in another
 * theme): its colours over the ones on screen, its own message bubbles, Telegram's ticks and icons recoloured in it, and
 * flat service backgrounds. Nothing of the app's theme is changed by it.
 */
public class SolegramLookProvider implements Theme.ResourcesProvider {

    protected final SparseIntArray colors;
    protected final boolean night;
    public boolean compact = SharedConfig.compactMode;
    private final HashMap<String, Drawable> drawables = new HashMap<>();
    private final HashMap<String, Paint> paints = new HashMap<>();

    /**
     * @param colors  key → colour of the look (keys not in it: the colour on screen now)
     * @param service the colour of service bubbles (dates, actions) on the look's wallpaper
     */
    public SolegramLookProvider(SparseIntArray colors, boolean night, int service, int serviceSelected) {
        this.colors = colors;
        this.night = night;
        final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setColor(service);
        paints.put(Theme.key_paint_chatActionBackground, bg);
        final Paint bgSelected = new Paint(Paint.ANTI_ALIAS_FLAG);
        bgSelected.setColor(serviceSelected);
        paints.put(Theme.key_paint_chatActionBackgroundSelected, bgSelected);
        final Paint darken = new Paint(Paint.ANTI_ALIAS_FLAG);
        darken.setColor(0x15000000);
        paints.put(Theme.key_paint_chatActionBackgroundDarken, darken);
        textPaint(Theme.key_paint_chatActionText, getColor(Theme.key_chat_serviceText));
        textPaint(Theme.key_paint_chatActionText2, getColor(Theme.key_chat_serviceText));
        textPaint(Theme.key_paint_chatActionText3, getColor(Theme.key_chat_serviceText));
        textPaint(Theme.key_paint_chatBotButton, getColor(Theme.key_chat_botButtonText));
    }

    private void textPaint(String key, int color) {
        final Paint base = Theme.getThemePaint(key);
        if (base == null) return;
        final TextPaint p = new TextPaint(base);
        p.setColor(color);
        paints.put(key, p);
    }

    /** Every colour on screen now, as a table (to work a look out from). */
    public static SparseIntArray currentColors() {
        final SparseIntArray all = new SparseIntArray(Theme.colorsCount);
        for (int key = 0; key < Theme.colorsCount; key++) {
            all.put(key, Theme.getColor(key));
        }
        return all;
    }

    @Override
    public int getColor(int key) {
        final int i = colors.indexOfKey(key);
        return i >= 0 ? colors.valueAt(i) : Theme.getColor(key);
    }

    @Override
    public Paint getPaint(String paintKey) {
        final Paint p = paints.get(paintKey);
        return p != null ? p : Theme.getThemePaint(paintKey);
    }

    @Nullable
    @Override
    public Drawable getDrawable(String key) {
        if (drawables.containsKey(key)) {
            return drawables.get(key);
        }
        Drawable d;
        switch (key) {
            case Theme.key_drawable_msgIn: d = new MessageDrawable(MessageDrawable.TYPE_TEXT, false, false, this); break;
            case Theme.key_drawable_msgInSelected: d = new MessageDrawable(MessageDrawable.TYPE_TEXT, false, true, this); break;
            case Theme.key_drawable_msgInMedia: d = new MessageDrawable(MessageDrawable.TYPE_MEDIA, false, false, this); break;
            case Theme.key_drawable_msgInMediaSelected: d = new MessageDrawable(MessageDrawable.TYPE_MEDIA, false, true, this); break;
            case Theme.key_drawable_msgOut: d = new MessageDrawable(MessageDrawable.TYPE_TEXT, true, false, this); break;
            case Theme.key_drawable_msgOutSelected: d = new MessageDrawable(MessageDrawable.TYPE_TEXT, true, true, this); break;
            case Theme.key_drawable_msgOutMedia: d = new MessageDrawable(MessageDrawable.TYPE_MEDIA, true, false, this); break;
            case Theme.key_drawable_msgOutMediaSelected: d = new MessageDrawable(MessageDrawable.TYPE_MEDIA, true, true, this); break;
            default: {
                // ticks, clocks, icons: Telegram's, in the look's colours (as a chat with its own theme does)
                d = null;
                final Drawable base = Theme.getThemeDrawablesMap().get(key);
                final Drawable.ConstantState state = base != null ? base.getConstantState() : null;
                final int colorKey = Theme.getThemeDrawableColorKey(key);
                if (state != null && colorKey >= 0) {
                    d = state.newDrawable().mutate();
                    Theme.setDrawableColor(d, getColor(colorKey));
                }
            }
        }
        drawables.put(key, d);
        return d;
    }

    @Override
    public boolean isDark() {
        return night;
    }

    @Override
    public boolean isCompactMode() {
        return compact;
    }

    @Override
    public boolean hasGradientService() {
        return false;
    }

    @Override
    public void applyServiceShaderMatrix(int w, int h, float translationX, float translationY) {
    }
}
