/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.soledrop;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.dpf2;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.solegram.messenger.ui.SolegramMotion;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;

/**
 * The other phone's person in a bubble of glass: the avatar with the transfer ring around it, the name, and a
 * line of what happens. The ring spins while waiting, fills with the progress of the sending, then becomes a
 * filled circle with a check drawn into it (or turns red when it did not go).
 */
@SuppressLint("ViewConstructor")
public class SoleDropPeerView extends FrameLayout {

    public static final int RING_NONE = 0, RING_SPIN = 1, RING_PROGRESS = 2, RING_DONE = 3, RING_FAIL = 4;

    private static final int AVATAR = 68;

    private final Theme.ResourcesProvider resourcesProvider;
    final BackupImageView avatarView;
    private final AvatarDrawable avatarDrawable = new AvatarDrawable();
    final TextView nameView, statusView;
    final LinearLayout content;

    private final Paint ringTrack = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint checkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF ringRect = new RectF();
    private final Matrix matrix = new Matrix();
    private final Path checkPath = new Path(), checkSegment = new Path();
    private final PathMeasure checkMeasure = new PathMeasure();
    private SweepGradient ringShader;
    private float ringCx, ringCy, ringR;
    private int trackAlpha;

    private int ring = RING_NONE;
    private float progress, shownProgress, done, ringAlpha;
    private final long spinStart = SystemClock.elapsedRealtime();
    private ValueAnimator doneAnimator, alphaAnimator;

    public SoleDropPeerView(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        setWillNotDraw(false);
        setClipChildren(false);
        setClipToPadding(false);

        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setClipChildren(false);
        addView(content, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 14, 18, 14, 16));

        avatarView = new BackupImageView(context);
        avatarView.setRoundRadius(dp(AVATAR / 2f));
        content.addView(avatarView, LayoutHelper.createLinear(AVATAR, AVATAR, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 0));

