/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.graphics.ColorUtils;

import com.solegram.messenger.SolegramThemeColors;
import com.solegram.messenger.SolegramThemeShare;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;

import java.util.Map;

/**
 * Solegram: a theme sent in a chat (a {@code .sgtheme} file): a little chat drawn in its colours (light and dark), what
 * it brings, and the button that puts it on, with the circle of new colours growing from the button.
 */
public class SolegramThemePreviewActivity extends BaseFragment {

    private final SolegramThemeShare.Theme theme;
    private boolean previewDark = Theme.isCurrentThemeDark();
    private PreviewView preview;
    private ButtonWithCounterView installButton;
    private boolean installed;

    public SolegramThemePreviewActivity(SolegramThemeShare.Theme theme) {
        this.theme = theme;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(getString(R.string.SG_ThemeShareTitle));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        final FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));
        final ScrollView scroll = new ScrollView(context);
        scroll.setClipToPadding(false);
        scroll.setPadding(0, 0, 0, dp(96));
        root.addView(scroll, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        final LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(column, new ScrollView.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final TextView name = new TextView(context);
        name.setText(theme.name);
        name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 24);
        name.setTypeface(AndroidUtilities.bold());
        name.setGravity(Gravity.CENTER);
        name.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setSingleLine(true);
        column.addView(name, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 18, 24, 0));

        final TextView about = new TextView(context);
        about.setText(describe());
        about.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        about.setGravity(Gravity.CENTER);
        about.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        column.addView(about, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 4, 24, 14));

        preview = new PreviewView(context);
        column.addView(preview, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 420, 24, 0, 24, 0));

        // light or dark: both are in the theme (each falls back to Telegram's own colours where it has none)
        final LinearLayout modes = new LinearLayout(context);
        modes.setOrientation(LinearLayout.HORIZONTAL);
        modes.setGravity(Gravity.CENTER);
        final TextView light = chip(context, getString(R.string.SG_ThemeShareLight));
        final TextView dark = chip(context, getString(R.string.SG_ThemeShareDark));
        modes.addView(light, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 36, 0, 0, 6, 0));
        modes.addView(dark, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 36, 6, 0, 0, 0));
        column.addView(modes, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 16, 24, 0));
        final Runnable updateChips = () -> {
            styleChip(light, !previewDark);
            styleChip(dark, previewDark);
        };
        light.setOnClickListener(v -> {
            if (previewDark) {
                previewDark = false;
                updateChips.run();
                preview.switchMode(false);
            }
        });
        dark.setOnClickListener(v -> {
            if (!previewDark) {
                previewDark = true;
                updateChips.run();
                preview.switchMode(true);
            }
        });
        updateChips.run();

        final TextView note = new TextView(context);
        note.setText(getString(R.string.SG_ThemeShareNote));
        note.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        note.setGravity(Gravity.CENTER);
        note.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        column.addView(note, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 32, 14, 32, 0));

        installButton = new ButtonWithCounterView(context, getResourceProvider());
        installButton.setText(getString(R.string.SG_ThemeShareInstall), false);
        installButton.setOnClickListener(v -> install());
        root.addView(installButton, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 48, Gravity.BOTTOM, 16, 0, 16, 24));

        fragmentView = root;
        return root;
    }

    private CharSequence describe() {
        final int colors = Math.max(theme.light.size(), theme.dark.size());
        final StringBuilder sb = new StringBuilder();
        if (colors > 0) {
            sb.append(LocaleController.formatString(R.string.SG_ThemeShareColors, colors));
        }
        if (!theme.settings.isEmpty()) {
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append(LocaleController.formatString(R.string.SG_ThemeShareSettings, theme.settings.size()));
        }
        return sb.toString();
    }

    private TextView chip(Context context, String text) {
        final TextView v = new TextView(context);
        v.setText(text);
        v.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        v.setTypeface(AndroidUtilities.bold());
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(18), 0, dp(18), 0);
        return v;
    }

    private void styleChip(TextView v, boolean selected) {
        final int accent = getThemedColor(Theme.key_featuredStickers_addButton);
        v.setTextColor(selected ? 0xFFFFFFFF : getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        v.setBackground(Theme.createRoundRectDrawable(dp(18), selected ? accent : Theme.multAlpha(getThemedColor(Theme.key_windowBackgroundWhiteBlackText), 0.08f)));
    }

    private void install() {
        if (installed || getParentActivity() == null) {
            return;
        }
        installed = true;
        installButton.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        final int[] loc = new int[2];
        installButton.getLocationInWindow(loc);
        final float x = loc[0] + installButton.getWidth() / 2f, y = loc[1] + installButton.getHeight() / 2f;
        final boolean dark = Theme.isCurrentThemeDark();
        // the same circle of new colours as when a theme is made of three colours
        SolegramThemeReveal.start(getParentActivity(), x, y, theme.accent(dark), () -> {
            SolegramThemeShare.apply(theme);
            Theme.refreshThemeColors(false, true);
            if (getParentLayout() != null) {
                getParentLayout().rebuildAllFragmentViews(false, false);
            }
        });
        installButton.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        AndroidUtilities.runOnUIThread(() -> {
            if (installButton != null) {
                installButton.setText(getString(R.string.SG_ThemeShareInstalled), true);
            }
            if (!theme.settings.isEmpty() && fragmentView != null) {
                BulletinFactory.of(this).createSimpleBulletin(R.raw.contact_check, getString(R.string.SG_ThemeShareInstalled),
                    getString(R.string.SG_ThemeShareRestartHint)).show();
            }
        }, 700);
    }

    /** A little chat in the theme's colours: header, three messages, the input; it switches day/night smoothly. */
    private final class PreviewView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final Path path = new Path();
        private float mode = previewDark ? 1f : 0f;
        private ValueAnimator modeAnimator;
        private final long start = SystemClock.uptimeMillis();

        PreviewView(Context context) {
            super(context);
            text.setTextSize(dp(14));
        }

        void switchMode(boolean dark) {
            if (modeAnimator != null) {
                modeAnimator.cancel();
            }
            modeAnimator = ValueAnimator.ofFloat(mode, dark ? 1f : 0f);
            modeAnimator.setDuration(380);
            modeAnimator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
            modeAnimator.addUpdateListener(a -> {
                mode = (float) a.getAnimatedValue();
                invalidate();
            });
            modeAnimator.start();
        }

        /** The theme's colour of a slot, or (when it has none) Telegram's colour of today's theme or a sensible one. */
        private int slot(String id, boolean dark, int lightDefault, int darkDefault) {
            final Map<String, Integer> map = dark ? theme.dark : theme.light;
            final Integer c = map.get(id);
            if (c != null) {
                return c;
            }
            final SolegramThemeColors.Slot s = SolegramThemeColors.slot(id);
            if (s != null && dark == Theme.isCurrentThemeDark() && !SolegramThemeColors.isSet(dark, id)) {
                return Theme.getColor(s.shownKey);
            }
            return dark ? darkDefault : lightDefault;
        }

        private int c(String id, int lightDefault, int darkDefault) {
            return ColorUtils.blendARGB(slot(id, false, lightDefault, darkDefault), slot(id, true, lightDefault, darkDefault), mode);
        }

        /** A message's appearance: 0 → 1 over its own moment after the screen opened. */
        private float appear(int index) {
            final float t = (SystemClock.uptimeMillis() - start - 120 - index * 140) / 420f;
            return CubicBezierInterpolator.EASE_OUT_BACK.getInterpolation(Math.max(0f, Math.min(1f, t)));
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            final float w = getWidth(), h = getHeight();
            final float radius = dp(22);
            path.rewind();
            rect.set(0, 0, w, h);
            path.addRoundRect(rect, radius, radius, Path.Direction.CW);
            canvas.save();
            canvas.clipPath(path);

            final int accent = c("accent", 0xFF3390EC, 0xFF5EA9F0);
            final int background = c("background", 0xFFFFFFFF, 0xFF1C1C1E);
            // the chat's wallpaper: the background tinted with the accent
            paint.setShader(new LinearGradient(0, 0, w, h, ColorUtils.blendARGB(background, accent, 0.10f), ColorUtils.blendARGB(background, accent, 0.22f), Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, paint);
            paint.setShader(null);

            // the header
            final float headerH = dp(58);
            paint.setColor(c("headerBg", 0xFFFFFFFF, 0xFF232326));
            canvas.drawRect(0, 0, w, headerH, paint);
            paint.setColor(c("headerText", 0xFF000000, 0xFFFFFFFF));
            paint.setStrokeWidth(dp(2));
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            canvas.drawLine(dp(18), headerH / 2f, dp(26), headerH / 2f - dp(8), paint);
            canvas.drawLine(dp(18), headerH / 2f, dp(26), headerH / 2f + dp(8), paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(accent);
            canvas.drawCircle(dp(56), headerH / 2f, dp(17), paint);
            text.setColor(0xFFFFFFFF);
            text.setTypeface(AndroidUtilities.bold());
            text.setTextSize(dp(15));
            final String letter = theme.name.isEmpty() ? "S" : theme.name.substring(0, 1).toUpperCase();
            canvas.drawText(letter, dp(56) - text.measureText(letter) / 2f, headerH / 2f + dp(5), text);
            text.setColor(c("headerText", 0xFF000000, 0xFFFFFFFF));
            canvas.drawText(TextUtils.ellipsize(theme.name, text, w - dp(100), TextUtils.TruncateAt.END).toString(), dp(82), headerH / 2f - dp(2), text);
            text.setTypeface(null);
            text.setTextSize(dp(12.5f));
            text.setColor(c("secondary", 0xFF8A8A8E, 0xFF98989E));
            canvas.drawText(getString(R.string.SG_ThemeSharePreviewOnline), dp(82), headerH / 2f + dp(14), text);

            // the messages
            float y = headerH + dp(18);
            y = bubble(canvas, 0, false, getString(R.string.SG_ThemeSharePreviewMsg1), y, w);
            y = bubble(canvas, 1, true, getString(R.string.SG_ThemeSharePreviewMsg2), y + dp(8), w);
            bubble(canvas, 2, false, getString(R.string.SG_ThemeSharePreviewMsg3), y + dp(8), w);

            // the input
            final float panelH = dp(56);
            paint.setColor(c("panel", 0xFFFFFFFF, 0xFF232326));
            canvas.drawRect(0, h - panelH, w, h, paint);
            paint.setColor(Theme.multAlpha(c("secondary", 0xFF8A8A8E, 0xFF98989E), 0.18f));
            rect.set(dp(12), h - panelH + dp(10), w - dp(60), h - dp(10));
            canvas.drawRoundRect(rect, dp(18), dp(18), paint);
            text.setColor(c("secondary", 0xFF8A8A8E, 0xFF98989E));
            text.setTextSize(dp(14));
            canvas.drawText(getString(R.string.SG_ThemeSharePreviewInput), dp(26), h - panelH / 2f + dp(5), text);
            paint.setColor(c("send", 0xFF3390EC, 0xFF5EA9F0));
            canvas.drawCircle(w - dp(32), h - panelH / 2f, dp(18), paint);
            paint.setColor(0xFFFFFFFF);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2.2f));
            canvas.drawLine(w - dp(32), h - panelH / 2f + dp(7), w - dp(32), h - panelH / 2f - dp(7), paint);
            canvas.drawLine(w - dp(32), h - panelH / 2f - dp(7), w - dp(38), h - panelH / 2f - dp(1), paint);
            canvas.drawLine(w - dp(32), h - panelH / 2f - dp(7), w - dp(26), h - panelH / 2f - dp(1), paint);
            paint.setStyle(Paint.Style.FILL);

            canvas.restore();
            if (SystemClock.uptimeMillis() - start < 1200) {
                postInvalidateOnAnimation();
            }
        }

        private float bubble(Canvas canvas, int index, boolean out, String message, float top, float w) {
            final float a = appear(index);
            text.setTextSize(dp(14.5f));
            text.setTypeface(null);
            final float maxText = w * 0.62f;
            final String line = TextUtils.ellipsize(message, text, maxText, TextUtils.TruncateAt.END).toString();
            final float tw = text.measureText(line);
            final float bw = tw + dp(28 + (out ? 22 : 0)), bh = dp(40);
            final float left = out ? w - dp(12) - bw : dp(12);
            canvas.save();
            canvas.translate(0, (1f - a) * dp(16));
            canvas.scale(0.85f + 0.15f * a, 0.85f + 0.15f * a, out ? left + bw : left, top + bh);
            final int alpha = (int) (0xFF * Math.min(1f, a));
            rect.set(left, top, left + bw, top + bh);
            if (out) {
                final int c1 = c("outBubble", 0xFFE1FFC7, 0xFF3D6A97);
                final int c2 = c("outGradient", c1, c1);
                paint.setShader(new LinearGradient(rect.left, rect.top, rect.right, rect.bottom, c1, c2, Shader.TileMode.CLAMP));
            } else {
                paint.setColor(c("inBubble", 0xFFFFFFFF, 0xFF2C2C2E));
            }
            paint.setAlpha(alpha);
            canvas.drawRoundRect(rect, dp(16), dp(16), paint);
            paint.setShader(null);
            text.setColor(out ? c("outText", 0xFF000000, 0xFFFFFFFF) : c("inText", 0xFF000000, 0xFFFFFFFF));
            text.setAlpha(alpha);
            canvas.drawText(line, left + dp(14), top + bh / 2f + dp(5), text);
            if (out) {
                // the read checks
                paint.setColor(c("checks", 0xFF5DC452, 0xFFFFFFFF));
                paint.setAlpha(alpha);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(1.6f));
                final float cx = left + bw - dp(22), cy = top + bh / 2f + dp(2);
                canvas.drawLine(cx, cy, cx + dp(3), cy + dp(3), paint);
                canvas.drawLine(cx + dp(3), cy + dp(3), cx + dp(9), cy - dp(4), paint);
                canvas.drawLine(cx + dp(5), cy + dp(3), cx + dp(11), cy - dp(4), paint);
                paint.setStyle(Paint.Style.FILL);
            }
            canvas.restore();
            paint.setAlpha(0xFF);
            text.setAlpha(0xFF);
            return top + bh;
        }
    }
}
