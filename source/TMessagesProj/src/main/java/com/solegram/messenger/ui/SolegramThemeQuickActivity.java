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
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.text.TextPaint;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.core.graphics.ColorUtils;

import com.solegram.messenger.SolegramThemeColors;
import com.solegram.messenger.SolegramThemeGenerator;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.Paint.ColorPickerBottomSheet;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;
import org.telegram.ui.ThemeActivity;
import org.telegram.ui.WallpapersListActivity;

import java.util.ArrayList;
import java.util.Map;

/**
 * Solegram: "Edit theme" - a theme from a few colours. Pick a main colour, a second one (or let it be worked out)
 * and a style; Solegram makes a light and a dark theme of them (every shade worked out, see
 * {@link SolegramThemeGenerator}), and "Create theme" puts it on with Telegram's circle reveal. Every colour one by
 * one is in the "⋮" menu (the advanced editor, {@link SolegramThemeEditorActivity}).
 */
public class SolegramThemeQuickActivity extends UniversalFragment implements NotificationCenter.NotificationCenterDelegate {

    private static final int ID_WALLPAPER = 1;
    private static final int ID_READY = 2;
    private static final int MENU_OPTIONS = 10;

    private int primary = SolegramThemeGenerator.savedPrimary();
    private int secondary = SolegramThemeGenerator.savedSecondary();
    private int background = SolegramThemeGenerator.savedBackground();

    private static final int ROW_MAIN = 0, ROW_SECOND = 1, ROW_BACKGROUND = 2;

    private int valueOf(int row) {
        return row == ROW_MAIN ? primary : row == ROW_SECOND ? secondary : background;
    }

    private void setValue(int row, int value) {
        if (row == ROW_MAIN) {
            primary = value;
        } else if (row == ROW_SECOND) {
            secondary = value;
        } else {
            background = value;
        }
    }

    private static int[] presetsOf(int row) {
        return row == ROW_BACKGROUND ? SolegramThemeGenerator.BACKGROUNDS : SolegramThemeGenerator.COLORS;
    }
    private int style = SolegramThemeGenerator.savedStyle();

    private SwatchRow primaryRow, secondaryRow, backgroundRow;
    private StyleRow styleRow;
    private FrameLayout buttonContainer;
    private ButtonWithCounterView createButton;
    private boolean creating;

