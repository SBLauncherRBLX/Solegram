/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.dpf2;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.customview.widget.ExploreByTouchHelper;

import com.solegram.messenger.SolegramConfig;
import com.solegram.messenger.SolegramGlass;
import com.solegram.messenger.SolegramProfileLinks;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LiteMode;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.CubicBezierInterpolator;

import java.util.ArrayList;
import java.util.List;

/**
 * Profile links as buttons: a capsule per link with the platform's mark in its colour and the platform's name,
 * wrapping onto more rows. With liquid glass on, the capsules are glass (a light fill, a rim lit from above,
 * a soft shadow); otherwise plain soft capsules. Tap opens, long press copies; on the own profile a last
 * capsule opens the editor. Everything is measured when the links change, nothing is made while drawing.
 */
public class SolegramProfileLinksView extends View {

    public interface Delegate {
        void onOpen(SolegramProfileLinks.Link link);

        void onCopy(SolegramProfileLinks.Link link);

        /** The own profile's "Edit" capsule. */
        default void onEdit() {
        }
    }

    private static final float CHIP_HEIGHT = 34, GAP = 8, ICON = 24, SIDE = 16, VERTICAL = 8;

    private final Theme.ResourcesProvider resourcesProvider;
    private final ArrayList<Chip> chips = new ArrayList<>();
    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint circlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmp = new RectF();
    private final AccessHelper accessHelper;

    private List<SolegramProfileLinks.Link> links = new ArrayList<>();
    private boolean editable;
    private Delegate delegate;
    private int backgroundKey = Theme.key_windowBackgroundWhite;
    private int textKey = Theme.key_windowBackgroundWhiteBlackText;
    private int laidOutWidth = -1;
    private int contentHeight;
    private boolean glass, dark, shadowSet;

    private Chip pressed;
    private float downX, downY;
    private boolean longPressed;
    private final Runnable longPress = this::onLongPress;

    private final class Chip {
        @Nullable
        final SolegramProfileLinks.Link link; // null: the edit capsule
        final Drawable icon;
        final int brand;
        String fullLabel, label;
        float labelWidth;
        final RectF rect = new RectF();
        LinearGradient rim;
        float press;
        ValueAnimator animator;

        Chip(@Nullable SolegramProfileLinks.Link link) {
            this.link = link;
            final int iconRes = link != null ? link.platform.icon : R.drawable.msg_edit;
            final Drawable d = ContextCompat.getDrawable(getContext(), iconRes);
            icon = d == null ? null : d.mutate();
            brand = link != null && !link.platform.isWebsite() ? link.platform.color : Theme.getColor(Theme.key_featuredStickers_addButton, resourcesProvider);
            fullLabel = label = link != null ? link.label() : LocaleController.getString(R.string.Edit);
            if (icon != null) {
                // white on the brand colour, dark on a light one (Snapchat)
                final boolean lightBrand = ColorUtils.calculateLuminance(brand) > 0.6;
                icon.setColorFilter(new PorterDuffColorFilter(lightBrand ? 0xFF000000 : 0xFFFFFFFF, PorterDuff.Mode.SRC_IN));
            }
        }

        void animatePress(boolean down) {
            if (animator != null) {
                animator.cancel();
            }
            animator = ValueAnimator.ofFloat(press, down ? 1f : 0f);
            animator.addUpdateListener(a -> {
                press = (float) a.getAnimatedValue();
                invalidate();
            });
            animator.setDuration(down ? 120 : 260);
            animator.setInterpolator(down ? CubicBezierInterpolator.EASE_OUT : CubicBezierInterpolator.EASE_OUT_BACK);
            animator.start();
        }
    }

    public SolegramProfileLinksView(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        textPaint.setTextSize(dp(14));
        textPaint.setTypeface(AndroidUtilities.bold());
        rimPaint.setStyle(Paint.Style.STROKE);
        rimPaint.setStrokeWidth(Math.max(1, dpf2(1f)));
        accessHelper = new AccessHelper(this);
        ViewCompat.setAccessibilityDelegate(this, accessHelper);
    }

