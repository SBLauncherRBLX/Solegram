package org.telegram.ui.Components.glass;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.dpf2;
import static org.telegram.messenger.AndroidUtilities.lerp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.RawRes;
import androidx.annotation.StringRes;
import androidx.core.graphics.ColorUtils;
import androidx.core.math.MathUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ContactsController;
import org.telegram.messenger.DocumentObject;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedTextView;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.Premium.PremiumGradient;
import org.telegram.ui.Components.RLottieDrawable;
import org.telegram.ui.Components.RLottieImageView;
import org.telegram.ui.Components.ScaleStateListAnimator;
import org.telegram.ui.MainTabsLayout;

import me.vkryl.android.AnimatorUtils;
import me.vkryl.android.animator.BoolAnimator;
import me.vkryl.android.animator.FactorAnimator;

public class GlassTabView extends FrameLayout implements MainTabsLayout.Tab, FactorAnimator.Target {
    private final TextView textView;
    private final RLottieImageView imageView;
    private BackupImageView backupImageView;
    private Theme.ResourcesProvider resourcesProvider;
    private final Paint paintCounterBackground = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final AnimatedTextView.AnimatedTextDrawable counter;

    private static final int ANIMATOR_ID_IS_SELECTED = 0;
    private static final int ANIMATOR_ID_COUNTER_VISIBLE = 1;
    private static final int ANIMATOR_ID_COUNTER_ERROR = 2;

    private final BoolAnimator isSelectedAnimator = new BoolAnimator(ANIMATOR_ID_IS_SELECTED, this, AnimatorUtils.DECELERATE_INTERPOLATOR, 320);
    private final BoolAnimator isHasCounterAnimator = new BoolAnimator(ANIMATOR_ID_COUNTER_VISIBLE, this, CubicBezierInterpolator.EASE_OUT_QUINT, 380);
    private final BoolAnimator isHasCounterErrorAnimator = new BoolAnimator(ANIMATOR_ID_COUNTER_ERROR, this, CubicBezierInterpolator.EASE_OUT_QUINT, 380);
    private int colorSelected;
    private int colorSelectedText;
    private int colorDefault;
    private boolean usePremiumCounter;

    private TabAnimation tabAnimation;
    private TLRPC.TL_attachMenuBot tabAnimationBot;

    private final TextPaint defaultTextPaint;

    public GlassTabView(@NonNull Context context) {
        super(context);
        imageView = new RLottieImageView(context) {
            @Override
            public void draw(android.graphics.Canvas canvas) {
                if (solegramBands < 0f || solegramBands >= 1f || getWidth() <= 0) {
                    super.draw(canvas);
                    return;
                }
                // Solegram: the newspaper stays put while the lines in it rise into place, the bottom one first
                final android.graphics.drawable.Drawable d = getDrawable();
                if (d == null) {
                    super.draw(canvas);
                    return;
                }
                final android.graphics.RectF box = new android.graphics.RectF(d.getBounds());
                getImageMatrix().mapRect(box);
                box.offset(getPaddingLeft(), getPaddingTop());
                final boolean pack = com.solegram.messenger.SolegramIcons.tabIcon(TabAnimation.FEED.name(), true) != 0;
                final float[] content = pack ? FEED_PACK_CONTENT : FEED_LOTTIE_CONTENT;
                final float[][] bands = pack ? FEED_PACK_BANDS : FEED_LOTTIE_BANDS;
                final android.graphics.RectF area = solegramFeedRect(box, content, solegramFeedArea);
                // an own layer: the cut-outs must reach the icon only, not the tab bar under it
                final int root = canvas.saveLayer(0, 0, getWidth(), getHeight(), null);
                super.draw(canvas);
                // the paper under the lines, in the icon's colour (the lines are cut out of a filled page)
                solegramFeedPaint.setXfermode(null);
                solegramFeedPaint.setAlpha(255);
                solegramFeedPaint.setColor(ColorUtils.blendARGB(colorDefault, colorSelected, isSelectedAnimator.getFloatValue()));
                canvas.drawRect(area, solegramFeedPaint);
                for (int i = 0; i < bands.length; i++) {
                    final int b = bands.length - 1 - i;
                    final float t = Math.max(0f, Math.min(1f, (solegramBands - i * 0.16f) / 0.62f));
                    if (t <= 0f) {
                        continue;
                    }
                    final float dy = dp(6) * (1f - CubicBezierInterpolator.EASE_OUT_BACK.getInterpolation(t));
                    final android.graphics.RectF band = solegramFeedRect(box, bands[b], solegramFeedBand);
                    // the band's cut-outs, moved, punched out of the paper
                    solegramFeedOut.setAlpha((int) (255 * Math.min(1f, t * 1.8f)));
                    final int outer = canvas.saveLayer(area, solegramFeedOut);
                    canvas.clipRect(area);
                    solegramFeedPaint.setColor(Color.BLACK);
                    // a pixel inside the band, so no hairline is left where the band's edges meet the page
                    canvas.drawRect(band.left + 1, band.top + dy + 1, band.right - 1, band.bottom + dy - 1, solegramFeedPaint);
                    solegramFeedCut.setAlpha(255);
                    canvas.saveLayer(area, solegramFeedCut);
                    canvas.translate(0, dy);
                    canvas.clipRect(band);
                    super.draw(canvas);
                    canvas.restoreToCount(outer);
                }
                canvas.restoreToCount(root);
            }
        };
        addView(imageView, LayoutHelper.createFrame(44, 44, Gravity.CENTER_HORIZONTAL | Gravity.TOP, 0, -6, 0, 0));

        imageView.setColorFilter(new PorterDuffColorFilter(Color.BLACK, PorterDuff.Mode.SRC_IN));

        textView = new TextView(context);
        textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12f);
        textView.setSingleLine();
        textView.setLines(1);
        textView.setEllipsize(TextUtils.TruncateAt.END);
        textView.setTypeface(AndroidUtilities.bold());
        textView.setGravity(Gravity.CENTER);

