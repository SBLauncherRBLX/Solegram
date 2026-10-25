/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.ui;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.StateListAnimator;
import android.animation.ValueAnimator;
import android.os.Build;
import android.transition.ChangeBounds;
import android.transition.Fade;
import android.transition.TransitionManager;
import android.transition.TransitionSet;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Interpolator;

import androidx.annotation.Nullable;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.FloatValueHolder;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LiteMode;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.Components.CubicBezierInterpolator;

import java.lang.ref.WeakReference;
import java.util.WeakHashMap;

/**
 * The one motion system of Solegram's own UI, after iOS: springs for anything touched or opened, short eased
 * fades for what only comes and goes, one set of durations. Everything here does nothing (or jumps to the end)
 * when the system's animations are off, and the decorative extras (stretch, bounce, stagger) also step aside
 * in Telegram's power saving.
 * <p>
 * Springs are given as SwiftUI does: a response (seconds, roughly the time to settle) and a damping fraction
 * (1 = no overshoot). {@link #force} turns a preset into a {@link SpringForce} for interactive motion that can be
 * caught and turned around; {@link Spring} is the same curve as an interpolator for one-shot animators (those
 * follow the system's animation speed, which dynamicanimation 1.0 does not).
 */
public final class SolegramMotion {

    private SolegramMotion() {
    }

    // region Switches

    /** Animations are on at all (the system's animator duration scale is not zero). */
    public static boolean enabled() {
        return Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled();
    }

    /** The decorative extras too: liquid stretch, icon bounce, staggered entrances (off in power saving). */
    public static boolean rich() {
        return enabled() && !LiteMode.isPowerSaverApplied();
    }
    // endregion

    // region Durations and curves

    /** Taps answered: a pressed state, a colour change. */
    public static final long FAST = 160;
    /** Things that come and go: a fade, a small move. */
    public static final long NORMAL = 260;
    /** Things that change their size: a section opening, a list making room. */
    public static final long SLOW = 360;
    /** A spring settling (open of a menu or a sheet), as a fixed duration for {@link Spring} curves. */
    public static final long SPRING = 460;
    /** Delay between rows of a staggered entrance, and the most it adds up to. */
    public static final long STAGGER = 28, STAGGER_MAX = 220;

    /** Decelerating, for what arrives (Telegram's own quint). */
    public static final CubicBezierInterpolator EASE_OUT = CubicBezierInterpolator.EASE_OUT_QUINT;
    /** Material's emphasized curve: a quick start that lands softly, for changes of size and place. */
    public static final CubicBezierInterpolator EMPHASIZED = new CubicBezierInterpolator(0.2, 0, 0, 1);
    /** Accelerating, for what leaves: it goes quickly without a lingering tail. */
    public static final CubicBezierInterpolator EASE_IN = new CubicBezierInterpolator(0.3, 0, 0.8, 0.15);
    /** Symmetric, for back and forth (a cross-fade of two states). */
    public static final CubicBezierInterpolator EASE_BOTH = CubicBezierInterpolator.EASE_BOTH;

    /** Presets: {response seconds, damping fraction}. */
    public static final int SNAPPY = 0, SMOOTH = 1, BOUNCY = 2, GENTLE = 3;
    private static final float[][] PRESETS = {
        {0.30f, 0.86f}, // SNAPPY: quick, the slightest overshoot (selection, press release of big things)
        {0.42f, 1.00f}, // SMOOTH: no overshoot (sheets, layout)
        {0.45f, 0.62f}, // BOUNCY: a visible bounce (press release, icons)
        {0.50f, 0.78f}, // GENTLE: a soft settle (menus growing out of their button)
    };

    /** A spring of the preset for SpringAnimation, aimed at {@code finalPosition}. */
    public static SpringForce force(int preset, float finalPosition) {
        final float response = PRESETS[preset][0], damping = PRESETS[preset][1];
        final float omega = (float) (2 * Math.PI / response);
        return new SpringForce(finalPosition).setStiffness(omega * omega).setDampingRatio(damping);
    }