    @Override
    public boolean onFragmentCreate() {
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.didSetNewTheme);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.didSetNewTheme);
        super.onFragmentDestroy();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.didSetNewTheme) {
            if (primaryRow != null) {
                primaryRow.invalidateAll();
                secondaryRow.invalidateAll();
                backgroundRow.invalidateAll();
                styleRow.invalidate();
            }
            if (listView != null && listView.adapter != null) {
                listView.adapter.update(true);
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
        final ActionBar.ActionBarMenuOnItemClick onItemClick = new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == MENU_OPTIONS) {
                    showOptions();
                }
            }
        };
        if (simpleActionBar != null) {
            menuButton = simpleActionBar.createMenu().addItem(MENU_OPTIONS, R.drawable.ic_ab_other);
            simpleActionBar.setActionBarMenuOnItemClick(onItemClick);
        } else {
            menuButton = actionBar.createMenu().addItem(MENU_OPTIONS, R.drawable.ic_ab_other);
            actionBar.setActionBarMenuOnItemClick(onItemClick);
        }
        menuButton.setContentDescription(getString(R.string.AccDescrMoreOptions));
        return fragmentView;
    }

    private View menuButton;

    private void showOptions() {
        ItemOptions.makeOptions(this, menuButton)
            .add(R.drawable.msg_customize, getString(R.string.SG_ThemeAdvanced), () -> presentFragment(new SolegramThemeEditorActivity()))
            .addIf(SolegramThemeColors.anySet(false) || SolegramThemeColors.anySet(true), R.drawable.msg_reset, getString(R.string.SG_ThemeBackToOriginal), this::confirmReset)
            .show();
    }

    private static boolean dark() {
        return Theme.isCurrentThemeDark();
    }

    private void onChanged() {
        primaryRow.invalidateAll();
        secondaryRow.invalidateAll();
        backgroundRow.invalidateAll();
        styleRow.invalidate();
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        final Context context = getContext();
        if (context == null) {
            return;
        }
        if (primaryRow == null) {
            primaryRow = new SwatchRow(context, ROW_MAIN);
            secondaryRow = new SwatchRow(context, ROW_SECOND);
            backgroundRow = new SwatchRow(context, ROW_BACKGROUND);
            styleRow = new StyleRow(context);
            buttonContainer = new FrameLayout(context);
            createButton = new ButtonWithCounterView(context, getResourceProvider()).setRound();
            createButton.setText(getString(R.string.SG_ThemeCreate), false);
            createButton.setOnClickListener(v -> create());
            buttonContainer.addView(createButton, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 48, Gravity.CENTER, 16, 14, 16, 14));
        }
        items.add(UItem.asHeader(getString(R.string.SG_ThemeMainColor)));
        items.add(UItem.asCustom(primaryRow));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(getString(R.string.SG_ThemeSecondColor)));
        items.add(UItem.asCustom(secondaryRow));
        items.add(UItem.asShadow(getString(R.string.SG_ThemeSecondColorInfo)));

        items.add(UItem.asHeader(getString(R.string.SG_ThemeBackgroundColor)));
        items.add(UItem.asCustom(backgroundRow));
        items.add(UItem.asShadow(getString(R.string.SG_ThemeBackgroundColorInfo)));

        items.add(UItem.asHeader(getString(R.string.SG_ThemeStyle)));
        items.add(UItem.asCustom(styleRow));
        items.add(UItem.asCustom(buttonContainer));
        items.add(UItem.asShadow(getString(R.string.SG_ThemeCreateInfo)));

        items.add(UItem.asButton(ID_WALLPAPER, R.drawable.msg_background, getString(R.string.SG_ThemeWallpaper)));
        items.add(UItem.asButton(ID_READY, R.drawable.msg_colors, getString(R.string.SG_ThemeReady)));
        items.add(UItem.asShadow(getString(R.string.SG_ThemeAdvancedInfo)));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_WALLPAPER) {
            presentFragment(new WallpapersListActivity(WallpapersListActivity.TYPE_ALL));
        } else if (item.id == ID_READY) {
            presentFragment(new ThemeActivity(ThemeActivity.THEME_TYPE_THEMES_BROWSER));
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }

    // region creating

    /**
     * After new colours: the screens under this one are built again (a text coloured once when its screen was made
     * kept the old colour), and this one makes its rows anew where it is scrolled to.
     */
    static void refreshScreens(UniversalFragment fragment) {
        if (fragment.getParentLayout() != null) {
            fragment.getParentLayout().rebuildAllFragmentViews(false, false);
        }
        final org.telegram.ui.Components.UniversalRecyclerView list = fragment.listView;
        if (list != null && list.adapter != null) {
            final androidx.recyclerview.widget.RecyclerView.LayoutManager layoutManager = list.getLayoutManager();
            final android.os.Parcelable state = layoutManager != null ? layoutManager.onSaveInstanceState() : null;
            list.adapter.update(false);
            list.setAdapter(list.adapter);
            list.getRecycledViewPool().clear();
            if (state != null) {
                layoutManager.onRestoreInstanceState(state);
            }
        }
        if (fragment.getFragmentView() != null) {
            fragment.getFragmentView().invalidate();
        }
    }

    private void create() {
        if (creating || getParentActivity() == null) {
            return;
        }
        creating = true;
        createButton.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        createButton.setLoading(true);
        // both: the theme stays when the app switches between day and night
        final Map<String, Integer> light = SolegramThemeGenerator.generate(primary, secondary, background, style, false);
        final Map<String, Integer> night = SolegramThemeGenerator.generate(primary, secondary, background, style, true);
        final Integer accent = (dark() ? night : light).get("accent");
        // a moment of "making" on the button, then the new colours come out of it in a circle
        AndroidUtilities.runOnUIThread(() -> {
            if (getParentActivity() == null) {
                creating = false;
                return;
            }
            createButton.setLoading(false);
            final int[] loc = new int[2];
            createButton.getLocationInWindow(loc);
            final float x = loc[0] + createButton.getWidth() / 2f, y = loc[1] + createButton.getHeight() / 2f;
            SolegramThemeReveal.start(getParentActivity(), x, y, accent != null ? accent : 0xFF3390EC, () -> {
                SolegramThemeGenerator.save(primary, secondary, background, style);
                SolegramThemeColors.setAll(false, light);
                SolegramThemeColors.setAll(true, night);
                Theme.refreshThemeColors(false, true);
                refreshScreens(this); // under the picture of the old colours: not seen
            });
            createButton.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            creating = false;
        }, 520);
    }

    private void confirmReset() {
        if (getParentActivity() == null) {
            return;
        }
        final AlertDialog dialog = new AlertDialog.Builder(getParentActivity(), getResourceProvider())
            .setTitle(getString(R.string.SG_ThemeBackToOriginal))
            .setMessage(getString(R.string.SG_ThemeResetAllConfirm))
            .setPositiveButton(getString(R.string.SG_ThemeResetAllButton), (d, w) -> {
                final int[] loc = new int[2];
                View anchor = menuButton != null ? menuButton : fragmentView;
                anchor.getLocationInWindow(loc);
                SolegramThemeReveal.start(getParentActivity(), loc[0] + anchor.getWidth() / 2f, loc[1] + anchor.getHeight() / 2f,
                    Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4), () -> {
                        SolegramThemeColors.resetAll(false);
                        SolegramThemeColors.resetAll(true);
                        Theme.refreshThemeColors(false, true);
                        refreshScreens(this);
                    });
            })
            .setNegativeButton(getString(R.string.Cancel), null)
            .create();
        showDialog(dialog);
        final View button = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (button instanceof android.widget.TextView) {
            ((android.widget.TextView) button).setTextColor(Theme.getColor(Theme.key_text_RedBold));
        }
    }

    // endregion

    // region colours to pick

    private static final ColorPickerBottomSheet.PipetteDelegate NO_PIPETTE = new ColorPickerBottomSheet.PipetteDelegate() {
        @Override public void onStartColorPipette() { }
        @Override public void onStopColorPipette() { }
        @Override public ViewGroup getContainerView() { return null; }
        @Override public View getSnapshotDrawingView() { return null; }
        @Override public void onDrawImageOverCanvas(Bitmap bitmap, Canvas canvas) { }
        @Override public boolean isPipetteVisible() { return false; }
        @Override public boolean isPipetteAvailable() { return false; }
        @Override public void onColorSelected(int color) { }
    };

    private void pickCustom(int row) {
        if (getContext() == null) {
            return;
        }
        final int start = valueOf(row) != 0 ? valueOf(row) : row == ROW_BACKGROUND ? (dark() ? 0xFF1C1C1E : 0xFFF7F7F8) : primary;
        // the same picker as the profile's own colours, for one colour
        SolegramGradientPickerSheet.single(getContext(), getResourceProvider(), start, getString(R.string.SG_ThemePickColor), color -> {
            setValue(row, color | 0xFF000000);
            onChanged();
        }).show();
    }

    private static boolean isPreset(int row, int color) {
        for (int c : presetsOf(row)) {
            if (c == color) {
                return true;
            }
        }
        return false;
    }

    /** A colour in a soft square; the picked one with a ring round it and a check. */
    private class SwatchTile extends View {
        static final int KIND_COLOR = 0, KIND_AUTO = 1, KIND_CUSTOM = 2;
        final int kind;
        final int presetColor;
        final int row;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        private final Path path = new Path();
        private float selectedProgress;

        SwatchTile(Context context, int kind, int presetColor, int row) {
            super(context);
            this.kind = kind;
            this.presetColor = presetColor;
            this.row = row;
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeCap(Paint.Cap.ROUND);
            stroke.setStrokeJoin(Paint.Join.ROUND);
            setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                if (kind == KIND_CUSTOM) {
                    pickCustom(row);
                    return;
                }
                setValue(row, kind == KIND_AUTO ? 0 : presetColor);
                onChanged();
            });
        }

        boolean isSelected0() {
            final int value = valueOf(row);
            switch (kind) {
                case KIND_AUTO:
                    return value == 0;
                case KIND_CUSTOM:
                    return value != 0 && !isPreset(row, value);
                default:
                    return value == presetColor;
            }
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            final boolean selected = isSelected0();
            // the selection ring eases in and out
            final float target = selected ? 1 : 0;
            if (selectedProgress != target) {
                selectedProgress += (target - selectedProgress) * 0.35f;
                if (Math.abs(selectedProgress - target) < 0.02f) {
                    selectedProgress = target;
                }
                invalidate();
            }
            final float cx = getWidth() / 2f, cy = getHeight() / 2f;
            final float outer = Math.min(cx, cy) - dp(1);
            final float half = outer - dp(2) - dp(3.5f) * selectedProgress;
            final float rad = half * 0.6f;
            box.set(cx - half, cy - half, cx + half, cy + half);
            final int value = valueOf(row);
            int color, split = 0;
            if (kind == KIND_CUSTOM) {
                color = value != 0 && !isPreset(row, value) ? value : 0;
            } else if (kind == KIND_AUTO && row == ROW_BACKGROUND) {
                // the backgrounds the main colour gives: the light one and the dark one
                color = SolegramThemeGenerator.generate(primary, secondary, 0, style, false).get("background");
                split = SolegramThemeGenerator.generate(primary, secondary, 0, style, true).get("background");
            } else if (kind == KIND_AUTO) {
                final float[] hsl = new float[3];
                ColorUtils.colorToHSL(primary, hsl);
                hsl[0] = (hsl[0] + 26) % 360;
                color = primary;
                split = ColorUtils.HSLToColor(hsl);
            } else {
                color = presetColor;
            }
            if (selectedProgress > 0) {
                // backgrounds are near white or black: their ring takes the main colour so it shows
                final int ring = row == ROW_BACKGROUND || color == 0 ? (row == ROW_BACKGROUND ? primary : Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4)) : color;
                stroke.setStrokeWidth(dp(2));
                stroke.setColor(Theme.multAlpha(ring | 0xFF000000, selectedProgress));
                canvas.drawRoundRect(cx - outer, cy - outer, cx + outer, cy + outer, outer * 0.6f, outer * 0.6f, stroke);
            }
            if (kind == KIND_CUSTOM && color == 0) {
                // any colour: a colour wheel with a plus
                paint.setShader(new SweepGradient(cx, cy, new int[]{0xFFFF5252, 0xFFFFD740, 0xFF69F0AE, 0xFF40C4FF, 0xFF7C4DFF, 0xFFFF4081, 0xFFFF5252}, null));
                canvas.drawRoundRect(box, rad, rad, paint);
                paint.setShader(null);
                stroke.setStrokeWidth(dp(2.4f));
                stroke.setColor(0xFFFFFFFF);
                final float p = half * 0.38f;
                canvas.drawLine(cx - p, cy, cx + p, cy, stroke);
                canvas.drawLine(cx, cy - p, cx, cy + p, stroke);
                return;
            }
            paint.setShader(null);
            paint.setColor(color | 0xFF000000);
            canvas.drawRoundRect(box, rad, rad, paint);
            if (kind == KIND_AUTO) {
                // "worked out": the two colours it gives, split, with an "A"
                canvas.save();
                path.rewind();
                path.addRoundRect(box, rad, rad, Path.Direction.CW);
                canvas.clipPath(path);
                paint.setColor(split | 0xFF000000);
                path.rewind();
                path.moveTo(box.right, box.top);
                path.lineTo(box.right, box.bottom);
                path.lineTo(box.left, box.bottom);
                path.close();
                canvas.drawPath(path, paint);
                canvas.restore();
                text.setTextSize(dp(15));
                text.setTypeface(AndroidUtilities.bold());
                text.setColor(row == ROW_BACKGROUND ? primary | 0xFF000000 : 0xFFFFFFFF);
                final float tw = text.measureText("A");
                canvas.drawText("A", cx - tw / 2f, cy + dp(5.5f), text);
            }
            stroke.setStrokeWidth(dp(1));
            stroke.setColor(Theme.multAlpha(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText), 0.12f));
            canvas.drawRoundRect(box, rad, rad, stroke);
            if (selected && kind != KIND_AUTO) {
                stroke.setStrokeWidth(dp(2.2f));
                stroke.setColor(ColorUtils.calculateLuminance(color | 0xFF000000) > 0.6f ? 0xFF000000 : 0xFFFFFFFF);
                stroke.setAlpha((int) (255 * selectedProgress));
                final float s = half * 0.36f;
                path.rewind();
                path.moveTo(cx - s, cy);
                path.lineTo(cx - s * 0.3f, cy + s * 0.7f);
                path.lineTo(cx + s, cy - s * 0.6f);
                canvas.drawPath(path, stroke);
            }
        }
    }

    /** The colours to pick from, seven in a row; "auto" first for the second colour and the background, "any colour" last. */
    private class SwatchRow extends ViewGroup {
        private static final int COLUMNS = 7;
        private final ArrayList<SwatchTile> tiles = new ArrayList<>();

        SwatchRow(Context context, int row) {
            super(context);
            if (row != ROW_MAIN) {
                tiles.add(new SwatchTile(context, SwatchTile.KIND_AUTO, 0, row));
            }
            for (int c : presetsOf(row)) {
                tiles.add(new SwatchTile(context, SwatchTile.KIND_COLOR, c, row));
            }
            tiles.add(new SwatchTile(context, SwatchTile.KIND_CUSTOM, 0, row));
            for (SwatchTile tile : tiles) {
                tile.setContentDescription(tile.kind == SwatchTile.KIND_AUTO ? getString(R.string.SG_ThemeAuto)
                    : tile.kind == SwatchTile.KIND_CUSTOM ? getString(R.string.SG_ThemeAnyColor) : null);
                addView(tile);
            }
            setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        void invalidateAll() {
            for (SwatchTile tile : tiles) {
                tile.invalidate();
            }
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            final int width = MeasureSpec.getSize(widthMeasureSpec);
            final int cell = (width - dp(16) * 2) / COLUMNS;
            for (SwatchTile tile : tiles) {
                tile.measure(MeasureSpec.makeMeasureSpec(cell, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(cell, MeasureSpec.EXACTLY));
            }
            final int rows = (tiles.size() + COLUMNS - 1) / COLUMNS;
            setMeasuredDimension(width, rows * cell + dp(10) * 2);
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            final int cell = (getWidth() - dp(16) * 2) / COLUMNS;
            for (int i = 0; i < tiles.size(); i++) {
                final int x = dp(16) + (i % COLUMNS) * cell;
                final int y = dp(10) + (i / COLUMNS) * cell;
                tiles.get(i).layout(x, y, x + cell, y + cell);
            }
        }
    }

    /** The three styles side by side, each a small picture of what it does with the colours. */
    private class StyleRow extends View {
        private final int[] names = {R.string.SG_ThemeStyleCalm, R.string.SG_ThemeStyleVivid, R.string.SG_ThemeStyleMono};
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final RectF[] cards = {new RectF(), new RectF(), new RectF()};

        StyleRow(Context context) {
            super(context);
            stroke.setStyle(Paint.Style.STROKE);
            setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            setOnTouchListener((v, e) -> {
                if (e.getAction() == android.view.MotionEvent.ACTION_UP) {
                    for (int i = 0; i < 3; i++) {
                        if (cards[i].contains(e.getX(), e.getY())) {
                            if (style != i) {
                                style = i;
                                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                                onChanged();
                            }
                            break;
                        }
                    }
                }
                return true;
            });
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(132));
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            final boolean dark = dark();
            final float gap = dp(10), side = dp(16);
            final float w = (getWidth() - side * 2 - gap * 2) / 3f;
            for (int i = 0; i < 3; i++) {
                final float l = side + i * (w + gap), t = dp(8), b = getHeight() - dp(8);
                cards[i].set(l, t, l + w, b);
                // a tiny screen in that style: its background, two bubbles and an accent pill
                final Map<String, Integer> g = SolegramThemeGenerator.generate(primary, secondary, background, i, dark);
                rect.set(l, t, l + w, b - dp(26));
                paint.setColor(g.get("background"));
                canvas.drawRoundRect(rect, dp(14), dp(14), paint);
                final float bw = w * 0.56f;
                rect.set(l + dp(8), t + dp(10), l + dp(8) + bw, t + dp(28));
                paint.setColor(g.get("inBubble"));
                canvas.drawRoundRect(rect, dp(9), dp(9), paint);
                rect.set(l + w - dp(8) - bw, t + dp(34), l + w - dp(8), t + dp(52));
                final Integer gradient = g.get("outGradient");
                if (gradient != null) {
                    paint.setShader(new android.graphics.LinearGradient(rect.left, 0, rect.right, 0, g.get("outBubble"), gradient, android.graphics.Shader.TileMode.CLAMP));
                } else {
                    paint.setColor(g.get("outBubble"));
                }
                canvas.drawRoundRect(rect, dp(9), dp(9), paint);
                paint.setShader(null);
                rect.set(l + dp(8), t + dp(62), l + w - dp(8), t + dp(78));
                paint.setColor(g.get("accent"));
                canvas.drawRoundRect(rect, dp(8), dp(8), paint);
                // the frame: the picked one in the accent
                final boolean selected = style == i;
                stroke.setStrokeWidth(selected ? dp(2) : dp(1));
                stroke.setColor(selected ? Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4)
                    : Theme.multAlpha(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText), 0.12f));
                rect.set(l, t, l + w, b - dp(26));
                canvas.drawRoundRect(rect, dp(14), dp(14), stroke);
                text.setTextSize(dp(13));
                text.setTypeface(selected ? AndroidUtilities.bold() : null);
                text.setColor(Theme.getColor(selected ? Theme.key_windowBackgroundWhiteBlackText : Theme.key_windowBackgroundWhiteGrayText));
                final String name = getString(names[i]);
                canvas.drawText(name, l + (w - text.measureText(name)) / 2f, b - dp(6), text);
            }
        }
    }

    // endregion
}
