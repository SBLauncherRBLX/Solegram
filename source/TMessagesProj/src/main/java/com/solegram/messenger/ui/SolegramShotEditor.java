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
import org.telegram.ui.Cells.ChatMessageCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.DialogsActivity;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;

/**
 * Solegram: «красивый скриншот». The messages a chat shows are taken as pictures (Telegram's own drawing, without the
 * chat's header and input) and put on a background of the user's choice. Messages can be swiped away (the others close
 * up), text can be selected and hidden under blur, and the result goes to chats or to the gallery, signed «Solegram».
 */
public class SolegramShotEditor extends BaseFragment {

    /** A message (or a date) of the screenshot: its picture and, for text, its lines. */
    public static final class Piece {
        Bitmap bitmap;
        @Nullable
        Bitmap blurred;
        int gapBefore;
        boolean message;
        final ArrayList<Line> lines = new ArrayList<>();
        final ArrayList<RectF> hidden = new ArrayList<>();
        boolean removed;
        float y = Float.NaN, dx, alpha = 1f;
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

    private final ArrayList<Piece> pieces;
    private final int contentWidth;
    @Nullable
    private final Drawable chatWallpaper;
    private ShotView shot;
    private int background; // 0: the chat's own wallpaper, then GRADIENTS
    private final ArrayList<Runnable> undo = new ArrayList<>();

    private static final int[][] GRADIENTS = {
        {0xFF9AD6F3, 0xFFB6A8F4}, {0xFFA8E6B8, 0xFFF3F0A0}, {0xFFF6B3D3, 0xFFB49CF2}, {0xFF7FD3C4, 0xFF5B8EE6},
        {0xFFFFD39A, 0xFFF59BB2}, {0xFF8FB7FF, 0xFFE0A6FF}, {0xFF2C3E7A, 0xFF7A4CB0}, {0xFF1D2B3A, 0xFF3B6E6A},
        {0xFFC9D6DF, 0xFFE2D1C3}, {0xFF262626, 0xFF4A4A4A},
    };

    public SolegramShotEditor(ArrayList<Piece> pieces, int contentWidth) {
        this.pieces = pieces;
        this.contentWidth = contentWidth;
        Drawable w = Theme.getCachedWallpaperNonBlocking();
        this.chatWallpaper = w != null ? w : Theme.getCachedWallpaper();
    }

