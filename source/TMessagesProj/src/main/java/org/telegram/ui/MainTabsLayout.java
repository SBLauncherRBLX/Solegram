package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.lerp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RecordingCanvas;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.os.Build;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.FloatPropertyCompat;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import android.util.Log;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedLinearLayout;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.glass.GlassTabView;

import java.util.HashSet;
import java.util.Set;

import me.vkryl.android.animator.BoolAnimator;
import me.vkryl.android.animator.ListAnimator;
import me.vkryl.android.util.ClickHelper;

@SuppressLint("ViewConstructor")
public class MainTabsLayout extends AnimatedLinearLayout {

    private final Theme.ResourcesProvider resourcesProvider;

    public MainTabsLayout(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
    }

    private static final float[] PASS_TEXT_SIZES_DP = {12f, 12f, 10f};
    private static final int[] PASS_PADDINGS_DP = {16, 8, 4};

    private int maxWidthPx;
    private int minTabsWidthPx = -1;

    /** Solegram: the narrowest the row of tabs gets (Telegram stretches it to 320dp). */
    public void setMinTabsWidth(int px) {
        if (minTabsWidthPx != px) {
            minTabsWidthPx = px;
            requestLayout();
        }
    }

    public void setMaxWidth(int maxWidthPx) {
        if (this.maxWidthPx != maxWidthPx) {
            this.maxWidthPx = maxWidthPx;
            requestLayout();
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        final int height = MeasureSpec.getSize(heightMeasureSpec);
        final int tabHeight = height - getPaddingTop() - getPaddingBottom();

        if (maxWidthPx > 0 && width > maxWidthPx) {
            width = maxWidthPx;
        }

        final int maxTotalWidthForTabs = width - getPaddingLeft() - getPaddingRight();
        final int minTotalWidthForTabs = Math.min(minTabsWidthPx >= 0 ? minTabsWidthPx : dp(320), maxTotalWidthForTabs);

        int chosenPass = PASS_TEXT_SIZES_DP.length - 1;
        float lastMeasuredTextSize = -1;
        for (int pass = 0; pass < PASS_TEXT_SIZES_DP.length; pass++) {
            if (PASS_TEXT_SIZES_DP[pass] != lastMeasuredTextSize) {
                measureTabTexts(PASS_TEXT_SIZES_DP[pass]);
                lastMeasuredTextSize = PASS_TEXT_SIZES_DP[pass];
            }
            final int padding = dp(PASS_PADDINGS_DP[pass]);
            float total = 0;
            for (int a = 0, N = getChildCount(); a < N; a++) {
                if (!isViewVisible(getChildAt(a))) continue;
                final float withMargin = tabsTextWidth[a] + padding * 2;
                total += withMargin;
            }
            final boolean fits = total <= maxTotalWidthForTabs;
            if (fits || pass == PASS_TEXT_SIZES_DP.length - 1) {
                chosenPass = pass;
                break;
            }
        }

        applyPassTextSize(chosenPass);

        final int tabPadding = dp(PASS_PADDINGS_DP[chosenPass]);
        final int maxTabTextWidthIfEq = (maxTotalWidthForTabs / Math.max(1, visibleChildCount)) - tabPadding * 2;

        float totalWidth = 0;
        int totalWeight = 0;
        for (int a = 0, N = getChildCount(); a < N; a++) {
            final View child = getChildAt(a);
            if (!isViewVisible(child)) {
                tabsTextWidth[a] = tabsTextWidthWithMargin[a] = 0;
                tabsWeight[a] = 0;
                continue;
            }

            tabsTextWidthWithMargin[a] = tabsTextWidth[a] + tabPadding * 2;
            tabsWeight[a] = tabsTextWidthWithMargin[a] > (maxTabTextWidthIfEq + tabPadding * 2) ? 0 : 1;

            totalWidth += tabsTextWidthWithMargin[a];
            totalWeight += tabsWeight[a];
        }

        if (totalWeight == 0) {
            for (int a = 0, N = getChildCount(); a < N; a++) {
                tabsWeight[a] = isViewVisible(getChildAt(a)) ? 1 : 0;
            }
            totalWeight = visibleChildCount;
        }

        if (totalWidth > maxTotalWidthForTabs) {
            final float m = maxTotalWidthForTabs / totalWidth;
            for (int a = 0, N = getChildCount(); a < N; a++) {
                tabsTextWidthWithMargin[a] *= m;
            }
        } else if (totalWidth < minTotalWidthForTabs) {
            final float growW = minTotalWidthForTabs - totalWidth;
            final float growP = growW / totalWeight;

            for (int a = 0, N = getChildCount(); a < N; a++) {
                tabsTextWidthWithMargin[a] += growP * tabsWeight[a];
            }
        }

        int l = 0;
        for (int a = 0, N = getChildCount(); a < N; a++) {
            if (!isViewVisible(getChildAt(a))) {
                continue;
            }

            tabsWidth[a] = Math.round(tabsTextWidthWithMargin[a]);
            tabsLeftPos[a] = l;
            l += tabsWidth[a];
        }
        setMeasuredDimension(l + getPaddingLeft() + getPaddingRight(), height);
        for (int a = 0, N = getChildCount(); a < N; a++) {
            final View child = getChildAt(a);
            child.measure(
                MeasureSpec.makeMeasureSpec(tabsWidth[a], MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(tabHeight, MeasureSpec.EXACTLY));
        }

        calculateTotalSizesAfterMeasure();
    }

    public interface Tab {
        float measureTextWidth();
        default float measureTextWidth(float textSizeDp) { return measureTextWidth(); }
        default void setTextSizeDp(float textSizeDp) {}
    }



    // fills tabsTextWidth[] and return visible child count;

    private float[] tabsTextWidth;
    private float[] tabsTextWidthWithMargin;
    private int[] tabsWeight;
    private int[] tabsWidth;

    private int[] tabsLeftPos;


    private int visibleChildCount;
    private int biggestTabTextWidth;

    private void measureTabTexts(float textSizeDp) {
        final int childCount = getChildCount();
        if (tabsTextWidth == null || tabsTextWidth.length < childCount) {
            tabsTextWidth = new float[childCount];
            tabsTextWidthWithMargin = new float[childCount];
            tabsWeight = new int[childCount];
            tabsLeftPos = new int[childCount];
            tabsWidth = new int[childCount];
        }

        float maxTabWidthF = 0;
        int index = 0;

        for (int a = 0; a < childCount; a++) {
            final View child = getChildAt(a);
            if (!isViewVisible(child)) {
                tabsTextWidth[a] = -1;
                continue;
            }

            final float tabWidth;
            if (child instanceof MainTabsLayout.Tab) {
                tabWidth = ((MainTabsLayout.Tab) child).measureTextWidth(textSizeDp);
            } else {
                tabWidth = 0;
            }

            tabsTextWidth[a] = tabWidth;
            maxTabWidthF = Math.max(maxTabWidthF, tabWidth);
            index++;
        }

        biggestTabTextWidth = (int) Math.ceil(maxTabWidthF);
        visibleChildCount = index;
    }

    private void applyPassTextSize(int pass) {
        final float textSizeDp = PASS_TEXT_SIZES_DP[pass];
        for (int a = 0, N = getChildCount(); a < N; a++) {
            final View child = getChildAt(a);
            if (child instanceof MainTabsLayout.Tab) {
                ((MainTabsLayout.Tab) child).setTextSizeDp(textSizeDp);
            }
        }
    }

    @Override
    protected void setChildVisibilityFactor(View view, float factor) {
        final float s = lerp(0.7f, 1f, factor);
        view.setAlpha(factor);
        view.setScaleX(s);
        view.setScaleY(s);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        checkVisualWidth();
    }

    @Override
    protected void onItemsChanged() {
        super.onItemsChanged();
        checkVisualWidth();
    }

    private void checkVisualWidth() {
        for (int a = 0, N = getEntriesCount(); a < N; a++) {
            final ListAnimator.Entry<Holder> entry = getEntry(a);
            final float width = entry.getRectF().width();
            ((GlassTabView) entry.item.view).setVisualWidth(width);
        }
    }








    public void setTabSelected(View tab, boolean animated) {
        for (int a = 0, N = getChildCount(); a < N; a++) {
            final View child = getChildAt(a);
            if (child instanceof GlassTabView) {
                ((GlassTabView) child).setSelected(child == tab, animated);
            }
        }
    }

    private View findSelectedTab() {
        for (int a = 0, N = getChildCount(); a < N; a++) {
            final View child = getChildAt(a);
            if (child.getVisibility() != View.VISIBLE) {
                continue;
            }

            if (child instanceof GlassTabView) {
                if (((GlassTabView) child).isTabSelected()) {
                    return child;
                }
            }
        }
        return null;
    }

    private final Runnable restoreDrawSelector = () -> setSkipDrawSelector(false);

    private boolean drawCustomSelector;
    private void setSkipDrawSelector(boolean skipDrawSelector) {
        drawCustomSelector = skipDrawSelector;
        if (drawCustomSelector) {
            selectorPaint.setColor(Theme.multAlpha(Theme.getColor(Theme.key_glass_tabSelected, resourcesProvider), 0.09f));
        }
        for (int a = 0, N = getChildCount(); a < N; a++) {
            final View child = getChildAt(a);
            if (child.getVisibility() != View.VISIBLE) {
                continue;
            }

            if (child instanceof GlassTabView) {
                ((GlassTabView) child).setSkipDrawSelector(skipDrawSelector);
            }
        }
        invalidate();
    }







    @Override
    protected void dispatchDraw(@NonNull Canvas canvas) {
        if (lensShown) {
            // Solegram: the selection is a glass lens under the finger
            drawLens(canvas);
            return;
        }
        if (drawCustomSelector) {
            final float x = animatedLongSelectedViewCenterX + animatedLongSelectedViewOffsetX;
            final float sWidth = getInterpolatedWidthByX(x, this);
            final float sHeight = getHeight() - getPaddingTop() - getPaddingBottom();

            canvas.drawRoundRect(
                    x - sWidth / 2f, (getHeight() - sHeight) / 2f,
                    x + sWidth / 2f, (getHeight() + sHeight) / 2f,
                    sHeight / 2f, sHeight / 2f, selectorPaint);
        }

        super.dispatchDraw(canvas);
    }


    final Paint selectorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    final SpringAnimation scaleX = new SpringAnimation(this, DynamicAnimation.SCALE_X, 1f);
    final SpringAnimation scaleY = new SpringAnimation(this, DynamicAnimation.SCALE_Y, 1f);

    final SpringAnimation selectedTabPositionOffsetX = new SpringAnimation(this, new FloatPropertyCompat<MainTabsLayout>("selectedTabPositionOffsetX") {
        @Override
        public float getValue(MainTabsLayout object) {
            return object.animatedLongSelectedViewOffsetX;
        }

        @Override
        public void setValue(MainTabsLayout object, float value) {
            object.animatedLongSelectedViewOffsetX = value;
            object.invalidate();
        }
    });
    final SpringAnimation selectedTabPositionX = new SpringAnimation(this, new FloatPropertyCompat<MainTabsLayout>("selectedTabPositionX") {
        @Override
        public float getValue(MainTabsLayout object) {
            return object.animatedLongSelectedViewCenterX;
        }

        @Override
        public void setValue(MainTabsLayout object, float value) {
            object.animatedLongSelectedViewCenterX = value;
            object.invalidate();
        }
    });

    {
        selectedTabPositionOffsetX.setSpring(new SpringForce(1)
            .setStiffness(SpringForce.STIFFNESS_MEDIUM)
            .setDampingRatio(SpringForce.DAMPING_RATIO_LOW_BOUNCY));
        scaleX.setSpring(new SpringForce(1f)
            .setStiffness(250)
            .setDampingRatio(0.25f));
        scaleY.setSpring(new SpringForce(1f)
            .setStiffness(250)
            .setDampingRatio(0.25f));
        selectedTabPositionX.setSpring(new SpringForce(1f)
            .setStiffness(SpringForce.STIFFNESS_MEDIUM)
            .setDampingRatio(SpringForce.DAMPING_RATIO_LOW_BOUNCY));
    }

    private float animatedLongSelectedViewCenterX;
    private float animatedLongSelectedViewOffsetX;

    private boolean isInLongPress;
    private float lastLongSelectedViewCenterX;
    private float lastLongSelectedViewWidth;
    private View lastLongSelectedView;




    public static View findChildUnder(ViewGroup parent, float x, float y) {
        for (int i = parent.getChildCount() - 1; i >= 0; i--) {
            View child = parent.getChildAt(i);

            if (child.getVisibility() != View.VISIBLE) continue;

            if (x >= child.getLeft() && x <= child.getRight()
                    && y >= child.getTop() && y <= child.getBottom()) {
                return child;
            }
        }
        return null;
    }

    private void checkLongMove(float x_, float y, boolean start, boolean end) {
        final float x = clampXToChildrenCenters(x_, this);
        final View found = findNearestVisibleChildByX(x, this);
        if (start) {
            View selected = findSelectedTab();
            if (selected != null) {
                animatedLongSelectedViewCenterX = selected.getX() + selected.getWidth() / 2f;
                animatedLongSelectedViewOffsetX = animatedLongSelectedViewCenterX - x;
                selectedTabPositionOffsetX.animateToFinalPosition(0);
                if (selected != found && found != null) {
                    found.performClick();
                }
            }
            selectedTabPositionX.cancel();
        }

        if (!end) {
            animatedLongSelectedViewCenterX = x;
            invalidate();
        }

        if (found != null) {
            lastLongSelectedView = found;
            setTabSelected(found, true);

            if (end) {
                final float vw = found.getWidth();
                final float cx = found.getX() + vw / 2f;
                if (lastLongSelectedViewWidth != vw || lastLongSelectedViewCenterX != cx) {
                    selectedTabPositionX.animateToFinalPosition(cx);
                }
            }
        }
    }

    private final Set<View> tabsWithIgnoreClick = new HashSet<>();
    public void addTabToIgnoreClick(View v) {
        tabsWithIgnoreClick.add(v);
    }

    private final BoolAnimator animatorIsScaled = new BoolAnimator(0, (a, factor, c, g) -> {
        setScaleX(lerp(1, 1.019f, factor));
        setScaleY(lerp(1, 1.019f, factor));
    }, CubicBezierInterpolator.EASE_OUT_QUINT, 380);

    private final ClickHelper clickHelper = new ClickHelper(new ClickHelper.Delegate() {
        @Override
        public boolean needClickAt(View view, float x, float y) {
            lastLongSelectedView = null;
            final View found = findChildUnder(MainTabsLayout.this, x, y);
            return found != null && !tabsWithIgnoreClick.contains(found);
        }

        @Override
        public void onClickAt(View view, float x, float y) {
        }

        @Override
        public boolean needLongPress(float x, float y) {
            return true;
        }

        @Override
        public boolean needCancelTouchBySlopMove() {
            return false;
        }


        @Override
        public boolean onLongPressRequestedAt(View view, float x, float y) {
            checkPivot(view, x, y);
            isInLongPress = true;
            AndroidUtilities.cancelRunOnUIThread(restoreDrawSelector);
            setSkipDrawSelector(true);
            checkLongMove(x, y, true, false);
            invalidate();
            longTouchStart();
            return true;
        }

        @Override
        public void onLongPressMove(View view, MotionEvent e, float x, float y, float startX, float startY) {
            checkPivot(view, x, y);
            checkLongMove(x, y, false, false);
            invalidate();
        }

        @Override
        public long getLongPressDuration() {
            return ClickHelper.Delegate.super.getLongPressDuration() * 750 / 1000;
        }

        @Override
        public void onLongPressFinish(View view, float x, float y) {
            checkPivot(view, x, y);
            checkLongMove(x, y, false, true);
            isInLongPress = false;
            AndroidUtilities.runOnUIThread(restoreDrawSelector, 450);
            if (lastLongSelectedView != null) {
                lastLongSelectedView.performClick();
            }
            lastLongSelectedView = null;
            invalidate();
            longTouchEnd();
        }

        @Override
        public void onLongPressCancelled(View view, float x, float y) {
            checkPivot(view, x, y);
            checkLongMove(x, y, false, true);
            isInLongPress = false;
            AndroidUtilities.runOnUIThread(restoreDrawSelector, 450);
            lastLongSelectedView = null;
            invalidate();
            longTouchEnd();
        }

        private void longTouchStart() {
            animatorIsScaled.setValue(true, true);

            /*
            if (!scaleX.isRunning()) {
                scaleX.setStartVelocity(-0.45f);
                scaleY.setStartVelocity(-0.45f);
            }
            scaleX.animateToFinalPosition(1.012f);
            scaleY.animateToFinalPosition(1.012f);
            */
        }

        private void longTouchEnd() {
            animatorIsScaled.setValue(false, true);

            /*
            if (!scaleX.isRunning()) {
                scaleX.setStartVelocity(0.25f);
                scaleY.setStartVelocity(0.25f);
            }
            scaleX.animateToFinalPosition(1f);
            scaleY.animateToFinalPosition(1f);
            */
        }
    });

    /** Solegram: told when the bar grows under a long press (its liquid glass, drawn elsewhere, grows along). */
    public Runnable solegramOnScaleChanged;

    @Override
    public void setScaleY(float scaleY) {
        super.setScaleY(scaleY);
        checkLayerType();
        if (solegramOnScaleChanged != null) {
            solegramOnScaleChanged.run();
        }
    }

    @Override
    public void setScaleX(float scaleX) {
        super.setScaleX(scaleX);
        checkLayerType();
        if (solegramOnScaleChanged != null) {
            solegramOnScaleChanged.run();
        }
    }

    private void checkLayerType() {
        final int layerType = Math.abs(getScaleX() - 1f) < 0.0001f && Math.abs(getScaleY() - 1f) < 0.0001f ?
            View.LAYER_TYPE_NONE : View.LAYER_TYPE_HARDWARE;

        if (getLayerType() != layerType) {
            setLayerType(layerType, null);
            invalidate();
        }
    }


    private void checkPivot(View view, float x, float y) {
        float w = view.getWidth();
        float h = view.getHeight();

        if (w <= 0f || h <= 0f) {
            return;
        }

        float cx = w * 0.5f;
        float cy = h * 0.5f;

        float dx = x - cx;
        float dy = y - cy;

        float halfW = w * 0.5f;
        float halfH = h * 0.5f;

        float nx = dx / halfW;
        float ny = dy / halfH;

        float r = (float) Math.sqrt(nx * nx + ny * ny);

        float pivotX;
        float pivotY;

        if (r > 1e-4f) {
            float mappedR = 1.5f * r / (r + 0.5f);

            float scale = mappedR / r;
            pivotX = cx + dx * scale;
            pivotY = cy + dy * scale;
        } else {
            pivotX = cx;
            pivotY = cy;
        }

        pivotX = lerp(cx, pivotX, 1f);
        pivotY = lerp(cy, pivotY, 3f);

        view.setPivotX(pivotX);
        view.setPivotY(pivotY);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
            lensMode = true;
        }
        if (lensMode) {
            // Solegram: press or drag grows the selection into a lens; the tabs still get their taps
            lensTouch(ev);
            final boolean handled = super.dispatchTouchEvent(ev);
            return handled || lensTracking;
        }
        clickHelper.onTouchEvent(this, ev);
        return super.dispatchTouchEvent(ev);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        // a drag along the bar is the lens's: the tab under the finger lets go (no click, no menu)
        return lensMode && lensDragging || super.onInterceptTouchEvent(ev);
    }

    /* Solegram: like iOS 26, pressing or dragging on the bar turns the selection pill into a big clear glass
     * lens (taller than the bar) that magnifies and bends the tabs under it and follows the finger; on release
     * it springs back to a pill on the chosen tab. Drawn over Telegram's own tab bar and glass. */

    private boolean lensMode;
    private boolean lensTracking, lensDragging;
    private boolean lensShown;
    private float lensDownX;
    @Nullable
    private View lensDownChild, lensFound, lensStartSelected;
    private final com.solegram.messenger.ui.SolegramLiquid.Spring lensX = new com.solegram.messenger.ui.SolegramLiquid.Spring(0).params(0.3f, 0.8f);
    private final com.solegram.messenger.ui.SolegramLiquid.Spring lensA = new com.solegram.messenger.ui.SolegramLiquid.Spring(0).rest(0.002f);
    private long lensLastFrame;
    private boolean lensRich;
    @Nullable
    private Object lensShader;
    private boolean lensShaderFailed;
    private final Path lensPath = new Path();
    private final RectF lensRect = new RectF();
    private final Paint lensPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lensRimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix lensRimMatrix = new Matrix();
    // what the glass refracts: the bar's own glass (the blurred screen under it) and the tabs on it
    private final Utilities.Callback<Canvas> lensContent = c -> {
        final android.graphics.drawable.Drawable bg = getBackground();
        if (bg != null) {
            bg.draw(c);
        }
        super.dispatchDraw(c);
    };
    private final Runnable lensHoldCheck = () -> {
        // held still on a tab with a menu of its own: the menu takes the gesture, the lens goes back
        if (lensTracking && !lensDragging && lensDownChild != null && lensDownChild.isLongClickable()) {
            lensRelease(false);
        }
    };

    private void lensTouch(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                lensTracking = false;
                lensDragging = false;
                removeCallbacks(lensHoldCheck);
                final View child = findChildUnder(this, ev.getX(), ev.getY());
                if (child == null) {
                    return;
                }
                lensTracking = true;
                lensRich = com.solegram.messenger.ui.SolegramMotion.rich();
                lensDownX = ev.getX();
                lensDownChild = child;
                lensStartSelected = findSelectedTab();
                lensFound = lensStartSelected;
                if (!lensShown) {
                    // the lens grows out of the selection pill, then slides under the finger
                    final View from = lensStartSelected != null ? lensStartSelected : child;
                    lensX.set(from.getX() + from.getWidth() / 2f);
                    lensA.set(0);
                    setLensShown(true);
                }
                lensX.to(clampXToChildrenCenters(ev.getX(), this));
                lensA.params(0.3f, 0.72f);
                lensA.to(1);
                postDelayed(lensHoldCheck, ViewConfiguration.getLongPressTimeout());
                invalidate();
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                if (!lensTracking) {
                    return;
                }
                if (!lensDragging && Math.abs(ev.getX() - lensDownX) > ViewConfiguration.get(getContext()).getScaledTouchSlop()) {
                    lensDragging = true;
                    removeCallbacks(lensHoldCheck);
                    if (getParent() != null) {
                        getParent().requestDisallowInterceptTouchEvent(true);
                    }
                }
                if (lensDragging) {
                    final float x = clampXToChildrenCenters(ev.getX(), this);
                    lensX.to(x);
                    final View found = findNearestVisibleChildByX(x, this);
                    if (found != null && found != lensFound) {
                        lensFound = found;
                        setTabSelected(found, true);
                        try {
                            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
                        } catch (Exception ignore) {
                        }
                    }
                    invalidate();
                }
                break;
            }
            case MotionEvent.ACTION_UP:
                lensRelease(true);
                break;
            case MotionEvent.ACTION_CANCEL:
                lensRelease(false);
                break;
        }
    }

    /** The finger is gone: a drag selects the tab under it; the lens springs back into a pill on the selection. */
    private void lensRelease(boolean up) {
        removeCallbacks(lensHoldCheck);
        if (!lensTracking) {
            return;
        }
        if (lensDragging) {
            if (up && lensFound != null && lensFound != lensStartSelected) {
                lensFound.performClick();
            } else if (lensStartSelected != null && lensFound != lensStartSelected) {
                setTabSelected(lensStartSelected, true);
            }
        }
        lensTracking = false;
        lensDragging = false;
        lensDownChild = null;
        // a little bounce as it settles
        lensA.params(0.42f, 0.6f);
        lensA.to(0);
        invalidate();
    }

    private void setLensShown(boolean shown) {
        lensShown = shown;
        for (int a = 0, N = getChildCount(); a < N; a++) {
            final View child = getChildAt(a);
            if (child instanceof GlassTabView) {
                ((GlassTabView) child).setSkipDrawSelector(shown);
            }
        }
        invalidate();
    }

    private void drawLens(Canvas canvas) {
        final long now = System.nanoTime();
        final float dt = lensLastFrame == 0 ? 1f / 60f : Math.max(0, Math.min(1f / 30f, (now - lensLastFrame) / 1e9f));
        lensLastFrame = now;
        if (!lensTracking) {
            // back to the selected tab (a tap selects it a moment after the finger is up)
            final View selected = findSelectedTab();
            if (selected != null) {
                lensX.to(selected.getX() + selected.getWidth() / 2f);
            }
        }
        boolean moving = lensX.step(dt);
        if (lensA.step(dt)) {
            moving = true;
        }
        if (!moving) {
            lensLastFrame = 0;
            if (!lensTracking && Math.abs(lensA.value) < 0.002f) {
                // a plain pill again: the tabs draw their own
                setLensShown(false);
                super.dispatchDraw(canvas);
                return;
            }
        }

        final float a = Math.max(-0.2f, Math.min(1.04f, lensA.value));
        final float on = Math.max(0, Math.min(1, a));
        final float tabH = getHeight() - getPaddingTop() - getPaddingBottom();
        final float cx = lensX.value, cy = getPaddingTop() + tabH / 2f;
        final float pillW = getInterpolatedWidthByX(cx, this);
        // about 1.25 of the capsule's height: over the bar's glass at the top and bottom
        final float lensH = getHeight() - dp(1);
        final float lensW = Math.max(pillW * 1.18f + dp(6), lensH * 1.2f);
        // liquid stretch: the glass draws out along its way as it slides, and rounds up again as it stops
        final float stretch = lensRich ? Math.min(0.22f, Math.abs(lensX.velocity) / dp(2400)) : 0f;
        final float w = Math.max(dp(8), pillW + (lensW - pillW) * a) * (1f + stretch);
        final float h = Math.max(dp(8), tabH + (lensH - tabH) * a) * (1f - stretch * 0.15f);
        final float r = h / 2f;
        // how fast the glass slides (0..1): a moving drop bends the picture a little more, like liquid
        lensFlow = lensRich ? Math.min(1f, Math.abs(lensX.velocity) / dp(1800)) : 0f;
        lensRect.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f);
        lensPath.rewind();
        lensPath.addRoundRect(lensRect, r, r, Path.Direction.CW);

        // the tabs around the lens as they are; under it only the lens shows them
        canvas.save();
        if (Build.VERSION.SDK_INT >= 26) {
            canvas.clipOutPath(lensPath);
        } else {
            canvas.clipPath(lensPath, android.graphics.Region.Op.DIFFERENCE);
        }
        super.dispatchDraw(canvas);
        canvas.restore();

        // a soft shadow while lifted, never seen through the glass
        if (Build.VERSION.SDK_INT >= 28 && on > 0.01f) {
            canvas.save();
            canvas.clipOutPath(lensPath);
            lensPaint.setColor(0x06000000);
            lensPaint.setShadowLayer(dp(6) * on, 0, dp(2) * on, (int) (0x26 * on) << 24);
            canvas.drawPath(lensPath, lensPaint);
            lensPaint.clearShadowLayer();
            canvas.restore();
        }
        // the tabs under the lens: magnified, bent at the rim, colours split there (Android 13+)
        final boolean shaderLens = drawShaderLens(canvas, cx, cy, w, h, on);
        // Telegram's pill fades as the glass forms (over the shader's picture, which hides what is under it)
        if (on < 1f) {
            lensPaint.setColor(Theme.multAlpha(Theme.getColor(Theme.key_glass_tabSelected, resourcesProvider), 0.09f * (1f - on)));
            canvas.drawPath(lensPath, lensPaint);
        }
        if (!shaderLens) {
            canvas.save();
            canvas.clipPath(lensPath);
            final float magnify = 1f + 0.3f * on;
            canvas.scale(magnify, magnify, cx, cy);
            super.dispatchDraw(canvas);
            canvas.restore();
            if (on > 0.01f) {
                // clear glass: a barely-there lightening, a soft dark inner edge and a thin crisp rim
                lensPaint.setColor((int) ((Theme.isCurrentThemeDark() ? 0x0B : 0x0F) * on) << 24 | 0xFFFFFF);
                canvas.drawPath(lensPath, lensPaint);
                lensPaint.setStyle(Paint.Style.STROKE);
                lensPaint.setStrokeWidth(AndroidUtilities.dpf2(3));
                lensPaint.setColor((int) (0x1A * on) << 24);
                canvas.save();
                canvas.clipPath(lensPath);
                canvas.drawPath(lensPath, lensPaint);
                canvas.restore();
                lensPaint.setStyle(Paint.Style.FILL);
                if (lensRimPaint.getShader() == null) {
                    lensRimPaint.setStyle(Paint.Style.STROKE);
                    lensRimPaint.setShader(new LinearGradient(0, 0, 1, 1,
                        new int[]{0xF0FFFFFF, 0x26FFFFFF, 0xC0FFFFFF}, new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
                }
                lensRimMatrix.setScale(lensRect.width(), lensRect.height());
                lensRimMatrix.postTranslate(lensRect.left, lensRect.top);
                lensRimPaint.getShader().setLocalMatrix(lensRimMatrix);
                lensRimPaint.setStrokeWidth(Math.max(1f, AndroidUtilities.dpf2(1.4f)));
                lensRimPaint.setAlpha((int) (230 * on));
                final float inset = lensRimPaint.getStrokeWidth() / 2f;
                lensRect.inset(inset, inset);
                canvas.drawRoundRect(lensRect, r - inset, r - inset, lensRimPaint);
            }
        }
        if (moving) {
            postInvalidateOnAnimation();
        }
    }

    private final android.graphics.Rect lensBarPadding = new android.graphics.Rect();
    private final RectF lensBar = new RectF();
    private float lensFlow;

    private boolean drawShaderLens(Canvas canvas, float cx, float cy, float w, float h, float on) {
        if (lensShaderFailed || Build.VERSION.SDK_INT < 33 || !canvas.isHardwareAccelerated() || getWidth() <= 0 || getHeight() <= 0) {
            return false;
        }
        try {
            if (lensShader == null) {
                lensShader = new LensShader();
            }
            // the bar's glass capsule: the view less the background's padding
            final android.graphics.drawable.Drawable bg = getBackground();
            lensBarPadding.setEmpty();
            if (bg != null) {
                bg.getPadding(lensBarPadding);
            }
            lensBar.set(lensBarPadding.left, lensBarPadding.top, getWidth() - lensBarPadding.right, getHeight() - lensBarPadding.bottom);
            if (lensBar.width() <= 0 || lensBar.height() <= 0) {
                lensBar.set(0, 0, getWidth(), getHeight());
            }
            ((LensShader) lensShader).draw(canvas, getWidth(), getHeight(), cx, cy, w, h, on, lensFlow, lensBar, lensContent);
            return true;
        } catch (Throwable e) {
            FileLog.e(e);
            lensShaderFailed = true;
            lensShader = null;
            return false;
        }
    }

    /** The tab bar's lens shader (solegram_tab_lens_shader) over the tabs. */
    @RequiresApi(33)
    private static final class LensShader {
        private final RenderNode node = new RenderNode("solegramTabLens");
        private final RuntimeShader shader = new RuntimeShader(AndroidUtilities.readRes(R.raw.solegram_tab_lens_shader));

        private RenderEffect effect;
        private final float[] last = new float[10];
        private boolean lastDark;
        private int lastDispersion = -1;

        void draw(Canvas canvas, int viewW, int viewH, float cx, float cy, float w, float h, float a, float flow, RectF bar, Utilities.Callback<Canvas> content) {
            // the tabs under the lens (child render nodes by reference: cheap, and their changes show at once)
            node.setPosition(0, 0, viewW, viewH);
            final RecordingCanvas rc = node.beginRecording(viewW, viewH);
            try {
                content.run(rc);
            } finally {
                node.endRecording();
            }
            // the effect takes the uniforms when it is made: a new one only when the lens changed
            final boolean dark = Theme.isCurrentThemeDark();
            final int dispersion = com.solegram.messenger.SolegramConfig.glassDispersion;
            if (effect == null || last[0] != cx || last[1] != cy || last[2] != w || last[3] != h || last[4] != a || lastDark != dark
                || last[5] != bar.left || last[6] != bar.top || last[7] != bar.right || last[8] != bar.bottom
                || last[9] != flow || lastDispersion != dispersion) {
                lastDark = dark;
                lastDispersion = dispersion;
                last[0] = cx;
                last[1] = cy;
                last[2] = w;
                last[3] = h;
                last[4] = a;
                last[5] = bar.left;
                last[6] = bar.top;
                last[7] = bar.right;
                last[8] = bar.bottom;
                last[9] = flow;
                // clear glass: a sharp middle magnified a little, a wide rounded bevel that draws the picture
                // out to the rim and smears it along the edge, with rainbow fringes there. The glass's own
                // dispersion setting drives the fringes, never below a clear rainbow on the lens
                final float band = Math.min(w, h) / 2f * 0.45f * (1f + 0.08f * flow);
                shader.setFloatUniform("center", cx, cy);
                shader.setFloatUniform("halfSize", w / 2f, h / 2f);
                shader.setFloatUniform("magnify", 1f + (0.15f + 0.03f * flow) * a);
                shader.setFloatUniform("band", band);
                shader.setFloatUniform("bend", band * (0.6f + 0.06f * flow) * a);
                shader.setFloatUniform("dispersion", (0.6f + 0.6f * dispersion / 100f) * (1f + 0.25f * flow) * a);
                shader.setFloatUniform("light", 0.9f * a);
                shader.setFloatUniform("shade", 0.16f * a);
                shader.setFloatUniform("dp", AndroidUtilities.density);
                shader.setFloatUniform("barCenter", bar.centerX(), bar.centerY());
                shader.setFloatUniform("barHalf", bar.width() / 2f, bar.height() / 2f);
                shader.setFloatUniform("dark", dark ? 1f : 0f);
                final float body = (dark ? 0.045f : 0.06f) * a; // a barely-there lightening, premultiplied
                shader.setFloatUniform("tint", body, body, body, body);
                effect = RenderEffect.createRuntimeShaderEffect(shader, "img");
                node.setRenderEffect(effect);
            }
            canvas.drawRenderNode(node);
        }
    }


    private static float clampXToChildrenCenters(float x, ViewGroup parent) {
        if (parent == null || parent.getChildCount() == 0) {
            return x;
        }

        float min = Float.MAX_VALUE;
        float max = -Float.MAX_VALUE;
        boolean found = false;

        for (int i = 0; i < parent.getChildCount(); i++) {
            View view = parent.getChildAt(i);
            if (view == null || view.getVisibility() != View.VISIBLE) {
                continue;
            }

            float centerX = view.getX() + view.getWidth() * 0.5f;

            if (centerX < min) min = centerX;
            if (centerX > max) max = centerX;

            found = true;
        }

        if (!found) {
            return x;
        }

        if (x < min) return min;
        if (x > max) return max;
        return x;
    }

    @Nullable
    private static View findNearestVisibleChildByX(float x, ViewGroup parent) {
        if (parent == null || parent.getChildCount() == 0) {
            return null;
        }

        View nearest = null;
        float nearestDistance = Float.MAX_VALUE;

        for (int i = 0; i < parent.getChildCount(); i++) {
            View view = parent.getChildAt(i);
            if (view == null || view.getVisibility() != View.VISIBLE) {
                continue;
            }

            float centerX = view.getX() + view.getWidth() * 0.5f;
            float distance = Math.abs(centerX - x);

            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = view;
            }
        }

        return nearest;
    }

    private static float getInterpolatedWidthByX(float x, ViewGroup parent) {
        if (parent == null || parent.getChildCount() == 0) {
            return 0f;
        }

        View left = null;
        View right = null;

        for (int i = 0; i < parent.getChildCount(); i++) {
            View view = parent.getChildAt(i);
            if (view == null || view.getVisibility() != View.VISIBLE) {
                continue;
            }

            float centerX = view.getX() + view.getWidth() * 0.5f;

            if (centerX <= x && (left == null || centerX > getCenterX(left))) {
                left = view;
            }

            if (centerX >= x && (right == null || centerX < getCenterX(right))) {
                right = view;
            }
        }

        if (left == null && right == null) {
            return 0f;
        }

        if (left == null) {
            return right.getWidth();
        }

        if (right == null) {
            return left.getWidth();
        }

        float leftX = getCenterX(left);
        float rightX = getCenterX(right);

        if (left == right || leftX == rightX) {
            return left.getWidth();
        }

        float ratio = (x - leftX) / (rightX - leftX);
        return lerp(left.getWidth(), right.getWidth(), ratio);
    }

    private static float getCenterX(View v) {
        return v.getX() + v.getWidth() * 0.5f;
    }
}
