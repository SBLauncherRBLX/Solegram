package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.SharedConfig;

public class BlurredRecyclerView extends RecyclerListView {

    public int blurTopPadding;
    public int topPadding;
    public int bottomPadding;
    boolean globalIgnoreLayout;
    public int additionalClipBottom;
    public boolean alwaysDrawChild;

    public BlurredRecyclerView(Context context) {
        super(context);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        globalIgnoreLayout = true;
        updateTopPadding();
        super.setPadding(getPaddingLeft(), topPadding + blurTopPadding, getPaddingRight(), getPaddingBottom());
        globalIgnoreLayout = false;
        super.onMeasure(widthSpec, heightSpec);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        updateTopPadding();
    }

    private void updateTopPadding() {
        if (getLayoutParams() == null) {
            return;
        }
        if (SharedConfig.chatBlurEnabled()) {
            blurTopPadding = measureBlurTopPadding();
            ((MarginLayoutParams) getLayoutParams()).topMargin = -blurTopPadding;
        } else {
            blurTopPadding = 0;
            ((MarginLayoutParams) getLayoutParams()).topMargin = 0;
        }
    }

    protected int measureBlurTopPadding() {
        return AndroidUtilities.dp(203);
    }

    @Override
    public void requestLayout() {
        if (globalIgnoreLayout) {
            return;
        }
        super.requestLayout();
    }

    /**
     * Solegram: under a floating header (Android 12+, where it is blurred) the rows above the list's top are drawn
     * too, not skipped to save work: the blur under the header must have them, else it showed the bare background
     * (a darkening) except while the list was pulled past its end.
     */
    private static boolean solegramDrawAll() {
        return android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S && com.solegram.messenger.SolegramConfig.floatingHeader;
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        if (blurTopPadding != 0 && !hasActiveEdgeEffects() && !solegramDrawAll()) {
            canvas.clipRect(0, blurTopPadding, getMeasuredWidth(), getMeasuredHeight() + additionalClipBottom);
            super.dispatchDraw(canvas);
        } else {
            super.dispatchDraw(canvas);
        }
    }

    @Override
    public void capture(Canvas canvas, RectF position) {
        alwaysDrawChild = true;
        super.capture(canvas, position);
        alwaysDrawChild = false;
    }

    @Override
    public boolean drawChild(Canvas canvas, View child, long drawingTime) {
        if ((child.getY() + child.getMeasuredHeight() < blurTopPadding) && !alwaysDrawChild && !hasActiveEdgeEffects() && !solegramDrawAll()) {
            return true;
        }
        return super.drawChild(canvas, child, drawingTime);
    }

    @Override
    public void setPadding(int left, int top, int right, int bottom) {
        topPadding = top;
        bottomPadding = bottom;
        super.setPadding(left, topPadding + blurTopPadding, right, bottomPadding);
    }
}
