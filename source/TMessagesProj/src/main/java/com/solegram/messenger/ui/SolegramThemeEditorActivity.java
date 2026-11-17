/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.graphics.ColorUtils;

import com.solegram.messenger.SolegramThemeColors;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.ThemePreviewMessagesCell;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.Paint.ColorPickerBottomSheet;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;
import org.telegram.ui.ThemeActivity;
import org.telegram.ui.WallpapersListActivity;

import java.util.ArrayList;

/**
 * Solegram: the theme editor - "Edit theme" in the appearance settings. The current theme comes first: a live
 * preview, quick palettes that recolour it in one tap, then the parts of the interface as round colour tiles,
 * grouped by where they are (general, header, chat list, chat, controls). The wallpaper and the ready-made themes
 * are rows below. Light and dark themes keep their own colours ({@link SolegramThemeColors}).
 */
public class SolegramThemeEditorActivity extends UniversalFragment implements NotificationCenter.NotificationCenterDelegate {

    private static final int ID_READY_THEMES = 1;
    private static final int ID_WALLPAPER = 2;
    private static final int ID_RESET_ALL = 3;

    private static final int[] SECTION_TITLES = {
        R.string.SG_ThemeSectionGeneral, R.string.SG_ThemeSectionHeader, R.string.SG_ThemeSectionChats,
        R.string.SG_ThemeSectionChat, R.string.SG_ThemeSectionControls
    };

    private FrameLayout previewContainer;
    private ThemePreviewMessagesCell preview;
    private PaletteRow paletteRow;
    private final SlotGrid[] grids = new SlotGrid[SECTION_TITLES.length];

