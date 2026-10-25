/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.core.graphics.ColorUtils;

import com.solegram.messenger.SolegramConfig;
import com.solegram.messenger.SolegramIcons;

import org.telegram.messenger.LiteMode;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedFloat;
import org.telegram.ui.Components.CubicBezierInterpolator;

/**
 * The icon pack choice: three cards side by side, each with six icons drawn by its pack, the name and a round
 * check below, as the light/dark choice on iOS. The selected card gets an accent rim and its icons the accent
 * colour. Drawing allocates nothing; the colour filters are made again only when the theme changes.
 */
@SuppressLint("ViewConstructor")
public class SolegramIconPackPicker extends View {

    private static final int[] PACKS = {SolegramIcons.PACK_STANDARD, SolegramIcons.PACK_THIN, SolegramIcons.PACK_FILLED};
    /** Icons everybody knows: chat, bell, gear on top, reply, pin, delete below. */
    private static final int[] PREVIEW = {R.drawable.msg_discussion, R.drawable.msg_notifications, R.drawable.msg_settings,
        R.drawable.menu_reply, R.drawable.msg_pin, R.drawable.msg_delete};

    private final Theme.ResourcesProvider resourcesProvider;
    private final Utilities.Callback<Integer> onSelect;
    // per card: the icons in the gray of the list and again in the accent, crossfaded by the selection
    private final Drawable[][] gray = new Drawable[3][];
    private final Drawable[][] accent = new Drawable[3][];
    private final String[] names = new String[3];
    private final AnimatedFloat[] selection = new AnimatedFloat[3];
    private final AnimatedFloat[] press = new AnimatedFloat[3];

    private final Paint cardPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint radioPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint checkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Path checkPath = new Path();
    private final RectF rect = new RectF();

    private int selected;
    private int pressed = -1;
    private int lastGray, lastAccent;
    private float cardWidth, startX;

    private static final int CARD_HEIGHT = 86, GAP = 10, PADDING = 16, TOP = 14;

    public SolegramIconPackPicker(Context context, Theme.ResourcesProvider resourcesProvider, Utilities.Callback<Integer> onSelect) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        this.onSelect = onSelect;
        selected = SolegramConfig.iconPack;
        names[0] = LocaleController.getString(R.string.SG_IconPackStandard);
        names[1] = LocaleController.getString(R.string.SG_IconPackThin);
        names[2] = LocaleController.getString(R.string.SG_IconPackFilled);
        for (int c = 0; c < 3; c++) {
            gray[c] = new Drawable[PREVIEW.length];
            accent[c] = new Drawable[PREVIEW.length];
            for (int i = 0; i < PREVIEW.length; i++) {
                gray[c][i] = SolegramIcons.preview(context, PACKS[c], PREVIEW[i]);
                accent[c][i] = SolegramIcons.preview(context, PACKS[c], PREVIEW[i]);
            }
            selection[c] = new AnimatedFloat(c == selected ? 1f : 0f, this, 0, 360, CubicBezierInterpolator.EASE_OUT_QUINT);
            press[c] = new AnimatedFloat(0f, this, 0, 200, CubicBezierInterpolator.EASE_OUT_QUINT);
        }
        rimPaint.setStyle(Paint.Style.STROKE);
        rimPaint.setStrokeWidth(dp(2));
        radioPaint.setStrokeWidth(dp(1.5f));
        checkPaint.setStyle(Paint.Style.STROKE);
        checkPaint.setStrokeWidth(dp(2));
        checkPaint.setStrokeCap(Paint.Cap.ROUND);
        checkPaint.setStrokeJoin(Paint.Join.ROUND);
        checkPaint.setColor(0xFFFFFFFF);
        checkPath.moveTo(-dp(4), 0);
        checkPath.lineTo(-dp(1.2f), dp(2.8f));
        checkPath.lineTo(dp(4.2f), -dp(2.6f));
        textPaint.setTextSize(dp(14));
        textPaint.setTextAlign(Paint.Align.CENTER);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    public void setSelected(int pack) {
        if (selected != pack) {
            selected = pack;
            invalidate();
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(dp(TOP + CARD_HEIGHT + 10 + 18 + 8 + 20 + 16), MeasureSpec.EXACTLY));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        cardWidth = Math.min((w - dp(PADDING) * 2 - dp(GAP) * 2) / 3f, dp(150));
        startX = (w - cardWidth * 3 - dp(GAP) * 2) / 2f;
    }

