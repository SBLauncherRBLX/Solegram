/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.ui.ActionBar;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.dpf2;
import static org.telegram.messenger.AndroidUtilities.find;
import static org.telegram.messenger.AndroidUtilities.lerp;
import static org.telegram.messenger.LocaleController.getString;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.VectorDrawable;
import android.text.SpannableString;
import android.text.TextPaint;
import android.text.TextUtils;
import android.transition.ChangeBounds;
import android.transition.Fade;
import android.transition.TransitionManager;
import android.transition.TransitionSet;
import android.transition.TransitionValues;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewPropertyAnimator;
import android.view.animation.Interpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.core.graphics.ColorUtils;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.Adapters.FiltersView;
import org.telegram.ui.Components.AnimatedEmojiDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.ChatAvatarContainer;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.EllipsizeSpanAnimator;
import org.telegram.ui.Components.FireworksEffect;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.SectionsScrollView;
import org.telegram.ui.Components.SizeNotifierFrameLayout;
import org.telegram.ui.Components.SnowflakesEffect;
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundColorProvider;
import org.telegram.ui.MainTabsLayout;

import java.util.ArrayList;

import me.vkryl.android.animator.BoolAnimator;
import me.vkryl.android.animator.FactorAnimator;
import me.vkryl.android.animator.ReplaceAnimator;

public class ActionBar extends FrameLayout implements FactorAnimator.Target, Theme.Colorable {

    public static class ActionBarMenuOnItemClick {
        public void onItemClick(int id) {

        }

        public boolean canOpenMenu() {
            return true;
        }
    }

    private BlurredBackgroundDrawable glassDrawable;
    private BlurredBackgroundDrawable glassDrawableBack;
    private BlurredBackgroundDrawable glassDrawableMenu;
    private INavigationLayout.BackButtonState backButtonState = INavigationLayout.BackButtonState.BACK;
    public ImageView backButtonImageView;
    private BackupImageView avatarSearchImageView;
    private Drawable backButtonDrawable;
    private final SimpleTextView[] titleTextView = new SimpleTextView[2];
    private SimpleTextView subtitleTextView;
    private SimpleTextView additionalSubtitleTextView;
    private View actionModeTop;
    private int actionModeColor;
    private int actionBarColor;
    private boolean isMenuOffsetSuppressed;
    public ActionBarMenu menu;
    private ActionBarMenu actionMode;
    private String actionModeTag;
    private boolean ignoreLayoutRequest;
    protected boolean occupyStatusBar = true;
    protected boolean actionModeVisible;
    private boolean addToContainer = true;
    private boolean clipContent;
    private boolean interceptTouches = true;
    private boolean forceSkipTouches;
    private int extraHeight;
    private AnimatorSet actionModeAnimation;
    private View actionModeExtraView;
    private View actionModeTranslationView;
    private View actionModeShowingView;
    private View[] actionModeHidingViews;

    private boolean supportsHolidayImage;
    private SnowflakesEffect snowflakesEffect;
    private FireworksEffect fireworksEffect;
    private Paint.FontMetricsInt fontMetricsInt;
    private boolean manualStart;
    private Rect rect;

    private int titleRightMargin;

    private boolean allowOverlayTitle;
    private CharSequence lastTitle;
    private Drawable lastRightDrawable;
    private OnClickListener rightDrawableOnClickListener;
    private CharSequence lastOverlayTitle;
    private Object[] overlayTitleToSet = new Object[3];
    private Runnable lastRunnable;
    private boolean titleOverlayShown;
    private Runnable titleActionRunnable;
    private boolean castShadows = true;
    private int shadowAlpha = 0xFF;

    public boolean menuOccupyBack;
    protected boolean isSearchFieldVisible;
    public float searchFieldVisibleAlpha;
    public int itemsBackgroundColor;
    protected int itemsActionModeBackgroundColor;
    protected int itemsColor;
    protected int itemsActionModeColor;
    private boolean isBackOverlayVisible;
    protected BaseFragment parentFragment;
    public ActionBarMenuOnItemClick actionBarMenuOnItemClick;
    private int titleColorToSet = 0;
    private boolean overlayTitleAnimation;
    private boolean titleAnimationRunning;
    private boolean fromBottom;
    private boolean centerScale;
    private CharSequence subtitle;
    private boolean drawBackButton;
    private boolean attached;
    private boolean resumed;
    private boolean attachState;
    private FrameLayout titlesContainer;
    private boolean useContainerForTitles;

    private View.OnTouchListener interceptTouchEventListener;
    private final Theme.ResourcesProvider resourcesProvider;

    SizeNotifierFrameLayout contentView;
    boolean blurredBackground;
    public Paint blurScrimPaint = new Paint();
    Rect rectTmp = new Rect();

    EllipsizeSpanAnimator ellipsizeSpanAnimator = new EllipsizeSpanAnimator(this);

    public ActionBar(Context context) {
        this(context, null);
    }

    public ActionBar(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        setOnClickListener(v -> {
            if (isSearchFieldVisible()) {
                return;
            }
            if (titleActionRunnable != null) {
                titleActionRunnable.run();
            }
        });
    }

    private boolean glassMode;
    private boolean glassOnlyBack;
    private boolean glassModeIsForum;

    private ChatAvatarContainer chatAvatarContainer;

    public void setGlassOnlyBack() {
        glassOnlyBack = true;
    }

    public void setChatAvatarContainer(ChatAvatarContainer chatAvatarContainer) {
        this.chatAvatarContainer = chatAvatarContainer;
    }

    public void setupGlass(BlurredBackgroundDrawableViewFactory factory, BlurredBackgroundColorProvider colorProvider) {
        setupGlass(factory, colorProvider, false);
    }

    public void setupGlass(BlurredBackgroundDrawableViewFactory factory,
                           BlurredBackgroundColorProvider colorProvider,
                           boolean isForum) {
        setBackground(null);
        setClipChildren(false);
        glassMode = true;
        glassModeIsForum = isForum;
        solegramButtonsGlass = false; // the full glass header has its own bubbles
        solegramTonal = false;
        if (menu != null) {
            menu.solegramKeepTextItems = false;
        }

        glassDrawable = factory.create(this)
            .setColorProvider(colorProvider)
            .setPadding(dp(6));
        if (isForum) {
            glassDrawable.setRadius(dp(18.33f), dp(23), dp(23), dp(18.33f));
        } else {
            glassDrawable.setRadius(dp(23));
        }


        glassDrawableBack = factory.create(this)
            .setColorProvider(colorProvider)
            .setRadius(dp(23))
            .setPadding(dp(6));

        glassDrawableMenu = factory.create(this)
            .setColorProvider(colorProvider)
            .setRadius(dp(23))
            .setPadding(dp(6));

        if (menu != null) {
            menu.setTranslationX(-dp(10));
            menu.setGlassMode(true);
        }
        if (actionMode != null) {
            actionMode.setTranslationX(-dp(10));
            actionMode.setGlassMode(true);
        }
        if (backButtonImageView != null) {
            backButtonImageView.setTranslationX(dp(2));
        }
    }

    /** Solegram: glass bubbles for the buttons only (back button and menu); the title stays plain text. */
    private boolean solegramButtonsGlass;
    private float solegramMenuGlassAlpha = 1f;

    private final float[] solegramExtent = new float[2];
    private boolean solegramTonal;
    /** Solegram floating header of an ordinary screen: the bar stays clear, the screen goes on under it. */
    private boolean solegramFloating;
    /** The bubbles' glass has no root view to follow: their place in the picture is set from this bar's position. */
    private boolean solegramManualGlassOffset;

    public void solegramSetFloating(boolean manualGlassOffset) {
        solegramFloating = true;
        solegramManualGlassOffset = manualGlassOffset;
        super.setBackgroundColor(Color.TRANSPARENT);
        setCastShadows(false);
    }

    public boolean solegramIsFloating() {
        return solegramFloating;
    }

    /** The header's own colour (kept while the bar is clear): the floating header's search panel uses it. */
    public int solegramHeaderColor() {
        return actionBarColor;
    }

    // Solegram floating header: the page behind the clear bar; the icons and the title stay readable on it
    private int solegramPageColor;
    private int solegramRawItemsColor, solegramRawTitleColor, solegramRawSubtitleColor;

    public void solegramSetPageColor(int pageColor, int readableColor) {
        if (solegramPageColor == pageColor && solegramReadableColor == readableColor) {
            return;
        }
        solegramPageColor = pageColor;
        solegramReadableColor = readableColor;
        // the colours the screen asked for, checked again against the new page
        if (solegramRawItemsColor != 0) {
            setItemsColor(solegramRawItemsColor, false);
        }
        if (solegramRawTitleColor != 0) {
            setTitleColor(solegramRawTitleColor);
        }
        if (solegramRawSubtitleColor != 0) {
            setSubtitleColor(solegramRawSubtitleColor);
        }
    }

    private int solegramReadableColor;

    /** A colour that would hardly show on the page behind the clear bar gives way to the page's text colour. */
    private int solegramReadable(int color) {
        if (!solegramFloating || solegramPageColor == 0 || solegramReadableColor == 0 || color == 0) {
            return color;
        }
        final double page = ColorUtils.calculateLuminance(solegramPageColor | 0xFF000000);
        if (Math.abs(ColorUtils.calculateLuminance(color | 0xFF000000) - page) >= 0.3) {
            return color;
        }
        return ColorUtils.setAlphaComponent(solegramReadableColor, Color.alpha(color));
    }

    public boolean solegramIsGlassMode() {
        return glassMode;
    }

    public int solegramExtraHeight() {
        return extraHeight;
    }
    // the menu's bubble changing between a circle and a capsule
    private boolean solegramCapsule;
    private org.telegram.ui.Components.AnimatedFloat solegramBubbleMorph;
    private float solegramMorphFromLeft, solegramMorphFromRight;
    private BlurredBackgroundDrawableViewFactory solegramMenuFactory;
    private BlurredBackgroundColorProvider solegramMenuColors;

    private BlurredBackgroundDrawable solegramCreateMenuBubble() {
        return solegramMenuFactory.create(menu)
            .setColorProvider(solegramMenuColors)
            .setRadius(dp(23))
            .setPadding(dp(6));
    }

    /** Solegram: the menu's bubble, drawn by the menu under its buttons (in the menu's coordinates). */
    void solegramDrawMenuBubble(Canvas canvas) {
        if (!solegramButtonsGlass || glassDrawableMenu == null || menu == null || glassOnlyBack || doNotDrawGlassMenu) {
            return;
        }
        // when the selection bar fades the menu itself, the bubble (drawn in the menu) already fades with it
        float alpha = (menu.getAlpha() < 1f ? 1f : 1f - solegramActionModeFactor()) * (1f - searchFieldVisibleAlpha) * solegramMenuGlassAlpha
            * (hasForcedMenuWidth ? 1f : animatorHasMenuItems.getFloatValue());
        final int menuWidth = solegramMenuWidth();
        if (alpha <= 0f || menuWidth <= 0) {
            return;
        }
        float left = Float.POSITIVE_INFINITY, right = Float.NEGATIVE_INFINITY;
        boolean textLeft = false, textRight = false;
        float buttonsAlpha = 0f;
        for (int i = 0, n = menu.getChildCount(); i < n; i++) {
            final View child = menu.getChildAt(i);
            if (solegramShown(child)) {
                final boolean text = child instanceof ActionBarMenuItem && ((ActionBarMenuItem) child).getTextView() != null;
                if (child.getRight() > right) {
                    right = child.getRight();
                    textRight = text;
                }
                if (child.getLeft() < left) {
                    left = child.getLeft();
                    textLeft = text;
                }
                buttonsAlpha = Math.max(buttonsAlpha, child.getAlpha());
            }
        }
        if (right == Float.NEGATIVE_INFINITY) {
            return;
        }
        alpha *= Math.min(1f, buttonsAlpha); // the bubble fades with its buttons
        final int p = dp(6);
        final int s = dp(46);
        // the row of buttons in the bar, in the menu's coordinates
        final int t = (occupyStatusBar ? AndroidUtilities.statusBarHeight : 0) + (getCurrentActionBarHeight() - s) / 2 - p - (int) menu.getY();
        final boolean capsule = textLeft || textRight;
        int boundsLeft, boundsRight;
        if (capsule) {
            // a word ("Save", "Done") gets a capsule round it with even room on both sides, not an icon's circle
            final int textPad = dp(10);
            int l = (int) left + (textLeft ? -textPad : dp(SOLEGRAM_BUBBLE_INSET));
            int r = (int) right + (textRight ? textPad : -dp(SOLEGRAM_BUBBLE_INSET));
            if (r - l < s) {
                final int grow = (s - (r - l) + 1) / 2;
                l -= grow;
                r += grow;
            }
            boundsLeft = l - p;
            boundsRight = r + p;
        } else {
            boundsRight = (int) right - dp(SOLEGRAM_BUBBLE_INSET) + p;
            boundsLeft = boundsRight - Math.max(s, menuWidth) - p * 2;
        }
        // a circle turning into a capsule (a word appears among the buttons) and back changes shape smoothly
        if (capsule != solegramCapsule) {
            solegramCapsule = capsule;
            final android.graphics.Rect was = glassDrawableMenu.getBounds();
            if (!was.isEmpty() && glassDrawableMenu.getAlpha() > 0) {
                solegramMorphFromLeft = was.left;
                solegramMorphFromRight = was.right;
                if (solegramBubbleMorph == null) {
                    solegramBubbleMorph = new org.telegram.ui.Components.AnimatedFloat(menu, 0, 320, CubicBezierInterpolator.EASE_OUT_QUINT);
                }
                solegramBubbleMorph.set(0f, true);
            }
        }
        if (solegramBubbleMorph != null) {
            final float morph = solegramBubbleMorph.set(1f);
            if (morph < 1f) {
                boundsLeft = (int) AndroidUtilities.lerp(solegramMorphFromLeft, boundsLeft, morph);
                boundsRight = (int) AndroidUtilities.lerp(solegramMorphFromRight, boundsRight, morph);
            }
        }
        glassDrawableMenu.setBounds(boundsLeft, t, boundsRight, t + s + p * 2);
        if (solegramManualGlassOffset) {
            glassDrawableMenu.setSourceOffset(getX() + menu.getX(), getY() + menu.getY());
        }
        final int alpha255 = (int) (255 * alpha);
        if (glassDrawableMenu.getAlpha() != alpha255) {
            glassDrawableMenu.setAlpha(alpha255);
        }
        // fluid touch: a held button takes its bubble with it
        final int fluid = canvas.save();
        for (int i = 0, n = menu.getChildCount(); i < n; i++) {
            if (com.solegram.messenger.ui.SolegramFluidTouch.applyTo(canvas, menu.getChildAt(i))) {
                break;
            }
        }
        glassDrawableMenu.draw(canvas);
        canvas.restoreToCount(fluid);
    }

