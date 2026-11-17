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
 * and a style; the preview shows the theme Solegram makes of them (every shade worked out, see
 * {@link SolegramThemeGenerator}), and "Create theme" puts it on with Telegram's circle reveal. Every colour one by
 * one is in the "⋮" menu (the advanced editor, {@link SolegramThemeEditorActivity}).
 */
public class SolegramThemeQuickActivity extends UniversalFragment implements NotificationCenter.NotificationCenterDelegate {

    private static final int ID_WALLPAPER = 1;
    private static final int ID_READY = 2;
    private static final int MENU_OPTIONS = 10;

    private int primary = SolegramThemeGenerator.savedPrimary();
    private int secondary = SolegramThemeGenerator.savedSecondary();
    private int style = SolegramThemeGenerator.savedStyle();

    private SolegramThemeMockView preview;
    private SwatchRow primaryRow, secondaryRow;
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
            // light or dark may have changed: the preview is worked out for it again
            updatePreview();
            if (primaryRow != null) {
                primaryRow.invalidateAll();
                secondaryRow.invalidateAll();
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
            .addIf(SolegramThemeColors.anySet(dark()), R.drawable.msg_reset, getString(R.string.SG_ThemeBackToOriginal), this::confirmReset)
            .show();
    }

    private static boolean dark() {
        return Theme.isCurrentThemeDark();
    }

    private Map<String, Integer> generated() {
        return SolegramThemeGenerator.generate(primary, secondary, style, dark());
    }

    private void updatePreview() {
        if (preview != null) {
            preview.setColorTable(SolegramThemeColors.preview(generated(), dark()));
        }
    }

    private void onChanged() {
        updatePreview();
        primaryRow.invalidateAll();
        secondaryRow.invalidateAll();
        styleRow.invalidate();
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        final Context context = getContext();
        if (context == null) {
            return;
        }
        if (preview == null) {
            preview = new SolegramThemeMockView(context, slot -> { });
            preview.setPreviewOnly(true);
            updatePreview();
            primaryRow = new SwatchRow(context, false);
            secondaryRow = new SwatchRow(context, true);
            styleRow = new StyleRow(context);
            buttonContainer = new FrameLayout(context);
            createButton = new ButtonWithCounterView(context, getResourceProvider()).setRound();
            createButton.setText(getString(R.string.SG_ThemeCreate), false);
            createButton.setOnClickListener(v -> create());
            buttonContainer.addView(createButton, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 48, Gravity.CENTER, 16, 14, 16, 14));
        }
        items.add(UItem.asHeader(getString(dark() ? R.string.SG_ThemeYoursDark : R.string.SG_ThemeYoursLight)));
        items.add(UItem.asCustom(preview));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(getString(R.string.SG_ThemeMainColor)));
        items.add(UItem.asCustom(primaryRow));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(getString(R.string.SG_ThemeSecondColor)));
        items.add(UItem.asCustom(secondaryRow));
        items.add(UItem.asShadow(getString(R.string.SG_ThemeSecondColorInfo)));

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

    private void create() {
        if (creating || getParentActivity() == null) {
            return;
        }
        creating = true;
        createButton.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        createButton.setLoading(true);
        final Map<String, Integer> theme = generated();
        final boolean dark = dark();
        final Integer accent = theme.get("accent");
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
                SolegramThemeGenerator.save(primary, secondary, style);
                SolegramThemeColors.setAll(dark, theme);
                Theme.refreshThemeColors(false, true);
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
                        SolegramThemeColors.resetAll(dark());
                        Theme.refreshThemeColors(false, true);
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

    private void pickCustom(boolean second) {
        if (getContext() == null) {
            return;
        }
        final int start = second ? (secondary != 0 ? secondary : primary) : primary;
        new ColorPickerBottomSheet(getContext(), getResourceProvider())
            .setPipetteDelegate(NO_PIPETTE)
            .setColor(start)
            .setColorListener(color -> {
                if (second) {
                    secondary = color | 0xFF000000;
                } else {
                    primary = color | 0xFF000000;
                }
                onChanged();
            })
            .show();
    }

    private static boolean isPreset(int color) {
        for (int c : SolegramThemeGenerator.COLORS) {
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
        final boolean second;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        private final Path path = new Path();
        private float selectedProgress;

        SwatchTile(Context context, int kind, int presetColor, boolean second) {
            super(context);
            this.kind = kind;
            this.presetColor = presetColor;
            this.second = second;
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeCap(Paint.Cap.ROUND);
            stroke.setStrokeJoin(Paint.Join.ROUND);
            setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                if (kind == KIND_CUSTOM) {
                    pickCustom(second);
                    return;
                }
                if (second) {
                    secondary = kind == KIND_AUTO ? 0 : presetColor;
                } else {
                    primary = presetColor;
                }
                onChanged();
            });
        }

        boolean isSelected0() {
            final int value = second ? secondary : primary;
            switch (kind) {
                case KIND_AUTO:
                    return value == 0;
                case KIND_CUSTOM:
                    return value != 0 && !isPreset(value);
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
            final int value = second ? secondary : primary;
            int color;
            if (kind == KIND_CUSTOM) {
                color = value != 0 && !isPreset(value) ? value : 0;
            } else if (kind == KIND_AUTO) {
                color = primary;
            } else {
                color = presetColor;
            }
            if (selectedProgress > 0) {
                stroke.setStrokeWidth(dp(2));
                stroke.setColor(Theme.multAlpha(color != 0 ? color | 0xFF000000 : Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4), selectedProgress));
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
                // "worked out": the first colour and its neighbour, split, with an "A"
                canvas.save();
                path.rewind();
                path.addRoundRect(box, rad, rad, Path.Direction.CW);
                canvas.clipPath(path);
                final float[] hsl = new float[3];
                ColorUtils.colorToHSL(primary, hsl);
                hsl[0] = (hsl[0] + 26) % 360;
                paint.setColor(ColorUtils.HSLToColor(hsl));
                path.rewind();
                path.moveTo(box.right, box.top);
                path.lineTo(box.right, box.bottom);
                path.lineTo(box.left, box.bottom);
                path.close();
                canvas.drawPath(path, paint);
                canvas.restore();
                text.setTextSize(dp(15));
                text.setTypeface(AndroidUtilities.bold());
                text.setColor(0xFFFFFFFF);
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

    /** The colours to pick from, six in a row; "auto" first for the second colour, "any colour" last. */
    private class SwatchRow extends ViewGroup {
        private static final int COLUMNS = 7;
        private final ArrayList<SwatchTile> tiles = new ArrayList<>();

        SwatchRow(Context context, boolean second) {
            super(context);
            if (second) {
                tiles.add(new SwatchTile(context, SwatchTile.KIND_AUTO, 0, true));
            }
            for (int c : SolegramThemeGenerator.COLORS) {
                tiles.add(new SwatchTile(context, SwatchTile.KIND_COLOR, c, second));
            }
            tiles.add(new SwatchTile(context, SwatchTile.KIND_CUSTOM, 0, second));
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
                final Map<String, Integer> g = SolegramThemeGenerator.generate(primary, secondary, i, dark);
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
