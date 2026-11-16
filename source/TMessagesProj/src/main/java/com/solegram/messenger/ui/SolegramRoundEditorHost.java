/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.ui;

import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.solegram.messenger.SolegramConfig;
import com.solegram.messenger.SolegramRoundFx;
import com.solegram.messenger.SolegramVoiceEffects;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.VideoEditedInfo;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.telegram.messenger.AndroidUtilities.dp;

/**
 * The round video editor inside a recorder (Telegram's new one): the panel under the circle, the mask over the
 * playing preview, the filter on it, the voice effect on its sound; and what of it goes into the sent video.
 */
public final class SolegramRoundEditorHost {

    /** The recorder's preview player. */
    public interface Player {
        /** The playing position, ms (-1 when nothing plays). */
        long positionMs();

        /** Plays another file from the same moment, with sound (to hear the voice effect). */
        void play(File file);
    }

    public interface ShiftListener {
        /** The circle moves up by this (negative) amount to make room for the panel. */
        void onShift(float shift);
    }

    private final FrameLayout root;
    private final ViewGroup previewParent;
    private final View circle;
    private final TextureView texture;
    private final Theme.ResourcesProvider resourcesProvider;
    private final Player player;
    private final ShiftListener shiftListener;
    private final SolegramRoundMasks.Sprites sprites = new SolegramRoundMasks.Sprites();

    private SolegramRoundEditor.Panel panel;
    private SolegramRoundEditor.Overlay overlay;
    private int mask, filter, voice;
    private File source;
    private File voiceFile;
    private SolegramRoundFx.FaceTrack track;
    private AtomicBoolean cancel;
    private float shift;
    private ValueAnimator shiftAnimator;
    private int voiceRequest;
    /** told when the panel changes (the input's glass refracts it) */
    private Runnable onChanged;

    public void setOnChanged(Runnable onChanged) {
        this.onChanged = onChanged;
        if (panel != null) {
            panel.onChanged = onChanged;
        }
    }

    /** The panel while it is shown, else null. */
    public View getPanel() {
        return panel != null && panel.getVisibility() == View.VISIBLE && panel.getAlpha() > 0.01f ? panel : null;
    }

    private void notifyChanged() {
        if (onChanged != null) {
            onChanged.run();
        }
    }

    /**
     * @param root          the recorder's view: the panel goes to its bottom (above its bottom padding)
     * @param previewParent where the preview's picture is: the mask's layer goes over it, as big as it
     * @param circle        the circle that moves up for the panel
     */
    public SolegramRoundEditorHost(FrameLayout root, ViewGroup previewParent, View circle, TextureView texture,
                                   Theme.ResourcesProvider resourcesProvider, Player player, ShiftListener shiftListener) {
        this.root = root;
        this.previewParent = previewParent;
        this.circle = circle;
        this.texture = texture;
        this.resourcesProvider = resourcesProvider;
        this.player = player;
        this.shiftListener = shiftListener;
    }

    public static boolean enabled() {
        return SolegramConfig.roundEditor;
    }

    public boolean isActive() {
        return panel != null;
    }

    public float getShift() {
        return shift;
    }

