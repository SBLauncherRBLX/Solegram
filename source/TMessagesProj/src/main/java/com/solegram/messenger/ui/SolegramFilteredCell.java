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
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.core.content.ContextCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;

/**
 * A message hidden by the message filters. In a chat: a slim pill in the style of the date pills,
 * "Hidden: «rule» · Show", over the wallpaper; or nothing at all (zero height) when filtered messages are
 * hidden completely - also used for a date whose whole day is hidden. In the Feed: a slim card.
 * Tapping it is handled by the owner (it knows the list), see {@link #isOnButton(float, float)}.
 */
@SuppressLint("ViewConstructor")
public class SolegramFilteredCell extends View {

    public static final int STYLE_CHAT = 0;
    public static final int STYLE_FEED = 1;

    private final int style;
    private final Theme.ResourcesProvider resourcesProvider;
    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint buttonPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cardPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF pill = new RectF();
    private final Drawable icon;

    private MessageObject messageObject;
    private boolean gone;
    private String label = "";
    private CharSequence shownLabel = "";
    private final String button;
    private float buttonWidth;
    private float shift;
    private float pressed;
    private boolean pressing, tracking;
    private int iconColor;

    private float viewTop;
    private int backgroundHeight;

    public SolegramFilteredCell(Context context, int style, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.style = style;
        this.resourcesProvider = resourcesProvider;
        textPaint.setTextSize(dp(style == STYLE_FEED ? 14 : 13));
        buttonPaint.setTextSize(dp(style == STYLE_FEED ? 14 : 13));
        buttonPaint.setTypeface(AndroidUtilities.bold());
        button = LocaleController.getString(R.string.SG_FilterShow);
        final Drawable d = ContextCompat.getDrawable(context, R.drawable.msg_archive_hide);
        icon = d == null ? null : d.mutate();
    }

    /**
     * @param gone true: the message takes no space at all
     * @param ruleTitle what hid it, shown on the line
     */
    public void set(MessageObject messageObject, boolean gone, String ruleTitle, String prefix) {
        this.messageObject = messageObject;
        this.gone = gone;
        label = prefix == null ? LocaleController.formatString(R.string.SG_FilterHiddenBy, ruleTitle)
            : LocaleController.formatString(R.string.SG_FilterHiddenByIn, prefix, ruleTitle);
        setContentDescription(label);
        requestLayout();
        invalidate();
    }

    public MessageObject getMessageObject() {
        return messageObject;
    }

    public boolean isGone() {
        return gone;
    }

    /** Moves the pill with the chat content when the forum side menu is shown. */
    public void setShift(float shift) {
        if (this.shift != shift) {
            this.shift = shift;
            invalidate();
        }
    }

    /** Same as the other service cells: where the wallpaper part behind the pill is. */
    public void setVisiblePart(float viewTop, int backgroundHeight) {
        if (this.viewTop != viewTop || this.backgroundHeight != backgroundHeight) {
            this.viewTop = viewTop;
            this.backgroundHeight = backgroundHeight;
            invalidate();
        }
    }