    public static final Spring SPRING_SNAPPY = new Spring(PRESETS[SNAPPY][1]);
    public static final Spring SPRING_SMOOTH = new Spring(PRESETS[SMOOTH][1]);
    public static final Spring SPRING_BOUNCY = new Spring(PRESETS[BOUNCY][1]);
    public static final Spring SPRING_GENTLE = new Spring(PRESETS[GENTLE][1]);

    /**
     * A damped spring from 0 to 1 as an interpolator: it settles (within 0.1%) exactly at the end of the
     * animator's duration, so the duration sets its speed and the damping its character.
     */
    public static final class Spring implements Interpolator {
        private final float zeta, omega, omegaD;

        public Spring(float dampingRatio) {
            zeta = Math.max(0.1f, Math.min(1f, dampingRatio));
            if (zeta >= 0.999f) {
                omega = 9.23f; // e^-w (1 + w) = 0.001
                omegaD = 0;
            } else {
                final double root = Math.sqrt(1 - zeta * zeta);
                // the envelope e^(-zeta w t) / root reaches 0.001 at t = 1
                omega = (float) (Math.log(1000 / root) / zeta);
                omegaD = (float) (omega * root);
            }
        }

        @Override
        public float getInterpolation(float t) {
            if (t >= 1f) {
                return 1f;
            }
            if (t <= 0f) {
                return 0f;
            }
            if (omegaD == 0) {
                final float wt = omega * t;
                return (float) (1 - Math.exp(-wt) * (1 + wt));
            }
            final double decay = Math.exp(-zeta * omega * t);
            return (float) (1 - decay * (Math.cos(omegaD * t) + zeta * omega / omegaD * Math.sin(omegaD * t)));
        }
    }
    // endregion

    // region Press

    /** How much a pressed thing gives in: rows a little, buttons and cards a bit more. */
    public static final float PRESS_ROW = 0.97f, PRESS_CARD = 0.98f, PRESS_BUTTON = 0.96f;

    private static final WeakHashMap<View, Press> presses = new WeakHashMap<>();

    /** One view's press spring; it holds the view weakly (the map that keeps it is weak on the view). */
    private static final class Press implements DynamicAnimation.OnAnimationUpdateListener {
        final WeakReference<View> ref;
        final FloatValueHolder holder = new FloatValueHolder(1f);
        final SpringAnimation spring = new SpringAnimation(holder);

        Press(View view) {
            ref = new WeakReference<>(view);
            spring.setSpring(new SpringForce(1f));
            spring.setMinimumVisibleChange(DynamicAnimation.MIN_VISIBLE_CHANGE_SCALE);
            spring.addUpdateListener(this);
        }

        @Override
        public void onAnimationUpdate(DynamicAnimation animation, float value, float velocity) {
            final View view = ref.get();
            if (view != null) {
                view.setScaleX(value);
                view.setScaleY(value);
            }
        }
    }

    /**
     * Press feedback as on iOS: the view gives in quickly while held and springs back with a little bounce when
     * let go; a new press catches it wherever it is.
     */
    public static void press(@Nullable View view, boolean pressed, float scale) {
        if (view == null) {
            return;
        }
        Press p = presses.get(view);
        if (!enabled()) {
            if (p != null) {
                p.spring.cancel();
                view.setScaleX(1f);
                view.setScaleY(1f);
            }
            return;
        }
        final float target = pressed ? scale : 1f;
        if (p == null) {
            if (!pressed && Math.abs(view.getScaleX() - 1f) < 0.001f) {
                return; // never pressed here: nothing to bring back
            }
            p = new Press(view);
            presses.put(view, p);
        }
        if (!p.spring.isRunning()) {
            if (Math.abs(view.getScaleX() - target) < 0.001f) {
                return;
            }
            p.holder.setValue(view.getScaleX());
        }
        final SpringForce force = p.spring.getSpring();
        if (pressed) {
            // in: fast and flat, it follows the finger
            force.setStiffness(1400f).setDampingRatio(1f);
        } else {
            // out: a soft spring with a small overshoot
            force.setStiffness(380f).setDampingRatio(rich() ? 0.55f : 1f);
        }
        p.spring.animateToFinalPosition(target);
    }

