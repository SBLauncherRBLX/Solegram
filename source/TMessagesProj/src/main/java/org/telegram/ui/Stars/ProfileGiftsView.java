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
import android.graphics.Path;
import android.graphics.LinearGradient;
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
        NotificationCenter.getInstance(currentAccount).addWeakObserver(this, NotificationCenter.starGiftsLoaded);

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
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.starGiftsLoaded);

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
        if (id == NotificationCenter.starGiftsLoaded) {
            if (sgTestGiftsWanted()) {
                update();
            }
        } else if (id == NotificationCenter.starUserGiftsLoaded) {
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

        /** Solegram (betas): a test gift on the own profile, only on this phone; the gift itself snows inside. */
        Gift(long fakeId, TLRPC.Document sticker, int color, int patternColor) {
            id = fakeId;
            document = sticker;
            documentId = sticker.id;
            this.color = color | 0xFF000000;
            slug = null;
            sgPatternDocument = sticker;
            sgPatternDocumentId = sticker.id;
            sgPatternColor = patternColor | 0xFF000000;
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

        /** Solegram: the bubble's colour = the sticker's own average colour (the backdrop's until the sticker is drawn). */
        int sgTone;
        private int sgToneTries;
        private long sgToneAt;

        int sgTone() {
            if (sgTone != 0 || sgToneTries >= 12) {
                return sgTone != 0 ? sgTone : color;
            }
            final long now = android.os.SystemClock.uptimeMillis();
            if (now - sgToneAt < 400) {
                return color;
            }
            sgToneAt = now;
            sgToneTries++;
            try {
                final org.telegram.messenger.ImageReceiver ir = emojiDrawable != null ? emojiDrawable.getImageReceiver() : null;
                android.graphics.Bitmap bm = ir != null ? ir.getBitmap() : null;
                if (bm == null || bm.isRecycled()) {
                    return color;
                }
                if (android.os.Build.VERSION.SDK_INT >= 26 && bm.getConfig() == android.graphics.Bitmap.Config.HARDWARE) {
                    bm = bm.copy(android.graphics.Bitmap.Config.ARGB_8888, false);
                }
                final android.graphics.Bitmap small = android.graphics.Bitmap.createScaledBitmap(bm, 24, 24, true);
                final int tone = sgAverage(small);
                if (small != bm) small.recycle();
                if (tone != 0) {
                    sgTone = tone;
                }
            } catch (Throwable e) {
                sgToneTries = 12;
            }
            return sgTone != 0 ? sgTone : color;
        }

        /** Solegram: the gift's emoji (the sticker's own one, e.g. 🧸 for a bear), drawn small inside its bubble. */
        private android.graphics.drawable.Drawable sgEmoji;
        private int sgEmojiTries;

        android.graphics.drawable.Drawable sgEmoji() {
            if (sgEmoji != null || sgEmojiTries >= 400) {
                return sgEmoji;
            }
            sgEmojiTries++;
            try {
                TLRPC.Document doc = document;
                if (doc == null && emojiDrawable != null) {
                    doc = emojiDrawable.getDocument();
                }
                if (doc == null && sgEmojiTries < 400) {
                    return null; // the collectible's sticker is not here yet: ask again on a later frame
                }
                String e = org.telegram.messenger.MessageObject.findAnimatedEmojiEmoticon(doc, null);
                android.graphics.drawable.Drawable d = sgEmojiOf(e);
                if (d == null && e != null) {
                    d = sgEmojiOf(e.replace("\uFE0F", ""));
                }
                if (d == null) {
                    d = sgEmojiOf("\u2728");
                }
                sgEmoji = d;
                sgEmojiTries = 400;
            } catch (Throwable t) {
                sgEmojiTries = 400;
            }
            return sgEmoji;
        }

        private android.graphics.drawable.Drawable sgEmojiOf(String e) {
            if (android.text.TextUtils.isEmpty(e)) {
                return null;
            }
            // the first emoji of the text (a sticker may carry several)
            final CharSequence one = org.telegram.messenger.Emoji.fullyConsistsOfEmojis(e) ? e : null;
            android.graphics.drawable.Drawable d = one != null ? org.telegram.messenger.Emoji.getEmojiDrawable(one) : null;
            if (d == null) {
                final int n = Character.charCount(e.codePointAt(0));
                d = org.telegram.messenger.Emoji.getEmojiDrawable(e.substring(0, n));
            }
            return d;
        }

        public void copy(Gift b) {
            sgEmoji = b.sgEmoji;
            sgEmojiTries = b.sgEmojiTries;
            sgTone = b.sgTone;
            sgToneTries = b.sgToneTries;
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
    private static final int SG_FLAKES = 14;
    /** Where the symbols stay inside, in bubble radii; their size in bubble radii. */
    private static final float SG_FLAKE_ROOM = 0.8f, SG_FLAKE_SIZE = 0.3f;

    /** One floating bubble: where it is (px from the avatar's centre), how it moves, wobbles, swells; its snow. */
    static final class SgBubble {
        boolean placed;
        // the bloom round it: the gift's colour made richer (not wider)
        Paint glow;
        int glowColor;
        // its light on the avatar's glass ring
        RadialGradient reflect;
        int reflectColor;
        // the liquid film near its edge
        Shader film1, film2;
        int filmColor;
        float x, y, vx, vy, lastVx, lastVy;
        float phase, size = 1f, ring, spin = 1f;
        // its orbit: where on it, how fast; the orbit itself drifts slowly (radius, tilt, height)
        float orbit, orbitSpeed;
        float pulse, pulseV;
        // elastic shape: the ellipse mode (cos 2θ, sin 2θ) on a spring, driven by the smoothed acceleration
        float ex, ey, exv, eyv, axs, ays;
        // where it presses against others: the plane's direction and distance (in its radii), at most 4
        final float[] cnx = new float[4], cny = new float[4], clim = new float[4];
        int contacts;
        float volume = 1f;
        /** The beat's flash of its colour, 1 → 0. */
        float flash;
        /** How strongly the outline flows now (each breathes in its own rhythm). */
        float w2 = SG_WOBBLE_2, w3 = SG_WOBBLE_3;
        // the push of the others this step
        float cfx, cfy;
        float inflate = 1f, inflateV, inflateTarget = 1f;
        long inflateUntil;
        float drawX, drawY, drawR;
        RadialGradient body;
        int bodyColor;
        // the snow: positions and speeds in bubble radii from its centre, and their turn
        final float[] fx = new float[SG_FLAKES], fy = new float[SG_FLAKES], fvx = new float[SG_FLAKES], fvy = new float[SG_FLAKES], fa = new float[SG_FLAKES];
        // each speck's own place it drifts round; the bubble's usual (slow) acceleration, which the specks ignore
        final float[] hx = new float[SG_FLAKES], hy = new float[SG_FLAKES];
        float sax, say;
        boolean snowPlaced;

        void copy(SgBubble b) {
            placed = b.placed;
            x = b.x;
            y = b.y;
            vx = b.vx;
            vy = b.vy;
            phase = b.phase;
            orbit = b.orbit;
            orbitSpeed = b.orbitSpeed;
            size = b.size;
            ring = b.ring;
            spin = b.spin;
            snowPlaced = b.snowPlaced;
            System.arraycopy(b.fx, 0, fx, 0, SG_FLAKES);
            System.arraycopy(b.fy, 0, fy, 0, SG_FLAKES);
            System.arraycopy(b.fa, 0, fa, 0, SG_FLAKES);
            System.arraycopy(b.hx, 0, hx, 0, SG_FLAKES);
            System.arraycopy(b.hy, 0, hy, 0, SG_FLAKES);
            sax = b.sax;
            say = b.say;
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
    private final Matrix sgMatrix = new Matrix();

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

    private final ArrayList<Object> sgLensBack = new ArrayList<>(), sgLensFront = new ArrayList<>();
    private static boolean sgLensBroken;

    /**
     * What is under the bubble, refracted: the header's capture (a glass drawable, plain, as big as the outline can
     * get) drawn through the lens shader (Android 13+) that bends it towards the middle near this outline's edge.
     */
    private void sgDrawLens(Canvas canvas, boolean front, View owner, SgBubble b, float cx, float cy, float r, float alpha) {
        final ArrayList<org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable> list = front ? sgGlassFront : sgGlassBack;
        final int used = front ? sgGlassFrontUsed++ : sgGlassBackUsed++;
        if (used == list.size()) {
            list.add(sgGlassFactory.create(owner, org.telegram.ui.Components.ProfileButtonsGlass.NO_COLORS));
        }
        final org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable glass = list.get(used);
        final float reach = r * 1.35f;
        final int left = (int) Math.floor(cx - reach), top = (int) Math.floor(cy - reach);
        final int right = (int) Math.ceil(cx + reach), bottom = (int) Math.ceil(cy + reach);
        glass.setRadius(0);
        glass.setBounds(left, top, right, bottom);
        if (android.os.Build.VERSION.SDK_INT >= 33 && !sgLensBroken) {
            try {
                final ArrayList<Object> lenses = front ? sgLensFront : sgLensBack;
                while (lenses.size() <= used) {
                    lenses.add(new SgLens());
                }
                glass.setAlpha(0xFF);
                ((SgLens) lenses.get(used)).draw(canvas, glass, left, top, right, bottom, cx - left, cy - top, r, b, sgC1, sgS1, sgC2, sgS2, alpha);
                return;
            } catch (Throwable e) {
                // a phone whose graphics cannot run the shader: plain glass from now on
                org.telegram.messenger.FileLog.e(e);
                sgLensBroken = true;
            }
        }
        {
            glass.setAlpha((int) (0xFF * alpha));
            glass.draw(canvas);
        }
    }

    /** The lens (Android 13+): a render node of the capture with a runtime shader on it. */
    @androidx.annotation.RequiresApi(33)
    private static final class SgLens {
        private static final String SHADER =
            "uniform shader content;\n" +
            "uniform float2 center;\n" +
            "uniform float radius;\n" +
            "uniform float4 ph;\n" +
            "uniform float2 ell;\n" +
            "uniform float4 k0;\n" +
            "uniform float4 k1;\n" +
            "uniform float4 k2;\n" +
            "uniform float4 k3;\n" +
            "uniform float3 wob;\n" +
            "float smin(float a, float b, float k) {\n" +
            "  float h = clamp(0.5 + 0.5 * (b - a) / k, 0.0, 1.0);\n" +
            "  return mix(b, a, h) - k * h * (1.0 - h);\n" +
            "}\n" +
            "float press(float f, float2 d, float4 c) {\n" +
            "  if (c.w < 0.5) { return f; }\n" +
            "  float cs = dot(d, c.xy);\n" +
            "  if (cs <= 0.05) { return f; }\n" +
            "  return smin(f, c.z / cs, wob.z);\n" +
            "}\n" +
            "float shape(float2 d) {\n" +
            "  float cos2 = d.x * d.x - d.y * d.y;\n" +
            "  float sin2 = 2.0 * d.x * d.y;\n" +
            "  float cos3 = 4.0 * d.x * d.x * d.x - 3.0 * d.x;\n" +
            "  float sin3 = 3.0 * d.y - 4.0 * d.y * d.y * d.y;\n" +
            "  float f = 1.0 + wob.x * (sin2 * ph.x + cos2 * ph.y) + wob.y * (sin3 * ph.z - cos3 * ph.w) + ell.x * cos2 + ell.y * sin2;\n" +
            "  f = press(f, d, k0);\n" +
            "  f = press(f, d, k1);\n" +
            "  f = press(f, d, k2);\n" +
            "  f = press(f, d, k3);\n" +
            "  return f;\n" +
            "}\n" +
            "half4 main(float2 p) {\n" +
            "  float2 v = p - center;\n" +
            "  float len = length(v);\n" +
            "  if (len < 0.001) { return content.eval(p); }\n" +
            "  float rho = len / (radius * shape(v / len));\n" +
            "  if (rho >= 1.0) { return content.eval(p); }\n" +
            "  float r2 = rho * rho;\n" +
            "  float r4 = r2 * r2;\n" +
            // a slight magnifier inside, bending strongly near the edge (the light comes from deeper in)
            "  float2 src = center + v * (0.9 - 0.3 * r4 * r2);\n" +
            "  float2 spl = v * 0.025 * r4;\n" +
            "  half4 col = content.eval(src);\n" +
            "  half red = content.eval(src + spl).r;\n" +
            "  half blue = content.eval(src - spl).b;\n" +
            "  return half4(red, col.g, blue, col.a);\n" +
            "}\n";

        private final android.graphics.RenderNode node = new android.graphics.RenderNode("sgBubbleLens");
        private final android.graphics.RuntimeShader shader = new android.graphics.RuntimeShader(SHADER);

        void draw(Canvas canvas, android.graphics.drawable.Drawable glass, int left, int top, int right, int bottom,
                  float cx, float cy, float r, SgBubble b, float c1, float s1, float c2, float s2, float alpha) {
            node.setPosition(left, top, right, bottom);
            final android.graphics.RecordingCanvas rc = node.beginRecording();
            rc.translate(-left, -top);
            glass.draw(rc);
            node.endRecording();
            shader.setFloatUniform("center", cx, cy);
            shader.setFloatUniform("radius", r);
            shader.setFloatUniform("ph", c1, s1, c2, s2);
            shader.setFloatUniform("ell", b.ex, b.ey);
            shader.setFloatUniform("wob", b.w2, b.w3, SG_SMOOTH);
            for (int k = 0; k < 4; k++) {
                final boolean on = k < b.contacts;
                shader.setFloatUniform("k" + k, on ? b.cnx[k] : 0, on ? b.cny[k] : 0, on ? b.clim[k] : 2f, on ? 1f : 0f);
            }
            node.setRenderEffect(android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "content"));
            node.setAlpha(alpha);
            canvas.drawRenderNode(node);
        }
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
            // each flashes its own colour on the beat (they do not move for it)
            b.flash = Math.max(b.flash, Math.min(1f, strength * (0.8f + 0.4f * sgRandom.nextFloat())));
        }
        invalidate();
    };

    /** Whether the profile's own music plays now (the bubbles flash to its beat then). */
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

    /** Whether a touch at (x, y) (this view's coordinates) is on a bubble that should take it. */
    public boolean sgTakesTouch(float x, float y) {
        if (!sgOrbit || !active || sgAlpha <= 0.01f || sgAway > 0.01f) {
            return false;
        }
        final Gift g = sgGiftUnder(x, y);
        if (g == null) {
            return false;
        }
        final SgBubble b = g.sg;
        final boolean isFront = Math.sin(b.orbit) > 0 || b.inflate > 1.15f || b == sgHeld;
        if (!isFront) {
            // behind the avatar: the avatar keeps its own taps there
            final float ax = avatarContainer.getX(), ay = avatarContainer.getY();
            final float aw = avatarContainer.getWidth() * avatarContainer.getScaleX(), ah = avatarContainer.getHeight() * avatarContainer.getScaleY();
            if (x >= ax && x <= ax + aw && y >= ay && y <= ay + ah) {
                return false;
            }
        }
        return true;
    }

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

    /** A tap opens the gift (and stirs its snow). */
    private void sgTap(Gift gift) {
        gift.sg.stir(sgRandom, 2.5f);
        onGiftClick(gift);
        invalidate();
    }

    // region holding a bubble: it swells and follows the finger until it is let go

    @androidx.annotation.Nullable
    private SgBubble sgHeld;
    private Gift sgPressed;
    private float sgFingerX, sgFingerY, sgDownX, sgDownY;
    private boolean sgMoved;
    private final Runnable sgHoldRunnable = () -> {
        if (sgPressed != null) {
            sgStartHold(sgPressed);
        }
    };

    private void sgStartHold(Gift gift) {
        sgHeld = gift.sg;
        sgHeld.inflateTarget = 1.9f;
        sgHeld.inflateUntil = Long.MAX_VALUE;
        sgHeld.stir(sgRandom, 3f);
        gift.bounce.setPressed(false);
        if (getParent() != null) {
            getParent().requestDisallowInterceptTouchEvent(true);
        }
        try {
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
        } catch (Throwable ignore) {
        }
        invalidate();
    }

    private void sgRelease() {
        if (sgHeld != null) {
            sgHeld.inflateTarget = 1f;
            sgHeld.inflateUntil = 0;
            // its place on the orbit: the nearest to where it was let go, so it does not fly round to the old one
            final float a = (float) Math.atan2(sgHeld.y / 0.62f, sgHeld.x / 1.45f);
            final float twoPi = (float) (Math.PI * 2);
            float k = Math.round((sgHeld.orbit - a) / twoPi);
            sgHeld.orbit = a + k * twoPi;
            sgHeld = null;
            invalidate();
        }
    }

    private boolean sgTouch(MotionEvent event) {
        if (!active) {
            return false;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                sgPressed = sgGiftUnder(event.getX(), event.getY());
                if (sgPressed == null) {
                    return false;
                }
                sgFingerX = sgDownX = event.getX();
                sgFingerY = sgDownY = event.getY();
                sgMoved = false;
                sgPressed.bounce.setPressed(true);
                removeCallbacks(sgHoldRunnable);
                postDelayed(sgHoldRunnable, 220);
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (sgPressed == null) {
                    return false;
                }
                sgFingerX = event.getX();
                sgFingerY = event.getY();
                if (!sgMoved && Math.hypot(sgFingerX - sgDownX, sgFingerY - sgDownY) > android.view.ViewConfiguration.get(getContext()).getScaledTouchSlop()) {
                    sgMoved = true;
                    if (sgHeld == null) {
                        // pulled at once: held right away
                        removeCallbacks(sgHoldRunnable);
                        sgStartHold(sgPressed);
                    }
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                removeCallbacks(sgHoldRunnable);
                final Gift pressed = sgPressed;
                sgPressed = null;
                if (pressed == null) {
                    return false;
                }
                pressed.bounce.setPressed(false);
                if (sgHeld != null) {
                    sgRelease();
                } else if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                    sgTap(pressed);
                }
                return true;
            }
        }
        return sgPressed != null;
    }

    // endregion

    private float sgTargetX, sgTargetY;

    // region test gifts (betas): the developer sees how gifts look on the own profile without having any

    private static final int[][] SG_TEST_COLORS = {
        {0xFF4F8FE8, 0xFF2A5BB0}, {0xFFE86F9A, 0xFFB03A66}, {0xFFF2B84B, 0xFFB07A1C}, {0xFF57C98A, 0xFF2A8A57},
        {0xFFA77BEA, 0xFF6A45B5}, {0xFFEF7B52, 0xFFB3482A}, {0xFF52C6D6, 0xFF23899A}, {0xFFB0B8C4, 0xFF6D7684},
        {0xFFE15A5A, 0xFF9E2B2B},
    };

    private boolean sgTestGiftsWanted() {
        return com.solegram.messenger.SolegramConfig.devTestGifts
            && org.telegram.messenger.BuildConfig.SOLEGRAM_VERSION_NAME.contains("beta")
            && dialogId == org.telegram.messenger.UserConfig.getInstance(currentAccount).getClientUserId();
    }

    private void sgAddTestGifts() {
        if (!sgTestGiftsWanted()) {
            return;
        }
        final int limit = sgOrbit ? SG_MAX : Math.max(1, maxCount);
        final StarsController stars = StarsController.getInstance(currentAccount);
        if (stars.gifts.isEmpty()) {
            stars.loadStarGifts(); // starGiftsLoaded brings them
            return;
        }
        // how many (the setting) and which: the picked ones first, in the order picked, then the catalog's next
        final int count = Math.max(1, Math.min(com.solegram.messenger.SolegramConfig.devTestGiftCount, limit));
        final ArrayList<TL_stars.StarGift> order = new ArrayList<>();
        for (long id : com.solegram.messenger.SolegramConfig.devTestGiftIdList()) {
            for (TL_stars.StarGift g : stars.gifts) {
                if (g != null && g.id == id && !order.contains(g)) {
                    order.add(g);
                }
            }
        }
        if (order.isEmpty()) {
            order.addAll(stars.gifts);
        }
        int added = 0, n = 0;
        for (int i = 0; i < order.size() && added < count && gifts.size() < limit; i++) {
            final TL_stars.StarGift g = order.get(i);
            final TLRPC.Document sticker = g != null ? g.getDocument() : null;
            if (sticker == null) {
                continue;
            }
            final long fakeId = -1000L - Math.abs(g.id % 100000);
            if (giftIds.contains(fakeId)) {
                continue;
            }
            final int[] colors = SG_TEST_COLORS[n++ % SG_TEST_COLORS.length];
            gifts.add(new Gift(fakeId, sticker, colors[0], colors[1]));
            giftIds.add(fakeId);
            added++;
        }
    }

    // endregion

    /**
     * Where the bubble's place on its orbit is now (px from the avatar's centre): an orbit wider than high, as if seen
     * a little from above, whose size, tilt and height drift slowly and differently for each bubble.
     */
    private void sgOrbitTarget(SgBubble b) {
        final float slow = sgTime * 0.11f + b.phase;
        final float radius = sgAvatarR + dp(30) + dp(30) * b.ring + dp(12) * (float) Math.sin(slow);
        final float tilt = 0.28f * (float) Math.sin(sgTime * 0.06f + b.phase * 0.5f);
        final float lift = dp(10) * (float) Math.sin(sgTime * 0.08f + b.phase * 1.3f);
        final float ox = (float) Math.cos(b.orbit) * radius * 1.45f;
        final float oy = (float) Math.sin(b.orbit) * radius * 0.62f;
        final float c = (float) Math.cos(tilt), s = (float) Math.sin(tilt);
        sgTargetX = ox * c - oy * s;
        sgTargetY = ox * s + oy * c - dp(6) + lift;
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
        // the shake egg: away (up and off) while the header lies fallen, down again when it comes back
        final boolean away = sgAwayWhile != null && sgAwayWhile.getAsBoolean();
        sgAway = away ? Math.min(1f, sgAway + dt / 1.1f) : Math.max(0f, sgAway - dt / 0.75f);
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
                b.orbit = (float) (Math.PI * 2 * (i + sgRandom.nextFloat() * 0.5f) / Math.max(1, n));
                // one way round for all, each at its own pace
                b.orbitSpeed = 0.3f + 0.16f * sgRandom.nextFloat();
                sgOrbitTarget(b);
                b.x = sgTargetX;
                b.y = sgTargetY;
            }
            if (!b.snowPlaced) {
                b.snowPlaced = true;
                for (int k = 0; k < SG_FLAKES; k++) {
                    // spread through the whole bubble (they float, they do not lie at the bottom)
                    final double a = sgRandom.nextFloat() * Math.PI * 2;
                    final float rr = (float) Math.sqrt(sgRandom.nextFloat()) * 0.7f;
                    b.fx[k] = b.hx[k] = (float) Math.cos(a) * rr;
                    b.fy[k] = b.hy[k] = (float) Math.sin(a) * rr;
                    b.fa[k] = 0;
                }
            }
        }
        sgContacts(n);
        for (int i = 0; i < n; i++) {
            final SgBubble b = gifts.get(i).sg;
            final float r = dp(21) * b.size * b.inflate;
            float fx, fy;
            if (b == sgHeld) {
                // held: it follows the finger closely
                fx = (sgFingerX - sgAcx - b.x) * 70f - b.vx * 15f;
                fy = (sgFingerY - sgAcy - b.y) * 70f - b.vy * 15f;
            } else {
                // its place on the orbit moves on; the bubble follows it softly (and finds its way back after a throw)
                b.orbit += b.orbitSpeed * dt;
                sgOrbitTarget(b);
                fx = (sgTargetX - b.x) * 9f - b.vx * 5f;
                fy = (sgTargetY - b.y) * 9f - b.vy * 5f;
            }
            // the header's edges push back softly
            final float left = -sgAcx + r, right = w - sgAcx - r, top = sgTop - sgAcy + r, bottom = sgBottom - sgAcy - r;
            if (b != sgHeld && b.x < left) fx += (left - b.x) * 40f;
            if (b != sgHeld && b.x > right) fx += (right - b.x) * 40f;
            if (b != sgHeld && b.y < top) fy += (top - b.y) * 40f;
            if (b != sgHeld && b.y > bottom) fy += (bottom - b.y) * 40f;
            fx += b.cfx;
            fy += b.cfy;
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
            b.flash *= (float) Math.exp(-7.5f * dt); // a short flash: it reads as the beat itself
            // the jelly: squashed along where it is pushed, springing back with a soft wobble
            final float ax = (b.vx - b.lastVx) / Math.max(dt, 1e-4f), ay = (b.vy - b.lastVy) / Math.max(dt, 1e-4f);
            final float follow = 1f - (float) Math.exp(-8f * dt);
            b.axs += (ax - b.axs) * follow;
            b.ays += (ay - b.ays) * follow;
            final float accel = (float) Math.hypot(b.axs, b.ays);
            float tex = 0, tey = 0;
            if (accel > 1f) {
                final float mag = Math.min(0.09f, accel / dp(3200));
                final float c = b.axs / accel, sn = b.ays / accel;
                tex = -mag * (c * c - sn * sn);
                tey = -mag * (2 * c * sn);
            }
            b.exv += (-110f * (b.ex - tex) - 7f * b.exv) * dt;
            b.eyv += (-110f * (b.ey - tey) - 7f * b.eyv) * dt;
            b.ex = Math.max(-0.2f, Math.min(0.2f, b.ex + b.exv * dt));
            b.ey = Math.max(-0.2f, Math.min(0.2f, b.ey + b.eyv * dt));
            if (b.inflateTarget > 1f && now > b.inflateUntil) {
                b.inflateTarget = 1f;
            }
            b.inflateV += (-90f * (b.inflate - b.inflateTarget) - 11f * b.inflateV) * dt;
            b.inflate = Math.max(0.5f, b.inflate + b.inflateV * dt);
            sgStepSnow(b, dt, r);
        }
    }

    /**
     * The bubbles against each other: a soft spring with damping along the line between them (no kicks), and where they
     * press, each is flattened against the plane where the two circles meet (the outline does it, see sgShape).
     */
    private void sgContacts(int n) {
        for (int i = 0; i < n; i++) {
            final SgBubble b = gifts.get(i).sg;
            b.cfx = b.cfy = 0;
            b.contacts = 0;
        }
        for (int i = 0; i < n; i++) {
            final SgBubble a = gifts.get(i).sg;
            final float ra = dp(21) * a.size * a.inflate * (1f + 0.12f * a.pulse);
            for (int j = i + 1; j < n; j++) {
                final SgBubble b = gifts.get(j).sg;
                final float rb = dp(21) * b.size * b.inflate * (1f + 0.12f * b.pulse);
                final float dx = b.x - a.x, dy = b.y - a.y;
                final float dist = Math.max(0.5f, (float) Math.hypot(dx, dy));
                if (dist > ra + rb + dp(8)) {
                    continue;
                }
                final float nx = dx / dist, ny = dy / dist;
                final float overlap = ra + rb - dist;
                if (overlap > 0) {
                    final float ma = a.size * a.size * a.inflate, mb = b.size * b.size * b.inflate;
                    final float vn = (b.vx - a.vx) * nx + (b.vy - a.vy) * ny;
                    final float push = overlap * 130f - vn * 9f;
                    if (push > 0) {
                        final float ka = 2f * mb / (ma + mb), kb = 2f * ma / (ma + mb);
                        a.cfx -= nx * push * ka;
                        a.cfy -= ny * push * ka;
                        b.cfx += nx * push * kb;
                        b.cfy += ny * push * kb;
                    }
                }
                // the plane where the two circles meet (the radical line), in each one's radii
                final float da = (dist * dist + ra * ra - rb * rb) / (2f * dist);
                sgAddContact(a, nx, ny, da / ra);
                sgAddContact(b, -nx, -ny, (dist - da) / rb);
            }
        }
        for (int i = 0; i < n; i++) {
            final SgBubble b = gifts.get(i).sg;
            float lost = 0;
            for (int k = 0; k < b.contacts; k++) {
                lost += Math.max(0, 1f - b.clim[k]);
            }
            // what is pressed in bulges out elsewhere a little (it keeps about its size)
            b.volume += (1f + 0.22f * lost - b.volume) * 0.3f;
        }
    }

    private static void sgAddContact(SgBubble b, float nx, float ny, float lim) {
        lim = Math.max(0.55f, Math.min(1.3f, lim));
        int slot = b.contacts;
        if (slot >= 4) {
            // keep the four that press the most
            int worst = 0;
            for (int k = 1; k < 4; k++) {
                if (b.clim[k] > b.clim[worst]) worst = k;
            }
            if (b.clim[worst] <= lim) {
                return;
            }
            slot = worst;
        } else {
            b.contacts++;
        }
        b.cnx[slot] = nx;
        b.cny[slot] = ny;
        b.clim[slot] = lim;
    }

    // the slow flow of the outline while it just floats: clearly not a circle, never a ripple
    private static final float SG_WOBBLE_2 = 0.075f, SG_WOBBLE_3 = 0.05f, SG_SMOOTH = 0.12f;

    private static float sgSmoothMin(float a, float b, float k) {
        final float h = Math.max(0f, Math.min(1f, 0.5f + 0.5f * (b - a) / k));
        return b + (a - b) * h - k * h * (1f - h);
    }

    /**
     * The outline's radius (in the bubble's radii) towards the direction (dx, dy) (a unit vector): a slow flow of low
     * harmonics, the elastic ellipse, flattened where it presses against others. The lens shader has the same function.
     */
    private float sgShape(SgBubble b, float dx, float dy, float c1, float s1, float c2, float s2) {
        final float cos2 = dx * dx - dy * dy, sin2 = 2f * dx * dy;
        final float cos3 = 4f * dx * dx * dx - 3f * dx, sin3 = 3f * dy - 4f * dy * dy * dy;
        float f = 1f + b.w2 * (sin2 * c1 + cos2 * s1) + b.w3 * (sin3 * c2 - cos3 * s2) + b.ex * cos2 + b.ey * sin2;
        for (int k = 0; k < b.contacts; k++) {
            final float c = dx * b.cnx[k] + dy * b.cny[k];
            if (c > 0.05f) {
                f = sgSmoothMin(f, b.clim[k] / c, SG_SMOOTH);
            }
        }
        return f;
    }

    /**
     * The light specks inside a bubble: each drifts slowly its own way (a gentle velocity that turns with time); when the
     * bubble or the phone moves they lag behind it and hit the wall, then the thick water calms them down to the slow
     * drift again.
     */
    private void sgStepSnow(SgBubble b, float dt, float r) {
        // in bubble radii per second squared. The bubble always moves (its orbit, its drift): that steady acceleration
        // would press the specks to one side for good, so only its sudden changes count (a drag, a throw, a bump),
        // and those only a little; the phone's own shake as well
        final float bubbleAx = (b.vx - b.lastVx) / Math.max(dt, 1e-4f) / r, bubbleAy = (b.vy - b.lastVy) / Math.max(dt, 1e-4f) / r;
        final float follow = 1f - (float) Math.exp(-dt / 0.5f);
        b.sax += (bubbleAx - b.sax) * follow;
        b.say += (bubbleAy - b.say) * follow;
        float ax = -(bubbleAx - b.sax) * 0.18f - sgAx * 0.3f;
        float ay = -(bubbleAy - b.say) * 0.18f - sgAy * 0.3f;
        final float max = 40f;
        ax = Math.max(-max, Math.min(max, ax));
        ay = Math.max(-max, Math.min(max, ay));
        // a push dies out in about a second, back to the drift
        final float calm = (float) Math.exp(-1.7f * dt);
        final float room = SG_FLAKE_ROOM;
        for (int k = 0; k < SG_FLAKES; k++) {
            final float ph = sgTime * 0.3f + k * 1.7f + b.phase * 3f;
            final float wx = 0.08f * (float) Math.cos(ph * 0.9f + k), wy = 0.08f * (float) Math.sin(ph * 1.1f + k * 0.6f);
            // a gentle pull back to its own place, so they stay spread through the bubble
            b.fvx[k] += (ax + (b.hx[k] - b.fx[k]) * 0.8f) * dt;
            b.fvy[k] += (ay + (b.hy[k] - b.fy[k]) * 0.8f) * dt;
            b.fvx[k] = wx + (b.fvx[k] - wx) * calm;
            b.fvy[k] = wy + (b.fvy[k] - wy) * calm;
            b.fx[k] += b.fvx[k] * dt;
            b.fy[k] += b.fvy[k] * dt;
            // the bubble's wall: a soft bounce
            final float d = (float) Math.hypot(b.fx[k], b.fy[k]);
            if (d > room) {
                final float nx = b.fx[k] / d, ny = b.fy[k] / d;
                b.fx[k] = nx * room;
                b.fy[k] = ny * room;
                final float vn = b.fvx[k] * nx + b.fvy[k] * ny;
                if (vn > 0) {
                    b.fvx[k] -= vn * nx * 1.5f;
                    b.fvy[k] -= vn * ny * 1.5f;
                }
            }
        }
    }

    /** {@code front}: the bubbles in front of the avatar (those coming nearer, and swollen ones), else those behind. */
    /** While this says so (the profile's shake egg), the bubbles fly up and away; then they come down again. */
    private java.util.function.BooleanSupplier sgAwayWhile;
    /** 0: in their places, 1: flown away above the screen. */
    private float sgAway;

    public void sgSetAwayWhile(java.util.function.BooleanSupplier awayWhile) {
        sgAwayWhile = awayWhile;
        invalidate();
    }

    /** Drawing the bubbles into the header's capture (for the glass buttons over them): no glass of their own then. */
    private boolean sgCapturing;

    /** Solegram: the bubbles as pictures into the capture that the glass buttons refract (no lenses, no state changed). */
    public void sgDrawForCapture(Canvas canvas) {
        if (!sgOrbit || gifts.isEmpty()) {
            return;
        }
        sgCapturing = true;
        try {
            sgDraw(canvas, false, this);
            sgDraw(canvas, true, sgFront);
        } finally {
            sgCapturing = false;
        }
    }

    private void sgDraw(Canvas canvas, boolean front, View owner) {
        if (front && !sgCapturing) {
            sgGlassFrontUsed = 0;
        }
        if (gifts.isEmpty() || sgAlpha <= 0.01f || expandProgress >= 1f || collapseProgress <= 0f) {
            if (!sgCapturing) sgHideUnusedGlass(front);
            return;
        }
        final float viewAlpha = front ? getAlpha() : 1f;
        if (front && viewAlpha <= 0.01f) {
            if (!sgCapturing) sgHideUnusedGlass(true);
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
            // the lower half of the orbit is the near one: those pass in front of the avatar
            final float depth = (float) Math.sin(b.orbit);
            final boolean isFront = depth > 0 || b.inflate > 1.15f || b == sgHeld;
            if (isFront != front) {
                continue;
            }
            final float enter = gift.animatedFloat.set(1.0f);
            final float collapse = Utilities.clamp01(isOpening || enter >= 1f ? sgCollapse : Math.min(enter, sgCollapse));
            final float ease = 1f - (1f - collapse) * (1f - collapse);
            float cx = AndroidUtilities.lerp(sgRealCx, sgAcx + b.x, ease);
            float cy = AndroidUtilities.lerp(sgRealCy, sgAcy + b.y, ease);
            float awayFade = 1f;
            if (sgAway > 0f) {
                // flying off: each a little later and its own way, faster and faster upwards, past the top of the screen
                final float lag = 0.12f * (float) ((Math.sin(b.phase * 7.3f) + 1f) / 2f);
                final float t = Utilities.clamp01((sgAway - lag) / (1f - lag));
                final float e = t * t * (1.6f - 0.6f * t);
                cy -= e * (cy + dp(140));
                cx += e * dp(46) * (float) Math.sin(b.phase * 3.1f) + e * e * dp(18) * (float) Math.cos(b.phase * 5.3f);
                awayFade = 1f - Utilities.clamp01((t - 0.75f) / 0.25f);
            }
            // the near ones bigger, the far ones smaller and dimmer: depth
            final float scale = (0.5f + 0.5f * ease) * (1f + 0.2f * depth) * (1f + 0.12f * b.pulse) * b.inflate * (1f - 0.12f * sgAway);
            final float r = dp(21) * b.size * scale * gift.bounce.getScale(0.1f);
            b.drawX = cx;
            b.drawY = cy;
            b.drawR = r;
            final float alpha = enter * sgAlpha * (0.62f + 0.38f * (depth + 1f) / 2f) * viewAlpha * awayFade;
            if (alpha <= 0.005f) {
                b.drawR = 0; // gone: nothing to tap
                continue;
            }
            sgDrawBubble(canvas, gift, cx, cy, r, alpha, front, owner);
        }
        canvas.restore();
        if (!sgCapturing) sgHideUnusedGlass(front);
    }

    private float sgC1, sgS1, sgC2, sgS2;

    /** The bubble's outline now, radius {@code r} (its volume already in). */
    private void sgBuildShape(SgBubble b, float cx, float cy, float r) {
        final float t1 = sgTime * 1.05f + b.phase, t2 = sgTime * 0.75f + b.phase * 1.7f;
        final float breathe = 0.65f + 0.35f * (float) Math.sin(sgTime * 0.37f + b.phase * 2.3f);
        b.w2 = SG_WOBBLE_2 * (0.7f + 0.6f * breathe);
        b.w3 = SG_WOBBLE_3 * (1.3f - 0.6f * breathe);
        sgC1 = (float) Math.cos(t1);
        sgS1 = (float) Math.sin(t1);
        sgC2 = (float) Math.cos(t2);
        sgS2 = (float) Math.sin(t2);
        sgPath.rewind();
        final int points = 48;
        float firstX = 0, firstY = 0, prevX = 0, prevY = 0;
        for (int k = 0; k <= points; k++) {
            final double t = Math.PI * 2 * (k % points) / points;
            final float dx = (float) Math.cos(t), dy = (float) Math.sin(t);
            final float rr = r * sgShape(b, dx, dy, sgC1, sgS1, sgC2, sgS2);
            final float px = cx + dx * rr, py = cy + dy * rr;
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

    /**
     * The sticker's colour as one: the average of its visible pixels, coloured ones weigh more than greys, dark
     * outlines and white glints less (so a pink gift gives pink, not grey). 0: nothing visible yet.
     */
    static int sgAverage(android.graphics.Bitmap b) {
        final int w = b.getWidth(), h = b.getHeight();
        final int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        final float[] hsv = new float[3];
        double r = 0, g = 0, bl = 0, total = 0;
        for (int p : px) {
            final int a = p >>> 24;
            if (a < 40) continue;
            android.graphics.Color.colorToHSV(p | 0xFF000000, hsv);
            final float v = hsv[2], s = hsv[1];
            final double weight = a / 255.0 * (0.2 + s * s * 2.0) * (v < 0.18f ? 0.15 : v > 0.97f && s < 0.08f ? 0.3 : 1.0);
            r += ((p >> 16) & 0xFF) * weight;
            g += ((p >> 8) & 0xFF) * weight;
            bl += (p & 0xFF) * weight;
            total += weight;
        }
        if (total < 4) {
            return 0;
        }
        final int avg = 0xFF000000 | ((int) (r / total) << 16) | ((int) (g / total) << 8) | (int) (bl / total);
        android.graphics.Color.colorToHSV(avg, hsv);
        if (hsv[1] > 0.1f) {
            hsv[1] = Math.min(1f, hsv[1] * 1.25f + 0.08f);
        }
        hsv[2] = Math.max(hsv[2], 0.82f);
        return android.graphics.Color.HSVToColor(hsv);
    }

    /** The colour with its saturation pushed up (greys stay grey), a touch brighter when it is dark. */
    private static int sgSaturated(int color) {
        final float[] hsv = new float[3];
        android.graphics.Color.colorToHSV(color | 0xFF000000, hsv);
        if (hsv[1] > 0.08f) {
            hsv[1] = Math.min(1f, hsv[1] * 1.55f + 0.18f);
        }
        hsv[2] = Math.max(hsv[2], 0.62f);
        return android.graphics.Color.HSVToColor(hsv);
    }

    private final Paint sgGlowStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sgRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF sgRingRect = new RectF();
    private final Path sgRingPath = new Path();
    private LinearGradient sgRingSheen;
    private float sgRingSheenW, sgRingSheenH;

    /**
     * A glass ring along the avatar's own shape (its corners as the avatar has them): a light rim catching light at
     * the top-left and the bottom-right, a thin dark line inside for depth, and the bubbles' light on it, each in its
     * colour, brightest where a bubble is nearest. Not with a stories ring (that one takes the edge).
     */
    private void sgDrawAvatarRing(Canvas canvas, float viewAlpha) {
        if (!sgOrbit || avatarImage == null || avatarImage.hasStories || avatarImage.getVisibility() != VISIBLE) {
            return;
        }
        // its own visibility (not the bubbles'): the header open, the photo not pulled down
        final float closed = Utilities.clamp01((expandY - (AndroidUtilities.statusBarHeight + ActionBar.getCurrentActionBarHeight())) / dp(50));
        final float alpha = viewAlpha * (1f - expandProgress) * (1f - actionBarProgress) * closed * Utilities.clamp01(collapseProgress * 1.5f);
        if (alpha <= 0.01f) {
            return;
        }
        final float scale = avatarContainer.getScaleX();
        final float x = avatarContainer.getX() + avatarImage.getX() * scale, y = avatarContainer.getY() + avatarImage.getY() * scale;
        final float w = avatarImage.getWidth() * scale, h = avatarImage.getHeight() * scale;
        if (w <= 0 || h <= 0) {
            return;
        }
        float rr = avatarImage.getImageReceiver().getRoundRadius()[0] * scale;
        rr = Math.max(0, Math.min(rr, Math.min(w, h) / 2f));
        // a glass tube round the avatar, apart from it: a gap, then the tube
        final float gap = dp(3.5f) * Math.min(1f, scale), tube = dp(3.6f) * Math.min(1f, scale);
        final float mid = gap + tube / 2f;
        sgRingRect.set(x - mid, y - mid, x + w + mid, y + h + mid);
        final float r = rr <= 0 ? mid * 0.6f : rr + mid;
        sgRingPath.rewind();
        sgRingPath.addRoundRect(sgRingRect, r, r, Path.Direction.CW);
        canvas.save();
        final float acx0 = x + w / 2f, acy0 = y + h / 2f;
        // it moves with the avatar (the beat's swell and tilt)
        canvas.rotate(avatarImage.getRotation(), acx0, acy0);
        canvas.scale(avatarImage.getScaleX(), avatarImage.getScaleY(), acx0, acy0);
        // the glass body: barely there
        sgRingPaint.setShader(null);
        sgRingPaint.setStyle(Paint.Style.STROKE);
        sgRingPaint.setStrokeWidth(tube);
        sgRingPaint.setColor(Theme.multAlpha(0xFFFFFFFF, 0.07f * alpha));
        canvas.drawPath(sgRingPath, sgRingPaint);
        // its two edges catch the light: the outer one brighter at the top-left, the inner one fainter
        if (sgRingSheen == null || sgRingSheenW != w || sgRingSheenH != h) {
            sgRingSheenW = w;
            sgRingSheenH = h;
            sgRingSheen = new LinearGradient(0, 0, w, h, new int[]{0xD9FFFFFF, 0x40FFFFFF, 0x8CFFFFFF},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP);
        }
        sgMatrix.reset();
        sgMatrix.postTranslate(x, y);
        sgRingSheen.setLocalMatrix(sgMatrix);
        final float edge = Math.max(1f, dp(0.9f));
        sgRingPaint.setShader(sgRingSheen);
        sgRingPaint.setStrokeWidth(edge);
        sgRingPaint.setAlpha((int) (0xFF * alpha));
        sgRingEdge(canvas, mid + tube / 2f - edge / 2f, x, y, w, h, rr);
        sgRingPaint.setAlpha((int) (0xFF * 0.6f * alpha));
        sgRingEdge(canvas, mid - tube / 2f + edge / 2f, x, y, w, h, rr);
        // the bubbles' glow mirrored in the glass: the body tinted near each, both edges lit in its colour
        final float acx = x + w / 2f, acy = y + h / 2f, ar = Math.max(w, h) / 2f + mid;
        for (int i = 0; i < gifts.size(); i++) {
            final Gift gift = gifts.get(i);
            final SgBubble b = gift.sg;
            if (!b.placed || b.drawR <= 0 || sgAlpha <= 0.01f) {
                continue;
            }
            final float d = (float) Math.hypot(b.drawX - acx, b.drawY - acy) - ar - b.drawR;
            final float near = Utilities.clamp01(1f - d / dp(64));
            if (near <= 0.02f) {
                continue;
            }
            final int rich = sgSaturated(gift.sgTone());
            if (b.reflect == null || b.reflectColor != rich) {
                b.reflectColor = rich;
                b.reflect = new RadialGradient(0, 0, 1f, new int[]{rich, Theme.multAlpha(rich, 0.5f), Theme.multAlpha(rich, 0f)},
                    new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP);
            }
            final float reach = b.drawR * 2.6f + dp(28);
            sgMatrix.reset();
            sgMatrix.postScale(reach, reach);
            sgMatrix.postTranslate(b.drawX, b.drawY);
            b.reflect.setLocalMatrix(sgMatrix);
            sgRingPaint.setShader(b.reflect);
            final float strength = near * alpha * sgAlpha * Math.min(1f, 0.8f + 0.5f * b.flash);
            sgRingPaint.setStrokeWidth(tube);
            sgRingPaint.setAlpha((int) (0xFF * 0.22f * strength));
            canvas.drawPath(sgRingPath, sgRingPaint);
            sgRingPaint.setStrokeWidth(edge);
            sgRingPaint.setAlpha((int) (0xFF * 0.9f * strength));
            sgRingEdge(canvas, mid + tube / 2f - edge / 2f, x, y, w, h, rr);
            sgRingPaint.setAlpha((int) (0xFF * 0.6f * strength));
            sgRingEdge(canvas, mid - tube / 2f + edge / 2f, x, y, w, h, rr);
        }
        sgRingPaint.setShader(null);
        canvas.restore();
    }

    /** One edge of the ring: the avatar's rounded rect pushed out by {@code out}. */
    private void sgRingEdge(Canvas canvas, float out, float x, float y, float w, float h, float rr) {
        sgRingRect.set(x - out, y - out, x + w + out, y + h + out);
        final float r = rr <= 0 ? out * 0.6f : rr + out;
        canvas.drawRoundRect(sgRingRect, r, r, sgRingPaint);
    }

    /**
     * A bubble as in the iOS look: a light, see-through jelly of its gift's colour, a bright rim of that colour along
     * its outline, a soft glow that hugs the outline (not a round blob) and a thin light edge; the gift inside.
     */
    private void sgDrawBubble(Canvas canvas, Gift gift, float cx, float cy, float r, float alpha, boolean front, View owner) {
        final SgBubble b = gift.sg;
        final int color = gift.sgTone();
        final float flash = Math.max(0f, Math.min(1f, b.flash));
        final int rich = sgSaturated(color);
        final int light = androidx.core.graphics.ColorUtils.blendARGB(rich, 0xFFFFFFFF, 0.45f);
        final float k = r / dp(21);
        // the glow: soft and wide, as measured in the example: right outside the edge about a third of the colour,
        // fading smoothly to nothing about 1.8 radii further; one gradient, no layers
        if (b.glow == null || b.glowColor != color) {
            b.glowColor = color;
            b.glow = new Paint(Paint.ANTI_ALIAS_FLAG);
            // distance in radii → strength, taken from the example's pixels (1.07R .30, 1.45R .17, 1.9R .10)
            final float[] dist = {0f, 1f, 1.1f, 1.25f, 1.45f, 1.7f, 1.9f, 2.2f, 2.5f, 2.8f};
            final float[] level = {0.36f, 0.36f, 0.3f, 0.23f, 0.17f, 0.125f, 0.1f, 0.06f, 0.028f, 0f};
            final float[] stops = new float[dist.length];
            final int[] colors = new int[dist.length];
            for (int i = 0; i < dist.length; i++) {
                stops[i] = dist[i] / 2.8f;
                colors[i] = Theme.multAlpha(rich, level[i]);
            }
            b.glow.setShader(new RadialGradient(0, 0, dp(21) * 2.8f, colors, stops, Shader.TileMode.CLAMP));
        }
        b.glow.setAlpha((int) (0xFF * Math.min(1f, alpha * (1f + 0.5f * flash))));
        canvas.save();
        canvas.translate(cx, cy);
        canvas.scale(k * b.volume, k * b.volume);
        canvas.drawCircle(0, 0, dp(21) * 2.8f, b.glow);
        canvas.restore();

        final float shapeR = r * b.volume;
        sgBuildShape(b, cx, cy, shapeR);

        canvas.save();
        canvas.clipPath(sgPath);
        // the lens: what is under it, refracted along this very outline (the glass of the header)
        final boolean glass = !sgCapturing && sgGlassFactory != null && canvas.isHardwareAccelerated();
        if (glass) {
            sgDrawLens(canvas, front, owner, b, cx, cy, shapeR, alpha);
        }
        // the jelly: light and clear in the middle, the colour gathering towards the edge, the edge itself bright
        if (b.body == null || b.bodyColor != color) {
            b.bodyColor = color;
            final int edge = androidx.core.graphics.ColorUtils.blendARGB(rich, 0xFFFFFFFF, 0.22f);
            final int soft = androidx.core.graphics.ColorUtils.blendARGB(rich, 0xFFFFFFFF, 0.18f); // pastel inside, as in the example
            b.body = new RadialGradient(0, 0, dp(21) * 1.06f,
                new int[]{Theme.multAlpha(soft, 0.5f), Theme.multAlpha(soft, 0.55f), Theme.multAlpha(rich, 0.66f), Theme.multAlpha(edge, 0.86f), Theme.multAlpha(edge, 0.95f)},
                new float[]{0f, 0.5f, 0.75f, 0.9f, 1f}, Shader.TileMode.CLAMP);
        }
        sgMatrix.reset();
        sgMatrix.postScale(shapeR / dp(21), shapeR / dp(21));
        sgMatrix.postTranslate(cx, cy);
        b.body.setLocalMatrix(sgMatrix);
        sgFill.setShader(b.body);
        sgFill.setAlpha((int) (0xFF * alpha * Math.min(1f, 0.95f + 0.2f * flash)));
        canvas.drawPaint(sgFill);
        // the gift's emoji floating small in the jelly (tiny like specks; a little bigger while the bubble is held),
        // each slowly swaying; light specks until the emoji is known
        {
            final android.graphics.drawable.Drawable emoji = gift.sgEmoji();
            final float held = Math.max(0f, Math.min(1f, (b.inflate - 1f) / 0.9f));
            final int speck = androidx.core.graphics.ColorUtils.blendARGB(light, 0xFFFFFFFF, 0.65f);
            sgFill.setShader(null);
            for (int i = 0; i < SG_FLAKES; i++) {
                final float fx = cx + b.fx[i] * shapeR, fy = cy + b.fy[i] * shapeR;
                final float tw = 0.7f + 0.3f * (float) Math.sin(sgTime * 1.6f + i * 2.1f + b.phase);
                if (emoji != null) {
                    // r already grows with the swelling; on top of that the emoji get a bit bigger themselves
                    final float half = dp(1.6f + 0.7f * ((i * 37) % 10) / 10f) * k * (1f + 0.45f * held);
                    final float tilt = 14f * (float) Math.sin(sgTime * 0.7f + i * 1.3f + b.phase * 2f);
                    canvas.save();
                    canvas.rotate(tilt, fx, fy);
                    emoji.setBounds((int) (fx - half), (int) (fy - half), (int) (fx + half), (int) (fy + half));
                    emoji.setAlpha((int) (0xFF * Math.min(1f, (0.8f + 0.2f * tw) * alpha)));
                    emoji.draw(canvas);
                    canvas.restore();
                } else {
                    final float size = dp(0.45f + 0.55f * ((i * 37) % 10) / 10f) * k;
                    sgFill.setColor(Theme.multAlpha(speck, 0.9f * tw * alpha));
                    canvas.drawCircle(fx, fy, size, sgFill);
                }
            }
            if (emoji != null) {
                emoji.setAlpha(0xFF);
            }
        }
        // the liquid near the edge: streaks of light and of shifted hue that flow round the rim (two layers turning
        // opposite ways, so the pattern keeps changing), fading out towards the middle, like a soap film / jelly
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            if (b.film1 == null || b.filmColor != color) {
                b.filmColor = color;
                final float[] hsv = new float[3];
                android.graphics.Color.colorToHSV(rich, hsv);
                final float hue = hsv[0];
                hsv[0] = (hue + 40f) % 360f;
                final int warm = android.graphics.Color.HSVToColor(hsv);
                hsv[0] = (hue + 320f) % 360f;
                final int cool = android.graphics.Color.HSVToColor(hsv);
                final int lit = androidx.core.graphics.ColorUtils.blendARGB(rich, 0xFFFFFFFF, 0.75f);
                final Shader mask = new RadialGradient(0, 0, 1f, new int[]{0x00FFFFFF, 0x00FFFFFF, 0x80FFFFFF, 0xFFFFFFFF, 0x99FFFFFF},
                    new float[]{0f, 0.55f, 0.78f, 0.93f, 1f}, Shader.TileMode.CLAMP);
                final Shader flow1 = new android.graphics.SweepGradient(0, 0,
                    new int[]{Theme.multAlpha(lit, 0.55f), 0, Theme.multAlpha(warm, 0.5f), 0, Theme.multAlpha(lit, 0.35f), Theme.multAlpha(cool, 0.45f), 0, Theme.multAlpha(lit, 0.55f)},
                    new float[]{0f, 0.12f, 0.27f, 0.4f, 0.55f, 0.68f, 0.84f, 1f});
                final Shader mask2 = new RadialGradient(0, 0, 1f, new int[]{0x00FFFFFF, 0x00FFFFFF, 0x66FFFFFF, 0xDDFFFFFF, 0x80FFFFFF},
                    new float[]{0f, 0.62f, 0.83f, 0.95f, 1f}, Shader.TileMode.CLAMP);
                final Shader flow2 = new android.graphics.SweepGradient(0, 0,
                    new int[]{0, Theme.multAlpha(cool, 0.4f), 0, Theme.multAlpha(lit, 0.5f), 0, Theme.multAlpha(warm, 0.35f), 0},
                    new float[]{0f, 0.18f, 0.33f, 0.5f, 0.66f, 0.82f, 1f});
                b.film1 = new android.graphics.ComposeShader(mask, flow1, android.graphics.PorterDuff.Mode.SRC_IN);
                b.film2 = new android.graphics.ComposeShader(mask2, flow2, android.graphics.PorterDuff.Mode.SRC_IN);
            }
            sgFill.setColor(0xFFFFFFFF);
            sgMatrix.reset();
            sgMatrix.postScale(shapeR, shapeR);
            sgMatrix.postRotate((sgTime * 26f + b.phase * 57f) % 360f);
            sgMatrix.postTranslate(cx, cy);
            b.film1.setLocalMatrix(sgMatrix);
            sgFill.setShader(b.film1);
            sgFill.setAlpha((int) (0xFF * alpha * Math.min(1f, 0.85f + 0.3f * flash)));
            canvas.drawPaint(sgFill);
            sgMatrix.reset();
            sgMatrix.postScale(shapeR, shapeR);
            sgMatrix.postRotate((-sgTime * 17f + b.phase * 91f) % 360f);
            sgMatrix.postTranslate(cx, cy);
            b.film2.setLocalMatrix(sgMatrix);
            sgFill.setShader(b.film2);
            canvas.drawPaint(sgFill);
            sgFill.setShader(null);
        }
        // the gift itself in the middle
        if (gift.emojiDrawable != null) {
            final int sz = (int) (dp(25) * k);
            gift.emojiDrawable.setBounds((int) (cx - sz / 2f), (int) (cy - sz / 2f), (int) (cx + sz / 2f), (int) (cy + sz / 2f));
            gift.emojiDrawable.setAlpha((int) (0xFF * alpha));
            gift.emojiDrawable.draw(canvas);
        }
        canvas.restore();
        // only a whisper of an edge, so the outline stays smooth (the example has no line round it)
        sgRim.setStrokeWidth(Math.max(1f, dp(0.6f) * k));
        sgRim.setColor(Theme.multAlpha(light, 0.22f * alpha));
        canvas.drawPath(sgPath, sgRim);
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

        sgAddTestGifts();

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
        if (sgOrbit) {
            return sgTouch(event);
        }
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
        if (gift.slug == null) {
            return; // a test gift
        }
        Browser.openUrl(getContext(), "https://t.me/nft/" + gift.slug);
    }
}