    /** For a sheet: its own background and text colours. */
    public void setColorKeys(int backgroundKey, int textKey) {
        this.backgroundKey = backgroundKey;
        this.textKey = textKey;
        invalidate();
    }

    public void setDelegate(Delegate delegate) {
        this.delegate = delegate;
    }

    /** @param editable the own profile: an "Edit" capsule closes the row */
    public void setLinks(List<SolegramProfileLinks.Link> links, boolean editable) {
        if (sameLinks(links) && this.editable == editable) {
            return;
        }
        this.links = new ArrayList<>(links);
        this.editable = editable;
        for (Chip c : chips) {
            if (c.animator != null) {
                c.animator.cancel();
            }
        }
        chips.clear();
        pressed = null;
        for (SolegramProfileLinks.Link l : this.links) {
            chips.add(new Chip(l));
        }
        if (editable) {
            chips.add(new Chip(null));
        }
        // the same platform twice (two Instagram accounts): the usernames tell them apart
        for (int i = 0; i < chips.size(); i++) {
            final Chip c = chips.get(i);
            if (c.link == null || c.link.platform.isWebsite()) {
                continue;
            }
            for (int j = 0; j < chips.size(); j++) {
                final Chip o = chips.get(j);
                if (i != j && o.link != null && o.link.platform == c.link.platform && !TextUtils.isEmpty(c.link.handle)) {
                    c.fullLabel = c.link.handle.startsWith("+") || c.link.handle.contains("/") ? c.link.handle : "@" + c.link.handle;
                    break;
                }
            }
        }
        laidOutWidth = -1;
        accessHelper.invalidateRoot();
        requestLayout();
        invalidate();
    }

    private boolean sameLinks(List<SolegramProfileLinks.Link> other) {
        if (other.size() != links.size()) {
            return false;
        }
        for (int i = 0; i < other.size(); i++) {
            if (!TextUtils.equals(other.get(i).text, links.get(i).text)) {
                return false;
            }
        }
        return true;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final int width = MeasureSpec.getSize(widthMeasureSpec);
        layoutChips(width);
        setMeasuredDimension(width, contentHeight);
    }

    private void layoutChips(int width) {
        if (width == laidOutWidth) {
            return;
        }
        laidOutWidth = width;
        final float left = dp(SIDE), right = width - dp(SIDE), h = dp(CHIP_HEIGHT);
        final float maxText = Math.max(dp(40), right - left - dp(5 + ICON + 7 + 14));
        float x = left, y = dp(VERTICAL);
        final boolean rtl = LocaleController.isRTL;
        for (Chip c : chips) {
            String label = c.fullLabel;
            float tw = textPaint.measureText(label);
            if (tw > maxText) {
                label = TextUtils.ellipsize(label, textPaint, maxText, TextUtils.TruncateAt.END).toString();
                tw = textPaint.measureText(label);
            }
            c.label = label;
            c.labelWidth = tw;
            final float w = dp(5 + ICON + 7 + 14) + tw;
            if (x > left && x + w > right) {
                x = left;
                y += h + dp(GAP);
            }
            if (rtl) {
                c.rect.set(width - x - w, y, width - x, y + h);
            } else {
                c.rect.set(x, y, x + w, y + h);
            }
            c.rim = null; // made again for the new place
            x += w + dp(GAP);
        }
        contentHeight = chips.isEmpty() ? 0 : (int) Math.ceil(y + h + dp(VERTICAL + 4));
    }

