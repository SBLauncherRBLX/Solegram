/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.ui;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;

import com.solegram.messenger.SolegramRoundFx;
import com.solegram.messenger.SolegramVoiceEffects;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.Emoji;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;

import static org.telegram.messenger.AndroidUtilities.dp;

/**
 * The editor of a recorded round video: a panel under the circle with three tabs (masks, filters, voice) of round
 * buttons, as Telegram's editors look, and the layer over the playing circle that shows the mask and the filter's
 * vignette while the video plays.
 */
public final class SolegramRoundEditor {

    public static final int TAB_MASKS = 0, TAB_FILTERS = 1, TAB_VOICE = 2;

    private SolegramRoundEditor() {
    }

    public interface Callback {
        void onMask(int mask);

        void onFilter(int filter);

        void onVoice(int effect);
    }

    // region The layer over the circle

    /** Draws the mask over the playing video (and the filter's vignette), frame by frame. */
    public static class Overlay extends View {
        public interface Clock {
            /** The playing position, ms (-1 while nothing plays). */
            long positionMs();
        }

        private SolegramRoundFx.FaceTrack track;
        private int mask;
        private float vignette;
        private Clock clock;
        private final SolegramRoundMasks.Sprites sprites;
        private final SolegramRoundFx.Pose pose = new SolegramRoundFx.Pose();
        private final Paint vignettePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int vignetteSize;

        public Overlay(Context context, SolegramRoundMasks.Sprites sprites) {
            super(context);
            this.sprites = sprites;
            // shadows and blurs of the masks drawn as in the sent video
            setLayerType(LAYER_TYPE_SOFTWARE, null);
        }

        public void setClock(Clock clock) {
            this.clock = clock;
        }

        public void setMask(int mask, SolegramRoundFx.FaceTrack track) {
            this.mask = mask;
            this.track = track;
            invalidate();
        }

        public void setVignette(float vignette) {
            this.vignette = vignette;
            invalidate();
        }

        public boolean active() {
            return mask != SolegramRoundMasks.NONE && track != null || vignette > 0;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) {
                return;
            }
            if (vignette > 0) {
                if (vignetteSize != w) {
                    vignetteSize = w;
                    vignettePaint.setShader(new RadialGradient(w / 2f, h / 2f, w * 0.72f,
                        new int[]{0x00000000, 0x00000000, 0xFF000000}, new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
                }
                vignettePaint.setAlpha((int) (255 * Math.min(1f, vignette * 0.9f)));
                canvas.drawRect(0, 0, w, h, vignettePaint);
            }
            if (mask != SolegramRoundMasks.NONE && track != null && clock != null) {
                final long ms = clock.positionMs();
                if (ms >= 0) {
                    track.pose(ms * 1000, pose);
                    SolegramRoundMasks.draw(canvas, mask, pose, w, h, ms, sprites);
                }
                postInvalidateOnAnimation();
            }
        }
    }

    // endregion

    // region The panel

    public static class Panel extends FrameLayout {

        private final Theme.ResourcesProvider resourcesProvider;
        private final Callback callback;
        private final TextView[] tabs = new TextView[3];
        private final TextView status;
        private final LinearLayout row;
        private final HorizontalScrollView scroll;
        private int tab = TAB_MASKS;
        private int mask, filter, voice;
        private int busyVoice = -1;
        private boolean masksReady, masksNoFace;
        private Bitmap thumb;
        /** told when the panel looks different (the glass that refracts it takes a new picture) */
        public Runnable onChanged;
        private boolean changePosted;

        /** The panel was drawn anew or moved: the glass round it is told once on the next frame. */
        public void changed() {
            if (onChanged == null || changePosted) {
                return;
            }
            changePosted = true;
            postOnAnimation(() -> {
                changePosted = false;
                if (onChanged != null) {
                    onChanged.run();
                }
            });
        }

        @Override
        protected void dispatchDraw(Canvas canvas) {
            super.dispatchDraw(canvas);
            changed();
        }

