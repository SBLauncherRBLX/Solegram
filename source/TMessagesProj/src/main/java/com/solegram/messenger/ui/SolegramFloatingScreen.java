/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.FrameLayout;
import android.widget.ScrollView;

import androidx.core.graphics.ColorUtils;
import androidx.core.widget.NestedScrollView;
import androidx.recyclerview.widget.RecyclerView;

import com.solegram.messenger.SolegramGlass;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LiteMode;
import org.telegram.messenger.utils.RenderNodeEffects;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory;
import org.telegram.ui.Components.blur3.RenderNodeWithHash;
import org.telegram.ui.Components.blur3.capture.IBlur3Hash;
import org.telegram.ui.Components.blur3.drawable.color.impl.BlurredBackgroundProviderImpl;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceColor;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceRenderNode;
import org.telegram.ui.DialogsActivity;

/**
 * Solegram floating header for any ordinary screen (one whose header is laid out by the navigation container):
 * the screen is laid out under its header, its list starts below it and scrolls on under it, the content fades
 * into the background at the top and the header's buttons sit in a bubble of glass that sees the content.
 * Only screens made of one full-size list (with overlays like an empty view or bottom buttons) qualify; screens
 * with their own things at the top (search fields, tabs) keep their regular header.
 */
public class SolegramFloatingScreen {

    /**
     * Decides once per screen view whether it gets the floating header.
     *
     * @return the floating screen, or null when the screen keeps its regular header
     */
    public static SolegramFloatingScreen decide(BaseFragment fragment, ActionBar actionBar, View fragmentView) {
        if (fragment == null || actionBar == null || fragmentView == null || !SolegramFloatingHeader.enabled()) {
            return null;
        }
        if (fragment.solegramInPreviewMode() || fragment instanceof ChatActivity || fragment instanceof DialogsActivity || !fragment.solegramAllowsFloatingHeader()) {
            return null;
        }
        if (actionBar.solegramIsGlassMode() || actionBar.getVisibility() != View.VISIBLE || actionBar.getAlpha() < 1f || actionBar.solegramExtraHeight() > 0) {
            return null;
        }
        // a header of the regular colour; screens that made theirs clear or coloured have their own design
        if (opaqueColor(actionBar.getBackground()) == 0 || actionBar.getTranslationY() != 0f) {
            return null;
        }
        final java.util.ArrayList<View> overlays = new java.util.ArrayList<>();
        final View scrollRoot = findScrollRoot(fragmentView, overlays);
        if (scrollRoot == null) {
            return null;
        }
        return new SolegramFloatingScreen(fragment, actionBar, fragmentView, scrollRoot, overlays);
    }

    private static boolean isScroll(View view) {
        return view instanceof RecyclerView || view instanceof AbsListView || view instanceof ScrollView || view instanceof NestedScrollView;
    }

