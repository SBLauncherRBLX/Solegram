/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.dpf2;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;

import com.solegram.messenger.SolegramConfig;
import com.solegram.messenger.SolegramNowPlaying;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedFloat;
import org.telegram.ui.Components.CubicBezierInterpolator;

/**
 * Music in the profile: the card with what someone listens to now, in one of three looks (see
 * {@link SolegramConfig#nowPlayingStyle}): washed with the cover's colours, frosted glass made of the cover, or one
 * slim line. The cover may be a record that turns while the music plays; the own card shows the song's progress.
 */
public class SolegramNowPlayingView extends View {

    public static final int STYLE_COVER = 0;
    public static final int STYLE_GLASS = 1;
    public static final int STYLE_COMPACT = 2;

    private static final int SPOTIFY_GREEN = 0xFF1DB954;
    private static final int SOUNDCLOUD_ORANGE = 0xFFFF5500;

    // the parts of the tall card, dp
    private static final int TOP_BLOCK = 92;
    private static final int PROGRESS_BLOCK = 30;
    private static final int COMPACT_CARD = 62;

    private final Theme.ResourcesProvider resourcesProvider;
    private SolegramNowPlaying.Track track;
    private boolean self;
    private CharSequence emptyText;

    private final RectF card = new RectF();
    private final RectF cover = new RectF();
    private final RectF tmp = new RectF();
    private final Path clip = new Path();
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint washPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint coverPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint progressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint statusPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint titlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint artistPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint timePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Drawable selector;
    private Drawable noteDrawable;

    private Bitmap washBitmap, washSource;
    private final Matrix washMatrix = new Matrix();
    private final Matrix coverMatrix = new Matrix();
    private BitmapShader washShader, coverShader;
    private Bitmap coverShaderBitmap;
    // the card redraws every frame while music plays (equalizer, progress, the record): what each frame would
    // otherwise allocate again is kept until its inputs change
    private final Path markPath = new Path();
    private LinearGradient washFade, coverFill, glassFill, glassScrim, rimShader;
    private float rimTop, rimBottom;
    private boolean rimDark;
    private int glassFillTint;
    private float glassFillLeft, glassFillTop;
    private float washFadeLeft, washFadeRight, coverFillLeft, coverFillTop, glassScrimTop, glassScrimBottom;
    private int washFadeBase, coverFillTint;
    private boolean washFadeDark;
    private String elapsedText, remainingText;
    private long timeKey = -1;
    private CharSequence titleSource, titleShown, artistSource, artistShown;
    private float titleWidth = -1, artistWidth = -1;
    private float contentAlpha = 1f;
    private ValueAnimator fadeAnimator;
    // the progress line glides to a new place after a seek, and follows the song exactly while it plays
    private final AnimatedFloat shownProgress = new AnimatedFloat(this, 0, 380, CubicBezierInterpolator.EASE_OUT_QUINT);
    // the record: its angle and when it was last turned
    private float vinylAngle;
    private long vinylTime;

    public SolegramNowPlayingView(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        statusPaint.setTypeface(AndroidUtilities.bold());
        titlePaint.setTypeface(AndroidUtilities.bold());
        timePaint.setTextSize(dp(11.5f));
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(Math.max(1, dpf2(0.66f)));
        barPaint.setStyle(Paint.Style.FILL);
        progressPaint.setStrokeCap(Paint.Cap.ROUND);
        selector = Theme.createRadSelectorDrawable(Theme.getColor(Theme.key_listSelector, resourcesProvider), 22, 22);
        selector.setCallback(this);
    }

    private static int style() {
        return SolegramConfig.nowPlayingStyle;
    }

    /** @param self the own card (live progress, a cover from the player) */
    public void setTrack(@Nullable SolegramNowPlaying.Track track, boolean self) {
        final SolegramNowPlaying.Track was = this.track;
        final int heightWas = wantedHeight();
        this.track = track;
        this.self = self;
        final boolean newSong = track == null || was == null || !TextUtils.equals(was.title, track.title) || !TextUtils.equals(was.artist, track.artist);
        if (track == null || track.art != washSource) {
            prepareWash(track != null ? track.art : null);
        }
        if (newSong && was != null && isAttachedToWindow()) {
            // a new song fades in
            if (fadeAnimator != null) {
                fadeAnimator.cancel();
            }
            fadeAnimator = ValueAnimator.ofFloat(0f, 1f);
            fadeAnimator.addUpdateListener(a -> {
                contentAlpha = (float) a.getAnimatedValue();
                invalidate();
            });
            fadeAnimator.setDuration(320);
            fadeAnimator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
            fadeAnimator.start();
        }
        updateDescription();
        if (wantedHeight() != heightWas || getMeasuredHeight() != wantedHeight()) {
            requestLayout();
        }
        invalidate();
    }

