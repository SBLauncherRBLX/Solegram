/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.util.SparseIntArray;
import android.view.View;
import android.view.animation.LinearInterpolator;

import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.Components.CubicBezierInterpolator;

/**
 * Icon buttons move when tapped, each in a way that fits what it shows, like the bottom tabs do: the bell rings,
 * the magnifier looks around, the paper plane takes off, the pencil scribbles, the trash can shakes... An icon
 * without a motion of its own pops. Only the icon's view is moved (rotation, scale, translation), and every motion
 * ends exactly at rest; a new tap restarts it.
 */
public final class SolegramIconMotion {

    public static final int POP = 0;
    public static final int RING = 1;
    public static final int LOOK = 2;
    public static final int TAKE_OFF = 3;
    public static final int SCRIBBLE = 4;
    public static final int SHAKE = 5;
    public static final int SWING = 6;
    public static final int SPIN = 7;
    public static final int PULSE = 8;
    public static final int SHUTTER = 9;
    public static final int NUDGE_BACK = 10;
    public static final int NUDGE_UP = 11;
    public static final int DROP = 12;
    public static final int DOTS = 13;
    public static final int WOBBLE = 14;
    public static final int TURN = 15;

    /** How long a menu that opens over its button waits for the button's motion to show (the same for every menu). */
    public static final long MENU_DELAY = 60;

    private static final int TAG = R.id.solegram_icon_motion;
    private static final int TAG_BASE = R.id.solegram_icon_motion_base;
    private static SparseIntArray kinds;

    private SolegramIconMotion() {
    }

    private static synchronized int kindOf(int resId) {
        if (kinds == null) {
            kinds = new SparseIntArray();
            put(RING, R.drawable.msg_mute, R.drawable.msg_unmute, R.drawable.msg_notifications,
                R.drawable.settings_sounds);
            put(LOOK, R.drawable.outline_header_search, R.drawable.msg_search);
            put(TAKE_OFF, R.drawable.msg_send, R.drawable.msg_forward, R.drawable.msg_share, R.drawable.msg_shareout, R.drawable.ic_send);
            put(SCRIBBLE, R.drawable.msg_edit, R.drawable.floating_pencil);
            put(SHAKE, R.drawable.msg_delete, R.drawable.msg_clear, R.drawable.msg_callback, R.drawable.msg_calls, R.drawable.ic_call,
                R.drawable.msg_block, R.drawable.msg_block2);
            put(SWING, R.drawable.input_attach, R.drawable.msg_link, R.drawable.msg_link2, R.drawable.msg_gift_premium);
            put(SPIN, R.drawable.msg_settings, R.drawable.msg_retry, R.drawable.msg_reset);
            put(PULSE, R.drawable.input_mic, R.drawable.msg_voicechat, R.drawable.msg_reactions2, R.drawable.msg_fave);
            put(SHUTTER, R.drawable.msg_camera, R.drawable.input_video, R.drawable.msg_videocall, R.drawable.msg_photos);
            put(NUDGE_BACK, R.drawable.ic_ab_back, R.drawable.msg_arrow_back);
            put(NUDGE_UP, R.drawable.msg_download, R.drawable.msg_saved, R.drawable.msg_pin, R.drawable.msg_unpin);
            put(DROP, R.drawable.msg_archive, R.drawable.msg_unarchive, R.drawable.msg_folders);
            put(DOTS, R.drawable.ic_ab_other, R.drawable.msg_actions);
            put(WOBBLE, R.drawable.input_smile, R.drawable.msg_emoji_cat, R.drawable.smiles_tab_smiles);
            put(TURN, R.drawable.msg_qrcode, R.drawable.msg_copy, R.drawable.msg_info, R.drawable.msg_translate);
        }
        return kinds.get(resId, POP);
    }

    private static void put(int kind, int... ids) {
        for (int id : ids) {
            kinds.put(id, kind);
        }
    }

    /** The motion of the icon this drawable draws. */
    public static void playFor(@Nullable View icon, int resId) {
        play(icon, kindOf(resId));
    }

