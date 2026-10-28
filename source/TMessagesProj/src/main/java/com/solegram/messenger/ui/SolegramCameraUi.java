/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.dpf2;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.hardware.Camera;
import android.os.Build;
import android.os.SystemClock;
import android.text.TextPaint;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.OrientationEventListener;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;

import com.solegram.messenger.SolegramConfig;
import com.solegram.messenger.SolegramGlass;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LiteMode;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.R;
import org.telegram.messenger.camera.CameraSession;
import org.telegram.messenger.camera.CameraSessionWrapper;
import org.telegram.messenger.camera.CameraView;
import org.telegram.messenger.utils.RenderNodeEffects;
import org.telegram.ui.Components.AnimatedFloat;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.ScaleStateListAnimator;
import org.telegram.ui.Components.ShutterButton;
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory;
import org.telegram.ui.Components.blur3.RenderNodeWithHash;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundProvider;
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundProviderBuilder;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceRenderNode;
import org.telegram.ui.Components.chat.ViewPositionWatcher;

import java.util.ArrayList;

/**
 * The attach camera as a phone's own camera app: settings on the viewfinder instead of after the
 * shot. A row of round buttons at the top (flash with the torch, HDR - Solegram's processing,
 * self-timer, frame 4:3 / 16:9 / 1:1, grid), zoom steps over the shutter (and pinch), a focus ring
 * with an exposure "sun" to drag after a tap, a 3x3 grid, the self-timer countdown, the last shot
 * beside the shutter and photo / video modes under it.
 * <p>
 * Every control drives the camera Telegram already runs (the old camera API under the attach
 * camera): flash and torch modes, exposure compensation and zoom steps of {@link CameraSession},
 * the frame through the preview and picture sizes of {@link CameraView}, the square cut after the
 * shot. What the camera can't do isn't offered (no flash on most front cameras, no exposure or
 * zoom on some).
 * <p>
 * With liquid glass on (Android 13+), the buttons are the app's liquid glass refracting the live
 * camera picture (the preview is a TextureView, so its frames can be drawn into the glass);
 * otherwise clean translucent dark circles.
 */
public final class SolegramCameraUi {

    public interface Host {
        @Nullable
        CameraView camera();

        /** The camera is fully open (no open / close animation). */
        boolean isSettled();

        /** Zoom (0..1, the camera's own steps) picked on the zoom pill. */
        void setZoom(float zoom);

        /** Zoom now (pinch, the shutter drag and resets included). */
        float zoom();

        /** The newest shot of this camera session, null before the first. */
        @Nullable
        MediaController.PhotoEntry lastShot();

        /** The frame setting changed: the camera has to be set up for it. */
        void onAspectChanged();

        /**
         * The camera may change mode or frame now: open and still (no open / close animation, no
         * shot being saved, no restart of its own still running).
         */
        boolean canChangeCamera();

        void onModeChanged(boolean video);

        /** The last shot was tapped: show the shots taken. */
        void openShots();

        boolean isVideoAllowed();

        /** Room taken at the bottom (portrait) or right (landscape) by the shutter panel and what is under it. */
        int controlsInset();
    }

    /** Solegram's camera controls replace Telegram's few buttons on the attach camera. */
    public static boolean enabled() {
        return SolegramConfig.cameraControls;
    }

    /** Liquid glass over the preview (Android 13+ and liquid glass on). */
    public static boolean liquid() {
        return Build.VERSION.SDK_INT >= 33 && LiteMode.isEnabled(LiteMode.FLAG_LIQUID_GLASS);
    }

    private static boolean animations() {
        return LiteMode.isEnabled(LiteMode.FLAGS_CHAT);
    }

    static final int YELLOW = 0xFFFFD60A;
    private static final int BUTTON = 40;
    private static final int BUTTON_GAP = 14;
    /** One line weight for every glyph of the top row (in the 24-unit icon box), a lighter one for inner details. */
    private static final float STROKE = 1.6f, STROKE_THIN = 1.15f;

    private static final int FLASH = 0, HDR = 1, TIMER = 2, ASPECT = 3, GRID = 4;

    private final Context context;
    private final Host host;
    private final ViewGroup root;
    @Nullable
    private final Glass glass;

    public final Overlay overlay;
    /** Beside the shutter: the last shot. */
    public final ThumbView thumb;
    /** Under the shutter: photo / video. */
    public final ModeSwitch modeSwitch;

    private final LinearLayout topBar;
    private final CamButton[] buttons = new CamButton[5];
    private final TextView toast;
    private final ZoomPill zoomPill;
    @Nullable
    private ShutterButton shutter;
    @Nullable
    private View switchButton;

    private boolean active;
    private boolean videoMode;
    private boolean recording;
    private float iconRotation;
    private int lastDeviceOrientation = -1;
    /** The photo flash (auto / on) put aside while in video mode. */
    @Nullable
    private String photoFlashMode;
    private OrientationEventListener orientationListener;

    // self-timer
    @Nullable
    private Runnable timerAction;
    private int timerLeft;
    private long timerTickAt;
    private final Runnable timerTick = this::onTimerTick;

    public SolegramCameraUi(Context context, ViewGroup root, Host host) {
        this.context = context;
        this.root = root;
        this.host = host;
        this.glass = liquid() ? new Glass() : null;

        overlay = new Overlay(context);

        topBar = new LinearLayout(context);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER);
        topBar.setClipChildren(false);
        topBar.setClipToPadding(false);
        for (int i = 0; i < buttons.length; i++) {
            final int type = i;
            final CamButton button = new CamButton(context, type);
            button.setOnClickListener(v -> onButton(type));
            buttons[i] = button;
            topBar.addView(button, new LinearLayout.LayoutParams(dp(BUTTON), dp(BUTTON)));
        }
        overlay.addView(topBar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        toast = new TextView(context);
        toast.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        toast.setTypeface(AndroidUtilities.bold());
        toast.setLetterSpacing(0.02f);
        toast.setTextColor(0xFFFFFFFF);
        toast.setGravity(Gravity.CENTER);
        toast.setPadding(dp(14), 0, dp(14), 0);
        toast.setBackground(new PlainPill(0xA31C1C1E, 0x1AFFFFFF));
        toast.setAlpha(0f);
        toast.setVisibility(View.GONE);
        overlay.addView(toast, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(28)));

        zoomPill = new ZoomPill(context);
        overlay.addView(zoomPill, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        thumb = new ThumbView(context);
        thumb.setOnClickListener(v -> host.openShots());
        thumb.setVisibility(View.INVISIBLE);
        thumb.setContentDescription(LocaleController.getString(R.string.SG_CamLastShot));

        modeSwitch = new ModeSwitch(context);

        updateButtons();
    }

    // region Host calls

    /** The shutter and the switch button of the camera panel: styled, and told about the timer. */
    public void attachPanel(ShutterButton shutter, View switchButton) {
        this.shutter = shutter;
        this.switchButton = switchButton;
        shutter.setSolegramStyle(true);
        shutter.setVideoMode(videoMode, false);
        switchButton.setBackground(background(switchButton, dp(24)));
    }

    /** The camera opened (true) or closed: sensors, glass and state follow it. */
    public void setActive(boolean value) {
        if (active == value) {
            return;
        }
        active = value;
        if (value) {
            if (orientationListener == null) {
                orientationListener = new OrientationEventListener(context) {
                    @Override
                    public void onOrientationChanged(int orientation) {
                        onDeviceOrientation(orientation);
                    }
                };
            }
            if (orientationListener.canDetectOrientation()) {
                orientationListener.enable();
            }
            if (recording && (shutter == null || shutter.getState() != ShutterButton.State.RECORDING)) {
                // the sheet went away mid-recording last time: the controls come back
                setRecording(false);
            }
            overlay.resetFocus();
            refresh();
        } else {
            cancelTimer();
            if (orientationListener != null) {
                orientationListener.disable();
            }
            overlay.resetFocus();
        }
        overlay.invalidate();
    }

    public boolean isActive() {
        return active;
    }

    /** A camera session is ready (also after switching cameras or frames): show what it offers. */
    public void onCameraInit() {
        overlay.resetFocus();
        refresh();
    }

    public boolean isVideoMode() {
        return videoMode;
    }

    /** Back to photos (video isn't allowed in this chat). */
    public void setPhotoMode() {
        if (videoMode && !recording) {
            cancelTimer();
            applyVideoMode(false, false);
        }
    }

    /** Recording (or not): the settings step aside like in a camera app, the zoom stays. */
    public void setRecording(boolean value) {
        recording = value;
        final float alpha = value ? 0f : 1f;
        fade(topBar, alpha);
        fade(modeSwitch, value || !host.isVideoAllowed() ? 0f : 1f);
        fade(thumb, value ? 0f : 1f);
        if (value) {
            toast.setVisibility(View.GONE);
        }
    }

