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

import com.solegram.messenger.SolegramNowPlaying;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.CubicBezierInterpolator;

/**
 * Music in the profile: the card with what someone listens to now. The cover with a soft shadow, the card
 * washed with the cover's colours (a blurred copy of it), the player's mark, a little equalizer while the
 * music plays and, for the own card, the progress of the song.
 */
public class SolegramNowPlayingView extends View {

    private static final int SPOTIFY_GREEN = 0xFF1DB954;
    private static final int SOUNDCLOUD_ORANGE = 0xFFFF5500;

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
    // the card redraws every frame while music plays (equalizer, progress): what each frame would otherwise
    // allocate again is kept until its inputs change
    private final Path markPath = new Path();
    private LinearGradient washFade, coverFill;
    private float washFadeLeft, washFadeRight, coverFillLeft, coverFillTop;
    private int washFadeBase, coverFillTint;
    private boolean washFadeDark;
    private String timeText;
    private long timeKey = -1;
    private CharSequence titleSource, titleShown, artistSource, artistShown;
    private float titleWidth = -1, artistWidth = -1;
    private float contentAlpha = 1f;
    private ValueAnimator fadeAnimator;
    // the progress line glides to a new place after a seek, and follows the song exactly while it plays
    private final org.telegram.ui.Components.AnimatedFloat shownProgress = new org.telegram.ui.Components.AnimatedFloat(this, 0, 380, CubicBezierInterpolator.EASE_OUT_QUINT);

    public SolegramNowPlayingView(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        statusPaint.setTextSize(dp(12.5f));
        statusPaint.setTypeface(AndroidUtilities.bold());
        titlePaint.setTextSize(dp(16));
        titlePaint.setTypeface(AndroidUtilities.bold());
        artistPaint.setTextSize(dp(14));
        timePaint.setTextSize(dp(11.5f));
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(Math.max(1, dpf2(0.66f)));
        barPaint.setStyle(Paint.Style.FILL);
        progressPaint.setStrokeCap(Paint.Cap.ROUND);
        progressPaint.setStrokeWidth(dp(3));
        selector = Theme.createRadSelectorDrawable(Theme.getColor(Theme.key_listSelector, resourcesProvider), 20, 20);
        selector.setCallback(this);
    }