        defaultTextPaint = new TextPaint(textView.getPaint());
        addView(textView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL | Gravity.TOP, 0, 28.33f, 0, 0));

        counter = new AnimatedTextView.AnimatedTextDrawable();
        counter.setTypeface(AndroidUtilities.bold());
        counter.setCallback(this);
        counter.setGravity(Gravity.CENTER);
        counter.setTextColor(Color.WHITE);
        counter.setTextSize(dp(10));
    }

    private boolean hasVisualWidth;
    private float visualWidth;
    public void setVisualWidth(float width) {
        hasVisualWidth = true;
        if (visualWidth != width) {
            visualWidth = width;
            checkVisualWidth();
            invalidate();
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        checkVisualWidth();
    }

    private void checkVisualWidth() {
        if (hasVisualWidth) {
            final float offset = (visualWidth - getMeasuredWidth()) / 2f;
            imageView.setTranslationX(offset);
            textView.setTranslationX(offset);
        }
    }

    private static final RectF tmpRectF = new RectF();

    private boolean hasGestureSelectedOverride;
    private float gestureSelectedOverride;
    private boolean skipDrawSelector;
    private int solegramIconDp = 24;

    public void setGestureSelectedOverride(float gestureSelectedOverride, boolean allow) {
        this.gestureSelectedOverride = gestureSelectedOverride;
        this.hasGestureSelectedOverride = allow;
        invalidate();
    }

    /** Solegram: a roomier tab outside the tab bar (a row that scrolls): a bigger icon and text, placed for it. */
    public void solegramSetRoomy(int iconDp, float textSizeDp, float textTopDp) {
        solegramIconDp = iconDp;
        if (tabAnimation != null) {
            setTabAnimation(tabAnimation); // the Lottie icon is drawn again at its new size
        }
        imageView.setLayoutParams(LayoutHelper.createFrame(iconDp, iconDp, Gravity.CENTER_HORIZONTAL | Gravity.TOP, 0, 8, 0, 0));
        textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, textSizeDp);
        textView.setLayoutParams(LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL | Gravity.TOP, 4, textTopDp, 4, 0));
    }

    public void setSkipDrawSelector(boolean skipDrawSelector) {
        if (this.skipDrawSelector != skipDrawSelector) {
            this.skipDrawSelector = skipDrawSelector;
            invalidate();
        }
    }

    @Override
    protected void dispatchDraw(@NonNull Canvas canvas) {
        final float viewWidth = hasVisualWidth ? visualWidth : getWidth();
        final float selectedFactor = hasGestureSelectedOverride ? gestureSelectedOverride : isSelectedAnimator.getFloatValue();
        if (selectedFactor > 0 && !skipDrawSelector) {
            final float alpha = AnimatorUtils.DECELERATE_INTERPOLATOR.getInterpolation(selectedFactor);

            paintCounterBackground.setColor(Theme.multAlpha(colorSelected, 0.09f * alpha));
            tmpRectF.set(0, 0, viewWidth, getHeight());
            final float r = Math.min(tmpRectF.width(), tmpRectF.height()) / 2f;
            final float s = lerp(0.6f, 1, selectedFactor) * MathUtils.clamp(attachScale, 0, 1);
            canvas.save();
            canvas.scale(s, s, tmpRectF.centerX(), tmpRectF.centerY());
            canvas.drawRoundRect(tmpRectF, r, r, paintCounterBackground);
            canvas.restore();
        }

        final float hasCounter = (usePremiumCounter ? 1f : isHasCounterAnimator.getFloatValue()) * attachScale;
        final boolean saveLayer = hasCounter > 0;
        if (saveLayer) {
            canvas.saveLayer(0, 0, viewWidth, getHeight(), null);
        }

        super.dispatchDraw(canvas);

        if (hasCounter > 0) {
            canvas.save();

            final float gap = dpf2(1.33f);
            final float cx = viewWidth / 2f + dpf2(11);
            final float cy = dpf2(10);
            final float height = dpf2(16);
            final float width = Math.max(height, counter.getCurrentWidth() + dp(8));
            final float rOuter = dpf2(9.333f);
            final float rInner = dpf2(8f);
            tmpRectF.set(
                    cx - width / 2f - gap,
                    cy - height / 2f - gap,
                    cx + width / 2f + gap,
                    cy + height / 2f + gap
            );

            canvas.scale(hasCounter, hasCounter, cx, cy);
            canvas.drawRoundRect(tmpRectF, rOuter, rOuter, Theme.PAINT_CLEAR);
            tmpRectF.inset(gap, gap);

            if (usePremiumCounter) {
                if (premiumStarDrawable == null) {
                    premiumStarDrawable = getContext().getResources().getDrawable(R.drawable.star).mutate();
                }

                PremiumGradient.getInstance().updateMainGradientMatrix(0, 0, dp(96), dp(16), 0, 0);
                canvas.drawRoundRect(tmpRectF, rInner, rInner, PremiumGradient.getInstance().getMainGradientPaint());
                int x = (int)(cx - dpf2(7f));
                int y = (int)(cy - dpf2(7f));
                premiumStarDrawable.setBounds(x, y, x + dp(14), y + dp(14));
                premiumStarDrawable.draw(canvas);
            } else {
                paintCounterBackground.setColor(ColorUtils.blendARGB(Theme.getColor(Theme.key_telegram_color), Theme.getColor(Theme.key_fill_RedNormal), isHasCounterErrorAnimator.getFloatValue()));
                canvas.drawRoundRect(tmpRectF, rInner, rInner, paintCounterBackground);
                counter.setBounds(tmpRectF);
                counter.draw(canvas);
            }
            canvas.restore();
        }

        if (saveLayer) {
            canvas.restore();
        }
    }

    private Drawable premiumStarDrawable;

    public void setCounter(String text, boolean isError, boolean animated) {
        counter.setText(text, animated);
        isHasCounterAnimator.setValue(!TextUtils.isEmpty(text), animated);
        isHasCounterErrorAnimator.setValue(isError, animated);
    }

    public void setPremiumBadge(boolean usePremiumBadge) {
        usePremiumCounter = usePremiumBadge;
    }

    public void setSelected(boolean selected, boolean animated) {
        if (selected && animated && !isSelectedAnimator.getValue() && getParent() instanceof org.telegram.ui.MainTabsLayout) {
            solegramPlaySelectMotion(); // Solegram: every main tab moves in its own way when it opens
        }
        isSelectedAnimator.setValue(selected, animated);
        checkPlayAnimation(animated);

        textView.setTypeface(selected ? AndroidUtilities.getTypeface(AndroidUtilities.TYPEFACE_ROBOTO_EXTRA_BOLD) : AndroidUtilities.bold());
    }

    // region Solegram: tab motions

    private float solegramBands = -1f;
    private final Paint solegramFeedPaint = new Paint();
    private final Paint solegramFeedOut = new Paint();
    private final Paint solegramFeedCut = new Paint();
    private final android.graphics.RectF solegramFeedArea = new android.graphics.RectF(), solegramFeedBand = new android.graphics.RectF();
    {
        solegramFeedOut.setXfermode(new android.graphics.PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
        solegramFeedCut.setXfermode(new android.graphics.PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
    }

    // where the feed icon's lines are, as parts of the icon (left, top, right, bottom): the page under them, then each line
    private static final float[] FEED_LOTTIE_CONTENT = {0.29f, 0.30f, 0.71f, 0.69f};
    private static final float[][] FEED_LOTTIE_BANDS = {{0.29f, 0.30f, 0.71f, 0.503f}, {0.29f, 0.503f, 0.71f, 0.592f}, {0.29f, 0.592f, 0.71f, 0.69f}};
    private static final float[] FEED_PACK_CONTENT = {0.3125f, 0.375f, 0.75f, 0.625f};
    private static final float[][] FEED_PACK_BANDS = {{0.3125f, 0.375f, 0.75f, 0.5f}, {0.3125f, 0.5f, 0.75f, 0.625f}};

    private static android.graphics.RectF solegramFeedRect(android.graphics.RectF box, float[] f, android.graphics.RectF out) {
        out.set(box.left + box.width() * f[0], box.top + box.height() * f[1], box.left + box.width() * f[2], box.top + box.height() * f[3]);
        return out;
    }
    private android.animation.Animator solegramMotion;

    private void solegramPlaySelectMotion() {
        if (solegramMotion != null) {
            solegramMotion.cancel();
        }
        final android.view.View icon = backupImageView != null && imageView.getVisibility() != VISIBLE ? backupImageView : imageView;
        if (!com.solegram.messenger.ui.SolegramMotion.rich() || !icon.isAttachedToWindow()) {
            return;
        }
        icon.animate().cancel();
        final TabAnimation type = backupImageView != null && icon == backupImageView ? null : tabAnimation;
        final float rest = icon == imageView ? attachScale : 1f;
        final android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(0f, 1f);
        final long duration;
        if (type == TabAnimation.SETTINGS) {
            duration = 720;
        } else if (type == TabAnimation.FEED) {
            duration = 620;
        } else if (type == TabAnimation.CALLS) {
            duration = 640;
        } else {
            duration = 560;
        }
        a.setDuration(duration);
        a.setInterpolator(new android.view.animation.LinearInterpolator());
        a.addUpdateListener(v -> solegramApplyMotion(icon, type, rest, (float) v.getAnimatedValue()));
        a.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                solegramApplyMotion(icon, type, rest, 1f);
                if (solegramMotion == animation) {
                    solegramMotion = null;
                }
            }
        });
        solegramMotion = a;
        a.start();
    }

    /** One frame of a tab's motion; t runs 0..1 and every motion ends exactly at rest. */
    private void solegramApplyMotion(android.view.View icon, TabAnimation type, float rest, float t) {
        float scaleX = 1f, scaleY = 1f, rotation = 0f, dy = 0f;
        icon.setPivotX(icon.getWidth() / 2f);
        icon.setPivotY(icon.getHeight() / 2f);
        if (type == TabAnimation.SETTINGS) {
            // the gear turns half a round (the same look as at rest), quick at first and settling with a little spring
            rotation = 180f * CubicBezierInterpolator.EASE_OUT_BACK.getInterpolation(Math.min(1f, t * 1.05f));
            final float press = (float) Math.sin(Math.PI * Math.min(1f, t * 2.2f));
            scaleX = scaleY = 1f - 0.08f * press;
            if (t >= 1f) {
                rotation = 0f;
            }
        } else if (type == TabAnimation.FEED) {
            solegramBands = t >= 1f ? -1f : t;
            imageView.invalidate();
        } else if (type == TabAnimation.CALLS) {
            // the handset rings: a few quick shakes that die away
            rotation = (float) (Math.sin(t * Math.PI * 7) * 16f * (1f - t));
        } else if (type == TabAnimation.CHATS || type == TabAnimation.CONTACTS) {
            // the bubble pops out of its tail with a little wiggle
            icon.setPivotX(icon.getWidth() * 0.25f);
            icon.setPivotY(icon.getHeight() * 0.85f);
            final float pop = t < 0.18f ? 1f - 0.2f * (t / 0.18f)
                : 0.8f + 0.2f * CubicBezierInterpolator.EASE_OUT_BACK.getInterpolation((t - 0.18f) / 0.82f) + 0.06f * (float) Math.sin(Math.PI * Math.min(1f, (t - 0.18f) / 0.5f));
            scaleX = scaleY = pop;
            rotation = (float) (Math.sin(t * Math.PI * 3) * 9f * (1f - t));
        } else {
            // the avatar hops and squashes a little when it lands
            final float up = t < 0.45f ? (float) Math.sin(Math.PI * t / 0.45f) : 0f;
            dy = -dp(5) * up;
            final float land = t >= 0.42f && t < 0.75f ? (float) Math.sin(Math.PI * (t - 0.42f) / 0.33f) : 0f;
            final float stretch = t < 0.42f ? 0.07f * (float) Math.sin(Math.PI * t / 0.42f) : 0f;
            scaleX = 1f + 0.12f * land - stretch;
            scaleY = 1f - 0.12f * land + stretch;
            icon.setPivotY(icon.getHeight());
        }
        if (t >= 1f) {
            scaleX = scaleY = 1f;
            rotation = 0f;
            dy = 0f;
        }
        icon.setScaleX(rest * scaleX);
        icon.setScaleY(rest * scaleY);
        icon.setRotation(rotation);
        icon.setTranslationY(dy);
    }
    // endregion

    public boolean isTabSelected() {
        return isSelectedAnimator.getValue();
    }

    @Override
    public void onFactorChanged(int id, float factor, float fraction, FactorAnimator callee) {
        if (id == ANIMATOR_ID_IS_SELECTED) {
            updateColors();
        }
        invalidate();
    }

    private boolean needUpdateBackupViewColor;

    private void updateColors() {
        final int color = ColorUtils.blendARGB(colorDefault, colorSelected, isSelectedAnimator.getFloatValue());
        final int colorText = ColorUtils.blendARGB(colorDefault, colorSelectedText, isSelectedAnimator.getFloatValue());

        final PorterDuffColorFilter filter = new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN);
        if (backupImageView != null && needUpdateBackupViewColor) {
            backupImageView.setColorFilter(filter);
            backupImageView.invalidate();
        }
        imageView.setColorFilter(filter);
        textView.setTextColor(colorText);
    }

    public void updateColorsLottie() {
        colorDefault = Theme.getColor(Theme.key_glass_tabUnselected, resourcesProvider);
        colorSelected = Theme.getColor(Theme.key_glass_tabSelected, resourcesProvider);
        colorSelectedText = Theme.getColor(Theme.key_glass_tabSelectedText, resourcesProvider);
        updateColors();
        invalidate();
    }


    private boolean lastIsSelected;
    private int lastIconAnimationRaw;
    private long lastBotIconId;

    private void checkPlayAnimation(boolean animated) {
        final boolean isSelected = isSelectedAnimator.getValue();

        if (tabAnimationBot !=  null) {
            boolean animatedIcon = true;
            TLRPC.TL_attachMenuBotIcon icon = MediaDataController.getAnimatedAttachMenuBotIcon(tabAnimationBot, isSelected);
            if (icon == null) {
                icon = MediaDataController.getStaticAttachMenuBotIcon(tabAnimationBot);
                animatedIcon = false;
            }
            if (icon != null && icon.icon != null) {
                TLRPC.Document iconDoc = icon.icon;
                if (lastBotIconId != icon.icon.id) {
                    String filter = "24_24" + (animatedIcon && !animated || true ? "_lastframe" : "");
                    backupImageView.setImage(
                        ImageLocation.getForDocument(iconDoc), filter,
                        ImageLocation.getForDocument(iconDoc), filter,
                        animatedIcon ? null : DocumentObject.getSvgThumb(iconDoc, Theme.key_windowBackgroundGray, 1f),
                        tabAnimationBot
                    );
                    lastBotIconId = iconDoc.id;
                }
            } else {
                backupImageView.clearImage();
            }
            updateColors();
            return;
        }

        if (tabAnimation == null) {
            return;
        }

        // Solegram: an icon pack shows its own icons instead of the animations (filled when selected)
        final int packIcon = com.solegram.messenger.SolegramIcons.tabIcon(tabAnimation.name(), isSelected);
        if (packIcon != 0) {
            imageView.setImageResource(packIcon);
            updateColors();
            return;
        }

        if (tabAnimation.iconStatic != -1) {
            imageView.setImageResource(tabAnimation.iconStatic);
            updateColors();
            return;
        }

        final int animationToSet = isSelected ?
            tabAnimation.iconToFilled : tabAnimation.iconToOutline;

        if (tabAnimation.endFrameMid != -1) {
            boolean update = lastIsSelected != isSelected;
            if (lastIconAnimationRaw != animationToSet) {
                lastIconAnimationRaw = animationToSet;
                imageView.setAnimation(animationToSet, solegramIconDp, solegramIconDp);
                update = true;
            }

            if (update) {
                final RLottieDrawable drawable = imageView.getAnimatedDrawable();
                if (drawable == null) {
                    return;
                }

                if (isSelected) {
                    drawable.setCustomEndFrame(tabAnimation.endFrameMid);
                    if (drawable.getCurrentFrame() >= tabAnimation.endFrameEnd - 2) {
                        drawable.setCurrentFrame(0, false);
                    }
                    if (drawable.getCurrentFrame() <= tabAnimation.endFrameMid) {
                        drawable.start();
                    } else {
                        drawable.setCurrentFrame(tabAnimation.endFrameMid);
                    }
                } else {
                    if (drawable.getCurrentFrame() >= tabAnimation.endFrameMid - 1) {
                        drawable.setCustomEndFrame(tabAnimation.endFrameEnd - 1);
                        drawable.start();
                    } else {
                        drawable.setCustomEndFrame(0);
                        drawable.setCurrentFrame(0);
                    }
                }
            }
            lastIsSelected = isSelected;
            return;
        }

        if (tabAnimation.iconToFilled != tabAnimation.iconToOutline) {
            if (lastIconAnimationRaw != animationToSet) {
                lastIconAnimationRaw = animationToSet;

                imageView.setAnimation(animationToSet, solegramIconDp, solegramIconDp);
                imageView.getAnimatedDrawable().setPlayInDirectionOfCustomEndFrame(false);
                if (animated) {
                    imageView.getAnimatedDrawable().setCurrentFrame(0);
                    imageView.playAnimation();
                } else {
                    imageView.getAnimatedDrawable().setProgress(0.99f);
                }
            }
            return;
        }

        if (imageView.getAnimatedDrawable() == null) {
            imageView.setAnimation(tabAnimation.iconToFilled, solegramIconDp, solegramIconDp);
        }

        final RLottieDrawable drawable = imageView.getAnimatedDrawable();
        if (drawable == null) {
            return;
        }

        if (lastIsSelected != isSelected) {
            lastIsSelected = isSelected;
            if (isSelected) {
                drawable.setPlayInDirectionOfCustomEndFrame(false);
                drawable.setCurrentFrame(0);
                drawable.setCustomEndFrame(drawable.getFramesCount());
            } else {
                drawable.setPlayInDirectionOfCustomEndFrame(true);
                drawable.setCurrentFrame(drawable.getFramesCount());
                drawable.setCustomEndFrame(0);
            }
            imageView.playAnimation();
        }
    }

    public static GlassTabView createMainTab(Context context, Theme.ResourcesProvider resourcesProvider, TabAnimation tabAnimation, @StringRes int stringRes) {
        GlassTabView tab = new GlassTabView(context);
        tab.resourcesProvider = resourcesProvider;
        tab.tabAnimation = tabAnimation;
        tab.textView.setText(LocaleController.getString(stringRes));
        tab.checkPlayAnimation(false);
        tab.imageView.setLayoutParams(LayoutHelper.createFrame(24, 24, Gravity.CENTER_HORIZONTAL | Gravity.TOP, 0, 4, 0, 0));
        tab.colorDefault = Theme.getColor(Theme.key_glass_tabUnselected, resourcesProvider);
        tab.colorSelected = Theme.getColor(Theme.key_glass_tabSelected, resourcesProvider);
        tab.colorSelectedText = Theme.getColor(Theme.key_glass_tabSelectedText, resourcesProvider);
        tab.updateColors();
        return tab;
    }

    public static GlassTabView createAvatar(Context context, Theme.ResourcesProvider resourcesProvider, int currentAccount, @StringRes int stringRes) {
        GlassTabView tab = new GlassTabView(context);
        tab.textView.setText(LocaleController.getString(stringRes));
        tab.imageView.setVisibility(GONE);

        TLRPC.User user = MessagesController.getInstance(currentAccount).getUser(UserConfig.getInstance(currentAccount).getClientUserId());
        AvatarDrawable avatarDrawable = new AvatarDrawable(user);

        BackupImageView backupImageView = new BackupImageView(context);
        backupImageView.setForUserOrChat(user, avatarDrawable);
        backupImageView.setRoundRadius(dp(11));
        tab.backupImageView = backupImageView;

        tab.addView(backupImageView, LayoutHelper.createFrame(22, 22, Gravity.CENTER_HORIZONTAL | Gravity.TOP, 0, 5, 0, 0));
        tab.colorDefault = Theme.getColor(Theme.key_glass_tabUnselected, resourcesProvider);
        tab.colorSelected = Theme.getColor(Theme.key_glass_tabSelected, resourcesProvider);
        tab.colorSelectedText = Theme.getColor(Theme.key_glass_tabSelectedText, resourcesProvider);
        tab.updateColors();
        return tab;
    }

    public void updateUserAvatar(int currentAccount) {
        TLRPC.User user = MessagesController.getInstance(currentAccount).getUser(UserConfig.getInstance(currentAccount).getClientUserId());
        AvatarDrawable avatarDrawable = new AvatarDrawable(user);
        backupImageView.setForUserOrChat(user, avatarDrawable);
    }

    public static GlassTabView createAttachTab(Context context, Theme.ResourcesProvider resourcesProvider) {
        GlassTabView tab = new GlassTabView(context);
        tab.resourcesProvider = resourcesProvider;
        tab.selfMeasure = true;
        tab.textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
        tab.textView.setPadding(dp(8), 0, dp(8), 0);
        tab.checkPlayAnimation(false);
        tab.imageView.setLayoutParams(LayoutHelper.createFrame(24, 24, Gravity.CENTER_HORIZONTAL | Gravity.TOP, 0, 4, 0, 0));
        tab.colorDefault = Theme.getColor(Theme.key_glass_tabUnselected, resourcesProvider);
        tab.colorSelected = Theme.getColor(Theme.key_glass_tabSelected, resourcesProvider);
        tab.colorSelectedText = Theme.getColor(Theme.key_glass_tabSelectedText, resourcesProvider);
        tab.updateColors();
        return tab;
    }

    public static GlassTabView createAttachBotTab(Context context, Theme.ResourcesProvider resourcesProvider) {
        GlassTabView tab = new GlassTabView(context);
        tab.resourcesProvider = resourcesProvider;
        tab.selfMeasure = true;
        tab.textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
        tab.textView.setPadding(dp(8), 0, dp(8), 0);
        tab.imageView.setVisibility(GONE);
        tab.checkPlayAnimation(false);
        tab.backupImageView = new BackupImageView(context);
        tab.addView(tab.backupImageView, LayoutHelper.createFrame(24, 24, Gravity.CENTER_HORIZONTAL | Gravity.TOP, 0, 4, 0, 0));
        tab.colorDefault = Theme.getColor(Theme.key_glass_tabUnselected, resourcesProvider);
        tab.colorSelected = Theme.getColor(Theme.key_glass_tabSelected, resourcesProvider);
        tab.colorSelectedText = Theme.getColor(Theme.key_glass_tabSelectedText, resourcesProvider);
        tab.updateColors();
        return tab;
    }

    public BackupImageView getBackupImageView() {
        return backupImageView;
    }

    private boolean selfMeasure;
    private int additionalWidth;

    public void setAdditionalWidth(int additionalWidth) {
        this.additionalWidth = additionalWidth;
        this.selfMeasure = true;
    }

    public float measureAttachTabWidth() {
        final float textWidth = measureTextWidth();
        final float padding = lerp(dpf2(16), dp(8), MathUtils.clamp((textWidth - dp(40)) / dp(16), 0, 1));
        return Math.min(dp(84), (int) (textWidth + padding * 2));
    }

    public float attachScale = 1;
    public void setAttachScale(float scale) {
        textView.setScaleX(scale);
        textView.setScaleY(scale);
        imageView.setScaleX(scale);
        imageView.setScaleY(scale);
        if (backupImageView != null) {
            backupImageView.setScaleX(scale);
            backupImageView.setScaleY(scale);
        }
        attachScale = scale;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (selfMeasure) {
            final int width = (int) (measureAttachTabWidth()) + additionalWidth;
            super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), heightMeasureSpec);
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }
    }

    @Override
    public float measureTextWidth() {
        return defaultTextPaint.measureText(textView.getText().toString());
    }

    private TextPaint scaledTextPaint;

    @Override
    public float measureTextWidth(float textSizeDp) {
        if (scaledTextPaint == null) {
            scaledTextPaint = new TextPaint(defaultTextPaint);
        }
        scaledTextPaint.setTextSize(dp(textSizeDp));
        return scaledTextPaint.measureText(textView.getText().toString());
    }

    @Override
    public void setTextSizeDp(float textSizeDp) {
        final float px = dp(textSizeDp);
        if (textView.getTextSize() != px) {
            textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, textSizeDp);
            defaultTextPaint.setTextSize(px);
        }
    }

    private enum TabAnimationType {
        LOTTIE,
        STATIC
    }

    public enum TabAnimation {
        CONTACTS(R.raw.tab_contacts),
        CALLS(R.raw.tab_calls),
        CHATS(R.raw.tab_chats),
        SETTINGS(R.raw.tab_settings),
        FEED(R.raw.tab_feed), // Solegram

        CHECKLIST(R.raw.tab_checklist, R.raw.tab_checklist_reverse),
        COLORS(R.raw.tab_colors, R.raw.tab_colors_reverse),
        FILES(R.raw.tab_files, R.raw.tab_files_reverse),
        GALLERY(R.raw.tab_gallery, R.raw.tab_gallery_reverse),
        GIFT(R.raw.tab_gift, R.raw.tab_gift_reverse),
        LOCATION(R.raw.tab_location, R.raw.tab_location_reverse),
        STICKER(R.raw.tab_sticker, R.raw.tab_sticker_reverse),
        EMOJI(R.raw.tab_emoji, R.raw.tab_emoji_reverse),
        MODELS(R.raw.tab_models, R.raw.tab_models_reverse),
        MUSIC(R.raw.tab_music, R.raw.tab_music_reverse),
        POLL(R.raw.tab_poll, R.raw.tab_poll_reverse),
        SYMBOLS(R.raw.tab_symbols, R.raw.tab_symbols_reverse),
        REPLIES(R.raw.tab_reply, R.raw.tab_reply_reverse),
        WALLET(R.raw.tab_wallet, R.raw.tab_wallet_reverse),
        LINK(TabAnimationType.STATIC, R.drawable.tabs_link_24),
        STARS(TabAnimationType.STATIC, R.drawable.settings_stars), // Solegram: SoleDrop
        ARTICLE(R.raw.tab_article, R.raw.tab_article_reverse),
        GRAM(R.raw.gram, R.raw.gram_reverse),

        BOOSTS(R.raw.boosts, 25, 49),
        MONETIZATION(R.raw.monetize, 19, 45);

        public final @RawRes int iconToFilled;
        public final @RawRes int iconToOutline;
        public final @DrawableRes int iconStatic;
        public final int endFrameMid, endFrameEnd;

        TabAnimation(int iconRes, int endFrameMid, int endFrameEnd) {
            this.iconToFilled = iconRes;
            this.iconToOutline = iconRes;
            this.endFrameMid = endFrameMid;
            this.endFrameEnd = endFrameEnd;
            this.iconStatic = -1;
        }

        TabAnimation(TabAnimationType type, int icon) {
            if (type == TabAnimationType.LOTTIE) {
                this.iconToFilled = icon;
                this.iconToOutline = icon;
                this.iconStatic = -1;
            } else {
                this.iconStatic = icon;
                this.iconToFilled = -1;
                this.iconToOutline = -1;
            }
            this.endFrameMid = -1;
            this.endFrameEnd = -1;
        }

        TabAnimation(int iconRes) {
            this.iconToFilled = iconRes;
            this.iconToOutline = iconRes;
            this.endFrameMid = -1;
            this.endFrameEnd = -1;
            this.iconStatic = -1;
        }

        TabAnimation(int iconToFilled, int iconToOutline) {
            this.iconToFilled = iconToFilled;
            this.iconToOutline = iconToOutline;
            this.endFrameMid = -1;
            this.endFrameEnd = -1;
            this.iconStatic = -1;
        }
    }

    public void setTabAnimation(TabAnimation animation) {
        tabAnimation = animation;
        tabAnimationBot = null;
        lastIconAnimationRaw = 0;
        lastBotIconId = 0;
        imageView.clearAnimationDrawable();
        checkPlayAnimation(false);
    }

    public void setText(CharSequence text) {
        textView.setText(text);
    }


    private AvatarDrawable avatarDrawable;

    public void setAttachBot(TLRPC.User user, TLRPC.TL_attachMenuBot bot, int currentAccount) {
        if (user == null || bot == null) {
            return;
        }
        tabAnimation = null;
        tabAnimationBot = bot;
        lastIconAnimationRaw = 0;
        lastBotIconId = 0;
        textView.setText(bot.short_name);

        backupImageView.setRoundRadius(0);
        backupImageView.setSize(dp(24), dp(24));
        backupImageView.setLayoutParams(LayoutHelper.createFrame(24, 24, Gravity.CENTER_HORIZONTAL | Gravity.TOP, 0, 4, 0, 0));
        needUpdateBackupViewColor = true;
        checkPlayAnimation(false);
        updateColors();
        invalidate();
    }

    public void setAttachBotUser(TLRPC.User user, int currentAccount) {
        if (user == null) {
            return;
        }
        tabAnimation = null;
        tabAnimationBot = null;
        lastIconAnimationRaw = 0;
        lastBotIconId = 0;

        textView.setText(ContactsController.formatName(user.first_name, user.last_name));
        if (avatarDrawable == null) {
            avatarDrawable = new AvatarDrawable();
        }
        avatarDrawable.setInfo(currentAccount, user);
        backupImageView.setForUserOrChat(user, avatarDrawable);
        backupImageView.setSize(-1, -1);
        backupImageView.setRoundRadius(dp(11.33f));
        backupImageView.setLayoutParams(LayoutHelper.createFrame(22, 22, Gravity.CENTER_HORIZONTAL | Gravity.TOP, 0, 5, 0, 0));
        backupImageView.setColorFilter(null);
        needUpdateBackupViewColor = false;
        invalidate();
    }

    public void onPreBind() {

    }
}