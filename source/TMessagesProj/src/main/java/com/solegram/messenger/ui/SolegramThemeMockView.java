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
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.CubicBezierInterpolator;

import java.util.ArrayList;

/**
 * Solegram theme editor: three test screens of Solegram (the chat list, a chat, settings) drawn in the current
 * colours. Tapping a part of a screen picks its group of colours (a {@link com.solegram.messenger.SolegramThemeColors}
 * slot): every part of that group lights up, and the editor opens the colour picker for it.
 */
public class SolegramThemeMockView extends View {

    public interface Delegate {
        /** A part of the screen was tapped: its slot id, or "wallpaper". */
        void onPick(String slotId);
    }

    public static final String WALLPAPER = "wallpaper";

    private static final int SCREEN_CHATS = 0, SCREEN_CHAT = 1, SCREEN_SETTINGS = 2;
    // the phone is drawn in these dp and scaled to the width
    private static final float PHONE_W = 300, PHONE_H = 540, TABS_H = 52;

    private final Delegate delegate;
    private int screen = SCREEN_CHATS;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path path = new Path();

    /** The parts of the screen drawn last time, in view pixels: what a tap means. */
    private static final class Region {
        final RectF rect = new RectF();
        String slot;
        float radius;
    }
    private final ArrayList<Region> regions = new ArrayList<>();
    private int regionCount;

    private final RectF[] tabRects = {new RectF(), new RectF(), new RectF()};
    private float tabProgress; // where the selection of the tabs is (0..2)
    private ValueAnimator tabAnimator;

    private String highlighted;
    private float highlight;
    private ValueAnimator highlightAnimator;

    private float scale, phoneX, phoneY;

    public SolegramThemeMockView(Context context, Delegate delegate) {
        super(context);
        this.delegate = delegate;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final int width = MeasureSpec.getSize(widthMeasureSpec);
        final float available = width - dp(32);
        scale = Math.min(1.15f, available / dp(PHONE_W));
        final int height = (int) (dp(TABS_H) + dp(PHONE_H) * scale + dp(20));
        setMeasuredDimension(width, height);
    }

    // region drawing helpers

    private float px(float dpValue) {
        return dp(1) * dpValue;
    }

    private int color(int key) {
        return Theme.getColor(key);
    }

    /** Registers the part (in phone dp) for taps; the canvas is in phone dp scaled already. */
    private void region(String slot, float l, float t, float r, float b, float radius) {
        Region region;
        if (regionCount < regions.size()) {
            region = regions.get(regionCount);
        } else {
            region = new Region();
            regions.add(region);
        }
        regionCount++;
        region.slot = slot;
        region.radius = radius * scale;
        region.rect.set(phoneX + px(l) * scale, phoneY + px(t) * scale, phoneX + px(r) * scale, phoneY + px(b) * scale);
    }

    private void fillRect(int color, float l, float t, float r, float b, float radius, Canvas canvas) {
        paint.setShader(null);
        paint.setColor(color);
        rect.set(px(l), px(t), px(r), px(b));
        canvas.drawRoundRect(rect, px(radius), px(radius), paint);
    }

    /** Text from (x, baseline), the given size and colour, ellipsized to the width; returns its right edge. */
    private float drawText(Canvas canvas, CharSequence value, float x, float baseline, float size, int color, boolean bold, float maxWidth) {
        text.setTextSize(px(size));
        text.setColor(color);
        text.setTypeface(bold ? AndroidUtilities.bold() : null);
        final CharSequence s = TextUtils.ellipsize(value, text, px(maxWidth), TextUtils.TruncateAt.END);
        canvas.drawText(s, 0, s.length(), px(x), px(baseline), text);
        return x + text.measureText(s, 0, s.length()) / dp(1);
    }

    private float textWidth(CharSequence value, float size, boolean bold) {
        text.setTextSize(px(size));
        text.setTypeface(bold ? AndroidUtilities.bold() : null);
        return text.measureText(value, 0, value.length()) / dp(1);
    }