    /** @param self the own card (live progress, a cover from the player) */
    public void setTrack(@Nullable SolegramNowPlaying.Track track, boolean self) {
        final SolegramNowPlaying.Track was = this.track;
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

    private void updateDescription() {
        if (track == null) {
            setContentDescription(emptyText);
            return;
        }
        setContentDescription(LocaleController.getString(self ? R.string.SG_NowPlayingListeningSelf : R.string.SG_NowPlayingListening)
            + ": " + (TextUtils.isEmpty(track.artist) ? "" : track.artist + " — ") + track.title);
    }

    /** A blurred copy of the cover tints the card, as Apple Music does under its player. */
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

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(dp(98), MeasureSpec.EXACTLY));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        card.set(dp(12), dp(7), w - dp(12), h - dp(7));
        clip.rewind();
        clip.addRoundRect(card, dp(20), dp(20), Path.Direction.CW);
        final float size = dp(64);
        cover.set(card.left + dp(10), card.centerY() - size / 2f, card.left + dp(10) + size, card.centerY() + size / 2f);
        selector.setBounds((int) card.left, (int) card.top, (int) card.right, (int) card.bottom);
    }

    private int color(int key) {
        return Theme.getColor(key, resourcesProvider);
    }

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

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        final boolean dark = Theme.isCurrentThemeDark();
        final int base = color(Theme.key_windowBackgroundWhite);
        final int tint = track != null ? track.color : color(Theme.key_windowBackgroundWhiteGrayText);

        // the card: the page colour washed with the cover's colours
        fillPaint.setColor(ColorUtils.blendARGB(base, tint, dark ? 0.26f : 0.14f));
        canvas.drawRoundRect(card, dp(20), dp(20), fillPaint);
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
            // the left side stays calmer under the cover, the text side clear for reading
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
        strokePaint.setColor(dark ? 0x1FFFFFFF : 0x14000000);
        tmp.set(card);
        tmp.inset(strokePaint.getStrokeWidth() / 2f, strokePaint.getStrokeWidth() / 2f);
        canvas.drawRoundRect(tmp, dp(20), dp(20), strokePaint);

        final int alpha = (int) (255 * contentAlpha);
        drawCover(canvas, tint, alpha);

        final float textLeft = cover.right + dp(14);
        final float textRight = card.right - dp(14);
        final float textWidth = Math.max(0, textRight - textLeft);
        final int textColor = color(Theme.key_windowBackgroundWhiteBlackText);
        final int grayColor = color(Theme.key_windowBackgroundWhiteGrayText);

        if (track == null) {
            artistPaint.setColor(grayColor);
            final CharSequence empty = emptyText == null ? "" : ellipsizeArtist(emptyText, textWidth);
            canvas.drawText(empty, 0, empty.length(), textLeft, card.centerY() + dp(5), artistPaint);
            selector.draw(canvas);
            return;
        }

        // status line: the player's mark, "Listening now", the equalizer; the time on the right
        float x = textLeft;
        final float statusBaseline = card.top + dp(24);
        drawMark(canvas, x + dp(7), statusBaseline - dp(4.5f), dp(7), alpha);
        x += dp(14) + dp(6);
        final String status = LocaleController.getString(!track.playing ? R.string.SG_NowPlayingPaused
            : self ? R.string.SG_NowPlayingListeningSelf : R.string.SG_NowPlayingListening);
        statusPaint.setColor(track.source == SolegramNowPlaying.SOURCE_NONE ? sourceColor() : ColorUtils.blendARGB(sourceColor(), textColor, dark ? 0.1f : 0.25f));
        statusPaint.setAlpha(alpha);
        canvas.drawText(status, x, statusBaseline, statusPaint);
        x += statusPaint.measureText(status) + dp(7);
        final boolean animate = drawEqualizer(canvas, x, statusBaseline, alpha);

        final boolean showProgress = self && !track.remote && track.duration > 0;
        if (showProgress) {
            final long position = track.currentPosition();
            final long key = (position / 1000) * 1_000_000L + track.duration / 1000;
            if (key != timeKey || timeText == null) {
                timeKey = key;
                timeText = format(position) + " / " + format(track.duration);
            }
            final String time = timeText;
            timePaint.setColor(grayColor);
            timePaint.setAlpha(alpha);
            canvas.drawText(time, textRight - timePaint.measureText(time), statusBaseline, timePaint);
        }

        titlePaint.setColor(textColor);
        titlePaint.setAlpha(alpha);
        if (titleShown == null || titleSource != track.title || titleWidth != textWidth) {
            titleSource = track.title;
            titleWidth = textWidth;
            titleShown = TextUtils.ellipsize(track.title, titlePaint, textWidth, TextUtils.TruncateAt.END);
        }
        final CharSequence title = titleShown;
        // a new song's lines rise into place as they fade in
        final float rise = dp(8) * (1f - contentAlpha);
        canvas.drawText(title, 0, title.length(), textLeft, card.top + dp(showProgress ? 46 : 50) + rise, titlePaint);

        if (!TextUtils.isEmpty(track.artist)) {
            artistPaint.setColor(grayColor);
            artistPaint.setAlpha(alpha);
            final CharSequence artist = ellipsizeArtist(track.artist, textWidth);
            canvas.drawText(artist, 0, artist.length(), textLeft, card.top + dp(showProgress ? 65 : 70) + rise * 1.4f, artistPaint);
        }

        if (showProgress) {
            final float y = card.bottom - dp(9);
            final float actual = Math.max(0f, Math.min(1f, track.currentPosition() / (float) track.duration));
            final float progress = shownProgress.set(actual, Math.abs(actual - shownProgress.get()) < 0.02f);
            progressPaint.setColor(ColorUtils.setAlphaComponent(textColor, dark ? 0x30 : 0x22));
            progressPaint.setAlpha((int) (Color.alpha(progressPaint.getColor()) * contentAlpha));
            canvas.drawLine(textLeft, y, textRight, y, progressPaint);
            if (progress > 0) {
                progressPaint.setColor(sourceColor());
                progressPaint.setAlpha(alpha);
                canvas.drawLine(textLeft, y, textLeft + Math.max(dp(1), textWidth * progress), y, progressPaint);
            }
        }

        selector.draw(canvas);
        if (animate || showProgress && track.playing) {
            postInvalidateOnAnimation();
        }
    }

    private void drawCover(Canvas canvas, int tint, int alpha) {
        final float r = dp(14);
        // the soft shadow under the cover
        shadowPaint.setColor(0);
        shadowPaint.setShadowLayer(dp(8), 0, dp(3), ColorUtils.setAlphaComponent(ColorUtils.blendARGB(tint, Color.BLACK, 0.5f), (int) (0x55 * contentAlpha)));
        canvas.drawRoundRect(cover, r, r, shadowPaint);
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
            if (noteDrawable != null) {
                final int half = dp(15);
                noteDrawable.setBounds((int) cover.centerX() - half, (int) cover.centerY() - half, (int) cover.centerX() + half, (int) cover.centerY() + half);
                noteDrawable.setAlpha((int) (0xE6 * contentAlpha));
                noteDrawable.draw(canvas);
            }
        }
        strokePaint.setColor(0x1A000000);
        canvas.drawRoundRect(cover, r, r, strokePaint);
    }

    /** Spotify's three arcs, SoundCloud's cloud, or a note for a track from someone's bio. */
    private void drawMark(Canvas canvas, float cx, float cy, float r, int alpha) {
        markPaint.setStyle(Paint.Style.FILL);
        markPaint.setColor(sourceColor());
        markPaint.setAlpha(alpha);
        canvas.drawCircle(cx, cy, r, markPaint);
        markPaint.setColor(Color.WHITE);
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
    private boolean drawEqualizer(Canvas canvas, float x, float baseline, int alpha) {
        if (track == null) {
            return false;
        }
        final boolean playing = track.playing;
        final float t = SystemClock.elapsedRealtime() / 1000f;
        final float w = dp(2.6f), gap = dp(2.2f), max = dp(11);
        barPaint.setColor(sourceColor());
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
        if (event.getAction() == MotionEvent.ACTION_DOWN && !card.contains(event.getX(), event.getY())) {
            return false;
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