    /**
     * A button that really shows: visible, not faded or scaled away (done buttons hide that way), and not the
     * search field that stretches over the bar while the search is open.
     */
    private static boolean solegramShown(View child) {
        if (child.getVisibility() != VISIBLE || child.getWidth() <= 0 || child.getAlpha() <= 0.02f || child.getScaleX() <= 0.02f) {
            return false;
        }
        final ViewGroup.LayoutParams params = child.getLayoutParams();
        return !(params instanceof LinearLayout.LayoutParams && ((LinearLayout.LayoutParams) params).weight > 0);
    }

    boolean solegramMenuBubbleActive() {
        return solegramButtonsGlass;
    }

    private void solegramInvalidateBubbles() {
        invalidate();
        if (solegramButtonsGlass && menu != null) {
            menu.invalidate();
        }
    }

    /**
     * Solegram: bubbles for the back button and the menu of an ordinary header - a soft veil of the icons'
     * own colour with a light rim, so they read on any header colour.
     */
    public void solegramSetupTonalButtons() {
        final org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceColor source = new org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceColor();
        source.setColor(0);
        final BlurredBackgroundDrawableViewFactory factory = new BlurredBackgroundDrawableViewFactory(source);
        final BlurredBackgroundColorProvider colors = new org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundProviderBuilder(resourcesProvider)
            .setBackgroundColor((r, isDark) -> Theme.multAlpha(solegramIconColor(), 0.09f))
            .setStrokeColorTop((r, isDark) -> Theme.multAlpha(solegramIconColor(), 0.16f))
            .setStrokeColorBottom((r, isDark) -> Theme.multAlpha(solegramIconColor(), 0.06f))
            .setShadowColor(0, 0)
            .setStrokeWidth(dp(1) * 0.8f, dp(1) * 0.8f)
            .build();
        solegramSetupButtonsGlass(factory, colors);
        solegramTonal = true;
    }

    private int solegramIconColor() {
        return itemsColor != 0 ? itemsColor : Theme.getColor(Theme.key_actionBarDefaultIcon, resourcesProvider);
    }

    /**
     * Solegram: left and right edges of the visible buttons in this bar, from where they really are -
     * any view in the menu counts, also ones that plugins add.
     */
    private boolean solegramMenuExtent(float[] out) {
        if (menu == null || menu.getVisibility() == GONE) {
            return false;
        }
        float left = Float.POSITIVE_INFINITY, right = Float.NEGATIVE_INFINITY;
        for (int i = 0, n = menu.getChildCount(); i < n; i++) {
            final View child = menu.getChildAt(i);
            if (!solegramShown(child)) {
                continue;
            }
            left = Math.min(left, child.getLeft());
            right = Math.max(right, child.getRight());
        }
        if (right <= left) {
            return false;
        }
        out[0] = menu.getX() + left;
        out[1] = menu.getX() + right;
        return true;
    }

    /** Solegram: left edge of the buttons' glass bubble as drawn, or -1 when there is none. */
    public float solegramMenuBubbleLeft() {
        if (!solegramButtonsGlass || !solegramMenuExtent(solegramExtent)) {
            return -1;
        }
        return solegramExtent[1] - dp(SOLEGRAM_BUBBLE_INSET) - solegramMenuWidth();
    }

    // the bubble ends this far inside the last button, so it is centred on 48dp icon buttons
    private static final int SOLEGRAM_BUBBLE_INSET = 1;

    /** Solegram: where the left edge of the buttons' bubble ends up once its width animation is over, or -1. */
    public float solegramMenuBubbleTargetLeft() {
        if (!solegramButtonsGlass || !solegramMenuExtent(solegramExtent)) {
            return -1;
        }
        return solegramExtent[1] - dp(SOLEGRAM_BUBBLE_INSET) - (hasForcedMenuWidth ? forcedMenuWidth : animatorMenuItemsWidth.getToFactor());
    }

    private int solegramMenuWidth() {
        return hasForcedMenuWidth ? forcedMenuWidth : (int) animatorMenuItemsWidth.getFactor();
    }

    /** Solegram: how much of the menu's bubble shows, following the screen's own hiding of its buttons. */
    public void solegramSetMenuGlassAlpha(float alpha) {
        if (solegramMenuGlassAlpha != alpha) {
            solegramMenuGlassAlpha = alpha;
            solegramInvalidateBubbles();
        }
    }

    public void solegramSetupButtonsGlass(BlurredBackgroundDrawableViewFactory factory, BlurredBackgroundColorProvider colorProvider) {
        solegramButtonsGlass = true;
        solegramTonal = false;
        solegramMenuFactory = factory;
        solegramMenuColors = colorProvider;
        setClipChildren(false);
        glassDrawableBack = factory.create(this)
            .setColorProvider(colorProvider)
            .setRadius(dp(23))
            .setPadding(dp(6));
        // the menu draws its own bubble (see solegramDrawMenuBubble): it fades and moves with the buttons
        glassDrawableMenu = menu != null ? solegramCreateMenuBubble() : null;
        if (menu != null) {
            // the items sit closer together inside their bubble, as in a chat
            menu.setTranslationX(-dp(10));
            menu.setGlassMode(true);
            menu.solegramKeepTextItems = true;
            menu.requestLayout();
        }
        if (backButtonImageView != null) {
            backButtonImageView.setTranslationX(dp(2));
        }
        invalidate();
    }

    public INavigationLayout.BackButtonState getBackButtonState() {
        if (backButtonDrawable instanceof INavigationLayout.IBackButtonDrawable) {
            return ((INavigationLayout.IBackButtonDrawable) backButtonDrawable).getBackButtonState();
        }
        return backButtonState;
    }

    private void createBackButtonImage() {
        if (backButtonImageView != null) {
            return;
        }
        backButtonImageView = new ImageView(getContext());
        backButtonImageView.setScaleType(ImageView.ScaleType.CENTER);
        backButtonImageView.setBackgroundDrawable(Theme.createSelectorDrawable(itemsBackgroundColor));
        backButtonImageView.setPadding(dp(1), 0, 0, 0);
        addView(backButtonImageView, LayoutHelper.createFrame(54, 54, Gravity.LEFT | Gravity.TOP));
        if (solegramButtonsGlass) {
            backButtonImageView.setTranslationX(dp(2)); // centred in its bubble
        }

        backButtonImageView.setOnClickListener(v -> {
            if (!actionModeVisible && isSearchFieldVisible) {
                closeSearchField();
                return;
            }
            if (actionBarMenuOnItemClick != null) {
                actionBarMenuOnItemClick.onItemClick(-1);
            }
        });
        backButtonImageView.setContentDescription(LocaleController.getString(R.string.AccDescrGoBack));
    }

    public Drawable getBackButtonDrawable() {
        return backButtonDrawable;
    }

    public void setBackButtonDrawable(Drawable drawable) {
        if (backButtonImageView == null) {
            createBackButtonImage();
        }
        backButtonImageView.setVisibility(drawable == null ? GONE : VISIBLE);
        backButtonImageView.setImageDrawable(backButtonDrawable = drawable);
        if (drawable instanceof BackDrawable) {
            BackDrawable backDrawable = (BackDrawable) drawable;
            backDrawable.setRotation(isActionModeShowed() ? 1 : 0, false);
            backDrawable.setRotatedColor(itemsActionModeColor);
            backDrawable.setColor(itemsColor);
        } else if (drawable instanceof MenuDrawable) {
            MenuDrawable menuDrawable = (MenuDrawable) drawable;
            menuDrawable.setBackColor(actionBarColor);
            menuDrawable.setIconColor(itemsColor);
        } else if (drawable instanceof BitmapDrawable || drawable instanceof VectorDrawable) {
            backButtonImageView.setColorFilter(new PorterDuffColorFilter(itemsColor, PorterDuff.Mode.SRC_IN));
        }
        if (mAlwaysApplyColorFilterToBackButton) {
            backButtonImageView.setColorFilter(new PorterDuffColorFilter(itemsColor, PorterDuff.Mode.SRC_IN));
        }

        checkBackButtonLayerType();
    }

    private void checkBackButtonLayerType() {
        if (backButtonImageView == null) {
            return;
        }

        // BackDrawable is expensive for render thread because it uses PathStencilCoverOp

        final Drawable drawable = backButtonImageView.getDrawable();
        final int layerToSet = (drawable instanceof BackDrawable || drawable instanceof MenuDrawable) ?
            View.LAYER_TYPE_HARDWARE :
            View.LAYER_TYPE_NONE;

        if (backButtonImageView.getLayerType() != layerToSet) {
            backButtonImageView.setLayerType(layerToSet, null);
            backButtonImageView.invalidate();
        }
    }

    public void setBackButtonContentDescription(CharSequence description) {
        if (backButtonImageView != null) {
            backButtonImageView.setContentDescription(description);
        }
    }

    public void setSupportsHolidayImage(boolean value) {
        supportsHolidayImage = value;
        if (supportsHolidayImage) {
            fontMetricsInt = new Paint.FontMetricsInt();
            rect = new Rect();
        }
        invalidate();
    }

    public BackupImageView getSearchAvatarImageView() {
        return avatarSearchImageView;
    }

    public void setSearchAvatarImageView(BackupImageView backupImageView) {
        if (avatarSearchImageView == backupImageView) {
            return;
        }
        if (avatarSearchImageView != null) {
            removeView(avatarSearchImageView);
        }
        avatarSearchImageView = backupImageView;
        if (avatarSearchImageView != null) {
            addView(avatarSearchImageView);
        }
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (supportsHolidayImage && !titleOverlayShown && !LocaleController.isRTL && ev.getAction() == MotionEvent.ACTION_DOWN) {
            Drawable drawable = Theme.getCurrentHolidayDrawable();
            if (drawable != null && drawable.getBounds().contains((int) ev.getX(), (int) ev.getY())) {
                manualStart = true;
                if (snowflakesEffect == null) {
                    fireworksEffect = null;
                    snowflakesEffect = new SnowflakesEffect(0);
                    titleTextView[0].invalidate();
                    invalidate();
                } else {
                    snowflakesEffect = null;
                    fireworksEffect = new FireworksEffect();
                    titleTextView[0].invalidate();
                    invalidate();
                }
            }
        }
        return interceptTouchEventListener != null && interceptTouchEventListener.onTouch(this, ev) || super.onInterceptTouchEvent(ev);
    }

    protected boolean shouldClipChild(View child) {
        return clipContent && (child == titleTextView[0] || child == titleTextView[1] || child == subtitleTextView || child == menu || child == backButtonImageView || child == additionalSubtitleTextView || child == titlesContainer);
    }

    @Override
    protected boolean drawChild(Canvas canvas, View child, long drawingTime) {
        if (parentFragment != null && parentFragment.getParentLayout() != null && parentFragment.getParentLayout().isActionBarInCrossfade()) {
            return false;
        }
        if (drawBackButton && child == backButtonImageView) {
            return true;
        }

        boolean clip = shouldClipChild(child);
        if (clip) {
            canvas.save();
            canvas.clipRect(0, -getTranslationY() + (occupyStatusBar ? AndroidUtilities.statusBarHeight : 0), getMeasuredWidth(), getMeasuredHeight());
        }
        boolean result = super.drawChild(canvas, child, drawingTime);
        if (supportsHolidayImage && !titleOverlayShown && !LocaleController.isRTL && (child == titleTextView[0] || child == titleTextView[1] || child == titlesContainer && useContainerForTitles)) {
            Drawable drawable = Theme.getCurrentHolidayDrawable();
            if (drawable != null) {
                SimpleTextView titleView = child == titlesContainer ? titleTextView[0] : (SimpleTextView) child;
                if (titleView != null && titleView.getVisibility() == View.VISIBLE && titleView.getText() instanceof String) {
                    TextPaint textPaint = titleView.getTextPaint();
                    textPaint.getFontMetricsInt(fontMetricsInt);
                    textPaint.getTextBounds((String) titleView.getText(), 0, 1, rect);
                    int x = titleView.getTextStartX() + Theme.getCurrentHolidayDrawableXOffset() + (rect.width() - (drawable.getIntrinsicWidth() + Theme.getCurrentHolidayDrawableXOffset())) / 2;
                    int y = titleView.getTextStartY() + Theme.getCurrentHolidayDrawableYOffset() + (int) Math.ceil((titleView.getTextHeight() - rect.height()) / 2.0f) + (int) (dp(8) * (1f - titlesContainer.getScaleY()));
                    drawable.setBounds(x, y - drawable.getIntrinsicHeight(), x + drawable.getIntrinsicWidth(), y);
                    drawable.setAlpha((int) (255 * titlesContainer.getAlpha() * titleView.getAlpha()));
                    drawable.draw(canvas);
                    if (overlayTitleAnimationInProgress) {
                        child.invalidate();
                        invalidate();
                    }
                }

                if (Theme.canStartHolidayAnimation()) {
                    if (snowflakesEffect == null) {
                        snowflakesEffect = new SnowflakesEffect(0);
                    }
                } else if (!manualStart) {
                    if (snowflakesEffect != null) {
                        snowflakesEffect = null;
                    }
                }
                if (snowflakesEffect != null) {
                    snowflakesEffect.onDraw(this, canvas);
                } else if (fireworksEffect != null) {
                    fireworksEffect.onDraw(this, canvas);
                }
            }
        }
        if (clip) {
            canvas.restore();
        }
        return result;
    }