    private void checks(Canvas canvas, float x, float y, int color, boolean two) {
        stroke.setColor(color);
        stroke.setStrokeWidth(px(1.6f));
        path.rewind();
        path.moveTo(px(x), px(y));
        path.lineTo(px(x + 3), px(y + 3));
        path.lineTo(px(x + 9), px(y - 4));
        if (two) {
            path.moveTo(px(x + 5), px(y + 3));
            path.lineTo(px(x + 12), px(y - 4));
        }
        canvas.drawPath(path, stroke);
    }

    // endregion

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        regionCount = 0;
        drawTabs(canvas);

        final float phoneWidth = dp(PHONE_W) * scale;
        phoneX = (getWidth() - phoneWidth) / 2f;
        phoneY = dp(TABS_H);
        canvas.save();
        canvas.translate(phoneX, phoneY);
        canvas.scale(scale, scale);

        // the phone: a soft shadow and a frame round the screen
        rect.set(0, 0, px(PHONE_W), px(PHONE_H));
        paint.setShader(null);
        paint.setColor(0);
        paint.setShadowLayer(px(10), 0, px(3), 0x33000000);
        paint.setColor(color(Theme.key_windowBackgroundWhite));
        canvas.drawRoundRect(rect, px(30), px(30), paint);
        paint.clearShadowLayer();
        canvas.save();
        path.rewind();
        path.addRoundRect(rect, px(30), px(30), Path.Direction.CW);
        canvas.clipPath(path);
        switch (screen) {
            case SCREEN_CHATS:
                drawChats(canvas);
                break;
            case SCREEN_CHAT:
                drawChat(canvas);
                break;
            default:
                drawSettings(canvas);
                break;
        }
        canvas.restore();
        stroke.setStrokeWidth(px(1.2f));
        stroke.setColor(Theme.multAlpha(color(Theme.key_windowBackgroundWhiteBlackText), 0.12f));
        canvas.drawRoundRect(rect, px(30), px(30), stroke);
        canvas.restore();