    public SolegramNowPlaying.Track getTrack() {
        return track;
    }

    /** What the card says when nothing plays (the preview in the settings). */
    public void setEmptyText(CharSequence text) {
        emptyText = text;
        invalidate();
    }

    /** After the look was changed in the settings. */
    public void styleChanged() {
        titleShown = null;
        artistShown = null;
        requestLayout();
        invalidate();
    }

    private void updateDescription() {
        if (track == null) {
            setContentDescription(emptyText);
            return;
        }
        setContentDescription(LocaleController.getString(self ? R.string.SG_NowPlayingListeningSelf : R.string.SG_NowPlayingListening)
            + ": " + (TextUtils.isEmpty(track.artist) ? "" : track.artist + " — ") + track.title);
    }

    private boolean hasProgress() {
        return self && track != null && !track.remote && track.duration > 0 && SolegramConfig.nowPlayingProgress;
    }

    private boolean playing() {
        return track != null && track.playing;
    }

    /** A blurred copy of the cover tints the card (or is the card, in the glass look), as Apple Music does under its player. */
    private void prepareWash(@Nullable Bitmap art) {
        washSource = art;
        washBitmap = null;
        washShader = null;
        coverShader = null;
        coverShaderBitmap = null;
        if (art == null) {
            return;
        }
        try {
            final Bitmap small = Bitmap.createScaledBitmap(art, 36, 36, true);
            final Bitmap blurred = small.isMutable() ? small : small.copy(Bitmap.Config.ARGB_8888, true);
            Utilities.stackBlurBitmap(blurred, 7);
            washBitmap = blurred;
            washShader = new BitmapShader(blurred, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        } catch (Throwable e) {
            washBitmap = null;
            washShader = null;
        }
    }

    // region layout

    private int wantedHeight() {
        if (style() == STYLE_COMPACT) {
            return dp(COMPACT_CARD + 12);
        }
        int h = TOP_BLOCK;
        if (hasProgress()) {
            h += PROGRESS_BLOCK;
        }
        return dp(h + 14);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(wantedHeight(), MeasureSpec.EXACTLY));
    }

    private float radius() {
        return dp(style() == STYLE_COMPACT ? 18 : 22);
    }

    /** Where everything sits; cheap, done before every frame (the parts depend on the track and the settings). */
    private void layoutParts() {
        final int w = getWidth(), h = getHeight();
        card.set(dp(12), dp(6), w - dp(12), h - dp(6));
        final float size = dp(style() == STYLE_COMPACT ? 44 : 68);
        final float coverTop = style() == STYLE_COMPACT ? card.centerY() - size / 2f : card.top + dp(12);
        cover.set(card.left + dp(style() == STYLE_COMPACT ? 9 : 12), coverTop, card.left + dp(style() == STYLE_COMPACT ? 9 : 12) + size, coverTop + size);
        selector.setBounds((int) card.left, (int) card.top, (int) card.right, (int) card.bottom);
    }
    // endregion

    // region colours

    private int color(int key) {
        return Theme.getColor(key, resourcesProvider);
    }

    /** The player's own colour. */
    private int sourceColor() {
        if (track == null) {
            return color(Theme.key_windowBackgroundWhiteGrayText);
        }
        switch (track.source) {
            case SolegramNowPlaying.SOURCE_SPOTIFY:
                return SPOTIFY_GREEN;
            case SolegramNowPlaying.SOURCE_SOUNDCLOUD:
                return SOUNDCLOUD_ORANGE;
            default:
                return color(Theme.key_windowBackgroundWhiteBlueText4);
        }
    }

