package org.telegram.ui.Components.chat.layouts;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.lerp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.solegram.messenger.ui.SolegramLiquid;
import com.solegram.messenger.ui.SolegramLiquidGlass;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundColorProvider;
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundColorProviderThemed;
import org.telegram.ui.Components.chat.buttons.ChatActivityBlurredRoundButton;
import org.telegram.ui.Components.chat.buttons.ChatActivityBlurredRoundPageDownButton;

import me.vkryl.android.AnimatorUtils;
import me.vkryl.android.animator.BoolAnimator;
import me.vkryl.android.animator.FactorAnimator;

@SuppressLint("ViewConstructor")
public class ChatActivitySideControlsButtonsLayout extends FrameLayout implements FactorAnimator.Target {
    public static final int BUTTON_ATTACH = 0;
    public static final int BUTTON_PAGE_DOWN = 1;
    public static final int BUTTON_MENTION = 2;
    public static final int BUTTON_REACTIONS = 3;
    public static final int BUTTON_POLL_VOTES = 4;
    public static final int BUTTON_SEARCH_DOWN = 5;
    public static final int BUTTON_SEARCH_UP = 6;

    private static final int BUTTONS_COUNT = 7;

    private static final int ANIMATOR_ID_VISIBILITY = 1;
    private static final int ANIMATOR_ID_COUNTER_VISIBILITY = 2;

    private static final @DrawableRes int[] buttonIcons = new int[] {
        R.drawable.msg_input_attach2,
        R.drawable.pagedown,
        R.drawable.mentionbutton,
        R.drawable.reactionbutton,
        R.drawable.menu_poll_notify,
        R.drawable.pagedown,
        R.drawable.pagedown
    };

    private final String[] buttonDescriptions = new String[] {
        LocaleController.getString(R.string.AttachMenu),
        LocaleController.getString(R.string.AccDescrPageDown),
        LocaleController.getString(R.string.AccDescrMentionDown),
        LocaleController.getString(R.string.AccDescrReactionMentionDown),
        LocaleController.getString(R.string.AccDescrPollVotesMentionDown),
        LocaleController.getString(R.string.AccDescrSearchPrev),
        LocaleController.getString(R.string.AccDescrSearchNext)
    };

    private final Theme.ResourcesProvider resourcesProvider;
    private final BlurredBackgroundColorProvider colorProvider;
    private final BlurredBackgroundDrawableViewFactory blurredBackgroundDrawableViewFactory;
    private final ButtonHolder[] buttonHolders = new ButtonHolder[BUTTONS_COUNT];
    private final ButtonPendingState[] pendingStates = new ButtonPendingState[BUTTONS_COUNT];

    private ButtonOnClickListener onClickListener;
    private ButtonOnLongClickListener onLongClickListener;

    public ChatActivitySideControlsButtonsLayout(@NonNull Context context,
                                            Theme.ResourcesProvider resourcesProvider,
                                            BlurredBackgroundColorProvider colorProvider,
                                            BlurredBackgroundDrawableViewFactory blurredBackgroundDrawableViewFactory) {
        super(context);
        this.blurredBackgroundDrawableViewFactory = blurredBackgroundDrawableViewFactory;
        this.colorProvider = colorProvider;
        this.resourcesProvider = resourcesProvider;
    }

    private int gravity = Gravity.LEFT | Gravity.BOTTOM;
    public void setGravity(int gravity) {
        this.gravity = gravity;
    }

    public void setOnClickListener(ButtonOnClickListener onClickListener) {
        this.onClickListener = onClickListener;
    }

    public void setOnLongClickListener(ButtonOnLongClickListener onLongClickListener) {
        this.onLongClickListener = onLongClickListener;
    }

    public boolean getButtonLocationInWindow(final int buttonId, int[] loc) {
        final ButtonHolder holder = buttonHolders[buttonId];
        if (holder != null) {
            holder.button.getLocationInWindow(loc);
            return true;
        }
        return false;
    }

    public void updateColors() {
        for (ButtonHolder holder : buttonHolders) {
            if (holder != null) {
                holder.button.updateColors();
            }
        }
        if (liquid != null) {
            solegramApplyLiquidColors();
            invalidateLiquid();
        }
    }

