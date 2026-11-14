/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.graphics.ColorUtils;

import com.solegram.messenger.SolegramConfig;
import com.solegram.messenger.SolegramLyrics;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.ImageReceiver;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.audioinfo.AudioInfo;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AudioPlayerAlert;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.PlayPauseDrawable;

import java.util.ArrayList;
import java.util.List;

/**
 * Solegram's music player: the whole screen in the colours of the cover, the cover (square, round or a spinning
 * record), a comet progress line, synced lyrics from LRCLIB, the queue, and its look set in its own menu.
 */
public class SolegramPlayer extends Dialog implements NotificationCenter.NotificationCenterDelegate {

    // ------------------------------------------------------------------ entry

    /** Opens the player: Solegram's, or Telegram's when that one is chosen in the settings. */
    public static void open(Context context, Theme.ResourcesProvider rp) {
        if (context == null) {
            return;
        }
        if (!SolegramConfig.newPlayer || !(context instanceof Activity) || MediaController.getInstance().getPlayingMessageObject() == null) {
            new AudioPlayerAlert(context, rp).show();
            return;
        }
        new SolegramPlayer((Activity) context).show();
    }

    // ------------------------------------------------------------------ look

    public static final int BG_COVER = 0, BG_GRADIENT = 1, BG_DARK = 2;
    public static final int COVER_SQUARE = 0, COVER_ROUND = 1, COVER_VINYL = 2;

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("solegram_player", Context.MODE_PRIVATE);
    }

    private int bgStyle = prefs().getInt("bg", BG_COVER);
    private int coverStyle = prefs().getInt("cover", COVER_SQUARE);
    private int progressStyle = prefs().getInt("progress", SolegramCometProgress.STYLE_COMET);
    private boolean lyricsFirst = prefs().getBoolean("lyricsFirst", false);

    private void saveLook() {
        prefs().edit().putInt("bg", bgStyle).putInt("cover", coverStyle).putInt("progress", progressStyle).putBoolean("lyricsFirst", lyricsFirst).apply();
    }

    // ------------------------------------------------------------------ state

    private final Activity activity;
    private final int account;
    private MessageObject current;
    private Bitmap coverBitmap, blurred;
    private int tint = 0xFF2A2A35, tint2 = 0xFF111118;
    private boolean showingLyrics;
    private SolegramLyrics.Lyrics lyrics;
    private String lyricsFor;

    private FrameLayout root;
    private BackgroundView background;
    private LinearLayout content;
    private CoverView cover;
    private LyricsView lyricsView;
    private TextView titleView, artistView, timeLeft, timeRight, sourceView;
    private SolegramCometProgress progress;
    private ImageView playButton, prevButton, nextButton, shuffleButton, repeatButton, lyricsButton, queueButton;
    private PlayPauseDrawable playDrawable;

    private SolegramPlayer(Activity activity) {
        super(activity, R.style.TransparentDialogNoAnimation);
        this.activity = activity;
        final MessageObject mo = MediaController.getInstance().getPlayingMessageObject();
        this.account = mo != null ? mo.currentAccount : UserConfig.selectedAccount;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final Window w = getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(0));
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            w.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS | WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setStatusBarColor(0);
            w.setNavigationBarColor(0);
            AndroidUtilities.setLightStatusBar(w, false);
            AndroidUtilities.setLightNavigationBar(w.getDecorView(), false);
            w.setWindowAnimations(0);
        }
        setContentView(build(activity), new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        showingLyrics = lyricsFirst;
        bindTrack(MediaController.getInstance().getPlayingMessageObject(), false);
        applyLyricsVisibility(false);
        animateIn();
    }

    @Override
    public void show() {
        super.show();
        final NotificationCenter nc = NotificationCenter.getInstance(account);
        nc.addObserver(this, NotificationCenter.messagePlayingProgressDidChanged);
        nc.addObserver(this, NotificationCenter.messagePlayingPlayStateChanged);
        nc.addObserver(this, NotificationCenter.messagePlayingDidStart);
        nc.addObserver(this, NotificationCenter.messagePlayingDidReset);
    }

    @Override
    public void dismiss() {
        final NotificationCenter nc = NotificationCenter.getInstance(account);
        nc.removeObserver(this, NotificationCenter.messagePlayingProgressDidChanged);
        nc.removeObserver(this, NotificationCenter.messagePlayingPlayStateChanged);
        nc.removeObserver(this, NotificationCenter.messagePlayingDidStart);
        nc.removeObserver(this, NotificationCenter.messagePlayingDidReset);
        if (cover != null) {
            cover.image.onDetachedFromWindow();
        }
        super.dismiss();
    }

    @Override
    public void onBackPressed() {
        animateOut();
    }

    @Override
    public void didReceivedNotification(int id, int acc, Object... args) {
        final MessageObject mo = MediaController.getInstance().getPlayingMessageObject();
        if (id == NotificationCenter.messagePlayingDidReset) {
            if (mo == null) {
                animateOut();
            }
            return;
        }
        if (id == NotificationCenter.messagePlayingDidStart) {
            bindTrack(mo, true);
            return;
        }
        if (id == NotificationCenter.messagePlayingPlayStateChanged) {
            updatePlayState(true);
            return;
        }
        if (id == NotificationCenter.messagePlayingProgressDidChanged) {
            updateProgress();
        }
    }

    // ------------------------------------------------------------------ building

    @SuppressLint("ClickableViewAccessibility")
    private View build(Context context) {
        root = new FrameLayout(context) {
            // the player drawn through a live source: the glass of the sheets and menus over it shows the player
            // (its cover, its background), with the glass settings of the rest of the app
            private final SolegramLiveGlass.Host glassSource = new SolegramLiveGlass.Host(this, c -> super.draw(c));

            {
                setWillNotDraw(false); // draw() must run for the source to record
            }

            @Override
            public void draw(android.graphics.Canvas canvas) {
                if (!glassSource.draw(canvas)) {
                    super.draw(canvas);
                }
            }

            private float downY, downX;
            private boolean draggingSheet;
            private VelocityTracker velocity;
            private final int slop = ViewConfiguration.get(context).getScaledTouchSlop();

            @Override
            public boolean onInterceptTouchEvent(MotionEvent e) {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    downX = e.getX();
                    downY = e.getY();
                    draggingSheet = false;
                } else if (e.getActionMasked() == MotionEvent.ACTION_MOVE && !draggingSheet) {
                    final float dy = e.getY() - downY, dx = e.getX() - downX;
                    final boolean lyricsAtTop = !showingLyrics || lyricsView == null || !lyricsView.canScrollVertically(-1);
                    if (dy > slop * 1.5f && dy > Math.abs(dx) * 1.4f && lyricsAtTop && (progress == null || !progress.isDragging())) {
                        draggingSheet = true;
                        downY = e.getY();
                        return true;
                    }
                }
                return super.onInterceptTouchEvent(e);
            }

            @Override
            public boolean onTouchEvent(MotionEvent e) {
                if (!draggingSheet) {
                    return super.onTouchEvent(e) || e.getActionMasked() == MotionEvent.ACTION_DOWN;
                }
                if (velocity == null) {
                    velocity = VelocityTracker.obtain();
                }
                velocity.addMovement(e);
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_MOVE:
                        final float dy = Math.max(0, e.getY() - downY);
                        content.setTranslationY(dy);
                        background.setAlpha(1f - Math.min(1f, dy / getHeight()) * 0.6f);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        velocity.computeCurrentVelocity(1000);
                        final float vy = velocity.getYVelocity();
                        velocity.recycle();
                        velocity = null;
                        draggingSheet = false;
                        if (content.getTranslationY() > getHeight() * 0.22f || vy > dp(900)) {
                            animateOut();
                        } else {
                            content.animate().translationY(0).setDuration(260).setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).start();
                            background.animate().alpha(1f).setDuration(260).start();
                        }
                        return true;
                }
                return true;
            }
        };

        background = new BackgroundView(context);
        root.addView(background, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        content = new LinearLayout(context);
        content.setClipChildren(false);
        content.setClipToPadding(false);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), AndroidUtilities.statusBarHeight + dp(6), dp(24), AndroidUtilities.navigationBarHeight + dp(18));
        root.addView(content, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        // top: close, where it plays from, the look menu
        final FrameLayout top = new FrameLayout(context);
        top.setClipChildren(false);
        top.setClipToPadding(false);
        final ImageView close = iconButton(context, R.drawable.arrow_more, 32);
        close.setOnClickListener(v -> animateOut());
        top.addView(close, LayoutHelper.createFrame(44, 44, Gravity.LEFT | Gravity.CENTER_VERTICAL, -10, 0, 0, 0));
        final LinearLayout heading = new LinearLayout(context);
        heading.setOrientation(LinearLayout.VERTICAL);
        heading.setGravity(Gravity.CENTER_HORIZONTAL);
        final TextView nowPlaying = new TextView(context);
        nowPlaying.setText(getString(R.string.SG_PlayerNowPlaying).toUpperCase());
        nowPlaying.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
        nowPlaying.setLetterSpacing(0.08f);
        nowPlaying.setTextColor(0xB3FFFFFF);
        heading.addView(nowPlaying);
        sourceView = new TextView(context);
        sourceView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        sourceView.setTypeface(AndroidUtilities.bold());
        sourceView.setTextColor(Color.WHITE);
        sourceView.setSingleLine();
        sourceView.setEllipsize(TextUtils.TruncateAt.END);
        heading.addView(sourceView);
        top.addView(heading, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 50, 0, 50, 0));
        final ImageView more = iconButton(context, R.drawable.ic_ab_other, 24);
        more.setOnClickListener(v -> {
            SolegramIconMotion.play(more, SolegramIconMotion.DOTS);
            AndroidUtilities.runOnUIThread(this::showLookMenu, SolegramIconMotion.MENU_DELAY);
        });
        top.addView(more, LayoutHelper.createFrame(44, 44, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, -10, 0));
        content.addView(top, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 52));

        // the middle: the cover, or the lyrics over it
        final FrameLayout middle = new FrameLayout(context);
        middle.setClipChildren(false);
        middle.setClipToPadding(false);
        cover = new CoverView(context);
        middle.addView(cover, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.CENTER, 0, 12, 0, 12));
        lyricsView = new LyricsView(context);
        middle.addView(lyricsView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        content.addView(middle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));

        titleView = new TextView(context);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 23);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setTextColor(Color.WHITE);
        titleView.setSingleLine();
        titleView.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        titleView.setMarqueeRepeatLimit(-1);
        titleView.setSelected(true);
        content.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 14, 0, 0));
        artistView = new TextView(context);
        artistView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        artistView.setTextColor(0xB3FFFFFF);
        artistView.setSingleLine();
        artistView.setEllipsize(TextUtils.TruncateAt.END);
        content.addView(artistView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));

        progress = new SolegramCometProgress(context);
        progress.setStyle(progressStyle);
        progress.setDelegate((p, done) -> {
            final MessageObject mo = MediaController.getInstance().getPlayingMessageObject();
            if (mo == null) {
                return;
            }
            timeLeft.setText(AndroidUtilities.formatShortDuration((int) (mo.getDuration() * p)));
            if (done) {
                MediaController.getInstance().seekToProgress(mo, p);
            }
        });
        content.addView(progress, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 36, -10, 14, -10, 0));
        final FrameLayout times = new FrameLayout(context);
        timeLeft = timeText(context);
        timeRight = timeText(context);
        times.addView(timeLeft, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT));
        times.addView(timeRight, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.RIGHT));
        content.addView(times, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // controls
        final LinearLayout controls = new LinearLayout(context);
        controls.setClipChildren(false);
        controls.setClipToPadding(false);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        shuffleButton = iconButton(context, R.drawable.player_new_shuffle, 26);
        prevButton = iconButton(context, R.drawable.sg_player_prev, 40);
        nextButton = iconButton(context, R.drawable.sg_player_next, 40);
        repeatButton = iconButton(context, R.drawable.player_new_repeatall, 26);
        playButton = new ImageView(context);
        playDrawable = new PlayPauseDrawable(28);
        playDrawable.setColor(0xFF111111);
        playButton.setImageDrawable(playDrawable);
        playButton.setScaleType(ImageView.ScaleType.CENTER);
        playButton.setBackground(Theme.createCircleDrawable(dp(72), Color.WHITE));
        controls.addView(shuffleButton, LayoutHelper.createLinear(0, 48, 1f));
        controls.addView(prevButton, LayoutHelper.createLinear(0, 56, 1f));
        controls.addView(playButton, LayoutHelper.createLinear(72, 72, 8, 0, 8, 0));
        controls.addView(nextButton, LayoutHelper.createLinear(0, 56, 1f));
        controls.addView(repeatButton, LayoutHelper.createLinear(0, 48, 1f));
        content.addView(controls, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 80, 0, 10, 0, 0));

        playButton.setOnClickListener(v -> {
            final MessageObject mo = MediaController.getInstance().getPlayingMessageObject();
            if (mo == null) {
                return;
            }
            v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(90).withEndAction(() ->
                v.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(new android.view.animation.OvershootInterpolator(3f)).start()).start();
            if (MediaController.getInstance().isMessagePaused()) {
                MediaController.getInstance().playMessage(mo);
            } else {
                MediaController.getInstance().pauseMessage(mo);
            }
        });
        prevButton.setOnClickListener(v -> {
            nudge(v, -1);
            MediaController.getInstance().playPreviousMessage();
        });
        nextButton.setOnClickListener(v -> {
            nudge(v, 1);
            MediaController.getInstance().playNextMessage();
        });
        shuffleButton.setOnClickListener(v -> {
            SolegramIconMotion.play(v, SolegramIconMotion.POP);
            MediaController.getInstance().setPlaybackOrderType(SharedConfig.shuffleMusic ? 0 : 2);
            updateModes();
        });
        repeatButton.setOnClickListener(v -> {
            SolegramIconMotion.play(v, SolegramIconMotion.SPIN);
            SharedConfig.setRepeatMode(SharedConfig.repeatMode == 0 ? 1 : SharedConfig.repeatMode == 1 ? 2 : 0);
            updateModes();
        });

        // bottom: lyrics and the queue
        final FrameLayout bottom = new FrameLayout(context);
        bottom.setClipChildren(false);
        bottom.setClipToPadding(false);
        lyricsButton = iconButton(context, R.drawable.msg_photo_text_framed, 24);
        lyricsButton.setContentDescription(getString(R.string.SG_PlayerLyrics));
        lyricsButton.setOnClickListener(v -> {
            SolegramIconMotion.play(v, SolegramIconMotion.POP);
            showingLyrics = !showingLyrics;
            applyLyricsVisibility(true);
        });
        bottom.addView(lyricsButton, LayoutHelper.createFrame(44, 44, Gravity.LEFT | Gravity.CENTER_VERTICAL, -10, 0, 0, 0));
        queueButton = iconButton(context, R.drawable.msg_list, 24);
        queueButton.setContentDescription(getString(R.string.SG_PlayerQueue));
        queueButton.setOnClickListener(v -> {
            SolegramIconMotion.play(v, SolegramIconMotion.NUDGE_UP);
            AndroidUtilities.runOnUIThread(this::showQueue, SolegramIconMotion.MENU_DELAY);
        });
        bottom.addView(queueButton, LayoutHelper.createFrame(44, 44, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, -10, 0));
        final ImageView addButton = iconButton(context, R.drawable.msg_add, 24);
        addButton.setContentDescription(getString(R.string.SG_MusicAddTo));
        addButton.setOnClickListener(v -> {
            SolegramIconMotion.play(v, SolegramIconMotion.POP);
            final MessageObject mo = MediaController.getInstance().getPlayingMessageObject();
            AndroidUtilities.runOnUIThread(() -> SolegramMusicActivity.addToPlaylist(org.telegram.ui.LaunchActivity.getLastFragment(), getContext(), mo), SolegramIconMotion.MENU_DELAY);
        });
        bottom.addView(addButton, LayoutHelper.createFrame(44, 44, Gravity.CENTER));
        content.addView(bottom, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 0, 6, 0, 0));

        updateModes();
        return root;
    }

    private ImageView iconButton(Context context, int res, int sizeDp) {
        final ImageView v = new ImageView(context);
        v.setScaleType(ImageView.ScaleType.CENTER);
        final android.graphics.drawable.Drawable d = context.getResources().getDrawable(res).mutate();
        d.setColorFilter(new android.graphics.PorterDuffColorFilter(Color.WHITE, android.graphics.PorterDuff.Mode.SRC_IN));
        final int s = dp(sizeDp);
        final float k = Math.min(1f, s / (float) Math.max(1, Math.max(d.getIntrinsicWidth(), d.getIntrinsicHeight())));
        v.setImageDrawable(d);
        if (d.getIntrinsicWidth() > s) {
            v.setScaleType(ImageView.ScaleType.FIT_CENTER);
            v.setPadding((int) ((dp(44) - s) / 2f), (int) ((dp(44) - s) / 2f), (int) ((dp(44) - s) / 2f), (int) ((dp(44) - s) / 2f));
        }
        v.setBackground(Theme.createSelectorDrawable(0x26FFFFFF, Theme.RIPPLE_MASK_CIRCLE_20DP));
        return v;
    }

    private TextView timeText(Context context) {
        final TextView t = new TextView(context);
        t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        t.setTextColor(0xA6FFFFFF);
        t.setTypeface(AndroidUtilities.getTypeface(AndroidUtilities.TYPEFACE_ROBOTO_MONO));
        return t;
    }

    private static void nudge(View v, int dir) {
        v.animate().cancel();
        v.animate().translationX(dp(7) * dir).setDuration(110).setInterpolator(CubicBezierInterpolator.EASE_OUT).withEndAction(() ->
            v.animate().translationX(0).setDuration(320).setInterpolator(new android.view.animation.OvershootInterpolator(2.2f)).start()).start();
    }

    // ------------------------------------------------------------------ open / close

    private void animateIn() {
        // hidden before the first frame (no flash of the player before it slides), then it comes up softly: the
        // background fades in, the content rises a third of the way and grows a little, its rows follow one by one
        content.setAlpha(0f);
        background.setAlpha(0f);
        root.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                root.getViewTreeObserver().removeOnPreDrawListener(this);
                final float h = root.getHeight() > 0 ? root.getHeight() : AndroidUtilities.displaySize.y;
                content.setTranslationY(h * 0.28f);
                content.setScaleX(0.94f);
                content.setScaleY(0.94f);
                content.animate().translationY(0).alpha(1f).scaleX(1f).scaleY(1f)
                    .setDuration(560).setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).start();
                background.animate().alpha(1f).setDuration(380).setInterpolator(CubicBezierInterpolator.EASE_OUT).start();
                for (int i = 0; i < content.getChildCount(); i++) {
                    final View child = content.getChildAt(i);
                    child.setAlpha(0f);
                    child.setTranslationY(dp(28));
                    child.animate().alpha(1f).translationY(0).setStartDelay(90 + i * 40L).setDuration(460)
                        .setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).start();
                }
                return true;
            }
        });
    }

    private boolean closing;

    private void animateOut() {
        if (closing) {
            return;
        }
        closing = true;
        // goes away as it came: sinks a little, shrinks and fades (not a hard drop)
        content.animate().translationY(content.getTranslationY() + root.getHeight() * 0.22f).alpha(0f).scaleX(0.95f).scaleY(0.95f)
            .setDuration(300).setInterpolator(CubicBezierInterpolator.EASE_BOTH).start();
        background.animate().alpha(0f).setDuration(300).setListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                try {
                    dismiss();
                } catch (Exception ignore) {
                }
            }
        }).start();
    }

    // ------------------------------------------------------------------ the track

    private void bindTrack(MessageObject mo, boolean animated) {
        if (mo == null) {
            return;
        }
        final boolean changed = current == null || current.getId() != mo.getId() || current.getDialogId() != mo.getDialogId();
        current = mo;
        titleView.setText(mo.getMusicTitle());
        artistView.setText(mo.getMusicAuthor());
        final List<MessageObject> list = MediaController.getInstance().getPlaylist();
        final int at = MediaController.getInstance().getPlayingMessageObjectNum();
        sourceView.setText(list != null && list.size() > 1 ? LocaleController.formatString(R.string.SG_PlayerOf, at + 1, list.size()) : getString(R.string.SG_PlayerMusic));
        if (changed) {
            if (animated) {
                if (showingLyrics) {
                    // the lyrics are open: the cover stays hidden under them, only its picture changes
                    cover.animate().cancel();
                    loadCover(mo);
                    cover.setAlpha(0f);
                } else {
                    cover.animate().scaleX(0.92f).scaleY(0.92f).alpha(0.4f).setDuration(120).withEndAction(() -> {
                        loadCover(mo);
                        final boolean lyricsNow = showingLyrics;
                        cover.animate().scaleX(lyricsNow ? 0.9f : 1f).scaleY(lyricsNow ? 0.9f : 1f).alpha(lyricsNow ? 0f : 1f)
                            .setDuration(320).setInterpolator(CubicBezierInterpolator.EASE_OUT_BACK).start();
                    }).start();
                }
            } else {
                loadCover(mo);
            }
            lyrics = null;
            lyricsFor = null;
            if (showingLyrics) {
                loadLyrics();
            }
        }
        updatePlayState(false);
        updateProgress();
        updateModes();
    }

    private void loadCover(MessageObject mo) {
        final AudioInfo info = MediaController.getInstance().getAudioInfo();
        coverBitmap = null;
        if (info != null && info.getCover() != null) {
            cover.image.setImageBitmap(info.getCover());
            setCoverBitmap(info.getCover());
            return;
        }
        final TLRPC.Document document = mo.getDocument();
        TLRPC.PhotoSize thumb = document != null ? FileLoader.getClosestPhotoSizeWithSize(document.thumbs, 360) : null;
        if (!(thumb instanceof TLRPC.TL_photoSize) && !(thumb instanceof TLRPC.TL_photoSizeProgressive)) {
            thumb = null;
        }
        final ImageLocation thumbLocation = thumb != null ? ImageLocation.getForDocument(thumb, document)
            : mo.getArtworkUrl(true) != null ? ImageLocation.getForPath(mo.getArtworkUrl(true)) : null;
        final String artwork = mo.getArtworkUrl(false);
        if (!TextUtils.isEmpty(artwork)) {
            cover.image.setImage(ImageLocation.getForPath(artwork), null, thumbLocation, null, 0, null, mo, 1);
        } else if (thumbLocation != null) {
            cover.image.setImage(thumbLocation, null, null, null, 0, null, mo, 1);
        } else {
            cover.image.clearImage();
            setCoverBitmap(null);
        }
        cover.invalidate();
    }

    /** The cover's colours: a blurred copy for the background, and two tones for the gradient. */
    private void setCoverBitmap(Bitmap bitmap) {
        if (bitmap == coverBitmap && bitmap != null) {
            return;
        }
        coverBitmap = bitmap;
        if (bitmap == null || bitmap.isRecycled()) {
            blurred = null;
            tint = 0xFF2A2A35;
            tint2 = 0xFF111118;
            background.invalidate();
            return;
        }
        try {
            final Bitmap small = Bitmap.createScaledBitmap(bitmap, 48, 48, true);
            final Bitmap copy = small.copy(Bitmap.Config.ARGB_8888, true);
            Utilities.stackBlurBitmap(copy, 6);
            blurred = copy;
            final int avg = AndroidUtilities.calcBitmapColor(small);
            final float[] hsv = new float[3];
            Color.colorToHSV(avg, hsv);
            hsv[1] = Math.min(1f, hsv[1] * 1.15f);
            hsv[2] = Math.min(0.55f, Math.max(0.28f, hsv[2]));
            tint = Color.HSVToColor(hsv);
            hsv[2] = Math.max(0.08f, hsv[2] * 0.35f);
            tint2 = Color.HSVToColor(hsv);
            progress.setGlowColor(ColorUtils.blendARGB(tint, Color.WHITE, 0.7f)); // the comet glows in the cover's light
        } catch (Throwable t) {
            blurred = null;
        }
        background.invalidate();
    }

    private void updatePlayState(boolean animated) {
        final boolean paused = MediaController.getInstance().isMessagePaused();
        playDrawable.setPause(!paused, animated);
        progress.setPlaying(!paused);
        cover.setPlaying(!paused);
    }

    private void updateProgress() {
        final MessageObject mo = MediaController.getInstance().getPlayingMessageObject();
        if (mo == null) {
            return;
        }
        final double duration = mo.getDuration();
        progress.setProgress(mo.audioProgress, mo.bufferedProgress);
        if (!progress.isDragging()) {
            timeLeft.setText(AndroidUtilities.formatShortDuration(mo.audioProgressSec));
        }
        timeRight.setText(duration > 0 ? "-" + AndroidUtilities.formatShortDuration((int) Math.max(0, duration - mo.audioProgressSec)) : "");
        if (showingLyrics && lyrics != null && lyrics.synced) {
            lyricsView.setTime((long) (mo.audioProgress * duration * 1000));
        }
    }

    private void updateModes() {
        if (shuffleButton == null) {
            return;
        }
        shuffleButton.setAlpha(SharedConfig.shuffleMusic ? 1f : 0.45f);
        final int mode = SharedConfig.repeatMode;
        repeatButton.setAlpha(mode == 0 ? 0.45f : 1f);
        final android.graphics.drawable.Drawable d = getContext().getResources().getDrawable(mode == 2 ? R.drawable.player_new_repeatone : R.drawable.player_new_repeatall).mutate();
        d.setColorFilter(new android.graphics.PorterDuffColorFilter(Color.WHITE, android.graphics.PorterDuff.Mode.SRC_IN));
        repeatButton.setImageDrawable(d);
        lyricsButton.setAlpha(showingLyrics ? 1f : 0.6f);
    }

    // ------------------------------------------------------------------ lyrics

    private void applyLyricsVisibility(boolean animated) {
        updateModes();
        if (showingLyrics) {
            loadLyrics();
        }
        final float coverAlpha = showingLyrics ? 0f : 1f;
        if (animated) {
            cover.animate().alpha(coverAlpha).scaleX(showingLyrics ? 0.9f : 1f).scaleY(showingLyrics ? 0.9f : 1f).setDuration(260).start();
            lyricsView.setVisibility(View.VISIBLE);
            lyricsView.animate().alpha(showingLyrics ? 1f : 0f).setDuration(260).withEndAction(() -> lyricsView.setVisibility(showingLyrics ? View.VISIBLE : View.GONE)).start();
        } else {
            cover.setAlpha(coverAlpha);
            lyricsView.setAlpha(showingLyrics ? 1f : 0f);
            lyricsView.setVisibility(showingLyrics ? View.VISIBLE : View.GONE);
        }
    }

    private void loadLyrics() {
        final MessageObject mo = current;
        if (mo == null) {
            return;
        }
        final String title = mo.getMusicTitle(false);
        final String artist = mo.getMusicAuthor(false);
        final int seconds = (int) Math.round(mo.getDuration());
        final String forKey = artist + "\n" + title + "\n" + seconds;
        if (forKey.equals(lyricsFor)) {
            return;
        }
        lyricsFor = forKey;
        lyricsView.showMessage(getString(R.string.SG_PlayerLyricsLoading));
        SolegramLyrics.load(title, artist, seconds, l -> {
            if (!forKey.equals(lyricsFor)) {
                return;
            }
            lyrics = l;
            if (l == null) {
                lyricsView.showMessage(getString(R.string.SG_PlayerLyricsNone));
            } else if (l.instrumental && l.lines.isEmpty()) {
                lyricsView.showMessage(getString(R.string.SG_PlayerLyricsInstrumental));
            } else {
                lyricsView.setLyrics(l);
                updateProgress();
            }
        });
    }

    // ------------------------------------------------------------------ menus

    private void showQueue() {
        final List<MessageObject> list = new ArrayList<>(MediaController.getInstance().getPlaylist());
        final BottomSheet sheet = new BottomSheet(getContext(), false);
        final LinearLayout column = new LinearLayout(getContext());
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(0, dp(8), 0, dp(12));
        final TextView head = new TextView(getContext());
        head.setText(getString(R.string.SG_PlayerQueue));
        head.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        head.setTypeface(AndroidUtilities.bold());
        head.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        column.addView(head, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 20, 8, 20, 8));
        final MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
        final int accent = Theme.getColor(Theme.key_featuredStickers_addButton);
        for (MessageObject m : list) {
            final LinearLayout row = new LinearLayout(getContext());
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(20), dp(10), dp(20), dp(10));
            row.setBackground(Theme.getSelectorDrawable(false));
            final boolean isPlaying = playing != null && playing.getId() == m.getId() && playing.getDialogId() == m.getDialogId();
            final TextView t = new TextView(getContext());
            t.setText(m.getMusicTitle());
            t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            t.setTextColor(isPlaying ? accent : Theme.getColor(Theme.key_dialogTextBlack));
            t.setTypeface(isPlaying ? AndroidUtilities.bold() : null);
            t.setSingleLine();
            t.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(t);
            final TextView a = new TextView(getContext());
            a.setText(m.getMusicAuthor());
            a.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            a.setTextColor(Theme.getColor(Theme.key_dialogTextGray3));
            a.setSingleLine();
            a.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(a);
            row.setOnClickListener(v -> {
                MediaController.getInstance().playMessage(m);
                sheet.dismiss();
            });
            column.addView(row);
        }
        final ScrollView scroll = new ScrollView(getContext());
        scroll.addView(column);
        sheet.setCustomView(scroll);
        sheet.show();
    }

    private void showLookMenu() {
        final Context context = getContext();
        final BottomSheet sheet = new BottomSheet(context, false);
        final LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(0, dp(8), 0, dp(16));
        final TextView head = new TextView(context);
        head.setText(getString(R.string.SG_PlayerLook));
        head.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        head.setTypeface(AndroidUtilities.bold());
        head.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        column.addView(head, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 20, 8, 20, 4));
        chipRow(column, R.string.SG_PlayerBackground, new int[]{R.string.SG_PlayerBgCover, R.string.SG_PlayerBgGradient, R.string.SG_PlayerBgDark}, () -> bgStyle, i -> {
            bgStyle = i;
            background.invalidate();
        });
        chipRow(column, R.string.SG_PlayerCover, new int[]{R.string.SG_PlayerCoverSquare, R.string.SG_PlayerCoverRound, R.string.SG_PlayerCoverVinyl}, () -> coverStyle, i -> {
            coverStyle = i;
            cover.invalidate();
        });
        chipRow(column, R.string.SG_PlayerProgress, new int[]{R.string.SG_PlayerProgressComet, R.string.SG_PlayerProgressLine}, () -> progressStyle, i -> {
            progressStyle = i;
            progress.setStyle(i);
        });
        chipRow(column, R.string.SG_PlayerOpenWith, new int[]{R.string.SG_PlayerOpenCover, R.string.SG_PlayerOpenLyrics}, () -> lyricsFirst ? 1 : 0, i -> lyricsFirst = i == 1);
        final ScrollView scroll = new ScrollView(context);
        scroll.addView(column);
        sheet.setCustomView(scroll);
        sheet.show();
    }

    private interface IntGetter {
        int get();
    }

    private void chipRow(LinearLayout column, int title, int[] names, IntGetter selected, Utilities.Callback<Integer> pick) {
        final Context context = column.getContext();
        final TextView label = new TextView(context);
        label.setText(getString(title));
        label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        label.setTypeface(AndroidUtilities.bold());
        label.setTextColor(Theme.getColor(Theme.key_dialogTextGray3));
        column.addView(label, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 20, 14, 20, 8));
        final HorizontalScrollView h = new HorizontalScrollView(context);
        h.setHorizontalScrollBarEnabled(false);
        h.setClipToPadding(false);
        h.setPadding(dp(16), 0, dp(16), 0);
        final LinearLayout row = new LinearLayout(context);
        h.addView(row);
        column.addView(h);
        final Runnable[] fill = new Runnable[1];
        fill[0] = () -> {
            row.removeAllViews();
            final int accent = Theme.getColor(Theme.key_featuredStickers_addButton);
            final int text = Theme.getColor(Theme.key_dialogTextBlack);
            for (int i = 0; i < names.length; i++) {
                final int index = i;
                final TextView chip = new TextView(context);
                chip.setText(getString(names[i]));
                chip.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
                chip.setGravity(Gravity.CENTER);
                chip.setPadding(dp(14), 0, dp(14), 0);
                final GradientDrawable bg = new GradientDrawable();
                bg.setCornerRadius(dp(18));
                final boolean on = selected.get() == i;
                bg.setColor(on ? accent : Theme.multAlpha(text, 0.07f));
                chip.setTextColor(on ? Color.WHITE : text);
                chip.setTypeface(on ? AndroidUtilities.bold() : null);
                chip.setBackground(bg);
                chip.setOnClickListener(v -> {
                    pick.run(index);
                    saveLook();
                    fill[0].run();
                });
                row.addView(chip, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 36, 3, 0, 3, 0));
            }
        };
        fill[0].run();
    }

    // ------------------------------------------------------------------ views

    private class BackgroundView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint shade = new Paint();
        private final android.graphics.Matrix matrix = new android.graphics.Matrix();

        BackgroundView(Context context) {
            super(context);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final int w = getWidth(), h = getHeight();
            if (bgStyle == BG_DARK) {
                canvas.drawColor(0xFF0E0E12);
                paint.setShader(new RadialGradient(w / 2f, h * 0.3f, w, ColorUtils.setAlphaComponent(tint, 0x55), 0, Shader.TileMode.CLAMP));
                canvas.drawRect(0, 0, w, h, paint);
                paint.setShader(null);
                return;
            }
            if (bgStyle == BG_COVER && blurred != null && !blurred.isRecycled()) {
                final float s = Math.max(w, h) / (float) blurred.getWidth() * 1.25f;
                matrix.reset();
                matrix.postScale(s, s);
                matrix.postTranslate((w - blurred.getWidth() * s) / 2f, (h - blurred.getHeight() * s) / 2f);
                canvas.drawBitmap(blurred, matrix, paint);
                shade.setShader(new LinearGradient(0, 0, 0, h, new int[]{0x59000000, 0x40000000, 0xB3000000}, new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
                canvas.drawRect(0, 0, w, h, shade);
                return;
            }
            paint.setShader(new LinearGradient(0, 0, w * 0.4f, h, tint, tint2, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, paint);
            paint.setShader(null);
        }
    }

    private class CoverView extends View {
        final ImageReceiver image = new ImageReceiver(this);
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path clip = new Path();
        private final RectF rect = new RectF();
        private boolean playing;
        private float spin;
        private float lift = 1f;
        private long last;

        CoverView(Context context) {
            super(context);
            image.setRoundRadius(dp(14));
            image.setDelegate((receiver, set, thumb, memCache) -> {
                if (set && receiver.getBitmap() != null) {
                    setCoverBitmap(receiver.getBitmap());
                }
            });
            image.onAttachedToWindow();
        }

        void setPlaying(boolean playing) {
            this.playing = playing;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final long now = SystemClock.elapsedRealtime();
            final float dt = last == 0 ? 16 : Math.min(64, now - last);
            last = now;
            // the cover sits a little smaller while paused, and the record turns while playing
            final float targetLift = playing ? 1f : 0.88f;
            boolean again = false;
            if (Math.abs(lift - targetLift) > 0.001f) {
                lift += (targetLift - lift) * Math.min(1f, dt / 140f);
                again = true;
            }
            final float size = Math.min(getWidth(), getHeight()) * lift;
            final float cx = getWidth() / 2f, cy = getHeight() / 2f;
            rect.set(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f);

            if (coverStyle == COVER_VINYL) {
                if (playing) {
                    spin = (spin + dt * 0.03f) % 360f;
                    again = true;
                }
                paint.setColor(0xFF121214);
                canvas.drawCircle(cx, cy, size / 2f, paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(Math.max(1, dp(0.6f)));
                for (int i = 0; i < 9; i++) {
                    paint.setColor(i % 2 == 0 ? 0x22FFFFFF : 0x14FFFFFF);
                    canvas.drawCircle(cx, cy, size / 2f * (0.52f + i * 0.052f), paint);
                }
                paint.setStyle(Paint.Style.FILL);
                // the light on the record
                paint.setShader(new LinearGradient(rect.left, rect.top, rect.right, rect.bottom,
                    new int[]{0x00FFFFFF, 0x1AFFFFFF, 0x00FFFFFF, 0x14FFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
                canvas.drawCircle(cx, cy, size / 2f, paint);
                paint.setShader(null);
                canvas.save();
                canvas.rotate(spin, cx, cy);
                final float label = size * 0.46f;
                image.setRoundRadius((int) (label / 2f));
                image.setImageCoords(cx - label / 2f, cy - label / 2f, label, label);
                image.draw(canvas);
                canvas.restore();
                paint.setColor(0xFF121214);
                canvas.drawCircle(cx, cy, size * 0.025f, paint);
            } else {
                image.setRoundRadius(coverStyle == COVER_ROUND ? (int) (size / 2f) : dp(18));
                image.setImageCoords(rect.left, rect.top, rect.width(), rect.height());
                if (!image.hasImageSet()) {
                    paint.setShader(new LinearGradient(rect.left, rect.top, rect.right, rect.bottom, ColorUtils.blendARGB(tint, Color.WHITE, 0.2f), tint2, Shader.TileMode.CLAMP));
                    if (coverStyle == COVER_ROUND) {
                        canvas.drawCircle(cx, cy, size / 2f, paint);
                    } else {
                        canvas.drawRoundRect(rect, dp(18), dp(18), paint);
                    }
                    paint.setShader(null);
                    final android.graphics.drawable.Drawable note = getContext().getResources().getDrawable(R.drawable.files_music).mutate();
                    note.setColorFilter(new android.graphics.PorterDuffColorFilter(0x99FFFFFF, android.graphics.PorterDuff.Mode.SRC_IN));
                    final int ns = (int) (size * 0.3f);
                    note.setBounds((int) (cx - ns / 2f), (int) (cy - ns / 2f), (int) (cx + ns / 2f), (int) (cy + ns / 2f));
                    note.draw(canvas);
                } else {
                    image.draw(canvas);
                }
            }
            if (again) {
                postInvalidateOnAnimation();
            } else {
                last = 0;
            }
        }
    }

    /** Lyrics, Spotify-like: big lines, the one being sung bright and kept in the middle; tap a line to jump to it. */
    private class LyricsView extends ScrollView {
        private final LinearLayout column;
        private final ArrayList<TextView> lines = new ArrayList<>();
        private SolegramLyrics.Lyrics shown;
        private int currentLine = -2;
        private long userScrolledAt;

        LyricsView(Context context) {
            super(context);
            setVerticalScrollBarEnabled(false);
            setVerticalFadingEdgeEnabled(true);
            setFadingEdgeLength(dp(48));
            column = new LinearLayout(context);
            column.setOrientation(LinearLayout.VERTICAL);
            column.setPadding(0, dp(40), 0, dp(120));
            addView(column);
        }

        // the middle of the player doesn't clip its children (so buttons' glow isn't cut), so the lyrics clip
        // themselves to their own area: they scroll under the cover's place, never over the title and controls
        @Override
        public void draw(android.graphics.Canvas canvas) {
            canvas.save();
            canvas.clipRect(getScrollX(), getScrollY(), getScrollX() + getWidth(), getScrollY() + getHeight());
            super.draw(canvas);
            canvas.restore();
        }

        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            if (ev.getActionMasked() == MotionEvent.ACTION_MOVE) {
                userScrolledAt = SystemClock.elapsedRealtime();
            }
            return super.onTouchEvent(ev);
        }

        void showMessage(String text) {
            shown = null;
            lines.clear();
            column.removeAllViews();
            final TextView t = line(text);
            t.setAlpha(0.7f);
            t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
            column.addView(t, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, dp(30), 0, 0));
        }

        void setLyrics(SolegramLyrics.Lyrics l) {
            shown = l;
            lines.clear();
            column.removeAllViews();
            currentLine = -2;
            for (SolegramLyrics.Line ln : l.lines) {
                final TextView t = line(TextUtils.isEmpty(ln.text) ? "♪" : ln.text);
                t.setAlpha(l.synced ? 0.38f : 0.9f);
                if (l.synced && ln.time >= 0) {
                    t.setOnClickListener(v -> {
                        final MessageObject mo = MediaController.getInstance().getPlayingMessageObject();
                        if (mo != null && mo.getDuration() > 0) {
                            MediaController.getInstance().seekToProgress(mo, (float) Math.min(1.0, ln.time / 1000.0 / mo.getDuration()));
                            userScrolledAt = 0;
                        }
                    });
                }
                lines.add(t);
                column.addView(t, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 7, 0, 7));
            }
            final TextView source = new TextView(getContext());
            source.setText(getString(l.synced ? R.string.SG_PlayerLyricsSource : R.string.SG_PlayerLyricsPlain));
            source.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            source.setTextColor(0x80FFFFFF);
            column.addView(source, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 24, 0, 0));
            scrollTo(0, 0);
        }

        private TextView line(String text) {
            final TextView t = new TextView(getContext());
            t.setText(text);
            t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 25);
            t.setTypeface(AndroidUtilities.bold());
            t.setTextColor(Color.WHITE);
            t.setLineSpacing(dp(2), 1f);
            t.setPivotX(0);
            return t;
        }

        void setTime(long ms) {
            if (shown == null || !shown.synced) {
                return;
            }
            final int at = shown.lineAt(ms);
            if (at == currentLine) {
                return;
            }
            currentLine = at;
            for (int i = 0; i < lines.size(); i++) {
                final TextView t = lines.get(i);
                final boolean on = i == at;
                t.animate().alpha(on ? 1f : i < at ? 0.32f : 0.42f).scaleX(on ? 1.04f : 1f).scaleY(on ? 1.04f : 1f).setDuration(260).start();
            }
            if (at >= 0 && at < lines.size() && SystemClock.elapsedRealtime() - userScrolledAt > 3500) {
                final TextView t = lines.get(at);
                final int target = Math.max(0, t.getTop() + column.getTop() - getHeight() / 3);
                smoothScrollTo(0, target);
            }
        }
    }
}
