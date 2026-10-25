/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.ui.ActionBar;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.AnimationNotificationsLocker;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.utils.ViewOutlineProviderImpl;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.PopupSwipeBackLayout;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;

public class ActionBarPopupWindow extends PopupWindow implements com.solegram.messenger.ui.SolegramLiquidPopup.Room {

    private static Method layoutInScreenMethod;
    private static final Field superListenerField;
    private static DecelerateInterpolator decelerateInterpolator = new DecelerateInterpolator();
    private AnimatorSet windowAnimatorSet;
    private boolean animationEnabled = true;
    private int dismissAnimationDuration = 150;
    private boolean isClosingAnimated;
    private int currentAccount = UserConfig.selectedAccount;
    private boolean pauseNotifications;
    private long outEmptyTime = -1;
    private boolean scaleOut;

    static {
        Field f = null;
        try {
            f = PopupWindow.class.getDeclaredField("mOnScrollChangedListener");
            f.setAccessible(true);
        } catch (NoSuchFieldException e) {
            /* ignored */
        }
        superListenerField = f;
    }

    private static final ViewTreeObserver.OnScrollChangedListener NOP = () -> {
        /* do nothing */
    };

    private ViewTreeObserver.OnScrollChangedListener mSuperScrollListener;
    private ViewTreeObserver mViewTreeObserver;
    private AnimationNotificationsLocker notificationsLocker = new AnimationNotificationsLocker();

    public void setScaleOut(boolean b) {
        scaleOut = b;
    }

    public interface OnDispatchKeyEventListener {
        void onDispatchKeyEvent(KeyEvent keyEvent);
    }

    public static class ActionBarPopupWindowLayout extends FrameLayout {
        public final static int FLAG_USE_SWIPEBACK = 1;
        public final static int FLAG_SHOWN_FROM_BOTTOM = 2;
        public final static int FLAG_DONT_USE_SCROLLVIEW = 4;
        public boolean updateAnimation;
        public boolean clipChildren;
        public boolean swipeBackGravityRight;
        public boolean swipeBackGravityBottom;

        private OnDispatchKeyEventListener mOnDispatchKeyEventListener;
        private float backScaleX = 1;
        private float backScaleY = 1;
        private boolean startAnimationPending = false;
        private int backAlpha = 255;
        private int lastStartedChild = 0;
        public boolean shownFromBottom;
        private boolean animationEnabled = true;
        private ArrayList<AnimatorSet> itemAnimators;
        private HashMap<View, Integer> positions = new HashMap<>();
        private int gapStartY = -1000000;
        private int gapEndY = -1000000;
        private final Rect bgPaddings = new Rect();
        private onSizeChangedListener onSizeChangedListener;
        private float reactionsEnterProgress = 1f;

        private PopupSwipeBackLayout swipeBackLayout;
        @Nullable
        private ScrollView scrollView;
        protected LinearLayout linearLayout;

        private int backgroundColor = Color.WHITE;
        protected Drawable backgroundDrawable;

        private boolean fitItems;
        private final Theme.ResourcesProvider resourcesProvider;
        private View topView;
        protected ActionBarPopupWindow window;

        public int subtractBackgroundHeight;
        Rect rect;

        public Rect getPadding() {
            return bgPaddings;
        }

        public ActionBarPopupWindowLayout(Context context) {
            this(context, null);
        }

        public ActionBarPopupWindowLayout(Context context, Theme.ResourcesProvider resourcesProvider) {
            this(context, R.drawable.popup_fixed_alert2, resourcesProvider);
        }

        public ActionBarPopupWindowLayout(Context context, int resId, Theme.ResourcesProvider resourcesProvider) {
            this(context, resId, resourcesProvider, 0);
        }

        // Solegram "glass menus": live glass in place of the menu's own panel, the panel it replaced, and the screen
        // it looks at when that is in the menu's own window (null: the windows under the menu's window)
        private com.solegram.messenger.ui.SolegramLiveGlass solegramGlass;
        private Drawable solegramPlainBackground;
        @Nullable
        private com.solegram.messenger.ui.SolegramLiveGlass.Host solegramGlassHost;
        private int solegramGlassColor, solegramGlassRecipeUsed;
        /** Solegram: FROSTED for a menu over Telegram's blurred backdrop (frosted like the backdrop round it). */
        public int solegramGlassRecipe = com.solegram.messenger.ui.SolegramLiveGlass.CLEAR;
        // the liquid's fill: live glass with no colour of its own (the liquid tints and outlines it)
        private com.solegram.messenger.ui.SolegramLiveGlass solegramLiquidGlass;
        // the liquid is filled with glass (live, or Telegram's blur): what is under the panel shows through it
        private boolean solegramLiquidSeeThrough;
        /** Solegram: shown by an ActionBarPopupWindow, which decides on its glass (and liquid) itself. */
        boolean solegramManaged;

        /**
         * Solegram "glass menus": the menu turns into the app's live glass - it refracts what is under it as the
         * glass in chats does - with the colours, tint, density, outline and shadow of the one menu glass
         * (SolegramLiveGlass.menu). Called each time the menu is shown (its window is up); without a live picture
         * of the screen it keeps its panel.
         *
         * @param windowRoot the root of the menu's window
         */
        public void solegramUseGlass(View windowRoot) {
            if (backgroundDrawable == null) {
                return; // no panel of its own (a page of another menu, a bare list)
            }
            final boolean ours = solegramGlass != null && backgroundDrawable == solegramGlass.drawable;
            if (!ours && backgroundDrawable instanceof org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable) {
                return; // Telegram's blurred menu: its factory gives it live glass (SolegramLiveGlass.Factory)
            }
            // what is under the menu: the windows under its window, or the screen behind it in its own window (a preview's menu)
            com.solegram.messenger.ui.SolegramLiveGlass.Host host = null;
            if (!com.solegram.messenger.ui.SolegramLiveGlass.canSample(windowRoot)) {
                host = solegramScreenBehind();
                if (host == null || !host.canSample()) {
                    solegramDropGlass(); // nothing live to show: the menu's own panel
                    return;
                }
            }
            // its own colour; white is what a custom panel leaves (setBackgroundDrawable): the theme's menu colour then
            final int ownColor = backgroundColor == Color.WHITE ? 0 : backgroundColor;
            if (ours && solegramGlassHost == host && solegramGlassColor == ownColor && solegramGlassRecipeUsed == solegramGlassRecipe) {
                solegramGlass.drawable.updateColors(); // theme or glass settings may have changed since the last time
                solegramGlass.drawable.invalidateDisplayList();
                invalidate();
                return;
            }
            solegramDropGlass(); // it looks somewhere else now
            final android.graphics.Rect padding = new android.graphics.Rect();
            backgroundDrawable.getPadding(padding);
            // the same inset as the panel it replaces (8dp: its shadow margin)
            // in the menu's own colour (a dark menu over media keeps it dark)
            final com.solegram.messenger.ui.SolegramLiveGlass glass = com.solegram.messenger.ui.SolegramLiveGlass.menu(this,
                resourcesProvider, ownColor, solegramGlassRecipe, padding.left, host);
            if (glass == null) {
                return;
            }
            solegramPlainBackground = backgroundDrawable;
            solegramGlass = glass;
            solegramGlassHost = host;
            solegramGlassColor = ownColor;
            solegramGlassRecipeUsed = solegramGlassRecipe;
            final int keepColor = backgroundColor;
            setBackgroundDrawable(glass.drawable);
            backgroundColor = keepColor; // views that borrow the menu colour keep the real one
            invalidate();
        }

        /** Solegram: a menu in the window of the screen it is over (a preview's menu): that screen, else null. */
        @Nullable
        private com.solegram.messenger.ui.SolegramLiveGlass.Host solegramScreenBehind() {
            final android.view.ViewParent container = getParent();
            if (container instanceof ActionBarLayout.LayoutContainer && container.getParent() instanceof ActionBarLayout) {
                return ((ActionBarLayout) container.getParent()).solegramScreenBehind((View) container);
            }
            return null;
        }

        /**
         * Solegram: the panel is glass (live, Telegram's blur, or the liquid's glass), so what is under it shows
         * through: swipe-back pages don't paint an opaque slab over it.
         */
        public boolean solegramSeeThrough() {
            if (!com.solegram.messenger.SolegramConfig.glassMenus) {
                return false;
            }
            return solegramLiquidActive() ? solegramLiquidSeeThrough : backgroundDrawable instanceof org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;
        }

