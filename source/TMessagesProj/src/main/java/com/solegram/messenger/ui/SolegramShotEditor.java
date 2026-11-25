/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.util.SparseIntArray;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.text.Layout;
import android.text.TextPaint;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.ChatActionCell;
import org.telegram.ui.Cells.ChatMessageCell;
import org.telegram.ui.Components.MotionBackgroundDrawable;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.DialogsActivity;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.util.TypedValue;
import android.widget.ImageView;
import android.widget.TextView;

import org.telegram.messenger.ChatThemeController;
import org.telegram.tgnet.ResultCallback;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.EmojiThemes;
import org.telegram.ui.ChatActivity;

import java.util.List;

/**
 * Solegram: the screenshot editor («Редактор скриншота»). The messages a chat shows are taken as pictures (Telegram's own drawing, without the
 * chat's header and input) and put on a background of the user's choice. Messages can be swiped away (the others close
 * up), text can be selected and hidden under blur, and the result goes to chats or to the gallery, signed «Solegram».
 */
public class SolegramShotEditor {

    /** A message (or a date) of the screenshot: its picture and, for text, its lines. */
    public static final class Piece {
        Bitmap bitmap;
        /** The blurred patches over hidden text, made once per hidden rect. */
        final java.util.IdentityHashMap<RectF, Bitmap> patches = new java.util.IdentityHashMap<>();
        int gapBefore;
        boolean message;
        final ArrayList<Line> lines = new ArrayList<>();
        final ArrayList<RectF> hidden = new ArrayList<>();
        boolean removed;
        float y = Float.NaN, dx, alpha = 1f;

        void recycle() {
            if (bitmap != null) bitmap.recycle();
            for (Bitmap b : patches.values()) {
                if (b != null) b.recycle();
            }
            patches.clear();
        }
    }

    /** A line of a message's text: its layout and where it is in the piece's picture. */
    static final class Line {
        Layout layout;
        int line;
        float originX, originY, top, bottom, left, right;
    }

    // region taking the screenshot

    /**
     * The visible messages of a chat's list between {@code top} and {@code bottom} (the list's own coordinates): one
     * picture of the list, cut into the messages.
     */
    @Nullable
    public static ArrayList<Piece> capture(@NonNull ViewGroup list, int top, int bottom) {
        final int w = list.getWidth(), h = list.getHeight();
        if (w <= 0 || h <= 0 || bottom <= top) {
            return null;
        }
        final Bitmap all;
        try {
            all = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            list.draw(new Canvas(all));
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
        final ArrayList<View> children = new ArrayList<>();
        for (int i = 0; i < list.getChildCount(); i++) {
            final View c = list.getChildAt(i);
            if (c.getVisibility() == View.VISIBLE && c.getHeight() > 0 && c.getBottom() > top && c.getTop() < bottom) {
                children.add(c);
            }
        }
        children.sort((a, b) -> Integer.compare(a.getTop(), b.getTop()));
        final ArrayList<Piece> pieces = new ArrayList<>();
        int prevBottom = -1;
        for (View c : children) {
            final int t = Math.max(top, c.getTop()), b = Math.min(bottom, c.getBottom());
            if (b - t < dp(4)) {
                continue;
            }
            final Piece p = new Piece();
            try {
                p.bitmap = Bitmap.createBitmap(all, 0, t, w, b - t);
            } catch (Throwable e) {
                continue;
            }
            p.gapBefore = prevBottom < 0 ? 0 : Math.max(0, t - prevBottom);
            prevBottom = b;
            p.message = c instanceof ChatMessageCell;
            if (c instanceof ChatMessageCell) {
                collectLines((ChatMessageCell) c, c.getTop() - t, p);
            }
            pieces.add(p);
        }
        all.recycle();
        return pieces.isEmpty() ? null : pieces;
    }

    /** Where the text of a message's cell is drawn (as the cell itself works it out for a touch on its text). */
    private static void collectLines(ChatMessageCell cell, int offsetY, Piece p) {
        final MessageObject mo = cell.getMessageObject();
        if (mo == null || mo.textLayoutBlocks == null || mo.textLayoutBlocks.isEmpty()) {
            return;
        }
        try {
            for (int i = 0; i < mo.textLayoutBlocks.size(); i++) {
                final MessageObject.TextLayoutBlock block = mo.textLayoutBlocks.get(i);
                final Layout layout = block.textLayout;
                if (layout == null) {
                    continue;
                }
                final float x0 = cell.textX - (block.isRtl() ? mo.textXOffset : 0);
                final float y0 = cell.textY + block.textYOffset(mo.textLayoutBlocks, cell.getTransitionParams()) + block.padTop + offsetY;
                for (int l = 0; l < layout.getLineCount(); l++) {
                    final Line line = new Line();
                    line.layout = layout;
                    line.line = l;
                    line.originX = x0;
                    line.originY = y0;
                    line.top = y0 + layout.getLineTop(l);
                    line.bottom = y0 + layout.getLineBottom(l);
                    line.left = x0 + layout.getLineLeft(l);
                    line.right = x0 + layout.getLineRight(l);
                    if (line.bottom > 0 && line.top < p.bitmap.getHeight()) {
                        p.lines.add(line);
                    }
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
            p.lines.clear();
        }
    }

    // endregion

    // region the sheet over the chat

    private final ChatActivity chat;
    private final ViewGroup list;
    private final int captureTop, captureBottom;
    private ArrayList<Piece> pieces;
    private int contentWidth;
    private BottomSheet sheet;
    private ShotView shot;
    private TextView subtitle;
    private final ArrayList<Runnable> undo = new ArrayList<>();
    private final ArrayList<View> swatches = new ArrayList<>();
    private final ArrayList<EmojiThemes> themes = new ArrayList<>();
    /** -1: the chat's own look; else an index in {@link #themes}. */
    private int chosen = -1;
    @Nullable
    private final EmojiThemes startTheme;
    private final boolean startDark;
    private boolean dark;
    /** The look the messages are drawn in now; null = the chat exactly as it is. */
    @Nullable
    private Look look;
    private final java.util.HashMap<String, Look> looks = new java.util.HashMap<>();

    private SolegramShotEditor(ChatActivity chat, ViewGroup list, int top, int bottom, ArrayList<Piece> pieces) {
        this.chat = chat;
        this.list = list;
        this.captureTop = top;
        this.captureBottom = bottom;
        this.pieces = pieces;
        this.contentWidth = list.getWidth();
        final ChatActivity.ThemeDelegate delegate = chat.themeDelegate;
        final EmojiThemes current = delegate != null ? delegate.getCurrentTheme() : null;
        startTheme = current != null && !current.showAsDefaultStub && !current.items.isEmpty() ? current : null;
        startDark = delegate != null ? delegate.isDark() : Theme.isCurrentThemeDark();
        dark = startDark;
    }

    /** The messages on the chat's screen (between {@code top} and {@code bottom} of its list) into the editor's sheet. */
    public static boolean show(ChatActivity chat, ViewGroup list, int top, int bottom) {
        final ArrayList<Piece> pieces = capture(list, top, bottom);
        if (pieces == null || chat.getParentActivity() == null) {
            return false;
        }
        new SolegramShotEditor(chat, list, top, bottom, pieces).open();
        return true;
    }

    private int messageCount() {
        int n = 0;
        for (Piece p : pieces) {
            if (p.message && !p.removed) n++;
        }
        return n;
    }

    /** «Вернуть» for the last edit, as a bulletin in the sheet. */
    private void offerUndo(String text) {
        if (sheet == null || !sheet.isShowing()) return;
        try {
            BulletinFactory.of(sheet.getContainer(), chat.getResourceProvider()).createSimpleBulletin(R.raw.chats_infotip, text,
                getString(R.string.SG_ShotUndo), () -> {
                    if (!undo.isEmpty()) {
                        undo.remove(undo.size() - 1).run();
                        shot.relayout(true);
                        updateSubtitle();
                    }
                }).show();
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private void updateSubtitle() {
        if (subtitle != null) {
            subtitle.setText(LocaleController.formatString(R.string.SG_ShotSubtitle, messageCount()));
        }
    }

    private void open() {
        final Context context = chat.getParentActivity();
        sheet = new BottomSheet(context, false, chat.getResourceProvider());
        sheet.fixNavigationBar();
        final LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(0, dp(6), 0, dp(12));

        // the header: ✕, the title and the count, the day/night switch
        final FrameLayout header = new FrameLayout(context);
        final View close = roundButton(context, false);
        close.setOnClickListener(v -> sheet.dismiss());
        header.addView(close, LayoutHelper.createFrame(40, 40, Gravity.LEFT | Gravity.CENTER_VERTICAL, 14, 0, 0, 0));
        final View sun = roundButton(context, true);
        sun.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            dark = !dark;
            applyTheme();
            v.invalidate();
        });
        header.addView(sun, LayoutHelper.createFrame(40, 40, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, 14, 0));
        final LinearLayout titles = new LinearLayout(context);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setGravity(Gravity.CENTER_HORIZONTAL);
        final TextView title = new TextView(context);
        title.setText(getString(R.string.SG_ShotTitle));
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
        title.setTypeface(AndroidUtilities.bold());
        title.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        titles.addView(title);
        subtitle = new TextView(context);
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        subtitle.setTextColor(Theme.getColor(Theme.key_dialogTextGray3));
        titles.addView(subtitle);
        updateSubtitle();
        header.addView(titles, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));
        column.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 52, 0, 0, 0, 8));

        shot = new ShotView(context);
        final int cardHeight = (int) (AndroidUtilities.displaySize.y * 0.52f / AndroidUtilities.density);
        column.addView(shot, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, cardHeight, 0, 0, 0, 0));

