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
import org.telegram.messenger.R;
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

    /**
     * The page's parts rise in once, one after another, when the page opens. The page does not use the list's own
     * appear animation: it fades each row in a layer of the row's size, and that cut the header's glow off at the
     * top of the header for as long as it ran (a plain band under the header for a second).
     */
    public static void riseOnce(View view, long delay) {
        if (view.getTag(R.id.sg_ai_rise) != null) {
            return;
        }
        view.setTag(R.id.sg_ai_rise, Boolean.TRUE);
        view.setAlpha(0f);
        view.setTranslationY(dp(18));
        final long made = SystemClock.uptimeMillis();
        view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
                v.removeOnAttachStateChangeListener(this);
                if (SystemClock.uptimeMillis() - made > 900) {
                    // a row below the screen, first scrolled to later: just there, no late entrance
                    v.setAlpha(1f);
                    v.setTranslationY(0);
                    return;
                }
                v.animate().alpha(1f).translationY(0).setStartDelay(delay).setDuration(460).setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).start();
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
            }
        });
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
     * "Solegram AI" and its tagline, over the page's glow ({@link Glow}, drawn behind the list).
     */
    public static class Hero extends FrameLayout {
        private final Theme.ResourcesProvider resourcesProvider;
        private final LinearLayout texts;
        private boolean entered;

        public Hero(Context context, CharSequence subtitle, Theme.ResourcesProvider resourcesProvider) {
            super(context);
            this.resourcesProvider = resourcesProvider;
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
            sub.setTextColor(isDark(resourcesProvider) ? ColorUtils.blendARGB(accent, 0xFFFFFFFF, 0.35f) : ColorUtils.blendARGB(accent, 0xFF000000, 0.12f));
            texts.addView(sub, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 10, 0, 0));
            addView(texts, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM | Gravity.LEFT, 24, 0, 24, 34));
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
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(dp(HEIGHT_DP), MeasureSpec.EXACTLY));
        }

        static final int HEIGHT_DP = 206;
    }

    private static boolean isDark(Theme.ResourcesProvider resourcesProvider) {
        return ColorUtils.calculateLuminance(Theme.getColor(Theme.key_windowBackgroundGray, resourcesProvider)) < 0.4;
    }

    /**
     * The page's soft, slowly drifting glow in the theme's colours, behind the list: it has no top (it goes on up under
     * the header and the status bar) and melts into the page's colour towards the cards. It follows the hero's place
     * in the list as it scrolls.
     * <p>
     * It is not drawn by the hero row itself: the list fades and cross-fades its rows (when they appear, when the page
     * updates - the Gemini Nano status comes right after it opens), each in a layer of the row's own size, and every
     * such animation cut the glow off at the hero's top: a plain band under the header for a moment.
     */
    public static class Glow extends View {
        private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fade = new Paint();
        private final Theme.ResourcesProvider resourcesProvider;
        private final long born = SystemClock.uptimeMillis();
        private final androidx.recyclerview.widget.RecyclerView list;
        private final View hero;
        private final float[] pos = new float[2];

        public Glow(Context context, androidx.recyclerview.widget.RecyclerView list, View hero, Theme.ResourcesProvider resourcesProvider) {
            super(context);
            this.list = list;
            this.hero = hero;
            this.resourcesProvider = resourcesProvider;
            list.addOnScrollListener(new androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
                @Override
                public void onScrolled(@androidx.annotation.NonNull androidx.recyclerview.widget.RecyclerView recyclerView, int dx, int dy) {
                    invalidate();
                }
            });
        }

        /** The hero's top in this view; NaN when it is scrolled away. */
        private float heroTop() {
            if (hero.getParent() == null || !hero.isAttachedToWindow()) {
                // not laid out yet: where the list starts; laid out and gone: scrolled away above
                return list.getChildCount() == 0 ? list.getTop() + list.getPaddingTop() : Float.NaN;
            }
            float y = hero.getTop() + hero.getTranslationY();
            android.view.ViewParent p = hero.getParent();
            while (p instanceof View && p != getParent()) {
                final View v = (View) p;
                y += v.getTop() + v.getTranslationY() - v.getScrollY();
                p = v.getParent();
            }
            return y - getTop();
        }

        private void blob(Canvas canvas, float x, float y, float r, int color, float alpha) {
            glow.setShader(new RadialGradient(x, y, r, new int[]{ColorUtils.setAlphaComponent(color, (int) (255 * alpha)), ColorUtils.setAlphaComponent(color, 0)}, null, Shader.TileMode.CLAMP));
            canvas.drawCircle(x, y, r, glow);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final float top = heroTop();
            final float w = getWidth(), h = dp(Hero.HEIGHT_DP);
            if (!Float.isNaN(top) && top + h > 0) {
                final int[] c = SolegramAiSheet.brand();
                final float k = isDark(resourcesProvider) ? 1f : 0.6f;
                final float t = (SystemClock.uptimeMillis() - born) / 1000f;
                final float d = dp(18);
                canvas.save();
                canvas.translate(0, top);
                final int layer = canvas.saveLayer(0, -top, w, h, null);
                blob(canvas, w * 0.16f + d * (float) Math.sin(t * 0.35), h * 0.5f + d * (float) Math.cos(t * 0.29), w * 0.62f, c[0], 0.42f * k);
                blob(canvas, w * 0.5f + d * (float) Math.cos(t * 0.27), h * 0.3f + d * (float) Math.sin(t * 0.33), w * 0.6f, c[1], 0.34f * k);
                blob(canvas, w * 0.86f + d * (float) Math.sin(t * 0.31 + 1), h * 0.5f + d * (float) Math.cos(t * 0.23), w * 0.52f, c[3], 0.34f * k);
                blob(canvas, w * 0.74f + d * (float) Math.cos(t * 0.21), -h * 0.15f, w * 0.55f, c[2], 0.24f * k);
                blob(canvas, w * 0.3f + d * (float) Math.sin(t * 0.19), -h * 0.45f, w * 0.6f, c[1], 0.18f * k);
                // towards the cards it melts into the page's colour
                final int page = Theme.getColor(Theme.key_windowBackgroundGray, resourcesProvider);
                fade.setShader(new LinearGradient(0, h * 0.42f, 0, h, ColorUtils.setAlphaComponent(page, 0), page, Shader.TileMode.CLAMP));
                canvas.drawRect(0, h * 0.42f, w, h, fade);
                canvas.restoreToCount(layer);
                canvas.restore();
            }
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
        private final Paint lines = new Paint(Paint.ANTI_ALIAS_FLAG);
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
            // filled, as the Solegram AI mark: a speech bubble (its body and its tail one shape, no seam where they
            // meet), two lines of text on it, and a spark well clear of it
            paint.setShader(new LinearGradient(0, h, w, 0, SolegramAiSheet.brand(), null, Shader.TileMode.CLAMP));
            paint.setStyle(Paint.Style.FILL);
            path.rewind();
            path.setFillType(Path.FillType.WINDING);
            rect.set(w * 0.04f, h * 0.24f, w * 0.72f, h * 0.76f);
            path.addRoundRect(rect, s * 0.2f, s * 0.2f, Path.Direction.CW);
            canvas.drawPath(path, paint);
            // the tail is a shape of its own: in one path with the body its other winding cut the overlap out (a dark
            // wedge where the tail meets the bubble)
            path.rewind();
            path.moveTo(w * 0.16f, h * 0.7f);
            path.lineTo(w * 0.1f, h * 0.94f);
            path.lineTo(w * 0.38f, h * 0.74f);
            path.close();
            canvas.drawPath(path, paint);
            // the lines are drawn on the bubble, not cut out of it: cut out, the card's dark colour showed through
            final float lh = s * 0.045f;
            lines.setColor(0xF2FFFFFF);
            rect.set(w * 0.17f, h * 0.42f - lh, w * 0.58f, h * 0.42f + lh);
            canvas.drawRoundRect(rect, lh, lh, lines);
            rect.set(w * 0.17f, h * 0.58f - lh, w * 0.44f, h * 0.58f + lh);
            canvas.drawRoundRect(rect, lh, lh, lines);
            path.rewind();
            final float cx = w * 0.86f, cy = h * 0.14f, r = s * 0.14f;
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
            // the icon alone (no backdrop), in the theme's accent, every icon fitted to the same 30 dp box
            final int color = SolegramAiSheet.accent();
            final ImageView iconView = new ImageView(context);
            iconView.setImageResource(icon);
            iconView.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iconView.setColorFilter(new android.graphics.PorterDuffColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN));
            // the same optical size for all: the reply arrow and the summary's thin lines fill less of their box
            // than the others' glyphs do, so they are drawn larger
            final float k = opticalScale(icon);
            iconView.setScaleX(k);
            iconView.setScaleY(k);
            addView(iconView, LayoutHelper.createFrame(30, 30, Gravity.LEFT | Gravity.CENTER_VERTICAL, 22, 0, 0, 0));
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
            addView(texts, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP, 72, 14, 76, 14));
            toggle = new Switch(context, resourcesProvider);
            toggle.setColors(Theme.key_switchTrack, Theme.key_switchTrackChecked, Theme.key_windowBackgroundWhite, Theme.key_windowBackgroundWhite);
            addView(toggle, LayoutHelper.createFrame(37, 40, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, 20, 0));
            divider = new View(context);
            divider.setBackgroundColor(Theme.getColor(Theme.key_divider, resourcesProvider));
            addView(divider, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 1f / AndroidUtilities.density, Gravity.BOTTOM, 72, 0, 0, 0));
            setBackground(Theme.getSelectorDrawable(false));
        }

        private static float opticalScale(int icon) {
            if (icon == R.drawable.menu_reply) {
                return 1.26f;
            }
            if (icon == R.drawable.sg_ai_summary) {
                return 1.2f;
            }
            return 1f;
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