    /** A motion (one of the kinds above) of an icon's view. */
    public static void play(@Nullable View icon, int kind) {
        if (icon == null || !SolegramMotion.rich() || !icon.isAttachedToWindow() || icon.getWidth() <= 0) {
            return;
        }
        final Object old = icon.getTag(TAG);
        if (old instanceof Animator) {
            icon.setTag(TAG, null); // its end does not give the state back: this motion goes on from the same state
            ((Animator) old).cancel();
        }
        final long duration = duration(kind);
        // the view's own state before the motion: every motion is applied over it and gives it back at the end
        final float[] base = old instanceof Animator && icon.getTag(TAG_BASE) instanceof float[] ? (float[]) icon.getTag(TAG_BASE)
            : new float[]{icon.getScaleX(), icon.getScaleY(), icon.getRotation(), icon.getTranslationX(), icon.getTranslationY(), icon.getAlpha(),
            icon.getRotationY(), icon.getPivotX(), icon.getPivotY()};
        icon.setTag(TAG_BASE, base);
        final ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(duration);
        a.setInterpolator(new LinearInterpolator());
        a.addUpdateListener(v -> apply(icon, kind, base, (float) v.getAnimatedValue()));
        a.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (icon.getTag(TAG) == animation) {
                    restore(icon, base);
                    icon.setTag(TAG, null);
                    icon.setTag(TAG_BASE, null);
                }
            }
        });
        icon.setTag(TAG, a);
        a.start();
    }

    private static float sin(double x) {
        return (float) Math.sin(x);
    }

    /** One frame; t runs 0..1 and at 1 the icon is at rest. */
    private static void restore(View v, float[] b) {
        v.setScaleX(b[0]);
        v.setScaleY(b[1]);
        v.setRotation(b[2]);
        v.setTranslationX(b[3]);
        v.setTranslationY(b[4]);
        v.setAlpha(b[5]);
        v.setRotationY(b[6]);
        v.setPivotX(b[7]);
        v.setPivotY(b[8]);
    }

    private static void apply(View v, int kind, float[] b, float t) {
        if (t >= 1f) {
            return; // the end listener gives the view its own state back
        }
        final float[] f = frame(kind, t, v.getWidth(), v.getHeight());
        v.setPivotX(f[6]);
        v.setPivotY(f[7]);
        v.setScaleX(b[0] * f[0]);
        v.setScaleY(b[1] * f[1]);
        v.setRotation(b[2] + f[2]);
        v.setTranslationX(b[3] + f[3]);
        v.setTranslationY(b[4] + f[4]);
        v.setAlpha(b[5] * f[5]);
        if (kind == TURN) {
            v.setCameraDistance(v.getWidth() * 12f);
            v.setRotationY(b[6] + 360f * CubicBezierInterpolator.EASE_OUT_QUINT.getInterpolation(t));
        }
    }

    /** Duration of a motion, ms. */
    public static long duration(int kind) {
        switch (kind) {
            case RING:
            case SHAKE:
                return 620;
            case SPIN:
                return 640;
            case TAKE_OFF:
                return 520;
            case LOOK:
            case SCRIBBLE:
            case DOTS:
                return 560;
            default:
                return 460;
        }
    }

    /**
     * A motion drawn on a canvas (for icons a view draws itself), around the icon's bounds; t runs 0..1.
     * @return the alpha to draw the icon with
     */
    public static float transform(android.graphics.Canvas canvas, int kind, float t, android.graphics.Rect bounds) {
        if (t <= 0f || t >= 1f) {
            return 1f;
        }
        final float[] f = frame(kind, t, bounds.width(), bounds.height());
        float sx = f[0];
        if (kind == TURN) {
            sx *= (float) Math.cos(Math.toRadians(360f * CubicBezierInterpolator.EASE_OUT_QUINT.getInterpolation(t)));
        }
        final float px = bounds.left + f[6], py = bounds.top + f[7];
        canvas.translate(f[3], f[4]);
        canvas.rotate(f[2], px, py);
        canvas.scale(sx, f[1], px, py);
        return f[5];
    }

    /** {scaleX, scaleY, rotation, dx, dy, alpha, pivotX, pivotY} of one frame. */
    private static float[] frame(int kind, float t, int width, int height) {
        float sx = 1f, sy = 1f, rotation = 0f, dx = 0f, dy = 0f, alpha = 1f;
        float px = width / 2f, py = height / 2f;
        final float decay = 1f - t;
        final float d = AndroidUtilities.dp(1);
        switch (kind) {
            case RING:
                // the bell swings from its top, quick and dying away
                py = height * 0.18f;
                rotation = sin(t * Math.PI * 6) * 20f * decay;
                break;
            case LOOK: {
                // the magnifier looks left and right, a little closer
                rotation = sin(t * Math.PI * 2) * -16f * decay;
                dx = sin(t * Math.PI * 2) * -2.5f * d * decay;
                final float s = 1f + 0.12f * sin(Math.PI * t);
                sx = sy = s;
                break;
            }
            case TAKE_OFF: {
                // the plane flies off up and right and comes back in from the other side
                if (t < 0.45f) {
                    final float k = CubicBezierInterpolator.EASE_IN.getInterpolation(t / 0.45f);
                    dx = 14 * d * k;
                    dy = -10 * d * k;
                    alpha = 1f - k;
                    sx = sy = 1f - 0.3f * k;
                } else {
                    final float k = CubicBezierInterpolator.EASE_OUT_BACK.getInterpolation((t - 0.45f) / 0.55f);
                    dx = -10 * d * (1f - k);
                    dy = 8 * d * (1f - k);
                    alpha = Math.min(1f, (t - 0.45f) / 0.25f);
                    sx = sy = 0.7f + 0.3f * k;
                }
                break;
            }
            case SCRIBBLE:
                // the pencil writes: little strokes left and right
                px = width * 0.2f;
                py = height * 0.8f;
                rotation = sin(t * Math.PI * 5) * 10f * decay;
                dx = sin(t * Math.PI * 5 + 1) * 1.5f * d * decay;
                break;
            case SHAKE:
                rotation = sin(t * Math.PI * 8) * 12f * decay;
                break;
            case SWING:
                // the clip (or link) swings on its end
                px = width * 0.5f;
                py = height * 0.15f;
                rotation = sin(t * Math.PI * 3) * 24f * decay;
                break;
            case SPIN:
                // a whole turn, quick at first and settling with a little spring: it ends as it began, with no jump
                rotation = 360f * CubicBezierInterpolator.EASE_OUT_BACK.getInterpolation(Math.min(1f, t * 1.05f));
                sx = sy = 1f - 0.08f * sin(Math.PI * Math.min(1f, t * 2.2f));
                break;
            case PULSE: {
                // two beats
                final float beat = t < 0.5f ? sin(Math.PI * t / 0.5f) : 0.6f * sin(Math.PI * (t - 0.5f) / 0.5f);
                sx = sy = 1f + 0.2f * beat;
                break;
            }
            case SHUTTER: {
                // a click: squeezed for a moment, then out again
                final float k = t < 0.25f ? t / 0.25f : 1f - CubicBezierInterpolator.EASE_OUT_BACK.getInterpolation((t - 0.25f) / 0.75f);
                sx = 1f + 0.08f * k;
                sy = 1f - 0.22f * k;
                break;
            }
            case NUDGE_BACK:
                dx = -5 * d * sin(Math.PI * Math.min(1f, t * 1.6f)) * (t < 0.62f ? 1f : 0f) + (t >= 0.62f ? 1.5f * d * sin(Math.PI * (t - 0.62f) / 0.38f) : 0f);
                break;
            case NUDGE_UP: {
                // down into place with a little bounce
                final float k = CubicBezierInterpolator.EASE_OUT_BACK.getInterpolation(t);
                dy = -6 * d * (1f - k);
                alpha = Math.min(1f, 0.4f + t * 2f);
                break;
            }
            case DROP: {
                final float k = t < 0.35f ? sin(Math.PI * t / 0.35f) : 0f;
                dy = 4 * d * k;
                sy = 1f - 0.12f * (t >= 0.35f && t < 0.7f ? sin(Math.PI * (t - 0.35f) / 0.35f) : 0f);
                sx = 2f - sy;
                py = height;
                break;
            }
            case DOTS:
                // the dots tumble half a round: upright again at the end, so nothing jumps back
                rotation = 180f * CubicBezierInterpolator.EASE_OUT_BACK.getInterpolation(t);
                break;
            case WOBBLE: {
                // a smile that rocks and grows
                rotation = sin(t * Math.PI * 3) * 14f * decay;
                sx = sy = 1f + 0.14f * sin(Math.PI * Math.min(1f, t * 1.5f)) * (t < 0.67f ? 1f : 0f);
                break;
            }
            case TURN:
                // turns round on its vertical axis (the view's rotationY, or a squeeze on a canvas)
                break;
            default: {
                // pop: in a little, out a little, at rest
                final float k = t < 0.2f ? -0.12f * (t / 0.2f) : -0.12f + 0.2f * sin(Math.PI * (t - 0.2f) / 0.8f) + 0.12f * ((t - 0.2f) / 0.8f);
                sx = sy = 1f + (t >= 1f ? 0f : k);
            }
        }
        return new float[]{sx, sy, rotation, dx, dy, alpha, px, py};
    }
}
