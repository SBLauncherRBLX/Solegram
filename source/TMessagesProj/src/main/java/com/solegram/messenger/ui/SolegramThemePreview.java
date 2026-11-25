/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.solegram.messenger.SolegramThemeShare;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.INavigationLayout;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.ThemePreviewMessagesCell;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;

/**
 * Solegram: a theme sent in a chat is tried on in a sheet from the bottom. The theme is put on for real (colours, icons,
 * avatar shape, glass, the rest) with the circle of new colours, and the sheet shows a chat in it built of Telegram's
 * own parts: the user's wallpaper, real message bubbles, a glass header with the user's avatar and a glass input with
 * the theme's icons. «Установить» keeps it; «Отмена» or closing the sheet gives the old look back. What the look was is
 * kept on disk, so a preview the app never ended is undone at the next start.
 */
public final class SolegramThemePreview {

    private SolegramThemePreview() {
    }

    @Nullable
    private static SolegramThemeShare.Theme theme;
    private static boolean installed;
    private static int oldAccent;

    /** Puts the theme on for a while and shows the sheet to look at it. */
    public static void start(BaseFragment from, SolegramThemeShare.Theme t) {
        final Activity activity = from.getParentActivity();
        if (activity == null || theme != null) {
            return;
        }
        theme = t;
        installed = false;
        oldAccent = Theme.getColor(Theme.key_featuredStickers_addButton);
        SolegramThemeShare.savePreviewSnapshot();
        final INavigationLayout layout = from.getParentLayout();
        final View decor = activity.getWindow().getDecorView();
        SolegramThemeReveal.start(activity, decor.getWidth() / 2f, decor.getHeight() * 0.62f, t.accent(Theme.isCurrentThemeDark()), () -> {
            SolegramThemeShare.apply(t);
            SolegramThemeShare.refresh(layout);
        });
        // the sheet in the new look (a moment for the theme's resources)
        AndroidUtilities.runOnUIThread(() -> show(from, t), 160);
    }