    /** The recording is shown for a check: the editor opens for it. */
    public void open(File file) {
        close(true);
        if (!enabled() || file == null || !file.exists()) {
            return;
        }
        source = file;
        mask = SolegramRoundMasks.NONE;
        filter = SolegramRoundFx.F_NONE;
        voice = SolegramVoiceEffects.NONE;
        track = null;

        overlay = new SolegramRoundEditor.Overlay(root.getContext(), sprites);
        overlay.setClock(player::positionMs);
        previewParent.addView(overlay, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        panel = new SolegramRoundEditor.Panel(root.getContext(), resourcesProvider, new SolegramRoundEditor.Callback() {
            @Override
            public void onMask(int value) {
                mask = value;
                sprites.prepare(value);
                if (overlay != null) {
                    overlay.setMask(value, track);
                }
            }

            @Override
            public void onFilter(int value) {
                filter = value;
                applyFilterPreview();
            }

            @Override
            public void onVoice(int value) {
                setVoice(value);
            }
        });
        panel.setSelected(mask, filter, voice);
        panel.setMasksState(false, false);
        panel.onChanged = onChanged;
        root.addView(panel, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM, 8, 0, 8, 8));
        panel.setAlpha(0f);
        panel.setTranslationY(dp(24));
        panel.animate().alpha(1f).translationY(0).setDuration(320).setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT)
            .setUpdateListener(a -> notifyChanged()).start();
        panel.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> updateShift(true));

        // a frame of the video for the filters' buttons
        final SolegramRoundEditor.Panel forPanel = panel;
        AndroidUtilities.runOnUIThread(() -> {
            if (panel == forPanel && texture != null) {
                try {
                    final Bitmap frame = texture.getBitmap(dp(56), dp(56));
                    if (frame != null) {
                        forPanel.setThumbnail(frame);
                    }
                } catch (Throwable ignore) {
                }
            }
        }, 500);

        // the face, for the masks
        final AtomicBoolean cancelled = cancel = new AtomicBoolean();
        final String path = file.getAbsolutePath();
        final android.content.Context context = root.getContext().getApplicationContext();
        new Thread(() -> {
            final SolegramRoundFx.FaceTrack found = SolegramRoundFx.trackFaces(context, path, cancelled);
            AndroidUtilities.runOnUIThread(() -> {
                if (cancelled.get() || panel != forPanel || found == null) {
                    return;
                }
                track = found;
                panel.setMasksState(true, !found.hasFace());
                if (overlay != null) {
                    overlay.setMask(mask, found);
                }
            });
        }, "SolegramRoundFaces").start();
    }

    private void applyFilterPreview() {
        if (texture != null) {
            if (filter == SolegramRoundFx.F_NONE) {
                texture.setLayerPaint(null);
            } else {
                final Paint p = new Paint();
                p.setColorFilter(new ColorMatrixColorFilter(SolegramRoundFx.previewMatrix(filter)));
                texture.setLayerPaint(p);
            }
        }
        if (overlay != null) {
            overlay.setVignette(SolegramRoundFx.previewVignette(filter));
        }
    }

    private void setVoice(int effect) {
        if (panel == null || source == null || effect == voice) {
            return;
        }
        final int request = ++voiceRequest;
        if (effect == SolegramVoiceEffects.NONE) {
            voice = effect;
            deleteVoiceFile();
            panel.setSelected(mask, filter, voice);
            player.play(source);
            return;
        }
        panel.setVoiceBusy(effect);
        final File src = source;
        final File dst = new File(src.getParentFile(), "sg_round_preview_" + System.currentTimeMillis() + "_" + effect + ".mp4");
        final AtomicBoolean cancelled = cancel;
        new Thread(() -> {
            final boolean ok = SolegramRoundFx.replaceAudio(src.getAbsolutePath(), dst.getAbsolutePath(), effect, cancelled);
            AndroidUtilities.runOnUIThread(() -> {
                if (panel == null || request != voiceRequest || cancelled == null || cancelled.get()) {
                    dst.delete();
                    return;
                }
                panel.setVoiceBusy(-1);
                if (!ok) {
                    dst.delete();
                    BulletinFactory.of(root, resourcesProvider).createErrorBulletin(LocaleController.getString(R.string.SG_VoiceFxFailed)).show();
                    return;
                }
                deleteVoiceFile();
                voice = effect;
                voiceFile = dst;
                panel.setSelected(mask, filter, voice);
                player.play(dst);
            });
        }, "SolegramRoundVoice").start();
    }

    private void deleteVoiceFile() {
        if (voiceFile != null) {
            try {
                voiceFile.delete();
            } catch (Throwable ignore) {
            }
            voiceFile = null;
        }
    }

    /** Moves the circle up as far as needed for the panel under it (not off the top). */
    private void updateShift(boolean animated) {
        float target = 0;
        if (panel != null && panel.getHeight() > 0 && panel.getVisibility() == View.VISIBLE) {
            final int area = root.getHeight() - root.getPaddingBottom();
            final int size = circle.getHeight();
            final float circleBottom = area / 2f + size / 2f;
            final float need = circleBottom + dp(12) - panel.getTop();
            final float room = area / 2f - size / 2f - dp(8);
            target = -Math.max(0, Math.min(need, room));
        }
        if (target == shift && (shiftAnimator == null || !shiftAnimator.isRunning())) {
            return;
        }
        if (shiftAnimator != null) {
            shiftAnimator.cancel();
        }
        if (!animated) {
            shift = target;
            shiftListener.onShift(shift);
            return;
        }
        shiftAnimator = ValueAnimator.ofFloat(shift, target);
        shiftAnimator.addUpdateListener(a -> {
            shift = (float) a.getAnimatedValue();
            shiftListener.onShift(shift);
        });
        shiftAnimator.setDuration(320);
        shiftAnimator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
        shiftAnimator.start();
    }

    /** Shows or hides the panel (the editor's button in the input field). */
    public void toggle() {
        if (panel == null) {
            return;
        }
        final boolean show = panel.getVisibility() != View.VISIBLE || panel.getAlpha() < 0.5f;
        final View p = panel;
        if (show) {
            p.setVisibility(View.VISIBLE);
            p.animate().alpha(1f).translationY(0).setDuration(260).setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).withEndAction(null)
                .setUpdateListener(a -> notifyChanged()).start();
        } else {
            p.animate().alpha(0f).translationY(dp(24)).setDuration(200).setUpdateListener(a -> notifyChanged()).withEndAction(() -> {
                p.setVisibility(View.GONE);
                notifyChanged();
            }).start();
        }
        AndroidUtilities.runOnUIThread(() -> updateShift(true), show ? 0 : 210);
    }

    public boolean isOpen() {
        return panel != null && panel.getVisibility() == View.VISIBLE;
    }

    /** Whether the sound must be changed in the sent video. */
    public int getVoice() {
        return voiceFile != null ? voice : SolegramVoiceEffects.NONE;
    }

    /**
     * Puts the filter and the mask into what is sent (the video starts offsetUs into the recording, if trimmed);
     * returns whether anything was put in (the video must then be converted).
     */
    public boolean applyTo(VideoEditedInfo info, long offsetUs) {
        if (info == null || source == null) {
            return false;
        }
        boolean any = false;
        info.filterState = SolegramRoundFx.filterState(filter);
        if (info.filterState != null) {
            any = true;
        }
        if (mask != SolegramRoundMasks.NONE && track != null && track.hasFace()) {
            sprites.prepare(mask);
            info.sgOverlay = new SolegramRoundFx.Overlay(track, mask, sprites, offsetUs);
            any = true;
        }
        return any;
    }

    /** The editor goes away (the video was sent, thrown away, or recording goes on). */
    public void close(boolean deleteFiles) {
        if (cancel != null) {
            cancel.set(true);
            cancel = null;
        }
        voiceRequest++;
        if (deleteFiles) {
            deleteVoiceFile();
        } else {
            voiceFile = null;
        }
        if (panel != null) {
            final View p = panel;
            panel = null;
            p.animate().alpha(0f).translationY(dp(24)).setDuration(200).setUpdateListener(a -> notifyChanged()).withEndAction(() -> {
                if (p.getParent() instanceof ViewGroup) {
                    ((ViewGroup) p.getParent()).removeView(p);
                }
                notifyChanged();
            }).start();
        }
        if (overlay != null) {
            if (overlay.getParent() instanceof ViewGroup) {
                ((ViewGroup) overlay.getParent()).removeView(overlay);
            }
            overlay = null;
        }
        if (texture != null && filter != SolegramRoundFx.F_NONE) {
            texture.setLayerPaint(null);
        }
        filter = SolegramRoundFx.F_NONE;
        mask = SolegramRoundMasks.NONE;
        voice = SolegramVoiceEffects.NONE;
        track = null;
        source = null;
        if (shift != 0) {
            updateShift(true);
        }
    }
}