        // the themes: the chat's own look, then Telegram's chat themes (or plain gradients where a chat can't take one)
        final HorizontalScrollView scroll = new HorizontalScrollView(context);
        scroll.setHorizontalScrollBarEnabled(false);
        final LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(12), 0, dp(12), 0);
        scroll.addView(row);
        addSwatch(context, row, -1);
        column.addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 62, 0, 10, 0, 6));
        {
            ChatThemeController.getInstance(chat.getCurrentAccount()).requestAllChatThemes(new ResultCallback<List<EmojiThemes>>() {
                @Override
                public void onComplete(List<EmojiThemes> result) {
                    if (result == null) return;
                    for (EmojiThemes t : result) {
                        if (t == null || t.showAsDefaultStub || t.items.isEmpty()) continue;
                        try {
                            t.loadPreviewColors(chat.getCurrentAccount());
                        } catch (Throwable e) {
                            continue;
                        }
                        themes.add(t);
                        addSwatch(context, row, themes.size() - 1);
                    }
                    addGradients(context, row);
                }
            }, false);
        }

        // share (big), save (round)
        final LinearLayout buttons = new LinearLayout(context);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        final ImageView save = new ImageView(context);
        save.setScaleType(ImageView.ScaleType.CENTER);
        save.setImageResource(R.drawable.msg_download);
        save.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_featuredStickers_addButton), PorterDuff.Mode.SRC_IN));
        save.setBackground(Theme.createCircleDrawable(dp(48), Theme.multAlpha(Theme.getColor(Theme.key_featuredStickers_addButton), 0.12f)));
        save.setOnClickListener(v -> saveToGallery());
        final ButtonWithCounterView share = new ButtonWithCounterView(context, chat.getResourceProvider());
        share.setRound();
        share.setText(getString(R.string.SG_ShotShare), false);
        share.setOnClickListener(v -> shareToChats());
        buttons.addView(save, LayoutHelper.createLinear(48, 48, 0, 0, 10, 0));
        buttons.addView(share, LayoutHelper.createLinear(0, 48, 1f));
        column.addView(buttons, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 16, 6, 16, 0));

        sheet.setCustomView(column);
        // the chat itself is never touched: a theme is only drawn into the picture
        sheet.setOnDismissListener(d -> {
            for (Piece p : pieces) {
                p.recycle();
            }
        });
        // the themes may not come (no network): the gradients anyway, a moment later
        AndroidUtilities.runOnUIThread(() -> addGradients(context, row), 1500);
        sheet.show();
    }

    private View roundButton(Context context, boolean sunIcon) {
        final View v = new View(context) {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

            @Override
            protected void onDraw(@NonNull Canvas canvas) {
                final float cx = getWidth() / 2f, cy = getHeight() / 2f;
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(Theme.multAlpha(Theme.getColor(Theme.key_dialogTextBlack), 0.07f));
                canvas.drawCircle(cx, cy, Math.min(cx, cy), paint);
                paint.setColor(Theme.getColor(Theme.key_dialogTextBlack));
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(1.8f));
                paint.setStrokeCap(Paint.Cap.ROUND);
                if (!sunIcon) {
                    final float r = dp(6);
                    canvas.drawLine(cx - r, cy - r, cx + r, cy + r, paint);
                    canvas.drawLine(cx + r, cy - r, cx - r, cy + r, paint);
                } else if (!dark) {
                    // a sun: the look is light
                    canvas.drawCircle(cx, cy, dp(4.5f), paint);
                    for (int i = 0; i < 8; i++) {
                        final double a = Math.PI / 4 * i;
                        canvas.drawLine(cx + (float) Math.cos(a) * dp(7.5f), cy + (float) Math.sin(a) * dp(7.5f),
                            cx + (float) Math.cos(a) * dp(9.5f), cy + (float) Math.sin(a) * dp(9.5f), paint);
                    }
                } else {
                    // a moon: the look is dark
                    final Path moon = new Path();
                    moon.addCircle(cx, cy, dp(7), Path.Direction.CW);
                    final Path bite = new Path();
                    bite.addCircle(cx + dp(4), cy - dp(3), dp(6), Path.Direction.CW);
                    moon.op(bite, Path.Op.DIFFERENCE);
                    paint.setStyle(Paint.Style.FILL);
                    canvas.drawPath(moon, paint);
                }
            }
        };
        return v;
    }

    private boolean gradientsAdded;

    private void addGradients(Context context, LinearLayout row) {
        if (gradientsAdded) return;
        gradientsAdded = true;
        for (int i = 0; i < GRADIENTS.length; i++) {
            addSwatch(context, row, 1000 + i);
        }
    }

    private void addSwatch(Context context, LinearLayout row, int index) {
        final View swatch = new View(context) {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final RectF rect = new RectF();

            @Override
            protected void onDraw(@NonNull Canvas canvas) {
                final float cx = getWidth() / 2f, cy = getHeight() / 2f, r = dp(19);
                canvas.save();
                final Path clip = new Path();
                clip.addCircle(cx, cy, r, Path.Direction.CW);
                canvas.clipPath(clip);
                if (index == -1) {
                    drawStartWallpaper(canvas, (int) (cx - r), (int) (cy - r), (int) (2 * r), (int) (2 * r));
                } else if (index >= 1000) {
                    final int[] g = GRADIENTS[index - 1000];
                    paint.setShader(new LinearGradient(cx - r, cy - r, cx + r, cy + r, g[0], g[1], Shader.TileMode.CLAMP));
                    canvas.drawCircle(cx, cy, r, paint);
                    paint.setShader(null);
                } else {
                    final EmojiThemes.ThemeItem item = themes.get(index).getThemeItem(dark && themes.get(index).items.size() > 1 ? 1 : 0);
                    final int c1 = item.patternBgColor != 0 ? item.patternBgColor : item.inBubbleColor;
                    final int c2 = item.patternBgGradientColor2 != 0 ? item.patternBgGradientColor2 : item.patternBgGradientColor1 != 0 ? item.patternBgGradientColor1 : c1;
                    paint.setShader(new LinearGradient(cx - r, cy - r, cx + r, cy + r, c1 | 0xFF000000, c2 | 0xFF000000, Shader.TileMode.CLAMP));
                    canvas.drawCircle(cx, cy, r, paint);
                    paint.setShader(null);
                    // its two bubbles, as Telegram's theme picker draws them
                    paint.setColor(item.inBubbleColor | 0xFF000000);
                    rect.set(cx - r * 0.7f, cy - r * 0.42f, cx + r * 0.15f, cy - r * 0.08f);
                    canvas.drawRoundRect(rect, rect.height() / 2f, rect.height() / 2f, paint);
                    paint.setColor(item.outBubbleColor | 0xFF000000);
                    rect.set(cx - r * 0.15f, cy + r * 0.08f, cx + r * 0.7f, cy + r * 0.42f);
                    canvas.drawRoundRect(rect, rect.height() / 2f, rect.height() / 2f, paint);
                }
                canvas.restore();
                if (chosen == index) {
                    ring.setStyle(Paint.Style.STROKE);
                    ring.setStrokeWidth(dp(2));
                    ring.setColor(Theme.getColor(Theme.key_featuredStickers_addButton));
                    canvas.drawCircle(cx, cy, r + dp(4), ring);
                }
            }
        };
        swatch.setOnClickListener(v -> {
            if (chosen == index) return;
            chosen = index;
            for (View s : swatches) s.invalidate();
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            applyTheme();
        });
        swatches.add(swatch);
        row.addView(swatch, LayoutHelper.createLinear(50, 50, 2, 0, 2, 0));
    }

    /**
     * The chosen theme (and day or night): the messages are drawn again, once, in its colours into the picture. The
     * chat's own cells are given the look's colours only for that one drawing and get theirs back right after, so
     * neither the chat nor the app changes.
     */
    private void applyTheme() {
        try {
            look = lookFor(chosen >= 1000 ? -1 : chosen, dark);
        } catch (Throwable e) {
            FileLog.e(e);
            look = null;
        }
        recapture();
        for (View s : swatches) s.invalidate();
    }

    private void recapture() {
        if (sheet == null || !sheet.isShowing()) return;
        final ArrayList<Piece> fresh = captureIn(look);
        if (fresh == null) return;
        if (fresh.size() == pieces.size()) {
            for (int i = 0; i < fresh.size(); i++) {
                final Piece was = pieces.get(i), now = fresh.get(i);
                now.removed = was.removed;
                now.alpha = was.alpha;
                now.y = was.y;
                now.hidden.addAll(was.hidden);
            }
        }
        for (Piece p : pieces) {
            p.recycle();
        }
        pieces = fresh;
        contentWidth = list.getWidth();
        undo.clear(); // the old pictures are gone
        shot.selected = null;
        shot.hidePill();
        shot.relayout(false);
    }

    /** The chat's list drawn once with {@code look}'s colours (null: as it is). */
    @Nullable
    private ArrayList<Piece> captureIn(@Nullable Look look) {
        if (look == null) {
            return capture(list, captureTop, captureBottom);
        }
        final ArrayList<View> swapped = new ArrayList<>();
        final ArrayList<Theme.ResourcesProvider> own = new ArrayList<>();
        try {
            for (int i = 0; i < list.getChildCount(); i++) {
                final View c = list.getChildAt(i);
                if (c instanceof ChatMessageCell) {
                    final ChatMessageCell cell = (ChatMessageCell) c;
                    own.add(cell.getResourcesProvider());
                    swapped.add(c);
                    cell.setResourcesProvider(look);
                } else if (c instanceof ChatActionCell) {
                    own.add(((ChatActionCell) c).solegramSwapResources(look));
                    swapped.add(c);
                }
            }
            return capture(list, captureTop, captureBottom);
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        } finally {
            for (int i = 0; i < swapped.size(); i++) {
                final View c = swapped.get(i);
                if (c instanceof ChatMessageCell) {
                    ((ChatMessageCell) c).setResourcesProvider(own.get(i));
                } else {
                    ((ChatActionCell) c).solegramSwapResources(own.get(i));
                }
                c.invalidate();
            }
            list.invalidate();
        }
    }

    /**
     * The look for a theme of {@link #themes} ({@code index} >= 0) or the chat's own (-1) in day or night; null when
     * that is just the chat as it is on the screen.
     */
    @Nullable
    private Look lookFor(int index, boolean night) {
        final EmojiThemes theme = index >= 0 ? themes.get(index) : startTheme;
        if (index < 0 && night == startDark) {
            return null;
        }
        final String key = index + (night ? "n" : "d");
        Look l = looks.get(key);
        if (l != null) {
            return l;
        }
        final int account = chat.getCurrentAccount();
        final SparseIntArray colors = new SparseIntArray();
        final int[] defaults = Theme.getDefaultColors();
        if (defaults != null) {
            for (int i = 0; i < defaults.length; i++) {
                colors.put(i, defaults[i]);
            }
        }
        Drawable wallpaper = null;
        if (theme != null) {
            final int item = night && theme.items.size() > 1 ? 1 : 0;
            final SparseIntArray themeColors = theme.createColors(account, item);
            if (themeColors != null) {
                for (int i = 0; i < themeColors.size(); i++) {
                    colors.put(themeColors.keyAt(i), themeColors.valueAt(i));
                }
            }
            wallpaper = themeWallpaper(theme, item, colors);
        } else {
            // the app's own day or night theme
            final Theme.ThemeInfo info = EmojiThemes.getDefaultThemeInfo(night);
            final String[] link = new String[1];
            final SparseIntArray values = info.assetName != null ? Theme.getThemeFileValues(null, info.assetName, link)
                : info.pathToFile != null ? Theme.getThemeFileValues(new File(info.pathToFile), null, link) : null;
            if (values != null) {
                for (int i = 0; i < values.size(); i++) {
                    colors.put(values.keyAt(i), values.valueAt(i));
                }
                final Theme.ThemeAccent accent = info.getAccent(false);
                if (accent != null) {
                    accent.fillAccentColors(values, colors);
                }
            }
            if (colors.indexOfKey(Theme.key_chat_wallpaper) >= 0 && values != null && values.indexOfKey(Theme.key_chat_wallpaper) >= 0) {
                final int c1 = colors.get(Theme.key_chat_wallpaper), c2 = colors.get(Theme.key_chat_wallpaper_gradient_to1, 0);
                if (c2 != 0) {
                    wallpaper = new MotionBackgroundDrawable(c1, c2, colors.get(Theme.key_chat_wallpaper_gradient_to2, 0), colors.get(Theme.key_chat_wallpaper_gradient_to3, 0), true);
                } else {
                    wallpaper = new ColorDrawable(c1 | 0xFF000000);
                }
            }
        }
        final int[] service;
        if (colors.indexOfKey(Theme.key_chat_serviceBackground) >= 0 && wallpaper == null) {
            final int c = colors.get(Theme.key_chat_serviceBackground);
            service = new int[]{c, colors.get(Theme.key_chat_serviceBackgroundSelected, c)};
        } else {
            service = AndroidUtilities.calcDrawableColor(wallpaper != null ? wallpaper : Theme.getCachedWallpaperNonBlocking());
        }
        l = new Look(colors, night, wallpaper, service);
        looks.put(key, l);
        return l;
    }

    /** A theme's wallpaper as Telegram draws it for a chat: its gradient, its pattern when it comes. */
    private Drawable themeWallpaper(EmojiThemes theme, int item, SparseIntArray colors) {
        final MotionBackgroundDrawable motion = new MotionBackgroundDrawable();
        final TLRPC.WallPaper wp = theme.getWallpaper(item);
        final int intensity = wp != null && wp.settings != null ? wp.settings.intensity : 0;
        motion.setPatternBitmap(intensity);
        motion.setColors(colors.get(Theme.key_chat_wallpaper), colors.get(Theme.key_chat_wallpaper_gradient_to1, 0),
            colors.get(Theme.key_chat_wallpaper_gradient_to2, 0), colors.get(Theme.key_chat_wallpaper_gradient_to3, 0), 0, true);
        final int patternColor = motion.getPatternColor();
        try {
            theme.loadWallpaper(item, pair -> {
                if (pair == null || pair.second == null || pair.second.bitmap == null) return;
                final Bitmap bitmap = pair.second.bitmap;
                AndroidUtilities.runOnUIThread(() -> {
                    motion.setPatternBitmap(intensity, bitmap);
                    motion.setPatternColorFilter(patternColor);
                    if (shot != null) shot.invalidate();
                });
            });
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return motion;
    }

    /** Colours, bubbles and service paints of one look (with its wallpaper), for drawing the chat's cells once in it. */
    private final class Look extends SolegramLookProvider {
        @Nullable
        final Drawable wallpaper;

        Look(SparseIntArray colors, boolean night, @Nullable Drawable wallpaper, int[] service) {
            super(colors, night, service[0], service[1]);
            this.wallpaper = wallpaper;
            compact = chat.themeDelegate != null ? chat.themeDelegate.isCompactMode() : org.telegram.messenger.SharedConfig.compactMode;
        }
    }

    // endregion

    private static final int[][] GRADIENTS = {
        {0xFF9AD6F3, 0xFFB6A8F4}, {0xFFA8E6B8, 0xFFF3F0A0}, {0xFFF6B3D3, 0xFFB49CF2}, {0xFF7FD3C4, 0xFF5B8EE6},
        {0xFFFFD39A, 0xFFF59BB2}, {0xFF8FB7FF, 0xFFE0A6FF}, {0xFF2C3E7A, 0xFF7A4CB0}, {0xFF1D2B3A, 0xFF3B6E6A},
    };

    // region the background

    private void drawDrawable(Canvas canvas, @Nullable Drawable wp, int left, int top, int w, int h) {
        if (wp == null) {
            final Paint p = new Paint();
            p.setColor(Theme.getColor(Theme.key_chat_wallpaper));
            canvas.drawRect(left, top, left + w, top + h, p);
            return;
        }
        if (wp instanceof BitmapDrawable && ((BitmapDrawable) wp).getBitmap() != null) {
            final Bitmap b = ((BitmapDrawable) wp).getBitmap();
            final float scale = Math.max(w / (float) b.getWidth(), h / (float) b.getHeight());
            final int dw = (int) Math.ceil(b.getWidth() * scale), dh = (int) Math.ceil(b.getHeight() * scale);
            wp.setBounds(left + (w - dw) / 2, top + (h - dh) / 2, left + (w - dw) / 2 + dw, top + (h - dh) / 2 + dh);
        } else {
            wp.setBounds(left, top, left + w, top + h);
        }
        canvas.save();
        canvas.clipRect(left, top, left + w, top + h);
        wp.draw(canvas);
        canvas.restore();
    }

    private Drawable startWallpaperDrawable;

    private void drawStartWallpaper(Canvas canvas, int left, int top, int w, int h) {
        if (startWallpaperDrawable == null) {
            startWallpaperDrawable = chat.themeDelegate != null ? chat.themeDelegate.getWallpaperDrawable() : Theme.getCachedWallpaperNonBlocking();
        }
        drawDrawable(canvas, startWallpaperDrawable, left, top, w, h);
    }

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private void drawBackground(Canvas canvas, int w, int h) {
        if (chosen >= 1000) {
            final int[] g = GRADIENTS[chosen - 1000];
            bgPaint.setShader(new LinearGradient(0, 0, w, h, g[0], g[1], Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, bgPaint);
            return;
        }
        if (look != null && look.wallpaper != null) {
            drawDrawable(canvas, look.wallpaper, 0, 0, w, h);
        } else {
            drawStartWallpaper(canvas, 0, 0, w, h);
        }
    }

    // endregion

    // region the picture (on the screen and saved): background, the messages, the signature

    private static final int PAD_TOP = 18, PAD_BOTTOM = 34;
    private final Paint piecePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint hidePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final TextPaint signPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Path hidePath = new Path();

    private int contentHeight() {
        float y = dp(PAD_TOP);
        boolean first = true;
        for (Piece p : pieces) {
            if (p.removed) continue;
            y += first ? 0 : p.gapBefore;
            first = false;
            y += p.bitmap.getHeight();
        }
        return (int) (y + dp(PAD_BOTTOM));
    }

    private void drawPieces(Canvas canvas) {
        for (Piece p : pieces) {
            if (p.removed && p.alpha <= 0f || Float.isNaN(p.y) || p.bitmap == null || p.bitmap.isRecycled()) continue;
            piecePaint.setAlpha((int) (0xFF * Math.max(0f, Math.min(1f, p.alpha))));
            canvas.save();
            canvas.translate(p.dx, p.y);
            canvas.drawBitmap(p.bitmap, 0, 0, piecePaint);
            if (!p.hidden.isEmpty()) {
                drawHidden(canvas, p);
            }
            canvas.restore();
        }
    }

    /** Hidden text: frosted patches, the words under them blurred to soft smudges, the edges feathered. */
    private void drawHidden(Canvas canvas, Piece p) {
        hidePaint.setAlpha((int) (0xFF * Math.max(0f, Math.min(1f, p.alpha))));
        for (RectF r : p.hidden) {
            Bitmap patch = p.patches.get(r);
            if (patch == null && !p.patches.containsKey(r)) {
                patch = makePatch(p.bitmap, r);
                p.patches.put(r, patch);
            }
            if (patch != null) {
                canvas.drawBitmap(patch, r.left - dp(PATCH_GROW) - dp(PATCH_FEATHER), r.top - dp(PATCH_GROW) - dp(PATCH_FEATHER), hidePaint);
            }
        }
    }

    private static final float PATCH_GROW = 2.5f, PATCH_FEATHER = 2.5f;

    /**
     * The patch over one hidden rect of a piece. The words are made unreadable for sure: the area is averaged down
     * (halving again and again, so every pixel counts) to blocks about half a line high, i.e. a letter or two per
     * block, drawn back as a mosaic, and that mosaic is blurred so it is soft smudges with no letter shapes left. Then
     * a light frost and a rounded rect with feathered edges.
     */
    @Nullable
    private Bitmap makePatch(Bitmap source, RectF r) {
        try {
            final float grow = dp(PATCH_GROW), feather = dp(PATCH_FEATHER);
            final RectF shape = new RectF(r.left - grow, r.top - grow, r.right + grow, r.bottom + grow);
            final int pw = (int) Math.ceil(shape.width() + 2 * feather), ph = (int) Math.ceil(shape.height() + 2 * feather);
            if (pw <= 2 || ph <= 2) return null;
            final float ox = shape.left - feather, oy = shape.top - feather;
            // the area itself, as it is in the picture (outside the piece: transparent)
            Bitmap cur = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888);
            new Canvas(cur).drawBitmap(source, -ox, -oy, null);
            final int block = Math.max(dp(5), Math.round(r.height() * 0.5f));
            final int tw = Math.max(1, Math.round(pw / (float) block)), th = Math.max(1, Math.round(ph / (float) block));
            while (cur.getWidth() / 2 >= tw && cur.getHeight() / 2 >= th && cur.getWidth() > 1 && cur.getHeight() > 1) {
                final Bitmap half = Bitmap.createScaledBitmap(cur, Math.max(1, cur.getWidth() / 2), Math.max(1, cur.getHeight() / 2), true);
                cur.recycle();
                cur = half;
            }
            if (cur.getWidth() != tw || cur.getHeight() != th) {
                final Bitmap fit = Bitmap.createScaledBitmap(cur, tw, th, true);
                cur.recycle();
                cur = fit;
            }
            final Bitmap patch = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888);
            final Canvas pc = new Canvas(patch);
            pc.save();
            pc.scale(pw / (float) tw, ph / (float) th);
            pc.drawBitmap(cur, 0, 0, null); // nearest: the mosaic
            pc.restore();
            cur.recycle();
            // the blocks' edges melted away: soft smudges, nothing to read
            Utilities.stackBlurBitmap(patch, Math.max(2, Math.min(60, block / 2)));
            final RectF local = new RectF(feather, feather, feather + shape.width(), feather + shape.height());
            final float round = Math.min(local.height() / 2f, dp(9));
            // a light frost, so it reads as hidden rather than as an empty bubble
            final Paint frost = new Paint(Paint.ANTI_ALIAS_FLAG);
            final boolean night = look != null ? look.night : startDark;
            frost.setColor(night ? 0x24FFFFFF : 0x2EFFFFFF);
            pc.drawRoundRect(local, round, round, frost);
            frost.setColor(night ? 0x14FFFFFF : 0x0F000000);
            frost.setStyle(Paint.Style.STROKE);
            frost.setStrokeWidth(Math.max(1, dp(0.6f)));
            pc.drawRoundRect(local, round, round, frost);
            // the shape, its edge feathered
            final Paint mask = new Paint(Paint.ANTI_ALIAS_FLAG);
            mask.setXfermode(new android.graphics.PorterDuffXfermode(PorterDuff.Mode.DST_IN));
            mask.setMaskFilter(new android.graphics.BlurMaskFilter(feather, android.graphics.BlurMaskFilter.Blur.NORMAL));
            pc.drawRoundRect(local, round, round, mask);
            return patch;
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    private void drawSignature(Canvas canvas, int w, int h) {
        signPaint.setTypeface(AndroidUtilities.bold());
        signPaint.setTextSize(dp(13));
        signPaint.setColor(0xB3FFFFFF);
        signPaint.setShadowLayer(dp(2), 0, dp(0.5f), 0x40000000);
        canvas.drawText("Solegram", dp(14), h - dp(12), signPaint);
    }

    @Nullable
    private Bitmap render() {
        shot.relayout(false);
        final int w = contentWidth, h = contentHeight();
        try {
            final Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            final Canvas c = new Canvas(out);
            drawBackground(c, w, h);
            for (Piece p : pieces) {
                p.dx = 0;
                if (p.removed) p.alpha = 0;
            }
            drawPieces(c);
            drawSignature(c, w, h);
            return out;
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    @Nullable
    private File writeShot() {
        final Bitmap b = render();
        if (b == null) {
            return null;
        }
        try {
            final File dir = new File(ApplicationLoader.applicationContext.getCacheDir(), "sg_shots");
            dir.mkdirs();
            final File file = new File(dir, "Solegram_" + System.currentTimeMillis() + ".png");
            try (FileOutputStream out = new FileOutputStream(file)) {
                b.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
            b.recycle();
            return file;
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    private void saveToGallery() {
        final File file = writeShot();
        if (file == null || chat.getParentActivity() == null) {
            return;
        }
        MediaController.saveFile(file.getAbsolutePath(), chat.getParentActivity(), 0, null, null);
        BulletinFactory.of(sheet.getContainer(), chat.getResourceProvider()).createSimpleBulletin(R.raw.ic_save_to_gallery, getString(R.string.SG_ShotSaved)).show();
    }

    private void shareToChats() {
        final File file = writeShot();
        if (file == null) {
            return;
        }
        sheet.dismiss();
        final android.os.Bundle args = new android.os.Bundle();
        args.putBoolean("onlySelect", true);
        args.putInt("dialogsType", DialogsActivity.DIALOGS_TYPE_FORWARD);
        args.putBoolean("allowGlobalSearch", false);
        final DialogsActivity picker = new DialogsActivity(args);
        final int account = chat.getCurrentAccount();
        picker.setDelegate((fragment, dids, message, param, notify, scheduleDate, scheduleRepeatPeriod, topicsFragment) -> {
            if (dids != null) {
                for (int i = 0; i < dids.size(); i++) {
                    SendMessagesHelper.prepareSendingPhoto(AccountInstance.getInstance(account), file.getAbsolutePath(), null,
                        dids.get(i).dialogId, null, null, null, null, null, null, null, 0, null, notify, scheduleDate, 0, null);
                }
            }
            fragment.finishFragment();
            BulletinFactory.of(chat).createSimpleBulletin(R.raw.forward, getString(R.string.SG_ShotSent)).show();
            return true;
        });
        chat.presentFragment(picker);
    }

    // endregion

    /** The screenshot on the screen: a card scaled to the width, scrolled when tall; the messages are edited on it. */
    private final class ShotView extends View {
        private final RectF card = new RectF();
        private final Path cardPath = new Path();
        private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint selectPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint pillText = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private float scale = 1f, scroll;
        private ValueAnimator layoutAnimator;
        private final android.widget.OverScroller scroller;
        @Nullable
        private android.view.VelocityTracker velocity;

        // touch
        private float downX, downY, lastY;
        private boolean moved, swiping, scrolling, selectingText;
        @Nullable
        Piece touched, selected;
        private int selStartLine = -1, selStartOffset, selEndLine, selEndOffset;
        private boolean textMode;
        private final ArrayList<RectF> selection = new ArrayList<>();
        // the pill's buttons (in view coordinates)
        private final RectF pill = new RectF(), pillA = new RectF(), pillB = new RectF();
        private String pillLabelA, pillLabelB;

        ShotView(Context context) {
            super(context);
            scroller = new android.widget.OverScroller(context);
            selectPaint.setColor(Theme.multAlpha(Theme.getColor(Theme.key_featuredStickers_addButton), 0.32f));
            pillText.setTextSize(dp(14));
            pillText.setTypeface(AndroidUtilities.bold());
            pillText.setColor(0xFFFFFFFF);
            relayout(false);
        }

        void relayout(boolean animated) {
            float y = dp(PAD_TOP);
            boolean first = true;
            final float[] from = new float[pieces.size()];
            final float[] to = new float[pieces.size()];
            for (int i = 0; i < pieces.size(); i++) {
                final Piece p = pieces.get(i);
                from[i] = Float.isNaN(p.y) ? y : p.y;
                if (p.removed) {
                    to[i] = from[i];
                    continue;
                }
                y += first ? 0 : p.gapBefore;
                first = false;
                to[i] = y;
                y += p.bitmap.getHeight();
            }
            if (layoutAnimator != null) {
                layoutAnimator.cancel();
            }
            if (!animated) {
                for (int i = 0; i < pieces.size(); i++) {
                    pieces.get(i).y = to[i];
                    if (!pieces.get(i).removed) pieces.get(i).alpha = 1f;
                }
                invalidate();
                return;
            }
            layoutAnimator = ValueAnimator.ofFloat(0f, 1f);
            layoutAnimator.setDuration(380);
            layoutAnimator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
            layoutAnimator.addUpdateListener(a -> {
                final float t = (float) a.getAnimatedValue();
                for (int i = 0; i < pieces.size(); i++) {
                    final Piece p = pieces.get(i);
                    p.y = from[i] + (to[i] - from[i]) * t;
                    if (!p.removed && p.alpha < 1f) p.alpha = Math.max(p.alpha, t);
                }
                invalidate();
            });
            layoutAnimator.start();
        }

        private void layoutCard() {
            final float w = getWidth() - dp(32);
            scale = w / contentWidth;
            final float ch = contentHeight() * scale;
            final float maxH = getHeight() - dp(16);
            final float h = Math.min(ch, maxH);
            final float top = Math.max(dp(8), (getHeight() - h) / 2f);
            card.set(dp(16), top, dp(16) + w, top + h);
            scroll = Math.max(0, Math.min(scroll, ch - h));
        }

        private float maxScroll() {
            return Math.max(0, contentHeight() * scale - card.height());
        }

        @Override
        public void computeScroll() {
            if (scroller.computeScrollOffset()) {
                scroll = scroller.getCurrY();
                invalidate();
            }
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            layoutCard();
            cardPath.rewind();
            cardPath.addRoundRect(card, dp(18), dp(18), Path.Direction.CW);
            shadow.setColor(0x22000000);
            shadow.setShadowLayer(dp(10), 0, dp(3), 0x30000000);
            canvas.drawRoundRect(card, dp(18), dp(18), shadow);
            canvas.save();
            canvas.clipPath(cardPath);
            canvas.translate(card.left, card.top - scroll);
            canvas.scale(scale, scale);
            final int h = contentHeight();
            drawBackground(canvas, contentWidth, Math.max(h, (int) ((card.height() + scroll) / scale)));
            drawPieces(canvas);
            // what is selected
            if (selected != null && !selected.removed) {
                canvas.save();
                canvas.translate(selected.dx, selected.y);
                for (RectF r : selection) {
                    canvas.drawRoundRect(r, dp(4), dp(4), selectPaint);
                }
                canvas.restore();
            }
            drawSignature(canvas, contentWidth, h);
            canvas.restore();
            drawPill(canvas);
        }

        /** Content coordinates of a point on the view. */
        private float cx(float x) {
            return (x - card.left) / scale;
        }

        private float cy(float y) {
            return (y - card.top + scroll) / scale;
        }

        @Nullable
        private Piece pieceAt(float contentY) {
            for (Piece p : pieces) {
                if (!p.removed && p.message && contentY >= p.y && contentY < p.y + p.bitmap.getHeight()) {
                    return p;
                }
            }
            return null;
        }

        // region the pill over the selection: «Скрыть» / «Скрыть текст»

        private void showPill(String a, @Nullable String b) {
            pillLabelA = a;
            pillLabelB = b;
            invalidate();
        }

        void hidePill() {
            pillLabelA = pillLabelB = null;
            invalidate();
        }

        private void drawPill(Canvas canvas) {
            if (pillLabelA == null || selected == null) {
                pill.setEmpty();
                return;
            }
            final float wa = pillText.measureText(pillLabelA) + dp(28);
            final float wb = pillLabelB != null ? pillText.measureText(pillLabelB) + dp(28) : 0;
            final float w = wa + wb, h = dp(38);
            float top;
            if (textMode && !selection.isEmpty()) {
                top = card.top - scroll + (selected.y + selection.get(0).top) * scale - h - dp(8);
            } else {
                top = card.top - scroll + selected.y * scale - h - dp(6);
            }
            top = Math.max(card.top + dp(4), Math.min(top, card.bottom - h - dp(4)));
            final float left = Math.max(dp(12), Math.min(getWidth() / 2f - w / 2f, getWidth() - dp(12) - w));
            pill.set(left, top, left + w, top + h);
            pillPaint.setColor(0xE6222222);
            pillPaint.setShadowLayer(dp(6), 0, dp(2), 0x40000000);
            canvas.drawRoundRect(pill, h / 2f, h / 2f, pillPaint);
            pillA.set(left, top, left + wa, top + h);
            canvas.drawText(pillLabelA, pillA.left + dp(14), pillA.centerY() + dp(5), pillText);
            if (pillLabelB != null) {
                pillB.set(left + wa, top, left + w, top + h);
                pillPaint.setShadowLayer(0, 0, 0, 0);
                pillPaint.setColor(0x33FFFFFF);
                canvas.drawRect(pillB.left, top + dp(10), pillB.left + Math.max(1, dp(0.66f)), top + h - dp(10), pillPaint);
                canvas.drawText(pillLabelB, pillB.left + dp(14), pillB.centerY() + dp(5), pillText);
            } else {
                pillB.setEmpty();
            }
        }

        // endregion

        // region text selection over the message's lines

        /** (line index in the piece, character offset) for a point in the piece's coordinates. */
        private int[] hit(Piece p, float x, float y) {
            int best = 0;
            float bestD = Float.MAX_VALUE;
            for (int i = 0; i < p.lines.size(); i++) {
                final Line l = p.lines.get(i);
                final float d = y < l.top ? l.top - y : y > l.bottom ? y - l.bottom : 0;
                if (d < bestD) {
                    bestD = d;
                    best = i;
                }
            }
            final Line l = p.lines.get(best);
            final int offset = l.layout.getOffsetForHorizontal(l.line, x - l.originX);
            return new int[]{best, offset};
        }

        private void updateSelection(Piece p) {
            selection.clear();
            if (selStartLine < 0 || p.lines.isEmpty()) {
                return;
            }
            int aLine = selStartLine, aOff = selStartOffset, bLine = selEndLine, bOff = selEndOffset;
            if (aLine > bLine || aLine == bLine && aOff > bOff) {
                final int tl = aLine, to = aOff;
                aLine = bLine;
                aOff = bOff;
                bLine = tl;
                bOff = to;
            }
            for (int i = aLine; i <= bLine; i++) {
                final Line l = p.lines.get(i);
                final int lineStart = l.layout.getLineStart(l.line), lineEnd = l.layout.getLineEnd(l.line);
                final int s = i == aLine ? Math.max(lineStart, aOff) : lineStart;
                final int e = i == bLine ? Math.min(lineEnd, bOff) : lineEnd;
                float x1 = i == aLine ? l.originX + l.layout.getPrimaryHorizontal(s) : l.left;
                float x2 = i == bLine ? l.originX + l.layout.getPrimaryHorizontal(e) : l.right;
                if (x2 < x1) {
                    final float t = x1;
                    x1 = x2;
                    x2 = t;
                }
                if (x2 - x1 < dp(2)) {
                    continue;
                }
                selection.add(new RectF(x1 - dp(2), l.top + dp(1), x2 + dp(2), l.bottom - dp(1)));
            }
        }

        // endregion

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            final float x = e.getX(), y = e.getY();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    downX = x;
                    downY = lastY = y;
                    moved = swiping = scrolling = selectingText = false;
                    scroller.forceFinished(true);
                    if (velocity != null) velocity.recycle();
                    velocity = android.view.VelocityTracker.obtain();
                    velocity.addMovement(e);
                    // a card that scrolls keeps its drags: the sheet doesn't take them for closing
                    if (card.contains(x, y) && maxScroll() > 0) {
                        getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    if (!pill.isEmpty() && pill.contains(x, y)) {
                        return true;
                    }
                    touched = pieceAt(cy(y));
                    if (textMode && touched != null && touched == selected && !touched.lines.isEmpty()) {
                        selectingText = true;
                        final int[] h = hit(touched, cx(x), cy(y) - touched.y);
                        selStartLine = selEndLine = h[0];
                        selStartOffset = selEndOffset = h[1];
                        updateSelection(touched);
                        hidePill();
                        getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    return true;
                }
                case MotionEvent.ACTION_MOVE: {
                    if (velocity != null) velocity.addMovement(e);
                    final int slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
                    if (selectingText && touched != null) {
                        final int[] h = hit(touched, cx(x), cy(y) - touched.y);
                        selEndLine = h[0];
                        selEndOffset = h[1];
                        updateSelection(touched);
                        invalidate();
                        return true;
                    }
                    if (!moved && (Math.abs(x - downX) > slop || Math.abs(y - downY) > slop)) {
                        moved = true;
                        if (touched != null && Math.abs(x - downX) > Math.abs(y - downY) && !textMode) {
                            swiping = true;
                            hidePill();
                        } else {
                            scrolling = true;
                        }
                        getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    if (swiping && touched != null) {
                        touched.dx = (x - downX) / scale;
                        touched.alpha = 1f - Math.min(0.7f, Math.abs(touched.dx) / contentWidth);
                        invalidate();
                    } else if (scrolling) {
                        scroll = Math.max(0, Math.min(maxScroll(), scroll - (y - lastY)));
                        invalidate();
                    }
                    lastY = y;
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    final boolean up = e.getActionMasked() == MotionEvent.ACTION_UP;
                    if (scrolling && up && velocity != null) {
                        velocity.addMovement(e);
                        velocity.computeCurrentVelocity(1000, ViewConfiguration.get(getContext()).getScaledMaximumFlingVelocity());
                        final float vy = velocity.getYVelocity();
                        if (Math.abs(vy) > ViewConfiguration.get(getContext()).getScaledMinimumFlingVelocity()) {
                            scroller.fling(0, (int) scroll, 0, (int) -vy, 0, 0, 0, (int) maxScroll());
                            postInvalidateOnAnimation();
                        }
                    }
                    if (velocity != null) {
                        velocity.recycle();
                        velocity = null;
                    }
                    if (!moved && up && !pill.isEmpty() && pill.contains(x, y)) {
                        tapPill(pillA.contains(x, y));
                        return true;
                    }
                    if (selectingText) {
                        selectingText = false;
                        if (!selection.isEmpty()) {
                            showPill(getString(R.string.SG_ShotHideText), null);
                        }
                        return true;
                    }
                    if (swiping && touched != null) {
                        final Piece p = touched;
                        if (up && Math.abs(p.dx) * scale > getWidth() * 0.28f) {
                            removePiece(p, Math.signum(p.dx));
                        } else {
                            animateBack(p);
                        }
                        return true;
                    }
                    if (!moved && up) {
                        tap(touched);
                    }
                    return true;
                }
            }
            return super.onTouchEvent(e);
        }

        private void tap(@Nullable Piece p) {
            textMode = false;
            selection.clear();
            selStartLine = -1;
            if (p == null || p == selected) {
                selected = null;
                hidePill();
                return;
            }
            selected = p;
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            showPill(getString(R.string.SG_ShotHideMessage), p.lines.isEmpty() ? null : getString(R.string.SG_ShotSelectText));
        }

        private void tapPill(boolean first) {
            final Piece p = selected;
            if (p == null) {
                hidePill();
                return;
            }
            if (textMode && selection.isEmpty()) {
                return; // the hint: the text is selected with a finger first
            }
            if (textMode) {
                // hide the selected text under blur
                final ArrayList<RectF> added = new ArrayList<>(selection);
                p.hidden.addAll(added);
                undo.add(() -> p.hidden.removeAll(added));
                offerUndo(getString(R.string.SG_ShotTextHidden));
                selection.clear();
                selStartLine = -1;
                textMode = false;
                selected = null;
                hidePill();
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                return;
            }
            if (first) {
                removePiece(p, 1f);
            } else {
                // text mode: drag over the text to select what to hide
                textMode = true;
                showPill(getString(R.string.SG_ShotSelectHint), null);
            }
        }

        private void removePiece(Piece p, float direction) {
            hidePill();
            if (selected == p) {
                selected = null;
            }
            textMode = false;
            selection.clear();
            final float startDx = p.dx;
            final ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
            a.setDuration(240);
            a.setInterpolator(CubicBezierInterpolator.EASE_IN);
            a.addUpdateListener(v -> {
                final float t = (float) v.getAnimatedValue();
                p.dx = startDx + (direction * contentWidth - startDx) * t;
                p.alpha = (1f - t) * 0.9f;
                invalidate();
            });
            a.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(android.animation.Animator animation) {
                    p.removed = true;
                    p.alpha = 0f;
                    p.dx = 0;
                    relayout(true);
                    updateSubtitle();
                    offerUndo(getString(R.string.SG_ShotMessageRemoved));
                }
            });
            a.start();
            undo.add(() -> {
                p.removed = false;
                p.alpha = 0f;
                p.dx = 0;
            });
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        }

        private void animateBack(Piece p) {
            final float startDx = p.dx, startAlpha = p.alpha;
            final ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
            a.setDuration(260);
            a.setInterpolator(CubicBezierInterpolator.EASE_OUT_BACK);
            a.addUpdateListener(v -> {
                final float t = (float) v.getAnimatedValue();
                p.dx = startDx * (1f - t);
                p.alpha = startAlpha + (1f - startAlpha) * Math.min(1f, t);
                invalidate();
            });
            a.start();
        }
    }

}