    private static void show(BaseFragment from, SolegramThemeShare.Theme t) {
        final Activity activity = from.getParentActivity();
        if (activity == null) {
            end(from, false);
            return;
        }
        final BottomSheet sheet = new BottomSheet(activity, false);
        sheet.fixNavigationBar();
        final LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(0, dp(6), 0, dp(10));

        final TextView title = new TextView(activity);
        title.setText(LocaleController.formatString(R.string.SG_ThemePreviewTitle, t.name));
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        title.setTypeface(AndroidUtilities.bold());
        title.setGravity(Gravity.CENTER);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        column.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 22, 8, 22, 0));
        final TextView info = new TextView(activity);
        info.setText(getString(R.string.SG_ThemePreviewInfo));
        info.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        info.setGravity(Gravity.CENTER);
        info.setTextColor(Theme.getColor(Theme.key_dialogTextGray3));
        column.addView(info, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 28, 4, 28, 14));

        column.addView(new ChatPreview(activity, from.getParentLayout()), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 380, 14, 0, 14, 0));

        final LinearLayout buttons = new LinearLayout(activity);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        final ButtonWithCounterView cancel = new ButtonWithCounterView(activity, false, null);
        cancel.setText(getString(R.string.Cancel), false);
        final ButtonWithCounterView install = new ButtonWithCounterView(activity, null);
        install.setText(getString(R.string.SG_ThemeShareInstall), false);
        buttons.addView(cancel, LayoutHelper.createLinear(0, 48, 1f, 0, 0, 5, 0));
        buttons.addView(install, LayoutHelper.createLinear(0, 48, 1f, 5, 0, 0, 0));
        column.addView(buttons, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 14, 14, 14, 0));

        cancel.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            sheet.dismiss();
        });
        install.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            installed = true;
            sheet.dismiss();
        });
        sheet.setCustomView(column);
        sheet.setOnDismissListener(d -> end(from, installed));
        sheet.show();
    }

    /** The sheet is closed: the theme stays, or the look before it comes back (with the circle again). */
    private static void end(BaseFragment from, boolean keep) {
        if (theme == null) {
            return;
        }
        theme = null;
        installed = false;
        if (keep) {
            SolegramThemeShare.dropPreviewSnapshot();
            BulletinFactory.of(from).createSimpleBulletin(R.raw.contact_check, getString(R.string.SG_ThemeShareInstalled),
                getString(R.string.SG_ThemeShareRestartHint)).show();
            return;
        }
        final Activity activity = from.getParentActivity();
        final INavigationLayout layout = from.getParentLayout();
        final Runnable back = () -> {
            SolegramThemeShare.restorePreviewSnapshot();
            SolegramThemeShare.refresh(layout);
        };
        if (activity != null && activity.getWindow() != null) {
            final View decor = activity.getWindow().getDecorView();
            SolegramThemeReveal.start(activity, decor.getWidth() / 2f, decor.getHeight() * 0.62f, oldAccent, back);
        } else {
            back.run();
        }
    }

    /** A chat in the look now: the wallpaper, a glass header, Telegram's own message bubbles, a glass input. */
    private static final class ChatPreview extends FrameLayout {
        private static final int HEADER = 56, INPUT = 52;
        private final Paint glassPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint tint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final Matrix matrix = new Matrix();
        @Nullable
        private Bitmap blurred;
        private final Drawable wallpaper;
        private final Drawable back, more, attach, smile, mic;
        private final String name;

        ChatPreview(Context context, INavigationLayout layout) {
            super(context);
            setWillNotDraw(false);
            setClipToOutline(true);
            setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(20));
                }
            });
            Drawable w = Theme.getCachedWallpaperNonBlocking();
            if (w == null) {
                w = Theme.getCachedWallpaper();
            }
            wallpaper = w != null ? w : new ColorDrawable(Theme.getColor(Theme.key_chat_wallpaper));
            back = icon(context, R.drawable.ic_ab_back, Theme.key_actionBarDefaultIcon);
            more = icon(context, R.drawable.ic_ab_other, Theme.key_actionBarDefaultIcon);
            attach = icon(context, R.drawable.input_attach, Theme.key_chat_messagePanelIcons);
            smile = icon(context, R.drawable.input_smile, Theme.key_chat_messagePanelIcons);
            mic = icon(context, R.drawable.input_mic, Theme.key_chat_messagePanelIcons);

            final TLRPC.User me = UserConfig.getInstance(UserConfig.selectedAccount).getCurrentUser();
            name = me != null ? UserObject.getFirstName(me) : "Solegram";
            // the avatar: a real one, so it has the theme's avatar shape
            final BackupImageView avatar = new BackupImageView(context);
            avatar.setRoundRadius(dp(19));
            final AvatarDrawable avatarDrawable = new AvatarDrawable();
            if (me != null) {
                avatarDrawable.setInfo(me);
                avatar.setForUserOrChat(me, avatarDrawable);
            }
            addView(avatar, LayoutHelper.createFrame(38, 38, Gravity.LEFT | Gravity.TOP, 52, (HEADER - 38) / 2f, 0, 0));

            // Telegram's own bubbles (on our wallpaper: the cell's own background is off)
            final ThemePreviewMessagesCell messages = new ThemePreviewMessagesCell(context, layout, 0);
            messages.setOverrideBackground(new ColorDrawable(0));
            messages.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            addView(messages, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM, 0, 0, 0, INPUT + 6));
            rim.setStyle(Paint.Style.STROKE);
            rim.setStrokeWidth(Math.max(1, dp(0.66f)));
        }

        private static Drawable icon(Context context, int res, int key) {
            final Drawable d = ContextCompat.getDrawable(context, res);
            final Drawable m = d != null ? d.mutate() : new ColorDrawable(0);
            m.setColorFilter(new PorterDuffColorFilter(Theme.getColor(key), PorterDuff.Mode.SRC_IN));
            return m;
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            blurred = null;
        }

        /** The wallpaper as the chat shows it: the whole card, cropped from the middle. */
        private void drawWallpaper(Canvas canvas, int w, int h) {
            if (wallpaper instanceof BitmapDrawable && ((BitmapDrawable) wallpaper).getBitmap() != null) {
                final Bitmap b = ((BitmapDrawable) wallpaper).getBitmap();
                final float scale = Math.max(w / (float) b.getWidth(), h / (float) b.getHeight());
                final int dw = (int) Math.ceil(b.getWidth() * scale), dh = (int) Math.ceil(b.getHeight() * scale);
                wallpaper.setBounds((w - dw) / 2, (h - dh) / 2, (w - dw) / 2 + dw, (h - dh) / 2 + dh);
            } else {
                wallpaper.setBounds(0, 0, w, h);
            }
            wallpaper.draw(canvas);
        }

        /** The glass: the wallpaper under it, blurred (made once, small), tinted with the theme's colour. */
        private void glass(Canvas canvas, float top, float bottom, int tintColor, boolean rimBelow) {
            final int w = getWidth(), h = getHeight();
            if (blurred == null && w > 0 && h > 0) {
                try {
                    final int sw = Math.max(1, w / 8), sh = Math.max(1, h / 8);
                    final Bitmap small = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
                    final Canvas c = new Canvas(small);
                    c.scale(sw / (float) w, sh / (float) h);
                    drawWallpaper(c, w, h);
                    Utilities.stackBlurBitmap(small, 6);
                    blurred = small;
                } catch (Throwable e) {
                    FileLog.e(e);
                }
            }
            if (blurred != null) {
                final BitmapShader shader = new BitmapShader(blurred, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                matrix.setScale(w / (float) blurred.getWidth(), h / (float) blurred.getHeight());
                shader.setLocalMatrix(matrix);
                glassPaint.setShader(shader);
                canvas.drawRect(0, top, w, bottom, glassPaint);
            }
            tint.setColor(Theme.multAlpha(tintColor, 0.62f));
            canvas.drawRect(0, top, w, bottom, tint);
            rim.setColor(Theme.multAlpha(0xFFFFFFFF, Theme.isCurrentThemeDark() ? 0.12f : 0.35f));
            final float y = rimBelow ? bottom - rim.getStrokeWidth() / 2f : top + rim.getStrokeWidth() / 2f;
            canvas.drawLine(0, y, w, y, rim);
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            drawWallpaper(canvas, getWidth(), getHeight());
        }

        @Override
        protected void dispatchDraw(@NonNull Canvas canvas) {
            final int w = getWidth(), h = getHeight();
            // the messages (and the avatar) first, the glass header and input over the wallpaper's edges
            super.dispatchDraw(canvas);
            canvas.save();
            canvas.clipRect(0, 0, w, dp(HEADER));
            glass(canvas, 0, dp(HEADER), Theme.getColor(Theme.key_actionBarDefault), true);
            canvas.restore();
            final int s = dp(24);
            back.setBounds(dp(14), (dp(HEADER) - s) / 2, dp(14) + s, (dp(HEADER) + s) / 2);
            back.draw(canvas);
            more.setBounds(w - dp(14) - s, (dp(HEADER) - s) / 2, w - dp(14), (dp(HEADER) + s) / 2);
            more.draw(canvas);
            text.setTypeface(AndroidUtilities.bold());
            text.setTextSize(dp(16));
            text.setColor(Theme.getColor(Theme.key_actionBarDefaultTitle));
            canvas.drawText(TextUtils.ellipsize(name, text, w - dp(160), TextUtils.TruncateAt.END).toString(), dp(100), dp(HEADER) / 2f - dp(1), text);
            text.setTypeface(null);
            text.setTextSize(dp(13));
            text.setColor(Theme.getColor(Theme.key_actionBarDefaultSubtitle));
            canvas.drawText(getString(R.string.SG_ThemeSharePreviewOnline), dp(100), dp(HEADER) / 2f + dp(16), text);
            // the avatar is a child: drawn again over the header's glass
            for (int i = 0; i < getChildCount(); i++) {
                final View child = getChildAt(i);
                if (child instanceof BackupImageView) {
                    drawChild(canvas, child, getDrawingTime());
                }
            }

            final float top = h - dp(INPUT);
            glass(canvas, top, h, Theme.getColor(Theme.key_chat_messagePanelBackground), false);
            tint.setColor(Theme.multAlpha(Theme.getColor(Theme.key_chat_messagePanelHint), 0.14f));
            rect.set(dp(50), top + dp(8), w - dp(50), h - dp(8));
            canvas.drawRoundRect(rect, rect.height() / 2f, rect.height() / 2f, tint);
            attach.setBounds(dp(13), (int) (top + (dp(INPUT) - s) / 2f), dp(13) + s, (int) (top + (dp(INPUT) + s) / 2f));
            attach.draw(canvas);
            mic.setBounds(w - dp(13) - s, (int) (top + (dp(INPUT) - s) / 2f), w - dp(13), (int) (top + (dp(INPUT) + s) / 2f));
            mic.draw(canvas);
            smile.setBounds((int) rect.right - dp(8) - s, (int) (top + (dp(INPUT) - s) / 2f), (int) rect.right - dp(8), (int) (top + (dp(INPUT) + s) / 2f));
            smile.draw(canvas);
            text.setTextSize(dp(15));
            text.setColor(Theme.getColor(Theme.key_chat_messagePanelHint));
            canvas.drawText(getString(R.string.SG_ThemeSharePreviewInput), rect.left + dp(14), top + dp(INPUT) / 2f + dp(5), text);
        }
    }

    /** At the start: a preview the app did not end (it was closed meanwhile) is undone. */
    public static void undoUnfinished() {
        if (SolegramThemeShare.hasPreviewSnapshot()) {
            SolegramThemeShare.restorePreviewSnapshot();
        }
    }
}