    /** The colour of the marks, the equalizer and the progress, as chosen in the settings. */
    private int accent(boolean onGlass) {
        if (track == null) {
            return color(Theme.key_windowBackgroundWhiteGrayText);
        }
        int c;
        switch (SolegramConfig.nowPlayingAccent) {
            case 0:
                c = track.color;
                break;
            case 2:
                c = color(Theme.key_featuredStickers_addButton);
                break;
            default:
                c = sourceColor();
                break;
        }
        if (onGlass) {
            // over the dark glass a light tone of it reads best
            return ColorUtils.blendARGB(c, Color.WHITE, 0.35f);
        }
        if (SolegramConfig.nowPlayingAccent == 0) {
            // a cover colour may be pale or dark: keep it readable on the page
            final boolean dark = Theme.isCurrentThemeDark();
            final double l = ColorUtils.calculateLuminance(c | 0xFF000000);
            if (!dark && l > 0.55) {
                c = ColorUtils.blendARGB(c, Color.BLACK, 0.35f);
            } else if (dark && l < 0.12) {
                c = ColorUtils.blendARGB(c, Color.WHITE, 0.4f);
            }
        }
        return c;
    }
    // endregion

    // region drawing

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        if (getWidth() <= 0 || getHeight() <= 0) {
            return;
        }
        layoutParts();
        final int style = style();
        final boolean glass = style == STYLE_GLASS;
        final boolean dark = Theme.isCurrentThemeDark();
        final int base = color(Theme.key_windowBackgroundWhite);
        final int tint = track != null ? track.color : color(Theme.key_windowBackgroundWhiteGrayText);
        final float radius = radius();
        clip.rewind();
        clip.addRoundRect(card, radius, radius, Path.Direction.CW);

        if (glass && track != null) {
            drawGlassCard(canvas, tint, radius);
        } else {
            drawWashedCard(canvas, base, tint, dark, radius);
        }

        final int alpha = (int) (255 * contentAlpha);
        boolean animate = drawCover(canvas, tint, alpha, style);

        final float textLeft = cover.right + dp(style == STYLE_COMPACT ? 12 : 14);
        final float textRight = card.right - dp(14);
        final int textColor = glass && track != null ? Color.WHITE : color(Theme.key_windowBackgroundWhiteBlackText);
        final int grayColor = glass && track != null ? 0xB8FFFFFF : color(Theme.key_windowBackgroundWhiteGrayText);
        final int accent = accent(glass && track != null);

        statusPaint.setTextSize(dp(12.5f));
        titlePaint.setTextSize(dp(style == STYLE_COMPACT ? 15 : 16.5f));
        artistPaint.setTextSize(dp(style == STYLE_COMPACT ? 13 : 14));

        if (track == null) {
            final float textWidth = Math.max(0, textRight - textLeft);
            artistPaint.setColor(grayColor);
            final CharSequence empty = emptyText == null ? "" : ellipsizeArtist(emptyText, textWidth);
            canvas.drawText(empty, 0, empty.length(), textLeft, cover.centerY() + dp(5), artistPaint);
            selector.draw(canvas);
            return;
        }