    /** A list somewhere in it (a search results page, a second list shown later). */
    private static boolean containsScroll(View view, int depth) {
        if (isScroll(view)) {
            return true;
        }
        if (depth <= 0 || !(view instanceof ViewGroup)) {
            return false;
        }
        final ViewGroup group = (ViewGroup) view;
        for (int i = 0, n = group.getChildCount(); i < n; i++) {
            if (containsScroll(group.getChildAt(i), depth - 1)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The one list that fills the screen; null when something else sits (or may later sit) at the top of it.
     *
     * @param overlays gets the full-height overlays laid out from the top (empty and progress views): they start
     *                 below the header, as the list's rows do
     */
    private static View findScrollRoot(View view, java.util.ArrayList<View> overlays) {
        if (isScroll(view)) {
            return view;
        }
        if (!(view instanceof FrameLayout)) {
            return null;
        }
        final ViewGroup group = (ViewGroup) view;
        View found = null;
        for (int i = 0, n = group.getChildCount(); i < n; i++) {
            final View child = group.getChildAt(i);
            final ViewGroup.LayoutParams params = child.getLayoutParams();
            if (params == null) {
                return null;
            }
            final boolean fills = params.width == ViewGroup.LayoutParams.MATCH_PARENT && params.height == ViewGroup.LayoutParams.MATCH_PARENT;
            final int topMargin = params instanceof ViewGroup.MarginLayoutParams ? ((ViewGroup.MarginLayoutParams) params).topMargin : 0;
            if (found == null && fills && isScroll(child) && topMargin == 0 && child.getVisibility() != View.GONE) {
                found = child;
                continue;
            }
            // hidden things count too: they may show up later, after the screen was laid out under its header
            if (params.height == ViewGroup.LayoutParams.MATCH_PARENT) {
                if (containsScroll(child, 3)) {
                    return null; // a second list would start under the header
                }
                // overlays: empty and progress views, fast scroll
                final int gravity = params instanceof FrameLayout.LayoutParams ? ((FrameLayout.LayoutParams) params).gravity : -1;
                final int vertical = gravity == -1 ? Gravity.TOP : gravity & Gravity.VERTICAL_GRAVITY_MASK;
                if (params instanceof ViewGroup.MarginLayoutParams && vertical != Gravity.CENTER_VERTICAL && vertical != Gravity.BOTTOM) {
                    overlays.add(child);
                }
                continue;
            }
            final int gravity = params instanceof FrameLayout.LayoutParams ? ((FrameLayout.LayoutParams) params).gravity : -1;
            final int vertical = gravity == -1 ? Gravity.TOP : gravity & Gravity.VERTICAL_GRAVITY_MASK;
            if (vertical == Gravity.BOTTOM || vertical == Gravity.CENTER_VERTICAL) {
                continue; // bottom buttons, centred hints
            }
            return null; // something of its own at the top
        }
        return found;
    }

    public final View fragmentView;
    private final ActionBar actionBar;
    private final View scrollRoot;
    private final Theme.ResourcesProvider resourcesProvider;
    private final SolegramFloatingHeader fade = new SolegramFloatingHeader();
    private final BlurredBackgroundSourceRenderNode source;
    private final BlurredBackgroundSourceColor colorSource;
    private int paddingBase, paddingApplied = -1;
    private int lastPageColor;
    // full-height overlays and the top margins they had of their own / were given
    private final View[] overlays;
    private final int[] overlayBase, overlayApplied;

    private SolegramFloatingScreen(BaseFragment fragment, ActionBar actionBar, View fragmentView, View scrollRoot, java.util.ArrayList<View> overlays) {
        this.actionBar = actionBar;
        this.fragmentView = fragmentView;
        this.scrollRoot = scrollRoot;
        this.overlays = overlays.toArray(new View[0]);
        this.overlayBase = new int[this.overlays.length];
        this.overlayApplied = new int[this.overlays.length];
        java.util.Arrays.fill(this.overlayApplied, -1);
        this.resourcesProvider = fragment.getResourceProvider();

        if (Build.VERSION.SDK_INT >= 31) {
            source = new BlurredBackgroundSourceRenderNode(null);
            source.setupRenderer(new RenderNodeWithHash.Renderer() {
                @Override
                public void renderNodeUpdateDisplayList(Canvas canvas) {
                    drawContent(canvas);
                }

                @Override
                public void renderNodeCalculateHash(IBlur3Hash hash) {
                    hash.add(lastPageColor);
                    hash.addF(fragmentView.getX());
                    hash.addF(fragmentView.getY());
                    hash.add(fragmentView.getWidth());
                    hash.add(fragmentView.getHeight());
                    hash.add(actionBar.getMeasuredHeight());
                    hash.addF(actionBar.getY());
                    if (!hashUnderGlass(hash)) {
                        hash.unsupported();
                    }
                }
            });
            // the same blur as the app's glass sources (see SolegramCallSheet)
            final boolean liquid = LiteMode.isEnabled(LiteMode.FLAG_LIQUID_GLASS);
            final float radius = AndroidUtilities.dpf2(liquid ? 9f : 40f) * SolegramGlass.blurScale();
            if (!liquid || SolegramGlass.vibrancy()) {
                source.setBlur(radius, RenderNodeEffects.getSaturationX3RenderEffect());
            } else {
                source.setBlur(radius);
            }
            colorSource = null;
        } else {
            source = null;
            colorSource = new BlurredBackgroundSourceColor();
        }
        final BlurredBackgroundDrawableViewFactory factory = new BlurredBackgroundDrawableViewFactory(source != null ? source : colorSource);
        factory.setLiquidGlassEffectAllowed(LiteMode.isEnabled(LiteMode.FLAG_LIQUID_GLASS));

        actionBar.solegramSetFloating(true);
        checkPageColor();
        actionBar.solegramSetupButtonsGlass(factory, BlurredBackgroundProviderImpl.topPanel(resourcesProvider));
        if (scrollRoot instanceof ViewGroup) {
            ((ViewGroup) scrollRoot).setClipToPadding(false);
        }
    }

    /** The page's own background colour, which the header's fade turns into (it follows theme changes). */
    public int pageColor() {
        int color = opaqueColor(scrollRoot.getBackground());
        if (color == 0) {
            color = opaqueColor(fragmentView.getBackground());
        }
        return color != 0 ? color : Theme.getColor(Theme.key_windowBackgroundGray, resourcesProvider);
    }

    /** After a theme change: the fade, the glass without a picture and the readable colours of the bar. */
    private void checkPageColor() {
        final int color = pageColor();
        if (color == lastPageColor) {
            return;
        }
        lastPageColor = color;
        if (colorSource != null) {
            colorSource.setColor(color);
        }
        int text = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider);
        if (Math.abs(ColorUtils.calculateLuminance(text | 0xFF000000) - ColorUtils.calculateLuminance(color | 0xFF000000)) < 0.3) {
            text = ColorUtils.calculateLuminance(color | 0xFF000000) > 0.5 ? 0xFF000000 : 0xFFFFFFFF;
        }
        actionBar.solegramSetPageColor(color, text);
    }

    private static int opaqueColor(Drawable drawable) {
        if (drawable instanceof ColorDrawable) {
            final int color = ((ColorDrawable) drawable).getColor();
            if (Color.alpha(color) == 255) {
                return color;
            }
        }
        return 0;
    }

    /** Before the screen is measured: its list starts below the header (also after the screen sets its own padding). */
    public void beforeMeasure(int headerHeight) {
        final int current = scrollRoot.getPaddingTop();
        if (current != paddingApplied) {
            paddingBase = current;
        }
        final int want = paddingBase + headerHeight;
        paddingApplied = want;
        if (current != want) {
            scrollRoot.setPadding(scrollRoot.getPaddingLeft(), want, scrollRoot.getPaddingRight(), scrollRoot.getPaddingBottom());
        }
        // empty and loading views fill the screen: under the header their sticker, text or shimmer rows were
        // hidden behind it or sat too high, so they start below it, as the list does
        for (int i = 0; i < overlays.length; i++) {
            final View overlay = overlays[i];
            if (overlay.getParent() != fragmentView || !(overlay.getLayoutParams() instanceof ViewGroup.MarginLayoutParams)) {
                continue;
            }
            final ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) overlay.getLayoutParams();
            if (params.topMargin != overlayApplied[i]) {
                overlayBase[i] = params.topMargin;
            }
            overlayApplied[i] = overlayBase[i] + headerHeight;
            if (params.topMargin != overlayApplied[i]) {
                params.topMargin = overlayApplied[i];
                overlay.setLayoutParams(params);
            }
        }
    }

    /** Over the screen, under its header: the fade, and the header's own panel while its search is open. */
    public void drawFade(Canvas canvas, int width) {
        checkPageColor();
        fade.draw(canvas, actionBar, width, lastPageColor, 1f);
        final int header = actionBar.solegramHeaderColor();
        // the search field is coloured for the header, so it gets the header's panel back
        fade.drawSolid(canvas, actionBar, width, Color.alpha(header) == 255 ? header : lastPageColor, actionBar.searchFieldVisibleAlpha);
    }

    /** Before every frame: the picture the header's glass shows. */
    public void updateGlass() {
        if (actionBar.getVisibility() != View.VISIBLE || !(fragmentView.getParent() instanceof View)) {
            return;
        }
        if (paddingApplied >= 0 && scrollRoot.getPaddingTop() != paddingApplied && !fragmentView.isLayoutRequested()) {
            fragmentView.requestLayout(); // the screen set its list's padding without a layout pass: put the header back in
        }
        if (source == null) {
            return;
        }
        final View parent = (View) fragmentView.getParent();
        if (parent.getWidth() <= 0 || parent.getHeight() <= 0) {
            return;
        }
        // only the part under the header (and a bit more for the lens) is ever looked at: a smaller layer to blur
        source.setSize(parent.getWidth(), Math.min(parent.getHeight(), glassHeight()));
        source.updateDisplayListIfNeeded();
    }

    private int glassHeight() {
        return (int) Math.ceil(Math.max(0, actionBar.getY()) + actionBar.getMeasuredHeight() + dp(48));
    }

    /**
     * What is under the glass now: the screen's views and the list's rows that reach up there. Only they are
     * looked at, so a spinner or an animated row further down does not make the glass blur everything again.
     *
     * @return false when one of them is being redrawn (the picture must be taken again)
     */
    private boolean hashUnderGlass(IBlur3Hash hash) {
        final float limit = glassHeight();
        final float top = fragmentView.getY();
        if (fragmentView == scrollRoot) {
            return hashRows(hash, (ViewGroup) scrollRoot, top, limit);
        }
        if (!(fragmentView instanceof ViewGroup)) {
            return !fragmentView.isDirty();
        }
        final ViewGroup group = (ViewGroup) fragmentView;
        hash.add(group.getChildCount());
        for (int i = 0, n = group.getChildCount(); i < n; i++) {
            final View child = group.getChildAt(i);
            hash.add(child.getVisibility());
            if (child.getVisibility() != View.VISIBLE || top + child.getY() >= limit) {
                continue;
            }
            hash.addF(child.getX());
            hash.addF(child.getY());
            hash.addF(child.getAlpha());
            hash.add(child.getWidth());
            hash.add(child.getHeight());
            if (child == scrollRoot && child instanceof ViewGroup) {
                if (!hashRows(hash, (ViewGroup) child, top + child.getY(), limit)) {
                    return false;
                }
            } else if (child.isDirty()) {
                return false;
            }
        }
        return true;
    }

    private static boolean hashRows(IBlur3Hash hash, ViewGroup list, float top, float limit) {
        hash.add(list.getScrollY());
        hash.add(list.getScrollX());
        hash.add(list.getChildCount());
        for (int i = 0, n = list.getChildCount(); i < n; i++) {
            final View row = list.getChildAt(i);
            if (row.getVisibility() != View.VISIBLE) {
                continue;
            }
            final float rowTop = top + row.getY() - list.getScrollY();
            if (rowTop >= limit || rowTop + row.getHeight() <= 0) {
                continue;
            }
            if (row.isDirty()) {
                return false;
            }
            hash.add(System.identityHashCode(row));
            hash.addF(row.getX());
            hash.addF(row.getY());
            hash.addF(row.getAlpha());
            hash.add(row.getHeight());
        }
        return true;
    }

    private void drawContent(Canvas canvas) {
        if (fragmentView.getVisibility() != View.VISIBLE || fragmentView.getWidth() <= 0) {
            return;
        }
        canvas.save();
        canvas.clipRect(0, 0, fragmentView.getRight() + fragmentView.getTranslationX(), glassHeight());
        canvas.drawColor(lastPageColor);
        canvas.translate(fragmentView.getLeft() + fragmentView.getTranslationX(), fragmentView.getTop() + fragmentView.getTranslationY());
        fragmentView.draw(canvas);
        canvas.restore();
    }
}