    public void showButton(final int buttonId, boolean show, boolean animated) {
        if (buttonHolders[buttonId] == null && !show) {
            return;
        }

        final ButtonHolder holder = getOrCreateButtonHolder(buttonId);
        final boolean changed = holder.visibilityAnimator.getValue() != show;
        holder.visibilityAnimator.setValue(show, animated);
        if (isLiquid(buttonId)) {
            solegramShowDrop(buttonId, holder, show, animated && changed && getHeight() > 0);
        }
    }

    public void setButtonCount(final int buttonId, int count, boolean animated) {
        getOrCreatePendingState(buttonId).count = count;
        final ButtonHolder holder = buttonHolders[buttonId];
        if (holder != null) {
            holder.button.setCount(count, animated);
            holder.counterVisibilityAnimator.setValue(count > 0, animated);
            if (isLiquid(buttonId)) {
                solegramShowCounterDrop(holder, count > 0, animated);
            }
        }
    }

    public void setButtonLoading(final int buttonId, boolean loading, boolean animated) {
        getOrCreatePendingState(buttonId).loading = loading;
        final ButtonHolder holder = buttonHolders[buttonId];
        if (holder != null) {
            holder.button.showLoading(loading, animated);
        }
    }

    public boolean isButtonVisible(final int buttonId) {
        final ButtonHolder holder = getButtonHolder(buttonId);

        return holder != null && holder.visibilityAnimator.getValue();
    }

    public void setButtonEnabled(final int buttonId, boolean enabled, boolean animated) {
        getOrCreatePendingState(buttonId).enabled = enabled;
        final ButtonHolder holder = buttonHolders[buttonId];
        if (holder != null) {
            holder.button.setEnabled(enabled, animated);
        }
    }


    @Override
    public void onFactorChanged(int id, float factor, float fraction, FactorAnimator callee) {
        final int buttonId = id >> 16;
        final int animatorId = id & 0xFFFF;
        if (buttonId < 0 || buttonId >= buttonHolders.length || buttonHolders[buttonId] == null) {
            return;
        }

        if (animatorId == ANIMATOR_ID_VISIBILITY || animatorId == ANIMATOR_ID_COUNTER_VISIBILITY) {
            checkButtonsPositionsAndVisibility();
        }
    }

    private void checkButtonsPositionsAndVisibility() {
        float totalHeight = 0;

        for (int buttonId = 0; buttonId < buttonHolders.length; buttonId++) {
            ButtonHolder holder = buttonHolders[buttonId];

            if (holder == null) {
                continue;
            }

            final float visibility = holder.visibilityAnimator.getFloatValue();
            final float counterVisibility = holder.counterVisibilityAnimator.getFloatValue();

            holder.restTranslationY = -totalHeight;
            if (isLiquid(buttonId)) {
                // Solegram: the drop brings the button (applyLiquidContent); here only where it rests
                applyLiquidContent(holder);
            } else {
                holder.button.setVisibility(visibility > 0 ? VISIBLE : GONE);
                holder.button.setAlpha(visibility);
                holder.button.setScaleX(lerp(0.7f, 1f, visibility));
                holder.button.setScaleY(lerp(0.7f, 1f, visibility));
                if (buttonId != BUTTON_ATTACH) {
                    holder.button.setTranslationY(dp(80) * (1f - visibility) - totalHeight);
                }
            }

            final int height = dp(44);
            final int gap = dp(10 + 10 * counterVisibility);

            totalHeight += (height + gap) * visibility;
        }
    }



    private ButtonPendingState getOrCreatePendingState(final int buttonId) {
        if (pendingStates[buttonId] == null) {
            pendingStates[buttonId] = new ButtonPendingState();
        }
        return pendingStates[buttonId];
    }

    @Nullable
    private ButtonHolder getButtonHolder(final int buttonId) {
        if (buttonId < 0 || buttonId >= buttonHolders.length) {
            return null;
        }

        return buttonHolders[buttonId];
    }

