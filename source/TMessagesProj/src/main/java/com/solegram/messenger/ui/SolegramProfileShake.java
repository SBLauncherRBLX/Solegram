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
import android.view.ViewConfiguration;
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
 * The profile's easter egg: shake the phone and everything in the profile's header falls (the avatar, every letter of
 * the name by itself, the status, the buttons): rigid bodies that tumble, tip over their corners, roll (a round avatar),
 * slide with friction and pile up on the header's floor, where the account's info starts. They follow the real
 * gravity: tilt the phone and they slide, turn it over and they fall to the header's top. Grab one and throw it, tap
 * one to toss it; shake again and they fly back. Leaving the profile, or scrolling it, puts them back at once.
 */
@SuppressLint("ViewConstructor")
public class SolegramProfileShake extends View implements SensorEventListener {

    private static final float GRAVITY = 2400f; // dp per second squared at 1 g
    private static final float RESTITUTION = 0.22f;
    private static final float FRICTION = 0.55f;
    private static final int SUBSTEPS = 3, ITERATIONS = 6;
    private static final long RETURN_MS = 600;
    /** Bigger pictures are kept smaller (memory, drawing). */
    private static final int MAX_BITMAP_SIDE = 720;

    private static final class Body {
        Bitmap bitmap;
        float w, h; // drawn size (px)
        boolean circle;
        float hw, hh, radius;
        float invMass, invInertia;
        float homeX, homeY;
        float x, y, angle, vx, vy, va; // angle in radians, va in radians per second
        float fromX, fromY, fromAngle;
        View view;
        float viewAlpha;
        // the corners of the box now (x0,y0..x3,y3)
        final float[] corners = new float[8];

        void setup() {
            hw = w / 2f;
            hh = h / 2f;
            radius = Math.min(hw, hh);
            final float unit = dp(40);
            final float area = circle ? (float) Math.PI * radius * radius : w * h;
            final float mass = Math.max(0.05f, area / (unit * unit));
            invMass = 1f / mass;
            final float inertia = circle ? mass * radius * radius / 2f : mass * (w * w + h * h) / 12f;
            invInertia = 1f / inertia;
        }

        void updateCorners() {
            final float c = (float) Math.cos(angle), s = (float) Math.sin(angle);
            final float ax = c * hw, ay = s * hw, bx = -s * hh, by = c * hh;
            corners[0] = x - ax - bx; corners[1] = y - ay - by;
            corners[2] = x + ax - bx; corners[3] = y + ay - by;
            corners[4] = x + ax + bx; corners[5] = y + ay + by;
            corners[6] = x - ax + bx; corners[7] = y - ay + by;
        }

        /** Whether a point is inside (with a margin). */
        boolean contains(float px, float py, float margin) {
            final float dx = px - x, dy = py - y;
            if (circle) {
                return dx * dx + dy * dy <= (radius + margin) * (radius + margin);
            }
            final float c = (float) Math.cos(angle), s = (float) Math.sin(angle);
            final float lx = dx * c + dy * s, ly = -dx * s + dy * c;
            return Math.abs(lx) <= hw + margin && Math.abs(ly) <= hh + margin;
        }
    }

    /** A touch between two bodies (b null: a wall). n points from a to b (the way b is pushed). */
    private static final class Contact {
        Body a, b;
        float px, py, nx, ny, depth;
        float pn, pt; // accumulated impulses
        float bounce;
    }

    private final ProfileActionsView actions;
    private final ArrayList<Body> bodies = new ArrayList<>();
    private final ArrayList<View> hidden = new ArrayList<>();
    private final ArrayList<Float> hiddenAlpha = new ArrayList<>();
    private final ArrayList<Contact> contacts = new ArrayList<>();
    private final ArrayList<Contact> contactPool = new ArrayList<>();
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Random random = new Random();
    private final int[] loc = new int[2], rootLoc = new int[2];
    private float floorY, ceilingY;
    private float anchorWindowY;
    private View anchor;
    private long lastFrame, returnStart, quietSince;
    private boolean returning;

    /** Whether the fallen header is on its way back (the gift bubbles come down with it). */
    public boolean isReturning() {
        return returning;
    }
    // gravity on the screen, in g: x to the right, y down (straight down until the sensor says otherwise)
    private volatile float gx = 0f, gy = 1f;
    private float lastGx = 0f, lastGy = 1f;
    private int rotation;
    private SensorManager sensors;