    /** A recycled or reused view must not keep a half-finished press (only views pressed through here are touched). */
    public static void resetPress(@Nullable View view) {
        if (view == null) {
            return;
        }
        final Press p = presses.get(view);
        if (p != null && (p.spring.isRunning() || p.holder.getValue() != 1f)) {
            p.spring.cancel();
            p.holder.setValue(1f);
            view.setScaleX(1f);
            view.setScaleY(1f);
        }
    }

    /**
     * Gives a clickable view (a button, a chip, a card) its press feedback through its pressed state: no touch
     * listener is taken. Replaces the view's state list animator.
     */
    public static void pressable(@Nullable View view, float scale) {
        if (view == null) {
            return;
        }
        final AnimatorSet down = new AnimatorSet();
        down.playTogether(ObjectAnimator.ofFloat(view, View.SCALE_X, scale), ObjectAnimator.ofFloat(view, View.SCALE_Y, scale));
        down.setDuration(110);
        down.setInterpolator(EASE_OUT);
        final AnimatorSet up = new AnimatorSet();
        up.playTogether(ObjectAnimator.ofFloat(view, View.SCALE_X, 1f), ObjectAnimator.ofFloat(view, View.SCALE_Y, 1f));
        up.setDuration(520);
        up.setInterpolator(SPRING_BOUNCY);
        final StateListAnimator states = new StateListAnimator();
        states.addState(new int[]{android.R.attr.state_pressed}, down);
        states.addState(new int[0], up);
        view.setStateListAnimator(states);
    }

    /** A short tick for a change the finger makes (a toggle, a snap to a new value). */
    public static void haptic(@Nullable View view) {
        if (view == null) {
            return;
        }
        try {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
        } catch (Exception ignore) {
        }
    }

    /** A little pop for something that just became the chosen one (a tab icon): it dips and springs back. */
    public static void bounce(@Nullable View view, float rest) {
        if (view == null || !rich() || !view.isAttachedToWindow()) {
            return;
        }
        view.animate().cancel();
        view.setScaleX(rest * 0.86f);
        view.setScaleY(rest * 0.86f);
        view.animate().scaleX(rest).scaleY(rest).setDuration(560).setStartDelay(0).setInterpolator(SPRING_BOUNCY).start();
    }
    // endregion

    // region Appear and disappear

