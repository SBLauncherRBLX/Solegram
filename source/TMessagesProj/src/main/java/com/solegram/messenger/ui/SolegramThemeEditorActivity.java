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
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;

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
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;
import org.telegram.ui.Components.UniversalRecyclerView;
import org.telegram.ui.ThemeActivity;
import org.telegram.ui.WallpapersListActivity;

import java.util.ArrayList;

/**
 * Solegram: the theme editor - "Edit theme" in the appearance settings. The current theme comes first: a live
 * preview, then the parts of the interface grouped by where they are (general, header, chat list, chat, controls),
 * one colour each; the ready-made themes and the wallpaper are one row each below. Light and dark themes keep their
 * own colours ({@link SolegramThemeColors}).
 */
public class SolegramThemeEditorActivity extends UniversalFragment implements NotificationCenter.NotificationCenterDelegate {

    private static final int ID_READY_THEMES = 1;
    private static final int ID_WALLPAPER = 2;
    private static final int ID_RESET_ALL = 3;
    private static final int ID_SLOT_BASE = 1000;

    private static final int[] SECTION_TITLES = {
        R.string.SG_ThemeSectionGeneral, R.string.SG_ThemeSectionHeader, R.string.SG_ThemeSectionChats,
        R.string.SG_ThemeSectionChat, R.string.SG_ThemeSectionControls
    };
    private static final int[] SECTION_INFOS = {
        R.string.SG_ThemeSectionGeneralInfo, 0, 0, 0, 0
    };

    private FrameLayout previewContainer;
    private ThemePreviewMessagesCell preview;

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
            // another theme, or the colours changed: the swatches and the preview follow
            if (listView != null && listView.adapter != null) {
                listView.adapter.update(true);
            }
            invalidatePreview();
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