        nameView = new TextView(context);
        nameView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        nameView.setTypeface(AndroidUtilities.bold());
        nameView.setGravity(Gravity.CENTER);
        nameView.setSingleLine(true);
        nameView.setEllipsize(TextUtils.TruncateAt.END);
        content.addView(nameView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 12, 0, 0));

        statusView = new TextView(context);
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        statusView.setGravity(Gravity.CENTER);
        statusView.setSingleLine(true);
        statusView.setEllipsize(TextUtils.TruncateAt.END);
        content.addView(statusView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 2, 0, 0));

        ringTrack.setStyle(Paint.Style.STROKE);
        ringTrack.setStrokeWidth(dpf2(3f));
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(dpf2(3f));
        ringPaint.setStrokeCap(Paint.Cap.ROUND);
        checkPaint.setStyle(Paint.Style.STROKE);
        checkPaint.setStrokeWidth(dpf2(4.2f));
        checkPaint.setStrokeCap(Paint.Cap.ROUND);
        checkPaint.setStrokeJoin(Paint.Join.ROUND);
        checkPaint.setColor(0xFFFFFFFF);
        updateColors();
    }

    public void updateColors() {
        nameView.setTextColor(SoleDropPalette.text(resourcesProvider));
        statusView.setTextColor(SoleDropPalette.gray(resourcesProvider));
        ringTrack.setColor(SoleDropPalette.dark(resourcesProvider) ? 0x22FFFFFF : 0x14000000);
        trackAlpha = ringTrack.getAlpha();
    }

    /** The person as the tap told (name only), then as Telegram found them (user). */
    public void setPeer(long id, @Nullable String name, @Nullable TLRPC.User user) {
        if (user != null) {
            avatarDrawable.setInfo(user);
            avatarView.setForUserOrChat(user, avatarDrawable);
            nameView.setText(UserObject.getUserName(user));
        } else {
            avatarDrawable.setInfo(id, TextUtils.isEmpty(name) ? "?" : name, null);
            avatarView.setImageDrawable(avatarDrawable);
            nameView.setText(TextUtils.isEmpty(name) ? "…" : name);
        }
        updateDescription();
    }

    public void setStatus(CharSequence status) {
        statusView.setText(status);
        updateDescription();
    }

    private void updateDescription() {
        setContentDescription(nameView.getText() + ", " + statusView.getText());
    }

    // region Ring

    public void setRing(int mode) {
        if (ring == mode) {
            return;
        }
        final int was = ring;
        ring = mode;
        if (mode == RING_PROGRESS && was != RING_PROGRESS) {
            shownProgress = 0;
        }
        fadeRing(mode == RING_NONE ? 0f : 1f);
        if (mode == RING_DONE) {
            playDone();
        } else {
            if (doneAnimator != null) {
                doneAnimator.cancel();
            }
            done = 0;
        }
        if (mode == RING_FAIL) {
            shake();
        }
        invalidate();
    }

    public void setProgress(float p) {
        progress = Math.max(progress, Math.min(1f, p));
        invalidate();
    }

    public void resetRing() {
        progress = shownProgress = 0;
        done = 0;
        ring = RING_NONE;
        ringAlpha = 0;
        invalidate();
    }

    private void fadeRing(float to) {
        if (alphaAnimator != null) {
            alphaAnimator.cancel();
        }
        if (!SolegramMotion.enabled()) {
            ringAlpha = to;
            return;
        }
        alphaAnimator = ValueAnimator.ofFloat(ringAlpha, to);
        alphaAnimator.addUpdateListener(a -> {
            ringAlpha = (float) a.getAnimatedValue();
            invalidate();
        });
        alphaAnimator.setDuration(SolegramMotion.NORMAL);
        alphaAnimator.setInterpolator(SolegramMotion.EASE_BOTH);
        alphaAnimator.start();
    }

    private void playDone() {
        if (doneAnimator != null) {
            doneAnimator.cancel();
        }
        progress = 1f;
        if (!SolegramMotion.enabled()) {
            done = 1f;
            shownProgress = 1f;
            invalidate();
            return;
        }
        doneAnimator = ValueAnimator.ofFloat(0f, 1f);
        doneAnimator.addUpdateListener(a -> {
            done = (float) a.getAnimatedValue();
            invalidate();
        });
        doneAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                done = 1f;
                invalidate();
            }
        });
        doneAnimator.setDuration(720);
        doneAnimator.setInterpolator(SolegramMotion.EASE_BOTH);
        doneAnimator.start();
        // the avatar takes the check in with a little pop
        avatarView.animate().cancel();
        avatarView.setScaleX(0.92f);
        avatarView.setScaleY(0.92f);
        avatarView.animate().scaleX(1f).scaleY(1f).setStartDelay(160).setDuration(560).setInterpolator(SolegramMotion.SPRING_BOUNCY).start();
    }

    private void shake() {
        if (!SolegramMotion.enabled()) {
            return;
        }
        final ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.addUpdateListener(v -> {
            final float t = (float) v.getAnimatedValue();
            content.setTranslationX((float) (Math.sin(t * Math.PI * 6) * dp(7) * (1f - t)));
        });
        a.setDuration(460);
        a.start();
    }

    /** A pulse when something lands in the bubble (the flying card). */
    public void absorb() {
        if (!SolegramMotion.enabled()) {
            return;
        }
        animate().cancel();
        setScaleX(1.07f);
        setScaleY(1.07f);
        animate().scaleX(1f).scaleY(1f).setDuration(620).setInterpolator(SolegramMotion.SPRING_BOUNCY).start();
    }

    /** Centre of the avatar in this view's coordinates. */
    public float avatarCenterX() {
        return content.getLeft() + avatarView.getLeft() + avatarView.getWidth() / 2f;
    }

    public float avatarCenterY() {
        return content.getTop() + avatarView.getTop() + avatarView.getHeight() / 2f;
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        if (ringAlpha <= 0f || avatarView.getWidth() <= 0) {
            return;
        }
        final float cx = content.getLeft() + avatarView.getLeft() + avatarView.getWidth() / 2f;
        final float cy = content.getTop() + avatarView.getTop() + avatarView.getHeight() / 2f;
        final float r = avatarView.getWidth() / 2f + dp(6);
        if (ringShader == null || cx != ringCx || cy != ringCy || r != ringR) {
            ringCx = cx;
            ringCy = cy;
            ringR = r;
            ringShader = new SweepGradient(0, 0, SoleDropPalette.SWEEP, null);
            ringPaint.setShader(ringShader);
            fillPaint.setShader(new SweepGradient(cx, cy, SoleDropPalette.SWEEP, null));
            // the check, sized to the avatar
            checkPath.reset();
            checkPath.moveTo(cx - r * 0.36f, cy + r * 0.02f);
            checkPath.lineTo(cx - r * 0.10f, cy + r * 0.28f);
            checkPath.lineTo(cx + r * 0.40f, cy - r * 0.26f);
            checkMeasure.setPath(checkPath, false);
        }
        ringRect.set(cx - r, cy - r, cx + r, cy + r);
        final int alpha = (int) (255 * ringAlpha);
        canvas.save();
        canvas.translate(content.getTranslationX(), 0); // the ring shakes with the avatar
        ringTrack.setAlpha((int) (trackAlpha * ringAlpha * (ring == RING_DONE ? 1f - done : 1f)));
        canvas.drawCircle(cx, cy, r, ringTrack);
        drawRing(canvas, cx, cy, r, alpha);
        canvas.restore();
    }

    private void drawRing(Canvas canvas, float cx, float cy, float r, int alpha) {

        final long now = SystemClock.elapsedRealtime();
        boolean animating = false;
        if (ring == RING_FAIL) {
            ringPaint.setShader(null);
            ringPaint.setColor(0xFFFF453A);
            ringPaint.setAlpha(alpha);
            canvas.drawCircle(cx, cy, r, ringPaint);
            ringPaint.setShader(ringShader);
            return;
        }
        final float t = (now - spinStart) / 1000f;
        matrix.setRotate((t * 300f) % 360f);
        matrix.postTranslate(cx, cy);
        ringShader.setLocalMatrix(matrix);
        ringPaint.setAlpha(alpha);
        if (ring == RING_SPIN) {
            final float sweep = 70f + 50f * (float) Math.sin(t * 3.2f);
            canvas.drawArc(ringRect, (t * 360f / 1.1f) % 360f, sweep, false, ringPaint);
            animating = true;
        } else if (ring == RING_PROGRESS || ring == RING_DONE) {
            // the ring catches up with the progress smoothly
            final float target = ring == RING_DONE ? 1f : Math.max(0.04f, progress);
            shownProgress += (target - shownProgress) * 0.16f;
            if (Math.abs(target - shownProgress) < 0.002f) {
                shownProgress = target;
            } else {
                animating = true;
            }
            canvas.drawArc(ringRect, -90, 360f * shownProgress, false, ringPaint);
            if (ring == RING_PROGRESS) {
                animating = true; // the gradient keeps turning while waiting
            }
        }
        if (ring == RING_DONE && done > 0f) {
            // a filled circle grows over the avatar, the check is drawn into it
            final float grow = Math.min(1f, done / 0.45f);
            final float eased = 1f - (1f - grow) * (1f - grow) * (1f - grow);
            fillPaint.setAlpha((int) (240 * eased));
            canvas.drawCircle(cx, cy, r * eased, fillPaint);
            final float check = Math.max(0f, Math.min(1f, (done - 0.35f) / 0.6f));
            if (check > 0f) {
                checkSegment.reset();
                checkMeasure.getSegment(0, checkMeasure.getLength() * check, checkSegment, true);
                canvas.drawPath(checkSegment, checkPaint);
            }
        }
        if (animating && isAttachedToWindow()) {
            postInvalidateOnAnimation();
        }
    }
    // endregion
}
