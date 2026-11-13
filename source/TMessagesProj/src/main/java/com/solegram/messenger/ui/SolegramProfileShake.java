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
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.ProfileActionsView;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The profile's easter egg: shake the phone and every button of the profile's header falls to its floor (where the
 * account's info starts), tumbles, bounces and piles up. A tap on a fallen button tosses it up again; shake again
 * and they fly back to their places. Leaving the profile, or scrolling it, puts them back at once.
 */
@SuppressLint("ViewConstructor")
public class SolegramProfileShake extends View {

    private static final float GRAVITY = 2600f; // dp per second squared
    private static final float RESTITUTION = 0.32f;
    private static final long RETURN_MS = 520;

    private static final class Body {
        Bitmap bitmap;
        float w, h;
        // where it was, centre and angle
        float homeX, homeY;
        // now
        float x, y, angle, vx, vy, va;
        // flying back
        float fromX, fromY, fromAngle;
        // the original it stands for: a view (alpha 0 while fallen), or a button of the actions row
        View view;
        float viewAlpha;
    }

    private final ViewGroup root;
    private final ProfileActionsView actions;
    private final ArrayList<Body> bodies = new ArrayList<>();
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Random random = new Random();
    private final int[] loc = new int[2], rootLoc = new int[2];
    private float floorY;
    private float actionsWindowY;
    private long lastFrame;
    private long returnStart;
    private long quietSince;
    private boolean returning;

    private SolegramProfileShake(Context context, ViewGroup root, @Nullable ProfileActionsView actions) {
        super(context);
        this.root = root;
        this.actions = actions;
    }

    /** Shake: drops the buttons, or calls them back if they are down. Returns the overlay now in use (or null). */
    @Nullable
    public static SolegramProfileShake toggle(@Nullable SolegramProfileShake current, ViewGroup root, @Nullable ProfileActionsView actions, List<View> buttons) {
        if (current != null && current.getParent() != null) {
            current.callBack();
            return current;
        }
        if (root == null || root.getWidth() <= 0) {
            return null;
        }
        final SolegramProfileShake overlay = new SolegramProfileShake(root.getContext(), root, actions);
        if (!overlay.collect(buttons)) {
            return null;
        }
        root.addView(overlay, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        overlay.drop();
        return overlay;
    }

    private boolean collect(List<View> buttons) {
        root.getLocationInWindow(rootLoc);
        // the floor: the bottom of the actions row, or of the lowest button
        float floor = 0;
        if (actions != null && actions.getVisibility() == VISIBLE && actions.isShown()) {
            actions.getLocationInWindow(loc);
            actionsWindowY = loc[1];
            final int count = actions.sgVisibleCount();
            final RectF r = new RectF();
            for (int i = 0; i < count; i++) {
                final Bitmap b = actions.sgSnapshot(i, r);
                if (b == null) {
                    continue;
                }
                final Body body = new Body();
                body.bitmap = b;
                body.w = r.width();
                body.h = r.height();
                body.homeX = loc[0] - rootLoc[0] + r.centerX();
                body.homeY = loc[1] - rootLoc[1] + r.centerY();
                bodies.add(body);
                floor = Math.max(floor, loc[1] - rootLoc[1] + r.bottom + dp(6));
            }
        }
        for (View v : buttons) {
            if (v == null || v.getVisibility() != VISIBLE || !v.isShown() || v.getAlpha() < 0.05f || v.getWidth() <= 0 || v.getHeight() <= 0) {
                continue;
            }
            final Bitmap b;
            try {
                b = Bitmap.createBitmap(v.getWidth(), v.getHeight(), Bitmap.Config.ARGB_8888);
                v.draw(new Canvas(b));
            } catch (Throwable e) {
                continue;
            }
            v.getLocationInWindow(loc);
            final Body body = new Body();
            body.bitmap = b;
            body.w = v.getWidth() * v.getScaleX();
            body.h = v.getHeight() * v.getScaleY();
            body.homeX = loc[0] - rootLoc[0] + body.w / 2f;
            body.homeY = loc[1] - rootLoc[1] + body.h / 2f;
            body.view = v;
            body.viewAlpha = v.getAlpha();
            bodies.add(body);
        }
        if (bodies.isEmpty()) {
            return false;
        }
        floorY = floor > 0 ? floor : root.getHeight() * 0.35f;
        return true;
    }

    private void drop() {
        for (Body b : bodies) {
            b.x = b.homeX;
            b.y = b.homeY;
            b.angle = 0;
            b.vx = dp(-90 + random.nextInt(180));
            b.vy = -dp(60 + random.nextInt(160));
            b.va = -220 + random.nextInt(440);
            if (b.view != null) {
                b.view.setAlpha(0f);
            }
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

    /** Back to their places, flying. */
    public void callBack() {
        if (returning) {
            return;
        }
        returning = true;
        returnStart = SystemClock.uptimeMillis();
        for (Body b : bodies) {
            b.fromX = b.x;
            b.fromY = b.y;
            // the shortest turn back to upright
            float a = b.angle % 360f;
            if (a > 180f) {
                a -= 360f;
            } else if (a < -180f) {
                a += 360f;
            }
            b.fromAngle = a;
        }
        invalidate();
    }

    /** At once, nothing animated (leaving the profile, scrolling it). */
    public void restoreNow() {
        for (Body b : bodies) {
            if (b.view != null) {
                b.view.setAlpha(b.viewAlpha);
            }
            if (b.bitmap != null) {
                b.bitmap.recycle();
                b.bitmap = null;
            }
        }
        bodies.clear();
        if (actions != null) {
            actions.sgSetFallen(false);
        }
        if (getParent() instanceof ViewGroup) {
            ((ViewGroup) getParent()).removeView(this);
        }
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        // the profile scrolled (the header moves): everything back now
        if (actions != null && actionsWindowY != 0 && !returning) {
            actions.getLocationInWindow(loc);
            if (Math.abs(loc[1] - actionsWindowY) > dp(3)) {
                post(this::restoreNow);
                return;
            }
        }
        final long now = SystemClock.uptimeMillis();
        if (returning) {
            final float t = Math.min(1f, (now - returnStart) / (float) RETURN_MS);
            final float e = CubicBezierInterpolator.EASE_OUT_BACK.getInterpolation(t);
            for (Body b : bodies) {
                b.x = b.fromX + (b.homeX - b.fromX) * e;
                b.y = b.fromY + (b.homeY - b.fromY) * e;
                b.angle = b.fromAngle * (1f - e);
                drawBody(canvas, b);
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
        final boolean moving = step(dt);
        for (Body b : bodies) {
            // keep the originals hidden: the profile may have shown them again meanwhile
            if (b.view != null && b.view.getAlpha() != 0f) {
                b.view.setAlpha(0f);
            }
            drawBody(canvas, b);
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

    private void drawBody(Canvas canvas, Body b) {
        if (b.bitmap == null) {
            return;
        }
        canvas.save();
        canvas.translate(b.x, b.y);
        canvas.rotate(b.angle);
        canvas.drawBitmap(b.bitmap, -b.bitmap.getWidth() / 2f, -b.bitmap.getHeight() / 2f, paint);
        canvas.restore();
    }

    /** One step of the world; returns whether anything still moves. */
    private boolean step(float dt) {
        final float g = dp(GRAVITY);
        final float width = getWidth();
        boolean moving = false;
        for (Body b : bodies) {
            b.vy += g * dt;
            b.x += b.vx * dt;
            b.y += b.vy * dt;
            b.angle += b.va * dt;
            // the lowest point of the turned box
            final double rad = Math.toRadians(b.angle);
            final float halfH = (float) (Math.abs(b.w / 2f * Math.sin(rad)) + Math.abs(b.h / 2f * Math.cos(rad)));
            final float halfW = (float) (Math.abs(b.w / 2f * Math.cos(rad)) + Math.abs(b.h / 2f * Math.sin(rad)));
            if (b.y + halfH > floorY) {
                b.y = floorY - halfH;
                if (b.vy > 0) {
                    b.vy = -b.vy * RESTITUTION;
                    if (Math.abs(b.vy) < dp(40)) {
                        b.vy = 0;
                    }
                }
                b.vx *= 0.86f;
                // settle on a side: turn towards the nearest flat angle
                final float flat = Math.round(b.angle / 90f) * 90f;
                b.va = b.va * 0.8f + (flat - b.angle) * 9f;
            }
            if (b.x - halfW < 0) {
                b.x = halfW;
                b.vx = Math.abs(b.vx) * 0.5f;
            } else if (b.x + halfW > width) {
                b.x = width - halfW;
                b.vx = -Math.abs(b.vx) * 0.5f;
            }
            if (Math.abs(b.vx) > dp(4) || Math.abs(b.vy) > dp(4) || Math.abs(b.va) > 4) {
                moving = true;
            }
        }
        // a simple pile: the buttons push each other apart as circles
        for (int i = 0; i < bodies.size(); i++) {
            final Body a = bodies.get(i);
            final float ra = Math.min(a.w, a.h) * 0.48f;
            for (int j = i + 1; j < bodies.size(); j++) {
                final Body c = bodies.get(j);
                final float rc = Math.min(c.w, c.h) * 0.48f;
                final float dx = c.x - a.x, dy = c.y - a.y;
                final float dist = (float) Math.sqrt(dx * dx + dy * dy);
                final float min = ra + rc;
                if (dist > 0.01f && dist < min) {
                    final float push = (min - dist) / 2f, nx = dx / dist, ny = dy / dist;
                    a.x -= nx * push;
                    a.y -= ny * push;
                    c.x += nx * push;
                    c.y += ny * push;
                    final float rel = (c.vx - a.vx) * nx + (c.vy - a.vy) * ny;
                    if (rel < 0) {
                        final float imp = -rel * 0.6f;
                        a.vx -= nx * imp;
                        a.vy -= ny * imp;
                        c.vx += nx * imp;
                        c.vy += ny * imp;
                    }
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
            // a fallen button: toss it up; anywhere else: the touch goes on to the profile
            for (int i = bodies.size() - 1; i >= 0; i--) {
                final Body b = bodies.get(i);
                final float r = Math.max(b.w, b.h) / 2f;
                if (Math.abs(event.getX() - b.x) < r && Math.abs(event.getY() - b.y) < r) {
                    b.vy = -dp(700 + random.nextInt(300));
                    b.vx += dp(-120 + random.nextInt(240));
                    b.va = -540 + random.nextInt(1080);
                    lastFrame = 0;
                    quietSince = 0;
                    invalidate();
                    return true;
                }
            }
            return false;
        }
        return false;
    }
}