    /**
     * Shows or hides a view with a fade, a slight scale and a small rise (hidden: GONE at the end). Taking it
     * back halfway turns it around from where it is.
     */
    public static void setVisible(@Nullable View view, boolean visible, boolean animated) {
        if (view == null) {
            return;
        }
        view.animate().setListener(null).cancel();
        final boolean shown = view.getVisibility() == View.VISIBLE;
        if (!animated || !enabled() || !view.isAttachedToWindow() || !shown && !visible) {
            view.setVisibility(visible ? View.VISIBLE : View.GONE);
            view.setAlpha(1f);
            view.setScaleX(1f);
            view.setScaleY(1f);
            view.setTranslationY(0);
            return;
        }
        if (visible) {
            if (!shown) {
                view.setVisibility(View.VISIBLE);
                view.setAlpha(0f);
                view.setScaleX(0.94f);
                view.setScaleY(0.94f);
                view.setTranslationY(AndroidUtilities.dp(6));
            }
            view.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0).setStartDelay(0)
                .setDuration(SPRING).setInterpolator(SPRING_SNAPPY).start();
        } else {
            view.animate().alpha(0f).scaleX(0.96f).scaleY(0.96f).translationY(AndroidUtilities.dp(4)).setStartDelay(0)
                .setDuration(FAST).setInterpolator(EASE_IN)
                .withEndAction(() -> {
                    view.setVisibility(View.GONE);
                    view.setAlpha(1f);
                    view.setScaleX(1f);
                    view.setScaleY(1f);
                    view.setTranslationY(0);
                }).start();
        }
    }

    /** Fades a view to an alpha (a disabled button, a dimmed hint) instead of jumping there. */
    public static void fadeTo(@Nullable View view, float alpha) {
        if (view == null) {
            return;
        }
        if (!enabled() || !view.isAttachedToWindow()) {
            view.animate().cancel();
            view.setAlpha(alpha);
            return;
        }
        if (Math.abs(view.getAlpha() - alpha) < 0.001f) {
            return;
        }
        view.animate().alpha(alpha).setStartDelay(0).setDuration(FAST).setInterpolator(EASE_BOTH).start();
    }

    /**
     * Rows that just appeared rise and fade in one after another (call when they are laid out, before they are
     * drawn). Does nothing in power saving.
     */
    public static void staggerIn(@Nullable ViewGroup parent) {
        if (parent == null || !rich()) {
            return;
        }
        final int count = parent.getChildCount();
        final float rise = AndroidUtilities.dp(14);
        int visible = 0;
        for (int i = 0; i < count; i++) {
            final View child = parent.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE) {
                continue;
            }
            final long delay = Math.min(STAGGER_MAX, STAGGER * visible++);
            child.animate().cancel();
            child.setAlpha(0f);
            child.setTranslationY(rise);
            child.animate().alpha(1f).translationY(0).setStartDelay(delay).setDuration(SPRING).setInterpolator(SPRING_SMOOTH).start();
        }
    }
    // endregion

    // region Layout changes

    /**
     * The next layout of {@code root} animates: views that move or change size glide there, views that come or go
     * fade, all at once (Android's default transition plays them one after another and feels slow).
     */
    public static void beginLayoutTransition(@Nullable ViewGroup root) {
        if (root == null || !enabled() || !root.isLaidOut()) {
            return;
        }
        final TransitionSet set = new TransitionSet();
        set.setOrdering(TransitionSet.ORDERING_TOGETHER);
        set.addTransition(new ChangeBounds());
        set.addTransition(new Fade(Fade.IN | Fade.OUT));
        set.setDuration(SLOW);
        set.setInterpolator(EMPHASIZED);
        TransitionManager.beginDelayedTransition(root, set);
    }

    /** Payload of a row that changed its height in place: the adapter keeps the row as it is (see {@link #rowResized}). */
    public static final Object PAYLOAD_RESIZE = new Object();

    /**
     * A row of a list changed its height by itself (text unfolded, a translation shown): the rows under it glide
     * to their new places instead of jumping. The adapter must skip a rebind for {@link #PAYLOAD_RESIZE}.
     *
     * @return false when the row is not in a list that animates (the caller just relayouts it)
     */
    public static boolean rowResized(@Nullable View row) {
        if (row == null || !enabled() || !(row.getParent() instanceof RecyclerView)) {
            return false;
        }
        final RecyclerView list = (RecyclerView) row.getParent();
        final RecyclerView.Adapter<?> adapter = list.getAdapter();
        final int position = list.getChildAdapterPosition(row);
        if (adapter == null || position == RecyclerView.NO_POSITION || list.getItemAnimator() == null || list.isComputingLayout()) {
            return false;
        }
        adapter.notifyItemChanged(position, PAYLOAD_RESIZE);
        return true;
    }

    /** Animated list changes of Solegram's own lists: items glide, appear and go on the emphasized curve. */
    public static DefaultItemAnimator itemAnimator() {
        final DefaultItemAnimator animator = new DefaultItemAnimator();
        animator.setSupportsChangeAnimations(false);
        animator.setDelayAnimations(false);
        animator.setInterpolator(EMPHASIZED);
        animator.setTranslationInterpolator(EMPHASIZED);
        animator.setDurations(SLOW);
        return animator;
    }
    // endregion

    // region Sheets

    /** A sheet comes up on a spring: quick at first, it lands without the long tail of a quint. */
    public static void springSheet(@Nullable BottomSheet sheet) {
        if (sheet == null) {
            return;
        }
        sheet.openInterpolator = SPRING_SMOOTH;
        sheet.openDuration = 440;
    }
    // endregion
}
