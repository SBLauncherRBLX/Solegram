/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.Switch;

/** The parts of the Solegram AI settings page: its glowing header, the two big cards and the feature rows. */
public final class SolegramAiPageViews {

    private SolegramAiPageViews() {
    }

    // region Header

    /** "Solegram AI" over a soft glow of its colours, with what it is under it. */
    public static class Hero extends FrameLayout {
        private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final boolean dark;

        public Hero(Context context, CharSequence subtitle, Theme.ResourcesProvider resourcesProvider) {
            super(context);
            setWillNotDraw(false);
            dark = ColorUtils.calculateLuminance(Theme.getColor(Theme.key_windowBackgroundGray, resourcesProvider)) < 0.4;
            final LinearLayout texts = new LinearLayout(context);
            texts.setOrientation(LinearLayout.VERTICAL);
            final SolegramAiSheet.GradientText title = new SolegramAiSheet.GradientText(context);
            title.setText("Solegram AI");
            title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 40);
            title.setTypeface(AndroidUtilities.bold());
            title.setIncludeFontPadding(false);
            texts.addView(title);
            final TextView sub = new TextView(context);
            sub.setText(subtitle);
            sub.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
            sub.setTextColor(dark ? 0xFF8FD6FF : ColorUtils.blendARGB(SolegramAiSheet.BRAND[0], 0xFF000000, 0.15f));
            texts.addView(sub, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 10, 0, 0));
            addView(texts, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM | Gravity.LEFT, 24, 0, 24, 30));
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(dp(196), MeasureSpec.EXACTLY));
        }

        private void blob(Canvas canvas, float x, float y, float r, int color, float alpha) {
            glow.setShader(new RadialGradient(x, y, r, new int[]{ColorUtils.setAlphaComponent(color, (int) (255 * alpha)), ColorUtils.setAlphaComponent(color, 0)}, null, Shader.TileMode.CLAMP));
            canvas.drawCircle(x, y, r, glow);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final float w = getWidth(), h = getHeight();
            final float k = dark ? 1f : 0.55f;
            blob(canvas, w * 0.18f, h * 0.72f, w * 0.5f, 0xFF1E8CFF, 0.34f * k);
            blob(canvas, w * 0.52f, h * 0.55f, w * 0.48f, 0xFF8A4DFF, 0.26f * k);
            blob(canvas, w * 0.86f, h * 0.7f, w * 0.42f, 0xFFFF9A3C, 0.26f * k);
            blob(canvas, w * 0.78f, h * 0.18f, w * 0.3f, 0xFFFF5FA2, 0.14f * k);
        }
    }

    // endregion

    // region Cards

    /** Two big cards side by side. */
    public static class Cards extends LinearLayout {
        public Cards(Context context) {
            super(context);
            setOrientation(HORIZONTAL);
            setPadding(dp(14), dp(4), dp(14), dp(10));
        }

        public Card add(Card card) {
            addView(card, LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1f, getChildCount() == 0 ? 0 : 12, 0, 0, 0)); // one height for both
            return card;
        }
    }

    /** A big card: a colourful mark, a title, what it does, and a state line ("On") when it is a switch. */
    public static class Card extends LinearLayout {
        private final TextView state;
        private final Theme.ResourcesProvider resourcesProvider;

        public Card(Context context, View mark, CharSequence title, CharSequence text, Theme.ResourcesProvider resourcesProvider) {
            super(context);
            this.resourcesProvider = resourcesProvider;
            setOrientation(VERTICAL);
            setPadding(dp(18), dp(18), dp(16), dp(18));
            final int bg = Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider);
            setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(26), bg, ColorUtils.blendARGB(bg, Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider), 0.08f)));
            addView(mark, LayoutHelper.createLinear(34, 34));
            final TextView t = new TextView(context);
            t.setText(title);
            t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 19);
            t.setTypeface(AndroidUtilities.bold());
            t.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
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
            state.setVisibility(GONE);
            addView(state, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 10, 0, 0));
        }

        /** The card is a switch: its state under the text. */
        public Card setState(CharSequence text, boolean on) {
            state.setVisibility(VISIBLE);
            state.setText(text);
            state.setTextColor(on ? Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4, resourcesProvider) : Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
            return this;
        }
    }

    /** A colourful mark for a card: rings of the Solegram AI colours round a sparkle, or a chat bubble with lines. */
    public static class CardMark extends View {
        public static final int ASSISTANT = 0, CATCH_UP = 1;
        private final int kind;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF rect = new RectF();

        public CardMark(Context context, int kind) {
            super(context);
            this.kind = kind;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final float w = getWidth(), h = getHeight(), s = Math.min(w, h);
            final Shader shader = new LinearGradient(0, h, w, 0, SolegramAiSheet.BRAND, null, Shader.TileMode.CLAMP);
            paint.setShader(shader);
            if (kind == ASSISTANT) {
                // two crossed rings, as orbits
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(s * 0.13f);
                canvas.save();
                canvas.rotate(45, w / 2, h / 2);
                rect.set(w * 0.08f, h * 0.27f, w * 0.92f, h * 0.73f);
                canvas.drawOval(rect, paint);
                canvas.rotate(-90, w / 2, h / 2);
                canvas.drawOval(rect, paint);
                canvas.restore();
            } else {
                // a speech bubble with a sparkle
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(s * 0.12f);
                paint.setStrokeCap(Paint.Cap.ROUND);
                rect.set(w * 0.08f, h * 0.14f, w * 0.8f, h * 0.74f);
                canvas.drawRoundRect(rect, s * 0.26f, s * 0.26f, paint);
                path.rewind();
                path.moveTo(w * 0.24f, h * 0.74f);
                path.lineTo(w * 0.18f, h * 0.92f);
                path.lineTo(w * 0.42f, h * 0.74f);
                canvas.drawPath(path, paint);
                paint.setStyle(Paint.Style.FILL);
                final float cx = w * 0.82f, cy = h * 0.2f, r = s * 0.17f;
                path.rewind();
                path.moveTo(cx, cy - r);
                path.quadTo(cx + r * 0.16f, cy - r * 0.16f, cx + r, cy);
                path.quadTo(cx + r * 0.16f, cy + r * 0.16f, cx, cy + r);
                path.quadTo(cx - r * 0.16f, cy + r * 0.16f, cx - r, cy);
                path.quadTo(cx - r * 0.16f, cy - r * 0.16f, cx, cy - r);
                canvas.drawPath(path, paint);
            }
        }
    }

    // endregion

    // region Feature rows

    /** A feature: its icon in its own colour, its name, what it does, and its switch. */
    public static class FeatureRow extends FrameLayout {
        private final Switch toggle;
        private final View divider;

        public FeatureRow(Context context, int icon, int iconColor, CharSequence title, CharSequence text, Theme.ResourcesProvider resourcesProvider) {
            super(context);
            final ImageView iconView = new ImageView(context);
            iconView.setImageResource(icon);
            iconView.setScaleType(ImageView.ScaleType.CENTER);
            iconView.setColorFilter(new android.graphics.PorterDuffColorFilter(iconColor, android.graphics.PorterDuff.Mode.SRC_IN));
            addView(iconView, LayoutHelper.createFrame(32, 32, Gravity.LEFT | Gravity.TOP, 20, 16, 0, 0));
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
            addView(texts, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP, 68, 14, 76, 14));
            toggle = new Switch(context, resourcesProvider);
            toggle.setColors(Theme.key_switchTrack, Theme.key_switchTrackChecked, Theme.key_windowBackgroundWhite, Theme.key_windowBackgroundWhite);
            addView(toggle, LayoutHelper.createFrame(37, 40, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, 20, 0));
            divider = new View(context);
            divider.setBackgroundColor(Theme.getColor(Theme.key_divider, resourcesProvider));
            addView(divider, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 1f / AndroidUtilities.density, Gravity.BOTTOM, 68, 0, 0, 0));
            setBackground(Theme.getSelectorDrawable(false));
        }

        public FeatureRow setChecked(boolean checked, boolean animated) {
            toggle.setChecked(checked, animated);
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
            final long now = android.os.SystemClock.uptimeMillis();
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