    public boolean isOnButton(float x, float y) {
        if (gone) {
            return false;
        }
        final float slop = dp(6);
        if (style == STYLE_CHAT) {
            // the line is slim: all of it opens the message (TalkBack clicks come at 0, 0 too)
            return y >= -slop && y <= getHeight() + slop;
        }
        return x >= pill.left - slop && x <= pill.right + slop && y >= pill.top - slop && y <= pill.bottom + slop;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final int width = MeasureSpec.getSize(widthMeasureSpec);
        if (gone) {
            setMeasuredDimension(width, 0);
            return;
        }
        final boolean feed = style == STYLE_FEED;
        buttonWidth = buttonPaint.measureText(button);
        final float sideMargin = feed ? dp(12) : dp(36);
        final float padding = feed ? dp(14) : dp(10);
        final float iconSize = dp(feed ? 18 : 15);
        final float separator = textPaint.measureText("  ·  ");
        final float maxText = width - sideMargin * 2 - padding * 2 - iconSize - dp(6) - separator - buttonWidth;
        shownLabel = TextUtils.ellipsize(label, textPaint, Math.max(dp(40), maxText), TextUtils.TruncateAt.END);
        final float content = iconSize + dp(6) + textPaint.measureText(shownLabel, 0, shownLabel.length()) + separator + buttonWidth;
        final int height;
        if (feed) {
            height = dp(12 + 44 + 2);
            pill.set(sideMargin, dp(12), width - sideMargin, dp(12 + 44));
        } else {
            height = dp(36);
            final float w = content + padding * 2;
            final float left = (width - w) / 2f;
            pill.set(left, dp(5), left + w, dp(5 + 26));
        }
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (gone || messageObject == null) {
            return;
        }
        final boolean feed = style == STYLE_FEED;
        final float scale = 1f - 0.04f * pressed;
        canvas.save();
        canvas.translate(feed ? 0 : shift, 0);
        canvas.scale(scale, scale, pill.centerX(), pill.centerY());
        final int textColor;
        final int buttonColor;
        if (feed) {
            cardPaint.setColor(Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider));
            canvas.drawRoundRect(pill, dp(18), dp(18), cardPaint);
            textColor = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider);
            buttonColor = Theme.getColor(Theme.key_windowBackgroundWhiteBlueText, resourcesProvider);
        } else {
            applyServiceShaderMatrix();
            final float r = pill.height() / 2f;
            canvas.drawRoundRect(pill, r, r, getThemedPaint(Theme.key_paint_chatActionBackground));
            if (hasGradientService()) {
                canvas.drawRoundRect(pill, r, r, getThemedPaint(Theme.key_paint_chatActionBackgroundDarken));
            }
            textColor = Theme.getColor(Theme.key_chat_serviceText, resourcesProvider);
            buttonColor = textColor;
        }
        textPaint.setColor(textColor);
        buttonPaint.setColor(buttonColor);

        final float iconSize = dp(feed ? 18 : 15);
        float x = pill.left + (feed ? dp(14) : dp(10));
        final float cy = pill.centerY();
        if (icon != null) {
            if (iconColor != textColor) {
                iconColor = textColor;
                icon.setColorFilter(new PorterDuffColorFilter(textColor, PorterDuff.Mode.SRC_IN));
            }
            icon.setAlpha(feed ? 255 : 220);
            icon.setBounds((int) x, (int) (cy - iconSize / 2f), (int) (x + iconSize), (int) (cy + iconSize / 2f));
            icon.draw(canvas);
        }
        x += iconSize + dp(6);
        final float baseline = cy - (textPaint.descent() + textPaint.ascent()) / 2f;
        canvas.drawText(shownLabel, 0, shownLabel.length(), x, baseline, textPaint);
        if (feed) {
            // the button sits on the right edge of the card
            canvas.drawText(button, pill.right - dp(14) - buttonWidth, baseline, buttonPaint);
        } else {
            x += textPaint.measureText(shownLabel, 0, shownLabel.length());
            final String dot = "  ·  ";
            final int alpha = textPaint.getAlpha();
            textPaint.setAlpha((int) (alpha * 0.6f));
            canvas.drawText(dot, x, baseline, textPaint);
            textPaint.setAlpha(alpha);
            x += textPaint.measureText(dot);
            canvas.drawText(button, x, baseline, buttonPaint);
        }
        canvas.restore();
    }

    /** Press feedback. In a chat the click itself is the list's item click; in the Feed the cell's click listener. */
    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (gone) {
            return false;
        }
        final int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            tracking = isOnButton(event.getX(), event.getY());
            if (!tracking) {
                return super.onTouchEvent(event);
            }
            setPressing(true);
        } else if (action == MotionEvent.ACTION_MOVE && !isOnButton(event.getX(), event.getY())) {
            setPressing(false);
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            setPressing(false);
        }
        final boolean handled = super.onTouchEvent(event);
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            tracking = false;
            return true;
        }
        return handled || tracking;
    }

    private void setPressing(boolean value) {
        if (pressing == value) {
            return;
        }
        pressing = value;
        final float from = pressed;
        final android.animation.ValueAnimator animator = android.animation.ValueAnimator.ofFloat(from, value ? 1f : 0f);
        animator.setDuration(value ? 90 : 180);
        animator.addUpdateListener(a -> {
            pressed = (float) a.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setText(label + ". " + button);
        info.setClickable(!gone);
    }

    private boolean hasGradientService() {
        return resourcesProvider != null ? resourcesProvider.hasGradientService() : Theme.hasGradientService();
    }

    private void applyServiceShaderMatrix() {
        if (resourcesProvider != null) {
            resourcesProvider.applyServiceShaderMatrix(getMeasuredWidth(), backgroundHeight, getX(), viewTop);
        } else {
            Theme.applyServiceShaderMatrix(getMeasuredWidth(), backgroundHeight, getX(), viewTop);
        }
    }

    private Paint getThemedPaint(String key) {
        final Paint paint = resourcesProvider != null ? resourcesProvider.getPaint(key) : null;
        return paint != null ? paint : Theme.getThemePaint(key);
    }
}
