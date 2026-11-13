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
 * The icon pack choice: three cards side by side, each with six icons drawn by its pack and the name below, as
 * the light/dark choice on iOS. The icons are in the theme's icon colour; the selected card gets a soft accent
 * rim, a check badge in its corner and its icons the accent colour. Drawing allocates nothing; the colour
 * filters are made again only when the theme changes.
 */
@SuppressLint("ViewConstructor")
public class SolegramIconPackPicker extends View {

    private static final int[] PACKS = {SolegramIcons.PACK_STANDARD, SolegramIcons.PACK_PHOSPHOR, SolegramIcons.PACK_HUGEICONS,
        SolegramIcons.PACK_MINGCUTE, SolegramIcons.PACK_MAJESTICONS, SolegramIcons.PACK_SOLAR};
    private static final int N = PACKS.length, COLS = 3, ROWS = (N + COLS - 1) / COLS;
    /** Icons everybody knows: chat, bell, gear on top, reply, pin, delete below. */
    private static final int[] PREVIEW = {R.drawable.msg_discussion, R.drawable.msg_notifications, R.drawable.msg_settings,
        R.drawable.menu_reply, R.drawable.msg_pin, R.drawable.msg_delete};

    private final Theme.ResourcesProvider resourcesProvider;
    private final Utilities.Callback<Integer> onSelect;
    // per card: the icons in the gray of the list and again in the accent, crossfaded by the selection
    private final Drawable[][] gray = new Drawable[N][];
    private final Drawable[][] accent = new Drawable[N][];
    private final String[] names = new String[N];
    private final AnimatedFloat[] selection = new AnimatedFloat[N];
    private final AnimatedFloat[] press = new AnimatedFloat[N];

    private final Paint cardPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint checkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Path checkPath = new Path();
    private final RectF rect = new RectF();

    private int selected;
    private int pressed = -1;
    private int lastGray, lastAccent;
    private float cardWidth, startX;

    private static final int CARD_HEIGHT = 78, GAP = 10, PADDING = 16, TOP = 12, LABEL = 8, BOTTOM = 14, BADGE = 18;