    private void updateLook() {
        final int bg = Theme.getColor(backgroundKey, resourcesProvider);
        final boolean nowDark = ColorUtils.calculateLuminance(bg) < 0.5;
        final boolean nowGlass = Build.VERSION.SDK_INT >= 33 && LiteMode.isEnabled(LiteMode.FLAG_LIQUID_GLASS);
        if (nowDark != dark || nowGlass != glass || !shadowSet) {
            for (Chip c : chips) {
                c.rim = null;
            }
            shadowPaint.setShadowLayer(dp(5), 0, dp(1.5f), nowDark ? 0x59000000 : 0x1F000000);
            shadowSet = true;
        }
        dark = nowDark;
        glass = nowGlass;
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        if (chips.isEmpty()) {
            return;
        }
        updateLook();
        final int bg = Theme.getColor(backgroundKey, resourcesProvider);
        final int text = Theme.getColor(textKey, resourcesProvider);
        final int selector = Theme.getColor(Theme.key_listSelector, resourcesProvider);
        textPaint.setColor(text);
        final boolean scale = SolegramConfig.pressScale;
        for (Chip c : chips) {
            final RectF r = c.rect;
            final float radius = r.height() / 2f;
            canvas.save();
            if (scale && c.press > 0) {
                final float s = 1f - 0.05f * c.press;
                canvas.scale(s, s, r.centerX(), r.centerY());
            }
            if (glass) {
                // glass: a light body lifted by a soft shadow, a whisper of the brand, the rim lit from above
                shadowPaint.setColor(bg);
                canvas.drawRoundRect(r, radius, radius, shadowPaint);
                final int body = dark ? ColorUtils.compositeColors(0x14FFFFFF, bg) : ColorUtils.compositeColors(0xB3FFFFFF, bg);
                fillPaint.setColor(ColorUtils.blendARGB(body, c.brand, dark ? 0.12f : 0.07f));
                canvas.drawRoundRect(r, radius, radius, fillPaint);
                if (c.rim == null) {
                    c.rim = new LinearGradient(0, r.top, 0, r.bottom,
                        new int[]{SolegramGlass.menuRimTop(dark), SolegramGlass.menuRimBottom(dark), dark ? 0x10FFFFFF : 0x14000000},
                        new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP);
                }
                rimPaint.setShader(c.rim);
                tmp.set(r);
                tmp.inset(rimPaint.getStrokeWidth() / 2f, rimPaint.getStrokeWidth() / 2f);
                canvas.drawRoundRect(tmp, radius, radius, rimPaint);
            } else {
                fillPaint.setColor(ColorUtils.blendARGB(ColorUtils.compositeColors(ColorUtils.setAlphaComponent(text, dark ? 0x14 : 0x0B), bg), c.brand, dark ? 0.10f : 0.06f));
                canvas.drawRoundRect(r, radius, radius, fillPaint);
            }
            if (c.press > 0) {
                pressPaint.setColor(selector);
                pressPaint.setAlpha((int) (Color.alpha(selector) * c.press));
                canvas.drawRoundRect(r, radius, radius, pressPaint);
            }
            // the mark: white on the brand colour (dark on a light one), lifted a little on a dark page
            final float iconSize = dp(ICON);
            final float cx = LocaleController.isRTL ? r.right - dp(5) - iconSize / 2f : r.left + dp(5) + iconSize / 2f;
            final float cy = r.centerY();
            int circle = c.brand;
            if (dark && ColorUtils.calculateLuminance(circle) < 0.03) {
                circle = ColorUtils.blendARGB(circle, 0xFFFFFFFF, 0.16f);
            }
            circlePaint.setColor(circle);
            canvas.drawCircle(cx, cy, iconSize / 2f, circlePaint);
            if (c.icon != null) {
                final int half = dp(c.link == null ? 8 : 7);
                c.icon.setBounds((int) cx - half, (int) cy - half, (int) cx + half, (int) cy + half);
                c.icon.draw(canvas);
            }
            final float tx = LocaleController.isRTL ? r.right - dp(5 + ICON + 7) - c.labelWidth : r.left + dp(5 + ICON + 7);
            canvas.drawText(c.label, tx, cy - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint);
            canvas.restore();
        }
    }

