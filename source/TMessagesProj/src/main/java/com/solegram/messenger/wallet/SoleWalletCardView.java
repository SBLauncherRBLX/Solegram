/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.wallet;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Camera;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.SystemClock;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.OvershootInterpolator;

import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;

/**
 * The wallet's card in 3D. It tilts with the phone, follows the finger and turns over with a tap or a swipe. The
 * front: the Solegram sun and the wallet's name, the contactless mark, the chip, the number (masked) and the name.
 * The back: the stripe, a signature panel with the name, the whole number. Eight matte colours, as real cards,
 * with the Solegram sun pressed into them. The view is taller than the card, so a turned card is never cut.
 */
public class SoleWalletCardView extends View implements SensorEventListener {

    public static final int DESIGNS = 13;
    public static final int[] DESIGN_NAMES = {
        R.string.SG_WalletDesignBlack, R.string.SG_WalletDesignWhite, R.string.SG_WalletDesignNavy, R.string.SG_WalletDesignGraphite,
        R.string.SG_WalletDesignBurgundy, R.string.SG_WalletDesignEmerald, R.string.SG_WalletDesignSand, R.string.SG_WalletDesignSolegram,
        R.string.SG_WalletDesignHolo, R.string.SG_WalletDesignTitanium, R.string.SG_WalletDesignMidnight, R.string.SG_WalletDesignOcean,
        R.string.SG_WalletDesignMint,
    };
    private static final int HOLO = 8, TITANIUM = 9, MIDNIGHT = 10, OCEAN = 11, MINT = 12;
    /** The card's main colour: matte cards are just this colour; the rest draw their finish over it. */
    public static final int[] BASES = {0xFF1B1B1E, 0xFFF3F2EF, 0xFF172340, 0xFF3B3E44, 0xFF5A1A26, 0xFF0F4A3D, 0xFFD9CBB2, 0xFFEE8A2F,
        0xFFEFEEFB, 0xFFE2E4E9, 0xFF101114, 0xFF1E73E8, 0xFFA6EFCF};
    private static final boolean[] LIGHT = {false, true, false, false, false, false, true, false, true, true, false, false, true};
    /** Two tones of each design (the tour's and the lock screen's pictures). */
    public static final int[][] STYLES = new int[DESIGNS][];

    static {
        for (int i = 0; i < DESIGNS; i++) {
            STYLES[i] = new int[]{ColorUtils.blendARGB(BASES[i], Color.WHITE, 0.12f), ColorUtils.blendARGB(BASES[i], Color.BLACK, 0.12f)};
        }
    }

    private final boolean interactive;
    private final Camera camera = new Camera();
    private final Matrix matrix = new Matrix();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final RectF tmp = new RectF();
    private final Path clip = new Path();
    private final Path wave = new Path();
    private final Drawable mark;

    private int design;
    private String number = "", numberMasked = "", name = "";

    // a collectible gift on the card (as on Telegram's wallet cards), optionally colouring it with its backdrop
    private org.telegram.messenger.ImageReceiver giftImage;
    private int giftCenter, giftEdge;
    private boolean giftColor;

    /** Puts a collectible gift on the card (null takes it off); with color, the card takes the gift's backdrop colours. */
    public void setGift(org.telegram.tgnet.tl.TL_stars.StarGift gift, boolean color) {
        if (gift == null) {
            if (giftImage != null) {
                giftImage.onDetachedFromWindow();
                giftImage = null;
            }
            giftColor = false;
            invalidate();
            return;
        }
        if (giftImage == null) {
            giftImage = new org.telegram.messenger.ImageReceiver(this);
            if (isAttachedToWindow()) {
                giftImage.onAttachedToWindow();
            }
        }
        org.telegram.ui.Stars.StarsIntroActivity.setGiftImage(giftImage, gift, 96);
        giftCenter = giftEdge = 0;
        if (gift instanceof org.telegram.tgnet.tl.TL_stars.TL_starGiftUnique) {
            for (org.telegram.tgnet.tl.TL_stars.StarGiftAttribute a : ((org.telegram.tgnet.tl.TL_stars.TL_starGiftUnique) gift).attributes) {
                if (a instanceof org.telegram.tgnet.tl.TL_stars.starGiftAttributeBackdrop) {
                    giftCenter = ((org.telegram.tgnet.tl.TL_stars.starGiftAttributeBackdrop) a).center_color | 0xFF000000;
                    giftEdge = ((org.telegram.tgnet.tl.TL_stars.starGiftAttributeBackdrop) a).edge_color | 0xFF000000;
                }
            }
        }
        giftColor = color && giftCenter != 0;
        invalidate();
    }

