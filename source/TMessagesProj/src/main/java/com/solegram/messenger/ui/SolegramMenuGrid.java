package com.solegram.messenger.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.ui.ActionBar.ActionBarMenuSubItem;

import java.util.ArrayList;

/**
 * Solegram: the message menu's actions two in a row. The shown ones (whatever is visible now: the compact menu's
 * "More", an item hidden until the language is known) pair up in order; one left over takes the whole row.
 */
public class SolegramMenuGrid extends ViewGroup {

    /** The widest the panel gets (a tablet, a phone on its side). */
    private static final int MAX_WIDTH_DP = 360;
    private static final int PAD_H_DP = 6, PAD_V_DP = 4, GAP_DP = 4, ROW_GAP_DP = 2;

    private final int width;

    public SolegramMenuGrid(Context context, int availableWidth) {
        super(context);
        width = Math.max(dp(240), Math.min(dp(MAX_WIDTH_DP), availableWidth));
    }

    /**
     * Puts the runs of action rows of the menu ({@code rows}: the panel's own list) into grids. Only the rows given in
     * {@code items} move; anything else (a gap, the "seen by" row, a hint) stays where it was, between the grids.
     */
    public static void apply(ViewGroup rows, ActionBarMenuSubItem[] items, int availableWidth) {
        if (rows == null || items == null) {
            return;
        }
        final ArrayList<View> run = new ArrayList<>();
        for (int i = 0; i <= rows.getChildCount(); i++) {
            final View child = i < rows.getChildCount() ? rows.getChildAt(i) : null;
            if (child != null && isItem(child, items)) {
                run.add(child);
                continue;
            }
            if (run.size() >= 2) {
                final int at = rows.indexOfChild(run.get(0));
                final SolegramMenuGrid grid = new SolegramMenuGrid(rows.getContext(), availableWidth);
                for (int k = 0; k < run.size(); k++) {
                    final View item = run.get(k);
                    rows.removeView(item);
                    style((ActionBarMenuSubItem) item);
                    grid.addView(item);
                }
                rows.addView(grid, at, new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
                i = at; // the grid takes the run's place
            }
            run.clear();
        }
    }

    private static boolean isItem(View child, ActionBarMenuSubItem[] items) {
        if (!(child instanceof ActionBarMenuSubItem)) {
            return false;
        }
        for (ActionBarMenuSubItem item : items) {
            if (item == child) {
                return true;
            }
        }
        return false;
    }

    /** A row made a tile: narrower paddings, the label in up to two lines, its own rounded highlight. */
    public static void style(ActionBarMenuSubItem cell) {
        cell.setMinimumWidth(0);
        final boolean rtl = LocaleController.isRTL;
        cell.setPadding(dp(rtl ? 8 : 12), 0, dp(rtl ? 12 : 8), 0);
        final boolean subtext = cell.subtextView != null && cell.subtextView.getVisibility() == View.VISIBLE;
        if (cell.textView != null) {
            final boolean icon = cell.getImageView() != null && cell.getImageView().getVisibility() == View.VISIBLE;
            cell.textView.setSingleLine(false);
            cell.textView.setMinLines(1);
            cell.textView.setMaxLines(subtext ? 1 : 2);
            cell.textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            cell.textView.setGravity((rtl ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL);
            cell.textView.setPadding(rtl ? 0 : dp(icon ? 34 : 0), 0, rtl ? dp(icon ? 34 : 0) : 0, 0);
        }
        if (subtext) {
            cell.subtextView.setPadding(rtl ? 0 : dp(34), 0, rtl ? dp(34) : 0, 0);
        } else {
            cell.setItemHeight(48);
        }
        cell.updateSelectorBackground(true, true, 14);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = width;
        final int mode = MeasureSpec.getMode(widthMeasureSpec);
        if (mode == MeasureSpec.EXACTLY) {
            w = MeasureSpec.getSize(widthMeasureSpec);
        } else if (mode == MeasureSpec.AT_MOST) {
            w = Math.min(w, MeasureSpec.getSize(widthMeasureSpec));
        }
        final int inner = w - dp(PAD_H_DP) * 2;
        final int column = (inner - dp(GAP_DP)) / 2;
        final int unspecified = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
        final ArrayList<View> shown = shown();
        int height = 0;
        for (int i = 0; i < shown.size(); i += 2) {
            final View a = shown.get(i);
            final View b = i + 1 < shown.size() ? shown.get(i + 1) : null;
            a.measure(MeasureSpec.makeMeasureSpec(b == null ? inner : column, MeasureSpec.EXACTLY), unspecified);
            int row = a.getMeasuredHeight();
            if (b != null) {
                b.measure(MeasureSpec.makeMeasureSpec(column, MeasureSpec.EXACTLY), unspecified);
                row = Math.max(row, b.getMeasuredHeight());
            }
            height += row + (i > 0 ? dp(ROW_GAP_DP) : 0);
        }
        if (!shown.isEmpty()) {
            height += dp(PAD_V_DP) * 2;
        }
        setMeasuredDimension(w, height);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        final int w = r - l;
        final int inner = w - dp(PAD_H_DP) * 2;
        final int column = (inner - dp(GAP_DP)) / 2;
        final boolean rtl = LocaleController.isRTL;
        final ArrayList<View> shown = shown();
        int y = dp(PAD_V_DP);
        for (int i = 0; i < shown.size(); i += 2) {
            final View first = shown.get(i);
            final View second = i + 1 < shown.size() ? shown.get(i + 1) : null;
            if (second == null) {
                first.layout(dp(PAD_H_DP), y, dp(PAD_H_DP) + inner, y + first.getMeasuredHeight());
                y += first.getMeasuredHeight();
            } else {
                final int left = dp(PAD_H_DP), right = dp(PAD_H_DP) + column + dp(GAP_DP);
                final int row = Math.max(first.getMeasuredHeight(), second.getMeasuredHeight());
                final View atLeft = rtl ? second : first, atRight = rtl ? first : second;
                atLeft.layout(left, y + (row - atLeft.getMeasuredHeight()) / 2, left + column, y + (row + atLeft.getMeasuredHeight()) / 2);
                atRight.layout(right, y + (row - atRight.getMeasuredHeight()) / 2, right + column, y + (row + atRight.getMeasuredHeight()) / 2);
                y += row;
            }
            y += dp(ROW_GAP_DP);
        }
    }

    private ArrayList<View> shown() {
        final ArrayList<View> shown = new ArrayList<>();
        for (int i = 0; i < getChildCount(); i++) {
            final View child = getChildAt(i);
            if (child.getVisibility() != GONE) {
                shown.add(child);
            }
        }
        return shown;
    }

    /** The room the panel can take in a window of this width (its shadow and the screen margins left out). */
    public static int availableWidth(int windowWidth) {
        return windowWidth - dp(44);
    }

    /** Whether the grid is in use for the menu. */
    public static boolean enabled() {
        return com.solegram.messenger.SolegramConfig.gridMenus;
    }
}
