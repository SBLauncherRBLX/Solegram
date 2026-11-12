/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.Switch;

/**
 * The parts of the Solegram AI settings page: its glowing header, the two big cards and the feature rows, all in
 * the theme's Solegram AI colours ({@link SolegramAiSheet#brand()}).
 */
public final class SolegramAiPageViews {

    private SolegramAiPageViews() {
    }

    /** A view springs down a little while pressed, and back with a soft overshoot. */
    @SuppressLint("ClickableViewAccessibility")
    static void pressSpring(View view) {
        view.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(140).setInterpolator(CubicBezierInterpolator.EASE_OUT).start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.animate().scaleX(1f).scaleY(1f).setDuration(380).setInterpolator(CubicBezierInterpolator.EASE_OUT_BACK).start();
                    break;
            }
            return false;
        });
    }

    // region Header

    /**
     * "Solegram AI" over a soft, slowly drifting glow of the theme's colours. The glow has no top: it goes on up under
     * the header and the status bar (the view draws past its own top), and melts into the page's colour towards the
     * cards below.
     */
    public static class Hero extends FrameLayout {
        private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fade = new Paint();
        private final Theme.ResourcesProvider resourcesProvider;
        private final LinearLayout texts;
        private boolean entered;
        private final long born = SystemClock.uptimeMillis();

        public Hero(Context context, CharSequence subtitle, Theme.ResourcesProvider resourcesProvider) {
            super(context);
            this.resourcesProvider = resourcesProvider;
            setWillNotDraw(false);
            setClipChildren(false);
            texts = new LinearLayout(context);
            texts.setOrientation(LinearLayout.VERTICAL);
            final TextView title = new TextView(context);
            title.setText("Solegram AI");
            title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 40);
            title.setTypeface(AndroidUtilities.bold());
            title.setIncludeFontPadding(false);
            title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
            texts.addView(title);
            final TextView sub = new TextView(context);
            sub.setText(subtitle);
            sub.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
            final int accent = SolegramAiSheet.brand()[1];
            sub.setTextColor(isDark() ? ColorUtils.blendARGB(accent, 0xFFFFFFFF, 0.35f) : ColorUtils.blendARGB(accent, 0xFF000000, 0.12f));
            texts.addView(sub, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 10, 0, 0));
            addView(texts, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM | Gravity.LEFT, 24, 0, 24, 34));
        }

        private boolean isDark() {
            return ColorUtils.calculateLuminance(Theme.getColor(Theme.key_windowBackgroundGray, resourcesProvider)) < 0.4;
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            if (!entered) {
                // the title rises in once
                entered = true;
                texts.setAlpha(0f);
                texts.setTranslationY(dp(14));
                texts.animate().alpha(1f).translationY(0).setStartDelay(60).setDuration(520).setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).start();
            }
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(dp(206), MeasureSpec.EXACTLY));
        }

        private void blob(Canvas canvas, float x, float y, float r, int color, float alpha) {
            glow.setShader(new RadialGradient(x, y, r, new int[]{ColorUtils.setAlphaComponent(color, (int) (255 * alpha)), ColorUtils.setAlphaComponent(color, 0)}, null, Shader.TileMode.CLAMP));
            canvas.drawCircle(x, y, r, glow);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final float w = getWidth(), h = getHeight();
            final int[] c = SolegramAiSheet.brand();
            final float k = isDark() ? 1f : 0.6f;
            final float t = (SystemClock.uptimeMillis() - born) / 1000f;
            final float d = dp(18);
            // the glow reaches well above the view (under the header and the status bar): the list does not clip it
            final float top = -h * 1.4f;
            final int layer = canvas.saveLayer(-w, top, w * 2, h, null);
            blob(canvas, w * 0.16f + d * (float) Math.sin(t * 0.35), h * 0.5f + d * (float) Math.cos(t * 0.29), w * 0.62f, c[0], 0.42f * k);
            blob(canvas, w * 0.5f + d * (float) Math.cos(t * 0.27), h * 0.3f + d * (float) Math.sin(t * 0.33), w * 0.6f, c[1], 0.34f * k);
            blob(canvas, w * 0.86f + d * (float) Math.sin(t * 0.31 + 1), h * 0.5f + d * (float) Math.cos(t * 0.23), w * 0.52f, c[3], 0.34f * k);
            blob(canvas, w * 0.74f + d * (float) Math.cos(t * 0.21), -h * 0.15f, w * 0.55f, c[2], 0.24f * k);
            blob(canvas, w * 0.3f + d * (float) Math.sin(t * 0.19), -h * 0.45f, w * 0.6f, c[1], 0.18f * k);
            // towards the cards it melts into the page's colour
            final int page = Theme.getColor(Theme.key_windowBackgroundGray, resourcesProvider);
            fade.setShader(new LinearGradient(0, h * 0.42f, 0, h, ColorUtils.setAlphaComponent(page, 0), page, Shader.TileMode.CLAMP));
            canvas.drawRect(-w, h * 0.42f, w * 2, h, fade);
            canvas.restoreToCount(layer);
            if (isAttachedToWindow()) {
                postInvalidateDelayed(48); // the slow drift: about 20 frames a second are plenty for it
            }
        }
    }

    // endregion

    // region Cards

    /** Two big cards side by side, of one height. */
    public static class Cards extends LinearLayout {
        public Cards(Context context) {
            super(context);
            setOrientation(HORIZONTAL);
            setPadding(dp(14), dp(2), dp(14), dp(12));
            setClipChildren(false);
        }

        public Card add(Card card) {
            addView(card, LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1f, getChildCount() == 0 ? 0 : 12, 0, 0, 0));
            return card;
        }
    }

    /** A big card: a mark in the theme's colours, a title, what it does, and a state pill when it is a switch. */
    public static class Card extends LinearLayout {
        private final TextView state;
        private final Theme.ResourcesProvider resourcesProvider;

        public Card(Context context, View mark, CharSequence title, CharSequence text, Theme.ResourcesProvider resourcesProvider) {
            super(context);
            this.resourcesProvider = resourcesProvider;
            setOrientation(VERTICAL);
            setPadding(dp(18), dp(18), dp(16), dp(18));
            final int bg = Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider);
            final int ink = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider);
            setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(26), bg, ColorUtils.blendARGB(bg, ink, 0.07f)));
            addView(mark, LayoutHelper.createLinear(38, 38));
            final TextView t = new TextView(context);
            t.setText(title);
            t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 19);
            t.setTypeface(AndroidUtilities.bold());
            t.setTextColor(ink);
            t.setMaxLines(2);
            t.setEllipsize(TextUtils.TruncateAt.END);
            addView(t, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 16, 0, 0));
            final TextView d = new TextView(context);
            d.setText(text);
            d.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            d.setLineSpacing(dp(1.5f), 1f);
            d.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
            addView(d, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 6, 0, 0));
            state = new TextView(context);
            state.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            state.setTypeface(AndroidUtilities.bold());
            state.setPadding(dp(10), dp(4), dp(10), dp(4));
            state.setVisibility(GONE);
            addView(state, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 12, 0, 0));
            pressSpring(this);
        }

        /** The card is a switch: its state under the text, as a little pill in the theme's colour when on. */
        public Card setState(CharSequence text, boolean on, boolean animated) {
            final boolean was = state.getVisibility() == VISIBLE;
            state.setVisibility(VISIBLE);
            final Runnable apply = () -> {
                state.setText(text);
                final int accent = SolegramAiSheet.brand()[1];
                if (on) {
                    state.setTextColor(accent);
                    final GradientDrawable pill = new GradientDrawable();
                    pill.setColor(ColorUtils.setAlphaComponent(accent, 0x22));
                    pill.setCornerRadius(dp(12));
                    state.setBackground(pill);
                } else {
                    state.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
                    state.setBackground(null);
                }
            };
            if (animated && was) {
                state.animate().alpha(0f).scaleX(0.9f).scaleY(0.9f).setDuration(120).withEndAction(() -> {
                    apply.run();
                    state.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(CubicBezierInterpolator.EASE_OUT_BACK).start();
                }).start();
            } else {
                apply.run();
            }
            return this;
        }
    }

    /** The mark of a card: the Solegram AI mark, or a speech bubble with a spark; in the theme's colours. */
    public static class CardMark extends View {
        public static final int ASSISTANT = 0, CATCH_UP = 1;
        private final int kind;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF rect = new RectF();
        private final SolegramAiSheet.Spark spark;

        public CardMark(Context context, int kind) {
            super(context);
            this.kind = kind;
            spark = kind == ASSISTANT ? new SolegramAiSheet.Spark(context).setPlain(true) : null;
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            if (spark != null) {
                spark.layout(0, 0, w, h);
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final float w = getWidth(), h = getHeight(), s = Math.min(w, h);
            if (kind == ASSISTANT) {
                spark.draw(canvas);
                return;
            }
            paint.setShader(new LinearGradient(0, h, w, 0, SolegramAiSheet.brand(), null, Shader.TileMode.CLAMP));
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(s * 0.11f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            rect.set(w * 0.08f, h * 0.16f, w * 0.78f, h * 0.72f);
            canvas.drawRoundRect(rect, s * 0.24f, s * 0.24f, paint);
            path.rewind();
            path.moveTo(w * 0.25f, h * 0.72f);
            path.lineTo(w * 0.18f, h * 0.9f);
            path.lineTo(w * 0.42f, h * 0.72f);
            canvas.drawPath(path, paint);
            // two lines of text inside
            canvas.drawLine(w * 0.24f, h * 0.38f, w * 0.6f, h * 0.38f, paint);
            canvas.drawLine(w * 0.24f, h * 0.52f, w * 0.48f, h * 0.52f, paint);
            paint.setStyle(Paint.Style.FILL);
            final float cx = w * 0.84f, cy = h * 0.16f, r = s * 0.16f;
            path.rewind();
            path.moveTo(cx, cy - r);
            path.quadTo(cx + r * 0.16f, cy - r * 0.16f, cx + r, cy);
            path.quadTo(cx + r * 0.16f, cy + r * 0.16f, cx, cy + r);
            path.quadTo(cx - r * 0.16f, cy + r * 0.16f, cx - r, cy);
            path.quadTo(cx - r * 0.16f, cy - r * 0.16f, cx, cy - r);
            canvas.drawPath(path, paint);
        }
    }

    // endregion

    // region Feature rows

    /** A feature: its icon (one size for all, in the theme's colours), its name, what it does, and its switch. */
    public static class FeatureRow extends FrameLayout {
        private final Switch toggle;
        private final View divider;

        public FeatureRow(Context context, int icon, int colorIndex, CharSequence title, CharSequence text, Theme.ResourcesProvider resourcesProvider) {
            super(context);
            final int[] c = SolegramAiSheet.brand();
            final int color = c[Math.max(0, Math.min(c.length - 1, colorIndex))];
            // the icon on a soft round of its colour, every icon scaled to the same box
            final ImageView iconView = new ImageView(context);
            iconView.setImageResource(icon);
            iconView.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iconView.setPadding(dp(8), dp(8), dp(8), dp(8));
            iconView.setColorFilter(new android.graphics.PorterDuffColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN));
            final GradientDrawable round = new GradientDrawable();
            round.setShape(GradientDrawable.OVAL);
            round.setColor(ColorUtils.setAlphaComponent(color, 0x24));
            iconView.setBackground(round);
            addView(iconView, LayoutHelper.createFrame(42, 42, Gravity.LEFT | Gravity.CENTER_VERTICAL, 16, 0, 0, 0));
            final LinearLayout texts = new LinearLayout(context);
            texts.setOrientation(LinearLayout.VERTICAL);
            final TextView t = new TextView(context);
            t.setText(title);
            t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
            t.setTypeface(AndroidUtilities.bold());
            t.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
            texts.addView(t);
            final TextView d = new TextView(context);
            d.setText(text);
            d.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            d.setLineSpacing(dp(1.5f), 1f);
            d.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
            texts.addView(d, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 3, 0, 0));
            addView(texts, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP, 74, 14, 76, 14));
            toggle = new Switch(context, resourcesProvider);
            toggle.setColors(Theme.key_switchTrack, Theme.key_switchTrackChecked, Theme.key_windowBackgroundWhite, Theme.key_windowBackgroundWhite);
            addView(toggle, LayoutHelper.createFrame(37, 40, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, 20, 0));
            divider = new View(context);
            divider.setBackgroundColor(Theme.getColor(Theme.key_divider, resourcesProvider));
            addView(divider, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 1f / AndroidUtilities.density, Gravity.BOTTOM, 74, 0, 0, 0));
            setBackground(Theme.getSelectorDrawable(false));
        }

        public FeatureRow setChecked(boolean checked, boolean animated) {
            if (toggle.isChecked() != checked) {
                toggle.setChecked(checked, animated);
            }
            return this;
        }

        public FeatureRow setDivider(boolean shown) {
            divider.setVisibility(shown ? VISIBLE : GONE);
            return this;
        }

        private Runnable action;
        private long lastToggle;

        public FeatureRow onToggle(Runnable action) {
            this.action = action;
            setOnClickListener(v -> toggleOnce());
            return this;
        }

        /**
         * The row's tap: the list may report it too (its own item click), so a tap toggles once whichever of the two
         * comes, and the other is ignored.
         */
        public void toggleOnce() {
            final long now = SystemClock.uptimeMillis();
            if (action == null || now - lastToggle < 450) {
                return;
            }
            lastToggle = now;
            toggle.setChecked(!toggle.isChecked(), true);
            try {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            } catch (Exception ignore) {
            }
            action.run();
        }
    }

    // endregion
}