    private float rotY, tiltX, tiltY, tiltTargetX, tiltTargetY;
    private float downX, downRot;
    private boolean dragging;
    private android.animation.ValueAnimator settle;
    private SensorManager sensors;
    private long last;
    private float fixedTilt;

    public SoleWalletCardView(Context context) {
        this(context, true);
    }

    public SoleWalletCardView(Context context, boolean interactive) {
        super(context);
        this.interactive = interactive;
        mark = ContextCompat.getDrawable(context, R.drawable.notification).mutate();
        if (!interactive) {
            fixedTilt = 8f;
        }
    }

    public void set(int design, String number, String masked, String name) {
        this.design = Math.max(0, Math.min(DESIGNS - 1, design));
        this.number = number == null ? "" : number;
        this.numberMasked = masked == null ? "" : masked;
        this.name = name == null ? "" : name.toUpperCase();
        invalidate();
    }

    public void setDesign(int design) {
        this.design = Math.max(0, Math.min(DESIGNS - 1, design));
        invalidate();
    }

    /** A quick full turn, to show a new design. */
    public void spin() {
        animateTo(Math.round(rotY / 360f) * 360f + 360f, 900);
    }

    public boolean isBack() {
        final float n = ((rotY % 360) + 360) % 360;
        return n > 90 && n < 270;
    }

    public void flip() {
        animateTo(Math.round(rotY / 180f) * 180f + 180f, 620);
    }

    private float cardWidth() {
        return getMeasuredWidth() - dp(interactive ? 40 : 8);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final int w = MeasureSpec.getSize(widthMeasureSpec);
        final float cw = w - dp(interactive ? 40 : 8);
        // room above and below for the perspective of a turned card
        final int h = (int) (cw / 1.586f + (interactive ? dp(76) : dp(16)));
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (giftImage != null) {
            giftImage.onAttachedToWindow();
        }
        if (!interactive) {
            return;
        }
        sensors = (SensorManager) getContext().getSystemService(Context.SENSOR_SERVICE);
        if (sensors != null) {
            final Sensor s = sensors.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
            if (s != null) {
                sensors.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME);
            } else {
                sensors = null;
            }
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (giftImage != null) {
            giftImage.onDetachedFromWindow();
        }
        if (sensors != null) {
            sensors.unregisterListener(this);
        }
    }

    private final float[] rot = new float[9], orient = new float[3];
    private float basePitch = Float.NaN, baseRoll = Float.NaN;