    public SolegramIconPackPicker(Context context, Theme.ResourcesProvider resourcesProvider, Utilities.Callback<Integer> onSelect) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        this.onSelect = onSelect;
        selected = SolegramConfig.iconPack;
        names[0] = LocaleController.getString(R.string.SG_IconPackStandard);
        names[1] = "Phosphor";
        names[2] = "Hugeicons";
        names[3] = "MingCute";
        names[4] = "Majesticons";
        names[5] = "Solar";
        for (int c = 0; c < N; c++) {
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
        rimPaint.setStrokeWidth(dp(1.5f));
        checkPaint.setStyle(Paint.Style.STROKE);
        checkPaint.setStrokeWidth(dp(1.6f));
        checkPaint.setStrokeCap(Paint.Cap.ROUND);
        checkPaint.setStrokeJoin(Paint.Join.ROUND);
        checkPaint.setColor(0xFFFFFFFF);
        checkPath.moveTo(-dp(3.4f), dp(0.2f));
        checkPath.lineTo(-dp(1f), dp(2.5f));
        checkPath.lineTo(dp(3.6f), -dp(2.3f));
        textPaint.setTextSize(dp(13));
        textPaint.setTypeface(org.telegram.messenger.AndroidUtilities.bold());
        textPaint.setTextAlign(Paint.Align.CENTER);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    public void setSelected(int pack) {
        selected = Math.max(0, Math.min(N - 1, pack));
        invalidate();
    }

    /** The pack really in use: whatever instance is on the screen, the check and the rim follow the setting. */
    private void syncWithSetting() {
        final int pack = Math.max(0, Math.min(N - 1, SolegramConfig.iconPack));
        if (pack != selected && pressed < 0) {
            selected = pack;
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        // shown again (the list is rebuilt after a pack change): the marks jump to the pack in use at once
        selected = Math.max(0, Math.min(N - 1, SolegramConfig.iconPack));
        for (int c = 0; c < N; c++) {
            selection[c].set(selected == c, true);
        }
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(dp(TOP) + ROWS * rowHeight() + dp(BOTTOM), MeasureSpec.EXACTLY));
    }

    /** One row of cards with their labels and the gap to the next row. */
    private static int rowHeight() {
        return dp(CARD_HEIGHT + LABEL + 18 + GAP);
    }

    private float cardLeft(int c) {
        return startX + (c % COLS) * (cardWidth + dp(GAP));
    }

    private float cardTop(int c) {
        return dp(TOP) + (c / COLS) * rowHeight();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        cardWidth = Math.min((w - dp(PADDING) * 2 - dp(GAP) * 2) / 3f, dp(132));
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
            for (int c = 0; c < N; c++) {
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
        final int textGray = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2, resourcesProvider);
        final boolean dark = ColorUtils.calculateLuminance(background) < 0.5;
        final int card = ColorUtils.blendARGB(background, text, dark ? 0.07f : 0.04f);
        final float iconSize = dp(21), rowGap = dp(12);
        final float cardHeight = dp(CARD_HEIGHT), radius = dp(16);

        syncWithSetting();
        for (int c = 0; c < N; c++) {
            final float p = selection[c].set(selected == c, !animate);
            final float s = 1f - 0.04f * press[c].set(pressed == c && SolegramConfig.pressScale, !animate);
            final float left = cardLeft(c), top = cardTop(c);
            final float cx = left + cardWidth / 2f;

            canvas.save();
            canvas.scale(s, s, cx, top + cardHeight / 2f);
            rect.set(left, top, left + cardWidth, top + cardHeight);
            // a whisper of the accent in the chosen card, a soft rim around it
            cardPaint.setColor(ColorUtils.blendARGB(card, lastAccent, (dark ? 0.08f : 0.06f) * p));
            canvas.drawRoundRect(rect, radius, radius, cardPaint);
            if (p > 0f) {
                final float half = rimPaint.getStrokeWidth() / 2f;
                rect.inset(half, half);
                rimPaint.setColor(lastAccent);
                rimPaint.setAlpha((int) (0xC0 * p));
                canvas.drawRoundRect(rect, radius - half, radius - half, rimPaint);
            }

            final float colGap = (cardWidth - iconSize * 3) / 4f;
            final float iconsTop = top + (cardHeight - iconSize * 2 - rowGap) / 2f;
            for (int i = 0; i < PREVIEW.length; i++) {
                final int x = (int) (left + colGap + (i % 3) * (iconSize + colGap));
                final int y = (int) (iconsTop + (i / 3) * (iconSize + rowGap));
                drawIcon(canvas, gray[c][i], x, y, (int) iconSize, 1f - p);
                drawIcon(canvas, accent[c][i], x, y, (int) iconSize, p);
            }

            // the check badge grows out of the corner
            if (p > 0f) {
                final float bx = left + cardWidth - dp(BADGE) / 2f - dp(6), by = top + dp(BADGE) / 2f + dp(6);
                final float bs = 0.5f + 0.5f * p;
                badgePaint.setColor(lastAccent);
                badgePaint.setAlpha((int) (255 * p));
                canvas.drawCircle(bx, by, dp(BADGE) / 2f * bs, badgePaint);
                checkPaint.setAlpha((int) (255 * p));
                canvas.save();
                canvas.translate(bx, by);
                canvas.scale(bs, bs);
                canvas.drawPath(checkPath, checkPaint);
                canvas.restore();
            }
            canvas.restore();

            final float labelY = top + cardHeight + dp(LABEL) - textPaint.ascent();
            textPaint.setColor(ColorUtils.blendARGB(textGray, text, p));
            canvas.drawText(names[c], cx, labelY, textPaint);
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
        if (y < dp(TOP) - dp(6) || y > getHeight() - dp(4)) {
            return -1;
        }
        final int row = Math.max(0, Math.min(ROWS - 1, (int) ((y - dp(TOP) + dp(GAP) / 2f) / rowHeight())));
        for (int col = 0; col < COLS; col++) {
            final int c = row * COLS + col;
            if (c >= N) {
                break;
            }
            final float left = cardLeft(c);
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
        choose((selected + 1) % N);
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
        info.setContentDescription(LocaleController.getString(R.string.SG_IconPack) + ": " + names[Math.max(0, Math.min(N - 1, selected))]);
        info.setText(TextUtils.join(", ", names));
    }
}
