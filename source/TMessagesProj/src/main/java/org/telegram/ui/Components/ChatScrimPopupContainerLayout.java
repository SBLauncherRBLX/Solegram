package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.util.Log;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.ActionBarPopupWindow;

public class ChatScrimPopupContainerLayout extends LinearLayout {

    private ReactionsContainerLayout reactionsLayout;
    private ActionBarPopupWindow.ActionBarPopupWindowLayout popupWindowLayout;
    private View bottomView;
    private int maxHeight;
    private float popupLayoutLeftOffset;
    private float progressToSwipeBack;
    private float bottomViewYOffset;
    private float expandSize;
    private float bottomViewReactionsOffset;

    public ChatScrimPopupContainerLayout(Context context) {
        super(context);
        setOrientation(LinearLayout.VERTICAL);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        solegramLayoutSidePill();
        if (solegramAfterLayout != null) {
            solegramAfterLayout.run();
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (solegramSidePill != null && getParent() instanceof android.view.ViewGroup) {
            ((android.view.ViewGroup) getParent()).setClipChildren(false);
        }
    }

    /** Solegram: the icons' pill as laid out now, or null. */
    @androidx.annotation.Nullable
    public View solegramSidePill() {
        return solegramSidePill != null && solegramSidePill.getParent() == this && solegramSidePill.getVisibility() != GONE ? solegramSidePill : null;
    }

    /** Solegram: told after each layout (the window is made wide enough for the menu and its pill). */
    public Runnable solegramAfterLayout;
    /** Solegram: the width the menu with its pill wants, before it is cut to the width the window gave. */
    public int solegramWantedWidth;
    /** Solegram: the window is wider than the menu (to the screen's edge): measure to the content, not to the window. */
    public boolean solegramWrapInWide;

    // region Solegram side pill

    /** Reply, forward, copy, pin as icons: a pill of its own to the right of the menu, its top level with the menu's. */
    private View solegramSidePill;
    private final android.graphics.Rect solegramShadow = new android.graphics.Rect();

    public void solegramSetSidePill(View pill) {
        solegramSidePill = pill;
        // the pill may lie past this layout's (or the window frame's) edge for a moment: never cut it, the window grows
        setClipChildren(false);
        setClipToPadding(false);
        if (getParent() instanceof android.view.ViewGroup) {
            ((android.view.ViewGroup) getParent()).setClipChildren(false);
        }
        pill.setClickable(true); // a tap between its buttons does not close the menu
        final android.graphics.drawable.Drawable shadow = androidx.core.content.ContextCompat.getDrawable(getContext(), org.telegram.messenger.R.drawable.popup_fixed_alert4);
        if (shadow != null) {
            shadow.getPadding(solegramShadow);
        }
        // last, with no room of its own in the column: it is placed beside the menu
        addView(pill, new LayoutParams(0, 0));
    }

    /** How much wider than the menu's own column the pill makes this layout (0: no pill, or it fits in the margin). */
    private int solegramSidePillReserve() {
        final View pill = solegramSidePill;
        if (pill == null || pill.getParent() != this || popupWindowLayout == null || popupWindowLayout.getParent() != this || pill.getVisibility() == GONE) {
            return 0;
        }
        pill.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        final LayoutParams plp = (LayoutParams) popupWindowLayout.getLayoutParams();
        // the pill's right edge past the menu's: its width less the shadows that overlap, plus the 8dp of air
        final int extra = pill.getMeasuredWidth() - solegramShadow.right - solegramShadow.left + dp(8);
        return Math.max(0, extra - plp.rightMargin);
    }

    /** Where the pill's left goes: 8dp of air between the two panels (their shadows overlap). */
    private int solegramSidePillLeft(int popupLeft, int popupWidth) {
        return popupLeft + popupWidth - solegramShadow.right - solegramShadow.left + dp(8);
    }

    private void solegramMeasureSidePill() {
        final View pill = solegramSidePill;
        if (pill == null || pill.getParent() != this || popupWindowLayout == null || popupWindowLayout.getParent() != this || pill.getVisibility() == GONE) {
            return;
        }
        pill.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        int top = getPaddingTop();
        for (int i = 0; i < getChildCount(); i++) {
            final View child = getChildAt(i);
            final LayoutParams lp = (LayoutParams) child.getLayoutParams();
            if (child == popupWindowLayout) {
                top += lp.topMargin;
                break;
            }
            if (child.getVisibility() != GONE) {
                top += lp.topMargin + child.getMeasuredHeight() + lp.bottomMargin;
            }
        }
        final LayoutParams plp = (LayoutParams) popupWindowLayout.getLayoutParams();
        final int right = solegramSidePillLeft(getPaddingLeft() + plp.leftMargin, popupWindowLayout.getMeasuredWidth()) + pill.getMeasuredWidth() + getPaddingRight();
        final int bottom = top + pill.getMeasuredHeight() + getPaddingBottom();
        setMeasuredDimension(Math.max(getMeasuredWidth(), right), Math.max(getMeasuredHeight(), bottom));
    }

    private void solegramLayoutSidePill() {
        final View pill = solegramSidePill;
        if (pill == null || pill.getParent() != this || popupWindowLayout == null || popupWindowLayout.getParent() != this || pill.getVisibility() == GONE) {
            return;
        }
        final int left = solegramSidePillLeft(popupWindowLayout.getLeft(), popupWindowLayout.getMeasuredWidth());
        final int top = popupWindowLayout.getTop();
        pill.layout(left, top, left + pill.getMeasuredWidth(), top + pill.getMeasuredHeight());
    }

    // endregion

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // Solegram: the room of the icons' pill is kept first, so the menu is measured narrower when it must be and the
        // pill never ends past the window's edge (the window gives no more width than it measured with)
        if (solegramWrapInWide && MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY) {
            widthMeasureSpec = MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.AT_MOST);
        }
        final int sgReserve = solegramSidePillReserve();
        final int sgMode = MeasureSpec.getMode(widthMeasureSpec), sgSize = MeasureSpec.getSize(widthMeasureSpec);
        if (sgReserve > 0 && sgMode != MeasureSpec.UNSPECIFIED) {
            widthMeasureSpec = MeasureSpec.makeMeasureSpec(Math.max(0, sgSize - sgReserve), sgMode);
        }
        if (maxHeight != 0) {
            heightMeasureSpec = MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST);
        }
        if (reactionsLayout != null && popupWindowLayout != null) {
            reactionsLayout.getLayoutParams().width = LayoutHelper.WRAP_CONTENT;
            ((LayoutParams) reactionsLayout.getLayoutParams()).rightMargin = 0;
            popupLayoutLeftOffset = 0;
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);

            int maxWidth = reactionsLayout.getMeasuredWidth();
            if (popupWindowLayout.getSwipeBack() != null && popupWindowLayout.getSwipeBack().getMeasuredWidth() > maxWidth) {
                maxWidth = popupWindowLayout.getSwipeBack().getMeasuredWidth();
            }
            if (popupWindowLayout.getMeasuredWidth() > maxWidth) {
                maxWidth = popupWindowLayout.getMeasuredWidth();
            }
            if (reactionsLayout.showCustomEmojiReaction()) {
                widthMeasureSpec = MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.EXACTLY);
            }
            reactionsLayout.measureHint();

            int reactionsLayoutTotalWidth = reactionsLayout.getTotalWidth();
            View menuContainer = popupWindowLayout.getSwipeBack() != null ? popupWindowLayout.getSwipeBack().getChildAt(0) : popupWindowLayout.getChildAt(0);
            int maxReactionsLayoutWidth = menuContainer.getMeasuredWidth() + dp(16) + dp(16) + dp(36);
            int hintTextWidth = reactionsLayout.getHintTextWidth();
            if (hintTextWidth > maxReactionsLayoutWidth) {
                maxReactionsLayoutWidth = hintTextWidth;
            } else if (maxReactionsLayoutWidth > maxWidth) {
                maxReactionsLayoutWidth = maxWidth;
            }
            reactionsLayout.bigCircleOffset = dp(36);
            if (reactionsLayout.showCustomEmojiReaction()) {
                reactionsLayout.getLayoutParams().width = reactionsLayoutTotalWidth;
                reactionsLayout.bigCircleOffset = Math.max(reactionsLayoutTotalWidth - menuContainer.getMeasuredWidth() - dp(36), dp(36));
            } else if (reactionsLayoutTotalWidth > maxReactionsLayoutWidth) {
                int maxFullCount = ((maxReactionsLayoutWidth - dp(16)) / dp(36)) + 1;
                int newWidth = maxFullCount * dp(36) + dp(8);
                if (hintTextWidth + dp(24) > newWidth) {
                    newWidth = hintTextWidth + dp(24);
                }
                if (newWidth > reactionsLayoutTotalWidth || maxFullCount == reactionsLayout.getItemsCount()) {
                    newWidth = reactionsLayoutTotalWidth;
                }
                reactionsLayout.getLayoutParams().width = newWidth;
            } else {
                reactionsLayout.getLayoutParams().width = LayoutHelper.WRAP_CONTENT;
            }
            int widthDiff = 0;
            if (reactionsLayout.getMeasuredWidth() != maxWidth || !reactionsLayout.showCustomEmojiReaction()) {
                if (popupWindowLayout.getSwipeBack() != null) {
                    widthDiff = popupWindowLayout.getSwipeBack().getMeasuredWidth() - popupWindowLayout.getSwipeBack().getChildAt(0).getMeasuredWidth();
                }
                if (reactionsLayout.getLayoutParams().width != LayoutHelper.WRAP_CONTENT && reactionsLayout.getLayoutParams().width + widthDiff > maxWidth) {
                    widthDiff = maxWidth - reactionsLayout.getLayoutParams().width + dp(8);
                }
                if (widthDiff < 0) {
                    widthDiff = 0;
                }
                ((LayoutParams) reactionsLayout.getLayoutParams()).rightMargin = widthDiff;
                popupLayoutLeftOffset = 0;
                updatePopupTranslation();
            } else {
                popupLayoutLeftOffset = (maxWidth - menuContainer.getMeasuredWidth()) * 0.25f;
                reactionsLayout.bigCircleOffset -= popupLayoutLeftOffset;
                if (reactionsLayout.bigCircleOffset < dp(36)) {
                    popupLayoutLeftOffset = 0;
                    reactionsLayout.bigCircleOffset = dp(36);
                }
                updatePopupTranslation();
            }
            if (bottomView != null) {
                if (reactionsLayout.showCustomEmojiReaction()) {
                    bottomView.getLayoutParams().width = menuContainer.getMeasuredWidth() + dp(16);
                    updatePopupTranslation();
                } else {
                    bottomView.getLayoutParams().width = LayoutHelper.MATCH_PARENT;
                }
                if (popupWindowLayout.getSwipeBack() != null) {
                    ((LayoutParams) bottomView.getLayoutParams()).rightMargin = widthDiff + dp(36);
                } else {
                    ((LayoutParams) bottomView.getLayoutParams()).rightMargin = dp(36);
                }
            }
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }
        solegramMeasureSidePill();
        // the whole width the menu and its pill want (the window grows to it, see ChatActivity.solegramFitMenuWindow)
        solegramWantedWidth = getMeasuredWidth();
        if (sgReserve > 0 && sgMode != MeasureSpec.UNSPECIFIED && getMeasuredWidth() > sgSize) {
            setMeasuredDimension(sgSize, getMeasuredHeight());
        }
        maxHeight = getMeasuredHeight();
    }

    private void updatePopupTranslation() {
        float x = (1f - progressToSwipeBack) * popupLayoutLeftOffset;
        popupWindowLayout.setTranslationX(x);
        if (bottomView != null) {
            bottomView.setTranslationX(x);
        }
        if (solegramSidePill != null) {
            solegramSidePill.setTranslationX(x);
        }
    }

    public void applyViewBottom(FrameLayout bottomView) {
        this.bottomView = bottomView;
    }

    public void setReactionsLayout(ReactionsContainerLayout reactionsLayout) {
        this.reactionsLayout = reactionsLayout;
        if (reactionsLayout != null) {
            reactionsLayout.setChatScrimView(this);
        }
    }

    public void setPopupWindowLayout(ActionBarPopupWindow.ActionBarPopupWindowLayout popupWindowLayout) {
        this.popupWindowLayout = popupWindowLayout;
        popupWindowLayout.setOnSizeChangedListener(() -> {
            if (bottomView != null) {
                bottomViewYOffset = popupWindowLayout.getVisibleHeight() - popupWindowLayout.getMeasuredHeight();
                updateBottomViewPosition();
            }
        });
        if (popupWindowLayout.getSwipeBack() != null) {
            popupWindowLayout.getSwipeBack().addOnSwipeBackProgressListener((layout, toProgress, progress) -> {
                if (bottomView != null) {
                    bottomView.setAlpha(1f - progress);
                }
                progressToSwipeBack = progress;
                updatePopupTranslation();
                if (solegramSidePill != null) {
                    // a sub-menu over the menu: the icons step aside
                    solegramSidePill.setAlpha(1f - progress);
                    solegramSidePill.setVisibility(progress >= 1f ? INVISIBLE : VISIBLE);
                }
            });
        }
    }

    private void updateBottomViewPosition() {
        if (bottomView != null) {
            bottomView.setTranslationY(bottomViewYOffset + expandSize + bottomViewReactionsOffset);
        }
    }

    public void setMaxHeight(int maxHeight) {
        this.maxHeight = maxHeight;
    }

    public void setExpandSize(float expandSize) {
        popupWindowLayout.setTranslationY(expandSize);
        if (solegramSidePill != null) {
            solegramSidePill.setTranslationY(expandSize);
        }
        this.expandSize = expandSize;
        updateBottomViewPosition();
    }

    public void setPopupAlpha(float alpha) {
        popupWindowLayout.setAlpha(alpha);
        if (bottomView != null) {
            bottomView.setAlpha(alpha);
        }
        if (solegramSidePill != null) {
            solegramSidePill.setAlpha(alpha);
        }
    }

    // region Solegram liquid menu

    /*
     * The glass of the reactions and of the menu is drawn by the chat as liquid drops flowing out of the message;
     * here the content only rides on its drop (alpha, offset and a little scale), drawn without its own panels.
     */
    private boolean solegramLiquid;
    private float solegramReactionsAlpha = 1f, solegramReactionsDx, solegramReactionsDy;
    private float solegramMenuAlpha = 1f, solegramMenuDx, solegramMenuDy;
    private int solegramGap;
    private Runnable solegramOnDraw;

    public void solegramSetLiquid(boolean liquid, Runnable onDraw) {
        solegramLiquid = liquid;
        solegramOnDraw = onDraw;
        invalidate();
    }

    /** Space between the reactions and the menu: the message sits there (reactions above it, the menu below). */
    public void solegramSetGap(int gap) {
        if (popupWindowLayout == null || !(popupWindowLayout.getLayoutParams() instanceof LayoutParams)) {
            return;
        }
        final LayoutParams lp = (LayoutParams) popupWindowLayout.getLayoutParams();
        lp.topMargin += gap - solegramGap;
        solegramGap = gap;
        maxHeight = 0;
        requestLayout();
    }

    public void solegramSetLiquidContent(float reactionsAlpha, float reactionsDx, float reactionsDy, float menuAlpha, float menuDx, float menuDy) {
        if (solegramReactionsAlpha != reactionsAlpha || solegramReactionsDx != reactionsDx || solegramReactionsDy != reactionsDy
                || solegramMenuAlpha != menuAlpha || solegramMenuDx != menuDx || solegramMenuDy != menuDy) {
            solegramReactionsAlpha = reactionsAlpha;
            solegramReactionsDx = reactionsDx;
            solegramReactionsDy = reactionsDy;
            solegramMenuAlpha = menuAlpha;
            solegramMenuDx = menuDx;
            solegramMenuDy = menuDy;
            invalidate();
        }
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        if (solegramLiquid && solegramOnDraw != null) {
            solegramOnDraw.run();
        }
    }

    @Override
    protected boolean drawChild(Canvas canvas, View child, long drawingTime) {
        if (!solegramLiquid) {
            return super.drawChild(canvas, child, drawingTime);
        }
        final float alpha, dx, dy;
        if (child == reactionsLayout) {
            alpha = solegramReactionsAlpha;
            dx = solegramReactionsDx;
            dy = solegramReactionsDy;
        } else if (child == popupWindowLayout || child == bottomView) {
            alpha = solegramMenuAlpha;
            dx = solegramMenuDx;
            dy = solegramMenuDy;
        } else {
            return super.drawChild(canvas, child, drawingTime);
        }
        if (alpha <= 0.004f) {
            return false;
        }
        final int save = canvas.save();
        canvas.translate(dx, dy);
        if (alpha < 1f) {
            // a layer only as big as the child (and a little room for what it draws past its edges)
            final int pad = dp(24);
            canvas.saveLayerAlpha(child.getLeft() - pad, child.getTop() - pad, child.getRight() + pad, child.getBottom() + pad, (int) (255 * alpha), Canvas.ALL_SAVE_FLAG);
        }
        final float scale = 0.86f + 0.14f * alpha;
        if (scale != 1f) {
            canvas.scale(scale, scale, child.getX() + child.getWidth() / 2f, child.getY() + child.getHeight() / 2f);
        }
        final boolean result = super.drawChild(canvas, child, drawingTime);
        canvas.restoreToCount(save);
        return result;
    }

    // endregion

    public void setReactionsTransitionProgress(float v) {
        popupWindowLayout.setReactionsTransitionProgress(v);
        if (bottomView != null) {
            bottomView.setAlpha(v);
            float scale = 0.5f + v * 0.5f;
            bottomView.setPivotX(bottomView.getMeasuredWidth());
            bottomView.setPivotY(0);
            bottomViewReactionsOffset = -popupWindowLayout.getMeasuredHeight() * (1f - v);
            updateBottomViewPosition();
            bottomView.setScaleX(scale);
            bottomView.setScaleY(scale);
        }
    }
}