    @Override
    public void onSensorChanged(SensorEvent e) {
        SensorManager.getRotationMatrixFromVector(rot, e.values);
        SensorManager.getOrientation(rot, orient);
        final float pitch = (float) Math.toDegrees(orient[1]), roll = (float) Math.toDegrees(orient[2]);
        if (Float.isNaN(basePitch)) {
            basePitch = pitch;
            baseRoll = roll;
        }
        basePitch += (pitch - basePitch) * 0.02f;
        baseRoll += (roll - baseRoll) * 0.02f;
        tiltTargetX = clamp((pitch - basePitch) * 0.8f, -12f, 12f);
        tiltTargetY = clamp((roll - baseRoll) * 0.8f, -12f, 12f);
        invalidate();
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    private static float clamp(float v, float a, float b) {
        return Math.max(a, Math.min(b, v));
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!interactive) {
            return super.onTouchEvent(e);
        }
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (settle != null) {
                    settle.cancel();
                }
                downX = e.getX();
                downRot = rotY;
                dragging = true;
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_MOVE:
                rotY = downRot + (e.getX() - downX) / Math.max(1, getWidth()) * 200f;
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                if (Math.abs(e.getX() - downX) < dp(8) && e.getActionMasked() == MotionEvent.ACTION_UP) {
                    flip();
                } else {
                    animateTo(Math.round(rotY / 180f) * 180f, 620);
                }
                return true;
        }
        return super.onTouchEvent(e);
    }

    private void animateTo(float to, long duration) {
        if (settle != null) {
            settle.cancel();
        }
        final float from = rotY;
        settle = android.animation.ValueAnimator.ofFloat(0, 1);
        settle.setDuration(duration);
        settle.setInterpolator(new OvershootInterpolator(1.1f));
        settle.addUpdateListener(a -> {
            rotY = from + (to - from) * (float) a.getAnimatedValue();
            invalidate();
        });
        settle.start();
        try {
            performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
        } catch (Exception ignore) {
        }
    }

    /**
     * A shader is drawn at the paint's alpha too, and the paint keeps the last colour set on it (a faint stroke),
     * so before each shaded fill the paint is made opaque again; else the whole card came out see-through, dark.
     */
    private void opaque() {
        paint.setColor(0xFF000000);
    }

    private boolean lightDesign() {
        if (giftColor) {
            return androidx.core.graphics.ColorUtils.calculateLuminance(giftCenter) > 0.6;
        }
        return LIGHT[design];
    }

    private int ink() {
        return lightDesign() ? 0xFF1C1E24 : Color.WHITE;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final long now = SystemClock.elapsedRealtime();
        final float dt = last == 0 ? 16 : Math.min(48, now - last);
        last = now;
        tiltX += (tiltTargetX - tiltX) * Math.min(1f, dt / 120f);
        tiltY += (tiltTargetY - tiltY) * Math.min(1f, dt / 120f);
        final float sway = interactive && sensors == null ? (float) Math.sin(now / 1500.0) * 4f : 0f;

        final float w = cardWidth();
        final float h = w / 1.586f;
        final float cx = getWidth() / 2f, cy = getHeight() / 2f;
        final float angle = rotY + tiltY + sway - fixedTilt;
        final float norm = ((angle % 360) + 360) % 360;
        final boolean back = norm > 90 && norm < 270;

        canvas.save();
        camera.save();
        camera.setLocation(0, 0, -18);
        camera.rotateX(-tiltX + (interactive ? 0 : 4f));
        camera.rotateY(-angle);
        camera.getMatrix(matrix);
        camera.restore();
        matrix.preTranslate(-cx, -cy);
        matrix.postTranslate(cx, cy);
        canvas.concat(matrix);

        rect.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f);
        if (back) {
            canvas.scale(-1, 1, cx, cy);
        }
        final float r = h * 0.085f;

        clip.rewind();
        clip.addRoundRect(rect, r, r, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(clip);
        drawDesign(canvas, w, h, angle);
        if (back) {
            drawBack(canvas, w, h);
        } else {
            drawFront(canvas, w, h);
        }
        // the light moves over the card with its angle
        final float g = (((angle + tiltX * 2) % 360) + 360) % 360;
        final float shift = (float) Math.sin(Math.toRadians(g)) * w * 0.8f;
        opaque(); paint.setShader(new LinearGradient(rect.left + shift - w * 0.35f, rect.top, rect.left + shift + w * 0.35f, rect.bottom,
            new int[]{0x00FFFFFF, lightDesign() ? 0x2EFFFFFF : 0x0DFFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
        canvas.drawRect(rect, paint);
        paint.setShader(null);
        canvas.restore();

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1, dp(0.8f)));
        paint.setColor(lightDesign() ? 0x14000000 : 0x14FFFFFF);
        canvas.drawRoundRect(rect, r, r, paint);
        paint.setStyle(Paint.Style.FILL);
        canvas.restore();

        if (interactive && (dragging || settle != null && settle.isRunning() || Math.abs(tiltTargetX - tiltX) > 0.05f
            || Math.abs(tiltTargetY - tiltY) > 0.05f || sensors == null || design == HOLO)) {
            postInvalidateOnAnimation();
        } else {
            last = 0;
        }
    }

    // ------------------------------------------------------------------ designs

    private void drawDesign(Canvas canvas, float w, float h, float angle) {
        if (giftColor) {
            // the gift's backdrop: its centre colour round the gift, its edge colour at the card's corners
            opaque(); paint.setShader(new android.graphics.RadialGradient(rect.right - h * 0.3f, rect.top + h * 0.3f, w,
                giftCenter, giftEdge, Shader.TileMode.CLAMP));
            canvas.drawRect(rect, paint);
            paint.setShader(null);
            return;
        }
        final int base = BASES[design];
        switch (design) {
            case HOLO: {
                // a pale card whose colours turn with it
                opaque(); paint.setShader(new LinearGradient(rect.left, rect.top, rect.right, rect.bottom, 0xFFEFF4FF, 0xFFF7EEFF, Shader.TileMode.CLAMP));
                canvas.drawRect(rect, paint);
                final android.graphics.SweepGradient sweep = new android.graphics.SweepGradient(rect.centerX(), rect.centerY(),
                    new int[]{0x668EE8FF, 0x66D3A6FF, 0x66FFC3E6, 0x66FFF2A8, 0x66A8FFD8, 0x668EE8FF}, null);
                final Matrix m = new Matrix();
                m.setRotate(angle * 2.2f + tiltX * 6f, rect.centerX(), rect.centerY());
                sweep.setLocalMatrix(m);
                opaque(); paint.setShader(sweep);
                canvas.drawRect(rect, paint);
                paint.setShader(null);
                paint.setColor(0x14FFFFFF);
                for (float y = rect.top; y < rect.bottom; y += dp(3)) {
                    canvas.drawRect(rect.left, y, rect.right, y + dp(1), paint);
                }
                break;
            }
            case TITANIUM: {
                opaque(); paint.setShader(new LinearGradient(rect.left, rect.top, rect.right, rect.bottom, new int[]{0xFFF1F2F5, 0xFFD9DCE2, 0xFFEDEFF3}, null, Shader.TileMode.CLAMP));
                canvas.drawRect(rect, paint);
                paint.setShader(null);
                // brushed metal
                for (float y = rect.top; y < rect.bottom; y += dp(1.5f)) {
                    paint.setColor(((int) ((y * 7.31f) % 5) < 2) ? 0x0A000000 : 0x0DFFFFFF);
                    canvas.drawRect(rect.left, y, rect.right, y + dp(0.75f), paint);
                }
                break;
            }
            case MIDNIGHT: {
                opaque(); paint.setShader(new LinearGradient(rect.left, rect.top, rect.right, rect.bottom, 0xFF17181E, 0xFF050506, Shader.TileMode.CLAMP));
                canvas.drawRect(rect, paint);
                paint.setShader(null);
                // fine golden rays from the corner
                paint.setStrokeWidth(Math.max(1, dp(0.6f)));
                for (int i = 0; i < 26; i++) {
                    final double a = Math.toRadians(180 + i * 3.6);
                    paint.setColor(i % 2 == 0 ? 0x22D4AF37 : 0x12D4AF37);
                    canvas.drawLine(rect.right, rect.bottom, rect.right + (float) Math.cos(a) * w * 1.3f, rect.bottom + (float) Math.sin(a) * w * 1.3f, paint);
                }
                break;
            }
            case OCEAN: {
                opaque(); paint.setShader(new LinearGradient(rect.left, rect.top, rect.left, rect.bottom, new int[]{0xFF4FC3F7, 0xFF1E73E8, 0xFF0B2E73}, null, Shader.TileMode.CLAMP));
                canvas.drawRect(rect, paint);
                paint.setShader(null);
                for (int i = 0; i < 4; i++) {
                    wave.rewind();
                    final float y0 = rect.top + h * (0.55f + i * 0.12f);
                    wave.moveTo(rect.left, y0);
                    for (float x = 0; x <= w; x += dp(4)) {
                        wave.lineTo(rect.left + x, y0 + (float) Math.sin(x / w * Math.PI * 2.5 + i * 0.9) * h * 0.05f);
                    }
                    wave.lineTo(rect.right, rect.bottom);
                    wave.lineTo(rect.left, rect.bottom);
                    wave.close();
                    paint.setColor(ColorUtils.setAlphaComponent(0xFF0A2A66, 40 + i * 18));
                    canvas.drawPath(wave, paint);
                }
                break;
            }
            case MINT: {
                opaque(); paint.setShader(new LinearGradient(rect.left, rect.top, rect.right, rect.bottom, new int[]{0xFFD8FBEA, 0xFFA6EFCF, 0xFF7FE0BE}, null, Shader.TileMode.CLAMP));
                canvas.drawRect(rect, paint);
                paint.setShader(null);
                paint.setColor(0x1A0F6E57);
                for (float y = rect.top + dp(10); y < rect.bottom; y += dp(12)) {
                    for (float x = rect.left + dp(10); x < rect.right; x += dp(12)) {
                        canvas.drawCircle(x, y, dp(1.2f), paint);
                    }
                }
                break;
            }
            default:
                // matte plastic: one colour with a very soft light from the top
                opaque(); paint.setShader(new LinearGradient(rect.left, rect.top, rect.left, rect.bottom,
                    ColorUtils.blendARGB(base, Color.WHITE, 0.06f), ColorUtils.blendARGB(base, Color.BLACK, 0.06f), Shader.TileMode.CLAMP));
                canvas.drawRect(rect, paint);
        }
        paint.setShader(null);
    }

    /** The Solegram sun pressed into the card, in its own colour: a darker face with a light lip below. */
    private void drawDeboss(Canvas canvas, float x, float y, float size) {
        final boolean light = lightDesign();
        final float shift = Math.max(1, dp(0.8f));
        // the light lip under the press only on the black and the white card; on coloured cards it read as a white
        // outline, so there the sun is pressed in plain black
        if (design == 0 || design == 1) {
            mark.setColorFilter(new android.graphics.PorterDuffColorFilter(Color.WHITE, android.graphics.PorterDuff.Mode.SRC_IN));
            mark.setAlpha(light ? 140 : 15);
            mark.setBounds((int) (x + shift), (int) (y + shift), (int) (x + size + shift), (int) (y + size + shift));
            mark.draw(canvas);
        }
        mark.setColorFilter(new android.graphics.PorterDuffColorFilter(Color.BLACK, android.graphics.PorterDuff.Mode.SRC_IN));
        mark.setAlpha(design == 7 ? 26 : light ? 15 : 46);
        mark.setBounds((int) x, (int) y, (int) (x + size), (int) (y + size));
        mark.draw(canvas);
        mark.setColorFilter(null);
        mark.setAlpha(255);
    }

    // ------------------------------------------------------------------ faces

    private void drawFront(Canvas canvas, float w, float h) {
        final float pad = h * 0.1f;
        final int ink = ink();
        // the sun, pressed into the card in the top right, above the number
        final float ss = h * 0.56f;
        if (giftImage != null) {
            final float gs = h * 0.4f;
            giftImage.setImageCoords(rect.right - pad - gs, rect.top + pad * 0.7f, gs, gs);
            giftImage.draw(canvas);
        } else if (design < HOLO || design == TITANIUM) {
            drawDeboss(canvas, rect.right - pad * 0.6f - ss, rect.top + h * 0.04f, ss);
        }
        // the wallet's name
        text.setColor(ColorUtils.setAlphaComponent(ink, 235));
        text.setTypeface(AndroidUtilities.bold());
        text.setTextSize(h * 0.075f);
        canvas.drawText("SoleWallet", rect.left + pad, rect.top + pad + h * 0.06f, text);
        // the chip
        final float chipW = h * 0.2f, chipH = h * 0.15f, chipL = rect.left + pad, chipT = rect.top + h * 0.36f;
        opaque(); paint.setShader(new LinearGradient(chipL, chipT, chipL + chipW, chipT + chipH, 0xFFE9D08A, 0xFFB99442, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(chipL, chipT, chipL + chipW, chipT + chipH, h * 0.025f, h * 0.025f, paint);
        paint.setShader(null);
        paint.setColor(0x59503C14);
        paint.setStrokeWidth(Math.max(1, h * 0.005f));
        final float a = chipL + chipW / 3f, b = chipL + chipW * 2f / 3f;
        canvas.drawLine(chipL, chipT + chipH / 2f, a, chipT + chipH / 2f, paint);
        canvas.drawLine(b, chipT + chipH / 2f, chipL + chipW, chipT + chipH / 2f, paint);
        canvas.drawLine(a, chipT, a, chipT + chipH, paint);
        canvas.drawLine(b, chipT, b, chipT + chipH, paint);
        canvas.drawLine(a, chipT + chipH * 0.3f, b, chipT + chipH * 0.3f, paint);
        canvas.drawLine(a, chipT + chipH * 0.7f, b, chipT + chipH * 0.7f, paint);
        // the contactless sign, right of the chip: three arcs around one centre
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(h * 0.012f);
        paint.setColor(ColorUtils.setAlphaComponent(ink, 190));
        final float wcx = chipL + chipW + h * 0.06f, wcy = chipT + chipH / 2f;
        for (int i = 0; i < 3; i++) {
            final float rr = h * (0.025f + i * 0.022f);
            tmp.set(wcx - rr, wcy - rr, wcx + rr, wcy + rr);
            canvas.drawArc(tmp, -50, 100, false, paint);
        }
        paint.setStyle(Paint.Style.FILL);
        // the number, embossed: a light (or dark) lip under the digits
        text.setTypeface(AndroidUtilities.getTypeface(AndroidUtilities.TYPEFACE_ROBOTO_MONO));
        text.setTextSize(h * 0.082f);
        final float maxW = w - pad * 2;
        if (text.measureText(numberMasked) > maxW) {
            text.setTextSize(text.getTextSize() * maxW / text.measureText(numberMasked));
        }
        final float lip = Math.max(1, dp(0.8f));
        text.setColor(lightDesign() ? 0xB3FFFFFF : 0x59000000);
        canvas.drawText(numberMasked, rect.left + pad + lip * 0.6f, rect.top + h * 0.69f + lip, text);
        text.setColor(ColorUtils.setAlphaComponent(ink, 242));
        canvas.drawText(numberMasked, rect.left + pad, rect.top + h * 0.69f, text);
        // the name, and the sun where cards carry their network's mark
        text.setTypeface(AndroidUtilities.bold());
        text.setTextSize(h * 0.055f);
        text.setColor(ColorUtils.setAlphaComponent(ink, 218));
        final float ms = h * 0.14f;
        canvas.drawText(TextUtils.ellipsize(name, text, w - pad * 3 - ms, TextUtils.TruncateAt.END).toString(), rect.left + pad, rect.bottom - pad, text);
        mark.setColorFilter(new android.graphics.PorterDuffColorFilter(ink, android.graphics.PorterDuff.Mode.SRC_IN));
        mark.setAlpha(242);
        mark.setBounds((int) (rect.right - pad - ms), (int) (rect.bottom - pad - ms * 0.8f), (int) (rect.right - pad), (int) (rect.bottom - pad + ms * 0.2f));
        mark.draw(canvas);
        mark.setColorFilter(null);
        mark.setAlpha(255);
    }

    private void drawBack(Canvas canvas, float w, float h) {
        final float pad = h * 0.11f;
        final int ink = ink();
        // the stripe
        paint.setColor(0xE6121216);
        canvas.drawRect(rect.left, rect.top + h * 0.12f, rect.right, rect.top + h * 0.3f, paint);
        // the signature panel, left blank as on a new card (the name is on the front already)
        final float sigT = rect.top + h * 0.4f, sigH = h * 0.16f;
        paint.setColor(0xF2F4F4F0);
        canvas.drawRoundRect(rect.left + pad, sigT, rect.left + w * 0.7f, sigT + sigH, h * 0.02f, h * 0.02f, paint);
        canvas.save();
        tmp.set(rect.left + pad, sigT, rect.left + w * 0.7f, sigT + sigH);
        canvas.clipRect(tmp);
        paint.setColor(0x14000000);
        paint.setStrokeWidth(Math.max(1, dp(0.7f)));
        for (float x = tmp.left - sigH; x < tmp.right; x += dp(5)) {
            canvas.drawLine(x, sigT, x + sigH * 0.6f, sigT + sigH, paint);
        }
        canvas.restore();
        // the whole number
        text.setColor(ink);
        text.setTypeface(AndroidUtilities.getTypeface(AndroidUtilities.TYPEFACE_ROBOTO_MONO));
        text.setTextSize(h * 0.08f);
        final float maxW = w - pad * 2;
        if (text.measureText(number) > maxW) {
            text.setTextSize(text.getTextSize() * maxW / text.measureText(number));
        }
        canvas.drawText(number, rect.left + pad, rect.top + h * 0.72f, text);
        // a line on what the card is
        text.setTypeface(AndroidUtilities.bold());
        text.setTextSize(h * 0.05f);
        text.setColor(ColorUtils.setAlphaComponent(ink, 170));
        final String note = LocaleController.getString(R.string.SG_WalletBackNote);
        canvas.drawText(TextUtils.ellipsize(note, text, maxW, TextUtils.TruncateAt.END).toString(), rect.left + pad, rect.bottom - pad * 0.95f, text);
    }
}