        /** Solegram: a menu shown in some other way than by an ActionBarPopupWindow is glass too, as its window allows. */
        private void solegramAutoGlass() {
            if (backgroundDrawable == null) {
                return;
            }
            for (android.view.ViewParent p = getParent(); p instanceof View; p = p.getParent()) {
                if (p instanceof ActionBarPopupWindowLayout) {
                    return; // a page of another menu: that menu is the panel
                }
            }
            try {
                if (com.solegram.messenger.ui.SolegramLiveGlass.menusOn()) {
                    solegramUseGlass(getRootView());
                } else {
                    solegramDropGlass();
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            if (!solegramManaged) {
                solegramAutoGlass();
            }
        }

        /** Solegram: glass menus turned off (or nothing live under the menu) - the menu's own panel comes back. */
        public void solegramDropGlass() {
            if (solegramGlass == null) {
                return;
            }
            if (backgroundDrawable == solegramGlass.drawable && solegramPlainBackground != null) {
                final int keepColor = backgroundColor;
                setBackgroundDrawable(solegramPlainBackground);
                setBackgroundColor(keepColor);
                invalidate();
            }
            solegramGlass.release();
            solegramGlass = null;
            solegramGlassHost = null;
        }

        /** Solegram: the live glass shows what is under the menu where it is drawn now (it moves as it animates). */
        private void solegramUpdateGlassOffset() {
            if (solegramGlass != null && backgroundDrawable == solegramGlass.drawable) {
                solegramGlass.updateOffset();
            }
        }

        // region Solegram liquid: the menu flows out of what opened it (see SolegramLiquidPopup)

        /** Solegram: off for a menu that animates its own way (its window's flag counts too). */
        public boolean solegramLiquid = true;
        private com.solegram.messenger.ui.SolegramLiquidPopup solegramLiquidPopup;
        @Nullable
        private ActionBarPopupWindow solegramLiquidOwner;
        private float solegramBaseRadius = com.solegram.messenger.SolegramGlass.menuRadius(), solegramPanelRadius = solegramBaseRadius;
        private Drawable solegramOwnPanel;
        private final RectF solegramPanel = new RectF();
        private Paint solegramFillPaint;
        // the items as they were before they started hidden
        private View[] solegramItems;
        private float[] solegramItemAlpha, solegramItemTy;

        /** Solegram: the menu is drawn as liquid now (from its window's show until it is gone). */
        public boolean solegramLiquidActive() {
            return solegramLiquidPopup != null && solegramLiquidPopup.isActive();
        }

        /** Solegram: the liquid can draw this menu (the usual drawing knows things it does not). */
        boolean solegramLiquidAllowed() {
            // its own panel or Telegram's blurred one (not a custom drawable: the liquid could not look like it)
            final Drawable panel = solegramGlass != null && backgroundDrawable == solegramGlass.drawable ? solegramPlainBackground : backgroundDrawable;
            return solegramLiquid && panel != null && (panel == solegramOwnPanel || panel instanceof org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable)
                && topView == null && reactionsEnterProgress == 1f && gapStartY == -1000000 && com.solegram.messenger.ui.SolegramLiquid.enabled();
        }

        /**
         * Solegram: its window has just shown it; it flows out of the origin on its first frame.
         *
         * @param glass the app's live glass rather than the menu's own panel
         * @param windowRoot the root of the menu's window
         */
        boolean solegramLiquidPrepare(ActionBarPopupWindow owner, @Nullable View originView, @Nullable RectF originRect, float originRadius, boolean glass, @Nullable View windowRoot) {
            if (!solegramLiquidAllowed()) {
                return false;
            }
            if (solegramLiquidPopup == null) {
                solegramLiquidPopup = new com.solegram.messenger.ui.SolegramLiquidPopup(this);
            } else {
                // a window still flowing back with this menu (a menu shown again at once) closes now
                solegramLiquidPopup.finishClose();
            }
            solegramRestoreItems(false);
            solegramDropGlass(); // the liquid has its own glass
            // the panel is at its full size at once: the liquid is its growing
            if (itemAnimators != null) {
                for (int a = 0; a < itemAnimators.size(); a++) {
                    itemAnimators.get(a).removeAllListeners();
                    itemAnimators.get(a).cancel();
                }
                itemAnimators.clear();
            }
            startAnimationPending = false;
            final boolean wasUpdate = updateAnimation;
            updateAnimation = false;
            if (swipeBackLayout != null) {
                swipeBackLayout.invalidateTransforms();
            } else {
                setBackScaleX(1f);
                setBackScaleY(1f);
            }
            updateAnimation = wasUpdate;
            backAlpha = 255;
            if (backgroundDrawable.getAlpha() != 255) {
                backgroundDrawable.setAlpha(255);
            }

            final com.solegram.messenger.ui.SolegramLiquidPopup liquid = solegramLiquidPopup;
            liquid.prepare(owner, originView, originRect, originRadius, shownFromBottom);
            solegramPanelRadius = solegramBaseRadius;
            liquid.fill = null;
            solegramLiquidSeeThrough = false;
            if (glass && com.solegram.messenger.ui.SolegramLiveGlass.canSample(windowRoot)) {
                // the live glass of the screen under the menu, refracting along the liquid's edge
                if (solegramLiquidGlass == null) {
                    solegramLiquidGlass = com.solegram.messenger.ui.SolegramLiveGlass.create(this,
                        com.solegram.messenger.ui.SolegramLiveGlass.glassOnly(), com.solegram.messenger.ui.SolegramLiveGlass.CLEAR);
                    if (solegramLiquidGlass != null) {
                        solegramLiquidGlass.drawable.setRadius(dp(com.solegram.messenger.SolegramGlass.MENU_RADIUS_DP));
                    }
                }
                if (solegramLiquidGlass != null) {
                    solegramLiquidGlass.drawable.invalidateDisplayList();
                    liquid.fill = com.solegram.messenger.ui.SolegramLiquidGlass.live(solegramLiquidGlass.drawable);
                    solegramLiquidSeeThrough = true;
                }
            } else if (solegramLiquidGlass != null) {
                solegramLiquidGlass.release(); // not glass this time: it doesn't follow the screen for nothing
                solegramLiquidGlass = null;
            }
            if (liquid.fill != null) {
                liquid.glassColors(org.telegram.ui.Components.blur3.drawable.color.impl.BlurredBackgroundProviderImpl.solegramMenu(resourcesProvider, false), true);
                solegramPanelRadius = dp(com.solegram.messenger.SolegramGlass.MENU_RADIUS_DP);
            } else if (backgroundDrawable instanceof org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable) {
                // Telegram's own blurred menu: its blur (with its tint) fills the liquid, whose rim is the outline
                // (the drawable's own would be a second one)
                final Drawable blur = backgroundDrawable;
                ((org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable) blur).solegramSetStrokeHidden(true);
                liquid.fill = (canvas, path, bounds) -> {
                    canvas.save();
                    canvas.clipPath(path);
                    blur.setBounds((int) Math.floor(bounds.left), (int) Math.floor(bounds.top), (int) Math.ceil(bounds.right), (int) Math.ceil(bounds.bottom));
                    blur.draw(canvas);
                    canvas.restore();
                };
                solegramLiquidSeeThrough = true;
                liquid.glassColors(org.telegram.ui.Components.blur3.drawable.color.impl.BlurredBackgroundProviderImpl.solegramMenu(resourcesProvider, false), false);
                solegramPanelRadius = dp(com.solegram.messenger.SolegramGlass.MENU_RADIUS_DP);
            } else {
                // the menu's own colour (read each frame: it may change while open)
                if (solegramFillPaint == null) {
                    solegramFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                }
                final Paint paint = solegramFillPaint;
                liquid.fill = (canvas, path, bounds) -> {
                    paint.setColor(backgroundColor);
                    canvas.drawPath(path, paint);
                };
                liquid.plainColors(backgroundColor);
            }
            solegramLiquidOwner = owner;
            // where the panel will be: a tap before its first frame is not taken for one outside it
            solegramPanelRect(solegramPanel);
            solegramSetPanelBounds();
            solegramCollectItems(true); // the items start hidden: they show as the liquid reaches them
            invalidate();
            return true;
        }

        /** Solegram: remembers the items as they are (to put them back), {@code hide}: hidden for now. */
        private void solegramCollectItems(boolean hide) {
            final int count = linearLayout.getChildCount();
            solegramItems = new View[count];
            solegramItemAlpha = new float[count];
            solegramItemTy = new float[count];
            for (int a = 0; a < count; a++) {
                final View child = linearLayout.getChildAt(a);
                if (child instanceof GapView) {
                    continue;
                }
                float alpha = child.getAlpha();
                if (hide && alpha < 0.02f) {
                    alpha = child.isEnabled() ? 1f : 0.5f; // left hidden by an animation cut short (as Telegram's own opening does)
                }
                final float ty = child.getTranslationY();
                solegramItems[a] = child;
                solegramItemAlpha[a] = alpha;
                solegramItemTy[a] = Math.abs(ty) <= dp(6) + 1 ? 0 : ty;
                if (hide) {
                    child.setAlpha(0f);
                }
            }
        }

        /** Solegram: the liquid is over (the window is gone, or closed at once): the menu is the usual one again. */
        void solegramLiquidEnd(@Nullable ActionBarPopupWindow owner) {
            if (owner != null && owner != solegramLiquidOwner) {
                return; // shown by another window since
            }
            solegramLiquidOwner = null;
            if (solegramLiquidPopup != null) {
                solegramLiquidPopup.reset();
            }
            if (backgroundDrawable instanceof org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable) {
                ((org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable) backgroundDrawable).solegramSetStrokeHidden(false);
            }
            solegramRestoreItems(false);
            invalidate();
        }

        /** Solegram: flows back into its origin, then {@code onClosed} runs (once). */
        void solegramLiquidClose(Runnable onClosed) {
            if (solegramLiquidPopup == null) {
                AndroidUtilities.runOnUIThread(onClosed);
                return;
            }
            if (solegramItems == null) {
                solegramCollectItems(false); // they go before the panel flows back
            }
            solegramLiquidPopup.close(onClosed);
            invalidate();
        }

        /** Solegram: the items back as they were; {@code shown}: the menu came to rest (animated icons start). */
        private void solegramRestoreItems(boolean shown) {
            final View[] items = solegramItems;
            if (items == null) {
                return;
            }
            solegramItems = null;
            for (int a = 0; a < items.length; a++) {
                final View child = items[a];
                if (child == null) {
                    continue;
                }
                child.setAlpha(solegramItemAlpha[a]);
                child.setTranslationY(solegramItemTy[a]);
                if (shown && child instanceof ActionBarMenuSubItem && child.getParent() == linearLayout) {
                    ((ActionBarMenuSubItem) child).onItemShown();
                }
            }
        }

        /** Solegram: the panel's bounds as the usual drawing has them (touches outside them close a swipe-back menu). */
        private void solegramSetPanelBounds() {
            backgroundDrawable.setBounds(
                (int) (solegramPanel.left - bgPaddings.left), (int) (solegramPanel.top - bgPaddings.top),
                (int) Math.ceil(solegramPanel.right + bgPaddings.right), (int) Math.ceil(solegramPanel.bottom + bgPaddings.bottom)
            );
        }

        /** Solegram: where the panel is when open (the usual bounds of the background, less its shadow margin). */
        private void solegramPanelRect(RectF out) {
            final int w = getMeasuredWidth(), h = getMeasuredHeight();
            final float l, t, r, b;
            if (shownFromBottom) {
                l = 0;
                t = h * (1f - backScaleY);
                r = w * backScaleX;
                b = h;
            } else if (swipeBackLayout != null && swipeBackLayout.stickToRight) {
                l = w - w * backScaleX;
                t = 0;
                r = w;
                b = h * backScaleY - subtractBackgroundHeight;
            } else {
                l = 0;
                t = 0;
                r = w * backScaleX;
                b = h * backScaleY - subtractBackgroundHeight;
            }
            out.set(l + bgPaddings.left, t + bgPaddings.top, r - bgPaddings.right, b - bgPaddings.bottom);
        }

        /** Solegram: the items show as the liquid reaches them, and go before it flows back. */
        private void solegramRevealItems(com.solegram.messenger.ui.SolegramLiquidPopup liquid, long now) {
            final View[] items = solegramItems;
            if (items == null) {
                return;
            }
            // where the list is in this layout (inside the scroll and the swipe-back pages)
            float ox = 0, oy = 0;
            View v = linearLayout;
            while (v != null && v != this) {
                ox += v.getLeft() + v.getTranslationX();
                oy += v.getTop() + v.getTranslationY();
                if (!(v.getParent() instanceof View)) {
                    break;
                }
                final View parent = (View) v.getParent();
                ox -= parent.getScrollX();
                oy -= parent.getScrollY();
                v = parent;
            }
            for (int a = 0; a < items.length; a++) {
                final View child = items[a];
                if (child == null || child.getParent() != linearLayout || child.getVisibility() != VISIBLE || child.getHeight() <= 0) {
                    continue;
                }
                final float cy = oy + (child.getTop() + child.getBottom()) / 2f;
                final float cx = ox + (child.getLeft() + child.getRight()) / 2f;
                final float reveal = liquid.itemReveal(cx, cy, child.getHeight(), now);
                child.setAlpha(solegramItemAlpha[a] * reveal);
                child.setTranslationY(solegramItemTy[a] + liquid.itemShift(cy) * (1f - reveal));
            }
        }

        /** Solegram: the menu drawn as liquid; false to draw it the usual way. */
        private boolean solegramLiquidDraw(Canvas canvas) {
            final com.solegram.messenger.ui.SolegramLiquidPopup liquid = solegramLiquidPopup;
            if (liquid == null || !liquid.isActive()) {
                return false;
            }
            if (backgroundDrawable == null || reactionsEnterProgress != 1f || gapStartY != -1000000) {
                // something only the usual drawing knows (reactions, a split panel): the usual menu, shown at once
                liquid.finishClose();
                solegramLiquidEnd(null);
                return false;
            }
            final long now = System.nanoTime();
            solegramPanelRect(solegramPanel);
            solegramSetPanelBounds();
            if (!liquid.frame(solegramPanel, solegramPanelRadius, now)) {
                if (liquid.pendingTooLong()) {
                    // never got a size (or room): the usual menu, shown at once
                    liquid.finishClose();
                    solegramLiquidEnd(null);
                    return false;
                }
                postInvalidateOnAnimation(); // its first frame: it starts on the next one
                return true;
            }
            if (liquid.getState() == com.solegram.messenger.ui.SolegramLiquidPopup.STATE_CLOSED) {
                return true; // flowed back: the window goes away
            }
            final float alpha = liquid.alpha(now);
            liquid.drawShadow(canvas, alpha);
            liquid.group.draw(canvas, liquid.fill, alpha);
            solegramSetPanelBounds(); // the blurred fill may have moved them
            final Path outline = liquid.group.path;
            // the gaps between sections lie on the glass, inside its outline
            boolean hasGap = false;
            for (int i = 0; i < linearLayout.getChildCount(); i++) {
                if (linearLayout.getChildAt(i) instanceof GapView && linearLayout.getChildAt(i).getVisibility() == View.VISIBLE) {
                    hasGap = true;
                    break;
                }
            }
            if (hasGap && !outline.isEmpty()) {
                canvas.save();
                canvas.clipPath(outline);
                for (int i = 0; i < linearLayout.getChildCount(); i++) {
                    if (linearLayout.getChildAt(i) instanceof GapView && linearLayout.getChildAt(i).getVisibility() == View.VISIBLE) {
                        canvas.save();
                        float x = 0, y = 0;
                        GapView child = (GapView) linearLayout.getChildAt(i);
                        View view = child;
                        while (view != this) {
                            x += view.getX();
                            y += view.getY();
                            view = (View) view.getParent();
                            if (view == null) {
                                break;
                            }
                        }
                        canvas.translate(x, y * (scrollView == null ? 1f : scrollView.getScaleY()) - (scrollView == null ? 0 : scrollView.getScrollY()));
                        child.draw(canvas);
                        canvas.restore();
                    }
                }
                canvas.restore();
            }
            final boolean moving = liquid.isMoving();
            if (moving) {
                solegramRevealItems(liquid, now);
            }
            if (liquid.consumeSettled()) {
                solegramRestoreItems(true);
            }
            if (moving) {
                // the content shows only where the liquid has reached
                if (!outline.isEmpty()) {
                    canvas.save();
                    canvas.clipPath(outline);
                    super.dispatchDraw(canvas);
                    canvas.restore();
                }
            } else if (clipChildren) {
                canvas.save();
                canvas.clipRect(solegramPanel);
                super.dispatchDraw(canvas);
                canvas.restore();
            } else {
                super.dispatchDraw(canvas);
            }
            if (liquid.isAnimating(now)) {
                postInvalidateOnAnimation();
            }
            return true;
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent ev) {
            if (solegramLiquidPopup != null && solegramLiquidPopup.isClosing()) {
                return true; // Solegram: flowing back, its items no longer take taps
            }
            return super.dispatchTouchEvent(ev);
        }

        boolean solegramLiquidClosing() {
            return solegramLiquidPopup != null && solegramLiquidPopup.isClosing();
        }

        int solegramLiquidCloseFrames() {
            return solegramLiquidPopup != null ? solegramLiquidPopup.closeFrames() : 0;
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            solegramManaged = false; // its next window (or none) decides again
            if (solegramLiquidActive()) {
                // taken out of its window mid-way: a window waiting for it closes, the menu is the usual one again
                solegramLiquidPopup.finishClose();
                solegramLiquidEnd(null);
            }
        }
        // endregion

        public ActionBarPopupWindowLayout(Context context, int resId, Theme.ResourcesProvider resourcesProvider, int flags) {
            super(context);
            this.resourcesProvider = resourcesProvider;

            if (resId != 0) {
                backgroundDrawable = getResources().getDrawable(resId).mutate();
                setPadding(dp(8), dp(8), dp(8), dp(8));
                // Solegram: this panel, for the liquid drawn in its place (all popup_fixed_alert* have the menu corners)
                solegramOwnPanel = backgroundDrawable;
            }
            if (backgroundDrawable != null) {
                backgroundDrawable.getPadding(bgPaddings);
                setBackgroundColor(getThemedColor(Theme.key_actionBarDefaultSubmenuBackground));
            }


            setWillNotDraw(false);

            if ((flags & FLAG_SHOWN_FROM_BOTTOM) > 0) {
                shownFromBottom = true;
            }

            if ((flags & FLAG_USE_SWIPEBACK) > 0) {
                swipeBackLayout = new PopupSwipeBackLayout(context, resourcesProvider);
                addView(swipeBackLayout, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
            }

            if ((flags & FLAG_DONT_USE_SCROLLVIEW) == 0) {
                try {
                    scrollView = new ScrollView(context);
                    scrollView.getViewTreeObserver().addOnScrollChangedListener(new ViewTreeObserver.OnScrollChangedListener() {
                        @Override
                        public void onScrollChanged() {
                            invalidate();
                        }
                    });
                    scrollView.setVerticalScrollBarEnabled(false);
                    if (swipeBackLayout != null) {
                        swipeBackLayout.addView(scrollView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, shownFromBottom ? Gravity.BOTTOM : Gravity.TOP));
                    } else {
                        addView(scrollView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
                    }
                } catch (Throwable e) {
                    FileLog.e(e);
                }
            }

            linearLayout = new LinearLayout(context) {
                @Override
                protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                    if (fitItems) {
                        int maxWidth = 0;
                        int fixWidth = 0;
                        gapStartY = -1000000;
                        gapEndY = -1000000;
                        ArrayList<View> viewsToFix = null;
                        for (int a = 0, N = getChildCount(); a < N; a++) {
                            View view = getChildAt(a);
                            if (view.getVisibility() == GONE) {
                                continue;
                            }
                            Object tag = view.getTag(R.id.width_tag);
                            Object tag2 = view.getTag(R.id.object_tag);
                            Object fitToWidth = view.getTag(R.id.fit_width_tag);
                            if (tag != null) {
                                view.getLayoutParams().width = LayoutHelper.WRAP_CONTENT;
                            }
                            measureChildWithMargins(view, widthMeasureSpec, 0, heightMeasureSpec, 0);
                            if (fitToWidth != null) {

                            } else if (!(tag instanceof Integer) && tag2 == null) {
                                maxWidth = Math.max(maxWidth, view.getMeasuredWidth());
                                continue;
                            } else if (tag instanceof Integer) {
                                fixWidth = Math.max((Integer) tag, view.getMeasuredWidth());
                                gapStartY = view.getMeasuredHeight();
                                gapEndY = gapStartY + dp(6);
                            }
                            if (viewsToFix == null) {
                                viewsToFix = new ArrayList<>();
                            }
                            viewsToFix.add(view);
                        }
                        if (viewsToFix != null) {
                            for (int a = 0, N = viewsToFix.size(); a < N; a++) {
                                viewsToFix.get(a).getLayoutParams().width = Math.max(maxWidth, fixWidth);
                            }
                        }
                    }
                    super.onMeasure(widthMeasureSpec, heightMeasureSpec);
                }

                @Override
                protected boolean drawChild(Canvas canvas, View child, long drawingTime) {
                    if (child instanceof GapView && backgroundDrawable != null) {
                        return false;
                    }
                    return super.drawChild(canvas, child, drawingTime);
                }
            };
            linearLayout.setOrientation(LinearLayout.VERTICAL);
            if (scrollView != null) {
                scrollView.addView(linearLayout, new ScrollView.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            } else if (swipeBackLayout != null) {
                swipeBackLayout.addView(linearLayout, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, shownFromBottom ? Gravity.BOTTOM : Gravity.TOP));
            } else {
                addView(linearLayout, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
            }
            if (resId != 0 && swipeBackLayout == null) {
                // Solegram: the items and their highlights keep to the panel's round corners (swipe-back clips its own)
                final View content = scrollView != null ? scrollView : linearLayout;
                content.setOutlineProvider(SOLEGRAM_MENU_OUTLINE);
                content.setClipToOutline(true);
            }
        }

        /** Solegram: the round corners of a menu panel, for its content filling the panel. */
        public static final android.view.ViewOutlineProvider SOLEGRAM_MENU_OUTLINE = new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                final int w = view.getWidth(), h = view.getHeight();
                outline.setRoundRect(0, 0, w, h, Math.min(com.solegram.messenger.SolegramGlass.menuRadius(), Math.min(w, h) / 2f));
            }
        };

        @Nullable
        public PopupSwipeBackLayout getSwipeBack() {
            return swipeBackLayout;
        }

        public int addViewToSwipeBack(View v) {
            swipeBackLayout.addView(v, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, shownFromBottom ? Gravity.BOTTOM : Gravity.TOP));
            return swipeBackLayout.getChildCount() - 1;
        }