        public Panel(Context context, Theme.ResourcesProvider resourcesProvider, Callback callback) {
            super(context);
            this.resourcesProvider = resourcesProvider;
            this.callback = callback;
            final GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(22));
            bg.setColor(ColorUtils.setAlphaComponent(color(Theme.key_dialogBackground), 0xF5));
            setBackground(bg);
            setElevation(dp(6));
            setClickable(true);

            final LinearLayout column = new LinearLayout(context);
            column.setOrientation(LinearLayout.VERTICAL);
            addView(column, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, 0, 8, 0, 6));

            final LinearLayout tabsRow = new LinearLayout(context);
            tabsRow.setOrientation(LinearLayout.HORIZONTAL);
            tabsRow.setGravity(Gravity.CENTER);
            column.addView(tabsRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 34));
            final int[] names = {R.string.SG_RoundTabMasks, R.string.SG_RoundTabFilters, R.string.SG_RoundTabVoice};
            for (int i = 0; i < 3; i++) {
                final TextView t = new TextView(context);
                t.setText(LocaleController.getString(names[i]));
                t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
                t.setTypeface(AndroidUtilities.bold());
                t.setGravity(Gravity.CENTER);
                t.setPadding(dp(14), 0, dp(14), 0);
                final int index = i;
                t.setOnClickListener(v -> selectTab(index, true));
                tabs[i] = t;
                tabsRow.addView(t, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 30, 3, 0, 3, 0));
            }

            status = new TextView(context);
            status.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            status.setGravity(Gravity.CENTER);
            status.setTextColor(color(Theme.key_windowBackgroundWhiteGrayText));
            status.setVisibility(GONE);
            column.addView(status, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));

            scroll = new HorizontalScrollView(context);
            scroll.setHorizontalScrollBarEnabled(false);
            scroll.setClipToPadding(false);
            scroll.setPadding(dp(6), 0, dp(6), 0);
            row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            scroll.addView(row, new LayoutParams(LayoutHelper.WRAP_CONTENT, LayoutHelper.MATCH_PARENT));
            column.addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 88, 0, 6, 0, 0));

            selectTab(TAB_MASKS, false);
        }

        private int color(int key) {
            return Theme.getColor(key, resourcesProvider);
        }

        public void selectTab(int index, boolean animated) {
            tab = index;
            final int accent = color(Theme.key_featuredStickers_addButton);
            for (int i = 0; i < 3; i++) {
                final boolean on = i == index;
                tabs[i].setTextColor(on ? accent : color(Theme.key_windowBackgroundWhiteGrayText));
                if (on) {
                    final GradientDrawable pill = new GradientDrawable();
                    pill.setCornerRadius(dp(15));
                    pill.setColor(ColorUtils.setAlphaComponent(accent, 0x22));
                    tabs[i].setBackground(pill);
                } else {
                    tabs[i].setBackground(Theme.createSelectorDrawable(ColorUtils.setAlphaComponent(color(Theme.key_dialogTextBlack), 0x14), Theme.RIPPLE_MASK_ROUNDRECT_6DP));
                }
            }
            rebuild();
            updateStatus();
            scroll.scrollTo(0, 0);
            if (animated) {
                row.setAlpha(0f);
                row.setTranslationX(dp(16));
                row.animate().alpha(1f).translationX(0).setDuration(220).setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).start();
            }
        }

        public int getTab() {
            return tab;
        }

        private void rebuild() {
            row.removeAllViews();
            final int count = tab == TAB_MASKS ? SolegramRoundMasks.COUNT : tab == TAB_FILTERS ? SolegramRoundFx.FILTER_COUNT : SolegramVoiceEffects.COUNT;
            for (int i = 0; i < count; i++) {
                row.addView(new Item(getContext(), tab, i), LayoutHelper.createLinear(66, 88));
            }
        }

        /** The masks wait for the face to be found in the video. */
        public void setMasksState(boolean ready, boolean noFace) {
            masksReady = ready;
            masksNoFace = noFace;
            updateStatus();
            for (int i = 0; i < row.getChildCount(); i++) {
                row.getChildAt(i).invalidate();
            }
        }

        private void updateStatus() {
            String text = null;
            if (tab == TAB_MASKS && !masksReady) {
                text = LocaleController.getString(R.string.SG_RoundFindingFace);
            } else if (tab == TAB_MASKS && masksNoFace) {
                text = LocaleController.getString(R.string.SG_RoundNoFace);
            }
            status.setText(text);
            status.setVisibility(text == null ? GONE : VISIBLE);
        }

        public void setThumbnail(Bitmap bitmap) {
            thumb = bitmap;
            if (tab == TAB_FILTERS) {
                for (int i = 0; i < row.getChildCount(); i++) {
                    row.getChildAt(i).invalidate();
                }
            }
        }

        public void setSelected(int mask, int filter, int voice) {
            this.mask = mask;
            this.filter = filter;
            this.voice = voice;
            for (int i = 0; i < row.getChildCount(); i++) {
                ((Item) row.getChildAt(i)).updateSelected(true);
            }
        }

        public void setVoiceBusy(int effect) {
            busyVoice = effect;
            for (int i = 0; i < row.getChildCount(); i++) {
                row.getChildAt(i).invalidate();
            }
        }

        private int selectedOf(int t) {
            return t == TAB_MASKS ? mask : t == TAB_FILTERS ? filter : voice;
        }

        @SuppressLint("ViewConstructor")
        private class Item extends View {
            private final int kind, value;
            private final TextPaint label = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            private final Paint circle = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            private final Drawable icon;
            private CharSequence text;
            private float selected;
            private ValueAnimator animator;
            private Bitmap shaderFor;

            Item(Context context, int kind, int value) {
                super(context);
                this.kind = kind;
                this.value = value;
                label.setTextSize(dp(11));
                label.setTextAlign(Paint.Align.CENTER);
                ring.setStyle(Paint.Style.STROKE);
                ring.setStrokeWidth(dp(2.33f));
                final String name;
                Drawable d = null;
                if (kind == TAB_MASKS) {
                    name = LocaleController.getString(SolegramRoundMasks.nameOf(value));
                    final String e = SolegramRoundMasks.iconOf(value);
                    if (e != null) {
                        d = Emoji.getEmojiBigDrawable(e);
                    }
                } else if (kind == TAB_FILTERS) {
                    name = LocaleController.getString(SolegramRoundFx.filterName(value));
                    thumbPaint.setColorFilter(new ColorMatrixColorFilter(SolegramRoundFx.previewMatrix(value)));
                } else {
                    name = LocaleController.getString(SolegramVoiceEffects.nameOf(value));
                    d = getContext().getResources().getDrawable(SolegramVoiceEffects.iconOf(value)).mutate();
                }
                if (d == null && value == 0) {
                    d = getContext().getResources().getDrawable(R.drawable.msg_cancel).mutate();
                }
                icon = d;
                text = name;
                selected = selectedOf(kind) == value ? 1f : 0f;
                setOnClickListener(v -> {
                    if (kind == TAB_MASKS) {
                        if (!masksReady) {
                            return;
                        }
                        mask = value;
                        callback.onMask(value);
                    } else if (kind == TAB_FILTERS) {
                        filter = value;
                        callback.onFilter(value);
                    } else {
                        if (busyVoice >= 0) {
                            return;
                        }
                        callback.onVoice(value);
                    }
                    for (int i = 0; i < row.getChildCount(); i++) {
                        ((Item) row.getChildAt(i)).updateSelected(true);
                    }
                });
                setBackground(Theme.createSelectorDrawable(ColorUtils.setAlphaComponent(color(Theme.key_dialogTextBlack), 0x10), Theme.RIPPLE_MASK_ROUNDRECT_6DP));
            }

            void updateSelected(boolean animated) {
                final float target = selectedOf(kind) == value ? 1f : 0f;
                if (target == selected) {
                    return;
                }
                if (animator != null) {
                    animator.cancel();
                }
                if (!animated) {
                    selected = target;
                    invalidate();
                    return;
                }
                animator = ValueAnimator.ofFloat(selected, target);
                animator.addUpdateListener(a -> {
                    selected = (float) a.getAnimatedValue();
                    invalidate();
                });
                animator.setDuration(240);
                animator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
                animator.start();
            }

            @Override
            protected void onDraw(Canvas canvas) {
                changed();
                final float cx = getWidth() / 2f, cy = dp(8) + dp(26);
                final float r = dp(24);
                final int accent = color(Theme.key_featuredStickers_addButton);
                final int textColor = color(Theme.key_dialogTextBlack);
                final boolean disabled = kind == TAB_MASKS && value != 0 && (!masksReady || masksNoFace)
                    || kind == TAB_VOICE && busyVoice >= 0 && busyVoice != value;
                final float alpha = disabled ? 0.4f : 1f;
                // the ring of the chosen one
                if (selected > 0) {
                    ring.setColor(accent);
                    ring.setAlpha((int) (255 * selected));
                    canvas.drawCircle(cx, cy, r + dp(4.5f) - dp(1.5f) * (1f - selected), ring);
                }
                final float scale = 1f - 0.08f * selected;
                canvas.save();
                canvas.scale(scale, scale, cx, cy);
                if (kind == TAB_FILTERS && thumb != null) {
                    if (shaderFor != thumb) {
                        shaderFor = thumb;
                        final BitmapShader shader = new BitmapShader(thumb, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                        final Matrix m = new Matrix();
                        final float s = r * 2 / Math.min(thumb.getWidth(), thumb.getHeight());
                        m.setScale(s, s);
                        m.postTranslate(cx - thumb.getWidth() * s / 2f, cy - thumb.getHeight() * s / 2f);
                        shader.setLocalMatrix(m);
                        thumbPaint.setShader(shader);
                    }
                    canvas.drawCircle(cx, cy, r, thumbPaint);
                } else {
                    circle.setColor(ColorUtils.setAlphaComponent(textColor, kind == TAB_VOICE && busyVoice == value ? 0x30 : 0x12));
                    canvas.drawCircle(cx, cy, r, circle);
                    if (icon != null) {
                        final boolean emoji = icon instanceof Emoji.EmojiDrawable;
                        final int size = emoji ? dp(30) : dp(26);
                        icon.setBounds((int) (cx - size / 2f), (int) (cy - size / 2f), (int) (cx + size / 2f), (int) (cy + size / 2f));
                        if (!emoji) {
                            icon.setColorFilter(new PorterDuffColorFilter(selected > 0.5f ? accent : textColor, PorterDuff.Mode.SRC_IN));
                        }
                        icon.setAlpha((int) (255 * alpha));
                        icon.draw(canvas);
                        if (emoji && !((Emoji.EmojiDrawable) icon).isLoaded()) {
                            postInvalidateDelayed(120);
                        }
                    }
                }
                canvas.restore();
                if (kind == TAB_VOICE && busyVoice == value) {
                    // working on it: a turning arc round the button
                    ring.setColor(accent);
                    ring.setAlpha(255);
                    final float a = (System.currentTimeMillis() % 1000) / 1000f * 360f;
                    canvas.drawArc(new RectF(cx - r - dp(4.5f), cy - r - dp(4.5f), cx + r + dp(4.5f), cy + r + dp(4.5f)), a, 100, false, ring);
                    invalidate();
                }
                label.setColor(selected > 0.5f ? accent : textColor);
                label.setAlpha((int) (255 * alpha));
                label.setTypeface(selected > 0.5f ? AndroidUtilities.bold() : null);
                final CharSequence t = TextUtils.ellipsize(text, label, getWidth() - dp(6), TextUtils.TruncateAt.END);
                canvas.drawText(t, 0, t.length(), cx, getHeight() - dp(10), label);
            }
        }
    }

    // endregion
}