    private View previewView(Context context) {
        if (previewContainer == null) {
            previewContainer = new FrameLayout(context);
            preview = new ThemePreviewMessagesCell(context, getParentLayout(), 0);
            preview.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            previewContainer.addView(preview, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }
        if (previewContainer.getParent() instanceof ViewGroup) {
            ((ViewGroup) previewContainer.getParent()).removeView(previewContainer);
        }
        return previewContainer;
    }

    private void invalidatePreview() {
        if (preview != null) {
            invalidateAll(preview);
        }
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
        final boolean dark = dark();
        // the current theme first, as it looks now
        items.add(UItem.asHeader(getString(dark ? R.string.SG_ThemeEditingDark : R.string.SG_ThemeEditingLight)));
        if (context != null) {
            items.add(UItem.asCustom(previewView(context)));
        }
        items.add(UItem.asShadow(getString(R.string.SG_ThemeEditInfo)));

        for (int section = 0; section < SECTION_TITLES.length; section++) {
            items.add(UItem.asHeader(getString(SECTION_TITLES[section])));
            for (int i = 0; i < SolegramThemeColors.SLOTS.length; i++) {
                final SolegramThemeColors.Slot slot = SolegramThemeColors.SLOTS[i];
                if (slot.section != section) {
                    continue;
                }
                items.add(ColorCell.Factory.of(ID_SLOT_BASE + i, slot, dark));
            }
            if (section == SolegramThemeColors.SECTION_CHAT) {
                items.add(UItem.asButton(ID_WALLPAPER, R.drawable.msg_background, getString(R.string.SG_ThemeWallpaper)));
            }
            items.add(UItem.asShadow(SECTION_INFOS[section] != 0 ? getString(SECTION_INFOS[section]) : null));
        }

        items.add(UItem.asButton(ID_READY_THEMES, R.drawable.msg_colors, getString(R.string.SG_ThemeReady)));
        items.add(UItem.asShadow(getString(R.string.SG_ThemeReadyInfo)));

        if (SolegramThemeColors.anySet(dark)) {
            items.add(UItem.asButton(ID_RESET_ALL, R.drawable.msg_reset, getString(R.string.SG_ThemeResetAll)).red());
            items.add(UItem.asShadow(null));
        }
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_READY_THEMES) {
            presentFragment(new ThemeActivity(ThemeActivity.THEME_TYPE_THEMES_BROWSER));
        } else if (item.id == ID_WALLPAPER) {
            presentFragment(new WallpapersListActivity(WallpapersListActivity.TYPE_ALL));
        } else if (item.id == ID_RESET_ALL) {
            confirmResetAll();
        } else if (item.id >= ID_SLOT_BASE && item.id < ID_SLOT_BASE + SolegramThemeColors.SLOTS.length) {
            openPicker(SolegramThemeColors.SLOTS[item.id - ID_SLOT_BASE]);
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        if (item.id >= ID_SLOT_BASE && item.id < ID_SLOT_BASE + SolegramThemeColors.SLOTS.length) {
            final SolegramThemeColors.Slot slot = SolegramThemeColors.SLOTS[item.id - ID_SLOT_BASE];
            if (!SolegramThemeColors.isSet(dark(), slot.id)) {
                return false;
            }
            ItemOptions.makeOptions(this, view)
                .add(R.drawable.msg_reset, getString(R.string.SG_ThemeResetColor), () -> resetSlot(slot))
                .show();
            return true;
        }
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
            .setColor(current == 0 ? 0xFF3390EC : current)
            .setColorListener(color -> {
                // the screen follows the picker at once; the theme is built at most every 60 ms while dragging
                pendingSlot = slot;
                pendingColor = color;
                AndroidUtilities.cancelRunOnUIThread(applyPending);
                AndroidUtilities.runOnUIThread(applyPending, 60);
            })
            .show();
    }

    private void resetSlot(SolegramThemeColors.Slot slot) {
        SolegramThemeColors.set(dark(), slot.id, null);
        refreshTheme();
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

    // region the colour row

    /** A part of the interface: its colour in a circle, what it is and where; a reset button when it is changed. */
    public static class ColorCell extends LinearLayout implements Theme.Colorable {

        private final Theme.ResourcesProvider resourcesProvider;
        private final SwatchView swatch;
        private final TextView titleView;
        private final TextView infoView;
        private final ImageView resetView;
        private boolean divider;
        private Runnable onReset;

        public ColorCell(Context context, Theme.ResourcesProvider resourcesProvider) {
            super(context);
            this.resourcesProvider = resourcesProvider;
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setWillNotDraw(false);

            swatch = new SwatchView(context);
            addView(swatch, LayoutHelper.createLinear(30, 30, Gravity.CENTER_VERTICAL, 20, 0, 0, 0));

            final LinearLayout texts = new LinearLayout(context);
            texts.setOrientation(VERTICAL);
            titleView = new TextView(context);
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            titleView.setSingleLine();
            titleView.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            infoView = new TextView(context);
            infoView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            infoView.setMaxLines(2);
            infoView.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(infoView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
            addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 18, 10, 8, 10));

            resetView = new ImageView(context);
            resetView.setScaleType(ImageView.ScaleType.CENTER);
            resetView.setImageResource(R.drawable.msg_reset);
            resetView.setContentDescription(getString(R.string.SG_ThemeResetColor));
            resetView.setOnClickListener(v -> {
                if (onReset != null) {
                    onReset.run();
                }
            });
            addView(resetView, LayoutHelper.createLinear(44, 44, Gravity.CENTER_VERTICAL, 0, 0, 8, 0));
            updateColors();
        }

        void set(SolegramThemeColors.Slot slot, boolean changed, boolean divider, Runnable onReset) {
            titleView.setText(getString(slot.title));
            infoView.setText(getString(slot.info));
            swatch.setColor(Theme.getColor(slot.shownKey, resourcesProvider), slot.id.equals("outGradient") && !changed);
            resetView.setVisibility(changed ? VISIBLE : GONE);
            this.onReset = onReset;
            if (this.divider != divider) {
                this.divider = divider;
                invalidate();
            }
            updateColors();
        }

        @Override
        public void updateColors() {
            titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
            infoView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
            resetView.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4, resourcesProvider), PorterDuff.Mode.SRC_IN));
            resetView.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector, resourcesProvider), Theme.RIPPLE_MASK_CIRCLE_20DP));
            swatch.ring.setColor(Theme.getColor(Theme.key_divider, resourcesProvider));
            invalidate();
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            super.onDraw(canvas);
            if (divider) {
                canvas.drawLine(dp(68), getHeight() - 1, getWidth(), getHeight() - 1, Theme.getThemePaint(Theme.key_paint_divider, resourcesProvider));
            }
        }

        /** The colour in a circle with a thin ring; "none" as a crossed circle (the gradient that is not set). */
        static class SwatchView extends View {
            final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
            final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
            private boolean none;

            SwatchView(Context context) {
                super(context);
                ring.setStyle(Paint.Style.STROKE);
                ring.setStrokeWidth(dp(1.5f));
            }

            void setColor(int color, boolean none) {
                fill.setColor(color | 0xFF000000);
                this.none = none || color == 0;
                invalidate();
            }

            @Override
            protected void onDraw(@NonNull Canvas canvas) {
                final float cx = getWidth() / 2f, cy = getHeight() / 2f, r = Math.min(cx, cy) - dp(1);
                if (none) {
                    canvas.drawCircle(cx, cy, r, ring);
                    final float d = r * 0.7071f;
                    canvas.drawLine(cx - d, cy + d, cx + d, cy - d, ring);
                    return;
                }
                canvas.drawCircle(cx, cy, r, fill);
                canvas.drawCircle(cx, cy, r, ring);
            }
        }

        public static class Factory extends UItem.UItemFactory<ColorCell> {
            static { setup(new Factory()); }

            @Override
            public ColorCell createView(Context context, RecyclerListView listView, int currentAccount, int classGuid, Theme.ResourcesProvider resourcesProvider) {
                return new ColorCell(context, resourcesProvider);
            }

            @Override
            public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView listView) {
                final SolegramThemeColors.Slot slot = (SolegramThemeColors.Slot) item.object;
                final boolean dark = Theme.isCurrentThemeDark();
                ((ColorCell) view).set(slot, SolegramThemeColors.isSet(dark, slot.id), divider, () -> {
                    SolegramThemeColors.set(dark, slot.id, null);
                    refreshTheme();
                });
            }

            public static UItem of(int id, SolegramThemeColors.Slot slot, boolean dark) {
                final UItem item = UItem.ofFactory(Factory.class);
                item.id = id;
                item.object = slot;
                // what the row shows: a new colour or a reset rebinds it
                item.intValue = Theme.getColor(slot.shownKey);
                item.checked = SolegramThemeColors.isSet(dark, slot.id);
                item.text = slot.id;
                return item;
            }
        }
    }

    // endregion
}