        public void setFitItems(boolean value) {
            fitItems = value;
        }

        public void setShownFromBottom(boolean value) {
            shownFromBottom = value;
        }

        public void setDispatchKeyEventListener(OnDispatchKeyEventListener listener) {
            mOnDispatchKeyEventListener = listener;
        }

        public int getBackgroundColor() {
            return backgroundColor;
        }

        public void setBackgroundColor(int color) {
            if (backgroundColor != color && backgroundDrawable != null) {
                backgroundDrawable.setColorFilter(new PorterDuffColorFilter(backgroundColor = color, PorterDuff.Mode.MULTIPLY));
            }
        }

        @Keep
        public void setBackAlpha(int value) {
            if (backAlpha != value) {
                invalidate();
            }
            backAlpha = value;
        }

        @Keep
        public int getBackAlpha() {
            return backAlpha;
        }

        @Keep
        public void setBackScaleX(float value) {
            if (backScaleX != value) {
                backScaleX = value;
                invalidate();
                if (onSizeChangedListener != null) {
                    onSizeChangedListener.onSizeChanged();
                }
            }
        }

        @Keep
        public void setBackScaleY(float value) {
            if (backScaleY != value) {
                backScaleY = value;
                if (animationEnabled && updateAnimation) {
                    int height = getMeasuredHeight() - dp(16);
                    if (shownFromBottom) {
                        for (int a = lastStartedChild; a >= 0; a--) {
                            View child = getItemAt(a);
                            if (child == null || child.getVisibility() != VISIBLE || child instanceof GapView) {
                                continue;
                            }
                            Integer position = positions.get(child);
                            if (position != null && height - (position * dp(48) + dp(32)) > value * height) {
                                break;
                            }
                            lastStartedChild = a - 1;
                            startChildAnimation(child);
                        }
                    } else {
                        int count = getItemsCount();
                        int h = 0;
                        for (int a = 0; a < count; a++) {
                            View child = getItemAt(a);
                            if (child.getVisibility() != VISIBLE) {
                                continue;
                            }
                            h += child.getMeasuredHeight();
                            if (a < lastStartedChild) {
                                continue;
                            }
                            Integer position = positions.get(child);
                            if (position != null && h - dp(24) > value * height) {
                                break;
                            }
                            lastStartedChild = a + 1;
                            startChildAnimation(child);
                        }
                    }
                }
                invalidate();
                if (onSizeChangedListener != null) {
                    onSizeChangedListener.onSizeChanged();
                }
            }
        }

