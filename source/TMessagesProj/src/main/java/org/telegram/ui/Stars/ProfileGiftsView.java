package org.telegram.ui.Stars;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.dpf2;
import static org.telegram.messenger.AndroidUtilities.lerp;
import static org.telegram.ui.Stars.StarsController.findAttribute;

import android.animation.TimeInterpolator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;

import androidx.annotation.NonNull;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.browser.Browser;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_stars;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedEmojiDrawable;
import org.telegram.ui.Components.AnimatedFloat;
import org.telegram.ui.Components.ButtonBounce;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.ProfileActivity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

public class ProfileGiftsView extends View implements NotificationCenter.NotificationCenterDelegate {

    private final int currentAccount;
    private final long dialogId;
    private final View avatarContainer;
    private final ProfileActivity.AvatarImageView avatarImage;
    private final Theme.ResourcesProvider resourcesProvider;

    private boolean active = true;
    public void setActive(boolean f) {
        this.active = f;
    }

    public ProfileGiftsView(Context context, int currentAccount, long dialogId, @NonNull View avatarContainer, ProfileActivity.AvatarImageView avatarImage, Theme.ResourcesProvider resourcesProvider) {
        super(context);

        this.currentAccount = currentAccount;
        this.dialogId = dialogId;

        this.avatarContainer = avatarContainer;
        this.avatarImage = avatarImage;

        this.resourcesProvider = resourcesProvider;
    }

    public float expandProgress, collapseProgress;
    public boolean isOpening;

    public void setExpandProgress(float progress) {
        if (this.expandProgress != progress) {
            this.expandProgress = progress;
            invalidate();
        }
    }

    public void setCollapseProgress(float progress, boolean isOpening) {
        this.isOpening = isOpening;
        float newProgress = Utilities.clamp01((progress - 0.3f) / 0.7f);
        if (this.collapseProgress != newProgress) {
            this.collapseProgress = newProgress;
            invalidate();
        }
    }

    private float actionBarProgress;

    public void setActionBarActionMode(float progress) {
//        if (Theme.isCurrentThemeDark()) {
//            return;
//        }
        actionBarProgress = progress;
        invalidate();
    }


    private float left, right, cy;
    private float expandY, maxExpandY;
    private final AnimatedFloat rightAnimated = new AnimatedFloat(this, 0, 350, CubicBezierInterpolator.EASE_OUT_QUINT);

    public void setBounds(float left, float right, float cy, boolean animated, int maxExpandY) {
        boolean changed = Math.abs(left - this.left) > 0.1f || Math.abs(right - this.right) > 0.1f || Math.abs(cy - this.cy) > 0.1f;
        this.left = left;
        this.right = right;
        if (!animated) {
            this.rightAnimated.set(this.right, true);
        }
        this.cy = cy;
        this.maxExpandY = maxExpandY + cy;
        if (changed) {
            invalidate();
        }
    }

    public void setExpandCoords(float y) {
        this.expandY = y;
        invalidate();
    }

    private float progressToInsets = 1f;

    public void setProgressToStoriesInsets(float progressToInsets) {
        if (this.progressToInsets == progressToInsets) {
            return;
        }
        this.progressToInsets = progressToInsets;
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        NotificationCenter.getInstance(currentAccount).addWeakObserver(this, NotificationCenter.starUserGiftsLoaded);

        for (Gift gift : gifts) {
            gift.emojiDrawable.addView(this);
            if (gift.sgPattern != null) {
                gift.sgPattern.addView(this);
            }
        }

        update();
        sgUpdateSensors();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();

        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.starUserGiftsLoaded);