    // region Touch

    @Nullable
    private Chip chipAt(float x, float y) {
        for (Chip c : chips) {
            if (x >= c.rect.left - dp(3) && x <= c.rect.right + dp(3) && y >= c.rect.top - dp(4) && y <= c.rect.bottom + dp(4)) {
                return c;
            }
        }
        return null;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (delegate == null) {
            return false;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                pressed = chipAt(event.getX(), event.getY());
                if (pressed == null) {
                    return false;
                }
                downX = event.getX();
                downY = event.getY();
                longPressed = false;
                pressed.animatePress(true);
                if (pressed.link != null) {
                    postDelayed(longPress, ViewConfiguration.getLongPressTimeout());
                }
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (pressed != null && Math.hypot(event.getX() - downX, event.getY() - downY) > ViewConfiguration.get(getContext()).getScaledTouchSlop()) {
                    release();
                }
                return pressed != null;
            }
            case MotionEvent.ACTION_UP: {
                if (pressed == null) {
                    return false;
                }
                final Chip chip = pressed;
                release();
                if (!longPressed) {
                    playSoundEffect(android.view.SoundEffectConstants.CLICK);
                    perform(chip, false);
                }
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                release();
                return false;
        }
        return pressed != null;
    }

    private void release() {
        removeCallbacks(longPress);
        if (pressed != null) {
            pressed.animatePress(false);
            pressed = null;
        }
    }

    private void onLongPress() {
        if (pressed == null || pressed.link == null) {
            return;
        }
        longPressed = true;
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        perform(pressed, true);
    }

    private void perform(Chip chip, boolean longClick) {
        if (delegate == null) {
            return;
        }
        if (chip.link == null) {
            delegate.onEdit();
        } else if (longClick) {
            delegate.onCopy(chip.link);
        } else {
            delegate.onOpen(chip.link);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        release();
    }

    @Override
    protected boolean dispatchHoverEvent(MotionEvent event) {
        return accessHelper.dispatchHoverEvent(event) || super.dispatchHoverEvent(event);
    }
    // endregion

    /** Each capsule is a button for TalkBack: "Instagram, instagram.com/anna". */
    private final class AccessHelper extends ExploreByTouchHelper {

        AccessHelper(View host) {
            super(host);
        }

        @Override
        protected int getVirtualViewAt(float x, float y) {
            final Chip c = chipAt(x, y);
            return c == null ? INVALID_ID : chips.indexOf(c);
        }

        @Override
        protected void getVisibleVirtualViews(List<Integer> ids) {
            for (int i = 0; i < chips.size(); i++) {
                ids.add(i);
            }
        }

        @Override
        protected void onPopulateNodeForVirtualView(int id, @NonNull AccessibilityNodeInfoCompat node) {
            if (id < 0 || id >= chips.size()) {
                node.setContentDescription("");
                node.setBoundsInParent(new Rect());
                return;
            }
            final Chip c = chips.get(id);
            node.setContentDescription(c.link == null ? c.label : c.link.platform.isWebsite() ? c.link.text : c.link.platform.name + ", " + c.link.text);
            node.setClassName("android.widget.Button");
            final Rect bounds = new Rect();
            c.rect.round(bounds);
            node.setBoundsInParent(bounds);
            node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK);
            if (c.link != null) {
                node.addAction(AccessibilityNodeInfoCompat.ACTION_LONG_CLICK);
            }
        }

        @Override
        protected boolean onPerformActionForVirtualView(int id, int action, @Nullable Bundle arguments) {
            if (id < 0 || id >= chips.size()) {
                return false;
            }
            if (action == AccessibilityNodeInfoCompat.ACTION_CLICK) {
                perform(chips.get(id), false);
                return true;
            } else if (action == AccessibilityNodeInfoCompat.ACTION_LONG_CLICK && chips.get(id).link != null) {
                perform(chips.get(id), true);
                return true;
            }
            return false;
        }
    }
}