        @Override
        public void setBackgroundDrawable(Drawable drawable) {
            if (solegramGlass != null && drawable != solegramGlass.drawable && drawable != solegramPlainBackground) {
                // Solegram: a panel of its own replaced our glass (story menus' blur, say): the glass lets go
                solegramGlass.release();
                solegramGlass = null;
                solegramGlassHost = null;
                solegramPlainBackground = null;
            }
            backgroundColor = Color.WHITE;
            backgroundDrawable = drawable;
            if (backgroundDrawable != null) {
                backgroundDrawable.getPadding(bgPaddings);
            }
        }

        private void startChildAnimation(View child) {
            if (animationEnabled) {
                AnimatorSet animatorSet = new AnimatorSet();
                animatorSet.playTogether(
                        ObjectAnimator.ofFloat(child, View.ALPHA, 0f, child.isEnabled() ? 1f : 0.5f),
                        ObjectAnimator.ofFloat(child, View.TRANSLATION_Y, dp(shownFromBottom ? 6 : -6), 0));
                animatorSet.setDuration(180);
                animatorSet.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        itemAnimators.remove(animatorSet);

                        if (child instanceof ActionBarMenuSubItem) {
                            ((ActionBarMenuSubItem) child).onItemShown();
                        }
                    }
                });
                animatorSet.setInterpolator(decelerateInterpolator);
                animatorSet.start();
                if (itemAnimators == null) {
                    itemAnimators = new ArrayList<>();
                }
                itemAnimators.add(animatorSet);
            }
        }

        public void setAnimationEnabled(boolean value) {
            animationEnabled = value;
        }

        @Override
        public void addView(View child) {
            linearLayout.addView(child);
        }

        public void addView(View child, LinearLayout.LayoutParams layoutParams) {
            linearLayout.addView(child, layoutParams);
        }

        public int getViewsCount() {
            return linearLayout.getChildCount();
        }

        public int precalculateHeight() {
            int MOST_SPEC = View.MeasureSpec.makeMeasureSpec(dp(1000), View.MeasureSpec.AT_MOST);
            linearLayout.measure(MOST_SPEC, MOST_SPEC);
            return linearLayout.getMeasuredHeight();
        }

        public void removeInnerViews() {
            linearLayout.removeAllViews();
        }

        public float getBackScaleX() {
            return backScaleX;
        }

        public float getBackScaleY() {
            return backScaleY;
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent event) {
            if (mOnDispatchKeyEventListener != null) {
                mOnDispatchKeyEventListener.onDispatchKeyEvent(event);
            }
            return super.dispatchKeyEvent(event);
        }

        Path path;

        @Override
        protected void dispatchDraw(Canvas canvas) {
            if (swipeBackGravityRight) {
                setTranslationX(getMeasuredWidth() * (1f - backScaleX));
                if (topView != null) {
                    topView.setTranslationX(getMeasuredWidth() * (1f - backScaleX));
                    topView.setAlpha(1f - swipeBackLayout.transitionProgress);
                    float h = topView.getMeasuredHeight() - dp(16);
                    float yOffset = -h * swipeBackLayout.transitionProgress;
                    topView.setTranslationY(yOffset);
                    setTranslationY(yOffset);
                }
            }
            if (swipeBackGravityBottom) {
                setTranslationY(getMeasuredHeight() * (1f - backScaleY));
            }
            if (solegramLiquidDraw(canvas)) {
                return; // Solegram: drawn as liquid
            }
            solegramUpdateGlassOffset();
            if (backgroundDrawable != null) {
                int start = gapStartY - (scrollView == null ? 0 : scrollView.getScrollY());
                int end = gapEndY - (scrollView == null ? 0 : scrollView.getScrollY());
                boolean hasGap = false;
                for (int i = 0; i < linearLayout.getChildCount(); i++) {
                    if (linearLayout.getChildAt(i) instanceof GapView && linearLayout.getChildAt(i).getVisibility() == View.VISIBLE) {
                        hasGap = true;
                        break;
                    }
                }
                for (int a = 0; a < 2; a++) {
                    if (a == 1 && start < -dp(16)) {
                        break;
                    }
                    int saveCount = canvas.getSaveCount();
                    boolean applyAlpha = true;
                    if (hasGap && backAlpha != 255) {
                        canvas.saveLayerAlpha(0, bgPaddings.top, getMeasuredWidth(), getMeasuredHeight(), backAlpha, Canvas.ALL_SAVE_FLAG);
                        applyAlpha = false;
                    }  else if (gapStartY != -1000000) {
                        canvas.save();
                        canvas.clipRect(0, bgPaddings.top, getMeasuredWidth(), getMeasuredHeight());
                    }
                    final int backgroundAlpha = applyAlpha ? backAlpha : 255;
                    // Solegram: the glass redraws itself on every alpha change, so only real changes go through
                    if (solegramGlass == null || backgroundDrawable != solegramGlass.drawable || solegramGlass.drawable.getAlpha() != backgroundAlpha) {
                        backgroundDrawable.setAlpha(backgroundAlpha);
                    }
                    if (shownFromBottom) {
                        final int height = getMeasuredHeight();
                        AndroidUtilities.rectTmp2.set(0, (int) (height * (1.0f - backScaleY)), (int) (getMeasuredWidth() * backScaleX), height);
                    } else {
                        if (start > -dp(16)) {
                            int h = (int) (getMeasuredHeight() * backScaleY);
                            if (a == 0) {
                                if (swipeBackLayout != null && swipeBackLayout.stickToRight) {
                                    AndroidUtilities.rectTmp2.set(getMeasuredWidth() - (int) (getMeasuredWidth() * backScaleX), (scrollView == null ? 0 : -scrollView.getScrollY()) + (gapStartY != -1000000 ? dp(1) : 0), getMeasuredWidth(), (gapStartY != -1000000 ? Math.min(h, start + dp(16)) : h) - subtractBackgroundHeight);
                                } else {
                                    AndroidUtilities.rectTmp2.set(0, (scrollView == null ? 0 : -scrollView.getScrollY()) + (gapStartY != -1000000 ? dp(1) : 0), (int) (getMeasuredWidth() * backScaleX), (gapStartY != -1000000 ? Math.min(h, start + dp(16)) : h) - subtractBackgroundHeight);
                                }
                            } else {
                                if (h < end) {
                                    if (gapStartY != -1000000) {
                                        canvas.restore();
                                    }
                                    continue;
                                }
                                if (swipeBackLayout != null && swipeBackLayout.stickToRight) {
                                    AndroidUtilities.rectTmp2.set(getMeasuredWidth() - (int) (getMeasuredWidth() * backScaleX), end, getMeasuredWidth(), h - subtractBackgroundHeight);
                                } else {
                                    AndroidUtilities.rectTmp2.set(0, end, (int) (getMeasuredWidth() * backScaleX), h - subtractBackgroundHeight);
                                }
                            }
                        } else {
                            if (swipeBackLayout != null && swipeBackLayout.stickToRight) {
                                AndroidUtilities.rectTmp2.set(getMeasuredWidth() - (int) (getMeasuredWidth() * backScaleX), (gapStartY < 0 ? 0 : -dp(16)), getMeasuredWidth(), (int) (getMeasuredHeight() * backScaleY) - subtractBackgroundHeight);
                            } else {
                                AndroidUtilities.rectTmp2.set(0, (gapStartY < 0 ? 0 : -dp(16)), (int) (getMeasuredWidth() * backScaleX), (int) (getMeasuredHeight() * backScaleY) - subtractBackgroundHeight);
                            }
                        }
                    }
                    if (reactionsEnterProgress != 1f) {
                        if (rect == null) {
                            rect = new Rect();
                        }
                        rect.set(AndroidUtilities.rectTmp2.right, AndroidUtilities.rectTmp2.top, AndroidUtilities.rectTmp2.right, AndroidUtilities.rectTmp2.top);
                        AndroidUtilities.lerp(rect, AndroidUtilities.rectTmp2, reactionsEnterProgress, AndroidUtilities.rectTmp2);
                    }
                    backgroundDrawable.setBounds(AndroidUtilities.rectTmp2);
                    backgroundDrawable.draw(canvas);
                    if (clipChildren) {
                        AndroidUtilities.rectTmp2.left += bgPaddings.left;
                        AndroidUtilities.rectTmp2.top += bgPaddings.top;
                        AndroidUtilities.rectTmp2.right -= bgPaddings.right;
                        AndroidUtilities.rectTmp2.bottom -= bgPaddings.bottom;
                        canvas.clipRect(AndroidUtilities.rectTmp2);
                    }
                    if (hasGap) {
                        canvas.save();
                        AndroidUtilities.rectTmp.set(backgroundDrawable.getBounds());
                        AndroidUtilities.rectTmp.inset(dp(8), dp(8));
                        if (path == null) {
                            path = new Path();
                        } else {
                            path.rewind();
                        }
                        final float radius = com.solegram.messenger.SolegramGlass.menuRadius(); // Solegram: the menu corners
                        path.addRoundRect(AndroidUtilities.rectTmp, radius, radius, Path.Direction.CW);
                        canvas.clipPath(path);
                        for (int i = 0; i < linearLayout.getChildCount(); i++) {
                            if (linearLayout.getChildAt(i) instanceof GapView && linearLayout.getChildAt(i).getVisibility() == View.VISIBLE) {
                                canvas.save();
                                float x = 0, y = 0;
                                GapView child = (GapView) linearLayout.getChildAt(i);
                                View view = child;
                                while (view != this) {
                                    x += view.getX();
                                    y += view.getY();
                                    view = (View) view.getParent();
                                    if (view == null) {
                                        break;
                                    }
                                }
                                canvas.translate(x, y * (scrollView == null ? 1f : scrollView.getScaleY()) - (scrollView == null ? 0 : scrollView.getScrollY()));
                                child.draw(canvas);
                                canvas.restore();
                            }
                        }
                        canvas.restore();
                    }
                    canvas.restoreToCount(saveCount);
                }
            }
            if (reactionsEnterProgress != 1f) {
                canvas.saveLayerAlpha((float) AndroidUtilities.rectTmp2.left, (float) AndroidUtilities.rectTmp2.top, AndroidUtilities.rectTmp2.right, AndroidUtilities.rectTmp2.bottom, (int) (255 * reactionsEnterProgress), Canvas.ALL_SAVE_FLAG);
                float scale = 0.5f + reactionsEnterProgress * 0.5f;
                canvas.scale(scale, scale, AndroidUtilities.rectTmp2.right, AndroidUtilities.rectTmp2.top);
                super.dispatchDraw(canvas);
                canvas.restore();
            } else {
                super.dispatchDraw(canvas);
            }
        }

        public Drawable getBackgroundDrawable() {
            return backgroundDrawable;
        }

        public int getItemsCount() {
            return linearLayout.getChildCount();
        }

        public View getItemAt(int index) {
            return linearLayout.getChildAt(index);
        }

        public void scrollToTop() {
            if (scrollView != null) {
                scrollView.scrollTo(0, 0);
            }
        }

        public void setupRadialSelectors(int color) {
            int count = linearLayout.getChildCount();
            for (int a = 0; a < count; a++) {
                View child = linearLayout.getChildAt(a);
                final int r = (int) com.solegram.messenger.SolegramGlass.MENU_RADIUS_DP; // Solegram: the menu corners
                child.setBackground(Theme.createRadSelectorDrawable(color, a == 0 ? r : 0, a == count - 1 ? r : 0));
            }
        }

        public void updateRadialSelectors() {
            int count = linearLayout.getChildCount();
            View firstVisible = null;
            View lastVisible = null;
            for (int a = 0; a < count; a++) {
                View child = linearLayout.getChildAt(a);
                if (child.getVisibility() != View.VISIBLE) {
                    continue;
                }
                if (firstVisible == null) {
                    firstVisible = child;
                }
                lastVisible = child;
            }

            boolean prevGap = false;
            for (int a = 0; a < count; a++) {
                View child = linearLayout.getChildAt(a);
                if (child.getVisibility() != View.VISIBLE) {
                    continue;
                }
                Object tag = child.getTag(R.id.object_tag);
                if (child instanceof ActionBarMenuSubItem) {
                    ((ActionBarMenuSubItem) child).updateSelectorBackground(child == firstVisible || prevGap, child == lastVisible);
                }
                if (tag != null) {
                    prevGap = true;
                } else {
                    prevGap = false;
                }
            }
        }

        protected int getThemedColor(int key) {
            return Theme.getColor(key, resourcesProvider);
        }

        public void setOnSizeChangedListener(ActionBarPopupWindow.onSizeChangedListener onSizeChangedListener) {
            this.onSizeChangedListener = onSizeChangedListener;
        }

        public int getVisibleHeight() {
            return (int) (getMeasuredHeight() * backScaleY);
        }

        public void setTopView(View topView) {
            this.topView = topView;
        }

        public void setSwipeBackForegroundColor(int color) {
            getSwipeBack().setForegroundColor(color);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            if (swipeBackLayout != null) {
                swipeBackLayout.invalidateTransforms(!startAnimationPending);
            }
        }

        public void setParentWindow(ActionBarPopupWindow popupWindow) {
            window = popupWindow;
        }

        public void setReactionsTransitionProgress(float transitionEnterProgress) {
            this.reactionsEnterProgress = transitionEnterProgress;
            invalidate();
        }
    }

    public ActionBarPopupWindow() {
        super();
        init();
    }

    public ActionBarPopupWindow(Context context) {
        super(context);
        init();
    }

    public ActionBarPopupWindow(int width, int height) {
        super(width, height);
        init();
    }

    public ActionBarPopupWindow(View contentView) {
        super(contentView);
        init();
    }

    public ActionBarPopupWindow(View contentView, int width, int height, boolean focusable) {
        super(contentView, width, height, focusable);
        init();
    }

    public ActionBarPopupWindow(View contentView, int width, int height) {
        super(contentView, width, height);
        init();
    }

    public void setAnimationEnabled(boolean value) {
        animationEnabled = value;
    }

    @SuppressWarnings("PrivateAPI")
    public void setLayoutInScreen(boolean value) {
        try {
            if (layoutInScreenMethod == null) {
                layoutInScreenMethod = PopupWindow.class.getDeclaredMethod("setLayoutInScreenEnabled", boolean.class);
                layoutInScreenMethod.setAccessible(true);
            }
            layoutInScreenMethod.invoke(this, true);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private void init() {
        View contentView = getContentView();
        if (contentView instanceof ActionBarPopupWindowLayout && ((ActionBarPopupWindowLayout) contentView).getSwipeBack() != null) {
            setTouchInterceptor((v, e) -> {
                if (e.getAction() == MotionEvent.ACTION_DOWN) {
                    Drawable backgroundDrawable = ((ActionBarPopupWindowLayout) contentView).getBackgroundDrawable();
                    AndroidUtilities.rectTmp.set(backgroundDrawable.getBounds());
                    AndroidUtilities.rectTmp.offset(contentView.getX(), contentView.getY());
                    if (!AndroidUtilities.rectTmp.contains(e.getX(), e.getY())) {
                        dismiss();
                        return true;
                    }
                }
                return false;
            });
        }
        if (superListenerField != null) {
            try {
                mSuperScrollListener = (ViewTreeObserver.OnScrollChangedListener) superListenerField.get(this);
                superListenerField.set(this, NOP);
            } catch (Exception e) {
                mSuperScrollListener = null;
            }
        }
        super.setTouchInterceptor(solegramInterceptor); // Solegram: taps in the room round a liquid menu
    }

    public void setDismissAnimationDuration(int value) {
        dismissAnimationDuration = value;
    }

    private void unregisterListener() {
        if (mSuperScrollListener != null && mViewTreeObserver != null) {
            if (mViewTreeObserver.isAlive()) {
                mViewTreeObserver.removeOnScrollChangedListener(mSuperScrollListener);
            }
            mViewTreeObserver = null;
        }
    }

    private void registerListener(View anchor) {
        if (mSuperScrollListener != null) {
            ViewTreeObserver vto = (anchor.getWindowToken() != null) ? anchor.getViewTreeObserver() : null;
            if (vto != mViewTreeObserver) {
                if (mViewTreeObserver != null && mViewTreeObserver.isAlive()) {
                    mViewTreeObserver.removeOnScrollChangedListener(mSuperScrollListener);
                }
                if ((mViewTreeObserver = vto) != null) {
                    vto.addOnScrollChangedListener(mSuperScrollListener);
                }
            }
        }
    }

    public void dimBehind() {
        dimBehind(0.2f);
    }

    /** Solegram: off for a popup whose menus keep their plain panel. */
    public boolean solegramAllowGlass = true;

    /** Solegram "glass menus": the menus in this window are drawn as the app's glass (see ActionBarPopupWindowLayout.solegramUseGlass). */
    private void solegramApplyGlass() {
        try {
            final boolean glass = solegramAllowGlass && com.solegram.messenger.ui.SolegramLiveGlass.menusOn();
            final View root = getContentView().getRootView();
            for (int i = 0; i < solegramPanels.size(); i++) {
                if (glass) {
                    solegramPanels.get(i).solegramUseGlass(root);
                } else {
                    solegramPanels.get(i).solegramDropGlass();
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    // Solegram: the menu panels in this window - the content, or panels in a wrapper (with a top view, the message menu's container)
    private final ArrayList<ActionBarPopupWindowLayout> solegramPanels = new ArrayList<>();

    /** Solegram: finds the panels before the window shows them; they leave their glass to this window. */
    private void solegramCollectPanels() {
        solegramPanels.clear();
        solegramCollectPanels(getContentView(), 0);
        for (int i = 0; i < solegramPanels.size(); i++) {
            solegramPanels.get(i).solegramManaged = true;
        }
    }

    private void solegramCollectPanels(View view, int depth) {
        if (view instanceof ActionBarPopupWindowLayout) {
            if (((ActionBarPopupWindowLayout) view).getBackgroundDrawable() != null) {
                solegramPanels.add((ActionBarPopupWindowLayout) view);
            }
            return; // its pages are not panels of their own
        }
        if (view instanceof ViewGroup && depth < 3) {
            final ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                solegramCollectPanels(group.getChildAt(i), depth + 1);
            }
        }
    }

    public void dimBehind(float amount) {
        View container = getContentView().getRootView();
        Context context = getContentView().getContext();
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        WindowManager.LayoutParams p = (WindowManager.LayoutParams) container.getLayoutParams();
        p.flags |= WindowManager.LayoutParams.FLAG_DIM_BEHIND;
        p.dimAmount = amount;
        wm.updateViewLayout(container, p);
    }

    public void setFocusableFlag(boolean enable) {
        View container = getContentView().getRootView();
        Context context = getContentView().getContext();
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        WindowManager.LayoutParams p = (WindowManager.LayoutParams) container.getLayoutParams();

        if (p != null) {
            if (enable) {
                p.flags |= WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;
            } else {
                p.flags &= ~WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;
            }
            wm.updateViewLayout(container, p);
        }
    }

    private void dismissDim() {
        View container = getContentView().getRootView();
        Context context = getContentView().getContext();
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);

        if (container.getLayoutParams() == null || !(container.getLayoutParams() instanceof WindowManager.LayoutParams)) {
            return;
        }
        WindowManager.LayoutParams p = (WindowManager.LayoutParams) container.getLayoutParams();
        try {
            if ((p.flags & WindowManager.LayoutParams.FLAG_DIM_BEHIND) != 0) {
                p.flags &= ~WindowManager.LayoutParams.FLAG_DIM_BEHIND;
                p.dimAmount = 0.0f;
                wm.updateViewLayout(container, p);
            }
        } catch (Exception e) {

        }
    }

    @Override
    public void showAsDropDown(View anchor, int xoff, int yoff) {
        try {
            super.showAsDropDown(anchor, xoff, yoff); // shows through showAsDropDown(anchor, xoff, yoff, gravity) below
            registerListener(anchor);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    @Override
    public void showAsDropDown(View anchor, int xoff, int yoff, int gravity) {
        if (isShowing()) {
            super.showAsDropDown(anchor, xoff, yoff, gravity); // does nothing: shown already, its liquid stays as it is
            return;
        }
        // Solegram: a liquid menu has no window animation, the liquid is its animation
        final ActionBarPopupWindowLayout liquid = solegramLiquidTarget();
        if (liquid != null) {
            setAnimationStyle(0);
        }
        final boolean spring = liquid == null && solegramPrepareSpring();
        solegramRoomL = solegramRoomT = solegramRoomR = solegramRoomB = 0;
        solegramCollectPanels();
        super.showAsDropDown(anchor, xoff, yoff, gravity);
        if (liquid == null || !solegramStartLiquid(liquid, anchor, false)) {
            solegramApplyGlass();
        }
        if (spring) {
            solegramSpringIn(anchor);
        }
    }

    // region Solegram motion: a glass menu grows out of what opened it on a spring and shrinks back into it

    private static final float SOLEGRAM_SCALE_FROM = 0.84f, SOLEGRAM_SCALE_TO = 0.9f;
    // the window animation given up for the spring (-2: none), set again for a show without it
    private int solegramTakenStyle = -2;
    private boolean solegramSprung;
    @Nullable
    private ValueAnimator solegramOpenAnimator;
    @Nullable
    private View solegramMotionAnchor;
    private float solegramPivotX = Float.NaN, solegramPivotY;
    private final int[] solegramMotionLoc = new int[2];

    /**
     * Before a show: the spring replaces the window animation for glass menus that use the usual one (a menu with
     * no window animation animates its own way); everything a previous spring left on the content is undone.
     */
    private boolean solegramPrepareSpring() {
        if (solegramOpenAnimator != null) {
            final ValueAnimator running = solegramOpenAnimator;
            solegramOpenAnimator = null;
            running.cancel();
        }
        final View content = getContentView();
        if (solegramSprung && content != null) {
            content.setScaleX(1f);
            content.setScaleY(1f);
            content.setAlpha(1f);
        }
        solegramSprung = false;
        solegramMotionAnchor = null;
        final boolean wanted = solegramSpringAllowed() && (solegramTakenStyle != -2 || getAnimationStyle() != 0);
        if (wanted) {
            if (solegramTakenStyle == -2) {
                solegramTakenStyle = getAnimationStyle();
            }
            setAnimationStyle(0);
        } else if (solegramTakenStyle != -2) {
            setAnimationStyle(solegramTakenStyle);
            solegramTakenStyle = -2;
        }
        return wanted;
    }

    /** Glass menus of this window may spring (not those that animate their own way: no liquid, no glass). */
    private boolean solegramSpringAllowed() {
        return solegramLiquid && solegramAllowGlass && animationEnabled && getContentView() != null
            && com.solegram.messenger.SolegramConfig.glassMenus && com.solegram.messenger.ui.SolegramMotion.enabled();
    }

    /** Just shown: the menu scales up from the point nearest to what opened it, with a soft overshoot. */
    private void solegramSpringIn(@Nullable View opener) {
        final View content = getContentView();
        solegramSprung = true;
        solegramMotionAnchor = solegramOriginView != null ? solegramOriginView : opener;
        solegramPivotX = Float.NaN;
        content.setAlpha(0f);
        content.setScaleX(SOLEGRAM_SCALE_FROM);
        content.setScaleY(SOLEGRAM_SCALE_FROM);
        final ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setInterpolator(null); // linear time: the spring curve and the fade are applied below
        animator.setDuration(com.solegram.messenger.ui.SolegramMotion.SPRING);
        animator.addUpdateListener(a -> {
            final float t = a.getAnimatedFraction();
            solegramApplyPivot(content);
            final float s = SOLEGRAM_SCALE_FROM + (1f - SOLEGRAM_SCALE_FROM) * com.solegram.messenger.ui.SolegramMotion.SPRING_GENTLE.getInterpolation(t);
            content.setScaleX(s);
            content.setScaleY(s);
            content.setAlpha(com.solegram.messenger.ui.SolegramMotion.EASE_OUT.getInterpolation(Math.min(1f, t * 2.4f)));
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (solegramOpenAnimator == animation) {
                    solegramOpenAnimator = null;
                    content.setScaleX(1f);
                    content.setScaleY(1f);
                    content.setAlpha(1f);
                }
            }
        });
        solegramOpenAnimator = animator;
        animator.start();
    }

    /**
     * The point the menu grows from and shrinks into: what opened it (its centre, kept inside the menu), else the
     * menu's corner toward the middle of the screen's nearest edges. Found once the menu is laid out.
     */
    private void solegramApplyPivot(View content) {
        if (Float.isNaN(solegramPivotX)) {
            if (!content.isLaidOut() || content.getWidth() <= 0 || content.getHeight() <= 0) {
                return;
            }
            // the menu on screen without its scale: its window's place plus its offset in the window
            final View root = content.getRootView();
            root.getLocationOnScreen(solegramMotionLoc);
            float x = solegramMotionLoc[0], y = solegramMotionLoc[1];
            for (View v = content; v != root && v != null; v = v.getParent() instanceof View ? (View) v.getParent() : null) {
                x += v.getLeft();
                y += v.getTop();
            }
            final int w = content.getWidth(), h = content.getHeight();
            final View anchor = solegramMotionAnchor;
            float ax, ay;
            if (solegramOriginRect != null) {
                ax = solegramOriginRect.centerX();
                ay = solegramOriginRect.centerY();
            } else if (anchor != null && anchor.isAttachedToWindow() && anchor != anchor.getRootView()
                && anchor.getWidth() > 0 && anchor.getWidth() <= dp(200) && anchor.getHeight() > 0 && anchor.getHeight() <= dp(200)) {
                anchor.getLocationOnScreen(solegramMotionLoc);
                ax = solegramMotionLoc[0] + anchor.getWidth() * anchor.getScaleX() / 2f;
                ay = solegramMotionLoc[1] + anchor.getHeight() * anchor.getScaleY() / 2f;
            } else {
                // shown at a place: menus open toward the middle of the screen, from the corner nearest its edge
                ax = x + w / 2f > AndroidUtilities.displaySize.x / 2f ? x + w : x;
                ay = y + h / 2f > AndroidUtilities.displaySize.y / 2f ? y + h : y;
            }
            solegramPivotX = Math.max(0, Math.min(w, ax - x));
            solegramPivotY = Math.max(0, Math.min(h, ay - y));
        }
        content.setPivotX(solegramPivotX);
        content.setPivotY(solegramPivotY);
    }
    // endregion

    // region Solegram liquid: the menu flows out of what opened it and back into it

    /** Solegram: off for a menu that animates its own way (the chat's message menu, say). */
    public boolean solegramLiquid = true;
    @Nullable
    private View solegramOriginView;
    @Nullable
    private RectF solegramOriginRect;
    private float solegramOriginRadius = -1;
    @Nullable
    private ActionBarPopupWindowLayout solegramLiquidLayout;
    // transparent room round the menu (px) for the neck to an origin outside it; the menu stays where it was put
    private int solegramRoomL, solegramRoomT, solegramRoomR, solegramRoomB;
    private final int[] solegramLoc = new int[2];
    private final Rect solegramFrame = new Rect();
    @Nullable
    private View.OnTouchListener solegramUserInterceptor;
    @Nullable
    private Runnable solegramCloseTimeout;
    // made untouchable for its flowing back: touchable again for a later show of this window
    private boolean solegramUntouchable;
    // a view of the screen under the menu: the visible part of the screen is read from its window
    @Nullable
    private View solegramScreenView;

    /**
     * Solegram: the menu flows out of this view and back into it (its bounds), or out of {@code screenRect} (px on
     * screen, moving with the view) with the given corner radius (-1 to guess). Set before showing.
     */
    public void solegramSetLiquidOrigin(@Nullable View view, @Nullable RectF screenRect, float radius) {
        solegramOriginView = view;
        solegramOriginRect = screenRect != null ? new RectF(screenRect) : null;
        solegramOriginRadius = radius;
    }

    /** The menu that flows in this window: the content, or the only shown child of a plain wrapper; null for none. */
    @Nullable
    private ActionBarPopupWindowLayout solegramLiquidTarget() {
        if (!solegramLiquid || !animationEnabled || !com.solegram.messenger.ui.SolegramLiquid.enabled()) {
            return null;
        }
        if (android.os.Build.VERSION.SDK_INT >= 26 && !ValueAnimator.areAnimatorsEnabled()) {
            return null; // animations are off on the device: the menu just shows
        }
        final View content = getContentView();
        ActionBarPopupWindowLayout layout = null;
        if (content instanceof ActionBarPopupWindowLayout) {
            layout = (ActionBarPopupWindowLayout) content;
        } else if (content instanceof ViewGroup) {
            final ViewGroup group = (ViewGroup) content;
            for (int i = 0; i < group.getChildCount(); i++) {
                final View child = group.getChildAt(i);
                if (child.getVisibility() == View.GONE) {
                    continue;
                }
                if (layout != null || !(child instanceof ActionBarPopupWindowLayout)) {
                    return null; // other things in the window would appear without an animation
                }
                layout = (ActionBarPopupWindowLayout) child;
            }
        }
        return layout != null && layout.solegramLiquidAllowed() ? layout : null;
    }

    /** Starts the liquid of a just shown menu; false when it can't (the menu is then shown the usual way). */
    private boolean solegramStartLiquid(ActionBarPopupWindowLayout layout, @Nullable View opener, boolean atLocation) {
        try {
            View originView = solegramOriginView;
            RectF originRect = solegramOriginRect;
            if (originView == null && originRect == null && opener != null && opener.isAttachedToWindow() && opener != opener.getRootView()) {
                // shown at a view: the menu comes out of it if it is a button-sized thing (a window's root, or the
                // big view a menu is shown in only for its window, is not where it comes from)
                final int max = dp(atLocation ? 96 : 100000);
                if (opener.getWidth() > 0 && opener.getHeight() > 0 && opener.getWidth() <= max && opener.getHeight() <= max) {
                    originView = opener;
                }
            }
            solegramScreenView = originView != null ? originView : opener;
            final boolean glass = solegramAllowGlass && com.solegram.messenger.ui.SolegramLiveGlass.menusOn();
            if (layout.solegramLiquidPrepare(this, originView, originRect, solegramOriginRadius, glass, getContentView().getRootView())) {
                solegramLiquidLayout = layout;
                return true;
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return false;
    }

    /**
     * Solegram: grows the window toward an origin outside it (transparent margins round the content), keeping the
     * menu where it is on screen, so the liquid neck to the origin shows. Only as far as {@code reach} past the
     * origin's near edge (deeper inside the origin nothing of the liquid shows) and only within the visible part of
     * the screen (the system never moves such a window). True when the window changed.
     */
    @Override
    public boolean solegramEnsureRoom(RectF need, float reach) {
        try {
            final View content = getContentView();
            if (content == null || !isShowing() || getWidth() != ViewGroup.LayoutParams.WRAP_CONTENT || getHeight() != ViewGroup.LayoutParams.WRAP_CONTENT) {
                return false;
            }
            if (!(content.getParent() instanceof ViewGroup) || !(content.getLayoutParams() instanceof ViewGroup.MarginLayoutParams)) {
                return false;
            }
            final View root = content.getRootView();
            if (!(root.getLayoutParams() instanceof WindowManager.LayoutParams)) {
                return false;
            }
            final WindowManager.LayoutParams wp = (WindowManager.LayoutParams) root.getLayoutParams();
            final int hg = wp.gravity & Gravity.HORIZONTAL_GRAVITY_MASK, vg = wp.gravity & Gravity.VERTICAL_GRAVITY_MASK;
            final boolean hOk = hg == 0 || hg == Gravity.LEFT || hg == Gravity.RIGHT || hg == Gravity.CENTER_HORIZONTAL;
            final boolean vOk = vg == 0 || vg == Gravity.TOP || vg == Gravity.BOTTOM || vg == Gravity.CENTER_VERTICAL;
            if (!hOk || !vOk) {
                return false;
            }
            content.getLocationOnScreen(solegramLoc);
            final float cl = solegramLoc[0], ct = solegramLoc[1], cr = cl + content.getWidth(), cb = ct + content.getHeight();
            final float cap = dp(120) + reach;
            int l = need.left < cl ? (int) Math.ceil(Math.min(cap, Math.min(cl - need.left, Math.max(0, cl - need.right) + reach))) : 0;
            int t = need.top < ct ? (int) Math.ceil(Math.min(cap, Math.min(ct - need.top, Math.max(0, ct - need.bottom) + reach))) : 0;
            int r = need.right > cr ? (int) Math.ceil(Math.min(cap, Math.min(need.right - cr, Math.max(0, need.left - cr) + reach))) : 0;
            int b = need.bottom > cb ? (int) Math.ceil(Math.min(cap, Math.min(need.bottom - cb, Math.max(0, need.top - cb) + reach))) : 0;
            final View screen = solegramScreenView != null && solegramScreenView.isAttachedToWindow() ? solegramScreenView : content;
            screen.getWindowVisibleDisplayFrame(solegramFrame);
            if (solegramFrame.isEmpty()) {
                return false;
            }
            solegramFrame.top = Math.max(solegramFrame.top, AndroidUtilities.statusBarHeight);
            l = Math.max(solegramRoomL, Math.max(0, Math.min(l, (int) cl - solegramFrame.left)));
            t = Math.max(solegramRoomT, Math.max(0, Math.min(t, (int) ct - solegramFrame.top)));
            r = Math.max(solegramRoomR, Math.max(0, Math.min(r, solegramFrame.right - (int) Math.ceil(cr))));
            b = Math.max(solegramRoomB, Math.max(0, Math.min(b, solegramFrame.bottom - (int) Math.ceil(cb))));
            if (hg == Gravity.CENTER_HORIZONTAL && ((r - l) & 1) != 0) {
                r = r > 0 ? r - 1 : r + 1; // a centred window grows evenly
            }
            if (vg == Gravity.CENTER_VERTICAL && ((b - t) & 1) != 0) {
                b = b > 0 ? b - 1 : b + 1;
            }
            if (l == solegramRoomL && t == solegramRoomT && r == solegramRoomR && b == solegramRoomB) {
                return false;
            }
            final int dl = l - solegramRoomL, dt = t - solegramRoomT, dr = r - solegramRoomR, db = b - solegramRoomB;
            final int x = wp.x + (hg == Gravity.RIGHT ? -dr : hg == Gravity.CENTER_HORIZONTAL ? (dr - dl) / 2 : -dl);
            final int y = wp.y + (vg == Gravity.BOTTOM ? -db : vg == Gravity.CENTER_VERTICAL ? (db - dt) / 2 : -dt);
            final ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) content.getLayoutParams();
            lp.leftMargin = l;
            lp.topMargin = t;
            lp.rightMargin = r;
            lp.bottomMargin = b;
            content.setLayoutParams(lp);
            // the menu draws its neck outside its own bounds
            for (View v = solegramLiquidLayout != null ? solegramLiquidLayout : content; v.getParent() instanceof ViewGroup; v = (View) v.getParent()) {
                ((ViewGroup) v.getParent()).setClipChildren(false);
            }
            solegramRoomL = l;
            solegramRoomT = t;
            solegramRoomR = r;
            solegramRoomB = b;
            super.update(x, y, -1, -1, true);
            return true;
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        }
    }

    private boolean solegramHasRoom() {
        return (solegramRoomL | solegramRoomT | solegramRoomR | solegramRoomB) != 0;
    }

    /** A position the caller asks for (the menu's place) as the window's place, the room round the menu counted in. */
    private int solegramWindowX(int x) {
        final int hg = solegramGravity() & Gravity.HORIZONTAL_GRAVITY_MASK;
        return x + (hg == Gravity.RIGHT ? -solegramRoomR : hg == Gravity.CENTER_HORIZONTAL ? (solegramRoomR - solegramRoomL) / 2 : -solegramRoomL);
    }

    private int solegramWindowY(int y) {
        final int vg = solegramGravity() & Gravity.VERTICAL_GRAVITY_MASK;
        return y + (vg == Gravity.BOTTOM ? -solegramRoomB : vg == Gravity.CENTER_VERTICAL ? (solegramRoomB - solegramRoomT) / 2 : -solegramRoomT);
    }

    private int solegramGravity() {
        final View content = getContentView();
        final ViewGroup.LayoutParams params = content != null ? content.getRootView().getLayoutParams() : null;
        return params instanceof WindowManager.LayoutParams ? ((WindowManager.LayoutParams) params).gravity : Gravity.TOP | Gravity.LEFT;
    }

    /** Taps in the room round the menu are outside it: they close the menu as taps outside the window would. */
    private final View.OnTouchListener solegramInterceptor = (v, e) -> {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN && solegramHasRoom()) {
            final View content = getContentView();
            if (content != null) {
                float x = content.getLeft(), y = content.getTop();
                for (View p = content.getParent() instanceof View ? (View) content.getParent() : null; p != null && p != v; p = p.getParent() instanceof View ? (View) p.getParent() : null) {
                    x += p.getLeft();
                    y += p.getTop();
                }
                if (e.getX() < x || e.getY() < y || e.getX() >= x + content.getWidth() || e.getY() >= y + content.getHeight()) {
                    dismiss();
                    return true;
                }
            }
        }
        return solegramUserInterceptor != null && solegramUserInterceptor.onTouch(v, e);
    };

    @Override
    public void setTouchInterceptor(View.OnTouchListener l) {
        solegramUserInterceptor = l;
        super.setTouchInterceptor(solegramInterceptor);
    }

    @Override
    public void update(int x, int y, int width, int height, boolean force) {
        if (solegramHasRoom() && isShowing()) {
            x = solegramWindowX(x);
            y = solegramWindowY(y);
        }
        super.update(x, y, width, height, force);
    }

    @Override
    public void update(int width, int height) {
        final View content = getContentView();
        final ViewGroup.LayoutParams params = content != null ? content.getRootView().getLayoutParams() : null;
        if (solegramHasRoom() && isShowing() && params instanceof WindowManager.LayoutParams) {
            // the window's place now is the menu's place moved by the room: keep it
            final WindowManager.LayoutParams p = (WindowManager.LayoutParams) params;
            super.update(p.x, p.y, width, height, false);
            return;
        }
        super.update(width, height);
    }

    /** The liquid menu flows back into its origin; the window really goes once it is in. */
    private void solegramLiquidClose() {
        final ActionBarPopupWindowLayout layout = solegramLiquidLayout;
        isClosingAnimated = true;
        if (pauseNotifications) {
            notificationsLocker.lock();
        }
        // flowing back it takes no taps (no invisible item runs) and no keys (Back goes to the screen)
        try {
            solegramUntouchable = isTouchable();
            setTouchable(false);
            setFocusable(false);
            update();
        } catch (Exception e) {
            FileLog.e(e);
        }
        layout.solegramLiquidClose(() -> solegramLiquidClosed(layout));
        solegramScheduleCloseTimeout(layout, -1, System.currentTimeMillis());
    }

    private void solegramRestoreTouchable() {
        if (solegramUntouchable) {
            solegramUntouchable = false;
            setTouchable(true); // the window is gone (or going): applies to its next show
        }
    }

    /** If the menu stops being drawn half-way, the window still goes: checked while frames keep coming, 4 s at most. */
    private void solegramScheduleCloseTimeout(ActionBarPopupWindowLayout layout, int lastFrames, long start) {
        if (solegramCloseTimeout != null) {
            AndroidUtilities.cancelRunOnUIThread(solegramCloseTimeout);
        }
        AndroidUtilities.runOnUIThread(solegramCloseTimeout = () -> {
            solegramCloseTimeout = null;
            if (solegramLiquidLayout != layout || !isClosingAnimated) {
                return;
            }
            final int frames = layout.solegramLiquidCloseFrames();
            if (frames != lastFrames && System.currentTimeMillis() - start < 4000) {
                solegramScheduleCloseTimeout(layout, frames, start); // still flowing, only slowly
            } else {
                solegramLiquidClosed(layout);
            }
        }, 700);
    }

    private void solegramLiquidClosed(ActionBarPopupWindowLayout layout) {
        if (solegramLiquidLayout != layout || !isClosingAnimated) {
            return;
        }
        if (solegramCloseTimeout != null) {
            AndroidUtilities.cancelRunOnUIThread(solegramCloseTimeout);
            solegramCloseTimeout = null;
        }
        isClosingAnimated = false;
        solegramLiquidLayout = null;
        setFocusable(false);
        try {
            super.dismiss();
        } catch (Exception ignore) {

        }
        layout.solegramLiquidEnd(this);
        solegramRestoreTouchable();
        solegramRoomL = solegramRoomT = solegramRoomR = solegramRoomB = 0;
        solegramScreenView = null;
        unregisterListener();
        if (pauseNotifications) {
            notificationsLocker.unlock();
        }
    }
    // endregion

    public static AnimatorSet startAnimation(ActionBarPopupWindowLayout content) {
        if (content.solegramLiquidActive()) {
            return new AnimatorSet(); // Solegram: it flows out as liquid
        }
        content.startAnimationPending = true;
        content.setTranslationY(0);
        content.setAlpha(1.0f);
        content.setPivotX(content.getMeasuredWidth());
        content.setPivotY(0);
        final int count = content.getItemsCount();
        content.positions.clear();
        int visibleCount = 0;
        for (int a = 0; a < count; a++) {
            View child = content.getItemAt(a);
            if (child instanceof GapView) {
                continue;
            }
            child.setAlpha(0.0f);
            if (child.getVisibility() != View.VISIBLE) {
                continue;
            }
            content.positions.put(child, visibleCount);
            visibleCount++;
        }
        if (content.shownFromBottom) {
            content.lastStartedChild = count - 1;
        } else {
            content.lastStartedChild = 0;
        }
        float finalScaleY = 1f;
        if (content.getSwipeBack() != null) {
            content.getSwipeBack().invalidateTransforms();
            finalScaleY = content.backScaleY;
        }
        AnimatorSet windowAnimatorSet = new AnimatorSet();
        ValueAnimator childtranslations = ValueAnimator.ofFloat(0, 1);
        childtranslations.addUpdateListener(anm -> {
            final int count2 = content.getItemsCount();
            final float t = (float) anm.getAnimatedValue();
            for (int a = 0; a < count2; a++) {
                View child = content.getItemAt(a);
                if (child instanceof GapView) {
                    continue;
                }
                float at = AndroidUtilities.cascade(t, content.shownFromBottom ? count2 - 1 - a : a, count2, 4);
                child.setTranslationY((1f - at) * dp(-6));
                child.setAlpha(at * (child.isEnabled() ? 1f : 0.5f));
            }
        });
        content.updateAnimation = false;
        content.clipChildren = true;
        windowAnimatorSet.playTogether(
                ObjectAnimator.ofFloat(content, "backScaleY", 0.0f, finalScaleY),
                ObjectAnimator.ofInt(content, "backAlpha", 0, 255),
                childtranslations
        );
        windowAnimatorSet.setDuration(150 + 16 * visibleCount);
        windowAnimatorSet.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                content.startAnimationPending = false;
                int count = content.getItemsCount();
                for (int a = 0; a < count; a++) {
                    View child = content.getItemAt(a);
                    if (child instanceof GapView) {
                        continue;
                    }
                    child.setTranslationY(0);
                    child.setAlpha(child.isEnabled() ? 1f : 0.5f);
                }
            }
        });
        windowAnimatorSet.start();
        return windowAnimatorSet;
    }

    public void startAnimation() {
        if (solegramLiquidLayout != null && solegramLiquidLayout.solegramLiquidActive()) {
            return; // Solegram: it flows out as liquid
        }
        if (!solegramSprung && isShowing() && windowAnimatorSet == null && solegramSpringAllowed()) {
            // Solegram: a menu with no window animation of its own (a header's menu) grows out of its button too,
            // together with Telegram's reveal of its items below
            solegramSpringIn(null);
        }
        if (animationEnabled) {
            if (windowAnimatorSet != null) {
                return;
            }

            ViewGroup viewGroup = (ViewGroup) getContentView();
            ActionBarPopupWindowLayout content = null;
            if (viewGroup instanceof ActionBarPopupWindowLayout) {
                content = (ActionBarPopupWindowLayout) viewGroup;
                content.startAnimationPending = true;
            } else {
                for (int i = 0; i < viewGroup.getChildCount(); i++) {
                    if (viewGroup.getChildAt(i) instanceof ActionBarPopupWindowLayout) {
                        content = (ActionBarPopupWindowLayout) viewGroup.getChildAt(i);
                        content.startAnimationPending = true;
                    }
                }
            }
            content.setTranslationY(0);
            content.setAlpha(1.0f);
            content.setPivotX(content.getMeasuredWidth());
            content.setPivotY(0);
            int count = content.getItemsCount();
            content.positions.clear();
            int visibleCount = 0;
            for (int a = 0; a < count; a++) {
                View child = content.getItemAt(a);
                child.setAlpha(0.0f);
                if (child.getVisibility() != View.VISIBLE) {
                    continue;
                }
                content.positions.put(child, visibleCount);
                visibleCount++;
            }
            if (content.shownFromBottom) {
                content.lastStartedChild = count - 1;
            } else {
                content.lastStartedChild = 0;
            }
            float finalScaleY = 1f;
            if (content.getSwipeBack() != null) {
                content.getSwipeBack().invalidateTransforms();
                finalScaleY = content.backScaleY;
            }
            windowAnimatorSet = new AnimatorSet();
            windowAnimatorSet.playTogether(
                    ObjectAnimator.ofFloat(content, "backScaleY", 0.0f, finalScaleY),
                    ObjectAnimator.ofInt(content, "backAlpha", 0, 255));
            windowAnimatorSet.setDuration(150 + 16 * visibleCount);
            windowAnimatorSet.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    windowAnimatorSet = null;
                    ViewGroup viewGroup = (ViewGroup) getContentView();
                    ActionBarPopupWindowLayout content = null;
                    if (viewGroup instanceof ActionBarPopupWindowLayout) {
                        content = (ActionBarPopupWindowLayout) viewGroup;
                        content.startAnimationPending = false;
                    } else {
                        for (int i = 0; i < viewGroup.getChildCount(); i++) {
                            if (viewGroup.getChildAt(i) instanceof ActionBarPopupWindowLayout) {
                                content = (ActionBarPopupWindowLayout) viewGroup.getChildAt(i);
                                content.startAnimationPending = false;
                            }
                        }
                    }
                    int count = content.getItemsCount();
                    for (int a = 0; a < count; a++) {
                        View child = content.getItemAt(a);
                        if (child instanceof GapView) {
                            continue;
                        }
                        child.setAlpha(child.isEnabled() ? 1f : 0.5f);
                    }
                }
            });
            windowAnimatorSet.start();
        }
    }

    @Override
    public void update(View anchor, int xoff, int yoff, int width, int height) {
        super.update(anchor, xoff, yoff, width, height);
        registerListener(anchor);
    }

    @Override
    public void update(View anchor, int width, int height) {
        super.update(anchor, width, height);
        registerListener(anchor);
    }

    @Override
    public void showAtLocation(View parent, int gravity, int x, int y) {
        if (isShowing()) {
            super.showAtLocation(parent, gravity, x, y); // does nothing: shown already, its liquid stays as it is
            return;
        }
        // Solegram: a liquid menu has no window animation, the liquid is its animation
        final ActionBarPopupWindowLayout liquid = solegramLiquidTarget();
        if (liquid != null) {
            setAnimationStyle(0);
        }
        final boolean spring = liquid == null && solegramPrepareSpring();
        solegramRoomL = solegramRoomT = solegramRoomR = solegramRoomB = 0;
        solegramCollectPanels();
        super.showAtLocation(parent, gravity, x, y);
        unregisterListener();
        if (liquid == null || !solegramStartLiquid(liquid, parent, true)) {
            solegramApplyGlass();
        }
        if (spring) {
            solegramSpringIn(parent);
        }
    }

    @Override
    public void dismiss() {
        dismiss(true);
    }

    public void setPauseNotifications(boolean value) {
        pauseNotifications = value;
    }

    public void dismiss(boolean animated) {
        setFocusable(false);
        dismissDim();
        if (solegramLiquidLayout != null) {
            // Solegram: a liquid menu flows back into what opened it, then the window goes
            if (animated && isClosingAnimated) {
                return;
            }
            if (animated && animationEnabled && outEmptyTime <= 0 && solegramLiquidLayout.solegramLiquidActive()) {
                solegramLiquidClose();
                return;
            }
            if (solegramCloseTimeout != null) {
                AndroidUtilities.cancelRunOnUIThread(solegramCloseTimeout);
                solegramCloseTimeout = null;
            }
            final ActionBarPopupWindowLayout layout = solegramLiquidLayout;
            solegramLiquidLayout = null;
            if (isClosingAnimated && pauseNotifications) {
                notificationsLocker.unlock();
            }
            isClosingAnimated = false;
            if (!animated) {
                try {
                    super.dismiss();
                } catch (Exception ignore) {

                }
                layout.solegramLiquidEnd(this);
                solegramRestoreTouchable();
                solegramRoomL = solegramRoomT = solegramRoomR = solegramRoomB = 0;
                unregisterListener();
                return;
            }
            layout.solegramLiquidEnd(this);
            solegramRestoreTouchable();
        }
        if (windowAnimatorSet != null) {
            if (animated && isClosingAnimated) {
                return;
            }
            windowAnimatorSet.cancel();
            windowAnimatorSet = null;
        }
        isClosingAnimated = false;
        if (animationEnabled && animated) {
            isClosingAnimated = true;
            ViewGroup viewGroup = (ViewGroup) getContentView();
            ActionBarPopupWindowLayout content = null;
            for (int i = 0; i < viewGroup.getChildCount(); i++) {
                if (viewGroup.getChildAt(i) instanceof ActionBarPopupWindowLayout) {
                    content = (ActionBarPopupWindowLayout) viewGroup.getChildAt(i);
                }
            }
            if (content != null) {
                if (content.itemAnimators != null && !content.itemAnimators.isEmpty()) {
                    for (int a = 0, N = content.itemAnimators.size(); a < N; a++) {
                        AnimatorSet animatorSet = content.itemAnimators.get(a);
                        animatorSet.removeAllListeners();
                        animatorSet.cancel();
                    }
                    content.itemAnimators.clear();
                }
            }
            windowAnimatorSet = new AnimatorSet();
            if (outEmptyTime > 0) {
                windowAnimatorSet.playTogether(ValueAnimator.ofFloat(0, 1f));
                windowAnimatorSet.setDuration(outEmptyTime);
            } else if (solegramSprung && com.solegram.messenger.ui.SolegramMotion.enabled()) {
                // Solegram: the menu shrinks back into what opened it (from wherever its opening got to)
                if (solegramOpenAnimator != null) {
                    final ValueAnimator running = solegramOpenAnimator;
                    solegramOpenAnimator = null;
                    running.cancel();
                }
                solegramApplyPivot(viewGroup);
                windowAnimatorSet.playTogether(
                        ObjectAnimator.ofFloat(viewGroup, View.SCALE_Y, SOLEGRAM_SCALE_TO),
                        ObjectAnimator.ofFloat(viewGroup, View.SCALE_X, SOLEGRAM_SCALE_TO),
                        ObjectAnimator.ofFloat(viewGroup, View.ALPHA, 0.0f));
                windowAnimatorSet.setDuration(Math.max(180, dismissAnimationDuration));
                windowAnimatorSet.setInterpolator(com.solegram.messenger.ui.SolegramMotion.EASE_IN);
            } else if (scaleOut) {
                windowAnimatorSet.playTogether(
                        ObjectAnimator.ofFloat(viewGroup, View.SCALE_Y, 0.8f),
                        ObjectAnimator.ofFloat(viewGroup, View.SCALE_X, 0.8f),
                        ObjectAnimator.ofFloat(viewGroup, View.ALPHA, 0.0f));
                windowAnimatorSet.setDuration(dismissAnimationDuration);
            } else {
                windowAnimatorSet.playTogether(
                        ObjectAnimator.ofFloat(viewGroup, View.TRANSLATION_Y, dp((content != null && content.shownFromBottom) ? 5 : -5)),
                        ObjectAnimator.ofFloat(viewGroup, View.ALPHA, 0.0f));
                windowAnimatorSet.setDuration(dismissAnimationDuration);
            }

            windowAnimatorSet.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    windowAnimatorSet = null;
                    isClosingAnimated = false;
                    setFocusable(false);
                    try {
                        ActionBarPopupWindow.super.dismiss();
                    } catch (Exception ignore) {

                    }
                    unregisterListener();
                    if (pauseNotifications) {
                        notificationsLocker.unlock();
                    }
                }
            });
            if (pauseNotifications) {
                notificationsLocker.lock();
            }
            windowAnimatorSet.start();
        } else {
            try {
                super.dismiss();
            } catch (Exception ignore) {

            }
            unregisterListener();
        }
    }

    public void setEmptyOutAnimation(long time) {
        outEmptyTime = time;
    }

    public interface onSizeChangedListener {
        void onSizeChanged();
    }

    public static class GapView extends FrameLayout {

        Drawable shadowDrawable;
        // Solegram: groups in a menu are parted by a thin line over the glass, not an opaque grey band
        private final android.graphics.Paint solegramLine = new android.graphics.Paint();

        public GapView(Context context, Theme.ResourcesProvider resourcesProvider) {
            this(context, resourcesProvider, Theme.key_actionBarDefaultSubmenuSeparator);
        }

        public GapView(Context context, int color, int shadowColor) {
            this(context, color, shadowColor, Theme.getColor(Theme.key_actionBarDefaultSubmenuItem));
        }

        private GapView(Context context, int color, int shadowColor, int textColor) {
            super(context);
            setWillNotDraw(false);
            solegramLine.setColor(androidx.core.graphics.ColorUtils.setAlphaComponent(textColor, 0x24));
        }

        public GapView(Context context, Theme.ResourcesProvider resourcesProvider, int colorKey) {
            this(context, Theme.getColor(colorKey, resourcesProvider), Theme.getColor(Theme.key_windowBackgroundGrayShadow, resourcesProvider),
                Theme.getColor(Theme.key_actionBarDefaultSubmenuItem, resourcesProvider));
        }

        public void setColor(int color) {
            // Solegram: no band; a menu on a dark custom background (e.g. stories) gets a light line instead
            if (androidx.core.graphics.ColorUtils.calculateLuminance(color | 0xFF000000) < 0.25) {
                solegramLine.setColor(0x26FFFFFF);
            }
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            final float h = Math.max(1, AndroidUtilities.dpf2(0.66f));
            final float y = (getHeight() - h) / 2f;
            canvas.drawRect(0, y, getWidth(), y + h, solegramLine);
        }
    }
}