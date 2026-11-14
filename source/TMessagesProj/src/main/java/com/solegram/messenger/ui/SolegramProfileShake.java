/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.ProfileActionsView;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The profile's easter egg: shake the phone and everything in the profile's header falls (the avatar, the name, the
 * status, the buttons), tumbles, bounces and piles up on the header's floor, where the account's info starts. They
 * keep following the real gravity: tilt the phone and they slide, turn it over and they fall to the header's top.
 * A tap tosses one up; shake again and they fly back. Leaving the profile, or scrolling it, puts them back at once.
 */
@SuppressLint("ViewConstructor")
public class SolegramProfileShake extends View implements SensorEventListener {

    private static final float GRAVITY = 2600f; // dp per second squared at 1 g
    private static final float RESTITUTION = 0.3f;
    private static final long RETURN_MS = 560;

    private static final class Body {
        Bitmap bitmap;
        float w, h;
        float homeX, homeY;
        float x, y, angle, vx, vy, va;
        float fromX, fromY, fromAngle;
        View view;
        float viewAlpha;
        boolean resting;
    }

    private final ProfileActionsView actions;
    private final ArrayList<Body> bodies = new ArrayList<>();
    private final ArrayList<View> hidden = new ArrayList<>();
    private final ArrayList<Float> hiddenAlpha = new ArrayList<>();
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Random random = new Random();
    private final int[] loc = new int[2], rootLoc = new int[2];
    private final RectF a = new RectF(), b = new RectF();
    private float floorY, ceilingY;
    private float anchorWindowY;
    private View anchor;
    private long lastFrame, returnStart, quietSince;
    private boolean returning;
    // gravity on the screen, in g: x to the right, y down (straight down until the sensor says otherwise)
    private volatile float gx = 0f, gy = 1f;
    private SensorManager sensors;

    private SolegramProfileShake(Context context, @Nullable ProfileActionsView actions) {
        super(context);
        this.actions = actions;
    }

