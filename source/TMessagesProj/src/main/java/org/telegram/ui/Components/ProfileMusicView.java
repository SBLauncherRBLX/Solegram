package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.dpf2;
import static org.telegram.messenger.AndroidUtilities.lerp;
import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;
import static org.telegram.ui.ActionBar.Theme.multAlpha;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory;
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundColorProvider;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;

public class ProfileMusicView extends View {

    private final Theme.ResourcesProvider resourcesProvider;
    private final PorterDuffColorFilter filterColorWhite = new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN);
    private final PorterDuffColorFilter filterColorBlack = new PorterDuffColorFilter(Color.BLACK, PorterDuff.Mode.SRC_IN);

    private Text author, title;
    private ProfileMusicView trailingView;
    private ProfileMusicView leadingView;

    // The two pills share a centered row and the same profile/blur animation state.
    public void setTrailingView(ProfileMusicView view) {
        trailingView = view;
        view.leadingView = this;
        invalidate();
        view.invalidate();
    }

    private final Paint iconPaint = new Paint();
    private final Paint arrowPaint = new Paint();
    private final Path arrowPath = new Path();
    private final Drawable icon;

    private final RectF rect = new RectF();

    /** The pill's corner radius now. */
    public float getPillRadius() {
        return rect.height() / 2f;
    }

    public void getButtonBoundsOnScreen(RectF bounds) {
        int[] location = new int[2];
        getLocationOnScreen(location);
        bounds.set(rect);
        bounds.offset(location[0], location[1]);
    }

    private final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clipPath = new Path();

    private final ButtonBounce bounce = new ButtonBounce(this);

    public ProfileMusicView(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;

        icon = context.getResources().getDrawable(R.drawable.files_music).mutate();

        arrowPaint.setStyle(Paint.Style.STROKE);
        arrowPaint.setStrokeCap(Paint.Cap.ROUND);
        arrowPaint.setStrokeJoin(Paint.Join.ROUND);
        arrowPath.moveTo(0, -dpf2(3.33f));
        arrowPath.lineTo(dpf2(3.16f), 0);
        arrowPath.lineTo(0, dpf2(3.33f));

        setColor(null);
        setText("Author", " - Title");
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(
                MeasureSpec.getSize(widthMeasureSpec),
                MeasureSpec.EXACTLY
            ),
            MeasureSpec.makeMeasureSpec(
                dp(VIEW_HEIGHT + CARD_HEIGHT),
                MeasureSpec.EXACTLY
            )
        );
    }

    // Solegram: a bigger pill, a line of its own under the status (the row is ROW_HEIGHT tall in the header)
    public static final int ROW_HEIGHT = 34;
    private static final int VIEW_HEIGHT = 44;
    private static final int PILL_TOP = 13;
    private static final int PILL_HEIGHT = 26;
    /** The pill opened into the song's card: it grows down and to the sides (the room below is the buttons'). */
    private static final int CARD_HEIGHT = 100;
    private static final int CARD_PAD = 12;
    private static final int CARD_COVER = 76;
    private static final int CARD_BUTTON = 44;

    /** What the opened card shows and does. */
    public interface Card {
        String title();

        String artist();

        float progress();

        long durationMs();

        boolean playing();

        /** The button plays and pauses here; false: it opens the track's page. */
        boolean controls();

        void playPause();

        void open();

        @Nullable
        android.graphics.Bitmap cover();

        @Nullable
        String coverUrl();

        @Nullable
        CharSequence badge();
    }

    @Nullable
    private Card card;
    private float expand;
    @Nullable
    private android.animation.ValueAnimator expandAnimator;
    private final RectF shape = new RectF();
    private final RectF cardRect = new RectF();
    private final RectF cardButton = new RectF();
    private org.telegram.messenger.ImageReceiver cardCover;
    private android.graphics.Bitmap cardCoverSet;
    private PlayPauseDrawable cardPlayPause;
    private Drawable cardOpenIcon;
    private Text cardTitle, cardArtist, cardBadge;
    private float cardTextWidth = -1;
    private final Paint cardBarPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cardButtonPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public boolean isExpanded() {
        return card != null;
    }

    /** Opens the pill into the song's card, smoothly (down and to the sides, still glass). */
    public void expand(Card card) {
        this.card = card;
        cardTextWidth = -1;
        if (cardCover == null) {
            cardCover = new org.telegram.messenger.ImageReceiver(this);
            cardCover.setRoundRadius(dp(14));
            cardPlayPause = new PlayPauseDrawable(16);
            cardPlayPause.setCallback(this);
            cardOpenIcon = getContext().getResources().getDrawable(R.drawable.msg_openin).mutate();
            if (isAttachedToWindow()) {
                cardCover.onAttachedToWindow();
            }
        }
        cardCoverSet = null;
        cardCover.clearImage();
        if (card.cover() == null && !TextUtils.isEmpty(card.coverUrl())) {
            cardCover.setImage(org.telegram.messenger.ImageLocation.getForPath(card.coverUrl()), "200_200", null, null, null, 0, null, null, 1);
        }
        animateExpand(1f, null);
    }

    /** Back into the pill; {@code end} once it is a pill again. */
    public void collapse(@Nullable Runnable end) {
        if (card == null) {
            if (end != null) end.run();
            return;
        }
        animateExpand(0f, () -> {
            card = null;
            if (end != null) end.run();
        });
    }

    /** At once (the player opened over it). */
    public void collapseNow() {
        if (expandAnimator != null) {
            expandAnimator.cancel();
        }
        expand = 0;
        card = null;
        invalidate();
        if (trailingView != null) trailingView.invalidate();
    }

    private void animateExpand(float to, @Nullable Runnable end) {
        if (expandAnimator != null) {
            expandAnimator.cancel();
        }
        final android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(expand, to);
        expandAnimator = a;
        a.addUpdateListener(v -> {
            expand = (float) v.getAnimatedValue();
            invalidate();
            if (trailingView != null) trailingView.invalidate();
        });
        // a soft spring-like ease, the same both ways
        a.setInterpolator(new android.view.animation.PathInterpolator(0.22f, 1f, 0.36f, 1f));
        a.setDuration(to > expand ? 520 : 420);
        a.addListener(new android.animation.AnimatorListenerAdapter() {
            private boolean cancelled;

            @Override
            public void onAnimationCancel(android.animation.Animator animation) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                if (!cancelled && end != null) {
                    end.run();
                }
            }
        });
        a.start();
    }

    /** The card on the screen (for the taps around it). */
    public void getCardBoundsOnScreen(RectF out) {
        final int[] location = new int[2];
        getLocationOnScreen(location);
        out.set(shape);
        out.offset(location[0], location[1]);
    }

    /** A tap on the card at a point on the screen: its button plays/pauses (or opens), elsewhere it opens. */
    public void tapCard(float screenX, float screenY) {
        final Card c = card;
        if (c == null) {
            return;
        }
        final int[] location = new int[2];
        getLocationOnScreen(location);
        final float x = screenX - location[0], y = screenY - location[1];
        if (expand > 0.6f && cardButton.contains(x, y)) {
            if (c.controls()) {
                c.playPause();
                invalidate();
            } else {
                c.open();
            }
        } else {
            c.open();
        }
    }

    @Override
    protected boolean verifyDrawable(@NonNull Drawable who) {
        return who == cardPlayPause || super.verifyDrawable(who);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (cardCover != null) {
            cardCover.onAttachedToWindow();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (cardCover != null) {
            cardCover.onDetachedFromWindow();
        }
        if (expandAnimator != null) {
            expandAnimator.cancel();
        }
    }

    private static String cardTime(long ms) {
        return AndroidUtilities.formatShortDuration((int) (Math.max(0, ms) / 1000));
    }

    // the wave of the card's progress, as the player's (SolegramCometProgress, STYLE_WAVE)
    private final Paint waveStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path wavePath = new Path();
    private float waveAmount, wavePhase;
    private long waveLast;

    private void drawWave(Canvas canvas, float left, float right, float cy, float progress, boolean playing, float a) {
        final long now = android.os.SystemClock.elapsedRealtime();
        final float dt = waveLast == 0 ? 16 : Math.min(64, now - waveLast);
        waveLast = now;
        final float target = playing ? 1f : 0f;
        waveAmount = target > waveAmount ? Math.min(1, waveAmount + dt / 400f) : Math.max(0, waveAmount - dt / 500f);
        final float eased = waveAmount * waveAmount * (3 - 2 * waveAmount);
        final float wavelength = dp(26);
        wavePhase = (wavePhase + dp(30) * dt / 1000f * eased) % (wavelength * 1000);
        final float amp = dp(2.2f) * eased;
        final float stroke = dp(3);
        final float gap = dp(5);
        final float x = left + (right - left) * progress;

        waveStroke.setStyle(Paint.Style.STROKE);
        waveStroke.setStrokeCap(Paint.Cap.ROUND);
        waveStroke.setStrokeJoin(Paint.Join.ROUND);
        waveStroke.setStrokeWidth(stroke);
        // the rest: a faint straight line
        if (x + gap < right) {
            waveStroke.setColor(Theme.multAlpha(textColor, 0.24f * a));
            canvas.drawLine(x + gap, cy, right, cy, waveStroke);
        }
        // the played part: the wave, flat where it meets the start and the head
        final float end = x - gap;
        if (end > left) {
            waveStroke.setColor(Theme.multAlpha(textColor, a));
            wavePath.rewind();
            final float step = dp(1.5f), flatEnd = dp(12), flatStart = dp(8);
            for (float px = left; ; px += step) {
                final float at = Math.min(px, end);
                final float fade = Math.min(1, (end - at) / flatEnd) * Math.min(1, (at - left) / flatStart);
                final float y = cy + (float) Math.sin((at - wavePhase) / wavelength * Math.PI * 2) * amp * fade;
                if (at == left) {
                    wavePath.moveTo(at, y);
                } else {
                    wavePath.lineTo(at, y);
                }
                if (at >= end) {
                    break;
                }
            }
            canvas.drawPath(wavePath, waveStroke);
        }
        // the head: a small upright bar
        cardBarPaint.setColor(Theme.multAlpha(textColor, a));
        final float hh = dp(12), hw = dp(3);
        AndroidUtilities.rectTmp.set(x - hw / 2f, cy - hh / 2f, x + hw / 2f, cy + hh / 2f);
        canvas.drawRoundRect(AndroidUtilities.rectTmp, hw / 2f, hw / 2f, cardBarPaint);
        if (waveAmount > 0 && waveAmount < 1) {
            invalidate(); // lying down / rising
        }
    }

    /** The card's content in its final place {@code r}. */
    private void drawCard(Canvas canvas, RectF r, float a) {
        final Card c = card;
        if (c == null || a <= 0) {
            return;
        }
        final float w = r.width() - dp(CARD_PAD) * 2 - dp(CARD_COVER) - dp(12) - dp(CARD_BUTTON) - dp(10);
        if (w != cardTextWidth) {
            cardTextWidth = w;
            cardTitle = new Text(c.title(), 16, AndroidUtilities.bold()).ellipsize(w);
            cardArtist = new Text(c.artist(), 14).ellipsize(w);
            final CharSequence b = c.badge();
            cardBadge = b != null ? new Text(b.toString().toUpperCase(), 10, AndroidUtilities.bold()).ellipsize(w) : null;
        }
        final float cx = r.left + dp(CARD_PAD), cy = r.top + dp(CARD_PAD);
        final android.graphics.Bitmap bitmap = c.cover();
        if (bitmap != null && bitmap != cardCoverSet) {
            cardCoverSet = bitmap;
            cardCover.setImageBitmap(bitmap);
        }
        cardCover.setImageCoords(cx, cy, dp(CARD_COVER), dp(CARD_COVER));
        cardCover.setAlpha(a);
        if (cardCover.hasImageSet()) {
            cardCover.draw(canvas);
        } else {
            cardBarPaint.setColor(Theme.multAlpha(textColor, 0.12f * a));
            AndroidUtilities.rectTmp.set(cx, cy, cx + dp(CARD_COVER), cy + dp(CARD_COVER));
            canvas.drawRoundRect(AndroidUtilities.rectTmp, dp(14), dp(14), cardBarPaint);
            final int s = dp(28);
            icon.setBounds((int) (cx + dp(CARD_COVER) / 2f - s / 2f), (int) (cy + dp(CARD_COVER) / 2f - s / 2f), (int) (cx + dp(CARD_COVER) / 2f + s / 2f), (int) (cy + dp(CARD_COVER) / 2f + s / 2f));
            icon.setAlpha((int) (0x99 * a));
            icon.draw(canvas);
        }
        final float tx = cx + dp(CARD_COVER) + dp(12);
        float y = cy + dp(9);
        if (cardBadge != null) {
            cardBadge.draw(canvas, tx, y, textColor, 0.65f * a);
            y += dp(17);
        } else {
            y += dp(3);
        }
        cardTitle.draw(canvas, tx, y, textColor, a);
        y += dp(20);
        cardArtist.draw(canvas, tx, y, textColor, 0.7f * a);

        // how far it played: the player's wave (swaying while it plays, straight on pause), and the times
        final float barY = cy + dp(CARD_COVER) - dp(14);
        final float barRight = tx + w;
        final float played = c.progress();
        drawWave(canvas, tx, barRight, barY, played >= 0 ? Utilities.clamp01(played) : 0f, c.playing(), a);
        if (played >= 0) {
            final long duration = c.durationMs();
            if (duration > 0) {
                final Text left = new Text(cardTime((long) (duration * Utilities.clamp01(played))), 11);
                final Text right = new Text(cardTime(duration), 11);
                left.draw(canvas, tx, barY + dp(11), textColor, 0.6f * a);
                right.draw(canvas, barRight - right.getWidth(), barY + dp(11), textColor, 0.6f * a);
            }
        }

        // play / pause, or open the track's page
        final float bx = r.right - dp(CARD_PAD) - dp(CARD_BUTTON), by = r.centerY() - dp(CARD_BUTTON) / 2f;
        cardButton.set(bx, by, bx + dp(CARD_BUTTON), by + dp(CARD_BUTTON));
        final int buttonColor = textColor == Color.BLACK ? Color.BLACK : Color.WHITE;
        final int glyphColor = textColor == Color.BLACK ? Color.WHITE : 0xFF111111;
        cardButtonPaint.setColor(Theme.multAlpha(buttonColor, a));
        canvas.drawCircle(cardButton.centerX(), cardButton.centerY(), dp(CARD_BUTTON) / 2f, cardButtonPaint);
        final int s = c.controls() ? dp(26) : dp(22);
        final Drawable glyph;
        if (c.controls()) {
            cardPlayPause.setColor(glyphColor);
            cardPlayPause.setPause(c.playing(), true);
            glyph = cardPlayPause;
        } else {
            cardOpenIcon.setColorFilter(new PorterDuffColorFilter(glyphColor, PorterDuff.Mode.SRC_IN));
            glyph = cardOpenIcon;
        }
        glyph.setAlpha((int) (0xFF * a));
        glyph.setBounds((int) cardButton.centerX() - s / 2, (int) cardButton.centerY() - s / 2, (int) cardButton.centerX() + s / 2, (int) cardButton.centerY() + s / 2);
        glyph.draw(canvas);
    }

    /** What plays, for the progress and the bars: null for a pill without music (the balance). */
    public interface State {
        /** 0..1 of the track played, or a negative number when it is not known. */
        float progress();

        boolean playing();
    }

    @Nullable
    private State state;
    private final Paint progressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float pillCenterY;
    private long barsStart;

    @Nullable
    private Text badge;

    /** A small word before the track ("Playing now"), null: none. */
    public void setBadge(@Nullable CharSequence text) {
        badge = TextUtils.isEmpty(text) ? null : new Text(text.toString().toUpperCase(), 10, AndroidUtilities.bold());
        invalidate();
    }

    public void setState(@Nullable State state) {
        this.state = state;
        invalidate();
    }

    /** The middle of the pill, where its content lines up. */
    protected float pillCenterY() {
        return pillCenterY > 0 ? pillCenterY : getHeight() / 2f;
    }

    @Nullable
    private BlurredBackgroundColorProvider glassColors;

    protected int textColor = Color.WHITE;
    protected boolean hasProfileColor;
    private float parentExpanded;
    private int buttonColor;
    private boolean hasColorById;
    private RadialGradient radialGradient;
    private float radialGradientWidth;
    private final Paint shaderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix matrix = new Matrix();

    public void setColor(MessagesController.PeerColor peerColor) {
        hasProfileColor = peerColor != null;
        if (trailingView != null) trailingView.setColor(peerColor);
        invalidate();
    }

    // Same color as ProfileActionsView buttons.
    public void setButtonColor(int color, boolean hasColorById) {
        if (trailingView != null) trailingView.setButtonColor(color, hasColorById);
        if (buttonColor == color && this.hasColorById == hasColorById) return;
        buttonColor = color;
        this.hasColorById = hasColorById;
        radialGradient = null;
        backgroundPaint.setColor(color);
        checkTextColor();
    }

    private boolean isButtonColorLight() {
        return AndroidUtilities.computePerceivedBrightness(buttonColor) > 0.72f;
    }

    private void checkTextColor() {
        final boolean useBlackText = parentExpanded < 0.8f && isButtonColorLight();
        textColor = useBlackText ? Color.BLACK : Color.WHITE;
        icon.setColorFilter(useBlackText ? filterColorBlack : filterColorWhite);
        iconPaint.setColor(textColor);
        arrowPaint.setColor(Theme.multAlpha(textColor, 0.85f));
        invalidate();
    }

    public void setParentExpanded(float expanded) {
        if (trailingView != null) trailingView.setParentExpanded(expanded);
        if (parentExpanded != expanded) {
            parentExpanded = expanded;
            checkTextColor();
            invalidate();
        }
    }



    public void setMusicDocument(TLRPC.Document document) {
        CharSequence author = getAuthor(document);
        CharSequence title = getTitle(document);
        if (TextUtils.isEmpty(author)) {
            if (TextUtils.isEmpty(title)) {
                author = getString(R.string.AudioUnknownArtist);
                title = " - " + getString(R.string.AudioUnknownTitle);
            } else {
                author = "";
            }
        } else if (!TextUtils.isEmpty(title)) {
            title = " - " + title;
        } else {
            title = "";
        }
        setText(author, title);
    }

    public static CharSequence getTitle(TLRPC.Document document) {
        if (document == null) {
            return null;
        }
        for (int a = 0; a < document.attributes.size(); a++) {
            TLRPC.DocumentAttribute attribute = document.attributes.get(a);
            if (attribute instanceof TLRPC.TL_documentAttributeAudio) {
                String title = attribute.title;
                if (title == null || title.length() == 0) {
                    title = FileLoader.getDocumentFileName(document);
                }
                return title;
            }
        }
        String fileName = FileLoader.getDocumentFileName(document);
        if (!TextUtils.isEmpty(fileName)) {
            return fileName;
        }
//        return getString(R.string.AudioUnknownTitle);
        return null;
    }

    public static CharSequence getAuthor(TLRPC.Document document) {
        if (document == null) {
            return null;
        }
        for (int a = 0; a < document.attributes.size(); a++) {
            TLRPC.DocumentAttribute attribute = document.attributes.get(a);
            if (attribute instanceof TLRPC.TL_documentAttributeAudio) {
                if (!attribute.voice) {
                    return attribute.performer;
                }
            }
        }
        return null;
    }

    public void setText(CharSequence author, CharSequence title) {
        // Solegram: bigger, as the pill is (the first part bold, the second dimmer)
        this.author = new Text(author, 13, AndroidUtilities.bold());
        this.title = new Text(title, 13);

        setContentDescription(getString(R.string.AccDescrProfileMusic) + " " + author + " — " + title);
    }

    /** The pill's glass, like the action buttons'; without it the pill is filled with their color. */
    @Nullable
    private BlurredBackgroundDrawable glass;
    private float glassRadius;

    /** The pill on glass, see {@link ProfileButtonsGlass#actionsFactory}. */
    public void setGlassFactory(@Nullable BlurredBackgroundDrawableViewFactory factory) {
        setGlassFactory(factory, null);
    }

    /** The pill on glass with a rim and a shadow of {@code colors} (null: none). */
    public void setGlassFactory(@Nullable BlurredBackgroundDrawableViewFactory factory, @Nullable BlurredBackgroundColorProvider colors) {
        if (trailingView != null) trailingView.setGlassFactory(factory, colors);
        glassColors = colors;
        glass = factory != null ? factory.create(this, colors != null ? colors : ProfileButtonsGlass.NO_COLORS) : null;
        glassRadius = 0;
        invalidate();
    }

    private float currentHeight;

    @Override
    public void setAlpha(float alpha) {
        super.setAlpha(alpha);
        if (trailingView != null) trailingView.setAlpha(alpha);
    }

    protected boolean isOnProfilePhoto() {
        return parentExpanded >= 0.8f;
    }

    public void updatePosition(float y, float newHeight) {
        if (trailingView != null) trailingView.updatePosition(y, newHeight);
        currentHeight = newHeight;
        setTranslationY(y - dp(12));
        invalidate();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        final float alpha = Utilities.clamp01((currentHeight) / dp(ROW_HEIGHT - 4));
        if (alpha <= 0 || card != null) return false; // opened, the taps go through the profile's catcher
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            bounce.setPressed(rect.contains(event.getX(), event.getY()));
        } else if (event.getAction() == MotionEvent.ACTION_MOVE && bounce.isPressed()) {
            if (!rect.contains(event.getX(), event.getY())) {
                bounce.setPressed(false);
            }
        } else if (event.getAction() == MotionEvent.ACTION_CANCEL) {
            bounce.setPressed(false);
        } else if (event.getAction() == MotionEvent.ACTION_UP) {
            if (bounce.isPressed()) {
                performClick();
            }
            bounce.setPressed(false);
        }
        return bounce.isPressed();
    }

    protected float getContentWidth(float maxWidth) {
        final float badgeWidth = badge != null ? badge.getWidth() + dp(7) : 0;
        final float textWidth = Math.max(0, maxWidth - dp(16) - dp(22) - dp(6) - badgeWidth);
        this.author.ellipsize(textWidth * 0.6f);
        this.title.ellipsize(Math.max(0, textWidth - this.author.getWidth()));
        return dp(22) + badgeWidth + this.author.getWidth() + this.title.getWidth() + dp(6);
    }

    protected void drawContent(Canvas canvas, float alpha) {
        final float cy = pillCenterY();

        // Solegram: while it plays, three bars dance in place of the note
        final boolean playing = state != null && state.playing();
        if (playing) {
            if (barsStart == 0) {
                barsStart = android.os.SystemClock.elapsedRealtime();
            }
            final float t = (android.os.SystemClock.elapsedRealtime() - barsStart) / 1000f;
            barPaint.setColor(Theme.multAlpha(textColor, alpha));
            final float w = dpf2(2.6f), gap = dpf2(2.2f), max = dp(13), min = dp(4);
            for (int i = 0; i < 3; i++) {
                final float k = 0.5f + 0.5f * (float) Math.sin(t * (5.2f + i * 1.7f) + i * 1.9f);
                final float h = min + (max - min) * k;
                final float x = dpf2(1) + i * (w + gap);
                AndroidUtilities.rectTmp.set(x, cy - h / 2f, x + w, cy + h / 2f);
                canvas.drawRoundRect(AndroidUtilities.rectTmp, w / 2f, w / 2f, barPaint);
            }
        } else {
            barsStart = 0;
            final int iconSz = dp(15);
            icon.setBounds(0, (int) cy - iconSz / 2, iconSz, (int) cy + iconSz / 2);
            icon.setAlpha((int) (0xFF * alpha));
            icon.draw(canvas);
        }

        canvas.translate(dp(22), 0);
        if (badge != null) {
            badge.draw(canvas, 0, cy, textColor, 0.65f * alpha);
            canvas.translate(badge.getWidth() + dp(7), 0);
        }
        this.author.draw(canvas, 0, cy, textColor, alpha);
        canvas.translate(this.author.getWidth(), 0);
        this.title.draw(canvas, 0, cy, textColor, 0.6f * alpha);
    }

    private boolean hasVisibleCompanion() {
        ProfileMusicView companion = trailingView != null ? trailingView : leadingView;
        return companion != null && companion.getVisibility() == VISIBLE;
    }

    private float getPillWidth(float maxWidth) {
        return dp(20) + getContentWidth(maxWidth);
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        if (this.author == null || this.title == null) return;

        float alpha = Utilities.clamp01((currentHeight) / dp(ROW_HEIGHT - 4));
        if (leadingView != null && leadingView.expand > 0) {
            alpha *= 1f - Utilities.clamp01(leadingView.expand * 2f); // the companion makes room for the card
        }
        final float scale = expand > 0 ? 1f : bounce.getScale(0.02f);
        if (alpha <= 0) {
            if (glass != null) {
                glass.setAlpha(0);
            }
            return;
        }

        final int padding = dp(12);
        final int maxWidth = getWidth() - padding * 2;

        float containerWidth;
        float left;
        if (hasVisibleCompanion()) {
            ProfileMusicView leading = leadingView != null ? leadingView : this;
            ProfileMusicView trailing = trailingView != null ? trailingView : this;
            // Reserve the balance first so long song titles cannot push it offscreen.
            float trailingWidth = trailing.getPillWidth(maxWidth / 2f);
            float leadingWidth = leading.getPillWidth(Math.max(0, maxWidth - trailingWidth - dp(6)));
            left = (getWidth() - leadingWidth - dp(6) - trailingWidth) / 2f;
            containerWidth = leadingView != null ? trailingWidth : leadingWidth;
            if (leadingView != null) left += leadingWidth + dp(6);
        } else {
            containerWidth = getPillWidth(maxWidth);
            left = (getWidth() - containerWidth) / 2f;
        }

        rect.set(left, dp(PILL_TOP), left + containerWidth, dp(PILL_TOP) + dp(PILL_HEIGHT) * alpha);
        pillCenterY = rect.centerY();
        // Solegram: opened, the pill grows down and to the sides into the song's card, staying glass
        final float e = expand;
        cardRect.set(dp(16), rect.top, getWidth() - dp(16), rect.top + dp(CARD_HEIGHT));
        shape.set(
            AndroidUtilities.lerp(rect.left, cardRect.left, e),
            rect.top,
            AndroidUtilities.lerp(rect.right, cardRect.right, e),
            AndroidUtilities.lerp(rect.bottom, cardRect.bottom, e));
        final float shapeRadius = AndroidUtilities.lerp(rect.height() / 2f, dp(24), e);
        canvas.save();
        canvas.scale(scale, scale, rect.centerX(), rect.centerY());

        if (glass != null) {
            final float radius = shapeRadius;
            if (glassRadius != radius) {
                glassRadius = radius;
                glass.setRadius(radius);
            }
            glass.setBounds((int) shape.left, (int) shape.top, (int) Math.ceil(shape.right), (int) Math.ceil(shape.bottom));
            glass.setAlpha((int) (0xFF * alpha));
            glass.draw(canvas);
        } else {
            final float bgAlpha = hasColorById ? 0.1f : 1f;
            backgroundPaint.setShadowLayer(0, 0, 0, 0);
            int wasAlpha = backgroundPaint.getAlpha();
            backgroundPaint.setAlpha((int) (wasAlpha * alpha * bgAlpha));
            canvas.drawRoundRect(rect, rect.height() / 2f, rect.height() / 2f, backgroundPaint);
            backgroundPaint.setAlpha(wasAlpha);
            if (hasColorById && buttonColor != 0) {
                if (radialGradient == null || radialGradientWidth != rect.width()) {
                    radialGradientWidth = rect.width();
                    radialGradient = new RadialGradient(
                        rect.width() / 2f, dp(17) / 2f, rect.width() * 0.65f,
                        Theme.multAlpha(buttonColor, 0.8f), buttonColor,
                        Shader.TileMode.CLAMP
                    );
                    shaderPaint.setShader(radialGradient);
                }
                matrix.setTranslate(rect.left, rect.top);
                radialGradient.setLocalMatrix(matrix);
                shaderPaint.setAlpha((int) (0xFF * alpha));
                canvas.drawRoundRect(rect, rect.height() / 2f, rect.height() / 2f, shaderPaint);
            }
        }

        clipPath.rewind();
        clipPath.addRoundRect(shape, shapeRadius, shapeRadius, Path.Direction.CW);

        canvas.save();
        canvas.clipPath(clipPath);
        // the pill's own content fades out as the card opens, the card's fades in
        final float pillAlpha = alpha * (1f - Utilities.clamp01(e * 2.5f));
        // Solegram: the part already played is lighter, as on the player's line
        final float progress = state != null ? state.progress() : -1f;
        if (progress >= 0f && pillAlpha > 0) {
            progressPaint.setColor(textColor == Color.BLACK ? 0x14000000 : 0x2EFFFFFF);
            progressPaint.setAlpha((int) (progressPaint.getAlpha() * pillAlpha));
            canvas.drawRect(rect.left, rect.top, rect.left + rect.width() * Utilities.clamp01(progress), rect.bottom, progressPaint);
        }
        if (pillAlpha > 0) {
            canvas.save();
            canvas.translate(rect.left + dp(10), 0);
            drawContent(canvas, pillAlpha);
            canvas.restore();
        }
        if (e > 0) {
            // the card's content stays in its final place, uncovered as the glass grows over it
            drawCard(canvas, cardRect, alpha * Utilities.clamp01((e - 0.3f) / 0.6f));
        }

        canvas.restore();

        canvas.restore();
        if (card != null && card.playing()) {
            postInvalidateOnAnimation(); // the card's wave sways
        } else if (state != null && state.playing()) {
            postInvalidateDelayed(66); // the pill's bars and its progress move
        }
    }
}