    @Override
    public void setTranslationY(float translationY) {
        super.setTranslationY(translationY);
        if (clipContent) {
            invalidate();
        }
    }

    public void setBackButtonImage(int resource) {
        if (backButtonImageView == null) {
            createBackButtonImage();
        }
        backButtonImageView.setVisibility(resource == 0 ? GONE : VISIBLE);
        backButtonImageView.setImageResource(resource);
        backButtonImageView.setColorFilter(new PorterDuffColorFilter(itemsColor, PorterDuff.Mode.SRC_IN));
        checkBackButtonLayerType();
    }

    private boolean mAlwaysApplyColorFilterToBackButton;

    public void alwaysApplyColorFilterToBackButton() {
        mAlwaysApplyColorFilterToBackButton = true;
    }

    private void createSubtitleTextView() {
        if (subtitleTextView != null) {
            return;
        }
        subtitleTextView = new SimpleTextView(getContext());
        subtitleTextView.setGravity(Gravity.LEFT);
        subtitleTextView.setVisibility(GONE);
        subtitleTextView.setTextColor(getThemedColor(Theme.key_actionBarDefaultSubtitle));
        addView(subtitleTextView, 0, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP));
    }

    public void createAdditionalSubtitleTextView() {
        if (additionalSubtitleTextView != null) {
            return;
        }
        additionalSubtitleTextView = new SimpleTextView(getContext());
        additionalSubtitleTextView.setGravity(Gravity.LEFT);
        additionalSubtitleTextView.setVisibility(GONE);
        additionalSubtitleTextView.setTextColor(getThemedColor(Theme.key_actionBarDefaultSubtitle));
        addView(additionalSubtitleTextView, 0, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP));
    }

    public SimpleTextView getAdditionalSubtitleTextView() {
        return additionalSubtitleTextView;
    }

    public void setAddToContainer(boolean value) {
        addToContainer = value;
    }

    public boolean shouldAddToContainer() {
        return addToContainer;
    }

    public void setClipContent(boolean value) {
        clipContent = value;
    }

    public void setSubtitle(CharSequence value) {
        if (value != null && subtitleTextView == null) {
            createSubtitleTextView();
        }
        if (subtitleTextView != null) {
            boolean isEmpty = TextUtils.isEmpty(value);
            subtitleTextView.setVisibility(!isEmpty && !isSearchFieldVisible ? VISIBLE : GONE);
            subtitleTextView.setAlpha(1f);
            if (!isEmpty) {
                subtitleTextView.setText(value);
            }
            subtitle = value;
        }
    }

    private void createTitleTextView(int i) {
        if (titleTextView[i] != null) {
            return;
        }
        titleTextView[i] = new SimpleTextView(getContext());
        titleTextView[i].setGravity(isCenterTitle ? Gravity.CENTER : Gravity.LEFT | Gravity.CENTER_VERTICAL);
        if (titleColorToSet != 0) {
            titleTextView[i].setTextColor(titleColorToSet);
        } else {
            titleTextView[i].setTextColor(getThemedColor(Theme.key_actionBarDefaultTitle));
        }
        titleTextView[i].setEmojiColor(titleTextView[i].getTextColor());
        titleTextView[i].setTypeface(AndroidUtilities.bold());
        titleTextView[i].setDrawablePadding(dp(4));
        titleTextView[i].setPadding(0, dp(8), 0, dp(8));
        titleTextView[i].setRightDrawableTopPadding(-dp(1));
        if (useContainerForTitles) {
            titlesContainer.addView(titleTextView[i], 0, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP));
        } else {
            addView(titleTextView[i], 0, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP));
        }
    }

    private boolean isCenterTitle;

    public void centerTitle() {
        isCenterTitle = true;
        if (titleTextView != null) {
            for (int a = 0; a < titleTextView.length; a++) {
                if (titleTextView[a] != null) {
                    titleTextView[a].setGravity(Gravity.CENTER);
                }
            }
        }
    }

    public void setTitleRightMargin(int value) {
        titleRightMargin = value;
    }

    public void setTitle(CharSequence value) {
        setTitle(value, null);
    }

    public void setTitle(CharSequence value, Drawable rightDrawable) {
        if (value != null && titleTextView[0] == null) {
            createTitleTextView(0);
        }
        if (titleTextView[0] != null) {
            titleTextView[0].setVisibility(value != null && !isSearchFieldVisible ? VISIBLE : INVISIBLE);
            titleTextView[0].setText(lastTitle = value);
            if (attached && lastRightDrawable instanceof AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable) {
                ((AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable) lastRightDrawable).setParentView(null);
            }
            titleTextView[0].setRightDrawable(lastRightDrawable = rightDrawable);
            if (attached && lastRightDrawable instanceof AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable) {
                ((AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable) lastRightDrawable).setParentView(titleTextView[0]);
            }
            titleTextView[0].setRightDrawableOnClick(rightDrawableOnClickListener);
        }
        fromBottom = false;
    }

    public void setRightDrawableOnClick(OnClickListener onClickListener) {
        rightDrawableOnClickListener = onClickListener;
        if (titleTextView[0] != null) {
            titleTextView[0].setRightDrawableOnClick(rightDrawableOnClickListener);
        }
        if (titleTextView[1] != null) {
            titleTextView[1].setRightDrawableOnClick(rightDrawableOnClickListener);
        }
    }

    public void setTitleColor(int color) {
        if (titleTextView[0] == null) {
            createTitleTextView(0);
        }
        solegramRawTitleColor = color;
        color = solegramReadable(color);
        titleColorToSet = color;
        titleTextView[0].setTextColor(color);
        titleTextView[0].setEmojiColor(color);
        if (titleTextView[1] != null) {
            titleTextView[1].setTextColor(color);
            titleTextView[1].setEmojiColor(color);
        }
    }

    public void setSubtitleColor(int color) {
        if (subtitleTextView == null) {
            createSubtitleTextView();
        }
        solegramRawSubtitleColor = color;
        color = solegramReadable(color);
        subtitleTextView.setTextColor(color);
    }

    public void setTitleScrollNonFitText(boolean b) {
        titleTextView[0].setScrollNonFitText(b);
    }

    public void setPopupItemsColor(int color, boolean icon, boolean forActionMode) {
        if (forActionMode && actionMode != null) {
            actionMode.setPopupItemsColor(color, icon);
        } else if (!forActionMode && menu != null) {
            menu.setPopupItemsColor(color, icon);
        }
    }

    public void setPopupItemsSelectorColor(int color, boolean forActionMode) {
        if (forActionMode && actionMode != null) {
            actionMode.setPopupItemsSelectorColor(color);
        } else if (!forActionMode && menu != null) {
            menu.setPopupItemsSelectorColor(color);
        }
    }

    public void setPopupBackgroundColor(int color, boolean forActionMode) {
        if (forActionMode && actionMode != null) {
            actionMode.redrawPopup(color);
        } else if (!forActionMode && menu != null) {
            menu.redrawPopup(color);
        }
    }

    public SimpleTextView getSubtitleTextView() {
        return subtitleTextView;
    }

    public SimpleTextView getTitleTextView() {
        return titleTextView[0];
    }

    public Paint.FontMetricsInt getTitleFontMetricsInt() {
        if (titleTextView[0] == null) {
            TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            paint.setTextSize(dp(!AndroidUtilities.isTablet() && getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE ? 18 : 20));
            return paint.getFontMetricsInt();
        }
        return titleTextView[0].getPaint().getFontMetricsInt();
    }

    public SimpleTextView getTitleTextView2() {
        return titleTextView[1];
    }

    public String getTitle() {
        if (titleTextView[0] == null) {
            return null;
        }
        return titleTextView[0].getText().toString();
    }

    public String getSubtitle() {
        if (subtitleTextView == null || subtitle == null) {
            return null;
        }
        return subtitle.toString();
    }

    public ActionBarMenu createMenu() {
        if (menu != null) {
            return menu;
        }
        menu = new ActionBarMenu(getContext(), this);
        addView(menu, 0, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.MATCH_PARENT, Gravity.RIGHT));
        if (solegramButtonsGlass) {
            // Solegram: the buttons sit closer together inside their bubble
            menu.setTranslationX(-dp(10));
            menu.setGlassMode(true);
            menu.solegramKeepTextItems = true;
            glassDrawableMenu = solegramCreateMenuBubble();
        }
        return menu;
    }

    public void setActionBarMenuOnItemClick(ActionBarMenuOnItemClick listener) {
        actionBarMenuOnItemClick = listener;
    }

    public ActionBarMenuOnItemClick getActionBarMenuOnItemClick() {
        return actionBarMenuOnItemClick;
    }

    public ImageView getBackButton() {
        return backButtonImageView;
    }

    public ActionBarMenu createActionMode() {
        return createActionMode(true, null);
    }

    public boolean actionModeIsExist(String tag) {
        if (actionMode != null && ((actionModeTag == null && tag == null) || (actionModeTag != null && actionModeTag.equals(tag)))) {
            return true;
        }
        return false;
    }

    private Runnable doOnActionModeFactorChanged;

    public void setOnActionModeFactorChangeListener(Runnable listener) {
        doOnActionModeFactorChanged = listener;
    }

    public float getActionModeFactor() {
        return actionMode != null ? actionMode.getAlpha() : 0;
    }

    /** Solegram: how much the selection bar shows; one that was created but never shown counts as hidden. */
    public float solegramActionModeFactor() {
        if (actionMode == null || !actionModeVisible && actionMode.getVisibility() != VISIBLE) {
            return 0;
        }
        return actionMode.getAlpha();
    }

    public ActionBarMenu createActionMode(boolean needTop, String tag) {
        if (actionModeIsExist(tag)) {
            return actionMode;
        }
        if (actionMode != null) {
            removeView(actionMode);
            actionMode = null;
        }
        actionModeTag = tag;
        actionMode = new ActionBarMenu(getContext(), this) {
            @Override
            public void setBackgroundColor(int color) {
                actionModeColor = color;
                if (!blurredBackground) {
                    super.setBackgroundColor(actionModeColor);
                }
            }

            @Override
            protected void dispatchDraw(Canvas canvas) {
                if (blurredBackground && drawBlur && actionModeColor != 0) {
                    rectTmp.set(0, 0, getMeasuredWidth(), getMeasuredHeight());
                    blurScrimPaint.setColor(actionModeColor);
                    contentView.drawBlurRect(canvas, 0, rectTmp, blurScrimPaint, true);
                }
                super.dispatchDraw(canvas);
            }

            @Override
            public void setAlpha(float alpha) {
                super.setAlpha(alpha);
                solegramInvalidateBubbles();
                if (doOnActionModeFactorChanged != null) {
                    doOnActionModeFactorChanged.run();
                }
            }

            @Override
            protected void onAttachedToWindow() {
                super.onAttachedToWindow();
                if (contentView != null) {
                    contentView.blurBehindViews.add(this);
                }
            }

            @Override
            protected void onDetachedFromWindow() {
                super.onDetachedFromWindow();
                if (contentView != null) {
                    contentView.blurBehindViews.remove(this);
                }
            }
        };
        actionMode.setTranslationX(glassMode ? -dp(10) : 0);
        actionMode.setGlassMode(glassMode);
        actionMode.isActionMode = true;
        actionMode.setClickable(true);
        if (!glassMode) {
            actionMode.setBackgroundColor(getThemedColor(Theme.key_actionBarActionModeDefault));
        }
        addView(actionMode, indexOfChild(backButtonImageView));
        actionMode.setPadding(0, occupyStatusBar ? AndroidUtilities.statusBarHeight : 0, 0, 0);
        FrameLayout.LayoutParams layoutParams = (FrameLayout.LayoutParams) actionMode.getLayoutParams();
        layoutParams.height = LayoutHelper.MATCH_PARENT;
        layoutParams.width = LayoutHelper.MATCH_PARENT;
        layoutParams.bottomMargin = extraHeight;
        layoutParams.gravity = Gravity.RIGHT;
        actionMode.setLayoutParams(layoutParams);
        actionMode.setVisibility(INVISIBLE);

//        if (occupyStatusBar && needTop && actionModeTop == null && !blurredBackground) {
//            actionModeTop = new View(getContext());
//            actionModeTop.setBackgroundColor(getThemedColor(Theme.key_actionBarActionModeDefaultTop));
//            addView(actionModeTop);
//            layoutParams = (FrameLayout.LayoutParams) actionModeTop.getLayoutParams();
//            layoutParams.height = AndroidUtilities.statusBarHeight;
//            layoutParams.width = LayoutHelper.MATCH_PARENT;
//            layoutParams.gravity = Gravity.TOP | Gravity.LEFT;
//            actionModeTop.setLayoutParams(layoutParams);
//            actionModeTop.setVisibility(INVISIBLE);
//        }

        return actionMode;
    }

    public void onDrawCrossfadeContent(Canvas canvas, boolean front, boolean hideBackDrawable, float progress) {
        for (int i = 0; i < getChildCount(); i++) {
            View ch = getChildAt(i);
            if ((!hideBackDrawable || ch != backButtonImageView) && ch.getVisibility() == View.VISIBLE && ch instanceof ActionBarMenu) {
                canvas.save();
                canvas.translate(ch.getX(), ch.getY());
                ch.draw(canvas);
                canvas.restore();
            }
        }

        canvas.save();
        canvas.translate(front ? getWidth() * progress * 0.5f : -getWidth() * 0.4f * (1f - progress), 0);
        for (int i = 0; i < getChildCount(); i++) {
            View ch = getChildAt(i);
            if ((!hideBackDrawable || ch != backButtonImageView) && ch.getVisibility() == View.VISIBLE && !(ch instanceof ActionBarMenu)) {
                canvas.save();
                canvas.translate(ch.getX(), ch.getY());
                ch.draw(canvas);
                canvas.restore();
            }
        }
        canvas.restore();
    }

    public void showActionMode() {
        showActionMode(true, null, null, null, null, null, 0);
    }

    public void showActionMode(boolean animated) {
        showActionMode(animated, null, null, null, null, null, 0);
    }

    public void showActionMode(boolean animated, View extraView, View showingView, View[] hidingViews, boolean[] hideView, View translationView, int translation) {
        if (actionMode == null || actionModeVisible) {
            return;
        }
        actionModeVisible = true;
        checkMenuItemsWidth();
        if (animated) {
            ArrayList<Animator> animators = new ArrayList<>();
            animators.add(ObjectAnimator.ofFloat(actionMode, View.ALPHA, 0.0f, 1.0f));
            if (hidingViews != null) {
                for (int a = 0; a < hidingViews.length; a++) {
                    if (hidingViews[a] != null) {
                        animators.add(ObjectAnimator.ofFloat(hidingViews[a], View.ALPHA, 1.0f, 0.0f));
                    }
                }
            }
            if (showingView != null) {
                animators.add(ObjectAnimator.ofFloat(showingView, View.ALPHA, 0.0f, 1.0f));
            }
            if (translationView != null) {
                animators.add(ObjectAnimator.ofFloat(translationView, View.TRANSLATION_Y, translation));
                actionModeTranslationView = translationView;
            }
            actionModeExtraView = extraView;
            actionModeShowingView = showingView;
            actionModeHidingViews = hidingViews;
            if (actionModeExtraView != null) {
                animators.add(ObjectAnimator.ofFloat(actionModeExtraView, View.TRANSLATION_Y, 0));
            }
            if (actionModeColor == 0) {
                if (!isSearchFieldVisible) {
                    if (titleTextView[0] != null) {
                        animators.add(ObjectAnimator.ofFloat(titleTextView[0], View.ALPHA, 0));
                    }
                    if (subtitleTextView != null && !TextUtils.isEmpty(subtitle)) {
                        animators.add(ObjectAnimator.ofFloat(subtitleTextView, View.ALPHA, 0));
                    }
                }
                if (menu != null) {
                    animators.add(ObjectAnimator.ofFloat(menu, View.ALPHA, 0));
                }
            }
            final int color = actionModeColor == 0 ? actionBarColor : actionModeColor;
            if (color == 0 || glassMode) {
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.needCheckSystemBarColors);
            } else if (ColorUtils.calculateLuminance(color) < 0.7f) {
                AndroidUtilities.setLightStatusBar((Activity) getContext(), false);
            } else {
                AndroidUtilities.setLightStatusBar((Activity) getContext(), true);
            }
            if (actionModeAnimation != null) {
                actionModeAnimation.cancel();
            }
            actionModeAnimation = new AnimatorSet();
            actionModeAnimation.playTogether(animators);
            if (backgroundUpdateListener != null) {
                ValueAnimator alphaUpdate = ValueAnimator.ofFloat(0, 1);
                alphaUpdate.addUpdateListener(anm -> {
                    if (backgroundUpdateListener != null) {
                        backgroundUpdateListener.run();
                    }
                });
                actionModeAnimation.playTogether(alphaUpdate);
            }
            actionModeAnimation.setDuration(200);
            actionModeAnimation.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationStart(Animator animation) {
                    actionMode.setVisibility(VISIBLE);
                }

                @Override
                public void onAnimationEnd(Animator animation) {
                    if (actionModeAnimation != null && actionModeAnimation.equals(animation)) {
                        actionModeAnimation = null;
                        if (titleTextView[0] != null) {
                            titleTextView[0].setVisibility(INVISIBLE);
                        }
                        if (subtitleTextView != null && !TextUtils.isEmpty(subtitle)) {
                            subtitleTextView.setVisibility(INVISIBLE);
                        }
                        if (menu != null) {
                            menu.setVisibility(INVISIBLE);
                        }
                        if (actionModeHidingViews != null) {
                            for (int a = 0; a < actionModeHidingViews.length; a++) {
                                if (actionModeHidingViews[a] != null) {
                                    if (hideView == null || a >= hideView.length || hideView[a]) {
                                        actionModeHidingViews[a].setVisibility(INVISIBLE);
                                    }
                                }
                            }
                        }
                    }
                }

                @Override
                public void onAnimationCancel(Animator animation) {
                    if (actionModeAnimation != null && actionModeAnimation.equals(animation)) {
                        actionModeAnimation = null;
                    }
                }
            });
            actionModeAnimation.start();
            if (backButtonImageView != null) {
                Drawable drawable = backButtonImageView.getDrawable();
                if (drawable instanceof BackDrawable) {
                    ((BackDrawable) drawable).setRotation(1, true);
                }
                backButtonImageView.setBackgroundDrawable(Theme.createSelectorDrawable(itemsActionModeBackgroundColor));
            }
        } else {
            actionMode.setAlpha(1.0f);
            if (hidingViews != null) {
                for (int a = 0; a < hidingViews.length; a++) {
                    if (hidingViews[a] != null) {
                        hidingViews[a].setAlpha(0.0f);
                    }
                }
            }
            if (showingView != null) {
                showingView.setAlpha(1.0f);
            }
            if (translationView != null) {
                translationView.setTranslationY(translation);
                actionModeTranslationView = translationView;
            }
            actionModeExtraView = extraView;
            if (actionModeExtraView != null) {
                actionModeExtraView.setTranslationY(0);
            }
            actionModeShowingView = showingView;
            actionModeHidingViews = hidingViews;
            final int color = actionModeColor == 0 ? actionBarColor : actionModeColor;
            if (color == 0 || glassMode) {
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.needCheckSystemBarColors);
            } else if (ColorUtils.calculateLuminance(color) < 0.7f) {
                AndroidUtilities.setLightStatusBar((Activity) getContext(), false);
            } else {
                AndroidUtilities.setLightStatusBar((Activity) getContext(), true);
            }
            actionMode.setVisibility(VISIBLE);
            if (titleTextView[0] != null) {
                titleTextView[0].setVisibility(INVISIBLE);
            }
            if (subtitleTextView != null && !TextUtils.isEmpty(subtitle)) {
                subtitleTextView.setVisibility(INVISIBLE);
            }
            if (menu != null) {
                menu.setVisibility(INVISIBLE);
            }
            if (actionModeHidingViews != null) {
                for (int a = 0; a < actionModeHidingViews.length; a++) {
                    if (actionModeHidingViews[a] != null) {
                        if (hideView == null || a >= hideView.length || hideView[a]) {
                            actionModeHidingViews[a].setVisibility(INVISIBLE);
                        }
                    }
                }
            }
            if (backButtonImageView != null) {
                Drawable drawable = backButtonImageView.getDrawable();
                if (drawable instanceof BackDrawable) {
                    ((BackDrawable) drawable).setRotation(1, false);
                }
                backButtonImageView.setBackgroundDrawable(Theme.createSelectorDrawable(itemsActionModeBackgroundColor));
            }
        }
    }

    public void hideActionMode() {
        if (actionMode == null || !actionModeVisible) {
            return;
        }
        actionMode.hideAllPopupMenus();
        actionModeVisible = false;
        checkMenuItemsWidth();
        ArrayList<Animator> animators = new ArrayList<>();
        animators.add(ObjectAnimator.ofFloat(actionMode, View.ALPHA, 0.0f));
        if (actionModeHidingViews != null) {
            for (int a = 0; a < actionModeHidingViews.length; a++) {
                if (actionModeHidingViews[a] != null) {
                    actionModeHidingViews[a].setVisibility(VISIBLE);
                    animators.add(ObjectAnimator.ofFloat(actionModeHidingViews[a], View.ALPHA, 1.0f));
                }
            }
        }
        if (actionModeTranslationView != null) {
            animators.add(ObjectAnimator.ofFloat(actionModeTranslationView, View.TRANSLATION_Y, 0.0f));
            actionModeTranslationView = null;
        }
        if (actionModeShowingView != null) {
            animators.add(ObjectAnimator.ofFloat(actionModeShowingView, View.ALPHA, 0.0f));
        }
        if (actionModeExtraView != null) {
            animators.add(ObjectAnimator.ofFloat(actionModeExtraView, View.TRANSLATION_Y, actionModeExtraView.getMeasuredHeight()));
        }
        if (!isSearchFieldVisible) {
            if (titleTextView[0] != null) {
                animators.add(ObjectAnimator.ofFloat(titleTextView[0], View.ALPHA, 1));
            }
            if (subtitleTextView != null && !TextUtils.isEmpty(subtitle)) {
                animators.add(ObjectAnimator.ofFloat(subtitleTextView, View.ALPHA, 1));
            }
        }
        if (menu != null) {
            animators.add(ObjectAnimator.ofFloat(menu, View.ALPHA, 1));
        }
        if (actionBarColor == 0 || glassMode || solegramFloating) {
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.needCheckSystemBarColors);
        } else if (ColorUtils.calculateLuminance(actionBarColor) < 0.7f) {
            AndroidUtilities.setLightStatusBar((Activity) getContext(), false);
        } else {
            AndroidUtilities.setLightStatusBar((Activity) getContext(), true);
        }
        if (actionModeAnimation != null) {
            actionModeAnimation.cancel();
        }
        actionModeAnimation = new AnimatorSet();
        actionModeAnimation.playTogether(animators);
        if (backgroundUpdateListener != null) {
            ValueAnimator alphaUpdate = ValueAnimator.ofFloat(0, 1);
            alphaUpdate.addUpdateListener(anm -> {
                if (backgroundUpdateListener != null) {
                    backgroundUpdateListener.run();
                }
            });
            actionModeAnimation.playTogether(alphaUpdate);
        }
        actionModeAnimation.setDuration(200);
        actionModeAnimation.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (actionModeAnimation != null && actionModeAnimation.equals(animation)) {
                    actionModeAnimation = null;
                    actionMode.setVisibility(INVISIBLE);
                    if (actionModeExtraView != null) {
                        actionModeExtraView.setVisibility(INVISIBLE);
                    }
                }
            }

            @Override
            public void onAnimationCancel(Animator animation) {
                if (actionModeAnimation != null && actionModeAnimation.equals(animation)) {
                    actionModeAnimation = null;
                }
            }
        });
        actionModeAnimation.start();
        if (!isSearchFieldVisible) {
            if (titleTextView[0] != null) {
                titleTextView[0].setVisibility(VISIBLE);
            }
            if (subtitleTextView != null && !TextUtils.isEmpty(subtitle)) {
                subtitleTextView.setVisibility(VISIBLE);
            }
        }
        if (menu != null) {
            menu.setVisibility(VISIBLE);
        }
        if (backButtonImageView != null) {
            Drawable drawable = backButtonImageView.getDrawable();
            if (drawable instanceof BackDrawable) {
                ((BackDrawable) drawable).setRotation(0, true);
            }
            backButtonImageView.setBackgroundDrawable(Theme.createSelectorDrawable(itemsBackgroundColor));
        }
    }

    public void showActionModeTop() {
        if (occupyStatusBar && actionModeTop == null) {
            actionModeTop = new View(getContext());
            actionModeTop.setBackgroundColor(getThemedColor(Theme.key_actionBarActionModeDefaultTop));
            addView(actionModeTop);
            FrameLayout.LayoutParams layoutParams = (FrameLayout.LayoutParams) actionModeTop.getLayoutParams();
            layoutParams.height = AndroidUtilities.statusBarHeight;
            layoutParams.width = LayoutHelper.MATCH_PARENT;
            layoutParams.gravity = Gravity.TOP | Gravity.LEFT;
            actionModeTop.setLayoutParams(layoutParams);
        }
    }

    public void setActionModeTopColor(int color) {
        if (actionModeTop != null) {
            actionModeTop.setBackgroundColor(color);
        }
    }

    public void setSearchTextColor(int color, boolean placeholder) {
        if (menu != null) {
            menu.setSearchTextColor(color, placeholder);
        }
    }

    public void setSearchCursorColor(int color) {
        if (menu != null) {
            menu.setSearchCursorColor(color);
        }
    }

    public void setActionModeColor(int color) {
        if (actionMode != null) {
            actionMode.setBackgroundColor(color);
        }
    }

    public void setActionModeOverrideColor(int color) {
        actionModeColor = color;
    }

    @Override
    public void setBackgroundColor(int color) {
        actionBarColor = color;
        if (!blurredBackground && !solegramFloating) {
            super.setBackgroundColor(actionBarColor);
        }
        if (backButtonImageView != null) {
            Drawable drawable = backButtonImageView.getDrawable();
            if (drawable instanceof MenuDrawable) {
                ((MenuDrawable) drawable).setBackColor(color);
            }
        }
    }

    public int getBackgroundColor() {
        return actionBarColor;
    }

    public boolean isActionModeShowed() {
        return actionMode != null && actionModeVisible;
    }

    public boolean isActionModeShowed(String tag) {
        return actionMode != null && actionModeVisible && ((actionModeTag == null && tag == null) || (actionModeTag != null && actionModeTag.equals(tag)));
    }

    Runnable backgroundUpdateListener;
    AnimatorSet searchVisibleAnimator;

    public void listenToBackgroundUpdate(Runnable invalidate) {
        backgroundUpdateListener = invalidate;
    }

    protected boolean onSearchChangedIgnoreTitles() {
        return false;
    }

    public void onSearchFieldVisibilityChanged(boolean visible) {
        isSearchFieldVisible = visible;
        checkMenuItemsWidth();
        if (searchVisibleAnimator != null) {
            searchVisibleAnimator.cancel();
        }
        searchVisibleAnimator = new AnimatorSet();
        final ArrayList<View> viewsToHide = new ArrayList<>();

        final boolean ignoreTitles = onSearchChangedIgnoreTitles();
        if (!ignoreTitles) {
            if (titleTextView[0] != null) {
                viewsToHide.add(titleTextView[0]);
            }

            if (subtitleTextView != null && !TextUtils.isEmpty(subtitle)) {
                viewsToHide.add(subtitleTextView);
                subtitleTextView.setVisibility(visible ? INVISIBLE : VISIBLE);
            }
        }

        ValueAnimator alphaUpdate = ValueAnimator.ofFloat(searchFieldVisibleAlpha, visible ? 1f : 0f);
        alphaUpdate.addUpdateListener(anm -> {
            searchFieldVisibleAlpha = (float) anm.getAnimatedValue();

            if (glassDrawable != null && glassModeIsForum) {
                final float r1 = dp(23);
                final float r2 = lerp(dp(18.33f), dp(23), searchFieldVisibleAlpha);
                glassDrawable.setRadius(r2, r1, r1, r2);
                invalidate();
            }

            if (glassMode && menu != null) {
                menu.setTranslationX(-lerp((float) dp(10), dp(5), searchFieldVisibleAlpha));
            }
            if (solegramButtonsGlass) {
                solegramInvalidateBubbles(); // the bubbles give way to the search field
            }
            if (solegramFloating && getParent() instanceof View) {
                ((View) getParent()).invalidate(); // the screen draws the search's panel under a floating bar
            }
            if (backgroundUpdateListener != null) {
                backgroundUpdateListener.run();
            }
        });
        searchVisibleAnimator.playTogether(alphaUpdate);

        for (int i = 0; i < viewsToHide.size(); i++) {
            View view = viewsToHide.get(i);
            if (!visible) {
                view.setVisibility(View.VISIBLE);
                view.setAlpha(0);
                view.setScaleX(0.95f);
                view.setScaleY(0.95f);
            }
            searchVisibleAnimator.playTogether(ObjectAnimator.ofFloat(view, View.ALPHA, visible ? 0f : 1f));
            searchVisibleAnimator.playTogether(ObjectAnimator.ofFloat(view, View.SCALE_Y, visible ? 0.95f : 1f));
            searchVisibleAnimator.playTogether(ObjectAnimator.ofFloat(view, View.SCALE_X, visible ? 0.95f : 1f));
        }
        if (avatarSearchImageView != null) {
            avatarSearchImageView.setVisibility(View.VISIBLE);
            searchVisibleAnimator.playTogether(ObjectAnimator.ofFloat(avatarSearchImageView, View.ALPHA, visible ? 1f : 0f));
        }
        centerScale = true;
        requestLayout();
        searchVisibleAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                for (int i = 0; i < viewsToHide.size(); i++) {
                    View view = viewsToHide.get(i);
                    if (visible) {
                        view.setVisibility(View.INVISIBLE);
                        view.setAlpha(0);
                    } else {
                        view.setAlpha(1f);
                    }
                }

                if (visible && !ignoreTitles) {
                    if (titleTextView[0] != null) {
                        titleTextView[0].setVisibility(View.GONE);
                    }
                    if (titleTextView[1] != null) {
                        titleTextView[1].setVisibility(View.GONE);
                    }
                }

                if (avatarSearchImageView != null) {
                    if (!visible) {
                        avatarSearchImageView.setVisibility(View.GONE);
                    }
                }
            }
        });

        searchVisibleAnimator.setDuration(150).start();

        if (backButtonImageView != null) {
            Drawable drawable = backButtonImageView.getDrawable();
            if (drawable instanceof MenuDrawable) {
                MenuDrawable menuDrawable = (MenuDrawable) drawable;
                menuDrawable.setRotateToBack(true);
                menuDrawable.setRotation(visible ? 1 : 0, true);
            }
        }
    }

    public void setInterceptTouches(boolean value) {
        interceptTouches = value;
    }

    public void setInterceptTouchEventListener(View.OnTouchListener listener) {
        interceptTouchEventListener = listener;
    }

    public void setExtraHeight(int value) {
        extraHeight = value;
        if (actionMode != null) {
            FrameLayout.LayoutParams layoutParams = (FrameLayout.LayoutParams) actionMode.getLayoutParams();
            layoutParams.bottomMargin = extraHeight;
            actionMode.setLayoutParams(layoutParams);
        }
    }

    public void closeSearchField() {
        closeSearchField(true);
    }

    public void closeSearchField(boolean closeKeyboard) {
        if (!isSearchFieldVisible || menu == null) {
            return;
        }
        menu.closeSearchField(closeKeyboard);
    }

    public void openSearchField(String text, boolean animated) {
        if (menu == null || text == null) {
            return;
        }
        menu.openSearchField(!isSearchFieldVisible, !isSearchFieldVisible, text, animated);
    }

    public void openSearchField(boolean animated) {
        if (menu == null) {
            return;
        }
        menu.openSearchField(!isSearchFieldVisible, false, "", animated);
    }

    public void setSearchFilter(FiltersView.MediaFilterData filter) {
        if (menu != null) {
            menu.setFilter(filter);
        }
    }

    public void clearSearchFilters() {
        if (menu != null) {
            menu.clearSearchFilters();
        }
    }

    public void setSearchFieldText(String text) {
        menu.setSearchFieldText(text);
    }

    public void onSearchPressed() {
        menu.onSearchPressed();
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        if (backButtonImageView != null) {
            backButtonImageView.setEnabled(enabled);
        }
        if (menu != null) {
            menu.setEnabled(enabled);
        }
        if (actionMode != null) {
            actionMode.setEnabled(enabled);
        }
    }

    @Override
    public void requestLayout() {
        if (ignoreLayoutRequest) {
            return;
        }
        super.requestLayout();
    }

    @Override
    public void onViewAdded(View child) {
        super.onViewAdded(child);
    }

    private int additionalTextLeft;

    public void setAdditionalTextLeft(int x) {
        additionalTextLeft = x;
    }


    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);
        int actionBarHeight = getCurrentActionBarHeight();
        int actionBarHeightSpec = MeasureSpec.makeMeasureSpec(actionBarHeight, MeasureSpec.EXACTLY);

        ignoreLayoutRequest = true;
        if (actionModeTop != null) {
            FrameLayout.LayoutParams layoutParams = (FrameLayout.LayoutParams) actionModeTop.getLayoutParams();
            layoutParams.height = AndroidUtilities.statusBarHeight;
        }
        if (actionMode != null) {
            actionMode.setPadding(0, occupyStatusBar ? AndroidUtilities.statusBarHeight : 0, 0, 0);
        }
        ignoreLayoutRequest = false;

        setMeasuredDimension(width, actionBarHeight + (occupyStatusBar ? AndroidUtilities.statusBarHeight : 0) + extraHeight);

        int textLeft;
        if (backButtonImageView != null && backButtonImageView.getVisibility() != GONE) {
            backButtonImageView.measure(MeasureSpec.makeMeasureSpec(dp(54), MeasureSpec.EXACTLY), actionBarHeightSpec);
            textLeft = dp(AndroidUtilities.isTablet() ? 80 : 72);
        } else {
            textLeft = dp(AndroidUtilities.isTablet() ? 26 : 18);
        }
        // textLeft += additionalTextLeft;

        if (menu != null && menu.getVisibility() != GONE) {
            int menuWidth;
            boolean searchFieldIsVisible = menu.searchFieldVisible();
            if (searchFieldIsVisible && !this.isSearchFieldVisible) {
                menuWidth = MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST);
                menu.measure(menuWidth, actionBarHeightSpec);
                int itemsWidth = menu.getItemsMeasuredWidth(true);
                menuWidth = MeasureSpec.makeMeasureSpec(width - dp(menuOccupyBack ? 0 : AndroidUtilities.isTablet() ? 74 : 66) + menu.getItemsMeasuredWidth(true), MeasureSpec.EXACTLY);
                if (!isMenuOffsetSuppressed) {
                    menu.translateXItems(-itemsWidth);
                }
            } else if (isSearchFieldVisible) {
                menuWidth = MeasureSpec.makeMeasureSpec(width - dp(menuOccupyBack ? 0 : AndroidUtilities.isTablet() ? 74 : 66), MeasureSpec.EXACTLY);
                if (!isMenuOffsetSuppressed) {
                    menu.translateXItems(0);
                }
            } else {
                menuWidth = MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST);
                if (!isMenuOffsetSuppressed) {
                    menu.translateXItems(0);
                }
            }
            menu.measure(menuWidth, actionBarHeightSpec);

        }

        for (int i = 0; i < 2; i++) {
            if (titleTextView[0] != null && titleTextView[0].getVisibility() != GONE || subtitleTextView != null && subtitleTextView.getVisibility() != GONE) {
                int availableWidth = width - (menu != null ? menu.getMeasuredWidth() : 0) - dp(16) - textLeft - titleRightMargin;
                availableWidth = Math.max(availableWidth, 0);

                if (((fromBottom && i == 0) || (!fromBottom && i == 1)) && overlayTitleAnimation && titleAnimationRunning) {
                    titleTextView[i].setTextSize(glassMode ? 17 : !AndroidUtilities.isTablet() && getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE ? 18 : 20);
                } else {
                    if (titleTextView[0] != null && titleTextView[0].getVisibility() != GONE && subtitleTextView != null && subtitleTextView.getVisibility() != GONE) {
                        if (titleTextView[i] != null) {
                            titleTextView[i].setTextSize(glassMode ? 17 : AndroidUtilities.isTablet() ? 20 : 18);
                        }
                        subtitleTextView.setTextSize(AndroidUtilities.isTablet() ? 16 : 14);
                        if (additionalSubtitleTextView != null) {
                            additionalSubtitleTextView.setTextSize(AndroidUtilities.isTablet() ? 16 : 14);
                        }
                    } else {
                        if (titleTextView[i] != null && titleTextView[i].getVisibility() != GONE) {
                            titleTextView[i].setTextSize(glassMode ? 17 : !AndroidUtilities.isTablet() && getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE ? 18 : 20);
                        }
                        if (subtitleTextView != null && subtitleTextView.getVisibility() != GONE) {
                            subtitleTextView.setTextSize(!AndroidUtilities.isTablet() && getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE ? 14 : 16);
                        }
                        if (additionalSubtitleTextView != null) {
                            additionalSubtitleTextView.setTextSize(!AndroidUtilities.isTablet() && getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE ? 14 : 16);
                        }
                    }
                }

                if (titleTextView[i] != null && titleTextView[i].getVisibility() != GONE) {
                    titleTextView[i].measure(MeasureSpec.makeMeasureSpec(availableWidth, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(dp(24) + titleTextView[i].getPaddingTop() + titleTextView[i].getPaddingBottom(), MeasureSpec.AT_MOST));
                    if (centerScale) {
                        CharSequence text = titleTextView[i].getText();
                        titleTextView[i].setPivotX(titleTextView[i].getTextPaint().measureText(text, 0, text.length()) / 2f);
                        titleTextView[i].setPivotY((dp(24) >> 1));
                    } else {
                        titleTextView[i].setPivotX(0);
                        titleTextView[i].setPivotY(0);
                    }
                }
                if (subtitleTextView != null && subtitleTextView.getVisibility() != GONE) {
                    subtitleTextView.measure(MeasureSpec.makeMeasureSpec(availableWidth, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(dp(20), MeasureSpec.AT_MOST));
                }
                if (additionalSubTitleOverlayContainer != null) {
                    additionalSubTitleOverlayContainer.measure(MeasureSpec.makeMeasureSpec(availableWidth, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST));
                }
                if (additionalSubtitleTextView != null && additionalSubtitleTextView.getVisibility() != GONE) {
                    additionalSubtitleTextView.measure(MeasureSpec.makeMeasureSpec(availableWidth, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(dp(20), MeasureSpec.AT_MOST));
                }
            }
        }

        if (avatarSearchImageView != null) {
            avatarSearchImageView.measure(
                MeasureSpec.makeMeasureSpec(dp(42), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(dp(42), MeasureSpec.EXACTLY)
            );
        }

        int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE || child == titleTextView[0] || child == titleTextView[1] || child == additionalSubTitleOverlayContainer || child == subtitleTextView || child == menu || child == backButtonImageView || child == additionalSubtitleTextView || child == avatarSearchImageView) {
                continue;
            }
            measureChildWithMargins(child, widthMeasureSpec, 0, MeasureSpec.makeMeasureSpec(getMeasuredHeight(), MeasureSpec.EXACTLY), 0);
        }
    }

    public void setMenuOffsetSuppressed(boolean menuOffsetSuppressed) {
        isMenuOffsetSuppressed = menuOffsetSuppressed;
    }

    int prevWidth;

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int additionalTop = occupyStatusBar ? AndroidUtilities.statusBarHeight : 0;
        if (prevWidth != getMeasuredWidth()) {
            prevWidth = getMeasuredWidth();
            checkAvatarContainerWidth(animatorAvatarContainerWidth.isAnimating());
        }

        int textLeft;
        if (backButtonImageView != null && backButtonImageView.getVisibility() != GONE) {
            backButtonImageView.layout(0, additionalTop, backButtonImageView.getMeasuredWidth(), additionalTop + backButtonImageView.getMeasuredHeight());
            textLeft = glassMode ? dp(76) : dp(AndroidUtilities.isTablet() ? 80 : 72);
        } else {
            textLeft = glassMode ? dp(24) : dp(AndroidUtilities.isTablet() ? 26 : 18);
        }
        textLeft += additionalTextLeft;

        if (menu != null && menu.getVisibility() != GONE) {
            int menuLeft = menu.searchFieldVisible() ? dp(menuOccupyBack ? 0 : AndroidUtilities.isTablet() ? 74 : 66) : (getMeasuredWidth()) - menu.getMeasuredWidth();
            menu.layout(menuLeft, additionalTop, menuLeft + menu.getMeasuredWidth(), additionalTop + menu.getMeasuredHeight());
        }

        for (int i = 0; i < 2; i++) {
            if (titleTextView[i] != null && titleTextView[i].getVisibility() != GONE) {
                int textTop;
                if (((fromBottom && i == 0) || (!fromBottom && i == 1)) && overlayTitleAnimation && titleAnimationRunning) {
                    textTop = (getCurrentActionBarHeight() - titleTextView[i].getTextHeight()) / 2;
                } else {
                    if ((subtitleTextView != null && subtitleTextView.getVisibility() != GONE)) {
                        textTop = (getCurrentActionBarHeight() / 2 - titleTextView[i].getTextHeight()) / 2 + dp(2) + dp(!AndroidUtilities.isTablet() && getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE ? 2 : 3);
                    } else {
                        textTop = (getCurrentActionBarHeight() - titleTextView[i].getTextHeight()) / 2;
                    }
                }
                titleTextView[i].layout(textLeft, additionalTop + textTop - titleTextView[i].getPaddingTop(), textLeft + titleTextView[i].getMeasuredWidth(), additionalTop + textTop + titleTextView[i].getTextHeight() - titleTextView[i].getPaddingTop() + titleTextView[i].getPaddingBottom());
            }
        }
        if (additionalSubTitleOverlayContainer != null) {
            int textTop = getCurrentActionBarHeight() / 2 + (getCurrentActionBarHeight() / 2 - additionalSubTitleOverlayContainer.getMeasuredHeight()) / 2 - dp(2);
            additionalSubTitleOverlayContainer.layout(textLeft, additionalTop + textTop, textLeft + additionalSubTitleOverlayContainer.getMeasuredWidth(), additionalTop + textTop + additionalSubTitleOverlayContainer.getMeasuredHeight());
        }
        if (subtitleTextView != null && subtitleTextView.getVisibility() != GONE) {
            int textTop = getCurrentActionBarHeight() / 2 + (getCurrentActionBarHeight() / 2 - subtitleTextView.getTextHeight()) / 2 - dp(2);
            subtitleTextView.layout(textLeft, additionalTop + textTop, textLeft + subtitleTextView.getMeasuredWidth(), additionalTop + textTop + subtitleTextView.getTextHeight());
        }

        if (additionalSubtitleTextView != null && additionalSubtitleTextView.getVisibility() != GONE) {
            int textTop = getCurrentActionBarHeight() / 2 + (getCurrentActionBarHeight() / 2 - additionalSubtitleTextView.getTextHeight()) / 2 - dp(!AndroidUtilities.isTablet() && getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE ? 1 : 1);
            additionalSubtitleTextView.layout(textLeft, additionalTop + textTop, textLeft + additionalSubtitleTextView.getMeasuredWidth(), additionalTop + textTop + additionalSubtitleTextView.getTextHeight());
        }

        if (avatarSearchImageView != null) {
            avatarSearchImageView.layout(
                dp(56 + 8),
                additionalTop + (getCurrentActionBarHeight() - avatarSearchImageView.getMeasuredHeight()) / 2,
                dp(56 + 8) + avatarSearchImageView.getMeasuredWidth(),
                additionalTop + (getCurrentActionBarHeight() + avatarSearchImageView.getMeasuredHeight()) / 2
            );
        }

        int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE || child == titleTextView[0] || child == titleTextView[1] || child == additionalSubTitleOverlayContainer || child == subtitleTextView || child == menu || child == backButtonImageView || child == additionalSubtitleTextView || child == avatarSearchImageView) {
                continue;
            }

            LayoutParams lp = (LayoutParams) child.getLayoutParams();

            int width = child.getMeasuredWidth();
            int height = child.getMeasuredHeight();
            int childLeft;
            int childTop;

            int gravity = lp.gravity;
            if (gravity == -1) {
                gravity = Gravity.TOP | Gravity.LEFT;
            }

            final int absoluteGravity = gravity & Gravity.HORIZONTAL_GRAVITY_MASK;
            final int verticalGravity = gravity & Gravity.VERTICAL_GRAVITY_MASK;

            switch (absoluteGravity & Gravity.HORIZONTAL_GRAVITY_MASK) {
                case Gravity.CENTER_HORIZONTAL:
                    childLeft = (getMeasuredWidth() - width) / 2 + lp.leftMargin - lp.rightMargin;
                    break;
                case Gravity.RIGHT:
                    childLeft = getMeasuredWidth() - width - lp.rightMargin;
                    break;
                case Gravity.LEFT:
                default:
                    childLeft = lp.leftMargin;
            }

            switch (verticalGravity) {
                case Gravity.CENTER_VERTICAL:
                    childTop = (bottom - top - height) / 2 + lp.topMargin - lp.bottomMargin;
                    break;
                case Gravity.BOTTOM:
                    childTop = (bottom - top) - height - lp.bottomMargin;
                    break;
                default:
                    childTop = lp.topMargin;
            }
            child.layout(childLeft, childTop, childLeft + width, childTop + height);
        }
    }

    public void onMenuButtonPressed() {
        if (isActionModeShowed()) {
            return;
        }
        if (menu != null) {
            menu.onMenuButtonPressed();
        }
    }

    public void onResume() {
        resumed = true;
        updateAttachState();
    }

    protected void onPause() {
        resumed = false;
        updateAttachState();
        if (menu != null) {
            menu.hideAllPopupMenus();
        }
    }

    public void setAllowOverlayTitle(boolean value) {
        allowOverlayTitle = value;
    }

    public void setTitleActionRunnable(Runnable action) {
        lastRunnable = titleActionRunnable = action;
    }

    boolean overlayTitleAnimationInProgress;

    public void setTitleOverlayText(String title, int titleId, Runnable action) {
        if (!allowOverlayTitle || parentFragment.parentLayout == null) {
            return;
        }
        overlayTitleToSet[0] = title;
        overlayTitleToSet[1] = titleId;
        overlayTitleToSet[2] = action;
        if (overlayTitleAnimationInProgress) {
            return;
        }
        if (lastOverlayTitle == null && title == null || (lastOverlayTitle != null && lastOverlayTitle.equals(title))) {
            return;
        }
        lastOverlayTitle = title;

        if (additionalSubTitleOverlayContainer != null) {
            final CharSequence textToSet;
            if (titleId == R.string.ConnectingToProxyWithDots) {
                textToSet = AndroidUtilities.replaceArrows(getString(R.string.TitleSetupProxy), true, dp(8f / 3f), dp(2));
            } else {
                textToSet = null;
            }
            additionalSubTitleOverlayContainer.setText(textToSet, true);
        }


        CharSequence textToSet = title != null ? LocaleController.getString(title, titleId) : lastTitle;
        Drawable rightDrawableToSet = title != null ? null : lastRightDrawable;
        boolean ellipsize = false;
        if (title != null) {
            int index = TextUtils.indexOf(textToSet, "...");
            if (index >= 0) {
                SpannableString spannableString = SpannableString.valueOf(textToSet);
                ellipsizeSpanAnimator.wrap(spannableString, index);
                textToSet = spannableString;
                ellipsize = true;
            }
        }
        titleOverlayShown = title != null;
        if ((textToSet != null && titleTextView[0] == null) || getMeasuredWidth() == 0 || (titleTextView[0] != null && titleTextView[0].getVisibility() != View.VISIBLE)) {
            createTitleTextView(0);
            if (supportsHolidayImage) {
                titleTextView[0].invalidate();
                invalidate();
            }
            titleTextView[0].setText(textToSet);
            titleTextView[0].setDrawablePadding(dp(4));
            titleTextView[0].setRightDrawable(rightDrawableToSet);
            titleTextView[0].setRightDrawableOnClick(rightDrawableOnClickListener);
            if (rightDrawableToSet instanceof AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable) {
                ((AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable) rightDrawableToSet).setParentView(titleTextView[0]);
            }
            if (ellipsize) {
                ellipsizeSpanAnimator.addView(titleTextView[0]);
            } else {
                ellipsizeSpanAnimator.removeView(titleTextView[0]);
            }
        } else if (titleTextView[0] != null) {
            titleTextView[0].animate().cancel();
            if (titleTextView[1] != null) {
                titleTextView[1].animate().cancel();
            }
            if (titleTextView[1] == null) {
                createTitleTextView(1);
            }
            titleTextView[1].setText(textToSet);
            titleTextView[1].setDrawablePadding(dp(4));
            titleTextView[1].setRightDrawable(rightDrawableToSet);
            titleTextView[1].setRightDrawableOnClick(rightDrawableOnClickListener);
            if (rightDrawableToSet instanceof AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable) {
                ((AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable) rightDrawableToSet).setParentView(titleTextView[1]);
            }
            if (ellipsize) {
                ellipsizeSpanAnimator.addView(titleTextView[1]);
            }
            overlayTitleAnimationInProgress = true;
            SimpleTextView tmp = titleTextView[1];
            titleTextView[1] = titleTextView[0];
            titleTextView[0] = tmp;
            titleTextView[0].setAlpha(0);
            titleTextView[0].setTranslationY(-dp(20));
            titleTextView[0].animate()
                    .alpha(adaptiveBackgroundHideTitle ? 1.0f - onTopAnimated : 1f)
                    .translationY(0)
                    .setDuration(220).start();
            ViewPropertyAnimator animator = titleTextView[1].animate()
                    .alpha(0);
            if (subtitleTextView == null) {
                animator.translationY(dp(20));
            } else {
                animator.scaleY(0.7f).scaleX(0.7f);
            }
            requestLayout();
            centerScale = true;
            animator.setDuration(220).setListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    if (titleTextView[1] != null && titleTextView[1].getParent() != null) {
                        ViewGroup viewGroup = (ViewGroup) titleTextView[1].getParent();
                        viewGroup.removeView(titleTextView[1]);
                    }
                    ellipsizeSpanAnimator.removeView(titleTextView[1]);
                    titleTextView[1] = null;
                    overlayTitleAnimationInProgress = false;
                    setTitleOverlayText((String) overlayTitleToSet[0], (int) overlayTitleToSet[1], (Runnable) overlayTitleToSet[2]);
                }
            }).start();
        }
        titleActionRunnable = action != null ? action : lastRunnable;
    }

    public boolean isSearchFieldVisible() {
        return isSearchFieldVisible;
    }

    public void setOccupyStatusBar(boolean value) {
        occupyStatusBar = value;
        if (actionMode != null) {
            actionMode.setPadding(0, occupyStatusBar ? AndroidUtilities.statusBarHeight : 0, 0, 0);
        }
    }

    public boolean getOccupyStatusBar() {
        return occupyStatusBar;
    }

    public void setItemsBackgroundColor(int color, boolean isActionMode) {
        if (isActionMode) {
            itemsActionModeBackgroundColor = color;
            if (actionModeVisible) {
                if (backButtonImageView != null) {
                    backButtonImageView.setBackgroundDrawable(Theme.createSelectorDrawable(itemsActionModeBackgroundColor));
                }
            }
            if (actionMode != null) {
                actionMode.updateItemsBackgroundColor();
            }
        } else {
            itemsBackgroundColor = color;
            if (backButtonImageView != null) {
                backButtonImageView.setBackgroundDrawable(Theme.createSelectorDrawable(itemsBackgroundColor));
            }
            if (menu != null) {
                menu.updateItemsBackgroundColor();
            }
        }
    }

    public void setItemsColor(int color, boolean isActionMode) {
        if (isActionMode) {
            itemsActionModeColor = color;
            if (actionMode != null) {
                actionMode.updateItemsColor();
            }
            if (backButtonImageView != null) {
                Drawable drawable = backButtonImageView.getDrawable();
                if (drawable instanceof BackDrawable) {
                    ((BackDrawable) drawable).setRotatedColor(color);
                } else if (drawable instanceof BitmapDrawable || drawable instanceof VectorDrawable) {
                    backButtonImageView.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
                }
            }
        } else {
            solegramRawItemsColor = color;
            color = solegramReadable(color);
            itemsColor = color;
            if (solegramTonal) {
                if (glassDrawableMenu != null) {
                    glassDrawableMenu.updateColors();
                }
                if (glassDrawableBack != null) {
                    glassDrawableBack.updateColors();
                }
                solegramInvalidateBubbles();
            }
            if (backButtonImageView != null) {
                if (itemsColor != 0) {
                    Drawable drawable = backButtonImageView.getDrawable();
                    if (drawable instanceof BackDrawable) {
                        ((BackDrawable) drawable).setColor(color);
                    } else if (drawable instanceof MenuDrawable) {
                        ((MenuDrawable) drawable).setIconColor(color);
                    } else if (drawable instanceof BitmapDrawable || drawable instanceof VectorDrawable) {
                        backButtonImageView.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
                    }
                }
            }
            if (menu != null) {
                menu.updateItemsColor();
            }
        }

        if (backButtonImageView != null && mAlwaysApplyColorFilterToBackButton) {
            backButtonImageView.setColorFilter(new PorterDuffColorFilter(itemsColor, PorterDuff.Mode.SRC_IN));
        }
    }

    public void setCastShadows(boolean value) {
        if (castShadows != value && getParent() instanceof View) {
            ((View) getParent()).invalidate();
            invalidate();
        }
        castShadows = value;
    }

    public void setShadowAlpha(int alpha) {
        if (this.shadowAlpha == alpha) return;
        if (getParent() instanceof View) {
            ((View) getParent()).invalidate();
            invalidate();
        }
        this.shadowAlpha = alpha;
    }

    public int getShadowAlpha() {
        return shadowAlpha;
    }

    public boolean getCastShadows() {
        return castShadows && !solegramFloating; // a floating header has no edge to cast a shadow from
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (chatAvatarContainer != null && glassMode) {
            if (ev.getAction() == MotionEvent.ACTION_DOWN) {
                final int x = (int) ev.getX();
                final int y = (int) ev.getY();
                View child = findChildUnder(this, x, y, chatAvatarContainer);
                if (child == null) {
                    child = findChildUnder(this, x, y, null);
                }

                boolean contains = false;
                contains |= glassDrawable != null && glassDrawable.getBounds().contains(x, y);
                if (child != null && child != chatAvatarContainer) {
                    contains |= glassDrawableBack != null && glassDrawableBack.getBounds().contains(x, y);
                    contains |= glassDrawableMenu != null && glassDrawableMenu.getBounds().contains(x, y);
                }

                if (!contains) {
                    return false;
                }
            }
        }

        return super.dispatchTouchEvent(ev);
    }

    public static View findChildUnder(ViewGroup parent, float x, float y, View exclude) {
        for (int i = parent.getChildCount() - 1; i >= 0; i--) {
            View child = parent.getChildAt(i);

            if (child.getVisibility() != View.VISIBLE || child == exclude) continue;

            if (x >= child.getX() && x <= (child.getX() + child.getWidth())
                    && y >= child.getTop() && y <= child.getBottom()) {
                return child;
            }
        }
        return null;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (forceSkipTouches) {
            return false;
        }
        return super.onTouchEvent(event) || interceptTouches;
    }

    public static int getCurrentActionBarHeight() {
        if (AndroidUtilities.displaySize.x > AndroidUtilities.displaySize.y) {
            return dp(48);
        } else {
            return dp(56);
        }
    }

    public void setTitleAnimated(CharSequence title, boolean fromBottom, long duration) {
        setTitleAnimated(title, fromBottom, duration, null);
    }

    public void setTitleAnimated(CharSequence title, boolean fromBottom, long duration, Interpolator interpolator) {
        if (titleTextView[0] == null || title == null) {
            setTitle(title);
            return;
        }
        boolean crossfade = overlayTitleAnimation && !TextUtils.isEmpty(subtitle);
        if (crossfade) {
            if (subtitleTextView.getVisibility() != View.VISIBLE) {
                subtitleTextView.setVisibility(View.VISIBLE);
                subtitleTextView.setAlpha(0);
            }
            subtitleTextView.animate().alpha(fromBottom ? 0 : 1f).setDuration(220).start();
        }
        if (titleTextView[1] != null) {
            if (titleTextView[1].getParent() != null) {
                ViewGroup viewGroup = (ViewGroup) titleTextView[1].getParent();
                viewGroup.removeView(titleTextView[1]);
            }
            titleTextView[1] = null;
        }
        titleTextView[1] = titleTextView[0];
        titleTextView[0] = null;
        setTitle(title);
        this.fromBottom = fromBottom;
        titleTextView[0].setAlpha(0);
        if (!crossfade) {
            titleTextView[0].setTranslationY(fromBottom ? dp(20) : -dp(20));
        }
        ViewPropertyAnimator a1 = titleTextView[0].animate().alpha(1f).translationY(0).setDuration(duration);
        if (interpolator != null) {
            a1.setInterpolator(interpolator);
        }
        a1.start();

        titleAnimationRunning = true;
        ViewPropertyAnimator a = titleTextView[1].animate().alpha(0);
        if (!crossfade) {
            a.translationY(fromBottom ? -dp(20) : dp(20));
        }
        if (interpolator != null) {
            a.setInterpolator(interpolator);
        }
        a.setDuration(duration).setListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (titleTextView[1] != null && titleTextView[1].getParent() != null) {
                    ViewGroup viewGroup = (ViewGroup) titleTextView[1].getParent();
                    viewGroup.removeView(titleTextView[1]);
                }
                titleTextView[1] = null;
                titleAnimationRunning = false;

                if (crossfade && fromBottom) {
                    subtitleTextView.setVisibility(View.GONE);
                }

                requestLayout();
            }
        }).start();
        requestLayout();
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        updateAttachState();
        if (actionModeVisible) {
            final int color = actionModeColor == 0 ? actionBarColor : actionModeColor;
            if (color == 0 || glassMode) {
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.needCheckSystemBarColors);
            } else if (ColorUtils.calculateLuminance(color) < 0.7f) {
                AndroidUtilities.setLightStatusBar((Activity) getContext(), false);
            } else {
                AndroidUtilities.setLightStatusBar((Activity) getContext(), true);
            }
        }
        if (lastRightDrawable instanceof AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable) {
            ((AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable) lastRightDrawable).setParentView(titleTextView[0]);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        attached = false;
        updateAttachState();
        if (actionModeVisible) {
            if (actionBarColor == 0 || actionModeColor == 0 || glassMode || solegramFloating) {
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.needCheckSystemBarColors);
            } else {
                if (ColorUtils.calculateLuminance(actionBarColor) < 0.7f) {
                    AndroidUtilities.setLightStatusBar((Activity) getContext(), false);
                } else {
                    AndroidUtilities.setLightStatusBar((Activity) getContext(), true);
                }
            }
        }
        if (lastRightDrawable instanceof AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable) {
            ((AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable) lastRightDrawable).setParentView(null);
        }
    }

    private void updateAttachState() {
        boolean attachState = attached && resumed;
        if (this.attachState != attachState) {
            this.attachState = attachState;
            if (attachState) {
                ellipsizeSpanAnimator.onAttachedToWindow();
            } else {
                ellipsizeSpanAnimator.onDetachedFromWindow();
            }
        }
    }

    public ActionBarMenu getActionMode() {
        return actionMode;
    }

    public void setOverlayTitleAnimation(boolean ovelayTitleAnimation) {
        this.overlayTitleAnimation = ovelayTitleAnimation;
    }

    public void beginDelayedTransition() {
        if (!LocaleController.isRTL) {
            TransitionSet transitionSet = new TransitionSet();
            transitionSet.setOrdering(TransitionSet.ORDERING_TOGETHER);
            transitionSet.addTransition(new Fade());
            transitionSet.addTransition(new ChangeBounds() {


                public void captureStartValues(TransitionValues transitionValues) {
                    super.captureStartValues(transitionValues);
                    if (transitionValues.view instanceof SimpleTextView) {
                        float textSize = ((SimpleTextView) transitionValues.view).getTextPaint().getTextSize();
                        transitionValues.values.put("text_size", textSize);
                    }
                }

                public void captureEndValues(TransitionValues transitionValues) {
                    super.captureEndValues(transitionValues);
                    if (transitionValues.view instanceof SimpleTextView) {
                        float textSize= ((SimpleTextView) transitionValues.view).getTextPaint().getTextSize();
                        transitionValues.values.put("text_size", textSize);
                    }
                }

                @Override
                public Animator createAnimator(ViewGroup sceneRoot, TransitionValues startValues, TransitionValues endValues) {
                    if (startValues != null && startValues.view instanceof SimpleTextView) {
                        AnimatorSet animatorSet = new AnimatorSet();
                        if (startValues != null && endValues != null) {
                            Animator animator = super.createAnimator(sceneRoot, startValues, endValues);
                            float s = (float) startValues.values.get("text_size") / (float) endValues.values.get("text_size");
                            startValues.view.setScaleX(s);
                            startValues.view.setScaleY(s);
                            if (animator != null) {
                                animatorSet.playTogether(animator);
                            }
                        }
                        animatorSet.playTogether(ObjectAnimator.ofFloat(startValues.view, SCALE_X, 1f));
                        animatorSet.playTogether(ObjectAnimator.ofFloat(startValues.view, SCALE_Y, 1f));
                        animatorSet.addListener(new AnimatorListenerAdapter() {

                            @Override
                            public void onAnimationStart(Animator animation) {
                                super.onAnimationStart(animation);
                                startValues.view.setLayerType(LAYER_TYPE_HARDWARE, null);
                            }

                            @Override
                            public void onAnimationEnd(Animator animation) {
                                super.onAnimationEnd(animation);
                                startValues.view.setLayerType(LAYER_TYPE_NONE, null);
                            }
                        });
                        return animatorSet;
                    } else {
                        return super.createAnimator(sceneRoot, startValues, endValues);
                    }
                }
            });
            centerScale = false;
            transitionSet.setDuration(220);
            transitionSet.setInterpolator(CubicBezierInterpolator.DEFAULT);
            TransitionManager.beginDelayedTransition(this, transitionSet);
        }
    }

    private int getThemedColor(int key) {
        return Theme.getColor(key, resourcesProvider);
    }

    public void setDrawBlurBackground(SizeNotifierFrameLayout contentView) {
        blurredBackground = true;
        this.contentView = contentView;
        contentView.blurBehindViews.add(this);
        setBackground(null);
    }

    private boolean doNotDrawChild;

    public void setSkipDrawChild(boolean skip) {
        if (doNotDrawChild != skip) {
            doNotDrawChild = skip;
            invalidate();
        }
    }

    private float searchFactor;

    public void setSearchFactor(float factor) {
        if (searchFactor != factor) {
            searchFactor = factor;
            invalidate();
        }
    }

    public void checkAvatarContainerWidth(boolean animated) {
        if (chatAvatarContainer == null) {
            return;
        }

        final boolean hasAvatar = chatAvatarContainer.hasVisibleAvatar();
        int visualWidth = chatAvatarContainer.getVisualWidth();
        if (hasAvatar) {
            //visualWidth = Math.max(visualWidth, dp(168));
        }

        final int width = Math.min(getMeasuredWidth() - dp(6 + 46 + 6 + 6 + 46 + 6), visualWidth);
        if (animated) {
            if (animatorAvatarContainerWidth.getToFactor() != width) {
                animatorAvatarContainerWidth.animateTo(width);
            }
        } else {
            animatorAvatarContainerWidth.forceFactor(width);
        }
        animatorAvatarContainerHasAvatar.setValue(hasAvatar, animated);
    }

    private final FactorAnimator animatorAvatarContainerWidth = new FactorAnimator(0, this, CubicBezierInterpolator.EASE_OUT_QUINT, 380);
    private final BoolAnimator animatorAvatarContainerHasAvatar = new BoolAnimator(0, this, CubicBezierInterpolator.EASE_OUT_QUINT, 380);

    private final FactorAnimator animatorMenuItemsWidth = new FactorAnimator(0, this, CubicBezierInterpolator.EASE_OUT_QUINT, 320);
    private final BoolAnimator animatorHasMenuItems = new BoolAnimator(0, this, CubicBezierInterpolator.EASE_OUT_QUINT, 320);

    @Override
    public void onFactorChanged(int id, float factor, float fraction, FactorAnimator callee) {
        solegramInvalidateBubbles();
    }

    private int forcedMenuWidth;
    private int forcedMenuMinWidth;
    private boolean hasForcedMenuWidth;
    private boolean hasForcedMenuMinWidth;

    public void setForcedMenuWidth(int width) {
        hasForcedMenuWidth = true;
        if (forcedMenuWidth != width) {
            forcedMenuWidth = width;
            invalidate();
        }
    }

    public void setForcedMenuMinWidth(int width) {
        hasForcedMenuMinWidth = true;
        if (forcedMenuMinWidth != width) {
            forcedMenuMinWidth = width;
            invalidate();
        }
    }

    private boolean isAnimationsAllowed;

    public void checkMenuItemsWidth() {
        final int defaultMenuWidth;
        if (solegramButtonsGlass) {
            // the bubble hugs all visible buttons, plugin ones included
            defaultMenuWidth = solegramMenuExtent(solegramExtent) ? Math.max(0, (int) (solegramExtent[1] - solegramExtent[0]) - dp(SOLEGRAM_BUBBLE_INSET * 2)) : 0;
        } else {
            defaultMenuWidth = Math.max(0, menu != null ? (int) menu.getItemsWidth() - dp(1) - dp(1) : 0);
        }
        final int actionMenuWidth = Math.max(0, actionMode != null ? actionMode.getItemsWidth() - dp(1) - dp(1) : 0);
        final int searchMenuWidth = dp(46);
        // Solegram: the bubble of a plain header keeps the width of its own buttons while the selection bar takes over
        final int width = /*isSearchFieldVisible ? searchMenuWidth :*/ (actionModeVisible && !solegramButtonsGlass ? actionMenuWidth : defaultMenuWidth);

        animatorHasMenuItems.setValue(width > 0, isAnimationsAllowed);
        if (animatorMenuItemsWidth.getToFactor() != width) {
            if (isAnimationsAllowed) {
                animatorMenuItemsWidth.animateTo(width);
            } else {
                animatorMenuItemsWidth.forceFactor(width);
            }
        }
    }

    public boolean doNotDrawGlassMenu;
    private boolean drawGlassTitle = true;

    public void setDrawGlassTitle(boolean draw) {
        if (drawGlassTitle != draw) {
            drawGlassTitle = draw;
            invalidate();
        }
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        final int p = dp(6);
        final int s = dp(46);

        final float actionModeFactor = getActionModeFactor();
        final int menuWidthA = hasForcedMenuWidth ? forcedMenuWidth : (int) animatorMenuItemsWidth.getFactor();
        final int menuWidth = hasForcedMenuMinWidth ? Math.max((int) (forcedMenuMinWidth * (1f - searchFactor)), menuWidthA) : menuWidthA;

        final boolean hasBackButton = backButtonImageView != null && backButtonImageView.getVisibility() == View.VISIBLE;

        // Solegram: the bubbles of an ordinary header stay on its row of buttons, also when the bar is taller (tabs under it)
        final int t = solegramButtonsGlass
            ? (occupyStatusBar ? AndroidUtilities.statusBarHeight : 0) + (getCurrentActionBarHeight() - s) / 2 - p
            : getHeight() - (getCurrentActionBarHeight() + s) / 2 - p;
        final int b = t + s + p * 2;

        if (glassDrawable != null && !glassOnlyBack && drawGlassTitle) {
            final int menuWidthWithPadding = menuWidth + ((hasForcedMenuWidth || hasForcedMenuMinWidth) ? (menuWidth > 0 ? p : 0) : (int) (p * animatorHasMenuItems.getFloatValue()));
            final int rightOffset = lerp(menuWidthWithPadding, Math.max(menuWidthWithPadding, p + s), chatAvatarContainer == null ? 0f : 1f - animatorAvatarContainerHasAvatar.getFloatValue());

            final int leftDefault = lerp(hasBackButton ? s + p : 0, s + p, chatAvatarContainer == null? 0f : 1f - animatorAvatarContainerHasAvatar.getFloatValue());
            final int rightDefault = getWidth() - rightOffset;
            final int widthDefault = rightDefault - leftDefault;
            final int left, right;
            if (chatAvatarContainer != null) {
                final int width = lerp(Math.min(widthDefault, (int) animatorAvatarContainerWidth.getFactor() + p * 2), widthDefault, Math.max(searchFactor, actionModeFactor));
                left = (rightDefault + leftDefault - width) / 2;
                right = left + width;

                final float translationX = left
                    - ((MarginLayoutParams)(chatAvatarContainer.getLayoutParams())).leftMargin
                    - chatAvatarContainer.getLeftPadding()
                    + p + dp(3);
                chatAvatarContainer.setTranslationX(translationX);
                chatAvatarContainer.setPivotX((chatAvatarContainer.getMeasuredWidth()) / 2f - translationX );
            } else {
                left = leftDefault;
                right = rightDefault;
            }

            glassDrawable.setBounds(left, t, right, b);
            glassDrawable.draw(canvas);
        }
        // Solegram: the button bubbles of a plain header give way to the selection bar and the search field
        if (glassDrawableBack != null && hasBackButton && !solegramButtonsGlass) {
            glassDrawableBack.setBounds(0, t, s + p * 2, b);
            final int fluid = canvas.save();
            com.solegram.messenger.ui.SolegramFluidTouch.applyTo(canvas, backButtonImageView); // fluid touch: the bubble follows its button
            glassDrawableBack.draw(canvas);
            canvas.restoreToCount(fluid);
        }
        if (glassDrawableMenu != null && menuWidth > 0 && !glassOnlyBack && !doNotDrawGlassMenu && !solegramButtonsGlass) {
            {
                glassDrawableMenu.setBounds(getWidth() - Math.max(s, menuWidth) - p * 2, t, getWidth(), b);
            }
            glassDrawableMenu.setAlpha(hasForcedMenuWidth ? 255 : (int) (255 * animatorHasMenuItems.getFloatValue()));
            final int fluid = canvas.save();
            if (menu != null) {
                for (int i = 0, n = menu.getChildCount(); i < n; i++) {
                    final View child = menu.getChildAt(i);
                    // the menu's buttons are in the menu's frame: move the canvas there first
                    canvas.translate(menu.getX(), menu.getY());
                    if (com.solegram.messenger.ui.SolegramFluidTouch.applyTo(canvas, child)) {
                        canvas.translate(-menu.getX(), -menu.getY());
                        break;
                    }
                    canvas.translate(-menu.getX(), -menu.getY());
                }
            }
            glassDrawableMenu.draw(canvas);
            canvas.restoreToCount(fluid);
        }

        if (blurredBackground && actionBarColor != Color.TRANSPARENT) {
            rectTmp.set(0, 0, getMeasuredWidth(), getMeasuredHeight());
            blurScrimPaint.setColor(actionBarColor);
            if (adaptiveBackground) {
                contentView.drawBlurRect(canvas, getY(), rectTmp, blurScrimPaint, true, 1.0f - onTopAnimated);
            } else {
                contentView.drawBlurRect(canvas, getY(), rectTmp, blurScrimPaint, true);
            }
        }

        // Solegram: the back button's bubble, over the header's own panel; it gives way to the selection bar and the search field
        if (solegramButtonsGlass && glassDrawableBack != null && hasBackButton) {
            final float alpha = (1f - solegramActionModeFactor()) * (1f - searchFieldVisibleAlpha) * backButtonImageView.getAlpha();
            if (alpha > 0) {
                glassDrawableBack.setBounds(0, t, s + p * 2, b);
                if (solegramManualGlassOffset) {
                    glassDrawableBack.setSourceOffset(getX(), getY());
                }
                final int alpha255 = (int) (255 * alpha);
                if (glassDrawableBack.getAlpha() != alpha255) {
                    glassDrawableBack.setAlpha(alpha255);
                }
                // fluid touch: the bubble goes and stretches with its button
                final int fluid = canvas.save();
                com.solegram.messenger.ui.SolegramFluidTouch.applyTo(canvas, backButtonImageView);
                glassDrawableBack.draw(canvas);
                canvas.restoreToCount(fluid);
            }
        }

        isAnimationsAllowed = true;
        if (doNotDrawChild) {
            return;
        }

        super.dispatchDraw(canvas);
    }

    public void setForceSkipTouches(boolean forceSkipTouches) {
        this.forceSkipTouches = forceSkipTouches;
    }

    public void setDrawBackButton(boolean b) {
        this.drawBackButton = b;
        if (backButtonImageView != null) {
            backButtonImageView.invalidate();
        }
    }

    public void setUseContainerForTitles() {
        this.useContainerForTitles = true;
        if (titlesContainer == null) {
            titlesContainer = new FrameLayout(getContext()) {
                @Override
                protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                    setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec));
                }

                @Override
                protected void onLayout(boolean changed, int left, int top, int right, int bottom) {

                }
            };
            addView(titlesContainer);
        }
    }

    public FrameLayout getTitlesContainer() {
        return titlesContainer;
    }

    @Override
    public void updateColors() {
        adaptive_updateColor();
        if (glassDrawable != null) {
            glassDrawable.updateColors();
        }
        if (glassDrawableMenu != null) {
            glassDrawableMenu.updateColors();
        }
        if (glassDrawableBack != null) {
            glassDrawableBack.updateColors();
        }
        if (solegramButtonsGlass) {
            solegramInvalidateBubbles();
        }
        if (additionalSubTitleOverlayContainer != null) {
            additionalSubTitleOverlayContainer.updateColors();
        }
    }

    private ActionBarAnimatedSubtitleOverlayContainer additionalSubTitleOverlayContainer;
    public FrameLayout createAdditionalSubTitleOverlayContainer() {
        if (additionalSubTitleOverlayContainer == null) {
            additionalSubTitleOverlayContainer = new ActionBarAnimatedSubtitleOverlayContainer(getContext(), resourcesProvider, ellipsizeSpanAnimator) {
                @Override
                public void onItemChanged(ReplaceAnimator<?> animator) {
                    super.onItemChanged(animator);
                    final float overlayVisibility = getTotalVisibility();
                    if (titlesContainer != null) {
                        titlesContainer.setTranslationY(overlayVisibility * dp(-11));
                    }
                }
            };
            additionalSubTitleOverlayContainer.setClipChildren(false);
            addView(additionalSubTitleOverlayContainer);
        }
        return additionalSubTitleOverlayContainer;
    }
    public FrameLayout getAdditionalSubTitleOverlayContainer() {
        return additionalSubTitleOverlayContainer;
    }

    private boolean adaptiveBackground, adaptiveBackgroundHideTitle;
    private int adaptive_topColorKey;
    private int adaptive_lowerColorKey;
    private boolean onTop = true;
    private float onTopAnimated = 1.0f;
    private ValueAnimator adaptive_animator;
    public void setAdaptiveBackground(RecyclerView list) {
        setAdaptiveBackground(list, false, Theme.key_windowBackgroundGray, Theme.key_actionBarDefault);
    }
    public void setAdaptiveBackground(RecyclerView list, boolean hideTitle) {
        setAdaptiveBackground(list, hideTitle, Theme.key_windowBackgroundGray, Theme.key_actionBarDefault);
    }
    public void setAdaptiveBackground(RecyclerView list, boolean hideTitle, final int topColorKey, final int lowerColorKey) {
        this.adaptive_topColorKey = topColorKey;
        this.adaptive_lowerColorKey = lowerColorKey;
        final Runnable checkScroll = () -> {
            final boolean onTop = !list.canScrollVertically(-1);
            if (ActionBar.this.onTop == onTop) return;
            if (adaptive_animator != null)
                adaptive_animator.cancel();
            adaptive_animator = ValueAnimator.ofFloat(onTopAnimated, (ActionBar.this.onTop = onTop) ? 1.0f : 0.0f);
            adaptive_animator.addUpdateListener(anm -> {
                onTopAnimated = (float) anm.getAnimatedValue();
                adaptive_updateColor();
            });
            adaptive_animator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    onTopAnimated = onTop ? 1.0f : 0.0f;
                    adaptive_updateColor();
                }
            });
            adaptive_animator.setDuration(320);
            adaptive_animator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
            adaptive_animator.start();
        };
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                checkScroll.run();
            }
        });
        this.adaptiveBackgroundHideTitle = hideTitle;
        if (this.adaptiveBackground) {
            checkScroll.run();
        } else {
            this.adaptiveBackground = true;
            this.onTopAnimated = (this.onTop = !list.canScrollVertically(-1)) ? 1 : 0;
            adaptive_updateColor();
        }
    }
    public void setAdaptiveBackground(SectionsScrollView list) {
        setAdaptiveBackground(list, Theme.key_windowBackgroundGray, Theme.key_actionBarDefault);
    }
    public void setAdaptiveBackground(SectionsScrollView list, final int topColorKey, final int lowerColorKey) {
        this.adaptive_topColorKey = topColorKey;
        this.adaptive_lowerColorKey = lowerColorKey;
        adaptive_updateColor();
        final Runnable checkScroll = () -> {
            final boolean onTop = !list.canScrollVertically(-1);
            if (ActionBar.this.onTop == onTop) return;
            if (adaptive_animator != null)
                adaptive_animator.cancel();
            adaptive_animator = ValueAnimator.ofFloat(onTopAnimated, (ActionBar.this.onTop = onTop) ? 1.0f : 0.0f);
            adaptive_animator.addUpdateListener(anm -> {
                onTopAnimated = (float) anm.getAnimatedValue();
                adaptive_updateColor();
            });
            adaptive_animator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    onTopAnimated = onTop ? 1.0f : 0.0f;
                    adaptive_updateColor();
                }
            });
            adaptive_animator.setDuration(320);
            adaptive_animator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
            adaptive_animator.start();
        };
        list.onScroll(checkScroll);
        if (this.adaptiveBackground) {
            checkScroll.run();
        } else {
            this.adaptiveBackground = true;
            this.onTopAnimated = (this.onTop = !list.canScrollVertically(-1)) ? 1 : 0;
            adaptive_updateColor();
        }
    }
    private void adaptive_updateColor() {
        if (!adaptiveBackground) return;
        if (adaptiveBackgroundHideTitle) {
            if (titlesContainer != null) {
                titlesContainer.setAlpha(1.0f - onTopAnimated);
            } else if (titleTextView[0] != null) {
                titleTextView[0].setAlpha(1.0f - onTopAnimated);
            }
        }

        final float factor = onTopAnimated;
        int lowerColor = adaptive_lowerColorKey == -1 ? 0 : Theme.getColor(adaptive_lowerColorKey, resourcesProvider);
        int topColor = adaptive_topColorKey == -1 ? 0 : Theme.getColor(adaptive_topColorKey, resourcesProvider);

        if (topColor == 0) {
            topColor = ColorUtils.setAlphaComponent(lowerColor, 0);
        }
        if (lowerColor == 0) {
            lowerColor = ColorUtils.setAlphaComponent(topColor, 0);
        }

        setBackgroundColor(ColorUtils.blendARGB(lowerColor, topColor, factor));
        setShadowAlpha((int) ((1.0f - onTopAnimated) * 0xFF));
        if (blurredBackground) {
            invalidate();
        }
    }
}