    @Override
    public boolean onFragmentCreate() {
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.didSetNewTheme);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.didSetNewTheme);
        AndroidUtilities.cancelRunOnUIThread(applyPending);
        if (pendingSlot != null) {
            applyPending.run(); // the last colour of a drag is kept
        }
        super.onFragmentDestroy();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.didSetNewTheme) {
            // another theme, or the colours changed: the tiles and the preview follow, the list stays as it is
            for (SlotGrid grid : grids) {
                if (grid != null) {
                    grid.update();
                }
            }
            if (paletteRow != null) {
                paletteRow.update();
            }
            if (preview != null) {
                invalidateAll(preview);
            }
            if (listView != null && listView.adapter != null) {
                listView.adapter.update(true); // the header (light or dark) and the reset row
            }
        }
    }

    @Override
    protected CharSequence getTitle() {
        return getString(R.string.SG_ThemeEdit);
    }

    @Override
    public View createView(Context context) {
        super.createView(context);
        listView.setSections(); // rounded section cards, as on the other Solegram pages
        return fragmentView;
    }

    private static boolean dark() {
        return Theme.isCurrentThemeDark();
    }

    private static void invalidateAll(View view) {
        view.invalidate();
        if (view instanceof ViewGroup) {
            final ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                invalidateAll(group.getChildAt(i));
            }
        }
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        final Context context = getContext();
        if (context == null) {
            return;
        }
        // the current theme first, as it looks now, and the palettes that recolour it in one tap
        items.add(UItem.asHeader(getString(dark() ? R.string.SG_ThemeEditingDark : R.string.SG_ThemeEditingLight)));
        if (previewContainer == null) {
            previewContainer = new FrameLayout(context);
            preview = new ThemePreviewMessagesCell(context, getParentLayout(), 0);
            preview.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            previewContainer.addView(preview, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }
        items.add(UItem.asCustom(previewContainer));
        if (paletteRow == null) {
            paletteRow = new PaletteRow(context);
        }
        items.add(UItem.asCustom(paletteRow));
        items.add(UItem.asShadow(getString(R.string.SG_ThemeEditHint)));

        for (int section = 0; section < SECTION_TITLES.length; section++) {
            items.add(UItem.asHeader(getString(SECTION_TITLES[section])));
            if (grids[section] == null) {
                grids[section] = new SlotGrid(context, section);
            }
            items.add(UItem.asCustom(grids[section]));
            if (section == SolegramThemeColors.SECTION_CHAT) {
                items.add(UItem.asButton(ID_WALLPAPER, R.drawable.msg_background, getString(R.string.SG_ThemeWallpaper)));
            }
            items.add(UItem.asShadow(null));
        }

        items.add(UItem.asButton(ID_READY_THEMES, R.drawable.msg_colors, getString(R.string.SG_ThemeReady)));
        if (SolegramThemeColors.anySet(dark())) {
            items.add(UItem.asButton(ID_RESET_ALL, R.drawable.msg_reset, getString(R.string.SG_ThemeResetAll)).red());
        }
        items.add(UItem.asShadow(getString(R.string.SG_ThemeReadyInfo)));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_READY_THEMES) {
            presentFragment(new ThemeActivity(ThemeActivity.THEME_TYPE_THEMES_BROWSER));
        } else if (item.id == ID_WALLPAPER) {
            presentFragment(new WallpapersListActivity(WallpapersListActivity.TYPE_ALL));
        } else if (item.id == ID_RESET_ALL) {
            confirmResetAll();
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }

    // region colours

    private SolegramThemeColors.Slot pendingSlot;
    private int pendingColor;
    private final Runnable applyPending = () -> {
        if (pendingSlot != null) {
            SolegramThemeColors.set(dark(), pendingSlot.id, pendingColor);
            pendingSlot = null;
            refreshTheme();
        }
    };

    private static void refreshTheme() {
        Theme.refreshThemeColors(false, true); // not the wallpaper: only colours changed
    }

    private void openPicker(SolegramThemeColors.Slot slot) {
        if (getContext() == null) {
            return;
        }
        final int current = Theme.getColor(slot.shownKey);
        new ColorPickerBottomSheet(getContext(), getResourceProvider())
            .setPipetteDelegate(NO_PIPETTE)
            .setColor(current == 0 ? Theme.getColor(Theme.key_chat_outBubble) : current)
            .setColorListener(color -> {
                // the screen follows the picker; the theme is built at most every 60 ms while dragging
                pendingSlot = slot;
                pendingColor = color;
                AndroidUtilities.cancelRunOnUIThread(applyPending);
                AndroidUtilities.runOnUIThread(applyPending, 60);
            })
            .show();
    }

    private void slotMenu(SolegramThemeColors.Slot slot, View anchor) {
        ItemOptions.makeOptions(this, anchor)
            .add(R.drawable.msg_edit, getString(slot.title), () -> openPicker(slot))
            .addIf(SolegramThemeColors.isSet(dark(), slot.id), R.drawable.msg_reset, getString(R.string.SG_ThemeResetColor), () -> {
                SolegramThemeColors.set(dark(), slot.id, null);
                refreshTheme();
            })
            .show();
    }

    private void confirmResetAll() {
        if (getParentActivity() == null) {
            return;
        }
        final AlertDialog dialog = new AlertDialog.Builder(getParentActivity(), getResourceProvider())
            .setTitle(getString(R.string.SG_ThemeResetAll))
            .setMessage(getString(R.string.SG_ThemeResetAllConfirm))
            .setPositiveButton(getString(R.string.SG_ThemeResetAllButton), (d, w) -> {
                SolegramThemeColors.resetAll(dark());
                refreshTheme();
            })
            .setNegativeButton(getString(R.string.Cancel), null)
            .create();
        showDialog(dialog);
        final View button = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (button instanceof TextView) {
            ((TextView) button).setTextColor(Theme.getColor(Theme.key_text_RedBold));
        }
    }

    private static final ColorPickerBottomSheet.PipetteDelegate NO_PIPETTE = new ColorPickerBottomSheet.PipetteDelegate() {
        @Override
        public void onStartColorPipette() {
        }

        @Override
        public void onStopColorPipette() {
        }

        @Override
        public ViewGroup getContainerView() {
            return null;
        }

        @Override
        public View getSnapshotDrawingView() {
            return null;
        }

        @Override
        public void onDrawImageOverCanvas(Bitmap bitmap, Canvas canvas) {
        }

        @Override
        public boolean isPipetteVisible() {
            return false;
        }

        @Override
        public boolean isPipetteAvailable() {
            return false;
        }

        @Override
        public void onColorSelected(int color) {
        }
    };

    // endregion

    // region views

    /** A round colour with a thin ring; "none" as a crossed circle; a small dot when it was changed; a check when picked. */
    static class Swatch extends View {
        final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint second = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint mark = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint check = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint outer = new Paint(Paint.ANTI_ALIAS_FLAG);
        final android.graphics.Path checkPath = new android.graphics.Path();
        int color;
        int halfColor; // a palette: the bubble's colour in the right half
        boolean none, changed, selected;

        Swatch(Context context) {
            super(context);
            ring.setStyle(Paint.Style.STROKE);
            check.setStyle(Paint.Style.STROKE);
            check.setStrokeCap(Paint.Cap.ROUND);
            check.setStrokeJoin(Paint.Join.ROUND);
            outer.setStyle(Paint.Style.STROKE);
        }

        void set(int color, int halfColor, boolean none, boolean changed, boolean selected) {
            this.color = color | 0xFF000000;
            this.halfColor = halfColor;
            this.none = none || color == 0;
            this.changed = changed;
            this.selected = selected;
            invalidate();
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            final float cx = getWidth() / 2f, cy = getHeight() / 2f;
            final float r = Math.min(cx, cy) - dp(selected ? 4 : 1.5f);
            ring.setStrokeWidth(dp(1));
            ring.setColor(Theme.multAlpha(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText), 0.14f));
            if (selected) {
                // a ring round the picked palette, a gap between them
                outer.setStrokeWidth(dp(2));
                outer.setColor(color);
                canvas.drawCircle(cx, cy, Math.min(cx, cy) - dp(1), outer);
            }
            if (none) {
                ring.setStrokeWidth(dp(1.5f));
                ring.setColor(Theme.multAlpha(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText), 0.7f));
                canvas.drawCircle(cx, cy, r, ring);
                final float d = r * 0.7071f;
                canvas.drawLine(cx - d, cy + d, cx + d, cy - d, ring);
                return;
            }
            fill.setColor(color);
            canvas.drawCircle(cx, cy, r, fill);
            if (halfColor != 0) {
                second.setColor(halfColor | 0xFF000000);
                AndroidUtilities.rectTmp.set(cx - r, cy - r, cx + r, cy + r);
                canvas.drawArc(AndroidUtilities.rectTmp, -45, 180, true, second);
            }
            canvas.drawCircle(cx, cy, r, ring);
            if (changed) {
                // a small dot in the accent at the top right: this one is yours, not the theme's
                final float dr = dp(4.5f);
                final float dx = cx + r * 0.72f, dy = cy - r * 0.72f;
                mark.setColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                canvas.drawCircle(dx, dy, dr + dp(2), mark);
                mark.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4));
                canvas.drawCircle(dx, dy, dr, mark);
            }
            if (selected) {
                check.setStrokeWidth(dp(2.2f));
                check.setColor(ColorUtils.calculateLuminance(color) > 0.6f ? 0xFF000000 : 0xFFFFFFFF);
                final float s = r * 0.42f;
                checkPath.rewind();
                checkPath.moveTo(cx - s, cy);
                checkPath.lineTo(cx - s * 0.3f, cy + s * 0.7f);
                checkPath.lineTo(cx + s, cy - s * 0.6f);
                canvas.drawPath(checkPath, check);
            }
        }
    }

    /** One tile: the swatch with a short name under it. */
    private class Tile extends LinearLayout {
        final Swatch swatch;
        final TextView label;

        Tile(Context context) {
            super(context);
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER_HORIZONTAL);
            setPadding(0, dp(8), 0, dp(6));
            swatch = new Swatch(context);
            addView(swatch, LayoutHelper.createLinear(46, 46, Gravity.CENTER_HORIZONTAL));
            label = new TextView(context);
            label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            label.setGravity(Gravity.CENTER);
            label.setMaxLines(2);
            label.setEllipsize(TextUtils.TruncateAt.END);
            addView(label, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 2, 6, 2, 0));
        }

        void colors() {
            label.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            setBackground(Theme.createRadSelectorDrawable(Theme.getColor(Theme.key_listSelector), 14, 14));
        }
    }

    /** A section's parts of the interface as tiles, four in a row. */
    private class SlotGrid extends ViewGroup {
        private static final int COLUMNS = 4;
        private final ArrayList<SolegramThemeColors.Slot> slots = new ArrayList<>();
        private final ArrayList<Tile> tiles = new ArrayList<>();

        SlotGrid(Context context, int section) {
            super(context);
            for (SolegramThemeColors.Slot slot : SolegramThemeColors.SLOTS) {
                if (slot.section != section) {
                    continue;
                }
                slots.add(slot);
                final Tile tile = new Tile(context);
                tile.label.setText(getString(slot.shortTitle));
                tile.setContentDescription(getString(slot.title));
                tile.setOnClickListener(v -> openPicker(slot));
                tile.setOnLongClickListener(v -> {
                    v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                    slotMenu(slot, v);
                    return true;
                });
                tiles.add(tile);
                addView(tile);
            }
            setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            update();
        }

        void update() {
            final boolean dark = dark();
            for (int i = 0; i < slots.size(); i++) {
                final SolegramThemeColors.Slot slot = slots.get(i);
                final Tile tile = tiles.get(i);
                final boolean set = SolegramThemeColors.isSet(dark, slot.id);
                tile.swatch.set(Theme.getColor(slot.shownKey), 0, "outGradient".equals(slot.id) && !set, set, false);
                tile.colors();
            }
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            final int width = MeasureSpec.getSize(widthMeasureSpec);
            final int pad = dp(8);
            final int tileWidth = (width - pad * 2) / COLUMNS;
            int rowHeight = 0;
            for (Tile tile : tiles) {
                tile.measure(MeasureSpec.makeMeasureSpec(tileWidth, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                rowHeight = Math.max(rowHeight, tile.getMeasuredHeight());
            }
            for (Tile tile : tiles) {
                tile.measure(MeasureSpec.makeMeasureSpec(tileWidth, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(rowHeight, MeasureSpec.EXACTLY));
            }
            final int rows = (tiles.size() + COLUMNS - 1) / COLUMNS;
            setMeasuredDimension(width, rows * rowHeight + dp(4) * 2);
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            final int pad = dp(8);
            final int tileWidth = (getWidth() - pad * 2) / COLUMNS;
            for (int i = 0; i < tiles.size(); i++) {
                final Tile tile = tiles.get(i);
                final int x = pad + (i % COLUMNS) * tileWidth;
                final int y = dp(4) + (i / COLUMNS) * tile.getMeasuredHeight();
                tile.layout(x, y, x + tile.getMeasuredWidth(), y + tile.getMeasuredHeight());
            }
        }
    }

    /** The quick palettes in a row that scrolls: "as in the theme" first, then the colours. */
    private class PaletteRow extends HorizontalScrollView {
        private final LinearLayout row;
        private final ArrayList<Tile> tiles = new ArrayList<>();

        PaletteRow(Context context) {
            super(context);
            setHorizontalScrollBarEnabled(false);
            setClipToPadding(false);
            setPadding(dp(8), 0, dp(8), 0);
            row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            addView(row, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            for (int i = -1; i < SolegramThemeColors.PALETTES.length; i++) {
                final int accent = i < 0 ? 0 : SolegramThemeColors.PALETTES[i];
                final Tile tile = new Tile(context);
                tile.label.setText(getString(i < 0 ? R.string.SG_ThemePaletteOriginal : SolegramThemeColors.PALETTE_NAMES[i]));
                tile.setOnClickListener(v -> {
                    SolegramThemeColors.applyPalette(dark(), accent);
                    refreshTheme();
                });
                tiles.add(tile);
                row.addView(tile, LayoutHelper.createLinear(78, LayoutHelper.WRAP_CONTENT));
            }
            setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            update();
        }

        void update() {
            final boolean dark = dark();
            final int current = SolegramThemeColors.currentPalette(dark);
            final boolean anyPalettePart = SolegramThemeColors.isSet(dark, "accent") || SolegramThemeColors.isSet(dark, "outBubble");
            for (int i = 0; i < tiles.size(); i++) {
                final Tile tile = tiles.get(i);
                if (i == 0) {
                    // the theme's own accent and bubble, picked when no palette or accent of ours is on
                    tile.swatch.set(Theme.getColor(Theme.key_featuredStickers_addButton), Theme.getColor(Theme.key_chat_outBubble), false, false, !anyPalettePart);
                } else {
                    final int accent = SolegramThemeColors.PALETTES[i - 1];
                    tile.swatch.set(accent, SolegramThemeColors.paletteBubble(accent, dark), false, false, current == accent);
                }
                tile.colors();
            }
        }
    }

    // endregion
}