    /** The top row across (portrait) or down the side (landscape), the gaps only between the buttons. */
    private void setBarOrientation(boolean portrait) {
        final int orientation = portrait ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL;
        if (topBar.getOrientation() == orientation && topBar.getTag() != null) {
            return;
        }
        topBar.setTag(Boolean.TRUE);
        topBar.setOrientation(orientation);
        final int half = dp(BUTTON_GAP) / 2;
        for (CamButton button : buttons) {
            final LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) button.getLayoutParams();
            lp.leftMargin = lp.rightMargin = portrait ? half : 0;
            lp.topMargin = lp.bottomMargin = portrait ? 0 : half;
        }
    }

    private static void fade(View view, float alpha) {
        view.animate().cancel();
        if (animations()) {
            view.animate().alpha(alpha).setDuration(180).setInterpolator(CubicBezierInterpolator.DEFAULT).start();
        } else {
            view.setAlpha(alpha);
        }
    }

    /** A tap on the viewfinder (in overlay coordinates): focus there, the exposure sun next to it. */
    public void onTapToFocus(float x, float y) {
        overlay.focusAt(x, y);
    }

    /** Whether the 1:1 frame is on (the photo is cut to the middle square after the shot). */
    public boolean isSquare() {
        return !videoMode && SolegramConfig.cameraAspect == SolegramConfig.CAMERA_ASPECT_1_1;
    }

    /** Frame the camera has to give now, a {@code CameraView.SG_ASPECT_*} value (video is always 16:9). */
    public int cameraAspect() {
        if (videoMode || SolegramConfig.cameraAspect == SolegramConfig.CAMERA_ASPECT_16_9) {
            return CameraView.SG_ASPECT_16_9;
        }
        if (SolegramConfig.cameraAspect == SolegramConfig.CAMERA_ASPECT_FULL) {
            return CameraView.SG_ASPECT_FULL;
        }
        return CameraView.SG_ASPECT_4_3;
    }

    /** The open camera shows its frame letterboxed (true) or filling the screen (full screen photos). */
    public boolean wantsFit() {
        return videoMode || SolegramConfig.cameraAspect != SolegramConfig.CAMERA_ASPECT_FULL;
    }

    /**
     * Shape the next photo is cut to after the shot (long side over short side), 0 for none: 1 for
     * 1:1, and for full screen the shape of the filled viewfinder - exactly what it showed.
     */
    public float cropRatio(@Nullable CameraView camera) {
        if (videoMode) {
            return 0;
        }
        if (SolegramConfig.cameraAspect == SolegramConfig.CAMERA_ASPECT_1_1) {
            return 1f;
        }
        if (SolegramConfig.cameraAspect == SolegramConfig.CAMERA_ASPECT_FULL && camera != null && !camera.fit && camera.getWidth() > 0 && camera.getHeight() > 0) {
            return (float) Math.max(camera.getWidth(), camera.getHeight()) / Math.min(camera.getWidth(), camera.getHeight());
        }
        return 0;
    }
    // endregion

    // region Self-timer

    public boolean isTimerRunning() {
        return timerAction != null;
    }

    /**
     * Runs the action after the self-timer, counting down on the viewfinder; false (nothing done)
     * when the timer is off.
     */
    public boolean startTimer(Runnable action) {
        if (SolegramConfig.cameraTimer <= 0 || timerAction != null) {
            return false;
        }
        timerAction = action;
        timerLeft = SolegramConfig.cameraTimer;
        timerTickAt = SystemClock.uptimeMillis();
        if (shutter != null) {
            shutter.setTimerRunning(true);
        }
        fade(topBar, 0.35f);
        haptic(overlay);
        AndroidUtilities.runOnUIThread(timerTick, 1000);
        overlay.invalidate();
        return true;
    }

    public void cancelTimer() {
        if (timerAction == null) {
            return;
        }
        timerAction = null;
        AndroidUtilities.cancelRunOnUIThread(timerTick);
        if (shutter != null) {
            shutter.setTimerRunning(false);
        }
        if (!recording) {
            fade(topBar, 1f);
        }
        overlay.invalidate();
    }

    private void onTimerTick() {
        if (timerAction == null) {
            return;
        }
        timerLeft--;
        timerTickAt = SystemClock.uptimeMillis();
        if (timerLeft <= 0) {
            final Runnable action = timerAction;
            cancelTimer();
            if (active) {
                action.run();
            }
            return;
        }
        haptic(overlay);
        AndroidUtilities.runOnUIThread(timerTick, 1000);
        overlay.invalidate();
    }
    // endregion

    // region Buttons

    @Nullable
    private CameraSession session() {
        final CameraView camera = host.camera();
        final CameraSessionWrapper wrapper = camera != null ? camera.getCameraSession() : null;
        return wrapper != null ? wrapper.camera1Session : null;
    }

    private void onButton(int type) {
        if (isTimerRunning() || recording) {
            return; // the countdown keeps what it was started with
        }
        haptic(buttons[type]);
        switch (type) {
            case FLASH: {
                final CameraView camera = host.camera();
                final CameraSession session = session();
                if (camera == null || session == null || !camera.isInited()) {
                    return;
                }
                final ArrayList<String> modes = flashModes(session);
                if (modes.size() < 2) {
                    return;
                }
                final int index = modes.indexOf(session.getCurrentFlashMode());
                final String next = modes.get((index + 1) % modes.size());
                camera.getCameraSession().setCurrentFlashMode(next);
                showToast(flashName(next));
                break;
            }
            case HDR:
                SolegramConfig.putBoolean("cameraEnhance", !SolegramConfig.cameraEnhance);
                showToast(LocaleController.getString(SolegramConfig.cameraEnhance ? R.string.SG_CamHdrOn : R.string.SG_CamHdrOff));
                break;
            case TIMER: {
                final int next = SolegramConfig.cameraTimer == 0 ? 3 : SolegramConfig.cameraTimer == 3 ? 10 : 0;
                SolegramConfig.putInt("cameraTimer", next);
                showToast(next == 0 ? LocaleController.getString(R.string.SG_CamTimerOff) : LocaleController.formatString(R.string.SG_CamTimer, next));
                break;
            }
            case ASPECT: {
                if (!host.canChangeCamera()) {
                    return;
                }
                // full screen first, then the classic frames
                final int a = SolegramConfig.cameraAspect;
                final int next = a == SolegramConfig.CAMERA_ASPECT_FULL ? SolegramConfig.CAMERA_ASPECT_4_3
                    : a == SolegramConfig.CAMERA_ASPECT_4_3 ? SolegramConfig.CAMERA_ASPECT_16_9
                    : a == SolegramConfig.CAMERA_ASPECT_16_9 ? SolegramConfig.CAMERA_ASPECT_1_1 : SolegramConfig.CAMERA_ASPECT_FULL;
                SolegramConfig.putInt("cameraAspect", next);
                showToast(aspectName(next));
                zoomPill.stopZoom();
                host.onAspectChanged();
                overlay.resetFocus();
                break;
            }
            case GRID:
                SolegramConfig.putBoolean("cameraGrid", !SolegramConfig.cameraGrid);
                showToast(LocaleController.getString(SolegramConfig.cameraGrid ? R.string.SG_CamGridOn : R.string.SG_CamGridOff));
                overlay.invalidate();
                break;
        }
        updateButtons();
    }

    /** Flash modes in the order a tap goes through them: off, auto, on, torch; video: off and torch. */
    private ArrayList<String> flashModes(CameraSession session) {
        final ArrayList<String> modes = new ArrayList<>(4);
        final ArrayList<String> available = session.availableFlashModes;
        if (!available.isEmpty()) {
            modes.add(Camera.Parameters.FLASH_MODE_OFF);
        }
        if (!videoMode && available.contains(Camera.Parameters.FLASH_MODE_AUTO)) {
            modes.add(Camera.Parameters.FLASH_MODE_AUTO);
        }
        if (!videoMode && available.contains(Camera.Parameters.FLASH_MODE_ON)) {
            modes.add(Camera.Parameters.FLASH_MODE_ON);
        }
        if (session.torchSupported && !modes.isEmpty()) {
            modes.add(Camera.Parameters.FLASH_MODE_TORCH);
        }
        return modes;
    }

    private static String flashName(String mode) {
        if (Camera.Parameters.FLASH_MODE_AUTO.equals(mode)) {
            return LocaleController.getString(R.string.SG_CamFlashAuto);
        } else if (Camera.Parameters.FLASH_MODE_ON.equals(mode)) {
            return LocaleController.getString(R.string.SG_CamFlashOn);
        } else if (Camera.Parameters.FLASH_MODE_TORCH.equals(mode)) {
            return LocaleController.getString(R.string.SG_CamTorch);
        }
        return LocaleController.getString(R.string.SG_CamFlashOff);
    }

    private static String aspectName(int aspect) {
        if (aspect == SolegramConfig.CAMERA_ASPECT_FULL) {
            return LocaleController.getString(R.string.SG_CamFull);
        }
        return aspect == SolegramConfig.CAMERA_ASPECT_16_9 ? "16:9" : aspect == SolegramConfig.CAMERA_ASPECT_1_1 ? "1:1" : "4:3";
    }

    /** What the camera offers now: buttons it can't use go, the zoom steps follow its range. */
    private void refresh() {
        final CameraSession session = session();
        if (session != null && videoMode) {
            // auto and on make no sense for a recording: the torch or nothing
            final String mode = session.getCurrentFlashMode();
            if (Camera.Parameters.FLASH_MODE_AUTO.equals(mode) || Camera.Parameters.FLASH_MODE_ON.equals(mode)) {
                final CameraView camera = host.camera();
                if (camera != null && camera.getCameraSession() != null) {
                    camera.getCameraSession().setCurrentFlashMode(Camera.Parameters.FLASH_MODE_OFF);
                }
            }
        }
        zoomPill.setMaxRatio(session != null ? session.getMaxZoomRatio() : 1f);
        updateButtons();
    }

    private void updateButtons() {
        final CameraSession session = session();
        // while the camera restarts there is no session: the flash button keeps its place till it is back
        final boolean hasFlash = session != null ? flashModes(session).size() >= 2 : buttons[FLASH].getVisibility() == View.VISIBLE;
        setButtonsShown(hasFlash, !videoMode, !videoMode);
        for (CamButton button : buttons) {
            button.invalidate();
        }
        buttons[FLASH].setContentDescription(session != null ? flashName(session.getCurrentFlashMode()) : null);
        buttons[HDR].setContentDescription(LocaleController.getString(SolegramConfig.cameraEnhance ? R.string.SG_CamHdrOn : R.string.SG_CamHdrOff));
        buttons[TIMER].setContentDescription(SolegramConfig.cameraTimer == 0 ? LocaleController.getString(R.string.SG_CamTimerOff) : LocaleController.formatString(R.string.SG_CamTimer, SolegramConfig.cameraTimer));
        buttons[ASPECT].setContentDescription(LocaleController.getString(R.string.SG_CamAspect) + " " + aspectName(SolegramConfig.cameraAspect));
        buttons[GRID].setContentDescription(LocaleController.getString(SolegramConfig.cameraGrid ? R.string.SG_CamGridOn : R.string.SG_CamGridOff));
    }

    private final int[] barLefts = new int[5], barTops = new int[5];

    /**
     * Shows or hides the flash, HDR and frame buttons. The row closes up or opens smoothly: the
     * buttons that stay glide to their new places, the new ones grow in (by hand, a LayoutTransition
     * left buttons half faded when taps came fast).
     */
    private void setButtonsShown(boolean flash, boolean hdr, boolean aspect) {
        final boolean[] shown = {flash, hdr, true, aspect, true};
        boolean changed = false;
        for (int i = 0; i < buttons.length; i++) {
            if ((buttons[i].getVisibility() == View.VISIBLE) != shown[i]) {
                changed = true;
                break;
            }
        }
        if (!changed) {
            return;
        }
        final boolean animate = animations() && active && topBar.isLaidOut() && topBar.getAlpha() > 0;
        for (int i = 0; i < buttons.length; i++) {
            // where each one is seen now, a glide still under way included
            barLefts[i] = topBar.getLeft() + buttons[i].getLeft() + Math.round(buttons[i].getTranslationX());
            barTops[i] = topBar.getTop() + buttons[i].getTop() + Math.round(buttons[i].getTranslationY());
        }
        for (int i = 0; i < buttons.length; i++) {
            final CamButton button = buttons[i];
            final boolean wasShown = button.getVisibility() == View.VISIBLE;
            button.animate().cancel();
            button.setTranslationX(0);
            button.setTranslationY(0);
            button.setScaleX(1f);
            button.setScaleY(1f);
            button.setAlpha(1f);
            button.setVisibility(shown[i] ? View.VISIBLE : View.GONE);
            if (animate && shown[i] && !wasShown) {
                button.setAlpha(0f);
                button.setScaleX(0.5f);
                button.setScaleY(0.5f);
                button.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(60).setDuration(260).setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).start();
            }
        }
        if (!animate) {
            return;
        }
        topBar.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                topBar.getViewTreeObserver().removeOnPreDrawListener(this);
                for (int i = 0; i < buttons.length; i++) {
                    final CamButton button = buttons[i];
                    if (!shown[i] || button.getVisibility() != View.VISIBLE || button.getAlpha() < 1f) {
                        continue;
                    }
                    final int dx = barLefts[i] - (topBar.getLeft() + button.getLeft());
                    final int dy = barTops[i] - (topBar.getTop() + button.getTop());
                    if (dx != 0 || dy != 0) {
                        button.setTranslationX(dx);
                        button.setTranslationY(dy);
                        button.animate().translationX(0).translationY(0).setDuration(280).setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).start();
                    }
                }
                return true;
            }
        });
    }

    private void setVideoMode(boolean video) {
        if (videoMode == video || recording || isTimerRunning() || !host.canChangeCamera()) {
            return;
        }
        haptic(modeSwitch);
        applyVideoMode(video, true);
    }

    private void applyVideoMode(boolean video, boolean animated) {
        videoMode = video;
        zoomPill.stopZoom(); // a glide to a zoom step must not land on a restarted camera
        modeSwitch.sync(animated);
        if (shutter != null) {
            shutter.setVideoMode(video, animated && animations());
        }
        final CameraSession session = session();
        final CameraView camera = host.camera();
        if (session != null && camera != null && camera.getCameraSession() != null) {
            // a recording knows only off and the torch: the photo flash comes back with photos
            final String mode = session.getCurrentFlashMode();
            if (video) {
                if (Camera.Parameters.FLASH_MODE_AUTO.equals(mode) || Camera.Parameters.FLASH_MODE_ON.equals(mode)) {
                    photoFlashMode = mode;
                    camera.getCameraSession().setCurrentFlashMode(Camera.Parameters.FLASH_MODE_OFF);
                } else {
                    photoFlashMode = null;
                }
            } else {
                if (photoFlashMode != null && Camera.Parameters.FLASH_MODE_OFF.equals(mode) && session.availableFlashModes.contains(photoFlashMode)) {
                    camera.getCameraSession().setCurrentFlashMode(photoFlashMode);
                }
                photoFlashMode = null;
            }
        }
        refresh();
        overlay.resetFocus();
        overlay.invalidate();
        host.onModeChanged(video);
    }

    private final Runnable hideToast = this::hideToast;

    private void hideToast() {
        if (animations()) {
            toast.animate().alpha(0f).setDuration(200).withEndAction(() -> toast.setVisibility(View.GONE)).start();
        } else {
            toast.setVisibility(View.GONE);
        }
    }

    private void showToast(String text) {
        toast.setText(text);
        toast.setVisibility(View.VISIBLE);
        toast.animate().cancel();
        if (animations()) {
            toast.animate().alpha(1f).setDuration(150).start();
        } else {
            toast.setAlpha(1f);
        }
        AndroidUtilities.cancelRunOnUIThread(hideToast);
        AndroidUtilities.runOnUIThread(hideToast, 1300);
    }

    static void haptic(View view) {
        try {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        } catch (Exception ignore) {
        }
    }
    // endregion

    // region Rotation-aware icons

    private void onDeviceOrientation(int orientation) {
        if (orientation == OrientationEventListener.ORIENTATION_UNKNOWN) {
            return;
        }
        if (lastDeviceOrientation >= 0) {
            int dist = Math.abs(orientation - lastDeviceOrientation);
            dist = Math.min(dist, 360 - dist);
            if (dist < 55) {
                return; // a little past the diagonal before turning, so the icons don't flicker
            }
        }
        int device = ((orientation + 45) / 90 * 90) % 360;
        if (device == 180 || device == lastDeviceOrientation) {
            return; // upside down: the icons keep their way, as camera apps do
        }
        lastDeviceOrientation = device;
        int display = 0;
        try {
            final WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            switch (wm.getDefaultDisplay().getRotation()) {
                case Surface.ROTATION_90: display = 90; break;
                case Surface.ROTATION_180: display = 180; break;
                case Surface.ROTATION_270: display = 270; break;
            }
        } catch (Exception ignore) {
        }
        float target = -((device - display + 360) % 360);
        if (target <= -180) {
            target += 360;
        }
        if (target == iconRotation) {
            return;
        }
        iconRotation = target;
        for (CamButton button : buttons) {
            button.rotateIcon(target);
        }
        zoomPill.rotateText(target);
        thumb.animate().rotation(target).setDuration(animations() ? 300 : 0).setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).start();
        if (switchButton != null) {
            switchButton.animate().rotation(target).setDuration(animations() ? 300 : 0).setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).start();
        }
    }
    // endregion

    // region Glass

    /**
     * Background of a round control: live liquid glass over the preview, or a clean dark circle. Both get a soft
     * light from above ({@link Sheen}): over the black bars around a 4:3 or 1:1 frame there is nothing for the
     * glass to bend, and the lit edge is what still shows it as glass, as on iOS.
     */
    Drawable background(View view, int radius) {
        if (glass != null) {
            final BlurredBackgroundDrawable drawable = glass.create(view);
            drawable.setRadius(radius);
            return new android.graphics.drawable.LayerDrawable(new Drawable[]{drawable, new Sheen(0x2EFFFFFF, 0x0DFFFFFF)});
        }
        return new android.graphics.drawable.LayerDrawable(new Drawable[]{new PlainPill(0x61232326, 0x2EFFFFFF), new Sheen(0x1FFFFFFF, 0)});
    }

    /**
     * The liquid glass of the controls: what the camera shows, recorded each frame into a node of
     * its own (the TextureView's frame is drawn there by reference), blurred lightly and refracted
     * by every button as the glass in chats is.
     */
    private final class Glass {
        private final BlurredBackgroundSourceRenderNode source;
        private final BlurredBackgroundDrawableViewFactory factory;
        private final BlurredBackgroundProvider provider;
        private final Matrix tmpMatrix = new Matrix();

        Glass() {
            source = new BlurredBackgroundSourceRenderNode(null);
            source.setupRenderer(new RenderNodeWithHash.Renderer() {
                @Override
                public void renderNodeUpdateDisplayList(Canvas canvas) {
                    drawPreview(canvas);
                }
                // no hash: a camera frame changes every time, the recording is one reference anyway
            });
            final float radius = dpf2(9f); // a fixed light blur: the camera picture under the buttons stays readable as glass
            if (SolegramGlass.vibrancy()) {
                source.setBlur(radius, RenderNodeEffects.getSaturationX2RenderEffect());
            } else {
                source.setBlur(radius);
            }
            factory = new BlurredBackgroundDrawableViewFactory(source);
            factory.setSourceRootView(new ViewPositionWatcher(root), root);
            factory.setLiquidGlassEffectAllowed(true);
            // a neutral grey body rather than a dark one: over the black bars a dark tint was black, a flat circle;
            // grey reads as glass there and still lets the white icons stand out over a bright scene
            provider = new BlurredBackgroundProviderBuilder(null)
                .setBackgroundColor((r, isDark) -> Color.argb(Math.round(255 * Math.max(0.22f, Math.min(0.42f, 0.36f * SolegramGlass.alpha(true) / 0.85f))), 0x5C, 0x5C, 0x63))
                .setStrokeColorTop(0x6BFFFFFF, 0x6BFFFFFF)
                .setStrokeColorBottom(0x24FFFFFF, 0x24FFFFFF)
                .setShadowColor(0x38000000, 0x38000000)
                .setShadowLayer(dpf2(6f), 0, dpf2(1.5f))
                .setStrokeWidth(dpf2(1f), dpf2(0.8f))
                .build();
        }

        BlurredBackgroundDrawable create(View view) {
            return factory.create(view, provider);
        }

        void sync(int width, int height) {
            if (width > 0 && height > 0) {
                source.setSize(width, height);
                source.updateDisplayListIfNeeded();
            }
        }

        private void drawPreview(Canvas canvas) {
            final CameraView camera = host.camera();
            if (camera == null || camera.getVisibility() != View.VISIBLE || camera.getParent() != root) {
                return;
            }
            final TextureView texture = camera.getTextureView();
            if (texture == null || texture.getParent() != camera || texture.getVisibility() != View.VISIBLE || !texture.isAvailable()) {
                return;
            }
            final int count = canvas.save();
            canvas.translate(camera.getX(), camera.getY());
            canvas.translate(texture.getLeft(), texture.getTop());
            final Matrix matrix = texture.getMatrix();
            if (!matrix.isIdentity()) {
                tmpMatrix.set(matrix);
                canvas.concat(tmpMatrix);
            }
            final float alpha = camera.getAlpha() * texture.getAlpha();
            if (alpha < 1f) {
                canvas.saveLayerAlpha(0, 0, texture.getWidth(), texture.getHeight(), (int) (255 * alpha));
            }
            texture.draw(canvas);
            canvas.restoreToCount(count);
            // while the camera restarts for a new frame its blurred last picture lies over the preview: the glass shows it too
            final View stub = camera.blurredStubView;
            if (stub != null && stub.getParent() == camera && stub.getVisibility() == View.VISIBLE && stub.getAlpha() > 0 && stub.getWidth() > 0) {
                final int c = canvas.save();
                canvas.translate(camera.getX() + stub.getLeft(), camera.getY() + stub.getTop());
                final Matrix stubMatrix = stub.getMatrix();
                if (!stubMatrix.isIdentity()) {
                    tmpMatrix.set(stubMatrix);
                    canvas.concat(tmpMatrix);
                }
                canvas.saveLayerAlpha(0, 0, stub.getWidth(), stub.getHeight(), (int) (255 * stub.getAlpha() * camera.getAlpha()));
                stub.draw(canvas);
                canvas.restoreToCount(c);
            }
        }
    }

    /** A clean dark translucent pill or circle with a hairline, for glass off. */
    static final class PlainPill extends Drawable {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();

        PlainPill(int fillColor, int strokeColor) {
            fill.setColor(fillColor);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(dpf2(0.66f));
            stroke.setColor(strokeColor);
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            rect.set(getBounds());
            final float r = Math.min(rect.width(), rect.height()) / 2f;
            canvas.drawRoundRect(rect, r, r, fill);
            if (stroke.getColor() != 0) {
                rect.inset(stroke.getStrokeWidth() / 2f, stroke.getStrokeWidth() / 2f);
                canvas.drawRoundRect(rect, r, r, stroke);
            }
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    /**
     * The light on a round control: a soft glow over its upper part fading out before the middle, and a faint
     * lower edge. The gradient is made when the size changes, never while drawing.
     */
    static final class Sheen extends Drawable {
        private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint low = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final int topColor;

        Sheen(int topColor, int lowColor) {
            this.topColor = topColor;
            low.setStyle(Paint.Style.STROKE);
            low.setStrokeWidth(dpf2(0.8f));
            low.setColor(lowColor);
        }

        @Override
        protected void onBoundsChange(@NonNull android.graphics.Rect bounds) {
            super.onBoundsChange(bounds);
            if (bounds.height() > 0) {
                glow.setShader(new android.graphics.LinearGradient(0, bounds.top, 0, bounds.top + bounds.height() * 0.55f,
                    topColor, topColor & 0x00FFFFFF, android.graphics.Shader.TileMode.CLAMP));
            }
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            rect.set(getBounds());
            if (rect.isEmpty()) {
                return;
            }
            final float r = Math.min(rect.width(), rect.height()) / 2f;
            canvas.drawRoundRect(rect, r, r, glow);
            if (low.getColor() != 0 && Math.abs(rect.width() - rect.height()) < dpf2(1f)) {
                final float inset = dpf2(1.2f);
                rect.inset(inset, inset);
                // round buttons only, the lower part: light bounced back inside the glass
                canvas.drawArc(rect, 25, 130, false, low);
            }
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
    // endregion

    // region Views

    /**
     * Full-screen layer over the preview: the frame mask and grid under the buttons, the focus ring,
     * exposure sun and timer countdown over them. Lets every touch it doesn't use through to the
     * camera (tap to focus, pinch, swipe to close).
     */
    @SuppressLint("ViewConstructor")
    public final class Overlay extends FrameLayout implements ViewTreeObserver.OnPreDrawListener {

        private final RectF preview = new RectF();
        private final RectF tapRect = new RectF();
        private final RectF frame = new RectF();
        private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint gridShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint maskPaint = new Paint();
        private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint sunPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint countPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint evPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final AnimatedFloat gridT = new AnimatedFloat(this, 0, 220, CubicBezierInterpolator.EASE_OUT_QUINT);
        private final AnimatedFloat squareT = new AnimatedFloat(this, 0, 260, CubicBezierInterpolator.EASE_OUT_QUINT);
        private final AnimatedFloat focusT = new AnimatedFloat(this, 0, 260, CubicBezierInterpolator.EASE_OUT_QUINT);
        private final AnimatedFloat dimT = new AnimatedFloat(this, 0, 400, CubicBezierInterpolator.DEFAULT);
        private final AnimatedFloat sunT = new AnimatedFloat(this, 0, 120, CubicBezierInterpolator.EASE_OUT_QUINT);
        private final AnimatedFloat countT = new AnimatedFloat(this, 0, 200, CubicBezierInterpolator.DEFAULT);

        private boolean focusShown;
        private int evIndex;
        private String evText;
        private boolean drawnSettled;
        private final RectF drawnPreview = new RectF();
        private final RectF checkRect = new RectF();
        private long focusAt;
        private float focusX, focusY;
        private long lastInteraction;
        private int lastInset = -1;
        private boolean listening;

        // exposure drag
        private boolean dragging, moved;
        private float downX, downY, startFraction;
        private long downTime;
        private float fraction;

        Overlay(Context context) {
            super(context);
            setWillNotDraw(false);
            setClipChildren(false);
            gridPaint.setStyle(Paint.Style.STROKE);
            gridPaint.setStrokeWidth(Math.max(1, dpf2(0.66f)));
            gridPaint.setColor(0x8CFFFFFF);
            gridShadowPaint.setStyle(Paint.Style.STROKE);
            gridShadowPaint.setStrokeWidth(Math.max(1, dpf2(0.66f)));
            gridShadowPaint.setColor(0x1F000000);
            maskPaint.setColor(0xE6000000);
            ringPaint.setStyle(Paint.Style.STROKE);
            ringPaint.setStrokeWidth(dpf2(1.5f));
            ringPaint.setColor(YELLOW);
            ringPaint.setShadowLayer(dpf2(1.5f), 0, 0, 0x40000000);
            sunPaint.setColor(YELLOW);
            sunPaint.setStrokeCap(Paint.Cap.ROUND);
            sunPaint.setStrokeWidth(dpf2(1.5f));
            sunPaint.setShadowLayer(dpf2(1.5f), 0, 0, 0x40000000);
            trackPaint.setStyle(Paint.Style.STROKE);
            trackPaint.setStrokeWidth(dpf2(1f));
            trackPaint.setColor(YELLOW);
            countPaint.setColor(0xFFFFFFFF);
            countPaint.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
            countPaint.setTextSize(dp(110));
            countPaint.setTextAlign(Paint.Align.CENTER);
            countPaint.setShadowLayer(dpf2(6), 0, dpf2(1), 0x55000000);
            evPaint.setColor(YELLOW);
            evPaint.setTypeface(AndroidUtilities.bold());
            evPaint.setTextSize(dp(12));
            evPaint.setShadowLayer(dpf2(2), 0, 0, 0x55000000);
        }

        // ---- layout

        @Override
        protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            final int w = right - left, h = bottom - top;
            final boolean portrait = w < h;
            final int inset = host.controlsInset();
            lastInset = inset;
            setBarOrientation(portrait);
            safeInsets(safe);
            final int barW = topBar.getMeasuredWidth(), barH = topBar.getMeasuredHeight();
            if (portrait) {
                // under the status bar and a camera cutout, with the room of a camera app's top row
                final int y = Math.max(AndroidUtilities.statusBarHeight, safe.top) + dp(8);
                topBar.layout((w - barW) / 2, y, (w + barW) / 2, y + barH);
                final int barBottom = y + barH;
                final int tw = toast.getMeasuredWidth(), th = toast.getMeasuredHeight();
                toast.layout((w - tw) / 2, barBottom + dp(10), (w + tw) / 2, barBottom + dp(10) + th);
                final int pw = zoomPill.getMeasuredWidth(), ph = zoomPill.getMeasuredHeight();
                final int pb = h - inset - dp(10);
                zoomPill.layout((w - pw) / 2, pb - ph, (w + pw) / 2, pb);
            } else {
                // beside a cutout on the left; the shutter panel takes the right
                final int x = safe.left + dp(14);
                topBar.layout(x, (h - barH) / 2, x + barW, (h + barH) / 2);
                final int tw = toast.getMeasuredWidth(), th = toast.getMeasuredHeight();
                final int ty = Math.max(safe.top, dp(4)) + dp(14);
                toast.layout((w - tw) / 2, ty, (w + tw) / 2, ty + th);
                final int pw = zoomPill.getMeasuredWidth(), ph = zoomPill.getMeasuredHeight();
                final int pr = w - inset - dp(10);
                zoomPill.layout(pr - pw, (h - ph) / 2, pr, (h + ph) / 2);
            }
        }

        private final android.graphics.Rect safe = new android.graphics.Rect();

        /** The display cutout's safe insets of this window (none before Android 9 or without a cutout). */
        private void safeInsets(android.graphics.Rect out) {
            out.setEmpty();
            if (Build.VERSION.SDK_INT >= 28) {
                final android.view.WindowInsets insets = getRootWindowInsets();
                final android.view.DisplayCutout cutout = insets != null ? insets.getDisplayCutout() : null;
                if (cutout != null) {
                    out.set(cutout.getSafeInsetLeft(), cutout.getSafeInsetTop(), cutout.getSafeInsetRight(), cutout.getSafeInsetBottom());
                }
            }
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            final int w = MeasureSpec.getSize(widthMeasureSpec), h = MeasureSpec.getSize(heightMeasureSpec);
            final boolean portrait = w < h;
            setBarOrientation(portrait);
            zoomPill.setVertical(!portrait);
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            if (!listening) {
                getViewTreeObserver().addOnPreDrawListener(this);
                listening = true;
            }
            if (active && orientationListener != null && orientationListener.canDetectOrientation()) {
                orientationListener.enable();
            }
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            if (listening) {
                getViewTreeObserver().removeOnPreDrawListener(this);
                listening = false;
            }
            cancelTimer();
            if (orientationListener != null) {
                orientationListener.disable();
            }
        }

        /** Before each frame: the glass gets the camera's current picture, the layout follows the panel. */
        @Override
        public boolean onPreDraw() {
            if (!active || getVisibility() != View.VISIBLE) {
                return true;
            }
            if (lastInset != host.controlsInset()) {
                requestLayout();
            }
            zoomPill.setZoom(host.zoom());
            thumb.setEntry(host.lastShot(), true);
            // the grid and the frame follow the preview: drawn again once it settles, moves or changes shape
            final boolean settled = host.isSettled() && previewRect(checkRect);
            if (settled != drawnSettled || settled && !checkRect.equals(drawnPreview)) {
                invalidate();
            }
            if (glass != null && getAlpha() > 0f) {
                glass.sync(root.getWidth(), root.getHeight());
            }
            return true;
        }

        // ---- focus and exposure

        void resetFocus() {
            focusShown = false;
            dragging = false;
            focusT.set(0, true);
            invalidate();
        }

        void focusAt(float x, float y) {
            final CameraView camera = host.camera();
            if (camera == null || !active || !previewRect(tapRect) || !tapRect.contains(x, y)) {
                return; // not on the black bars around the frame
            }
            final TextureView texture = camera.getTextureView();
            final float cx = x - camera.getX(), cy = y - camera.getY();
            // the camera maps the point through the preview's own size and place; the metering area
            // (1.5 x 96dp) has to stay inside the picture or the camera refuses it
            final int tw = texture.getWidth(), th = texture.getHeight(), margin = dp(72);
            if (tw <= margin * 2 || th <= margin * 2) {
                return;
            }
            final int tx = (int) Math.max(margin, Math.min(tw - margin, cx - texture.getLeft()));
            final int ty = (int) Math.max(margin, Math.min(th - margin, cy - texture.getTop()));
            camera.focusToPoint(0, tx, ty, (int) cx, (int) cy, false);
            final CameraSession session = session();
            if (session != null) {
                session.setExposureIndex(0); // a new point meters anew, as camera apps do
            }
            fraction = 0;
            focusX = x;
            focusY = y;
            focusShown = true;
            focusAt = lastInteraction = SystemClock.uptimeMillis();
            focusT.set(0, true);
            sunT.set(0, true);
            haptic(this);
            invalidate();
        }

        private boolean exposureShown() {
            final CameraSession session = session();
            return focusShown && session != null && session.isExposureSupported();
        }

        private float sliderX() {
            final float x = focusX + dp(52);
            return x + dp(24) > getWidth() ? focusX - dp(52) : x;
        }

        private float sliderHalf() {
            return dp(70);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            final float x = event.getX(), y = event.getY();
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    if (!active || !exposureShown() || event.getPointerCount() > 1) {
                        return false;
                    }
                    final boolean onSlider = Math.abs(x - sliderX()) < dp(32) && Math.abs(y - focusY) < sliderHalf() + dp(24);
                    final boolean onRing = Math.hypot(x - focusX, y - focusY) < dp(44);
                    if (!onSlider && !onRing) {
                        return false;
                    }
                    dragging = true;
                    moved = false;
                    downX = x;
                    downY = y;
                    downTime = SystemClock.uptimeMillis();
                    startFraction = fraction;
                    lastInteraction = downTime;
                    invalidate();
                    return true;
                }
                case MotionEvent.ACTION_MOVE: {
                    if (!dragging) {
                        return false;
                    }
                    if (!moved && Math.abs(y - downY) > ViewConfiguration.get(getContext()).getScaledTouchSlop()) {
                        moved = true;
                        downY = y;
                    }
                    if (moved) {
                        setFraction(startFraction - (y - downY) / (sliderHalf() * 1.6f));
                    }
                    lastInteraction = SystemClock.uptimeMillis();
                    invalidate();
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    if (!dragging) {
                        return false;
                    }
                    dragging = false;
                    if (!moved && event.getActionMasked() == MotionEvent.ACTION_UP && SystemClock.uptimeMillis() - downTime < ViewConfiguration.getLongPressTimeout()
                            && Math.hypot(x - downX, y - downY) < dp(12)) {
                        focusAt(x, y);
                    }
                    lastInteraction = SystemClock.uptimeMillis();
                    invalidate();
                    return true;
                }
            }
            return dragging;
        }

        private void setFraction(float value) {
            final CameraSession session = session();
            if (session == null) {
                return;
            }
            value = Math.max(-1f, Math.min(1f, value));
            final int index = Math.round(value > 0 ? value * session.getMaxExposure() : -value * session.getMinExposure());
            final int before = session.getExposureIndex();
            fraction = value;
            if (index != before) {
                session.setExposureIndex(index);
                if (index == 0 || index == session.getMaxExposure() || index == session.getMinExposure()) {
                    try {
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    } catch (Exception ignore) {
                    }
                }
            }
        }

        // ---- drawing

        @Override
        protected void dispatchDraw(Canvas canvas) {
            final boolean settled = active && host.isSettled() && previewRect(preview);
            drawnSettled = settled;
            if (settled) {
                drawnPreview.set(preview);
            }
            final float square = squareT.set(settled && isSquare() ? 1 : 0);
            final float grid = gridT.set(settled && SolegramConfig.cameraGrid ? 1 : 0);
            if (settled || square > 0 || grid > 0) {
                frame.set(preview);
                if (square > 0) {
                    final float side = Math.min(preview.width(), preview.height());
                    final float dx = (preview.width() - side) / 2f * square, dy = (preview.height() - side) / 2f * square;
                    frame.inset(dx, dy);
                    maskPaint.setAlpha((int) (0xE6 * square));
                    canvas.drawRect(preview.left, preview.top, preview.right, frame.top, maskPaint);
                    canvas.drawRect(preview.left, frame.bottom, preview.right, preview.bottom, maskPaint);
                    canvas.drawRect(preview.left, frame.top, frame.left, frame.bottom, maskPaint);
                    canvas.drawRect(frame.right, frame.top, preview.right, frame.bottom, maskPaint);
                }
                if (grid > 0) {
                    gridPaint.setAlpha((int) (0x8C * grid));
                    gridShadowPaint.setAlpha((int) (0x1F * grid));
                    for (int i = 1; i <= 2; i++) {
                        final float gx = frame.left + frame.width() * i / 3f, gy = frame.top + frame.height() * i / 3f;
                        canvas.drawLine(gx + 1, frame.top, gx + 1, frame.bottom, gridShadowPaint);
                        canvas.drawLine(frame.left, gy + 1, frame.right, gy + 1, gridShadowPaint);
                        canvas.drawLine(gx, frame.top, gx, frame.bottom, gridPaint);
                        canvas.drawLine(frame.left, gy, frame.right, gy, gridPaint);
                    }
                }
            }
            super.dispatchDraw(canvas);
            if (active) {
                drawFocus(canvas);
                drawCountdown(canvas, settled);
            }
        }

        /** Where the preview is on the screen (overlay coordinates); false when there is none to show. */
        private boolean previewRect(RectF out) {
            final CameraView camera = host.camera();
            if (camera == null || camera.getParent() != root) {
                return false;
            }
            final TextureView texture = camera.getTextureView();
            if (texture == null || texture.getWidth() <= 0 || texture.getHeight() <= 0) {
                return false;
            }
            final float x = camera.getX() - getX(), y = camera.getY() - getY();
            out.set(texture.getLeft(), texture.getTop(), texture.getRight(), texture.getBottom());
            if (!out.intersect(0, 0, camera.getWidth(), camera.getHeight())) {
                return false;
            }
            out.offset(x, y);
            return true;
        }

        private void drawFocus(Canvas canvas) {
            final long now = SystemClock.uptimeMillis();
            final boolean idle = !dragging && now - lastInteraction > 2600;
            final float shown = focusT.set(focusShown && !idle ? 1 : 0);
            if (shown <= 0) {
                if (focusShown && idle) {
                    focusShown = false;
                }
                return;
            }
            if (!idle) {
                invalidate(); // to notice when it goes idle
            }
            final float dim = dimT.set(!dragging && now - lastInteraction > 900 ? 1 : 0);
            final float alpha = shown * (1f - 0.45f * dim);
            // the ring lands from a bigger size and settles, as on a phone's camera
            final float settle = Math.min(1f, (now - focusAt) / 320f);
            if (settle < 1f) {
                invalidate();
            }
            final float scale = 1f + 0.35f * (1f - CubicBezierInterpolator.EASE_OUT_QUINT.getInterpolation(settle));
            ringPaint.setAlpha((int) (255 * alpha));
            canvas.drawCircle(focusX, focusY, dp(34) * scale, ringPaint);

            if (!exposureShown()) {
                return;
            }
            final float sx = sliderX(), half = sliderHalf();
            final float sun = sunT.set(-fraction);
            final float sy = focusY + sun * half;
            trackPaint.setAlpha((int) (255 * alpha * (0.35f + 0.65f * (dragging ? 1 : 0))));
            final float gap = dp(13);
            if (sy - gap > focusY - half) {
                canvas.drawLine(sx, focusY - half, sx, sy - gap, trackPaint);
            }
            if (sy + gap < focusY + half) {
                canvas.drawLine(sx, sy + gap, sx, focusY + half, trackPaint);
            }
            // the sun: a disc and eight rays
            sunPaint.setAlpha((int) (255 * alpha));
            sunPaint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(sx, sy, dpf2(4.5f), sunPaint);
            sunPaint.setStyle(Paint.Style.STROKE);
            final float r1 = dpf2(7f), r2 = dpf2(9.5f);
            for (int i = 0; i < 8; i++) {
                final double a = Math.PI / 4 * i;
                final float cos = (float) Math.cos(a), sin = (float) Math.sin(a);
                canvas.drawLine(sx + cos * r1, sy + sin * r1, sx + cos * r2, sy + sin * r2, sunPaint);
            }
            final CameraSession session = session();
            if (dragging && moved && session != null) {
                final int index = session.getExposureIndex();
                if (index != evIndex || evText == null) {
                    evIndex = index;
                    final float ev = index * session.getExposureStep();
                    evText = index == 0 ? "0" : String.format(java.util.Locale.US, "%+.1f", ev);
                }
                final String text = evText;
                evPaint.setAlpha((int) (255 * alpha));
                final float tw = evPaint.measureText(text);
                final float tx = sx > focusX ? sx + dp(14) : sx - dp(14) - tw;
                canvas.drawText(text, tx, sy + dp(4), evPaint);
            }
        }

        private void drawCountdown(Canvas canvas, boolean settled) {
            final float shown = countT.set(timerAction != null ? 1 : 0);
            if (shown <= 0 || timerLeft <= 0) {
                return;
            }
            final float t = Math.min(1f, (SystemClock.uptimeMillis() - timerTickAt) / 1000f);
            invalidate();
            // each second lands big and fades towards the next
            final float scale = 1.25f - 0.25f * CubicBezierInterpolator.EASE_OUT_QUINT.getInterpolation(Math.min(1f, t * 2.5f));
            final float alpha = shown * (1f - 0.55f * Math.max(0f, (t - 0.55f) / 0.45f));
            final float cx = settled ? frame.centerX() : getWidth() / 2f, cy = settled ? frame.centerY() : getHeight() / 2f;
            countPaint.setAlpha((int) (255 * alpha));
            canvas.save();
            canvas.scale(scale, scale, cx, cy);
            canvas.rotate(iconRotation, cx, cy);
            canvas.drawText(Integer.toString(timerLeft), cx, cy - (countPaint.descent() + countPaint.ascent()) / 2f, countPaint);
            canvas.restore();
        }
    }

    /**
     * A round control of the top row; draws its own icon so it can turn with the phone. Every glyph is drawn in a
     * 24-unit box with one line weight ({@link #STROKE}); "off" is a slash cut out of the glyph, as on iOS.
     */
    @SuppressLint("ViewConstructor")
    private final class CamButton extends View {
        private final int type;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint cutPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF rect = new RectF();
        private final AnimatedFloat activeT = new AnimatedFloat(this, 0, 220, CubicBezierInterpolator.EASE_OUT_QUINT);
        private float rotation;
        private android.animation.ValueAnimator rotationAnimator;

        CamButton(Context context, int type) {
            super(context);
            this.type = type;
            setBackground(background(this, dp(BUTTON) / 2));
            ScaleStateListAnimator.apply(this, .12f, 1.5f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            // a faint shadow keeps the white glyphs apart from a bright scene behind the glass
            paint.setShadowLayer(dpf2(1.2f), 0, 0, 0x30000000);
            cutPaint.setStyle(Paint.Style.STROKE);
            cutPaint.setStrokeCap(Paint.Cap.ROUND);
            cutPaint.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_OUT));
            textPaint.setTypeface(AndroidUtilities.bold());
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setShadowLayer(dpf2(1.2f), 0, 0, 0x30000000);
        }

        void rotateIcon(float target) {
            if (rotationAnimator != null) {
                rotationAnimator.cancel();
            }
            if (!animations()) {
                rotation = target;
                invalidate();
                return;
            }
            rotationAnimator = android.animation.ValueAnimator.ofFloat(rotation, target);
            rotationAnimator.addUpdateListener(a -> {
                rotation = (float) a.getAnimatedValue();
                invalidate();
            });
            rotationAnimator.setDuration(300);
            rotationAnimator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
            rotationAnimator.start();
        }

        private boolean isOn() {
            switch (type) {
                case FLASH: {
                    final CameraSession session = session();
                    final String mode = session != null ? session.getCurrentFlashMode() : null;
                    return Camera.Parameters.FLASH_MODE_ON.equals(mode) || Camera.Parameters.FLASH_MODE_TORCH.equals(mode);
                }
                case HDR:
                    return SolegramConfig.cameraEnhance;
                case TIMER:
                    return SolegramConfig.cameraTimer > 0;
                case GRID:
                    return SolegramConfig.cameraGrid;
                default:
                    return false;
            }
        }

        /** How much of the "off" slash shows: the flash when off, HDR and the grid fading with their state. */
        private float slash(float on) {
            switch (type) {
                case FLASH: {
                    final CameraSession session = session();
                    final String mode = session != null ? session.getCurrentFlashMode() : Camera.Parameters.FLASH_MODE_OFF;
                    return Camera.Parameters.FLASH_MODE_OFF.equals(mode) || mode == null ? 1f : 0f;
                }
                case HDR:
                case GRID:
                    return 1f - on;
                default:
                    return 0f;
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final float on = activeT.set(isOn() ? 1 : 0);
            final int color = ColorUtils.blendARGB(0xFFFFFFFF, YELLOW, on);
            final float cx = getWidth() / 2f, cy = getHeight() / 2f;
            canvas.save();
            canvas.rotate(rotation, cx, cy);
            // icons are drawn in a 24-unit box centred in the button
            final float u = dpf2(1f);
            canvas.translate(cx - 12 * u, cy - 12 * u);
            final float slash = slash(on);
            // the slash is cut out of the glyph: drawn in a layer of the icon's own small box
            final int layer = slash > 0f ? canvas.saveLayer(0, 0, 24 * u, 24 * u, null) : -1;
            paint.setColor(color);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(STROKE * u);
            textPaint.setColor(color);
            textPaint.setLetterSpacing(0f);
            switch (type) {
                case FLASH:
                    drawFlash(canvas, u, color);
                    break;
                case HDR: {
                    // a kerned label; on, a frame closes around it
                    textPaint.setTextSize(9f * u);
                    textPaint.setLetterSpacing(0.05f);
                    canvas.drawText("HDR", 12 * u + 0.3f * u, 12 * u - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint);
                    if (on > 0f) {
                        paint.setStrokeWidth(STROKE_THIN * u);
                        paint.setAlpha((int) (255 * on));
                        final float grow = 0.85f + 0.15f * on;
                        rect.set(12 * u - 11.5f * u * grow, 12 * u - 7.5f * u * grow, 12 * u + 11.5f * u * grow, 12 * u + 7.5f * u * grow);
                        canvas.drawRoundRect(rect, 3.5f * u, 3.5f * u, paint);
                    }
                    break;
                }
                case TIMER:
                    drawTimer(canvas, u, color, on);
                    break;
                case ASPECT: {
                    if (SolegramConfig.cameraAspect == SolegramConfig.CAMERA_ASPECT_FULL) {
                        // full screen: the four corners of the screen, opened out
                        final float l = 5f, t = 4.5f, r = 19f, b = 19.5f, k = 4.5f;
                        path.rewind();
                        path.moveTo(l * u, (t + k) * u);
                        path.lineTo(l * u, t * u);
                        path.lineTo((l + k) * u, t * u);
                        path.moveTo((r - k) * u, t * u);
                        path.lineTo(r * u, t * u);
                        path.lineTo(r * u, (t + k) * u);
                        path.moveTo(r * u, (b - k) * u);
                        path.lineTo(r * u, b * u);
                        path.lineTo((r - k) * u, b * u);
                        path.moveTo((l + k) * u, b * u);
                        path.lineTo(l * u, b * u);
                        path.lineTo(l * u, (b - k) * u);
                        canvas.drawPath(path, paint);
                        break;
                    }
                    final String text = aspectName(SolegramConfig.cameraAspect);
                    textPaint.setTextSize((text.length() > 3 ? 8f : 8.5f) * u);
                    paint.setStrokeWidth(STROKE_THIN * u);
                    final float w = SolegramConfig.cameraAspect == SolegramConfig.CAMERA_ASPECT_1_1 ? 15 : SolegramConfig.cameraAspect == SolegramConfig.CAMERA_ASPECT_16_9 ? 21 : 19;
                    final float h = SolegramConfig.cameraAspect == SolegramConfig.CAMERA_ASPECT_1_1 ? 15 : SolegramConfig.cameraAspect == SolegramConfig.CAMERA_ASPECT_16_9 ? 13 : 15;
                    rect.set((24 - w) / 2f * u, (24 - h) / 2f * u, (24 + w) / 2f * u, (24 + h) / 2f * u);
                    canvas.drawRoundRect(rect, 3f * u, 3f * u, paint);
                    canvas.drawText(text, 12 * u, 12 * u - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint);
                    break;
                }
                case GRID: {
                    rect.set(4.5f * u, 4.5f * u, 19.5f * u, 19.5f * u);
                    canvas.drawRoundRect(rect, 3f * u, 3f * u, paint);
                    paint.setStrokeWidth(STROKE_THIN * u);
                    for (int i = 1; i <= 2; i++) {
                        final float p = (4.5f + 15 * i / 3f) * u;
                        canvas.drawLine(p, 4.5f * u, p, 19.5f * u, paint);
                        canvas.drawLine(4.5f * u, p, 19.5f * u, p, paint);
                    }
                    break;
                }
            }
            if (layer != -1) {
                drawSlash(canvas, u, slash);
                canvas.restoreToCount(layer);
            }
            canvas.restore();
        }

        /** The "off" slash: a clear gap cut along it, then the line in white. */
        private void drawSlash(Canvas canvas, float u, float alpha) {
            cutPaint.setStrokeWidth((STROKE + 2.4f) * u);
            cutPaint.setAlpha((int) (255 * alpha));
            canvas.drawLine(4.5f * u, 4.5f * u, 19.5f * u, 19.5f * u, cutPaint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(STROKE * u);
            paint.setColor(ColorUtils.setAlphaComponent(0xFFFFFFFF, (int) (255 * alpha)));
            canvas.drawLine(4.5f * u, 4.5f * u, 19.5f * u, 19.5f * u, paint);
        }

        private void drawFlash(Canvas canvas, float u, int color) {
            final CameraSession session = session();
            final String mode = session != null ? session.getCurrentFlashMode() : Camera.Parameters.FLASH_MODE_OFF;
            final boolean torch = Camera.Parameters.FLASH_MODE_TORCH.equals(mode);
            if (torch) {
                // a flashlight with its beam
                path.rewind();
                path.moveTo(9 * u, 10 * u);
                path.lineTo(15 * u, 10 * u);
                path.lineTo(14 * u, 13 * u);
                path.lineTo(14 * u, 20 * u);
                path.quadTo(14 * u, 21 * u, 13 * u, 21 * u);
                path.lineTo(11 * u, 21 * u);
                path.quadTo(10 * u, 21 * u, 10 * u, 20 * u);
                path.lineTo(10 * u, 13 * u);
                path.close();
                paint.setStyle(Paint.Style.FILL_AND_STROKE);
                paint.setStrokeWidth(0.8f * u);
                canvas.drawPath(path, paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(STROKE * u);
                canvas.drawLine(12 * u, 3 * u, 12 * u, 6 * u, paint);
                canvas.drawLine(7 * u, 5 * u, 8.6f * u, 7.4f * u, paint);
                canvas.drawLine(17 * u, 5 * u, 15.4f * u, 7.4f * u, paint);
                return;
            }
            final boolean on = Camera.Parameters.FLASH_MODE_ON.equals(mode);
            final boolean auto = Camera.Parameters.FLASH_MODE_AUTO.equals(mode);
            path.rewind();
            path.moveTo(13.5f * u, 3 * u);
            path.lineTo(6 * u, 13.5f * u);
            path.lineTo(11.3f * u, 13.5f * u);
            path.lineTo(10.2f * u, 21 * u);
            path.lineTo(18 * u, 10.2f * u);
            path.lineTo(12.6f * u, 10.2f * u);
            path.close();
            paint.setStyle(on ? Paint.Style.FILL_AND_STROKE : Paint.Style.STROKE);
            paint.setStrokeWidth((on ? 1f : STROKE) * u);
            canvas.drawPath(path, paint);
            if (auto) {
                // "A" on a small dark disc, so it reads where it meets the bolt
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(0xFFFFFFFF);
                canvas.drawCircle(19.5f * u, 19.5f * u, 4.6f * u, paint);
                textPaint.setTextSize(7f * u);
                textPaint.setColor(0xFF1C1C1E);
                canvas.drawText("A", 19.5f * u, 19.5f * u - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint);
                paint.setColor(color);
            }
        }

        private void drawTimer(Canvas canvas, float u, int color, float on) {
            final float r = 7.5f;
            canvas.drawCircle(12 * u, 13.5f * u, r * u, paint);
            canvas.drawLine(10 * u, 3.4f * u, 14 * u, 3.4f * u, paint);
            canvas.drawLine(12 * u, 13.5f * u, 12 * u, 9.5f * u, paint);
            canvas.drawLine(18.2f * u, 6.7f * u, 19.5f * u, 5.4f * u, paint);
            if (SolegramConfig.cameraTimer > 0) {
                // the seconds on a yellow tag over the dial's corner
                final String text = Integer.toString(SolegramConfig.cameraTimer);
                textPaint.setTextSize(7.5f * u);
                final float tw = textPaint.measureText(text);
                rect.set(19.5f * u - tw / 2f - 2.6f * u, 15.6f * u, 19.5f * u + tw / 2f + 2.6f * u, 23.2f * u);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(ColorUtils.setAlphaComponent(YELLOW, (int) (255 * on)));
                canvas.drawRoundRect(rect, 3.8f * u, 3.8f * u, paint);
                textPaint.setColor(ColorUtils.setAlphaComponent(0xFF000000, (int) (255 * on)));
                canvas.drawText(text, 19.5f * u, rect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint);
                paint.setColor(color);
            }
        }
    }

    /**
     * Zoom steps over the shutter (1x, 2x when the camera reaches it), as on iOS: each step a small dark circle in
     * the glass capsule, the current one a bigger circle gliding between them with its exact zoom in yellow ("1.4×").
     */
    @SuppressLint("ViewConstructor")
    private final class ZoomPill extends View {
        private final float[] presets = {1f, 2f};
        private int count = 1;
        private float maxRatio = 1f;
        private float zoom;
        private boolean vertical;
        private float textRotation;
        private final Paint chipPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final AnimatedFloat selectT = new AnimatedFloat(this, 0, 260, CubicBezierInterpolator.EASE_OUT_QUINT);
        private android.animation.ValueAnimator zoomAnimator;
        private final StringBuilder label = new StringBuilder(8);

        private static final int CHIP = 34, DOT = 26, PAD = 4, GAP = 6;

        ZoomPill(Context context) {
            super(context);
            setBackground(background(this, dp(CHIP + PAD * 2) / 2));
            chipPaint.setColor(0x8F000000);
            dotPaint.setColor(0x47000000);
            textPaint.setTypeface(AndroidUtilities.bold());
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setTextSize(dp(12));
            textPaint.setShadowLayer(dpf2(1f), 0, 0, 0x30000000);
            setVisibility(View.GONE);
        }

        void setVertical(boolean value) {
            if (vertical != value) {
                vertical = value;
                requestLayout();
            }
        }

        void rotateText(float target) {
            textRotation = target;
            invalidate();
        }

        void setMaxRatio(float max) {
            maxRatio = max;
            int n = 0;
            for (float preset : presets) {
                if (preset <= max + 0.01f) {
                    n++;
                }
            }
            if (count != n) {
                count = n;
                requestLayout();
            }
            // shown wherever the camera zooms: the steps it reaches, the exact zoom while pinching
            setVisibility(max > 1.05f ? View.VISIBLE : View.GONE);
            invalidate();
        }

        void setZoom(float value) {
            if (zoom != value) {
                zoom = value;
                invalidate();
            }
        }

        private float ratio() {
            final CameraSession session = session();
            return session != null ? session.zoomRatio(zoom) : 1f;
        }

        private int selected(float ratio) {
            int index = 0;
            for (int i = 0; i < count; i++) {
                if (ratio >= presets[i] - 0.01f) {
                    index = i;
                }
            }
            return index;
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            final int along = dp(CHIP) * count + dp(PAD) * 2 + dp(GAP) * (count - 1);
            final int across = dp(CHIP + PAD * 2);
            setMeasuredDimension(vertical ? across : along, vertical ? along : across);
        }

        private float chipCenter(int i) {
            return dp(PAD) + dp(CHIP) / 2f + i * (dp(CHIP) + dp(GAP));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final float ratio = ratio();
            final int sel = selected(ratio);
            final float pos = selectT.set(sel);
            final float across = (vertical ? getWidth() : getHeight()) / 2f;
            // the small circles of the steps, fading where the big one lies over them
            for (int i = 0; i < count; i++) {
                final float covered = Math.max(0f, 1f - Math.abs(pos - i));
                dotPaint.setAlpha((int) (0x47 * (1f - covered)));
                final float c = chipCenter(i);
                canvas.drawCircle(vertical ? across : c, vertical ? c : across, dp(DOT) / 2f, dotPaint);
            }
            // the current step's circle glides to it
            final float c = AndroidUtilities.lerp(chipCenter(0), chipCenter(Math.max(0, count - 1)), count > 1 ? pos / (count - 1) : 0);
            canvas.drawCircle(vertical ? across : c, vertical ? c : across, dp(CHIP) / 2f, chipPaint);
            for (int i = 0; i < count; i++) {
                final boolean current = i == sel;
                label.setLength(0);
                final float value = current ? ratio : presets[i];
                if (Math.abs(value - Math.round(value)) < 0.05f) {
                    label.append(Math.round(value));
                } else {
                    label.append(Math.round(value * 10) / 10f);
                }
                if (current) {
                    label.append('×');
                }
                textPaint.setColor(current ? YELLOW : 0xFFFFFFFF);
                textPaint.setTextSize(current ? dp(13) : dp(11.5f));
                final float x = vertical ? across : chipCenter(i), y = vertical ? chipCenter(i) : across;
                canvas.save();
                canvas.rotate(textRotation, x, y);
                canvas.drawText(label, 0, label.length(), x, y - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint);
                canvas.restore();
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                return true;
            }
            if (event.getAction() != MotionEvent.ACTION_UP) {
                return true;
            }
            final float along = vertical ? event.getY() : event.getX();
            int index = (int) ((along - dp(PAD)) / (dp(CHIP) + dp(GAP)));
            index = Math.max(0, Math.min(count - 1, index));
            final CameraSession session = session();
            if (session == null) {
                return true;
            }
            final float target = session.zoomForRatio(presets[index]);
            haptic(this);
            animateZoom(target);
            return true;
        }

        void stopZoom() {
            if (zoomAnimator != null) {
                zoomAnimator.cancel();
                zoomAnimator = null;
            }
        }

        private void animateZoom(float target) {
            if (zoomAnimator != null) {
                zoomAnimator.cancel();
            }
            if (!animations()) {
                host.setZoom(target);
                return;
            }
            zoomAnimator = android.animation.ValueAnimator.ofFloat(zoom, target);
            zoomAnimator.addUpdateListener(a -> host.setZoom((float) a.getAnimatedValue()));
            zoomAnimator.setDuration(260);
            zoomAnimator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
            zoomAnimator.start();
        }
    }

    /** The newest shot beside the shutter; pops in when one is added. */
    @SuppressLint("ViewConstructor")
    public final class ThumbView extends FrameLayout {
        private final BackupImageView image;
        private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private int entryId;

        ThumbView(Context context) {
            super(context);
            setWillNotDraw(false);
            image = new BackupImageView(context);
            image.setRoundRadius(dp(10));
            addView(image, LayoutHelper.createFrame(44, 44, Gravity.CENTER));
            border.setStyle(Paint.Style.STROKE);
            border.setStrokeWidth(dpf2(1.5f));
            border.setColor(0xE6FFFFFF);
            ScaleStateListAnimator.apply(this, .1f, 1.5f);
        }

        void setEntry(@Nullable MediaController.PhotoEntry entry, boolean animated) {
            if (entry == null) {
                entryId = 0;
                setVisibility(View.INVISIBLE);
                return;
            }
            if (entry.imageId == entryId && getVisibility() == View.VISIBLE) {
                return;
            }
            entryId = entry.imageId;
            if (entry.thumbPath != null) {
                image.setOrientation(0, true);
                image.setImage(entry.thumbPath, null, (Drawable) null);
            } else {
                image.setOrientation(entry.orientation, entry.invert, true);
                image.setImage("thumb://" + entry.imageId + ":" + entry.path, null, (Drawable) null);
            }
            setVisibility(View.VISIBLE);
            if (animated && animations()) {
                setScaleX(0.4f);
                setScaleY(0.4f);
                animate().scaleX(1f).scaleY(1f).setDuration(380).setInterpolator(new android.view.animation.OvershootInterpolator(1.6f)).start();
            }
        }

        @Override
        protected void dispatchDraw(Canvas canvas) {
            super.dispatchDraw(canvas);
            final float half = dp(44) / 2f;
            rect.set(getWidth() / 2f - half, getHeight() / 2f - half, getWidth() / 2f + half, getHeight() / 2f + half);
            canvas.drawRoundRect(rect, dp(10), dp(10), border);
        }
    }

    /**
     * Photo / video under the shutter (beside it, read upwards, in landscape): tap a word or swipe;
     * the current one sits in the middle and the words glide on a spring.
     */
    @SuppressLint("ViewConstructor")
    public final class ModeSwitch extends View {
        private final TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final String[] labels;
        private final float[] widths = new float[2];
        private final androidx.dynamicanimation.animation.FloatValueHolder position = new androidx.dynamicanimation.animation.FloatValueHolder(0);
        private final androidx.dynamicanimation.animation.SpringAnimation spring;
        private float downX, downY;
        private boolean tracking;
        private boolean vertical;

        ModeSwitch(Context context) {
            super(context);
            // the iOS camera's mode words: small caps, spaced out, the current one yellow
            paint.setTypeface(AndroidUtilities.bold());
            paint.setTextSize(dp(12.5f));
            paint.setLetterSpacing(0.09f);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setShadowLayer(dpf2(2), 0, 0, 0x40000000);
            labels = new String[] {
                LocaleController.getString(R.string.SG_CamModePhoto),
                LocaleController.getString(R.string.SG_CamModeVideo)
            };
            for (int i = 0; i < 2; i++) {
                widths[i] = paint.measureText(labels[i]);
            }
            spring = new androidx.dynamicanimation.animation.SpringAnimation(position);
            spring.setSpring(new androidx.dynamicanimation.animation.SpringForce(0)
                .setStiffness(650f)
                .setDampingRatio(0.78f));
            spring.setMinimumVisibleChange(0.002f);
            spring.addUpdateListener((animation, value, velocity) -> invalidate());
        }

        /** Landscape: the words run upwards beside the shutter. */
        public void setVertical(boolean value) {
            if (vertical != value) {
                vertical = value;
                invalidate();
            }
        }

        /** The mode changed: the words glide there (or jump, with animations off or while hidden). */
        void sync(boolean animated) {
            final float target = videoMode ? 1 : 0;
            if (animated && animations() && isAttachedToWindow() && getVisibility() == View.VISIBLE) {
                spring.animateToFinalPosition(target);
            } else {
                spring.cancel();
                position.setValue(target);
                invalidate();
            }
        }

        private float spacing() {
            return (widths[0] + widths[1]) / 2f + dp(24);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final float t = Math.max(-0.2f, Math.min(1.2f, position.getValue()));
            canvas.save();
            if (vertical) {
                // drawn as across, turned so it reads upwards: "along" is up the panel
                canvas.rotate(-90, getWidth() / 2f, getHeight() / 2f);
            }
            final float cx = getWidth() / 2f - t * spacing();
            final float cy = getHeight() / 2f - (paint.descent() + paint.ascent()) / 2f;
            for (int i = 0; i < 2; i++) {
                final float selected = Math.max(0f, Math.min(1f, i == 0 ? 1f - t : t));
                paint.setColor(ColorUtils.blendARGB(0xFFFFFFFF, YELLOW, selected));
                canvas.drawText(labels[i], cx + i * spacing(), cy, paint);
            }
            canvas.restore();
        }

        // position of a touch along the words (to the right across, upwards in landscape)
        private float along(MotionEvent event) {
            return vertical ? getHeight() - event.getY() : event.getX();
        }

        private float across(MotionEvent event) {
            return vertical ? event.getX() : event.getY();
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN && (!host.isVideoAllowed() || recording || isTimerRunning())) {
                return false;
            }
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = along(event);
                    downY = across(event);
                    tracking = true;
                    // a swipe here is the mode's: the sheet and the camera's own drags keep out of it
                    if (getParent() != null) {
                        getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (tracking && Math.abs(along(event) - downX) > dp(36)) {
                        // the words follow the finger: a swipe towards the start brings the next mode in
                        setVideoMode(along(event) < downX);
                        tracking = false;
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (tracking && Math.abs(along(event) - downX) < dp(10) && Math.abs(across(event) - downY) < dp(10)) {
                        // a tap on the word before or after the middle one
                        final float mid = (vertical ? getHeight() : getWidth()) / 2f;
                        if (Math.abs(along(event) - mid) > dp(24)) {
                            setVideoMode(along(event) > mid);
                        }
                    }
                    tracking = false;
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    tracking = false;
                    return true;
            }
            return true;
        }
    }
    // endregion
}