    private void updateColors() {
        final int grayColor = Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon, resourcesProvider);
        final int accentColor = Theme.getColor(Theme.key_radioBackgroundChecked, resourcesProvider);
        if (grayColor != lastGray || accentColor != lastAccent) {
            lastGray = grayColor;
            lastAccent = accentColor;
            final PorterDuffColorFilter g = new PorterDuffColorFilter(grayColor, PorterDuff.Mode.SRC_IN);
            final PorterDuffColorFilter a = new PorterDuffColorFilter(accentColor, PorterDuff.Mode.SRC_IN);
            for (int c = 0; c < 3; c++) {
                for (int i = 0; i < PREVIEW.length; i++) {
                    if (gray[c][i] != null) {
                        gray[c][i].setColorFilter(g);
                    }
                    if (accent[c][i] != null) {
                        accent[c][i].setColorFilter(a);
                    }
                }
            }
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        updateColors();
        final boolean animate = !LiteMode.isPowerSaverApplied();
        final int background = Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider);
        final int text = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider);
        final int radio = Theme.getColor(Theme.key_radioBackground, resourcesProvider);
        final int card = ColorUtils.blendARGB(background, text, Theme.isCurrentThemeDark() ? 0.08f : 0.045f);
        final float iconSize = dp(22), rowGap = dp(14);
        final float top = dp(TOP);

        for (int c = 0; c < 3; c++) {
            final float p = selection[c].set(selected == c, !animate);
            final float s = 1f - 0.04f * press[c].set(pressed == c && SolegramConfig.pressScale, !animate);
            final float left = startX + c * (cardWidth + dp(GAP));
            final float cx = left + cardWidth / 2f;

            canvas.save();
            canvas.scale(s, s, cx, top + dp(CARD_HEIGHT) / 2f);
            rect.set(left, top, left + cardWidth, top + dp(CARD_HEIGHT));
            cardPaint.setColor(ColorUtils.blendARGB(card, lastAccent, 0.08f * p));
            canvas.drawRoundRect(rect, dp(14), dp(14), cardPaint);
            if (p > 0f) {
                rect.inset(dp(1), dp(1));
                rimPaint.setColor(lastAccent);
                rimPaint.setAlpha((int) (255 * p));
                canvas.drawRoundRect(rect, dp(13), dp(13), rimPaint);
            }

            final float colGap = (cardWidth - iconSize * 3) / 4f;
            final float iconsTop = top + (dp(CARD_HEIGHT) - iconSize * 2 - rowGap) / 2f;
            for (int i = 0; i < PREVIEW.length; i++) {
                final int x = (int) (left + colGap + (i % 3) * (iconSize + colGap));
                final int y = (int) (iconsTop + (i / 3) * (iconSize + rowGap));
                drawIcon(canvas, gray[c][i], x, y, (int) iconSize, 1f - p);
                drawIcon(canvas, accent[c][i], x, y, (int) iconSize, p);
            }
            canvas.restore();

            final float labelY = top + dp(CARD_HEIGHT) + dp(10) + dp(14);
            textPaint.setColor(text);
            canvas.drawText(names[c], cx, labelY, textPaint);

            final float radioY = top + dp(CARD_HEIGHT) + dp(10 + 18 + 8 + 10);
            radioPaint.setStyle(Paint.Style.STROKE);
            radioPaint.setColor(radio);
            radioPaint.setAlpha((int) (255 * (1f - p)));
            canvas.drawCircle(cx, radioY, dp(9.25f), radioPaint);
            if (p > 0f) {
                radioPaint.setStyle(Paint.Style.FILL);
                radioPaint.setColor(lastAccent);
                canvas.drawCircle(cx, radioY, dp(10) * (0.6f + 0.4f * p), radioPaint);
                checkPaint.setAlpha((int) (255 * p));
                canvas.save();
                canvas.translate(cx, radioY);
                canvas.drawPath(checkPath, checkPaint);
                canvas.restore();
            }
        }
    }

    private static void drawIcon(Canvas canvas, Drawable d, int x, int y, int size, float alpha) {
        if (d == null || alpha <= 0f) {
            return;
        }
        d.setBounds(x, y, x + size, y + size);
        d.setAlpha((int) (255 * alpha));
        d.draw(canvas);
    }

    private int cardAt(float x, float y) {
        if (y < dp(TOP) || y > getHeight() - dp(8)) {
            return -1;
        }
        for (int c = 0; c < 3; c++) {
            final float left = startX + c * (cardWidth + dp(GAP));
            if (x >= left - dp(GAP) / 2f && x <= left + cardWidth + dp(GAP) / 2f) {
                return c;
            }
        }
        return -1;
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pressed = cardAt(event.getX(), event.getY());
                invalidate();
                return pressed >= 0;
            case MotionEvent.ACTION_MOVE:
                if (pressed >= 0 && cardAt(event.getX(), event.getY()) != pressed) {
                    pressed = -1;
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (pressed >= 0 && cardAt(event.getX(), event.getY()) == pressed) {
                    choose(pressed);
                }
                pressed = -1;
                invalidate();
                return true;
            case MotionEvent.ACTION_CANCEL:
                pressed = -1;
                invalidate();
                return true;
        }
        return super.onTouchEvent(event);
    }

    /** TalkBack double tap: the next pack. */
    @Override
    public boolean performClick() {
        super.performClick();
        choose((selected + 1) % 3);
        return true;
    }

    private void choose(int c) {
        playSoundEffect(android.view.SoundEffectConstants.CLICK);
        if (c == selected) {
            return;
        }
        selected = c;
        invalidate();
        if (onSelect != null) {
            onSelect.run(PACKS[c]);
        }
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName("android.widget.RadioGroup");
        info.setContentDescription(LocaleController.getString(R.string.SG_IconPack) + ": " + names[Math.max(0, Math.min(2, selected))]);
        info.setText(TextUtils.join(", ", names));
    }
}