    private int messageCount() {
        int n = 0;
        for (Piece p : pieces) {
            if (p.message && !p.removed) n++;
        }
        return n;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_close_white);
        actionBar.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        actionBar.setItemsColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText), false);
        actionBar.setTitleColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        actionBar.setSubtitleColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        actionBar.setCastShadows(false);
        actionBar.setTitle(getString(R.string.SG_ShotTitle));
        updateSubtitle();
        actionBar.createMenu().addItem(1, R.drawable.msg_reset);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == 1 && !undo.isEmpty()) {
                    undo.remove(undo.size() - 1).run();
                    shot.relayout(true);
                    updateSubtitle();
                }
            }
        });

        final LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        final FrameLayout spacer = new FrameLayout(context);
        root.addView(spacer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));
        shot = new ShotView(context);
        spacer.addView(shot, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.TOP, 0, 0, 0, 0));

        // the backgrounds: the chat's own, then gradients
        final HorizontalScrollView scroll = new HorizontalScrollView(context);
        scroll.setHorizontalScrollBarEnabled(false);
        final LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(12), 0, dp(12), 0);
        scroll.addView(row);
        final ArrayList<View> swatches = new ArrayList<>();
        for (int i = 0; i <= GRADIENTS.length; i++) {
            final int index = i;
            final View swatch = new View(context) {
                private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
                private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);

                @Override
                protected void onDraw(@NonNull Canvas canvas) {
                    final float cx = getWidth() / 2f, cy = getHeight() / 2f, r = dp(16);
                    if (index == 0) {
                        canvas.save();
                        final Path clip = new Path();
                        clip.addCircle(cx, cy, r, Path.Direction.CW);
                        canvas.clipPath(clip);
                        drawWallpaper(canvas, (int) (cx - r), (int) (cy - r), (int) (2 * r), (int) (2 * r));
                        canvas.restore();
                    } else {
                        final int[] g = GRADIENTS[index - 1];
                        paint.setShader(new LinearGradient(cx - r, cy - r, cx + r, cy + r, g[0], g[1], Shader.TileMode.CLAMP));
                        canvas.drawCircle(cx, cy, r, paint);
                    }
                    if (background == index) {
                        ring.setStyle(Paint.Style.STROKE);
                        ring.setStrokeWidth(dp(2));
                        ring.setColor(getThemedColor(Theme.key_featuredStickers_addButton));
                        canvas.drawCircle(cx, cy, r + dp(4), ring);
                    }
                }
            };
            swatch.setOnClickListener(v -> {
                background = index;
                for (View s : swatches) s.invalidate();
                shot.invalidate();
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            });
            swatches.add(swatch);
            row.addView(swatch, LayoutHelper.createLinear(46, 46, 2, 0, 2, 0));
        }
        root.addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 58, 0, 8, 0, 0));

        final LinearLayout buttons = new LinearLayout(context);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        final ButtonWithCounterView save = new ButtonWithCounterView(context, false, getResourceProvider());
        save.setText(getString(R.string.SG_ShotSave), false);
        save.setOnClickListener(v -> saveToGallery());
        final ButtonWithCounterView share = new ButtonWithCounterView(context, getResourceProvider());
        share.setText(getString(R.string.SG_ShotShare), false);
        share.setOnClickListener(v -> shareToChats());
        buttons.addView(save, LayoutHelper.createLinear(0, 48, 0.42f, 0, 0, 5, 0));
        buttons.addView(share, LayoutHelper.createLinear(0, 48, 0.58f, 5, 0, 0, 0));
        root.addView(buttons, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 14, 6, 14, 14));

        fragmentView = root;
        return root;
    }

    private void updateSubtitle() {
        if (actionBar != null) {
            actionBar.setSubtitle(LocaleController.formatString(R.string.SG_ShotSubtitle, messageCount()));
        }
    }

    @Override
    public boolean isLightStatusBar() {
        return AndroidUtilities.computePerceivedBrightness(getThemedColor(Theme.key_windowBackgroundWhite)) > 0.721f;
    }

    // region the background

    private void drawWallpaper(Canvas canvas, int left, int top, int w, int h) {
        final Drawable wp = chatWallpaper;
        if (wp == null) {
            final Paint p = new Paint();
            p.setColor(getThemedColor(Theme.key_chat_wallpaper));
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

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private void drawBackground(Canvas canvas, int w, int h) {
        if (background == 0) {
            drawWallpaper(canvas, 0, 0, w, h);
        } else {
            final int[] g = GRADIENTS[background - 1];
            bgPaint.setShader(new LinearGradient(0, 0, w, h, g[0], g[1], Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, bgPaint);
        }
    }

    // endregion

    // region the picture (on the screen and saved): background, the messages, the signature

    private static final int PAD_TOP = 18, PAD_BOTTOM = 34;
    private final Paint piecePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint hidePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final TextPaint signPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Path hidePath = new Path();

    /** The content's height (the picture's own pixels) with the pieces where they are going. */
    private int contentHeight(boolean animated) {
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

    private void drawPieces(Canvas canvas, boolean live) {
        for (Piece p : pieces) {
            if (p.removed && p.alpha <= 0f || Float.isNaN(p.y)) continue;
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

    /** The hidden text: the picture under it blurred (made once per message), in soft rounded patches. */
    private void drawHidden(Canvas canvas, Piece p) {
        if (p.blurred == null) {
            try {
                final int f = 6;
                final Bitmap small = Bitmap.createScaledBitmap(p.bitmap, Math.max(1, p.bitmap.getWidth() / f), Math.max(1, p.bitmap.getHeight() / f), true);
                Utilities.stackBlurBitmap(small, 3);
                p.blurred = small;
            } catch (Throwable e) {
                FileLog.e(e);
                return;
            }
        }
        hidePath.rewind();
        for (RectF r : p.hidden) {
            hidePath.addRoundRect(r, dp(6), dp(6), Path.Direction.CW);
        }
        canvas.save();
        canvas.clipPath(hidePath);
        canvas.scale(p.bitmap.getWidth() / (float) p.blurred.getWidth(), p.bitmap.getHeight() / (float) p.blurred.getHeight());
        hidePaint.setAlpha((int) (0xFF * Math.max(0f, Math.min(1f, p.alpha))));
        canvas.drawBitmap(p.blurred, 0, 0, hidePaint);
        canvas.restore();
    }

    private void drawSignature(Canvas canvas, int w, int h) {
        signPaint.setTypeface(AndroidUtilities.bold());
        signPaint.setTextSize(dp(13));
        signPaint.setColor(0xB3FFFFFF);
        signPaint.setShadowLayer(dp(2), 0, dp(0.5f), 0x40000000);
        canvas.drawText("Solegram", dp(14), h - dp(12), signPaint);
    }

    /** The whole picture at the size of the chat, for sending or saving. */
    @Nullable
    private Bitmap render() {
        shot.relayout(false);
        final int w = contentWidth, h = contentHeight(false);
        try {
            final Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            final Canvas c = new Canvas(out);
            drawBackground(c, w, h);
            for (Piece p : pieces) {
                p.dx = 0;
                if (p.removed) p.alpha = 0;
            }
            drawPieces(c, false);
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
        if (file == null || getParentActivity() == null) {
            return;
        }
        MediaController.saveFile(file.getAbsolutePath(), getParentActivity(), 0, null, null);
        BulletinFactory.of(this).createSimpleBulletin(R.raw.ic_save_to_gallery, getString(R.string.SG_ShotSaved)).show();
    }

    private void shareToChats() {
        final File file = writeShot();
        if (file == null) {
            return;
        }
        final android.os.Bundle args = new android.os.Bundle();
        args.putBoolean("onlySelect", true);
        args.putInt("dialogsType", DialogsActivity.DIALOGS_TYPE_FORWARD);
        args.putBoolean("allowGlobalSearch", false);
        final DialogsActivity picker = new DialogsActivity(args);
        picker.setDelegate((fragment, dids, message, param, notify, scheduleDate, scheduleRepeatPeriod, topicsFragment) -> {
            if (dids != null) {
                for (int i = 0; i < dids.size(); i++) {
                    SendMessagesHelper.prepareSendingPhoto(AccountInstance.getInstance(currentAccount), file.getAbsolutePath(), null,
                        dids.get(i).dialogId, null, null, null, null, null, null, null, 0, null, notify, scheduleDate, 0, null);
                }
            }
            fragment.finishFragment();
            BulletinFactory.of(SolegramShotEditor.this).createSimpleBulletin(R.raw.forward, getString(R.string.SG_ShotSent)).show();
            return true;
        });
        presentFragment(picker);
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

        // touch
        private float downX, downY, lastY;
        private boolean moved, swiping, scrolling, selectingText;
        @Nullable
        private Piece touched, selected;
        private int selStartLine = -1, selStartOffset, selEndLine, selEndOffset;
        private boolean textMode;
        private final ArrayList<RectF> selection = new ArrayList<>();
        // the pill's buttons (in view coordinates)
        private final RectF pill = new RectF(), pillA = new RectF(), pillB = new RectF();
        private String pillLabelA, pillLabelB;

        ShotView(Context context) {
            super(context);
            selectPaint.setColor(Theme.multAlpha(getThemedColor(Theme.key_featuredStickers_addButton), 0.32f));
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
            final float ch = contentHeight(false) * scale;
            final float maxH = getHeight() - dp(16);
            final float h = Math.min(ch, maxH);
            final float top = Math.max(dp(8), (getHeight() - h) / 2f);
            card.set(dp(16), top, dp(16) + w, top + h);
            scroll = Math.max(0, Math.min(scroll, ch - h));
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
            final int h = contentHeight(false);
            drawBackground(canvas, contentWidth, Math.max(h, (int) ((card.height() + scroll) / scale)));
            drawPieces(canvas, true);
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

        private void hidePill() {
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
                        scroll -= y - lastY;
                        invalidate();
                    }
                    lastY = y;
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    final boolean up = e.getActionMasked() == MotionEvent.ACTION_UP;
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

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        for (Piece p : pieces) {
            if (p.bitmap != null) p.bitmap.recycle();
            if (p.blurred != null) p.blurred.recycle();
        }
    }
}