    private ButtonHolder getOrCreateButtonHolder(final int buttonId) {
        if (buttonHolders[buttonId] == null) {

            final BoolAnimator visibilityAnimator = new BoolAnimator(
                (buttonId << 16) | ANIMATOR_ID_VISIBILITY, this,
                buttonId == BUTTON_ATTACH ? CubicBezierInterpolator.EASE_OUT_QUINT : AnimatorUtils.DECELERATE_INTERPOLATOR,
                buttonId == BUTTON_ATTACH ? 300 : 280
            );

            final BoolAnimator counterVisibilityAnimator = new BoolAnimator(
                (buttonId << 16) | ANIMATOR_ID_COUNTER_VISIBILITY, this,
                buttonId == BUTTON_ATTACH ? CubicBezierInterpolator.EASE_OUT_QUINT : AnimatorUtils.DECELERATE_INTERPOLATOR,
                buttonId == BUTTON_ATTACH ? 300 : 280);

            int size = 56, iconSize = 48;
            if (buttonId == BUTTON_ATTACH) {
                size = 50;
                iconSize = 32;
            }
            final ChatActivityBlurredRoundPageDownButton button = ChatActivityBlurredRoundPageDownButton.create(
                getContext(),
                size, iconSize,
                resourcesProvider,
                blurredBackgroundDrawableViewFactory,
                colorProvider,
                buttonIcons[buttonId]
            );

            button.setPivotX(dp(size / 2f));
            button.setPivotY(dp(size / 2f + 8));
            button.setVisibility(GONE);
            button.setContentDescription(buttonDescriptions[buttonId]);
            button.setOnClickListener(v -> {
                if (onClickListener != null) {
                    onClickListener.onClick(buttonId, v);
                }
            });
            button.setOnLongClickListener(v -> {
                if (onLongClickListener != null) {
                    return onLongClickListener.onLongClick(buttonId, v);
                }
                return false;
            });

            if (buttonId == BUTTON_SEARCH_UP) {
                button.reverseIconByY();
            }
            if (buttonId == BUTTON_PAGE_DOWN) {
                button.reverseCounter();
            }

            addView(button, LayoutHelper.createFrame(size, size + 8, gravity));

            buttonHolders[buttonId] = new ButtonHolder(button, visibilityAnimator, counterVisibilityAnimator);

            final ButtonPendingState pending = pendingStates[buttonId];
            if (pending != null) {
                button.setCount(pending.count, false);
                visibilityAnimator.setValue(false, false);
                counterVisibilityAnimator.setValue(pending.count > 0, false);
                button.showLoading(pending.loading, false);
                button.setEnabled(pending.enabled, false);
            }

            checkButtonsPositionsAndVisibility();
        }

        return buttonHolders[buttonId];
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    // region Solegram liquid motion

    /**
     * Where the input capsule is (the glass the buttons flow out of), in the parent's coordinates. The parent
     * draws the liquid ({@link #solegramDrawLiquid}) next to this layout, since necks reach outside of it.
     */
    public interface SolegramLiquidHost {
        boolean getInputCapsule(RectF out);

        float getInputCapsuleRadius(RectF capsule);
    }

    private SolegramLiquidHost liquidHost;
    private SolegramLiquid.Group liquid;
    private SolegramLiquid.Blob capsuleDrop;
    private SolegramLiquid.GlassFill liquidFill;
    private BlurredBackgroundDrawable liquidFillDrawable;
    private boolean liquidWasOn;
    private final RectF liquidRect = new RectF();

    public void setSolegramLiquidHost(SolegramLiquidHost host) {
        liquidHost = host;
    }

    /** Only the round buttons that come and go with the chat: page down and the mention/reaction/vote jumps. */
    private boolean isLiquid(int buttonId) {
        return liquidHost != null && SolegramLiquid.enabled()
            && (buttonId == BUTTON_PAGE_DOWN || buttonId == BUTTON_MENTION || buttonId == BUTTON_REACTIONS || buttonId == BUTTON_POLL_VOTES);
    }

    private void ensureLiquid() {
        if (liquid != null) {
            return;
        }
        liquid = new SolegramLiquid.Group();
        liquid.springs(0.42f, 0.78f);
        capsuleDrop = liquid.add();
        capsuleDrop.cut = true;
        solegramApplyLiquidColors();
    }

    private void solegramApplyLiquidColors() {
        if (liquid == null) {
            return;
        }
        final boolean dark = colorProvider instanceof BlurredBackgroundColorProviderThemed ? ((BlurredBackgroundColorProviderThemed) colorProvider).isDark() : Theme.isCurrentThemeDark();
        SolegramLiquidGlass.applyColors(liquid, colorProvider, dark);
    }

    private SolegramLiquid.GlassFill getLiquidFill() {
        if (liquidFill == null && getParent() instanceof View) {
            // the same live blur the buttons use, without an outline or shadow of its own (the liquid draws those)
            liquidFillDrawable = blurredBackgroundDrawableViewFactory.create((View) getParent(), (BlurredBackgroundColorProvider) null);
            liquidFill = SolegramLiquidGlass.live(liquidFillDrawable);
        }
        return liquidFill;
    }

    private void invalidateLiquid() {
        if (getParent() instanceof View) {
            ((View) getParent()).invalidate();
        }
    }

    /** The drop below the button (the button under it, or the input capsule): where it flows out of and back into. */
    private SolegramLiquid.Blob liquidParentOf(int buttonId) {
        for (int i = buttonId - 1; i >= 0; i--) {
            final ButtonHolder h = buttonHolders[i];
            if (h != null && h.drop != null && h.drop.isOut() && h.visibilityAnimator.getValue()) {
                return h.drop;
            }
        }
        return capsuleDrop != null && capsuleDrop.isVisible() ? capsuleDrop : null;
    }

    private void solegramShowDrop(int buttonId, ButtonHolder holder, boolean show, boolean animated) {
        ensureLiquid();
        updateLiquidGeometry();
        if (show) {
            if (holder.drop == null) {
                holder.drop = liquid.add();
            }
            if (holder.counterDrop == null && holder.counterVisibilityAnimator.getValue()) {
                // a count set before the button existed, or kept while it was hidden: its drop comes with the button
                holder.counterDrop = liquid.add();
            }
            updateLiquidGeometry();
            if (animated) {
                if (holder.counterDrop != null && holder.counterVisibilityAnimator.getValue()) {
                    // the counter buds off the button once that is out
                    liquid.chainOut(liquidParentOf(buttonId), 0.16f, holder.drop, holder.counterDrop);
                } else {
                    liquid.chainOut(liquidParentOf(buttonId), 0, holder.drop);
                }
            } else if (!holder.drop.isOut()) {
                holder.drop.show();
                if (holder.counterDrop != null && holder.counterVisibilityAnimator.getValue()) {
                    holder.counterDrop.show();
                }
                liquid.remove(LIQUID_DIRTY); // shown at once: the outline is rebuilt
            } else if (holder.counterDrop != null && holder.counterVisibilityAnimator.getValue() && !holder.counterDrop.isOut()) {
                // the button is out already: the counter buds off it
                liquid.out(holder.counterDrop, holder.drop, 0);
            }
        } else if (holder.drop != null) {
            if (animated && holder.drop.isVisible()) {
                // reverse order: the counter into the button, then the button into what it came out of
                if (holder.counterDrop != null) {
                    liquid.chainIn(liquidParentOf(buttonId), null, holder.drop, holder.counterDrop);
                } else {
                    liquid.chainIn(liquidParentOf(buttonId), null, holder.drop);
                }
            } else {
                removeDrops(holder);
            }
        }
        applyLiquidContent(holder);
        invalidateLiquid();
    }

    private void solegramShowCounterDrop(ButtonHolder holder, boolean show, boolean animated) {
        if (show) {
            if (holder.counterDrop == null) {
                ensureLiquid();
                holder.counterDrop = liquid.add();
                updateLiquidGeometry();
            }
            if (holder.drop != null && holder.drop.isOut() && holder.visibilityAnimator.getValue() && !holder.counterDrop.isOut()) {
                if (animated && holder.drop.isVisible()) {
                    liquid.out(holder.counterDrop, holder.drop, 0);
                } else {
                    holder.counterDrop.show();
                    liquid.remove(LIQUID_DIRTY);
                }
            }
        } else if (holder.counterDrop != null && holder.counterDrop.isVisible()) {
            if (animated && holder.drop != null && holder.drop.isVisible()) {
                liquid.in(holder.counterDrop, holder.drop, 0, null);
            } else {
                liquid.remove(holder.counterDrop);
                holder.counterDrop = null;
            }
        }
        applyLiquidContent(holder);
        invalidateLiquid();
    }

    private void removeDrops(ButtonHolder holder) {
        if (liquid != null) {
            if (holder.drop != null) {
                liquid.remove(holder.drop);
            }
            if (holder.counterDrop != null) {
                liquid.remove(holder.counterDrop);
            }
        }
        holder.drop = null;
        holder.counterDrop = null;
    }

    /** Removing a blob that is not in the group only marks its outline for a rebuild (a drop placed at rest). */
    private static final SolegramLiquid.Blob LIQUID_DIRTY = new SolegramLiquid.Blob();

    /** Puts the drops where the capsule and the buttons are now (springs while moving, at once at rest). */
    private void updateLiquidGeometry() {
        if (liquid == null) {
            return;
        }
        final boolean moving = liquid.isAnimating();
        boolean dirty = false;
        if (liquidHost != null && liquidHost.getInputCapsule(liquidRect)) {
            final float r = liquidHost.getInputCapsuleRadius(liquidRect);
            capsuleDrop.place(liquidRect.centerX(), liquidRect.centerY(), liquidRect.width(), liquidRect.height(), r);
            if (!capsuleDrop.isVisible()) {
                capsuleDrop.show();
                dirty = true;
            }
        } else if (capsuleDrop.isVisible() && !moving) {
            // no input to flow out of (search, channels): the buttons simply grow in place
            liquid.remove(capsuleDrop);
            capsuleDrop = liquid.add();
            capsuleDrop.cut = true;
        }
        // the buttons sit at the bottom left of this layout (56 x 64dp frames), stacked up by their translation
        final float left = getX();
        final float bottom = getY() + getHeight();
        final float size = dp(ChatActivityBlurredRoundButton.BUTTON_SIZE);
        for (int id = 0; id < buttonHolders.length; id++) {
            final ButtonHolder h = buttonHolders[id];
            if (h == null || h.drop == null && h.counterDrop == null) {
                continue;
            }
            final float top = bottom - dp(64) + h.restTranslationY;
            // the round glass: 44dp inside the 56dp button at the bottom of its frame
            final float cx = left + dp(28);
            final float cy = top + dp(36);
            if (h.drop != null) {
                dirty |= liquidPlace(h.drop, cx, cy, size, size, moving);
            }
            if (h.counterDrop != null) {
                // the counter badge: 23dp high, centred at the top of the frame
                final float cw = Math.max(dp(23), h.button.solegramCounterWidth());
                dirty |= liquidPlace(h.counterDrop, cx, top + dp(14), cw, dp(23), moving);
            }
        }
        if (dirty) {
            liquid.remove(LIQUID_DIRTY);
        }
    }

    /** True when a drop at rest jumped (its outline has to be rebuilt). */
    private static boolean liquidPlace(SolegramLiquid.Blob b, float cx, float cy, float w, float h, boolean moving) {
        if (b.finX == cx && b.finY == cy && b.finW == w && b.finH == h) {
            return false;
        }
        if (moving || !b.isOut()) {
            b.rest(cx, cy, w, h, Math.min(w, h) / 2f);
            return false;
        }
        b.place(cx, cy, w, h, Math.min(w, h) / 2f);
        return true;
    }

    /** The button's icon and counter ride on their drops; the glass itself is the liquid's. */
    private void applyLiquidContent(ButtonHolder holder) {
        final ChatActivityBlurredRoundPageDownButton button = holder.button;
        if (holder.drop == null) {
            button.setVisibility(GONE);
            button.setTranslationX(0);
            button.setTranslationY(holder.restTranslationY);
            return;
        }
        if (!holder.liquidContent) {
            holder.liquidContent = true;
            button.solegramSetLiquid(true);
            button.setAlpha(1f);
            button.setScaleX(1f);
            button.setScaleY(1f);
        }
        final SolegramLiquid.Blob d = holder.drop;
        final float dx = d.x.value - d.finX, dy = d.y.value - d.finY;
        button.setVisibility(d.isVisible() ? VISIBLE : GONE);
        button.setTranslationX(dx);
        button.setTranslationY(holder.restTranslationY + dy);
        float counterAlpha = 0, cdx = 0, cdy = 0;
        final SolegramLiquid.Blob c = holder.counterDrop;
        if (c != null && c.isVisible()) {
            counterAlpha = c.reveal;
            cdx = c.x.value - c.finX - dx;
            cdy = c.y.value - c.finY - dy;
        }
        button.solegramSetContent(d.reveal, counterAlpha, cdx, cdy);
    }

    /** Back to the buttons' own glass (the setting was turned off). */
    private void dropLiquid() {
        for (int id = 0; id < buttonHolders.length; id++) {
            final ButtonHolder h = buttonHolders[id];
            if (h == null) {
                continue;
            }
            removeDrops(h);
            if (h.liquidContent) {
                h.liquidContent = false;
                h.button.solegramSetLiquid(false);
                h.button.setTranslationX(0);
            }
        }
        liquid = null;
        capsuleDrop = null;
        checkButtonsPositionsAndVisibility();
    }

    /**
     * Draws the buttons' glass as liquid, in the parent's coordinates: call right before this layout is drawn.
     * The buttons flow out of the input capsule (and each out of the one below) and back into it.
     */
    public void solegramDrawLiquid(Canvas canvas) {
        final boolean on = liquidHost != null && SolegramLiquid.enabled();
        if (on != liquidWasOn) {
            liquidWasOn = on;
            if (!on) {
                dropLiquid();
            } else {
                // the buttons that are already there become drops at rest
                for (int id = 0; id < buttonHolders.length; id++) {
                    final ButtonHolder h = buttonHolders[id];
                    if (h != null && isLiquid(id) && h.visibilityAnimator.getValue()) {
                        solegramShowDrop(id, h, true, false);
                        if (h.counterVisibilityAnimator.getValue()) {
                            solegramShowCounterDrop(h, true, false);
                        }
                    }
                }
            }
        }
        if (!on || liquid == null) {
            return;
        }
        updateLiquidGeometry();
        final boolean moving = liquid.step(System.nanoTime());
        for (int id = 0; id < buttonHolders.length; id++) {
            final ButtonHolder h = buttonHolders[id];
            if (h == null || !isLiquid(id)) {
                continue;
            }
            if (h.drop != null && !h.drop.isVisible() && !moving) {
                // flowed back in: done with these drops
                removeDrops(h);
            }
            applyLiquidContent(h);
        }
        final float alpha = getAlpha() * (getVisibility() == VISIBLE ? 1f : 0f);
        if (alpha > 0) {
            liquid.draw(canvas, getLiquidFill(), alpha);
        }
        if (moving && getParent() instanceof View) {
            ((View) getParent()).postInvalidateOnAnimation();
        }
    }

    // endregion

    private static class ButtonHolder {
        public final ChatActivityBlurredRoundPageDownButton button;
        public final BoolAnimator visibilityAnimator;
        public final BoolAnimator counterVisibilityAnimator;
        // Solegram: liquid drops of the button and of its counter, and where the button rests
        public SolegramLiquid.Blob drop, counterDrop;
        public float restTranslationY;
        public boolean liquidContent;

        private ButtonHolder(ChatActivityBlurredRoundPageDownButton button, BoolAnimator visibilityAnimator, BoolAnimator counterVisibilityAnimator) {
            this.button = button;
            this.visibilityAnimator = visibilityAnimator;
            this.counterVisibilityAnimator = counterVisibilityAnimator;
        }
    }

    private static class ButtonPendingState {
        public int count = 0;
        public boolean loading = false;
        public boolean enabled = true;
    }
}