    // a finger holding a body: where it holds it (in the body's own frame) and where the finger is
    private Body held;
    private float holdLocalX, holdLocalY, fingerX, fingerY, downX, downY;
    private long downTime;
    private boolean dragging;

    private SolegramProfileShake(Context context, @Nullable ProfileActionsView actions) {
        super(context);
        this.actions = actions;
    }

    /**
     * Shake: drops the header's contents, or calls them back if they are down. {@code views} fall (pictures of
     * them; {@code letters} fall letter by letter), {@code hide} are only hidden meanwhile (rings and decorations round
     * the avatar). {@code round}: the avatar falls as a ball. Returns the overlay now in use (or null).
     */
    @Nullable
    public static SolegramProfileShake toggle(@Nullable SolegramProfileShake current, ViewGroup root, @Nullable ProfileActionsView actions,
                                              List<View> views, List<View> letters, @Nullable View round, List<View> hide, @Nullable View floorAnchor) {
        if (current != null && current.getParent() != null) {
            current.callBack();
            return current;
        }
        if (root == null || root.getWidth() <= 0) {
            return null;
        }
        final SolegramProfileShake overlay = new SolegramProfileShake(root.getContext(), actions);
        if (!overlay.collect(root, views, letters, round, hide, floorAnchor)) {
            overlay.restoreNow();
            return null;
        }
        root.addView(overlay, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        overlay.drop();
        return overlay;
    }

    // region taking the pictures

    private boolean collect(ViewGroup root, List<View> views, List<View> letters, @Nullable View round, List<View> hide, @Nullable View floorAnchor) {
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
                addBody(bitmap, loc[0] - rootLoc[0] + r.left, loc[1] - rootLoc[1] + r.top, r.width(), r.height(), null, false);
                floor = Math.max(floor, loc[1] - rootLoc[1] + r.bottom + dp(6));
            }
        }
        for (View v : views) {
            if (!usable(v)) {
                continue;
            }
            final Bitmap bitmap = picture(v);
            if (bitmap == null) {
                continue;
            }
            v.getLocationInWindow(loc);
            final float sx = Math.abs(v.getScaleX()), sy = Math.abs(v.getScaleY());
            final Body body = addBody(bitmap, loc[0] - rootLoc[0], loc[1] - rootLoc[1], v.getWidth() * sx, v.getHeight() * sy, v, v == round);
            floor = Math.max(floor, body.homeY + body.h / 2f);
        }
        for (View v : letters) {
            if (!usable(v)) {
                continue;
            }
            final Bitmap bitmap = picture(v);
            if (bitmap == null) {
                continue;
            }
            v.getLocationInWindow(loc);
            final float sx = Math.abs(v.getScaleX()), sy = Math.abs(v.getScaleY());
            final int before = bodies.size();
            splitLetters(bitmap, loc[0] - rootLoc[0], loc[1] - rootLoc[1], sx, sy, v);
            boolean kept = false;
            for (int i = before; i < bodies.size(); i++) {
                kept |= bodies.get(i).bitmap == bitmap;
            }
            if (!kept) {
                bitmap.recycle();
            }
            for (int i = before; i < bodies.size(); i++) {
                floor = Math.max(floor, bodies.get(i).homeY + bodies.get(i).h / 2f);
            }
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

    @Nullable
    private static Bitmap picture(View v) {
        try {
            final Bitmap bitmap = Bitmap.createBitmap(v.getWidth(), v.getHeight(), Bitmap.Config.ARGB_8888);
            v.draw(new Canvas(bitmap));
            return bitmap;
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * Adds a body for a picture shown at (left, top) with the size (w, h) on the screen; the picture is cut to what is
     * drawn in it (the empty margins do not collide) and kept no bigger than needed.
     */
    private Body addBody(Bitmap bitmap, float left, float top, float w, float h, @Nullable View view, boolean round) {
        final int[] trim = trim(bitmap, 0, bitmap.getWidth());
        Bitmap use = bitmap;
        float l = left, t = top, ww = w, hh = h;
        if (trim != null && (trim[0] > 0 || trim[1] > 0 || trim[2] < bitmap.getWidth() || trim[3] < bitmap.getHeight())) {
            final float kx = w / bitmap.getWidth(), ky = h / bitmap.getHeight();
            try {
                use = Bitmap.createBitmap(bitmap, trim[0], trim[1], trim[2] - trim[0], trim[3] - trim[1]);
                l = left + trim[0] * kx;
                t = top + trim[1] * ky;
                ww = (trim[2] - trim[0]) * kx;
                hh = (trim[3] - trim[1]) * ky;
                if (use != bitmap) {
                    bitmap.recycle();
                }
            } catch (Throwable e) {
                use = bitmap;
            }
        }
        use = limit(use);
        final Body body = new Body();
        body.bitmap = use;
        body.w = Math.max(1, ww);
        body.h = Math.max(1, hh);
        body.circle = round && Math.abs(ww - hh) < Math.max(ww, hh) * 0.08f;
        if (body.circle) {
            body.w = body.h = Math.max(ww, hh);
        }
        body.homeX = l + body.w / 2f;
        body.homeY = t + body.h / 2f;
        body.view = view;
        body.viewAlpha = view != null ? view.getAlpha() : 1f;
        body.setup();
        bodies.add(body);
        return body;
    }

    private static Bitmap limit(Bitmap bitmap) {
        final int side = Math.max(bitmap.getWidth(), bitmap.getHeight());
        if (side <= MAX_BITMAP_SIDE) {
            return bitmap;
        }
        final float k = MAX_BITMAP_SIDE / (float) side;
        try {
            final Bitmap small = Bitmap.createScaledBitmap(bitmap, Math.max(1, Math.round(bitmap.getWidth() * k)), Math.max(1, Math.round(bitmap.getHeight() * k)), true);
            if (small != bitmap) {
                bitmap.recycle();
            }
            return small;
        } catch (Throwable e) {
            return bitmap;
        }
    }

    /** The drawn part of columns [from, to): {left, top, right, bottom}, null when nothing is drawn. */
    @Nullable
    private static int[] trim(Bitmap bitmap, int from, int to) {
        final int w = bitmap.getWidth(), h = bitmap.getHeight();
        final int[] row = new int[w];
        int top = -1, bottom = -1, left = to, right = from;
        for (int y = 0; y < h; y++) {
            bitmap.getPixels(row, 0, w, 0, y, w, 1);
            boolean any = false;
            for (int x = from; x < to; x++) {
                if ((row[x] >>> 24) > 16) {
                    any = true;
                    if (x < left) left = x;
                    if (x >= right) right = x + 1;
                }
            }
            if (any) {
                if (top < 0) top = y;
                bottom = y + 1;
            }
        }
        if (top < 0 || right <= left) {
            return null;
        }
        return new int[]{left, top, right, bottom};
    }

    /** The name: each letter (each piece of ink between empty columns) is a body of its own. */
    private void splitLetters(Bitmap bitmap, float left, float top, float sx, float sy, View view) {
        final int w = bitmap.getWidth(), h = bitmap.getHeight();
        final boolean[] ink = new boolean[w];
        final int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            bitmap.getPixels(row, 0, w, 0, y, w, 1);
            for (int x = 0; x < w; x++) {
                if ((row[x] >>> 24) > 24) {
                    ink[x] = true;
                }
            }
        }
        // pieces of ink; very thin ones (an apostrophe's edge, a stray pixel column) join their neighbour
        final ArrayList<int[]> pieces = new ArrayList<>();
        int start = -1;
        for (int x = 0; x <= w; x++) {
            final boolean on = x < w && ink[x];
            if (on && start < 0) {
                start = x;
            } else if (!on && start >= 0) {
                pieces.add(new int[]{start, x});
                start = -1;
            }
        }
        final int minWidth = Math.max(2, (int) (dp(2) / Math.max(0.1f, sx)));
        for (int i = 0; i < pieces.size(); i++) {
            final int[] p = pieces.get(i);
            if (p[1] - p[0] < minWidth && pieces.size() > 1) {
                final int j = i > 0 ? i - 1 : i + 1;
                final int[] q = pieces.get(j);
                q[0] = Math.min(q[0], p[0]);
                q[1] = Math.max(q[1], p[1]);
                pieces.remove(i);
                i--;
            }
        }
        if (pieces.size() > 60) {
            // a very long name: fall in a few pieces rather than a crowd
            final ArrayList<int[]> joined = new ArrayList<>();
            final int per = (pieces.size() + 29) / 30;
            for (int i = 0; i < pieces.size(); i += per) {
                joined.add(new int[]{pieces.get(i)[0], pieces.get(Math.min(pieces.size() - 1, i + per - 1))[1]});
            }
            pieces.clear();
            pieces.addAll(joined);
        }
        boolean first = true;
        for (int[] p : pieces) {
            final int[] t = trim(bitmap, p[0], p[1]);
            if (t == null) {
                continue;
            }
            final Bitmap piece;
            try {
                piece = Bitmap.createBitmap(bitmap, t[0], t[1], t[2] - t[0], t[3] - t[1]);
            } catch (Throwable e) {
                continue;
            }
            final Body body = new Body();
            body.bitmap = piece;
            body.w = Math.max(1, (t[2] - t[0]) * sx);
            body.h = Math.max(1, (t[3] - t[1]) * sy);
            body.homeX = left + t[0] * sx + body.w / 2f;
            body.homeY = top + t[1] * sy + body.h / 2f;
            // the first letter keeps the view (it is hidden and shown back), the rest are its pieces
            body.view = first ? view : null;
            body.viewAlpha = view.getAlpha();
            first = false;
            body.setup();
            bodies.add(body);
        }
    }

    private static boolean usable(View v) {
        return v != null && v.getVisibility() == VISIBLE && v.isShown() && v.getAlpha() >= 0.05f && v.getWidth() > 0 && v.getHeight() > 0
            && v.getWidth() * v.getHeight() < 1600 * 1600;
    }

    // endregion

    private void drop() {
        for (Body body : bodies) {
            body.x = body.homeX;
            body.y = body.homeY;
            body.angle = 0;
            // a little jump up and aside, a little spin: the smaller, the livelier
            final float lively = Math.min(2f, 1f / (float) Math.sqrt(Math.max(0.05f, body.invMass > 0 ? 1f / body.invMass : 1f)));
            body.vx = dp(-110 + random.nextInt(220)) * (0.6f + 0.4f * lively);
            body.vy = -dp(80 + random.nextInt(200)) * (0.6f + 0.4f * lively);
            body.va = (float) Math.toRadians(-240 + random.nextInt(480)) * lively;
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

    // region sensors

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        readRotation();
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
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        readRotation();
    }

    private void readRotation() {
        try {
            final WindowManager wm = (WindowManager) getContext().getSystemService(Context.WINDOW_SERVICE);
            rotation = wm.getDefaultDisplay().getRotation();
        } catch (Throwable ignore) {
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        // the device's axes turned into the screen's (the screen may be rotated)
        final float dx = -event.values[0], dy = event.values[1];
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
        if (!returning && quietSince != 0 && (Math.abs(gx - lastGx) > 0.03f || Math.abs(gy - lastGy) > 0.03f)) {
            // the phone turned: everything wakes up
            quietSince = 0;
            lastFrame = 0;
            postInvalidateOnAnimation();
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    // endregion

    /** Back to their places, flying. */
    public void callBack() {
        if (returning) {
            return;
        }
        returning = true;
        held = null;
        returnStart = SystemClock.uptimeMillis();
        for (Body body : bodies) {
            body.fromX = body.x;
            body.fromY = body.y;
            float an = (float) Math.toDegrees(body.angle) % 360f;
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
            for (int i = 0; i < bodies.size(); i++) {
                final Body body = bodies.get(i);
                // the letters come back one after another
                final float delay = Math.min(0.3f, i * 0.012f);
                final float e = CubicBezierInterpolator.EASE_OUT_BACK.getInterpolation(Math.max(0f, Math.min(1f, (t - delay) / (1f - delay))));
                final float x = body.fromX + (body.homeX - body.fromX) * e;
                final float y = body.fromY + (body.homeY - body.fromY) * e;
                drawBody(canvas, body, x, y, body.fromAngle * (1f - e));
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
        final boolean moving = simulate(dt);
        for (Body body : bodies) {
            if (body.view != null && body.view.getAlpha() != 0f) {
                body.view.setAlpha(0f);
            }
            drawBody(canvas, body, body.x, body.y, (float) Math.toDegrees(body.angle));
        }
        for (View v : hidden) {
            if (v.getAlpha() != 0f) {
                v.setAlpha(0f);
            }
        }
        if (moving || held != null) {
            quietSince = 0;
            postInvalidateOnAnimation();
        } else if (quietSince == 0) {
            quietSince = now;
            postInvalidateOnAnimation();
        } else if (now - quietSince < 500) {
            postInvalidateOnAnimation();
        } else {
            // asleep until the phone turns or a finger comes
            lastGx = gx;
            lastGy = gy;
        }
    }

    private void drawBody(Canvas canvas, Body body, float x, float y, float degrees) {
        if (body.bitmap == null) {
            return;
        }
        canvas.save();
        canvas.translate(x, y);
        canvas.rotate(degrees);
        canvas.scale(body.w / body.bitmap.getWidth(), body.h / body.bitmap.getHeight());
        canvas.drawBitmap(body.bitmap, -body.bitmap.getWidth() / 2f, -body.bitmap.getHeight() / 2f, paint);
        canvas.restore();
    }

    // region the physics: rigid bodies, impulses at the contact points (so they tip, roll and rest on their faces)

    private boolean simulate(float frameDt) {
        final float dt = frameDt / SUBSTEPS;
        final float g = dp(GRAVITY);
        final float ax = gx * g, ay = gy * g;
        for (int step = 0; step < SUBSTEPS; step++) {
            for (Body b : bodies) {
                b.vx += ax * dt;
                b.vy += ay * dt;
                // air
                final float air = (float) Math.exp(-0.15f * dt);
                b.vx *= air;
                b.vy *= air;
                b.va *= (float) Math.exp(-0.6f * dt);
            }
            if (held != null) {
                holdStep(dt);
            }
            findContacts();
            for (int it = 0; it < ITERATIONS; it++) {
                for (int i = 0; i < contacts.size(); i++) {
                    solve(contacts.get(i));
                }
            }
            for (Body b : bodies) {
                b.x += b.vx * dt;
                b.y += b.vy * dt;
                b.angle += b.va * dt;
            }
            // what the impulses left overlapping is pushed apart (a part of it per step: no jitter)
            for (int i = 0; i < contacts.size(); i++) {
                correct(contacts.get(i));
            }
        }
        boolean moving = false;
        final float slow = dp(6);
        for (Body b : bodies) {
            final boolean resting = touching(b);
            if (resting && Math.abs(b.vx) < dp(3) && Math.abs(b.vy) < dp(3) && Math.abs(b.va) < 0.05f) {
                // lying still: really still
                b.vx = b.vy = b.va = 0;
            }
            if (Math.abs(b.vx) > slow || Math.abs(b.vy) > slow || Math.abs(b.va) > 0.12f) {
                moving = true;
            }
        }
        return moving;
    }

    private boolean touching(Body b) {
        for (int i = 0; i < contacts.size(); i++) {
            final Contact c = contacts.get(i);
            if (c.a == b || c.b == b) {
                return true;
            }
        }
        return false;
    }

    private Contact contact(Body a, @Nullable Body b, float px, float py, float nx, float ny, float depth) {
        final Contact c;
        if (contactPool.isEmpty()) {
            c = new Contact();
        } else {
            c = contactPool.remove(contactPool.size() - 1);
        }
        c.a = a;
        c.b = b;
        c.px = px;
        c.py = py;
        c.nx = nx;
        c.ny = ny;
        c.depth = depth;
        c.pn = c.pt = 0;
        // the speed it hits with decides the bounce (resting contacts do not bounce)
        final float vn = relativeNormalSpeed(c);
        c.bounce = vn < -dp(90) ? -vn * RESTITUTION : 0;
        contacts.add(c);
        return c;
    }

    private void findContacts() {
        contactPool.addAll(contacts);
        contacts.clear();
        final float right = getWidth();
        for (Body b : bodies) {
            b.updateCorners();
            // walls: floor, ceiling, sides; n points from the body into the wall
            wall(b, 0, 1, floorY);
            wall(b, 0, -1, -ceilingY);
            wall(b, 1, 0, right);
            wall(b, -1, 0, 0);
        }
        for (int i = 0; i < bodies.size(); i++) {
            final Body a = bodies.get(i);
            for (int j = i + 1; j < bodies.size(); j++) {
                final Body b = bodies.get(j);
                // far apart: no need to look
                final float reach = Math.max(a.hw, a.hh) * 1.42f + Math.max(b.hw, b.hh) * 1.42f;
                if (Math.abs(a.x - b.x) > reach || Math.abs(a.y - b.y) > reach) {
                    continue;
                }
                if (a.circle && b.circle) {
                    circleCircle(a, b);
                } else if (a.circle) {
                    circleBox(a, b);
                } else if (b.circle) {
                    circleBox(b, a);
                } else {
                    boxBox(a, b);
                }
            }
        }
    }

    /** A wall where {@code n·p <= limit} is inside: n = (nx, ny) points out of the room. */
    private void wall(Body b, float nx, float ny, float limit) {
        if (b.circle) {
            final float d = b.x * nx + b.y * ny + b.radius - limit;
            if (d > 0) {
                contact(b, null, b.x + nx * b.radius, b.y + ny * b.radius, nx, ny, d);
            }
            return;
        }
        for (int k = 0; k < 4; k++) {
            final float px = b.corners[k * 2], py = b.corners[k * 2 + 1];
            final float d = px * nx + py * ny - limit;
            if (d > 0) {
                contact(b, null, px, py, nx, ny, d);
            }
        }
    }

    private void circleCircle(Body a, Body b) {
        final float dx = b.x - a.x, dy = b.y - a.y;
        final float dist = (float) Math.hypot(dx, dy);
        final float depth = a.radius + b.radius - dist;
        if (depth <= 0) {
            return;
        }
        final float nx = dist > 1e-3f ? dx / dist : 0, ny = dist > 1e-3f ? dy / dist : 1;
        contact(a, b, a.x + nx * a.radius, a.y + ny * a.radius, nx, ny, depth);
    }

    /** A circle {@code c} against a box {@code b}. */
    private void circleBox(Body c, Body b) {
        final float cos = (float) Math.cos(b.angle), sin = (float) Math.sin(b.angle);
        final float dx = c.x - b.x, dy = c.y - b.y;
        final float lx = dx * cos + dy * sin, ly = -dx * sin + dy * cos;
        final float qx = Math.max(-b.hw, Math.min(b.hw, lx)), qy = Math.max(-b.hh, Math.min(b.hh, ly));
        float nlx, nly, depth;
        if (qx == lx && qy == ly) {
            // the centre is inside the box: out through the nearest face
            final float px = b.hw - Math.abs(lx), py = b.hh - Math.abs(ly);
            if (px < py) {
                nlx = Math.signum(lx) == 0 ? 1 : Math.signum(lx);
                nly = 0;
                depth = px + c.radius;
            } else {
                nlx = 0;
                nly = Math.signum(ly) == 0 ? 1 : Math.signum(ly);
                depth = py + c.radius;
            }
        } else {
            final float ex = lx - qx, ey = ly - qy;
            final float d = (float) Math.hypot(ex, ey);
            depth = c.radius - d;
            if (depth <= 0) {
                return;
            }
            nlx = ex / d;
            nly = ey / d;
        }
        // n from the box to the circle, in the screen's axes
        final float nx = nlx * cos - nly * sin, ny = nlx * sin + nly * cos;
        final float wx = b.x + qx * cos - qy * sin, wy = b.y + qx * sin + qy * cos;
        contact(b, c, wx, wy, nx, ny, depth);
    }

    private final float[] axis = new float[2];

    /** Two boxes: the axis of least overlap, then the corners of each inside the other are the contact points. */
    private void boxBox(Body a, Body b) {
        float best = Float.MAX_VALUE, bnx = 0, bny = 0;
        for (int k = 0; k < 4; k++) {
            final Body owner = k < 2 ? a : b;
            final float an = owner.angle + (k % 2 == 0 ? 0 : (float) Math.PI / 2f);
            final float nx = (float) Math.cos(an), ny = (float) Math.sin(an);
            project(a, nx, ny);
            final float minA = axis[0], maxA = axis[1];
            project(b, nx, ny);
            final float minB = axis[0], maxB = axis[1];
            final float overlap = Math.min(maxA, maxB) - Math.max(minA, minB);
            if (overlap <= 0) {
                return;
            }
            if (overlap < best) {
                best = overlap;
                // from a to b
                final boolean flip = (b.x - a.x) * nx + (b.y - a.y) * ny < 0;
                bnx = flip ? -nx : nx;
                bny = flip ? -ny : ny;
            }
        }
        int found = 0;
        project(a, bnx, bny);
        final float maxA = axis[1];
        for (int k = 0; k < 4; k++) {
            final float px = b.corners[k * 2], py = b.corners[k * 2 + 1];
            if (a.contains(px, py, dp(1))) {
                final float d = maxA - (px * bnx + py * bny);
                if (d > 0) {
                    contact(a, b, px, py, bnx, bny, Math.min(d, best));
                    found++;
                }
            }
        }
        project(b, bnx, bny);
        final float minB = axis[0];
        for (int k = 0; k < 4; k++) {
            final float px = a.corners[k * 2], py = a.corners[k * 2 + 1];
            if (b.contains(px, py, dp(1))) {
                final float d = (px * bnx + py * bny) - minB;
                if (d > 0) {
                    contact(a, b, px, py, bnx, bny, Math.min(d, best));
                    found++;
                }
            }
        }
        if (found == 0) {
            // crossing edges, no corner inside: one contact between them
            contact(a, b, (a.x + b.x) / 2f, (a.y + b.y) / 2f, bnx, bny, best);
        }
    }

    private void project(Body b, float nx, float ny) {
        float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
        for (int k = 0; k < 4; k++) {
            final float p = b.corners[k * 2] * nx + b.corners[k * 2 + 1] * ny;
            min = Math.min(min, p);
            max = Math.max(max, p);
        }
        axis[0] = min;
        axis[1] = max;
    }

    private static float relativeNormalSpeed(Contact c) {
        final Body a = c.a, b = c.b;
        final float rax = c.px - a.x, ray = c.py - a.y;
        float vx = -(a.vx - a.va * ray), vy = -(a.vy + a.va * rax);
        if (b != null) {
            final float rbx = c.px - b.x, rby = c.py - b.y;
            vx += b.vx - b.va * rby;
            vy += b.vy + b.va * rbx;
        }
        return vx * c.nx + vy * c.ny;
    }

    private void solve(Contact c) {
        final Body a = c.a, b = c.b;
        final float rax = c.px - a.x, ray = c.py - a.y;
        final float rbx = b != null ? c.px - b.x : 0, rby = b != null ? c.py - b.y : 0;
        // the speed of b's point against a's point
        float vx = -(a.vx - a.va * ray), vy = -(a.vy + a.va * rax);
        if (b != null) {
            vx += b.vx - b.va * rby;
            vy += b.vy + b.va * rbx;
        }
        final float vn = vx * c.nx + vy * c.ny;
        final float raN = rax * c.ny - ray * c.nx, rbN = rbx * c.ny - rby * c.nx;
        final float kn = a.invMass + a.invInertia * raN * raN + (b != null ? b.invMass + b.invInertia * rbN * rbN : 0);
        if (kn <= 0) {
            return;
        }
        float jn = (-vn + c.bounce) / kn;
        final float oldPn = c.pn;
        c.pn = Math.max(0, oldPn + jn);
        jn = c.pn - oldPn;
        apply(c, jn * c.nx, jn * c.ny, rax, ray, rbx, rby);

        // friction along the surface
        final float tx = -c.ny, ty = c.nx;
        vx = -(a.vx - a.va * ray);
        vy = -(a.vy + a.va * rax);
        if (b != null) {
            vx += b.vx - b.va * rby;
            vy += b.vy + b.va * rbx;
        }
        final float vt = vx * tx + vy * ty;
        final float raT = rax * ty - ray * tx, rbT = rbx * ty - rby * tx;
        final float kt = a.invMass + a.invInertia * raT * raT + (b != null ? b.invMass + b.invInertia * rbT * rbT : 0);
        if (kt <= 0) {
            return;
        }
        float jt = -vt / kt;
        final float maxT = FRICTION * c.pn;
        final float oldPt = c.pt;
        c.pt = Math.max(-maxT, Math.min(maxT, oldPt + jt));
        jt = c.pt - oldPt;
        apply(c, jt * tx, jt * ty, rax, ray, rbx, rby);
    }

    /** The impulse (jx, jy) pushes b and pushes a back. */
    private static void apply(Contact c, float jx, float jy, float rax, float ray, float rbx, float rby) {
        final Body a = c.a, b = c.b;
        a.vx -= jx * a.invMass;
        a.vy -= jy * a.invMass;
        a.va -= (rax * jy - ray * jx) * a.invInertia;
        if (b != null) {
            b.vx += jx * b.invMass;
            b.vy += jy * b.invMass;
            b.va += (rbx * jy - rby * jx) * b.invInertia;
        }
    }

    private void correct(Contact c) {
        final float slop = dp(0.5f);
        final float depth = c.depth - slop;
        if (depth <= 0) {
            return;
        }
        final Body a = c.a, b = c.b;
        final float total = a.invMass + (b != null ? b.invMass : 0);
        if (total <= 0) {
            return;
        }
        final float move = depth * 0.45f / total;
        a.x -= c.nx * move * a.invMass;
        a.y -= c.ny * move * a.invMass;
        if (b != null) {
            b.x += c.nx * move * b.invMass;
            b.y += c.ny * move * b.invMass;
        }
    }

    /** The held body's point follows the finger like on a soft spring (so it swings round where it is held). */
    private void holdStep(float dt) {
        final Body b = held;
        final float cos = (float) Math.cos(b.angle), sin = (float) Math.sin(b.angle);
        final float rx = holdLocalX * cos - holdLocalY * sin, ry = holdLocalX * sin + holdLocalY * cos;
        final float px = b.x + rx, py = b.y + ry;
        // the point's speed now and the speed it should have
        final float vpx = b.vx - b.va * ry, vpy = b.vy + b.va * rx;
        final float wantX = (fingerX - px) * 18f, wantY = (fingerY - py) * 18f;
        final float dvx = (wantX - vpx) * Math.min(1f, dt * 20f), dvy = (wantY - vpy) * Math.min(1f, dt * 20f);
        // impulses along x and y at the point (each with its own effective mass)
        final float kx = b.invMass + b.invInertia * ry * ry, ky = b.invMass + b.invInertia * rx * rx;
        final float jx = dvx / kx, jy = dvy / ky;
        b.vx += jx * b.invMass;
        b.vy += jy * b.invMass;
        b.va += (rx * jy - ry * jx) * b.invInertia;
        b.va *= (float) Math.exp(-2f * dt);
    }

    // endregion

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (returning) {
            return true;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                for (int i = bodies.size() - 1; i >= 0; i--) {
                    final Body body = bodies.get(i);
                    if (body.contains(event.getX(), event.getY(), dp(6))) {
                        held = body;
                        final float dx = event.getX() - body.x, dy = event.getY() - body.y;
                        final float cos = (float) Math.cos(body.angle), sin = (float) Math.sin(body.angle);
                        holdLocalX = dx * cos + dy * sin;
                        holdLocalY = -dx * sin + dy * cos;
                        fingerX = downX = event.getX();
                        fingerY = downY = event.getY();
                        downTime = SystemClock.uptimeMillis();
                        dragging = false;
                        lastFrame = 0;
                        quietSince = 0;
                        invalidate();
                        return true;
                    }
                }
                return false;
            }
            case MotionEvent.ACTION_MOVE:
                if (held != null) {
                    fingerX = event.getX();
                    fingerY = event.getY();
                    if (!dragging && Math.hypot(fingerX - downX, fingerY - downY) > ViewConfiguration.get(getContext()).getScaledTouchSlop()) {
                        dragging = true;
                    }
                    invalidate();
                    return true;
                }
                return false;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (held != null) {
                    final Body body = held;
                    held = null;
                    if (!dragging && event.getActionMasked() == MotionEvent.ACTION_UP && SystemClock.uptimeMillis() - downTime < 300) {
                        // a tap: tossed against the gravity
                        final float len = Math.max(0.2f, (float) Math.hypot(gx, gy));
                        final float speed = dp(700 + random.nextInt(300));
                        body.vx = -gx / len * speed + dp(-120 + random.nextInt(240));
                        body.vy = -gy / len * speed;
                        body.va = (float) Math.toRadians(-540 + random.nextInt(1080));
                    }
                    // let go: it flies on with the speed the finger gave it
                    lastFrame = 0;
                    quietSince = 0;
                    invalidate();
                    return true;
                }
                return false;
        }
        return held != null;
    }
}
