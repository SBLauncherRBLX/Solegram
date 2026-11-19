/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ComposeShader;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;
import org.telegram.ui.Components.LayoutHelper;

/**
 * Own colours of the profile: the top and bottom of its gradient. A live preview of the gradient, a switch between
 * the two ends, a saturation/brightness square with a hue bar under it, and a few ready colours.
 */
@SuppressLint("ViewConstructor")
public class SolegramGradientPickerSheet extends BottomSheet {

    public interface Listener {
        /** Every change, live. */
        void onChange(int top, int bottom);
    }

    private static final int[] PRESETS = {
        0xFF3B82C4, 0xFF6C4AD8, 0xFFD8467A, 0xFFE2653A, 0xFFE8A93B, 0xFF3DAA6B, 0xFF1FA3A3, 0xFF2B2F38,
    };

    private final int[] colors = new int[2];
    private int editing; // 0 top, 1 bottom
    private final Listener listener;
    private final GradientDrawable previewDrawable = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0, 0});
    private final TextView[] chips = new TextView[2];
    private final SvSquare square;
    private final HueBar hueBar;
    private final float[] hsv = new float[3];
    private final TextView titleView;

    public SolegramGradientPickerSheet(Context context, Theme.ResourcesProvider rp, int top, int bottom, Listener listener) {
        super(context, false, rp);
        this.listener = listener;
        colors[0] = top | 0xFF000000;
        colors[1] = bottom | 0xFF000000;
        fixNavigationBar(Theme.getColor(Theme.key_dialogBackground, rp));

        final LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(16), dp(4), dp(16), dp(12));

        final TextView title = titleView = new TextView(context);
        title.setText(getString(R.string.SG_ProfileOwnColors));
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        title.setTypeface(AndroidUtilities.bold());
        title.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, rp));
        column.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 6, 8, 6, 12));

        // the gradient as the profile will have it
        final View preview = new View(context);
        previewDrawable.setCornerRadius(dp(18));
        preview.setBackground(previewDrawable);
        column.addView(preview, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 72));

        // which end is being chosen
        final LinearLayout chipRow = new LinearLayout(context);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < 2; i++) {
            final int index = i;
            final TextView chip = new TextView(context);
            chip.setText(getString(i == 0 ? R.string.SG_ProfileColorTop : R.string.SG_ProfileColorBottom));
            chip.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            chip.setTypeface(AndroidUtilities.bold());
            chip.setGravity(Gravity.CENTER);
            chip.setCompoundDrawablePadding(dp(8));
            chip.setOnClickListener(v -> select(index));
            chips[i] = chip;
            chipRow.addView(chip, LayoutHelper.createLinear(0, 40, 1f, i == 0 ? 0 : 5, 0, i == 0 ? 5 : 0, 0));
        }
        column.addView(chipRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 12, 0, 12));

        square = new SvSquare(context);
        column.addView(square, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 168));
        hueBar = new HueBar(context);
        column.addView(hueBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 32, 0, 12, 0, 0));

        // ready colours
        final LinearLayout swatches = new LinearLayout(context);
        swatches.setOrientation(LinearLayout.HORIZONTAL);
        for (int c : PRESETS) {
            final View dot = new View(context);
            final GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(c);
            dot.setBackground(g);
            dot.setOnClickListener(v -> setEditing(c));
            final FrameLayout cell = new FrameLayout(context);
            cell.addView(dot, LayoutHelper.createFrame(30, 30, Gravity.CENTER));
            swatches.addView(cell, LayoutHelper.createLinear(0, 40, 1f));
        }
        column.addView(swatches, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 10, 0, 6));

        final ButtonWithCounterView done = new ButtonWithCounterView(context, rp);
        done.setText(getString(R.string.Done), false);
        done.setOnClickListener(v -> dismiss());
        column.addView(done, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 0, 8, 0, 0));

        setCustomView(column);
        select(0);
    }

    /** Other words for the sheet (the name's colours: the name and the reply line's second stripe). */
    public SolegramGradientPickerSheet labels(CharSequence title, CharSequence first, CharSequence second) {
        titleView.setText(title);
        chips[0].setText(first);
        chips[1].setText(second);
        return this;
    }

    private void select(int index) {
        editing = index;
        Color.colorToHSV(colors[index], hsv);
        square.invalidate();
        hueBar.invalidate();
        refresh(false);
    }

    private void setEditing(int color) {
        colors[editing] = color | 0xFF000000;
        Color.colorToHSV(colors[editing], hsv);
        square.invalidate();
        hueBar.invalidate();
        refresh(true);
    }

    private void fromHsv() {
        colors[editing] = Color.HSVToColor(hsv) | 0xFF000000;
        refresh(true);
    }

    private void refresh(boolean notify) {
        previewDrawable.setColors(new int[]{colors[0], colors[1]});
        final int text = Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider);
        final int accent = Theme.getColor(Theme.key_featuredStickers_addButton, resourcesProvider);
        for (int i = 0; i < 2; i++) {
            final boolean on = i == editing;
            final GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(20));
            bg.setColor(on ? Theme.multAlpha(accent, 0.15f) : Theme.multAlpha(text, 0.06f));
            if (on) {
                bg.setStroke(dp(1.5f), accent);
            }
            chips[i].setBackground(bg);
            chips[i].setTextColor(on ? accent : text);
            final GradientDrawable dot = new GradientDrawable();
            dot.setShape(GradientDrawable.OVAL);
            dot.setColor(colors[i]);
            dot.setStroke(dp(1), Theme.multAlpha(text, 0.2f));
            dot.setSize(dp(14), dp(14));
            chips[i].setCompoundDrawablesWithIntrinsicBounds(dot, null, null, null);
        }
        if (notify && listener != null) {
            listener.onChange(colors[0], colors[1]);
        }
    }

    /** Saturation across, brightness down, for the current hue. */
    private class SvSquare extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint thumb = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private float shaderHue = -1;
        private int shaderW, shaderH;

        SvSquare(Context context) {
            super(context);
            thumb.setStyle(Paint.Style.STROKE);
            thumb.setStrokeWidth(dp(3));
            thumb.setColor(Color.WHITE);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            rect.set(0, 0, getWidth(), getHeight());
            if (shaderHue != hsv[0] || shaderW != getWidth() || shaderH != getHeight()) {
                shaderHue = hsv[0];
                shaderW = getWidth();
                shaderH = getHeight();
                final int pure = Color.HSVToColor(new float[]{hsv[0], 1f, 1f});
                final Shader across = new LinearGradient(0, 0, getWidth(), 0, Color.WHITE, pure, Shader.TileMode.CLAMP);
                final Shader down = new LinearGradient(0, 0, 0, getHeight(), 0x00000000, 0xFF000000, Shader.TileMode.CLAMP);
                paint.setShader(new ComposeShader(across, down, PorterDuff.Mode.SRC_OVER));
            }
            canvas.drawRoundRect(rect, dp(14), dp(14), paint);
            final float x = hsv[1] * getWidth(), y = (1 - hsv[2]) * getHeight();
            final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
            fill.setColor(Color.HSVToColor(hsv));
            canvas.drawCircle(x, y, dp(11), fill);
            canvas.drawCircle(x, y, dp(11), thumb);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (getParent() != null) {
                getParent().requestDisallowInterceptTouchEvent(true);
            }
            hsv[1] = Math.max(0, Math.min(1, e.getX() / Math.max(1, getWidth())));
            hsv[2] = Math.max(0, Math.min(1, 1 - e.getY() / Math.max(1, getHeight())));
            invalidate();
            fromHsv();
            return true;
        }
    }

    /** The hue, as a rainbow. */
    private class HueBar extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint thumb = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private int shaderW;

        HueBar(Context context) {
            super(context);
            thumb.setStyle(Paint.Style.STROKE);
            thumb.setStrokeWidth(dp(3));
            thumb.setColor(Color.WHITE);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final float h = dp(14);
            final float top = (getHeight() - h) / 2f;
            rect.set(dp(2), top, getWidth() - dp(2), top + h);
            if (shaderW != getWidth()) {
                shaderW = getWidth();
                final int[] hues = new int[7];
                for (int i = 0; i < 7; i++) {
                    hues[i] = Color.HSVToColor(new float[]{i * 60f % 360f, 1f, 1f});
                }
                hues[6] = Color.HSVToColor(new float[]{359.9f, 1f, 1f});
                paint.setShader(new LinearGradient(rect.left, 0, rect.right, 0, hues, null, Shader.TileMode.CLAMP));
            }
            canvas.drawRoundRect(rect, h / 2f, h / 2f, paint);
            final float x = rect.left + hsv[0] / 360f * rect.width();
            final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
            fill.setColor(Color.HSVToColor(new float[]{hsv[0], 1f, 1f}));
            canvas.drawCircle(x, getHeight() / 2f, dp(12), fill);
            canvas.drawCircle(x, getHeight() / 2f, dp(12), thumb);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (getParent() != null) {
                getParent().requestDisallowInterceptTouchEvent(true);
            }
            final float w = Math.max(1, getWidth() - dp(4));
            hsv[0] = Math.max(0, Math.min(359.9f, (e.getX() - dp(2)) / w * 360f));
            // a grey or black colour has no hue to show: lift it so the hue can be seen
            if (hsv[1] < 0.15f) {
                hsv[1] = 0.6f;
            }
            if (hsv[2] < 0.15f) {
                hsv[2] = 0.7f;
            }
            invalidate();
            square.invalidate();
            fromHsv();
            return true;
        }
    }

    /** A colour for the grid's own-colours circle. */
    public static int mix(int a, int b) {
        return ColorUtils.blendARGB(a, b, 0.5f);
    }
}