        final float rise = dp(8) * (1f - contentAlpha);
        if (style == STYLE_COMPACT) {
            // one slim line: the equalizer at the end of the title, the artist under it
            float eqWidth = 0;
            if (SolegramConfig.nowPlayingEqualizer) {
                eqWidth = dp(3 * 2.6f + 2 * 2.2f) + dp(8);
            }
            final float textWidth = Math.max(0, textRight - textLeft - eqWidth);
            titlePaint.setColor(textColor);
            titlePaint.setAlpha(alpha);
            final CharSequence title = ellipsizeTitle(track.title, textWidth);
            final float titleBaseline = card.centerY() - dp(2) + rise;
            canvas.drawText(title, 0, title.length(), textLeft, titleBaseline, titlePaint);
            if (SolegramConfig.nowPlayingEqualizer) {
                animate |= drawEqualizer(canvas, textLeft + titlePaint.measureText(title, 0, title.length()) + dp(8), titleBaseline - dp(1), alpha, accent, dp(9));
            }
            artistPaint.setColor(grayColor);
            artistPaint.setAlpha(alpha);
            final CharSequence artist = ellipsizeArtist(TextUtils.isEmpty(track.artist) ? sourceLine() : track.artist, Math.max(0, textRight - textLeft));
            canvas.drawText(artist, 0, artist.length(), textLeft, card.centerY() + dp(16) + rise * 1.4f, artistPaint);
            if (hasProgress()) {
                // a hairline of progress along the card's bottom edge
                final float actual = progress();
                canvas.save();
                canvas.clipPath(clip);
                progressPaint.setStrokeCap(Paint.Cap.BUTT);
                progressPaint.setStrokeWidth(dp(2.5f));
                progressPaint.setColor(accent);
                progressPaint.setAlpha((int) (alpha * 0.9f));
                final float y = card.bottom - dp(1.25f);
                canvas.drawLine(card.left, y, card.left + card.width() * actual, y, progressPaint);
                canvas.restore();
                progressPaint.setStrokeCap(Paint.Cap.ROUND);
                animate |= track.playing;
            }
        } else {
            final float textWidth = Math.max(0, textRight - textLeft);
            // status line: the player's mark, "Listening now", the equalizer
            float x = textLeft;
            final float statusBaseline = cover.top + dp(15);
            drawMark(canvas, x + dp(7), statusBaseline - dp(4.5f), dp(7), alpha, glass);
            x += dp(14) + dp(6);
            final String status = LocaleController.getString(!playing() ? R.string.SG_NowPlayingPaused
                : self ? R.string.SG_NowPlayingListeningSelf : R.string.SG_NowPlayingListening);
            statusPaint.setColor(glass ? 0xD9FFFFFF : ColorUtils.blendARGB(accent, textColor, dark ? 0.1f : 0.2f));
            statusPaint.setAlpha((int) (Color.alpha(statusPaint.getColor()) * contentAlpha));
            canvas.drawText(status, x, statusBaseline, statusPaint);
            x += statusPaint.measureText(status) + dp(7);
            if (SolegramConfig.nowPlayingEqualizer) {
                animate |= drawEqualizer(canvas, x, statusBaseline, alpha, glass ? Color.WHITE : accent, dp(11));
            }

            titlePaint.setColor(textColor);
            titlePaint.setAlpha(alpha);
            final CharSequence title = ellipsizeTitle(track.title, textWidth);
            canvas.drawText(title, 0, title.length(), textLeft, cover.top + dp(41) + rise, titlePaint);

            artistPaint.setColor(grayColor);
            artistPaint.setAlpha(alpha);
            final CharSequence artist = ellipsizeArtist(TextUtils.isEmpty(track.artist) ? sourceLine() : track.artist, textWidth);
            canvas.drawText(artist, 0, artist.length(), textLeft, cover.top + dp(61) + rise * 1.4f, artistPaint);

            if (hasProgress()) {
                animate |= drawProgress(canvas, accent, textColor, grayColor, glass, alpha);
            }
        }