    /**
     * Shake: drops the header's contents, or calls them back if they are down. {@code views} fall (pictures of
     * them), {@code hide} are only hidden meanwhile (rings and decorations round the avatar). Returns the overlay now
     * in use (or null).
     */
    @Nullable
    public static SolegramProfileShake toggle(@Nullable SolegramProfileShake current, ViewGroup root, @Nullable ProfileActionsView actions,
                                              List<View> views, List<View> hide, @Nullable View floorAnchor) {
        if (current != null && current.getParent() != null) {
            current.callBack();
            return current;
        }
        if (root == null || root.getWidth() <= 0) {
            return null;
        }
        final SolegramProfileShake overlay = new SolegramProfileShake(root.getContext(), actions);
        if (!overlay.collect(root, views, hide, floorAnchor)) {
            return null;
        }
        root.addView(overlay, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        overlay.drop();
        return overlay;
    }

    private boolean collect(ViewGroup root, List<View> views, List<View> hide, @Nullable View floorAnchor) {
        root.getLocationInWindow(rootLoc);
        float floor = 0;
        if (actions != null && actions.getVisibility() == VISIBLE && actions.isShown()) {
            actions.getLocationInWindow(loc);
            final int count = actions.sgVisibleCount();
            final RectF r = new RectF();
            for (int i = 0; i < count; i++) {
                final Bitmap bitmap = actions.sgSnapshot(i, r);
                if (bitmap == null) {
                    continue;
                }
                final Body body = new Body();
                body.bitmap = bitmap;
                body.w = r.width();
                body.h = r.height();
                body.homeX = loc[0] - rootLoc[0] + r.centerX();
                body.homeY = loc[1] - rootLoc[1] + r.centerY();
                bodies.add(body);
                floor = Math.max(floor, loc[1] - rootLoc[1] + r.bottom + dp(6));
            }
        }
        for (View v : views) {
            if (!usable(v)) {
                continue;
            }
            final Bitmap bitmap;
            try {
                bitmap = Bitmap.createBitmap(v.getWidth(), v.getHeight(), Bitmap.Config.ARGB_8888);
                v.draw(new Canvas(bitmap));
            } catch (Throwable e) {
                continue;
            }
            v.getLocationInWindow(loc);
            final Body body = new Body();
            body.bitmap = bitmap;
            body.w = v.getWidth() * Math.abs(v.getScaleX());
            body.h = v.getHeight() * Math.abs(v.getScaleY());
            body.homeX = loc[0] - rootLoc[0] + body.w / 2f;
            body.homeY = loc[1] - rootLoc[1] + body.h / 2f;
            body.view = v;
            body.viewAlpha = v.getAlpha();
            bodies.add(body);
            floor = Math.max(floor, body.homeY + body.h / 2f);
        }
        for (View v : hide) {
            if (v != null && v.getVisibility() == VISIBLE) {
                hidden.add(v);
                hiddenAlpha.add(v.getAlpha());
            }
        }
        if (bodies.isEmpty()) {
            return false;
        }
        if (floorAnchor != null && floorAnchor.isShown()) {
            floorAnchor.getLocationInWindow(loc);
            floor = Math.max(floor, loc[1] - rootLoc[1] + floorAnchor.getHeight() * Math.abs(floorAnchor.getScaleY()));
        }
        anchor = actions != null && actions.isShown() ? actions : floorAnchor;
        if (anchor != null) {
            anchor.getLocationInWindow(loc);
            anchorWindowY = loc[1];
        }
        floorY = floor > 0 ? floor : root.getHeight() * 0.35f;
        ceilingY = 0;
        return true;
    }

    private static boolean usable(View v) {
        return v != null && v.getVisibility() == VISIBLE && v.isShown() && v.getAlpha() >= 0.05f && v.getWidth() > 0 && v.getHeight() > 0
            && v.getWidth() * v.getHeight() < 1600 * 1600;
    }

    private void drop() {
        for (Body body : bodies) {
            body.x = body.homeX;
            body.y = body.homeY;
            body.angle = 0;
            body.vx = dp(-90 + random.nextInt(180));
            body.vy = -dp(60 + random.nextInt(160));
            body.va = -200 + random.nextInt(400);
            if (body.view != null) {
                body.view.setAlpha(0f);
            }
        }
        for (View v : hidden) {
            v.setAlpha(0f);
        }
        if (actions != null) {
            actions.sgSetFallen(true);
        }
        try {
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
        } catch (Throwable ignore) {
        }
        lastFrame = 0;
        quietSince = 0;
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        try {
            sensors = (SensorManager) getContext().getSystemService(Context.SENSOR_SERVICE);
            Sensor s = sensors == null ? null : sensors.getDefaultSensor(Sensor.TYPE_GRAVITY);
            if (s == null && sensors != null) {
                s = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            }
            if (s != null) {
                sensors.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME);
            }
        } catch (Throwable ignore) {
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (sensors != null) {
            try {
                sensors.unregisterListener(this);
            } catch (Throwable ignore) {
            }
            sensors = null;
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        // the device's axes turned into the screen's (the screen may be rotated)
        float dx = -event.values[0], dy = event.values[1];
        int rotation = Surface.ROTATION_0;
        try {
            final WindowManager wm = (WindowManager) getContext().getSystemService(Context.WINDOW_SERVICE);
            rotation = wm.getDefaultDisplay().getRotation();
        } catch (Throwable ignore) {
        }
        float sx, sy;
        switch (rotation) {
            case Surface.ROTATION_90: sx = -dy; sy = dx; break;
            case Surface.ROTATION_180: sx = -dx; sy = -dy; break;
            case Surface.ROTATION_270: sx = dy; sy = -dx; break;
            default: sx = dx; sy = dy;
        }
        final float nx = sx / SensorManager.GRAVITY_EARTH, ny = sy / SensorManager.GRAVITY_EARTH;
        // smoothed: the accelerometer also feels the hand
        gx = gx * 0.8f + nx * 0.2f;
        gy = gy * 0.8f + ny * 0.2f;
        if (!returning && (Math.abs(nx - gx) > 0.02f || Math.abs(ny - gy) > 0.02f || quietSince != 0)) {
            quietSince = 0;
            postInvalidateOnAnimation();
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    /** Back to their places, flying. */
    public void callBack() {
        if (returning) {
            return;
        }
        returning = true;
        returnStart = SystemClock.uptimeMillis();
        for (Body body : bodies) {
            body.fromX = body.x;
            body.fromY = body.y;
            float an = body.angle % 360f;
            if (an > 180f) {
                an -= 360f;
            } else if (an < -180f) {
                an += 360f;
            }
            body.fromAngle = an;
        }
        invalidate();
    }

    /** At once, nothing animated (leaving the profile, scrolling it). */
    public void restoreNow() {
        for (Body body : bodies) {
            if (body.view != null) {
                body.view.setAlpha(body.viewAlpha);
            }
            if (body.bitmap != null) {
                body.bitmap.recycle();
                body.bitmap = null;
            }
        }
        bodies.clear();
        for (int i = 0; i < hidden.size(); i++) {
            hidden.get(i).setAlpha(hiddenAlpha.get(i));
        }
        hidden.clear();
        if (actions != null) {
            actions.sgSetFallen(false);
        }
        if (getParent() instanceof ViewGroup) {
            ((ViewGroup) getParent()).removeView(this);
        }
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        if (anchor != null && !returning) {
            anchor.getLocationInWindow(loc);
            if (Math.abs(loc[1] - anchorWindowY) > dp(3)) {
                post(this::restoreNow);
                return;
            }
        }
        final long now = SystemClock.uptimeMillis();
        if (returning) {
            final float t = Math.min(1f, (now - returnStart) / (float) RETURN_MS);
            final float e = CubicBezierInterpolator.EASE_OUT_BACK.getInterpolation(t);
            for (Body body : bodies) {
                body.x = body.fromX + (body.homeX - body.fromX) * e;
                body.y = body.fromY + (body.homeY - body.fromY) * e;
                body.angle = body.fromAngle * (1f - e);
                drawBody(canvas, body);
            }
            if (t >= 1f) {
                post(this::restoreNow);
            } else {
                postInvalidateOnAnimation();
            }
            return;
        }
        final float dt = lastFrame == 0 ? 1f / 60f : Math.min(1f / 30f, (now - lastFrame) / 1000f);
        lastFrame = now;
        // two half steps: stacks stay calmer
        boolean moving = step(dt / 2f);
        moving |= step(dt / 2f);
        for (Body body : bodies) {
            if (body.view != null && body.view.getAlpha() != 0f) {
                body.view.setAlpha(0f);
            }
            drawBody(canvas, body);
        }
        for (View v : hidden) {
            if (v.getAlpha() != 0f) {
                v.setAlpha(0f);
            }
        }
        if (moving) {
            quietSince = 0;
            postInvalidateOnAnimation();
        } else if (quietSince == 0) {
            quietSince = now;
            postInvalidateOnAnimation();
        } else if (now - quietSince < 400) {
            postInvalidateOnAnimation();
        }
    }

    private void drawBody(Canvas canvas, Body body) {
        if (body.bitmap == null) {
            return;
        }
        canvas.save();
        canvas.translate(body.x, body.y);
        canvas.rotate(body.angle);
        canvas.scale(body.w / body.bitmap.getWidth(), body.h / body.bitmap.getHeight());
        canvas.drawBitmap(body.bitmap, -body.bitmap.getWidth() / 2f, -body.bitmap.getHeight() / 2f, paint);
        canvas.restore();
    }

    private static float halfW(Body body) {
        final double rad = Math.toRadians(body.angle);
        return (float) (Math.abs(body.w / 2f * Math.cos(rad)) + Math.abs(body.h / 2f * Math.sin(rad)));
    }

    private static float halfH(Body body) {
        final double rad = Math.toRadians(body.angle);
        return (float) (Math.abs(body.w / 2f * Math.sin(rad)) + Math.abs(body.h / 2f * Math.cos(rad)));
    }

    /** One step of the world; returns whether anything still moves. */
    private boolean step(float dt) {
        final float g = dp(GRAVITY);
        final float ax = gx * g, ay = gy * g;
        final float left = 0, right = getWidth();
        boolean moving = false;
        for (Body body : bodies) {
            body.vx += ax * dt;
            body.vy += ay * dt;
            body.x += body.vx * dt;
            body.y += body.vy * dt;
            body.angle += body.va * dt;
            final float hw = halfW(body), hh = halfH(body);
            boolean touching = false;
            if (body.y + hh > floorY) {
                body.y = floorY - hh;
                if (body.vy > 0) {
                    body.vy = Math.abs(body.vy) < dp(40) ? 0 : -body.vy * RESTITUTION;
                }
                body.vx *= 0.9f;
                touching = true;
            } else if (body.y - hh < ceilingY) {
                body.y = ceilingY + hh;
                if (body.vy < 0) {
                    body.vy = Math.abs(body.vy) < dp(40) ? 0 : -body.vy * RESTITUTION;
                }
                body.vx *= 0.9f;
                touching = true;
            }
            if (body.x - hw < left) {
                body.x = left + hw;
                if (body.vx < 0) {
                    body.vx = Math.abs(body.vx) < dp(40) ? 0 : -body.vx * RESTITUTION;
                }
                body.vy *= 0.9f;
                touching = true;
            } else if (body.x + hw > right) {
                body.x = right - hw;
                if (body.vx > 0) {
                    body.vx = Math.abs(body.vx) < dp(40) ? 0 : -body.vx * RESTITUTION;
                }
                body.vy *= 0.9f;
                touching = true;
            }
            if (touching) {
                // lying against a wall: it turns to lie flat on it
                final float flat = Math.round(body.angle / 90f) * 90f;
                body.va = body.va * 0.82f + (flat - body.angle) * 8f;
            } else {
                body.va *= 0.995f;
            }
            if (Math.abs(body.vx) > dp(5) || Math.abs(body.vy) > dp(5) || Math.abs(body.va) > 5) {
                moving = true;
            }
        }
        // the pile: overlapping boxes are pushed apart along the shorter overlap
        for (int i = 0; i < bodies.size(); i++) {
            final Body p = bodies.get(i);
            final float pw = halfW(p) * 0.94f, ph = halfH(p) * 0.94f;
            for (int j = i + 1; j < bodies.size(); j++) {
                final Body q = bodies.get(j);
                final float qw = halfW(q) * 0.94f, qh = halfH(q) * 0.94f;
                final float ox = (pw + qw) - Math.abs(q.x - p.x);
                final float oy = (ph + qh) - Math.abs(q.y - p.y);
                if (ox <= 0 || oy <= 0) {
                    continue;
                }
                final float massP = p.w * p.h, massQ = q.w * q.h, total = massP + massQ;
                final float kp = massQ / total, kq = massP / total;
                if (ox < oy) {
                    final float sign = q.x >= p.x ? 1f : -1f;
                    p.x -= sign * ox * kp;
                    q.x += sign * ox * kq;
                    final float rel = (q.vx - p.vx) * sign;
                    if (rel < 0) {
                        final float imp = -rel * 0.55f;
                        p.vx -= sign * imp * kp;
                        q.vx += sign * imp * kq;
                    }
                } else {
                    final float sign = q.y >= p.y ? 1f : -1f;
                    p.y -= sign * oy * kp;
                    q.y += sign * oy * kq;
                    final float rel = (q.vy - p.vy) * sign;
                    if (rel < 0) {
                        final float imp = -rel * 0.55f;
                        p.vy -= sign * imp * kp;
                        q.vy += sign * imp * kq;
                    }
                    // resting on another: it settles flat too
                    final Body top = sign > 0 ? p : q;
                    final float flat = Math.round(top.angle / 90f) * 90f;
                    top.va = top.va * 0.85f + (flat - top.angle) * 6f;
                    top.vx *= 0.92f;
                }
            }
        }
        return moving;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (returning) {
            return true;
        }
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            for (int i = bodies.size() - 1; i >= 0; i--) {
                final Body body = bodies.get(i);
                if (Math.abs(event.getX() - body.x) < halfW(body) && Math.abs(event.getY() - body.y) < halfH(body)) {
                    // tossed against the gravity
                    final float len = Math.max(0.2f, (float) Math.hypot(gx, gy));
                    final float speed = dp(700 + random.nextInt(300));
                    body.vx = -gx / len * speed + dp(-120 + random.nextInt(240));
                    body.vy = -gy / len * speed;
                    body.va = -540 + random.nextInt(1080);
                    lastFrame = 0;
                    quietSince = 0;
                    invalidate();
                    return true;
                }
            }
        }
        return false;
    }
}