        for (Gift gift : gifts) {
            gift.emojiDrawable.removeView(this);
            if (gift.sgPattern != null) {
                gift.sgPattern.removeView(this);
            }
        }
        sgUpdateBeat();
        sgUpdateSensors();
        sgLastFrame = 0;
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.starUserGiftsLoaded) {
            if ((long) args[0] == dialogId) {
                update();
            }
        }
    }

    public final class Gift {

        public final long id;
        public final TLRPC.Document document;
        public final long documentId;
        public final int color;
        public final String slug;
        private StarsReactionsSheet.Particles particles;

        public int position = -1;
        /** Solegram: the floating bubble (see the Solegram region of the view), the symbol of its pattern inside. */
        final SgBubble sg = new SgBubble();
        final TLRPC.Document sgPatternDocument;
        final long sgPatternDocumentId;
        final int sgPatternColor;
        AnimatedEmojiDrawable sgPattern;

        public Gift(TL_stars.TL_starGiftUnique gift) {
            id = gift.id;
            document = gift.getDocument();
            documentId = document == null ? 0 : document.id;
            final TL_stars.starGiftAttributeBackdrop backdrop = findAttribute(gift.attributes, TL_stars.starGiftAttributeBackdrop.class);
            color = backdrop.center_color | 0xFF000000;
            slug = gift.slug;
            final TL_stars.starGiftAttributePattern pattern = findAttribute(gift.attributes, TL_stars.starGiftAttributePattern.class);
            sgPatternDocument = pattern != null ? pattern.document : null;
            sgPatternDocumentId = sgPatternDocument != null ? sgPatternDocument.id : 0;
            sgPatternColor = backdrop.pattern_color | 0xFF000000;
            initParticles();
        }

        public Gift(TLRPC.TL_emojiStatusCollectible status) {
            id = status.collectible_id;
            document = null;
            documentId = status.document_id;
            color = status.center_color | 0xFF000000;
            slug = status.slug;
            sgPatternDocument = null;
            sgPatternDocumentId = status.pattern_document_id;
            sgPatternColor = status.pattern_color | 0xFF000000;
            initParticles();
        }

        private void initParticles() {
            particles = new StarsReactionsSheet.Particles(StarsReactionsSheet.Particles.TYPE_RADIAL, 6);
            final float gsz = dp(36);
            particles.bounds.set(-gsz / 2.0f, -gsz / 2.0f, gsz / 2.0f, gsz / 2.0f);
        }

        public boolean equals(Gift b) {
            return b != null && b.id == id;
        }

        public RadialGradient gradient;
        public final Matrix gradientMatrix = new Matrix();
        public Paint gradientPaint;
        public AnimatedEmojiDrawable emojiDrawable;
        public AnimatedFloat animatedFloat;

        public final RectF bounds = new RectF();
        public final ButtonBounce bounce = new ButtonBounce(ProfileGiftsView.this);

        public void copy(Gift b) {
            gradient = b.gradient;
            emojiDrawable = b.emojiDrawable;
            gradientPaint = b.gradientPaint;
            animatedFloat = b.animatedFloat;
            particles = b.particles;
            position = b.position;
            sg.copy(b.sg);
            sgPattern = b.sgPattern;
        }

        public void draw(
                Canvas canvas,
                float cx, float cy,
                float ascale, float rotate,
                float alpha,
                float gradientAlpha
        ) {
            if (alpha <= 0.0f) return;
            final float gsz = dp(45);
            bounds.set(cx - gsz / 2, cy - gsz / 2, cx + gsz / 2, cy + gsz / 2);
            canvas.save();
            canvas.translate(cx, cy);
            canvas.rotate(rotate);
            final float scale = ascale * bounce.getScale(0.1f);
            canvas.scale(scale, scale);
            particles.process();
            particles.draw(canvas, color, alpha);
            if (gradientPaint != null) {
                gradientPaint.setAlpha((int) (0xFF * alpha * gradientAlpha));
                canvas.drawRect(-gsz / 2.0f, -gsz / 2.0f, gsz / 2.0f, gsz / 2.0f, gradientPaint);
            }
            if (emojiDrawable != null) {
                final int sz = dp(24);
                emojiDrawable.setBounds(-sz / 2, -sz / 2, sz / 2, sz / 2);
                emojiDrawable.setAlpha((int) (0xFF * alpha));
                emojiDrawable.draw(canvas);
            }
            canvas.restore();
        }
    }

    private StarsController.GiftsList list;

    // region Solegram: gifts as glass bubbles floating round the avatar (like Telegram's new iOS profile)

    /** More than the pinned ones float: the next collectible gifts of the profile fill up to this. */
    private static final int SG_MAX = 9;
    /** The symbols inside a bubble (a snow globe of the gift's pattern). */
    private static final int SG_FLAKES = 7;
    /** Where the symbols stay inside, in bubble radii; their size in bubble radii. */
    private static final float SG_FLAKE_ROOM = 0.7f, SG_FLAKE_SIZE = 0.3f;

    /** One floating bubble: where it is (px from the avatar's centre), how it moves, wobbles, swells; its snow. */
    static final class SgBubble {
        boolean placed;
        float x, y, vx, vy, lastVx, lastVy;
        float phase, size = 1f, ring, spin = 1f;
        float pulse, pulseV;
        float squash, squashV, squashAngle;
        float inflate = 1f, inflateV, inflateTarget = 1f;
        long inflateUntil;
        float drawX, drawY, drawR;
        RadialGradient body;
        int bodyColor;
        // the snow: positions and speeds in bubble radii from its centre, and their turn
        final float[] fx = new float[SG_FLAKES], fy = new float[SG_FLAKES], fvx = new float[SG_FLAKES], fvy = new float[SG_FLAKES], fa = new float[SG_FLAKES];
        boolean snowPlaced;

        void copy(SgBubble b) {
            placed = b.placed;
            x = b.x;
            y = b.y;
            vx = b.vx;
            vy = b.vy;
            phase = b.phase;
            size = b.size;
            ring = b.ring;
            spin = b.spin;
            snowPlaced = b.snowPlaced;
            System.arraycopy(b.fx, 0, fx, 0, SG_FLAKES);
            System.arraycopy(b.fy, 0, fy, 0, SG_FLAKES);
            System.arraycopy(b.fa, 0, fa, 0, SG_FLAKES);
        }

        /** Stirs the snow up. */
        void stir(java.util.Random random, float power) {
            for (int k = 0; k < SG_FLAKES; k++) {
                fvx[k] += (random.nextFloat() * 2 - 1) * power;
                fvy[k] += (random.nextFloat() * 2 - 1) * power - power * 0.4f;
            }
        }
    }

    private final boolean sgOrbit = com.solegram.messenger.SolegramConfig.profileGiftsOrbit;
    private long sgLastFrame;
    private float sgTime;
    private java.util.function.BooleanSupplier sgMusicPlays;
    private boolean sgBeatAdded;
    private final java.util.Random sgRandom = new java.util.Random();
    private final android.graphics.Path sgPath = new android.graphics.Path();
    private final Paint sgFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sgRim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sgShine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix sgMatrix = new Matrix();
    private RadialGradient sgShineShader;

    // the lens: the glass of the profile's header (Android 12+), one drawable per bubble and layer
    @androidx.annotation.Nullable
    private org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory sgGlassFactory;
    private final ArrayList<org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable> sgGlassBack = new ArrayList<>(), sgGlassFront = new ArrayList<>();
    private int sgGlassBackUsed, sgGlassFrontUsed;

    /** The bubbles in front of the avatar: the profile puts this view right above the avatar. */
    public final View sgFront;

    {
        sgRim.setStyle(Paint.Style.STROKE);
        sgFront = new View(getContext()) {
            @Override
            protected void onDraw(@NonNull Canvas canvas) {
                sgDraw(canvas, true, this);
            }
        };
        sgFront.setWillNotDraw(false);
    }

    /** The bubbles refract what is under them through this glass (null: they are drawn by themselves). */
    public void sgSetGlass(@androidx.annotation.Nullable org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory factory) {
        sgGlassFactory = factory;
        sgGlassBack.clear();
        sgGlassFront.clear();
        invalidate();
        sgFront.invalidate();
    }

    private void sgDrawGlass(Canvas canvas, boolean front, View owner, float cx, float cy, float r, float alpha) {
        final ArrayList<org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable> list = front ? sgGlassFront : sgGlassBack;
        final int used = front ? sgGlassFrontUsed++ : sgGlassBackUsed++;
        if (used == list.size()) {
            list.add(sgGlassFactory.create(owner, org.telegram.ui.Components.ProfileButtonsGlass.NO_COLORS));
        }
        final org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable glass = list.get(used);
        glass.setRadius(r);
        glass.setBounds((int) (cx - r), (int) (cy - r), (int) Math.ceil(cx + r), (int) Math.ceil(cy + r));
        glass.setAlpha((int) (0xFF * alpha));
        glass.draw(canvas);
    }

    private void sgHideUnusedGlass(boolean front) {
        final ArrayList<org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable> list = front ? sgGlassFront : sgGlassBack;
        for (int i = front ? sgGlassFrontUsed : sgGlassBackUsed; i < list.size(); i++) {
            if (!list.get(i).getBounds().isEmpty()) {
                list.get(i).setBounds(0, 0, 0, 0);
            }
        }
    }

    private final com.solegram.messenger.SolegramBeat.Listener sgBeat = strength -> {
        if (this.gifts.isEmpty() || sgMusicPlays == null || !sgMusicPlays.getAsBoolean()) {
            return;
        }
        for (int i = 0; i < this.gifts.size(); i++) {
            final SgBubble b = this.gifts.get(i).sg;
            // each swells and hops a little, its own way; the snow jumps
            b.pulseV += 9f * strength * (0.7f + 0.6f * sgRandom.nextFloat());
            b.vy -= dp(70) * strength * (0.6f + 0.8f * sgRandom.nextFloat());
            b.vx += dp(30) * strength * (sgRandom.nextFloat() * 2 - 1);
            b.stir(sgRandom, 1.6f * strength);
        }
        invalidate();
    };

    /** Whether the profile's own music plays now (the bubbles hop to its beat then). */
    public void sgSetMusicPlays(java.util.function.BooleanSupplier plays) {
        sgMusicPlays = plays;
        sgUpdateBeat();
    }

    private void sgUpdateBeat() {
        final boolean want = sgOrbit && sgMusicPlays != null && isAttachedToWindow() && !gifts.isEmpty();
        if (want != sgBeatAdded) {
            sgBeatAdded = want;
            if (want) {
                com.solegram.messenger.SolegramBeat.add(sgBeat);
            } else {
                com.solegram.messenger.SolegramBeat.remove(sgBeat);
            }
        }
    }

    // region the phone's motion: the snow settles down the real gravity and lags when the phone moves

    private android.hardware.SensorManager sgSensors;
    private int sgRotation;
    /** Gravity on the screen (in g) and the phone's own acceleration on the screen (m/s²). UI thread. */
    private float sgGx = 0f, sgGy = 1f, sgAx, sgAy;

    private final android.hardware.SensorEventListener sgSensorListener = new android.hardware.SensorEventListener() {
        @Override
        public void onSensorChanged(android.hardware.SensorEvent event) {
            final float dx = -event.values[0], dy = event.values[1];
            float sx, sy;
            switch (sgRotation) {
                case android.view.Surface.ROTATION_90: sx = -dy; sy = dx; break;
                case android.view.Surface.ROTATION_180: sx = -dx; sy = -dy; break;
                case android.view.Surface.ROTATION_270: sx = dy; sy = -dx; break;
                default: sx = dx; sy = dy;
            }
            if (event.sensor.getType() == android.hardware.Sensor.TYPE_GRAVITY) {
                sgGx = sx / android.hardware.SensorManager.GRAVITY_EARTH;
                sgGy = sy / android.hardware.SensorManager.GRAVITY_EARTH;
            } else {
                sgAx = sgAx * 0.5f + sx * 0.5f;
                sgAy = sgAy * 0.5f + sy * 0.5f;
            }
        }

        @Override
        public void onAccuracyChanged(android.hardware.Sensor sensor, int accuracy) {
        }
    };

    private void sgUpdateSensors() {
        final boolean want = sgOrbit && isAttachedToWindow() && !gifts.isEmpty();
        if (want == (sgSensors != null)) {
            return;
        }
        if (want) {
            try {
                final android.hardware.SensorManager sm = (android.hardware.SensorManager) getContext().getSystemService(Context.SENSOR_SERVICE);
                if (sm == null) {
                    return;
                }
                final android.hardware.Sensor gravity = sm.getDefaultSensor(android.hardware.Sensor.TYPE_GRAVITY);
                final android.hardware.Sensor linear = sm.getDefaultSensor(android.hardware.Sensor.TYPE_LINEAR_ACCELERATION);
                if (gravity != null) {
                    sm.registerListener(sgSensorListener, gravity, android.hardware.SensorManager.SENSOR_DELAY_GAME);
                }
                if (linear != null) {
                    sm.registerListener(sgSensorListener, linear, android.hardware.SensorManager.SENSOR_DELAY_GAME);
                }
                sgSensors = sm;
                sgReadRotation();
            } catch (Throwable ignore) {
            }
        } else {
            try {
                sgSensors.unregisterListener(sgSensorListener);
            } catch (Throwable ignore) {
            }
            sgSensors = null;
            sgGx = 0;
            sgGy = 1;
            sgAx = sgAy = 0;
        }
    }

    private void sgReadRotation() {
        try {
            final android.view.WindowManager wm = (android.view.WindowManager) getContext().getSystemService(Context.WINDOW_SERVICE);
            sgRotation = wm.getDefaultDisplay().getRotation();
        } catch (Throwable ignore) {
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (sgSensors != null) {
            sgReadRotation();
        }
    }

    // endregion

    // the frame's geometry
    private float sgAcx, sgAcy, sgAvatarR, sgRealCx, sgRealCy, sgAlpha, sgCollapse, sgTop, sgBottom;

    private Gift sgGiftUnder(float x, float y) {
        Gift best = null;
        float bestD = Float.MAX_VALUE;
        for (int i = 0; i < gifts.size(); i++) {
            final SgBubble b = gifts.get(i).sg;
            if (b.drawR <= 0) {
                continue;
            }
            final float d = (float) Math.hypot(x - b.drawX, y - b.drawY);
            if (d < b.drawR + dp(4) && d < bestD) {
                bestD = d;
                best = gifts.get(i);
            }
        }
        return best;
    }

    /** A tap swells the bubble and stirs its snow (a tap on a swollen one opens the gift). */
    private void sgTap(Gift gift) {
        final SgBubble b = gift.sg;
        if (b.inflateTarget > 1f) {
            onGiftClick(gift);
            b.inflateTarget = 1f;
        } else {
            b.inflateTarget = 2.6f;
            b.inflateUntil = android.os.SystemClock.uptimeMillis() + 2600;
            b.stir(sgRandom, 3.5f);
            try {
                performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
            } catch (Throwable ignore) {
            }
        }
        invalidate();
    }

    private void sgStepAndDraw(Canvas canvas) {
        sgGlassBackUsed = 0;
        if (gifts.isEmpty() || expandProgress >= 1.0f || collapseProgress <= 0f) {
            sgLastFrame = 0;
            sgAlpha = 0;
            sgHideUnusedGlass(false);
            sgFront.invalidate();
            return;
        }
        final float realX = avatarContainer.getX();
        final float realY = avatarContainer.getY();
        final float realW = avatarContainer.getWidth() * avatarContainer.getScaleX();
        final float realH = avatarContainer.getHeight() * avatarContainer.getScaleY();
        final float sz = dpf2(96);
        final float ax = Math.min(realX, (getWidth() - sz) / 2f);
        final float ay = Math.max(realY, (maxExpandY - sz) / 2f);
        final float aw = Math.max(realW, sz);
        final float ah = Math.max(realH, sz);
        sgAcx = ax + aw / 2f;
        sgAcy = ay + ah / 2f;
        sgAvatarR = Math.max(aw, ah) / 2f;
        sgRealCx = realX + realW / 2f;
        sgRealCy = realY + realH / 2f;
        sgTop = AndroidUtilities.statusBarHeight + dp(4);
        sgBottom = sgAcy + sgAvatarR + dp(26);
        final float closedAlpha = Utilities.clamp01((expandY - (AndroidUtilities.statusBarHeight + ActionBar.getCurrentActionBarHeight())) / dp(50));
        sgAlpha = (1.0f - expandProgress) * (1.0f - actionBarProgress) * closedAlpha;
        sgCollapse = collapseProgress;

        final long now = android.os.SystemClock.uptimeMillis();
        final float dt = sgLastFrame == 0 ? 1f / 60f : Math.min(1f / 30f, (now - sgLastFrame) / 1000f);
        sgLastFrame = now;
        sgTime += dt;
        if (sgAlpha > 0.01f) {
            sgStep(dt / 2f, now);
            sgStep(dt / 2f, now);
        }
        sgDraw(canvas, false, this);
        sgFront.invalidate();
        if (sgAlpha > 0.01f) {
            postInvalidateOnAnimation();
        } else {
            sgLastFrame = 0;
        }
    }

    private void sgStep(float dt, long now) {
        final int n = gifts.size();
        final float w = getWidth();
        for (int i = 0; i < n; i++) {
            final SgBubble b = gifts.get(i).sg;
            if (!b.placed) {
                b.placed = true;
                b.phase = sgRandom.nextFloat() * 100f;
                b.size = 0.85f + 0.3f * sgRandom.nextFloat();
                b.ring = sgRandom.nextFloat();
                b.spin = sgRandom.nextBoolean() ? 1f : 0.7f;
                final double a = Math.PI * 2 * (i + sgRandom.nextFloat() * 0.6f) / Math.max(1, n);
                b.x = (float) Math.cos(a) * (sgAvatarR + dp(50)) * 1.3f;
                b.y = (float) Math.sin(a) * (sgAvatarR + dp(30));
            }
            if (!b.snowPlaced) {
                b.snowPlaced = true;
                for (int k = 0; k < SG_FLAKES; k++) {
                    b.fx[k] = (sgRandom.nextFloat() * 2 - 1) * 0.5f;
                    b.fy[k] = 0.2f + sgRandom.nextFloat() * 0.4f;
                    b.fa[k] = sgRandom.nextFloat() * 60 - 30;
                }
            }
        }
        for (int i = 0; i < n; i++) {
            final SgBubble b = gifts.get(i).sg;
            final float r = dp(21) * b.size * b.inflate;
            // a ring round the avatar, wider than high; each keeps its own distance on it
            final float sx = 1.45f;
            final float ex = b.x / sx, ey = b.y;
            final float d = Math.max(1f, (float) Math.hypot(ex, ey));
            final float want = sgAvatarR + dp(26) + dp(26) * b.ring;
            float fx = -(d - want) * 5f * ex / d * sx;
            float fy = -(d - want) * 5f * ey / d;
            // a slow drift round it, and a wander of its own
            fx += -ey / d * dp(26) * b.spin * 1.6f - b.vx * 0.4f;
            fy += ex / d * dp(26) * b.spin * 1.6f - b.vy * 0.4f;
            fx += (float) Math.sin(sgTime * 0.9f + b.phase) * dp(60);
            fy += (float) Math.cos(sgTime * 0.7f + b.phase * 1.3f) * dp(50);
            // the header's edges push back softly
            final float left = -sgAcx + r, right = w - sgAcx - r, top = sgTop - sgAcy + r, bottom = sgBottom - sgAcy - r;
            if (b.x < left) fx += (left - b.x) * 40f;
            if (b.x > right) fx += (right - b.x) * 40f;
            if (b.y < top) fy += (top - b.y) * 40f;
            if (b.y > bottom) fy += (bottom - b.y) * 40f;
            // the others: bubbles do not go through each other, they squash a little where they touch
            for (int j = 0; j < n; j++) {
                if (j == i) continue;
                final SgBubble o = gifts.get(j).sg;
                final float or = dp(21) * o.size * o.inflate;
                final float dx = b.x - o.x, dy = b.y - o.y;
                final float dist = Math.max(0.5f, (float) Math.hypot(dx, dy));
                final float overlap = r + or + dp(3) - dist;
                if (overlap > 0) {
                    fx += dx / dist * overlap * 60f;
                    fy += dy / dist * overlap * 60f;
                    final float hit = Math.min(1f, overlap / r);
                    if (hit > Math.abs(b.squash)) {
                        b.squashV -= hit * 6f * dt * 60f;
                        b.squashAngle = (float) Math.atan2(dy, dx);
                    }
                }
            }
            b.lastVx = b.vx;
            b.lastVy = b.vy;
            b.vx += fx * dt;
            b.vy += fy * dt;
            final float damp = (float) Math.exp(-1.8f * dt);
            b.vx *= damp;
            b.vy *= damp;
            b.x += b.vx * dt;
            b.y += b.vy * dt;
            // springs: the beat's swell, the squash where it touched, a tap's swell (back by itself)
            b.pulseV += (-140f * b.pulse - 9f * b.pulseV) * dt;
            b.pulse += b.pulseV * dt;
            b.squashV += (-120f * b.squash - 7f * b.squashV) * dt;
            b.squash = Math.max(-0.35f, Math.min(0.35f, b.squash + b.squashV * dt));
            if (b.inflateTarget > 1f && now > b.inflateUntil) {
                b.inflateTarget = 1f;
            }
            b.inflateV += (-90f * (b.inflate - b.inflateTarget) - 11f * b.inflateV) * dt;
            b.inflate = Math.max(0.5f, b.inflate + b.inflateV * dt);
            sgStepSnow(b, dt, r);
        }
    }

    /** The snow inside a bubble: falls down the real gravity through thick water, lags behind the bubble and the phone. */
    private void sgStepSnow(SgBubble b, float dt, float r) {
        // in bubble radii per second squared
        final float gravity = 3.2f;
        final float bubbleAx = (b.vx - b.lastVx) / Math.max(dt, 1e-4f) / r, bubbleAy = (b.vy - b.lastVy) / Math.max(dt, 1e-4f) / r;
        float ax = sgGx * gravity - bubbleAx * 0.35f - sgAx * 0.55f;
        float ay = sgGy * gravity - bubbleAy * 0.35f - sgAy * 0.55f;
        final float max = 40f;
        ax = Math.max(-max, Math.min(max, ax));
        ay = Math.max(-max, Math.min(max, ay));
        final float drag = (float) Math.exp(-2.6f * dt);
        final float room = SG_FLAKE_ROOM;
        final float minGap = SG_FLAKE_SIZE * 0.8f;
        for (int k = 0; k < SG_FLAKES; k++) {
            b.fvx[k] = (b.fvx[k] + ax * dt) * drag;
            b.fvy[k] = (b.fvy[k] + ay * dt) * drag;
            b.fx[k] += b.fvx[k] * dt;
            b.fy[k] += b.fvy[k] * dt;
            b.fa[k] += b.fvx[k] * 40f * dt;
            // the bubble's wall
            final float d = (float) Math.hypot(b.fx[k], b.fy[k]);
            if (d > room) {
                final float nx = b.fx[k] / d, ny = b.fy[k] / d;
                b.fx[k] = nx * room;
                b.fy[k] = ny * room;
                final float vn = b.fvx[k] * nx + b.fvy[k] * ny;
                if (vn > 0) {
                    b.fvx[k] -= vn * nx * 1.3f;
                    b.fvy[k] -= vn * ny * 1.3f;
                }
                b.fvx[k] *= 0.9f;
                b.fvy[k] *= 0.9f;
            }
        }
        // they lie on each other instead of in one spot
        for (int k = 0; k < SG_FLAKES; k++) {
            for (int m = k + 1; m < SG_FLAKES; m++) {
                final float dx = b.fx[m] - b.fx[k], dy = b.fy[m] - b.fy[k];
                final float d = (float) Math.hypot(dx, dy);
                if (d < minGap && d > 1e-4f) {
                    final float push = (minGap - d) / 2f;
                    final float nx = dx / d, ny = dy / d;
                    b.fx[k] -= nx * push;
                    b.fy[k] -= ny * push;
                    b.fx[m] += nx * push;
                    b.fy[m] += ny * push;
                }
            }
        }
    }

    /** {@code front}: the bubbles in front of the avatar (those coming nearer, and swollen ones), else those behind. */
    private void sgDraw(Canvas canvas, boolean front, View owner) {
        if (front) {
            sgGlassFrontUsed = 0;
        }
        if (gifts.isEmpty() || sgAlpha <= 0.01f || expandProgress >= 1f || collapseProgress <= 0f) {
            sgHideUnusedGlass(front);
            return;
        }
        final float viewAlpha = front ? getAlpha() : 1f;
        if (front && viewAlpha <= 0.01f) {
            sgHideUnusedGlass(true);
            return;
        }
        canvas.save();
        canvas.clipRect(0, 0, getWidth(), expandY);
        for (int i = 0; i < gifts.size(); i++) {
            final Gift gift = gifts.get(i);
            final SgBubble b = gift.sg;
            if (!b.placed) {
                continue;
            }
            // nearer and farther, slowly: the near ones pass in front of the avatar
            final float depth = (float) Math.sin(sgTime * 0.45f + b.phase * 0.7f);
            final boolean isFront = depth > 0 || b.inflate > 1.15f;
            if (isFront != front) {
                continue;
            }
            final float enter = gift.animatedFloat.set(1.0f);
            final float collapse = Utilities.clamp01(isOpening || enter >= 1f ? sgCollapse : Math.min(enter, sgCollapse));
            final float ease = 1f - (1f - collapse) * (1f - collapse);
            final float cx = AndroidUtilities.lerp(sgRealCx, sgAcx + b.x, ease);
            final float cy = AndroidUtilities.lerp(sgRealCy, sgAcy + b.y, ease);
            final float scale = (0.5f + 0.5f * ease) * (1f + 0.06f * depth) * (1f + 0.12f * b.pulse) * b.inflate;
            final float r = dp(21) * b.size * scale * gift.bounce.getScale(0.1f);
            b.drawX = cx;
            b.drawY = cy;
            b.drawR = r;
            final float alpha = enter * sgAlpha * (0.8f + 0.2f * (depth + 1f) / 2f) * viewAlpha;
            sgDrawBubble(canvas, gift, cx, cy, r, alpha, front, owner);
        }
        canvas.restore();
        sgHideUnusedGlass(front);
    }

    /** The bubble's outline: a circle that slowly flows into other soft shapes, squashed where it hit and as it moves. */
    private void sgBuildShape(SgBubble b, float cx, float cy, float r) {
        sgPath.rewind();
        final int points = 24;
        final float speed = (float) Math.hypot(b.vx, b.vy);
        final float stretch = Math.min(0.1f, speed / dp(1000));
        final float moveAngle = (float) Math.atan2(b.vy, b.vx);
        final float t1 = sgTime * 0.9f + b.phase, t2 = sgTime * 0.6f + b.phase * 1.7f, t3 = sgTime * 1.3f + b.phase * 0.4f;
        float firstX = 0, firstY = 0, prevX = 0, prevY = 0;
        for (int k = 0; k <= points; k++) {
            final float t = (float) (Math.PI * 2 * (k % points) / points);
            // low harmonics only, slow: it flows, never ripples
            float rr = 1f + 0.05f * (float) Math.sin(2 * t + t1) + 0.035f * (float) Math.sin(3 * t - t2) + 0.015f * (float) Math.sin(4 * t + t3)
                + 0.05f * b.pulse * (float) Math.sin(2 * t + b.phase);
            rr *= 1f + stretch * (float) Math.cos(2 * (t - moveAngle));
            rr *= 1f + b.squash * (float) Math.cos(2 * (t - b.squashAngle));
            final float px = cx + (float) Math.cos(t) * r * rr;
            final float py = cy + (float) Math.sin(t) * r * rr;
            if (k == 0) {
                firstX = px;
                firstY = py;
            } else if (k == 1) {
                sgPath.moveTo((firstX + px) / 2f, (firstY + py) / 2f);
            } else {
                sgPath.quadTo(prevX, prevY, (prevX + px) / 2f, (prevY + py) / 2f);
            }
            prevX = px;
            prevY = py;
        }
        sgPath.close();
    }

    private void sgDrawBubble(Canvas canvas, Gift gift, float cx, float cy, float r, float alpha, boolean front, View owner) {
        final SgBubble b = gift.sg;
        final int color = gift.color;
        // the glow round it
        if (gift.gradientPaint != null) {
            gift.gradientPaint.setAlpha((int) (0x80 * alpha));
            canvas.save();
            canvas.translate(cx, cy);
            final float g = r / dp(22.5f) * 1.5f;
            canvas.scale(g, g);
            canvas.drawCircle(0, 0, dp(22.5f), gift.gradientPaint);
            canvas.restore();
        }
        sgBuildShape(b, cx, cy, r);
        canvas.save();
        canvas.clipPath(sgPath);
        // the lens: what is under it, refracted (the glass of the header; clipped to the flowing shape)
        final boolean glass = sgGlassFactory != null && canvas.isHardwareAccelerated();
        if (glass) {
            sgDrawGlass(canvas, front, owner, cx, cy, r * 1.12f, alpha);
        }
        // jelly glass: clear in the middle, the colour gathers at the rim
        if (b.body == null || b.bodyColor != color) {
            b.bodyColor = color;
            b.body = new RadialGradient(-dp(21) * 0.15f, -dp(21) * 0.2f, dp(21) * 1.15f,
                new int[]{Theme.multAlpha(color, 0.12f), Theme.multAlpha(color, 0.32f), Theme.multAlpha(androidx.core.graphics.ColorUtils.blendARGB(color, 0xFFFFFFFF, 0.25f), 0.78f)},
                new float[]{0f, 0.65f, 1f}, Shader.TileMode.CLAMP);
        }
        sgMatrix.reset();
        sgMatrix.postScale(r / dp(21), r / dp(21));
        sgMatrix.postTranslate(cx, cy);
        b.body.setLocalMatrix(sgMatrix);
        sgFill.setShader(b.body);
        sgFill.setAlpha((int) (0xFF * alpha * (glass ? 0.85f : 1f)));
        canvas.drawPaint(sgFill);
        // the snow: the pattern's symbol, settled at the bottom or swirling
        if (gift.sgPattern != null) {
            final int fs = (int) (r * SG_FLAKE_SIZE);
            gift.sgPattern.setAlpha((int) (0xE6 * alpha));
            for (int k = 0; k < SG_FLAKES; k++) {
                final float fx = cx + b.fx[k] * r, fy = cy + b.fy[k] * r;
                canvas.save();
                canvas.rotate(b.fa[k], fx, fy);
                gift.sgPattern.setBounds((int) (fx - fs / 2f), (int) (fy - fs / 2f), (int) (fx + fs / 2f), (int) (fy + fs / 2f));
                gift.sgPattern.draw(canvas);
                canvas.restore();
            }
        }
        // the gift itself in the middle
        if (gift.emojiDrawable != null) {
            final int sz = (int) (dp(24) * r / dp(21));
            gift.emojiDrawable.setBounds((int) (cx - sz / 2f), (int) (cy - sz / 2f), (int) (cx + sz / 2f), (int) (cy + sz / 2f));
            gift.emojiDrawable.setAlpha((int) (0xFF * alpha));
            gift.emojiDrawable.draw(canvas);
        }
        canvas.restore();
        // the rim and the shine on top
        sgRim.setStrokeWidth(Math.max(1f, dp(1.1f) * r / dp(21)));
        sgRim.setColor(Theme.multAlpha(0xFFFFFFFF, 0.4f * alpha));
        canvas.drawPath(sgPath, sgRim);
        if (sgShineShader == null) {
            sgShineShader = new RadialGradient(0, 0, dp(10), new int[]{0x99FFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP);
            sgShine.setShader(sgShineShader);
        }
        sgMatrix.reset();
        sgMatrix.postScale(r / dp(21) * 1.1f, r / dp(21) * 0.75f);
        sgMatrix.postRotate(-35);
        sgMatrix.postTranslate(cx - r * 0.42f, cy - r * 0.48f);
        sgShineShader.setLocalMatrix(sgMatrix);
        sgShine.setAlpha((int) (0xFF * alpha));
        canvas.drawCircle(cx - r * 0.42f, cy - r * 0.48f, r * 0.6f, sgShine);
    }

    // endregion

    public final ArrayList<Gift> oldGifts = new ArrayList<>();
    public final ArrayList<Gift> gifts = new ArrayList<>();
    public final HashSet<Long> giftIds = new HashSet<>();
    public int maxCount;

    public void update() {
        try {
            sgUpdate();
        } finally {
            sgUpdateBeat();
            sgUpdateSensors();
        }
    }

    private void sgUpdate() {
        if (!MessagesController.getInstance(currentAccount).enableGiftsInProfile) {
            return;
        }

        maxCount = MessagesController.getInstance(currentAccount).stargiftsPinnedToTopLimit;
        oldGifts.clear();
        oldGifts.addAll(gifts);
        gifts.clear();
        giftIds.clear();

        final TLRPC.EmojiStatus emojiStatus;
        if (dialogId >= 0) {
            final TLRPC.User user = MessagesController.getInstance(currentAccount).getUser(dialogId);
            emojiStatus = user == null ? null : user.emoji_status;
        } else {
            final TLRPC.User chat = MessagesController.getInstance(currentAccount).getUser(-dialogId);
            emojiStatus = chat == null ? null : chat.emoji_status;
        }
        if (emojiStatus instanceof TLRPC.TL_emojiStatusCollectible) {
            giftIds.add(((TLRPC.TL_emojiStatusCollectible) emojiStatus).collectible_id);
        }
        list = StarsController.getInstance(currentAccount).getProfileGiftsList(dialogId);
        if (list != null) {
            for (int i = 0; i < list.gifts.size(); i++) {
                final TL_stars.SavedStarGift savedGift = list.gifts.get(i);
                if (!savedGift.unsaved && savedGift.pinned_to_top && savedGift.gift instanceof TL_stars.TL_starGiftUnique) {
                    final Gift gift = new Gift((TL_stars.TL_starGiftUnique) savedGift.gift);
                    if (!giftIds.contains(gift.id)) {
                        gifts.add(gift);
                        giftIds.add(gift.id);
                    }
                }
            }
            // Solegram: more of the profile's collectibles float with the pinned ones
            if (sgOrbit) {
                for (int i = 0; i < list.gifts.size() && gifts.size() < SG_MAX; i++) {
                    final TL_stars.SavedStarGift savedGift = list.gifts.get(i);
                    if (!savedGift.unsaved && savedGift.gift instanceof TL_stars.TL_starGiftUnique) {
                        final Gift gift = new Gift((TL_stars.TL_starGiftUnique) savedGift.gift);
                        if (!giftIds.contains(gift.id)) {
                            gifts.add(gift);
                            giftIds.add(gift.id);
                        }
                    }
                }
            }
        }

        boolean changed = false;
        if (gifts.size() != oldGifts.size()) {
            changed = true;
        } else for (int i = 0; i < gifts.size(); i++) {
            if (!gifts.get(i).equals(oldGifts.get(i))) {
                changed = true;
                break;
            }
        }

        for (int i = 0; i < gifts.size(); i++) {
            final Gift g = gifts.get(i);
            Gift oldGift = null;
            for (int j = 0; j < oldGifts.size(); ++j) {
                if (oldGifts.get(j).id == g.id) {
                    oldGift = oldGifts.get(j);
                    break;
                }
            }

            if (oldGift != null) {
                g.copy(oldGift);
            } else {
                g.gradient = new RadialGradient(0, 0, dp(22.5f), new int[]{g.color, Theme.multAlpha(g.color, 0.0f)}, new float[]{0, 1}, Shader.TileMode.CLAMP);
                g.gradientPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                g.gradientPaint.setShader(g.gradient);
                if (g.document != null) {
                    g.emojiDrawable = AnimatedEmojiDrawable.make(currentAccount, AnimatedEmojiDrawable.CACHE_TYPE_MESSAGES, g.document);
                } else {
                    g.emojiDrawable = AnimatedEmojiDrawable.make(currentAccount, AnimatedEmojiDrawable.CACHE_TYPE_MESSAGES, g.documentId);
                }
                g.animatedFloat = new AnimatedFloat(this, 0, 320, null);
                g.animatedFloat.force(0.0f);
                if (sgOrbit && (g.sgPatternDocument != null || g.sgPatternDocumentId != 0)) {
                    g.sgPattern = g.sgPatternDocument != null
                        ? AnimatedEmojiDrawable.make(currentAccount, AnimatedEmojiDrawable.CACHE_TYPE_MESSAGES, g.sgPatternDocument)
                        : AnimatedEmojiDrawable.make(currentAccount, AnimatedEmojiDrawable.CACHE_TYPE_MESSAGES, g.sgPatternDocumentId);
                    g.sgPattern.setColorFilter(new android.graphics.PorterDuffColorFilter(
                        androidx.core.graphics.ColorUtils.blendARGB(g.sgPatternColor, 0xFFFFFFFF, 0.45f), android.graphics.PorterDuff.Mode.SRC_IN));
                }
                if (isAttachedToWindow()) {
                    g.emojiDrawable.addView(this);
                    if (g.sgPattern != null) {
                        g.sgPattern.addView(this);
                    }
                }
            }
        }

        List<Integer> positions = new ArrayList<>();
        for (int i = 0; i < maxCount; i++) {
            positions.add(i);
        }

        for (int i = 0; i < oldGifts.size(); i++) {
            final Gift g = oldGifts.get(i);
            Gift newGift = null;
            for (int j = 0; j < gifts.size(); ++j) {
                if (gifts.get(j).id == g.id) {
                    newGift = gifts.get(j);
                    break;
                }
            }
            if (newGift == null) {
                g.emojiDrawable.removeView(this);
                g.emojiDrawable = null;
                if (g.sgPattern != null) {
                    g.sgPattern.removeView(this);
                    g.sgPattern = null;
                }
                g.gradient = null;
            } else {
                positions.remove((Object) g.position);
            }
        }

        if (!positions.isEmpty()) {
            BagRandomizer<Integer> bagRandomizer = new BagRandomizer<>(positions);
            for (int i = 0; i < gifts.size(); ++i) {
                final Gift g = gifts.get(i);
                if (g.position == -1) {
                    g.position = bagRandomizer.next();
                }
            }
        }

        if (changed)
            invalidate();
    }

    private final TimeInterpolator giftCollapseXInterpolator = new DecelerateInterpolator();
    private final TimeInterpolator giftCollapseYInterpolator = new LinearInterpolator();

    @Override
    protected void dispatchDraw(@NonNull Canvas canvas) {
        if (sgOrbit) {
            sgStepAndDraw(canvas);
            return;
        }
        if (gifts.isEmpty() || expandProgress >= 1.0f || collapseProgress <= 0f) return;

        final float realX = avatarContainer.getX();
        final float realY = avatarContainer.getY();
        final float realW = (avatarContainer.getWidth()) * avatarContainer.getScaleX();
        final float realH = (avatarContainer.getHeight()) * avatarContainer.getScaleY();

        final float sz = dpf2(96);
        final float ax = Math.min(realX, (getWidth() - sz) / 2f);
        final float ay = Math.max(realY, (maxExpandY - sz) / 2f);
        final float aw = Math.max(realW, sz);
        final float ah = Math.max(realH, sz);


        canvas.save();
        canvas.clipRect(0, 0, getWidth(), expandY);

        final float acx = ax + aw / 2.0f;
        final float acy = ay + ah / 2.0f;

        final float realCX = realX + realW / 2.0f;
        final float realCY = realY + realH / 2.0f;

        float expandScale = expandY / maxExpandY;
        float closedAlpha = Utilities.clamp01((expandY - (AndroidUtilities.statusBarHeight + ActionBar.getCurrentActionBarHeight())) / dp(50));

        for (int i = 0; i < gifts.size(); ++i) {
            final Gift gift = gifts.get(i);
            final float enter = gift.animatedFloat.set(1.0f);
            float scale = lerp(0.5f, 1.0f, enter);
            final float alpha = enter * (1.0f - expandProgress) * (1.0f - actionBarProgress) * (closedAlpha);

            float gx, gy;
            float delayValue;

            switch (gift.position) {
                case 0: // left
                    gx = acx / 2f - (dp(20) * expandScale);
                    gy = acy - dp(13);
                    delayValue = 1.6f;
                    break;
                case 1: // top left
                    gx = acx * 2f / 3f - (dp(6) * expandScale);
                    gy = ay - dp(4);
                    delayValue = 0;
                    break;
                case 2: // bottom left
                    gx = acx * 2f / 3f - (dp(12) * expandScale);
                    gy = ay + ah - dp(16);
                    delayValue = 0.9f;
                    break;
                case 3: // right
                    gx = acx * 1.5f + (dp(20) * expandScale);
                    gy = acy - dp(13);
                    delayValue = 1.6f;
                    break;
                case 4: // top right
                    gx = acx * 4f / 3f + (dp(12) * expandScale);
                    gy = ay - dp(4);
                    delayValue = 0.9f;
                    break;
                default: // bottom right
                    gx = acx * 4f / 3f + (dp(12) * expandScale);
                    gy = ay + ah - dp(16);
                    delayValue = 0;
                    break;
            }

            final float collapseProgressWithEnter;
            if (isOpening || enter >= 1f) {
                collapseProgressWithEnter = collapseProgress;
            } else {
                collapseProgressWithEnter = Math.min(enter, collapseProgress);
            }

            final float delayFraction = 0.2f;
            final float maxDelayFraction = 1.6f * delayFraction;
            final float intervalFraction = 1f - maxDelayFraction;

            float delay = delayValue * delayFraction;
            float collapse = collapseProgressWithEnter >= 1f - delay ? 1f
                    : Utilities.clamp01((collapseProgressWithEnter - maxDelayFraction + delay) / intervalFraction);
            if (collapse < 1f) {
                gx = AndroidUtilities.lerp(realCX, gx, giftCollapseXInterpolator.getInterpolation(collapse));
                gy = AndroidUtilities.lerp(realCY, gy, giftCollapseYInterpolator.getInterpolation(collapse));
                scale = AndroidUtilities.lerp(scale / 2f, scale, collapse);
            }

            gift.draw(canvas, gx, gy, scale, 0, alpha, 1f);
        }

        canvas.restore();
    }

    public Gift getGiftUnder(float x, float y) {
        if (sgOrbit) {
            return sgGiftUnder(x, y);
        }
        for (int i = 0; i < gifts.size(); ++i) {
            if (gifts.get(i).bounds.contains(x, y))
                return gifts.get(i);
        }
        return null;
    }

    private Gift pressedGift;

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!active) return false;
        final Gift hit = getGiftUnder(event.getX(), event.getY());
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            pressedGift = hit;
            if (pressedGift != null) {
                pressedGift.bounce.setPressed(true);
            }
        } else if (event.getAction() == MotionEvent.ACTION_MOVE) {
            if (pressedGift != hit && pressedGift != null) {
                pressedGift.bounce.setPressed(false);
                pressedGift = null;
            }
        } else if (event.getAction() == MotionEvent.ACTION_UP) {
            if (pressedGift != null) {
                if (sgOrbit) {
                    sgTap(pressedGift);
                } else {
                    onGiftClick(pressedGift);
                }
                pressedGift.bounce.setPressed(false);
                pressedGift = null;
            }
        } else if (event.getAction() == MotionEvent.ACTION_CANCEL) {
            if (pressedGift != null) {
                pressedGift.bounce.setPressed(false);
                pressedGift = null;
            }
        }
        return pressedGift != null;
    }

    public void onGiftClick(Gift gift) {
        Browser.openUrl(getContext(), "https://t.me/nft/" + gift.slug);
    }
}