        drawHighlight(canvas);
    }

    private void drawTabs(Canvas canvas) {
        final String[] names = {getString(R.string.SG_ThemeMockTabChats), getString(R.string.SG_ThemeMockTabChat), getString(R.string.SG_ThemeMockTabSettings)};
        final float w = Math.min(getWidth() - dp(32), dp(320));
        final float left = (getWidth() - w) / 2f, top = dp(6), h = dp(34);
        final float tabW = w / 3f;
        // the track and the moving selection, as a segmented control
        rect.set(left, top, left + w, top + h);
        paint.setShader(null);
        paint.setColor(Theme.multAlpha(color(Theme.key_windowBackgroundWhiteBlackText), 0.06f));
        canvas.drawRoundRect(rect, h / 2f, h / 2f, paint);
        final float sx = left + tabW * tabProgress;
        rect.set(sx + dp(3), top + dp(3), sx + tabW - dp(3), top + h - dp(3));
        paint.setColor(color(Theme.key_windowBackgroundWhite));
        paint.setShadowLayer(dp(2), 0, dp(1), 0x22000000);
        canvas.drawRoundRect(rect, (h - dp(6)) / 2f, (h - dp(6)) / 2f, paint);
        paint.clearShadowLayer();
        text.setTextSize(dp(13));
        for (int i = 0; i < 3; i++) {
            tabRects[i].set(left + tabW * i, top, left + tabW * (i + 1), top + h);
            final boolean sel = i == screen;
            text.setTypeface(sel ? AndroidUtilities.bold() : null);
            text.setColor(sel ? color(Theme.key_windowBackgroundWhiteBlackText) : color(Theme.key_windowBackgroundWhiteGrayText));
            final float tw = text.measureText(names[i]);
            canvas.drawText(names[i], tabRects[i].centerX() - tw / 2f, tabRects[i].centerY() + dp(4.5f), text);
        }
    }

    private void drawStatusBar(Canvas canvas, int color) {
        drawText(canvas, "9:41", 26, 22, 12, color, true, 60);
        fillRect(color, PHONE_W - 46, 13, PHONE_W - 26, 21, 3, canvas);
    }

    private void drawHeader(Canvas canvas, CharSequence title, boolean back) {
        final int bg = color(Theme.key_actionBarDefault);
        final int fg = color(Theme.key_actionBarDefaultTitle);
        final int icon = color(Theme.key_actionBarDefaultIcon);
        fillRect(bg, 0, 0, PHONE_W, 82, 0, canvas);
        region("headerBg", 0, 0, PHONE_W, 82, 0);
        drawStatusBar(canvas, fg);
        float x = 22;
        if (back) {
            stroke.setColor(icon);
            stroke.setStrokeWidth(px(2.2f));
            path.rewind();
            path.moveTo(px(30), px(52));
            path.lineTo(px(22), px(60));
            path.lineTo(px(30), px(68));
            canvas.drawPath(path, stroke);
            region("headerText", 14, 44, 40, 76, 8);
            x = 48;
        }
        final float right = drawText(canvas, title, x, 67, 20, fg, true, 160);
        region("headerText", x, 46, right, 72, 6);
        // the search and "more" buttons on the right
        stroke.setColor(icon);
        stroke.setStrokeWidth(px(2f));
        canvas.drawCircle(px(PHONE_W - 66), px(58), px(7), stroke);
        canvas.drawLine(px(PHONE_W - 61), px(63), px(PHONE_W - 56), px(68), stroke);
        paint.setColor(icon);
        for (int i = 0; i < 3; i++) {
            canvas.drawCircle(px(PHONE_W - 38 + i * 7), px(59), px(1.8f), paint);
        }
        region("headerText", PHONE_W - 80, 44, PHONE_W - 18, 74, 12);
    }

    private static final int[] AVATARS = {0xFFF07B6B, 0xFF5BA3E6, 0xFF8BC163, 0xFFF4A443, 0xFFA77FE0};

    private void drawChats(Canvas canvas) {
        final int bg = color(Theme.key_windowBackgroundWhite);
        fillRect(bg, 0, 82, PHONE_W, PHONE_H, 0, canvas);
        region("background", 0, 82, PHONE_W, PHONE_H, 0);
        drawHeader(canvas, "Solegram", false);

        final String[] names = {getString(R.string.SG_ThemeMockName1), getString(R.string.SG_ThemeMockName2), getString(R.string.SG_ThemeMockName3), getString(R.string.SG_ThemeMockName4), getString(R.string.SG_ThemeMockName5)};
        final String[] msgs = {getString(R.string.SG_ThemeMockMsg1), getString(R.string.SG_ThemeMockMsg2), getString(R.string.SG_ThemeMockMsg3), getString(R.string.SG_ThemeMockMsg4), getString(R.string.SG_ThemeMockMsg5)};
        final String[] times = {"12:30", "11:02", "10:47", "09:15", getString(R.string.SG_ThemeMockYesterday)};
        final float rowH = 70;
        for (int i = 0; i < 5; i++) {
            final float top = 88 + i * rowH;
            // avatar, with the "online" dot on the first one
            paint.setShader(null);
            paint.setColor(AVATARS[i]);
            canvas.drawCircle(px(44), px(top + rowH / 2f), px(25), paint);
            drawText(canvas, names[i].substring(0, 1), 38, top + rowH / 2f + 6, 17, 0xFFFFFFFF, true, 30);
            if (i == 0) {
                paint.setColor(bg);
                canvas.drawCircle(px(63), px(top + rowH / 2f + 18), px(7.5f), paint);
                paint.setColor(color(Theme.key_chats_onlineCircle));
                canvas.drawCircle(px(63), px(top + rowH / 2f + 18), px(5.5f), paint);
                region("online", 54, top + rowH / 2f + 9, 72, top + rowH / 2f + 27, 9);
            }
            // time (with read checks on two of them) at the top right
            final String time = times[i];
            final float timeW = textWidth(time, 12, false);
            final float timeX = PHONE_W - 16 - timeW;
            drawText(canvas, time, timeX, top + 26, 12, color(Theme.key_chats_date), false, 80);
            float dateLeft = timeX;
            if (i == 1 || i == 3) {
                checks(canvas, timeX - 17, top + 21, color(i == 1 ? Theme.key_chats_sentReadCheck : Theme.key_chats_sentCheck), i == 1);
                dateLeft = timeX - 18;
            }
            region("chatDate", dateLeft - 2, top + 12, PHONE_W - 12, top + 30, 4);
            final float nameRight = drawText(canvas, names[i], 80, top + 27, 15, color(Theme.key_chats_name), true, dateLeft - 90);
            region("chatName", 78, top + 12, nameRight + 2, top + 32, 4);
            // the last message, and a counter on two of the chats
            float msgMax = PHONE_W - 96;
            if (i == 0 || i == 2) {
                final String count = i == 0 ? "3" : "12";
                final float cw = Math.max(22, textWidth(count, 12, true) + 12);
                final float cl = PHONE_W - 16 - cw;
                final int counter = color(i == 0 ? Theme.key_chats_unreadCounter : Theme.key_chats_unreadCounterMuted);
                fillRect(counter, cl, top + 38, PHONE_W - 16, top + 60, 11, canvas);
                drawText(canvas, count, cl + (cw - textWidth(count, 12, true)) / 2f, top + 53.5f, 12, color(Theme.key_chats_unreadCounterText), true, cw);
                region(i == 0 ? "unread" : "muted", cl - 2, top + 36, PHONE_W - 14, top + 62, 12);
                msgMax -= cw + 8;
            }
            final float msgRight = drawText(canvas, msgs[i], 80, top + 52, 14, color(Theme.key_chats_message), false, msgMax);
            region("chatMessage", 78, top + 38, msgRight + 2, top + 58, 4);
            if (i < 4) {
                paint.setColor(color(Theme.key_divider));
                rect.set(px(80), px(top + rowH - 0.5f), px(PHONE_W), px(top + rowH));
                canvas.drawRect(rect, paint);
                region("divider", 80, top + rowH - 4, PHONE_W, top + rowH + 4, 0);
            }
        }

        // the new message button
        final float fx = PHONE_W - 46, fy = PHONE_H - 46;
        paint.setColor(color(Theme.key_chats_actionBackground));
        paint.setShadowLayer(px(6), 0, px(2), 0x33000000);
        canvas.drawCircle(px(fx), px(fy), px(27), paint);
        paint.clearShadowLayer();
        stroke.setColor(color(Theme.key_chats_actionIcon));
        stroke.setStrokeWidth(px(2.2f));
        canvas.drawLine(px(fx - 7), px(fy + 7), px(fx + 6), px(fy - 6), stroke);
        canvas.drawLine(px(fx - 7), px(fy + 7), px(fx - 8), px(fy + 8), stroke);
        region("fab", fx - 27, fy - 27, fx + 27, fy + 27, 27);
    }

    private Bitmap wallpaperBitmap;
    private BitmapShader wallpaperShader;
    private final Matrix wallpaperMatrix = new Matrix();

    private void drawWallpaper(Canvas canvas, float top, float bottom) {
        final Drawable wallpaper = Theme.getCachedWallpaperNonBlocking();
        canvas.save();
        canvas.clipRect(0, px(top), px(PHONE_W), px(bottom));
        if (wallpaper instanceof BitmapDrawable && ((BitmapDrawable) wallpaper).getBitmap() != null) {
            // centre-cropped, as the chat shows it
            final Bitmap bitmap = ((BitmapDrawable) wallpaper).getBitmap();
            final float w = px(PHONE_W), h = px(bottom - top);
            final float s = Math.max(w / bitmap.getWidth(), h / bitmap.getHeight());
            if (wallpaperShader == null || wallpaperBitmap != bitmap) {
                wallpaperBitmap = bitmap;
                wallpaperShader = new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            }
            wallpaperMatrix.setScale(s, s);
            wallpaperMatrix.postTranslate((w - bitmap.getWidth() * s) / 2f, px(top) + (h - bitmap.getHeight() * s) / 2f);
            wallpaperShader.setLocalMatrix(wallpaperMatrix);
            paint.setShader(wallpaperShader);
            paint.setColor(0xFFFFFFFF);
            canvas.drawRect(0, px(top), w, px(bottom), paint);
            paint.setShader(null);
        } else if (wallpaper != null) {
            wallpaper.setBounds(0, (int) px(top), (int) px(PHONE_W), (int) px(bottom));
            wallpaper.draw(canvas);
        } else {
            paint.setColor(color(Theme.key_chat_wallpaper));
            canvas.drawRect(0, px(top), px(PHONE_W), px(bottom), paint);
        }
        canvas.restore();
        region(WALLPAPER, 0, top, PHONE_W, bottom, 0);
    }

    /** A message bubble with its lines of text, time and (for mine) checks; its rect in phone dp. */
    private void drawBubble(Canvas canvas, boolean out, float top, CharSequence line, boolean link) {
        final float textSize = 15;
        final float lineW = textWidth(line, textSize, false);
        final String time = out ? "12:31" : "12:30";
        final float timeW = textWidth(time, 11, false) + (out ? 18 : 0);
        final float w = Math.min(PHONE_W - 44, lineW + timeW + 30);
        final float h = 38;
        final float l = out ? PHONE_W - 10 - w : 10;
        final float r = l + w;
        final int bubble = color(out ? Theme.key_chat_outBubble : Theme.key_chat_inBubble);
        paint.setShader(null);
        paint.setColor(bubble);
        if (out) {
            final int gradient = color(Theme.key_chat_outBubbleGradient1);
            if (gradient != 0) {
                paint.setShader(new LinearGradient(0, px(top), 0, px(top + h), bubble | 0xFF000000, gradient | 0xFF000000, Shader.TileMode.CLAMP));
            }
        }
        paint.setShadowLayer(px(1.5f), 0, px(0.5f), 0x1A000000);
        rect.set(px(l), px(top), px(r), px(top + h));
        canvas.drawRoundRect(rect, px(17), px(17), paint);
        paint.clearShadowLayer();
        paint.setShader(null);
        region(out ? "outBubble" : "inBubble", l, top, r, top + h, 17);

        final int textColor = color(link ? Theme.key_chat_messageLinkIn : out ? Theme.key_chat_messageTextOut : Theme.key_chat_messageTextIn);
        final float tr = drawText(canvas, line, l + 13, top + 24, textSize, textColor, false, w - timeW - 24);
        if (link) {
            paint.setColor(textColor);
            rect.set(px(l + 13), px(top + 27), px(tr), px(top + 28.2f));
            canvas.drawRect(rect, paint);
            region("links", l + 11, top + 8, tr + 2, top + 30, 4);
        } else {
            region(out ? "outText" : "inText", l + 11, top + 8, tr + 2, top + 30, 4);
        }
        final float tx = r - 10 - timeW;
        drawText(canvas, time, tx, top + 30, 11, color(out ? Theme.key_chat_outTimeText : Theme.key_chat_inTimeText), false, 60);
        if (out) {
            checks(canvas, r - 24, top + 26, color(Theme.key_chat_outSentCheckRead), true);
            region("checks", r - 27, top + 18, r - 8, top + 32, 4);
        }
    }

    private void drawChat(Canvas canvas) {
        drawWallpaper(canvas, 82, PHONE_H - 58);
        drawHeader(canvas, getString(R.string.SG_ThemeMockName1), true);

        drawBubble(canvas, false, 104, getString(R.string.SG_ThemeMockIn1), false);
        drawBubble(canvas, false, 148, "solegram.app", true);
        drawBubble(canvas, true, 200, getString(R.string.SG_ThemeMockOut1), false);
        drawBubble(canvas, false, 252, getString(R.string.SG_ThemeMockIn2), false);
        drawBubble(canvas, true, 304, getString(R.string.SG_ThemeMockOut2), false);

        // the input bar
        final float top = PHONE_H - 58;
        fillRect(color(Theme.key_chat_messagePanelBackground), 0, top, PHONE_W, PHONE_H, 0, canvas);
        region("panel", 0, top, PHONE_W, PHONE_H, 0);
        final int icons = color(Theme.key_chat_messagePanelIcons);
        stroke.setColor(icons);
        stroke.setStrokeWidth(px(1.8f));
        canvas.drawCircle(px(26), px(top + 26), px(9), stroke);
        paint.setColor(icons);
        canvas.drawCircle(px(23), px(top + 24), px(1.3f), paint);
        canvas.drawCircle(px(29), px(top + 24), px(1.3f), paint);
        region("icons", 14, top + 14, 38, top + 38, 12);
        drawText(canvas, getString(R.string.SG_ThemeMockInput), 46, top + 31, 15, color(Theme.key_chat_messagePanelHint), false, 160);
        // attach clip
        canvas.drawLine(px(PHONE_W - 82), px(top + 18), px(PHONE_W - 82), px(top + 32), stroke);
        canvas.drawCircle(px(PHONE_W - 82), px(top + 32), px(4), stroke);
        region("icons", PHONE_W - 94, top + 14, PHONE_W - 70, top + 40, 12);
        // send
        final float sx = PHONE_W - 32, sy = top + 27;
        paint.setColor(color(Theme.key_chat_messagePanelSend));
        canvas.drawCircle(px(sx), px(sy), px(17), paint);
        stroke.setColor(0xFFFFFFFF);
        stroke.setStrokeWidth(px(2.2f));
        canvas.drawLine(px(sx), px(sy + 7), px(sx), px(sy - 7), stroke);
        canvas.drawLine(px(sx - 6), px(sy - 1), px(sx), px(sy - 7), stroke);
        canvas.drawLine(px(sx + 6), px(sy - 1), px(sx), px(sy - 7), stroke);
        region("send", sx - 18, sy - 18, sx + 18, sy + 18, 18);
    }

    private void drawSettings(Canvas canvas) {
        fillRect(color(Theme.key_windowBackgroundGray), 0, 82, PHONE_W, PHONE_H, 0, canvas);
        region("background", 0, 82, PHONE_W, PHONE_H, 0);
        drawHeader(canvas, getString(R.string.SG_ThemeMockTabSettings), false);

        final int card = color(Theme.key_windowBackgroundWhite);
        final int textColor = color(Theme.key_windowBackgroundWhiteBlackText);
        final int secondary = color(Theme.key_windowBackgroundWhiteGrayText2);
        final int icons = color(Theme.key_windowBackgroundWhiteGrayIcon);

        // the first card: a section title in the accent, rows with icons, a switch, a value
        float top = 96;
        fillRect(card, 12, top, PHONE_W - 12, top + 176, 18, canvas);
        region("background", 12, top, PHONE_W - 12, top + 176, 18);
        final float headRight = drawText(canvas, getString(R.string.SG_ThemeMockGeneral), 28, top + 26, 13.5f, color(Theme.key_windowBackgroundWhiteBlueHeader), true, 200);
        region("accent", 26, top + 12, headRight + 2, top + 32, 4);
        final String[] rows = {getString(R.string.SG_ThemeMockNotifications), getString(R.string.SG_ThemeMockLanguage), getString(R.string.SG_ThemeMockPrivacy)};
        for (int i = 0; i < 3; i++) {
            final float rt = top + 40 + i * 44;
            // the row's icon: a bell, a globe, a lock - simple shapes in the icons' colour
            stroke.setColor(icons);
            stroke.setStrokeWidth(px(1.8f));
            if (i == 0) {
                canvas.drawRoundRect(px(30), px(rt + 12), px(44), px(rt + 26), px(6), px(6), stroke);
                canvas.drawLine(px(35), px(rt + 29), px(39), px(rt + 29), stroke);
            } else if (i == 1) {
                canvas.drawCircle(px(37), px(rt + 21), px(8), stroke);
                canvas.drawLine(px(29), px(rt + 21), px(45), px(rt + 21), stroke);
            } else {
                canvas.drawRoundRect(px(30), px(rt + 18), px(44), px(rt + 29), px(3), px(3), stroke);
                canvas.drawArc(px(32.5f), px(rt + 11), px(41.5f), px(rt + 23), 180, 180, false, stroke);
            }
            region("icons", 24, rt + 6, 50, rt + 36, 8);
            final float tr = drawText(canvas, rows[i], 62, rt + 26, 15, textColor, false, 150);
            region("text", 60, rt + 10, tr + 2, rt + 32, 4);
            if (i == 0) {
                // a switch that is on
                final float sl = PHONE_W - 66, st = rt + 11;
                fillRect(color(Theme.key_switchTrackChecked), sl, st, sl + 38, st + 22, 11, canvas);
                paint.setColor(0xFFFFFFFF);
                canvas.drawCircle(px(sl + 27), px(st + 11), px(8.5f), paint);
                region("switches", sl - 2, st - 2, sl + 40, st + 24, 12);
            } else if (i == 1) {
                final String value = getString(R.string.SG_ThemeMockLanguageValue);
                final float vw = textWidth(value, 14, false);
                drawText(canvas, value, PHONE_W - 30 - vw, rt + 26, 14, secondary, false, 120);
                region("secondary", PHONE_W - 32 - vw, rt + 10, PHONE_W - 28, rt + 32, 4);
            }
            if (i < 2) {
                paint.setColor(color(Theme.key_divider));
                canvas.drawRect(px(62), px(rt + 43.5f), px(PHONE_W - 12), px(rt + 44), paint);
                region("divider", 62, rt + 40, PHONE_W - 12, rt + 47, 0);
            }
        }

        // the second card: a checkbox and a filled button
        top += 190;
        fillRect(card, 12, top, PHONE_W - 12, top + 112, 18, canvas);
        region("background", 12, top, PHONE_W - 12, top + 112, 18);
        fillRect(color(Theme.key_checkboxSquareBackground), 28, top + 16, 48, top + 36, 6, canvas);
        checks(canvas, 32, top + 26, color(Theme.key_checkboxSquareCheck), false);
        region("checkboxes", 24, top + 12, 52, top + 40, 8);
        final float cr = drawText(canvas, getString(R.string.SG_ThemeMockAutoload), 62, top + 31, 15, textColor, false, 190);
        region("text", 60, top + 15, cr + 2, top + 37, 4);
        fillRect(color(Theme.key_featuredStickers_addButton), 28, top + 54, PHONE_W - 28, top + 96, 14, canvas);
        final String save = getString(R.string.SG_ThemeMockSave);
        drawText(canvas, save, PHONE_W / 2f - textWidth(save, 15, true) / 2f, top + 80, 15, color(Theme.key_featuredStickers_buttonText), true, 200);
        region("buttons", 28, top + 54, PHONE_W - 28, top + 96, 14);

        // a pop-up menu over the bottom
        top += 126;
        final float ml = PHONE_W - 170;
        paint.setColor(color(Theme.key_actionBarDefaultSubmenuBackground));
        paint.setShadowLayer(px(8), 0, px(2), 0x33000000);
        rect.set(px(ml), px(top), px(PHONE_W - 16), px(top + 116));
        canvas.drawRoundRect(rect, px(16), px(16), paint);
        paint.clearShadowLayer();
        final String[] items = {getString(R.string.SG_ThemeMockReply), getString(R.string.SG_ThemeMockCopy), getString(R.string.SG_ThemeMockForward)};
        for (int i = 0; i < 3; i++) {
            final float it = top + 10 + i * 34;
            stroke.setColor(color(Theme.key_actionBarDefaultSubmenuItemIcon));
            stroke.setStrokeWidth(px(1.6f));
            canvas.drawCircle(px(ml + 22), px(it + 16), px(6), stroke);
            drawText(canvas, items[i], ml + 40, it + 21, 14, color(Theme.key_actionBarDefaultSubmenuItem), false, 110);
        }
        region("menus", ml, top, PHONE_W - 16, top + 116, 16);
    }

    private void drawHighlight(Canvas canvas) {
        if (highlighted == null || highlight <= 0) {
            return;
        }
        // every part of the group: a soft wash and an outline in the accent
        final int accent = color(Theme.key_windowBackgroundWhiteBlueText4);
        for (int i = 0; i < regionCount; i++) {
            final Region region = regions.get(i);
            if (!highlighted.equals(region.slot)) {
                continue;
            }
            rect.set(region.rect);
            rect.inset(-dp(2), -dp(2));
            final float rad = region.radius + dp(2);
            paint.setShader(null);
            paint.setColor(Theme.multAlpha(accent, 0.18f * highlight));
            canvas.drawRoundRect(rect, rad, rad, paint);
            stroke.setColor(Theme.multAlpha(accent, highlight));
            stroke.setStrokeWidth(dp(2));
            canvas.drawRoundRect(rect, rad, rad, stroke);
        }
    }

    // region touches

    private float downX, downY;

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                return true;
            case MotionEvent.ACTION_UP:
                if (Math.abs(event.getX() - downX) < dp(10) && Math.abs(event.getY() - downY) < dp(10)) {
                    onTap(event.getX(), event.getY());
                    performClick();
                }
                return true;
        }
        return super.onTouchEvent(event);
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    private void onTap(float x, float y) {
        for (int i = 0; i < 3; i++) {
            if (tabRects[i].contains(x, y)) {
                selectScreen(i);
                return;
            }
        }
        // the topmost part drawn under the finger: the regions go from the back to the front
        String slot = null;
        for (int i = regionCount - 1; i >= 0; i--) {
            final Region region = regions.get(i);
            if (region.rect.contains(x, y)) {
                slot = region.slot;
                break;
            }
        }
        if (slot == null) {
            return;
        }
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        flash(slot);
        delegate.onPick(slot);
    }

    private void selectScreen(int index) {
        if (screen == index) {
            return;
        }
        final float from = tabProgress;
        screen = index;
        if (tabAnimator != null) {
            tabAnimator.cancel();
        }
        tabAnimator = ValueAnimator.ofFloat(from, index);
        tabAnimator.addUpdateListener(a -> {
            tabProgress = (float) a.getAnimatedValue();
            invalidate();
        });
        tabAnimator.setDuration(260);
        tabAnimator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
        tabAnimator.start();
        highlighted = null;
        invalidate();
    }

    /** Lights up every part of the group for a moment. */
    public void flash(String slot) {
        highlighted = slot;
        if (highlightAnimator != null) {
            highlightAnimator.cancel();
        }
        highlightAnimator = ValueAnimator.ofFloat(0, 1, 1, 0);
        highlightAnimator.addUpdateListener(a -> {
            highlight = (float) a.getAnimatedValue();
            invalidate();
        });
        highlightAnimator.setDuration(1400);
        highlightAnimator.start();
    }

    // endregion
}