        selector.draw(canvas);
        if (animate) {
            postInvalidateOnAnimation();
        }
    }

    private String sourceLine() {
        return track != null && track.source != SolegramNowPlaying.SOURCE_NONE ? SolegramNowPlaying.sourceName(track.source) : "";
    }

    /** The page colour washed with the cover's colours. */
    private void drawWashedCard(Canvas canvas, int base, int tint, boolean dark, float radius) {
        fillPaint.setColor(ColorUtils.blendARGB(base, tint, dark ? 0.24f : 0.13f));
        canvas.drawRoundRect(card, radius, radius, fillPaint);
        if (washShader != null && washBitmap != null) {
            final float s = Math.max(card.width() / washBitmap.getWidth(), card.height() / washBitmap.getHeight());
            washMatrix.setScale(s, s);
            washMatrix.postTranslate(card.centerX() - washBitmap.getWidth() * s / 2f, card.centerY() - washBitmap.getHeight() * s / 2f);
            washShader.setLocalMatrix(washMatrix);
            washPaint.setShader(washShader);
            washPaint.setAlpha((int) (255 * (dark ? 0.42f : 0.30f)));
            canvas.save();
            canvas.clipPath(clip);
            canvas.drawRect(card, washPaint);
            // the left side keeps the colour under the cover, the text side clears for reading
            if (washFade == null || washFadeLeft != card.left || washFadeRight != card.right || washFadeBase != base || washFadeDark != dark) {
                washFadeLeft = card.left;
                washFadeRight = card.right;
                washFadeBase = base;
                washFadeDark = dark;
                washFade = new LinearGradient(card.left, 0, card.right, 0,
                    ColorUtils.setAlphaComponent(base, dark ? 0x10 : 0x30), ColorUtils.setAlphaComponent(base, dark ? 0x60 : 0x90), Shader.TileMode.CLAMP);
            }
            fillPaint.setShader(washFade);
            canvas.drawRect(card, fillPaint);
            fillPaint.setShader(null);
            canvas.restore();
        }
        // the rim, lit from above like the app's glass: bright along the top, fading down the sides
        if (rimShader == null || rimTop != card.top || rimBottom != card.bottom || rimDark != dark) {
            rimTop = card.top;
            rimBottom = card.bottom;
            rimDark = dark;
            rimShader = new LinearGradient(0, card.top, 0, card.bottom,
                new int[]{dark ? 0x3DFFFFFF : 0xB3FFFFFF, dark ? 0x14FFFFFF : 0x1A000000, dark ? 0x0AFFFFFF : 0x14000000},
                new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP);
        }
        strokePaint.setColor(Color.WHITE);
        strokePaint.setShader(rimShader);
        tmp.set(card);
        tmp.inset(strokePaint.getStrokeWidth() / 2f, strokePaint.getStrokeWidth() / 2f);
        canvas.drawRoundRect(tmp, radius, radius, strokePaint);
        strokePaint.setShader(null);
    }

    /** Frosted glass made of the cover: the blurred cover fills the card, darkened for white text, with a light rim. */
    private void drawGlassCard(Canvas canvas, int tint, float radius) {
        canvas.save();
        canvas.clipPath(clip);
        if (washShader != null && washBitmap != null) {
            final float s = Math.max(card.width() / washBitmap.getWidth(), card.height() / washBitmap.getHeight()) * 1.15f;
            washMatrix.setScale(s, s);
            washMatrix.postTranslate(card.centerX() - washBitmap.getWidth() * s / 2f, card.centerY() - washBitmap.getHeight() * s / 2f);
            washShader.setLocalMatrix(washMatrix);
            washPaint.setShader(washShader);
            washPaint.setAlpha(255);
            canvas.drawRect(card, washPaint);
        } else {
            if (glassFill == null || glassFillTint != tint || glassFillLeft != card.left || glassFillTop != card.top) {
                glassFillTint = tint;
                glassFillLeft = card.left;
                glassFillTop = card.top;
                glassFill = new LinearGradient(card.left, card.top, card.right, card.bottom,
                    ColorUtils.blendARGB(tint, Color.WHITE, 0.1f), ColorUtils.blendARGB(tint, Color.BLACK, 0.45f), Shader.TileMode.CLAMP);
            }
            fillPaint.setShader(glassFill);
            canvas.drawRect(card, fillPaint);
            fillPaint.setShader(null);
        }
        // darker towards the bottom, where the buttons and the time are
        if (glassScrim == null || glassScrimTop != card.top || glassScrimBottom != card.bottom) {
            glassScrimTop = card.top;
            glassScrimBottom = card.bottom;
            glassScrim = new LinearGradient(0, card.top, 0, card.bottom, 0x47000000, 0x8C000000, Shader.TileMode.CLAMP);
        }
        fillPaint.setShader(glassScrim);
        canvas.drawRect(card, fillPaint);
        fillPaint.setShader(null);
        canvas.restore();
        // the rim: light along the top, fading down
        strokePaint.setColor(0x38FFFFFF);
        tmp.set(card);
        tmp.inset(strokePaint.getStrokeWidth() / 2f, strokePaint.getStrokeWidth() / 2f);
        canvas.drawRoundRect(tmp, radius, radius, strokePaint);
    }

    /** @return true while the record turns */
    private boolean drawCover(Canvas canvas, int tint, int alpha, int style) {
        final boolean vinyl = SolegramConfig.nowPlayingVinyl && track != null;
        final float r = vinyl ? cover.width() / 2f : dp(style == STYLE_COMPACT ? 11 : 15);
        // a glow of the cover's colour under it, then the soft shadow that lifts it
        if (track != null && style != STYLE_COMPACT) {
            shadowPaint.setColor(0);
            shadowPaint.setShadowLayer(dp(16), 0, dp(6), ColorUtils.setAlphaComponent(tint, (int) (0x66 * contentAlpha)));
            canvas.drawRoundRect(cover, r, r, shadowPaint);
        }
        shadowPaint.setColor(0);
        shadowPaint.setShadowLayer(dp(style == STYLE_COMPACT ? 6 : 8), 0, dp(2.5f), ColorUtils.setAlphaComponent(ColorUtils.blendARGB(tint, Color.BLACK, 0.55f), (int) (0x50 * contentAlpha)));
        canvas.drawRoundRect(cover, r, r, shadowPaint);

        boolean turning = false;
        canvas.save();
        if (vinyl) {
            final long now = SystemClock.elapsedRealtime();
            if (playing() && vinylTime != 0) {
                vinylAngle = (vinylAngle + (now - vinylTime) * 360f / 7000f) % 360f; // a turn in seven seconds
                turning = true;
            }
            vinylTime = playing() ? now : 0;
            canvas.rotate(vinylAngle, cover.centerX(), cover.centerY());
        }
        if (track != null && track.art != null) {
            if (coverShader == null || coverShaderBitmap != track.art) {
                coverShaderBitmap = track.art;
                coverShader = new BitmapShader(track.art, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            }
            final float s = Math.max(cover.width() / track.art.getWidth(), cover.height() / track.art.getHeight());
            coverMatrix.setScale(s, s);
            coverMatrix.postTranslate(cover.centerX() - track.art.getWidth() * s / 2f, cover.centerY() - track.art.getHeight() * s / 2f);
            coverShader.setLocalMatrix(coverMatrix);
            coverPaint.setShader(coverShader);
            coverPaint.setAlpha(alpha);
            canvas.drawRoundRect(cover, r, r, coverPaint);
            coverPaint.setShader(null);
        } else {
            // no cover (someone else's track, or the player gives none): a cover of the track's colour
            if (coverFill == null || coverFillLeft != cover.left || coverFillTop != cover.top || coverFillTint != tint) {
                coverFillLeft = cover.left;
                coverFillTop = cover.top;
                coverFillTint = tint;
                coverFill = new LinearGradient(cover.left, cover.top, cover.right, cover.bottom,
                    ColorUtils.blendARGB(tint, Color.WHITE, 0.25f), ColorUtils.blendARGB(tint, Color.BLACK, 0.25f), Shader.TileMode.CLAMP);
            }
            coverPaint.setShader(coverFill);
            coverPaint.setAlpha(track == null ? 0x80 : alpha);
            canvas.drawRoundRect(cover, r, r, coverPaint);
            coverPaint.setShader(null);
            if (noteDrawable == null) {
                noteDrawable = ContextCompat.getDrawable(getContext(), R.drawable.files_music);
                if (noteDrawable != null) {
                    noteDrawable = noteDrawable.mutate();
                    noteDrawable.setColorFilter(new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN));
                }
            }
            if (noteDrawable != null && !vinyl) {
                final int half = dp(style == STYLE_COMPACT ? 11 : 15);
                noteDrawable.setBounds((int) cover.centerX() - half, (int) cover.centerY() - half, (int) cover.centerX() + half, (int) cover.centerY() + half);
                noteDrawable.setAlpha((int) (0xE6 * contentAlpha));
                noteDrawable.draw(canvas);
            }
        }
        if (vinyl) {
            drawGrooves(canvas, alpha);
        }
        canvas.restore();
        strokePaint.setColor(0x1A000000);
        canvas.drawRoundRect(cover, r, r, strokePaint);
        return turning;
    }

    /** The record's grooves, its shine and the hole in the middle. */
    private void drawGrooves(Canvas canvas, int alpha) {
        final float cx = cover.centerX(), cy = cover.centerY(), R = cover.width() / 2f;
        markPaint.setStyle(Paint.Style.STROKE);
        markPaint.setStrokeWidth(Math.max(1, dpf2(0.6f)));
        for (int i = 0; i < 4; i++) {
            markPaint.setColor(Color.BLACK);
            markPaint.setAlpha((int) (alpha * (i % 2 == 0 ? 0.16f : 0.09f)));
            canvas.drawCircle(cx, cy, R * (0.52f + i * 0.12f), markPaint);
        }
        // a shine across, which turns with the record
        markPaint.setStrokeWidth(R * 0.16f);
        markPaint.setColor(Color.WHITE);
        markPaint.setAlpha((int) (alpha * 0.12f));
        tmp.set(cx - R * 0.72f, cy - R * 0.72f, cx + R * 0.72f, cy + R * 0.72f);
        canvas.drawArc(tmp, 200, 50, false, markPaint);
        canvas.drawArc(tmp, 20, 50, false, markPaint);
        markPaint.setStyle(Paint.Style.FILL);
        // the label's edge and the hole
        markPaint.setColor(0xFF161616);
        markPaint.setAlpha(alpha);
        canvas.drawCircle(cx, cy, R * 0.15f, markPaint);
        markPaint.setColor(color(Theme.key_windowBackgroundWhite));
        markPaint.setAlpha(alpha);
        canvas.drawCircle(cx, cy, R * 0.06f, markPaint);
    }

    private float progress() {
        if (track == null || track.duration <= 0) {
            return 0f;
        }
        final float actual = Math.max(0f, Math.min(1f, track.currentPosition() / (float) track.duration));
        return shownProgress.set(actual, Math.abs(actual - shownProgress.get()) < 0.02f);
    }

    /** The bar across the card with the time gone on the left and the time left on the right. */
    private boolean drawProgress(Canvas canvas, int accent, int textColor, int grayColor, boolean glass, int alpha) {
        final float left = card.left + dp(16), right = card.right - dp(16);
        final float y = card.top + dp(TOP_BLOCK) + dp(4);
        final float progress = progress();
        progressPaint.setStrokeWidth(dp(4));
        progressPaint.setColor(glass ? 0x40FFFFFF : ColorUtils.setAlphaComponent(textColor, Theme.isCurrentThemeDark() ? 0x30 : 0x1E));
        progressPaint.setAlpha((int) (Color.alpha(progressPaint.getColor()) * contentAlpha));
        canvas.drawLine(left, y, right, y, progressPaint);
        if (progress > 0) {
            progressPaint.setColor(glass ? Color.WHITE : accent);
            progressPaint.setAlpha(alpha);
            canvas.drawLine(left, y, left + Math.max(dp(1), (right - left) * progress), y, progressPaint);
        }
        final long position = track.currentPosition();
        final long key = (position / 1000) * 1_000_000L + track.duration / 1000;
        if (key != timeKey || elapsedText == null) {
            timeKey = key;
            elapsedText = format(position);
            remainingText = "-" + format(Math.max(0, track.duration - position));
        }
        timePaint.setColor(grayColor);
        timePaint.setAlpha((int) (Color.alpha(grayColor) * contentAlpha));
        final float ty = y + dp(17);
        canvas.drawText(elapsedText, left, ty, timePaint);
        canvas.drawText(remainingText, right - timePaint.measureText(remainingText), ty, timePaint);
        return track.playing;
    }

    // endregion

    /** Spotify's three arcs, SoundCloud's cloud, or a note for a track from someone's bio. */
    private void drawMark(Canvas canvas, float cx, float cy, float r, int alpha, boolean glass) {
        markPaint.setStyle(Paint.Style.FILL);
        markPaint.setColor(glass ? Color.WHITE : sourceColor());
        markPaint.setAlpha(alpha);
        canvas.drawCircle(cx, cy, r, markPaint);
        markPaint.setColor(glass ? sourceColor() : Color.WHITE);
        markPaint.setAlpha(alpha);
        if (track != null && track.source == SolegramNowPlaying.SOURCE_SPOTIFY) {
            markPaint.setStyle(Paint.Style.STROKE);
            markPaint.setStrokeCap(Paint.Cap.ROUND);
            final Path p = markPath;
            for (int i = 0; i < 3; i++) {
                final float w = r * (1.18f - i * 0.22f);
                final float y = cy - r * 0.32f + i * r * 0.31f;
                markPaint.setStrokeWidth(r * (0.22f - i * 0.03f));
                p.rewind();
                p.moveTo(cx - w / 2f, y);
                p.quadTo(cx, y - r * 0.24f, cx + w / 2f, y + r * 0.08f);
                canvas.drawPath(p, markPaint);
            }
        } else if (track != null && track.source == SolegramNowPlaying.SOURCE_SOUNDCLOUD) {
            markPaint.setStyle(Paint.Style.FILL);
            // the cloud
            canvas.drawCircle(cx + r * 0.12f, cy - r * 0.02f, r * 0.34f, markPaint);
            canvas.drawCircle(cx + r * 0.44f, cy + r * 0.14f, r * 0.22f, markPaint);
            tmp.set(cx - r * 0.12f, cy + r * 0.02f, cx + r * 0.66f, cy + r * 0.36f);
            canvas.drawRoundRect(tmp, r * 0.17f, r * 0.17f, markPaint);
            // the bars before it
            for (int i = 0; i < 3; i++) {
                final float bx = cx - r * 0.62f + i * r * 0.17f;
                final float top = cy + r * 0.36f - r * (0.22f + i * 0.1f);
                tmp.set(bx, top, bx + r * 0.09f, cy + r * 0.36f);
                canvas.drawRoundRect(tmp, r * 0.05f, r * 0.05f, markPaint);
            }
        } else {
            markPaint.setStyle(Paint.Style.FILL);
            // a quaver
            canvas.drawCircle(cx - r * 0.18f, cy + r * 0.28f, r * 0.24f, markPaint);
            tmp.set(cx - r * 0.0f, cy - r * 0.48f, cx + r * 0.1f, cy + r * 0.3f);
            canvas.drawRect(tmp, markPaint);
            tmp.set(cx, cy - r * 0.48f, cx + r * 0.38f, cy - r * 0.26f);
            canvas.drawRoundRect(tmp, r * 0.08f, r * 0.08f, markPaint);
        }
        markPaint.setStyle(Paint.Style.FILL);
    }

    /** Three bars dancing while the music plays, resting low when paused. */
    private boolean drawEqualizer(Canvas canvas, float x, float baseline, int alpha, int color, float max) {
        if (track == null) {
            return false;
        }
        final boolean playing = playing();
        final float t = SystemClock.elapsedRealtime() / 1000f;
        final float w = dp(2.6f), gap = dp(2.2f);
        barPaint.setColor(color);
        barPaint.setAlpha(alpha);
        for (int i = 0; i < 3; i++) {
            final float level = playing ? 0.3f + 0.7f * Math.abs((float) Math.sin(t * (5.2f + i * 1.7f) + i * 1.3f)) : 0.25f;
            final float h = max * level;
            final float bx = x + i * (w + gap);
            tmp.set(bx, baseline - h, bx + w, baseline);
            canvas.drawRoundRect(tmp, w / 2f, w / 2f, barPaint);
        }
        return playing;
    }

    private CharSequence ellipsizeTitle(CharSequence text, float width) {
        if (titleShown == null || titleSource != text || titleWidth != width) {
            titleSource = text;
            titleWidth = width;
            titleShown = TextUtils.ellipsize(text, titlePaint, width, TextUtils.TruncateAt.END);
        }
        return titleShown;
    }

    /** The artist line (or the empty card's text), ellipsized again only when the text or the width changes. */
    private CharSequence ellipsizeArtist(CharSequence text, float width) {
        if (artistShown == null || artistSource != text || artistWidth != width) {
            artistSource = text;
            artistWidth = width;
            artistShown = TextUtils.ellipsize(text, artistPaint, width, TextUtils.TruncateAt.END);
        }
        return artistShown;
    }

    private static String format(long ms) {
        final long s = ms / 1000;
        return String.format(java.util.Locale.US, "%d:%02d", s / 60, s % 60);
    }

    // region touches and the press highlight

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            layoutParts();
            if (!card.contains(event.getX(), event.getY())) {
                return false;
            }
        }
        selector.setHotspot(event.getX(), event.getY());
        return super.onTouchEvent(event);
    }

    @Override
    protected boolean verifyDrawable(@NonNull Drawable who) {
        return who == selector || super.verifyDrawable(who);
    }

    @Override
    protected void drawableStateChanged() {
        super.drawableStateChanged();
        selector.setState(getDrawableState());
    }

    @Override
    public void jumpDrawablesToCurrentState() {
        super.jumpDrawablesToCurrentState();
        selector.jumpToCurrentState();
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClickable(isClickable());
    }
    // endregion
}
