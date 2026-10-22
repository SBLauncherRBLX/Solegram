/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.ui;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Build;
import android.view.View;

import androidx.annotation.Nullable;

import com.solegram.messenger.SolegramConfig;

import org.telegram.messenger.AndroidUtilities;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * Liquid glass: new things flow out of the thing that makes them, like drops of liquid, and flow back into it.
 * <p>
 * Every liquid shape is a {@link Blob}: a rounded rect whose centre, size and corner radius each sit on their
 * own {@link Spring}. The blobs of a {@link Group} melt together through a smooth minimum of their distance
 * fields, so a drop leaving its parent stretches a neck that thins and pinches off by itself, and a drop
 * coming back merges through the same neck. Each frame the outline is traced on a grid (marching squares,
 * refined only near the edge) into one {@link Path}, which is drawn as frosted glass with a light rim.
 * <p>
 * Pure drawing helpers: the host view advances the group in onDraw ({@link Group#step}) and draws it
 * ({@link Group#draw}); {@link SolegramLiquidLayout} does both for views placed in the drops.
 */
public final class SolegramLiquid {

    private SolegramLiquid() {
    }

    /** Liquid animations are on (Solegram settings → Glass). */
    public static boolean enabled() {
        // turned off at the user's request (1.6.1): menus and buttons appear as in Telegram again
        return false;
    }

    // region Spring

    /**
     * A damped spring set up like SwiftUI's: response (seconds to settle, roughly) and damping fraction
     * (1 = no overshoot). Retargeting keeps the velocity, so any motion can be caught and turned around.
     */
    public static final class Spring {
        public float value, velocity, target;
        private float stiffness, damping;
        private boolean active;
        private float restDelta = 0.05f;

        public Spring(float value) {
            this.value = this.target = value;
            params(0.42f, 0.82f);
        }

        public Spring params(float response, float dampingFraction) {
            final float omega = (float) (2 * Math.PI / Math.max(0.05f, response));
            stiffness = omega * omega;
            damping = 2 * dampingFraction * omega;
            return this;
        }

        /** The smallest change that still counts (in the spring's own units). */
        public Spring rest(float delta) {
            restDelta = delta;
            return this;
        }

        public void to(float target) {
            if (this.target == target && !active && value == target) {
                return;
            }
            this.target = target;
            active = true;
        }

        public void set(float value) {
            this.value = this.target = value;
            velocity = 0;
            active = false;
        }

        public void kick(float velocity) {
            this.velocity += velocity;
            active = true;
        }

        public boolean isActive() {
            return active;
        }

        /** Advances by dt seconds (in fixed sub-steps, stable at any frame rate); false once at rest. */
        public boolean step(float dt) {
            if (!active) {
                return false;
            }
            float left = dt;
            while (left > 1e-6f) {
                final float h = Math.min(left, 1f / 240f);
                final float a = -stiffness * (value - target) - damping * velocity;
                velocity += a * h;
                value += velocity * h;
                left -= h;
            }
            if (Math.abs(value - target) < restDelta && Math.abs(velocity) < restDelta * 20) {
                value = target;
                velocity = 0;
                active = false;
            }
            return active;
        }
    }
    // endregion

    // region Blob

    public static final int STATE_HIDDEN = 0, STATE_OUT = 1, STATE_IN = 2, STATE_SHOWN = 3;

    /** One drop: a rounded rect on springs. Coordinates are the host view's, in pixels. */
    public static final class Blob {
        public final Spring x = new Spring(0), y = new Spring(0), w = new Spring(0), h = new Spring(0), r = new Spring(0);
        /** Where it rests when out. */
        public float finX, finY, finW, finH, finR;
        /** Melts into the others (for necks) but is not glass itself: a message bubble the menus flow out of. */
        public boolean cut;
        /** Content placed in the drop: shown as the drop separates, hidden as it merges back. */
        @Nullable
        public View view;
        /** 0..1: how far the drop has come out (for its content). */
        public float reveal;
        public Object tag;

        int state = STATE_HIDDEN;
        @Nullable
        Blob home;
        float delay;
        /** Waits (delay) to be born inside its parent: drawn only from then on. */
        boolean pendingBorn;
        /**
         * 0..1: how liquid the drop is now. Only moving drops melt into others; once a drop settles this fades to 0
         * and it is drawn as its exact shape, so nothing at rest is left bent toward a neighbour.
         */
        float liquid;
        // merging back: size held while travelling, shrinking only near the parent
        float holdD0, holdW, holdH, holdR;
        @Nullable
        Runnable onIn;

        public Blob() {
            for (Spring s : springs()) {
                s.rest(0.08f);
            }
        }

        private final Spring[] all = {x, y, w, h, r};

        Spring[] springs() {
            return all;
        }

        /** Sets where the drop rests when out (it moves there if it is out now). */
        public Blob rest(float cx, float cy, float width, float height, float radius) {
            finX = cx;
            finY = cy;
            finW = width;
            finH = height;
            finR = Math.min(radius, Math.min(width, height) / 2);
            if (state == STATE_OUT || state == STATE_SHOWN) {
                x.to(cx);
                y.to(cy);
                w.to(width);
                h.to(height);
                r.to(finR);
            }
            return this;
        }

        /** Shown at once where it rests (a source: the button others flow out of). */
        public Blob show() {
            x.set(finX);
            y.set(finY);
            w.set(finW);
            h.set(finH);
            r.set(finR);
            state = STATE_SHOWN;
            reveal = 1;
            return this;
        }

        /** Follows a moving source (a button that animates on its own): jumps there. */
        public void place(float cx, float cy, float width, float height, float radius) {
            finX = cx;
            finY = cy;
            finW = width;
            finH = height;
            finR = Math.min(radius, Math.min(width, height) / 2);
            x.set(cx);
            y.set(cy);
            w.set(width);
            h.set(height);
            r.set(finR);
        }

        public boolean isVisible() {
            return state != STATE_HIDDEN;
        }

        public boolean isOut() {
            return state == STATE_OUT || state == STATE_SHOWN;
        }

        boolean active() {
            return moving() || liquid > 0;
        }

        boolean moving() {
            return delay > 0 || pendingBorn || x.isActive() || y.isActive() || w.isActive() || h.isActive() || r.isActive() || state == STATE_IN;
        }

        /** This drop's own spring feel (the group's otherwise); size follows position a little slower. */
        public Blob springs(float response, float damping) {
            params(response, damping);
            return this;
        }

        void params(float response, float damping) {
            x.params(response, damping);
            y.params(response, damping);
            // size a little slower than position: the drop leaves first and swells after (parallel motion)
            w.params(response * 1.16f, damping);
            h.params(response * 1.16f, damping);
            r.params(response * 1.16f, damping);
        }

        /** The corner radius drawn: a drop is born round and takes its own corners as it reaches full size. */
        float drawnRadius(float width, float height) {
            final float m = Math.min(width, height) / 2;
            float g = 1;
            if (finW > 0 && finH > 0) {
                g = Math.max(0, Math.min(1, Math.min(width / finW, height / finH)));
                g *= g;
            }
            return Math.max(0, Math.min(m, m + (r.value - m) * g));
        }
    }
    // endregion

    // region Group

    /** Glass that fills the outline: blurred background, or a plain colour where there is none. */
    public interface GlassFill {
        void fill(Canvas canvas, Path path, RectF bounds);
    }

    /** Drops that melt into one another: one outline, one piece of glass. */
    public static final class Group {

        public final ArrayList<Blob> blobs = new ArrayList<>();
        /** How far drops reach for each other (px): the neck pinches off at a gap of about k / 2. */
        public float k = AndroidUtilities.dp(20);
        public final Path path = new Path();
        public final RectF bounds = new RectF();

        private float response = 0.44f, damping = 0.8f;
        private long lastFrame;
        private boolean pathValid;
        private final ArrayList<Chain> chains = new ArrayList<>();

        // drawing
        private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint tintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint lowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int tint = 0x8CFFFFFF, rimTop = 0xE6FFFFFF, rimBottom = 0x14FFFFFF, low = 0x1F000000, shadow = 0x29000000;
        private float rimShaderTop = Float.NaN;
        private LinearGradient rimShader, lowShader;
        private final android.graphics.Matrix shaderMatrix = new android.graphics.Matrix();
        private float shadowRadius = AndroidUtilities.dp(16), shadowDy = AndroidUtilities.dp(5);

        public Group() {
            shadowPaint.setStyle(Paint.Style.FILL);
            tintPaint.setStyle(Paint.Style.FILL);
            rimPaint.setStyle(Paint.Style.STROKE);
            rimPaint.setStrokeWidth(Math.max(1, AndroidUtilities.dpf2(1.1f)));
            lowPaint.setStyle(Paint.Style.STROKE);
            lowPaint.setStrokeWidth(AndroidUtilities.dpf2(1.6f));
        }

        public Group springs(float response, float damping) {
            this.response = response;
            this.damping = damping;
            for (Blob b : blobs) {
                b.params(response, damping);
            }
            return this;
        }

        /** Light glass (white tint) or dark glass; colours of the tint, rim and shadow. */
        public Group colors(int tint, int rimTop, int rimBottom, int lowEdge, int shadow) {
            this.tint = tint;
            this.rimTop = rimTop;
            this.rimBottom = rimBottom;
            this.low = lowEdge;
            this.shadow = shadow;
            rimShaderTop = Float.NaN;
            return this;
        }

        public Blob add() {
            final Blob b = new Blob();
            b.params(response, damping);
            blobs.add(b);
            pathValid = false;
            return b;
        }

        /** Adds an existing drop (taken out earlier) back to the group. */
        public Blob add(Blob b) {
            if (!blobs.contains(b)) {
                b.params(response, damping);
                blobs.add(b);
            }
            pathValid = false;
            return b;
        }

        public void remove(Blob b) {
            blobs.remove(b);
            pathValid = false;
        }

        /** The outline is rebuilt on the next step (after moving drops with {@link Blob#place}). */
        public void invalidate() {
            pathValid = false;
        }

        /** Size and drop of the shadow (px); a popup window leaves little room around the panel for it. */
        public Group shadow(float radius, float dy) {
            shadowRadius = radius;
            shadowDy = dy;
            return this;
        }

        public void clear() {
            blobs.clear();
            chains.clear();
            path.rewind();
            bounds.setEmpty();
            pathValid = false;
        }

        // region choreography

        /**
         * The drop flows out of {@code from}: it is born small and round inside the parent, at the edge facing
         * where it goes, then its centre springs out and its size follows a beat later.
         */
        public void out(Blob b, @Nullable Blob from, float delaySeconds) {
            b.onIn = null;
            b.home = from;
            final boolean wasHidden = b.state == STATE_HIDDEN || b.state == STATE_IN && b.w.value < 0.5f;
            b.state = STATE_OUT;
            b.delay = Math.max(0, delaySeconds);
            if (b.delay == 0) {
                if (wasHidden) {
                    bornIn(b, from);
                }
                b.pendingBorn = false;
                launch(b);
            } else {
                // born when its turn comes, where the parent is by then (a chain: the parent is still moving)
                b.pendingBorn = wasHidden;
            }
            pathValid = false;
        }

        private void bornIn(Blob b, @Nullable Blob from) {
            if (from == null || !from.isVisible()) {
                // no parent: grows out of its own centre
                b.x.set(b.finX);
                b.y.set(b.finY);
                final float s = Math.min(b.finW, b.finH) * 0.3f;
                b.w.set(s);
                b.h.set(s);
                b.r.set(s / 2);
                return;
            }
            // the point of the parent nearest to the destination, kept a little inside it
            final float hw = Math.max(0, from.w.value / 2 - from.r.value), hh = Math.max(0, from.h.value / 2 - from.r.value);
            final float px = clamp(b.finX, from.x.value - hw, from.x.value + hw);
            final float py = clamp(b.finY, from.y.value - hh, from.y.value + hh);
            final float s = Math.max(AndroidUtilities.dp(6), Math.min(Math.min(b.finW, b.finH), Math.min(from.w.value, from.h.value)) * 0.55f);
            b.x.set(px);
            b.y.set(py);
            b.w.set(s);
            b.h.set(s);
            b.r.set(s / 2);
        }

        private void launch(Blob b) {
            b.liquid = 1;
            b.x.to(b.finX);
            b.y.to(b.finY);
            b.w.to(b.finW);
            b.h.to(b.finH);
            b.r.to(b.finR);
        }

        /**
         * The drop flows back into {@code into} (or its parent): it travels back at full size, merges through
         * a neck and shrinks inside the parent. Interruptible: {@link #out} turns it around.
         */
        public void in(Blob b, @Nullable Blob into, float delaySeconds, @Nullable Runnable done) {
            if (b.state == STATE_HIDDEN) {
                if (done != null) {
                    done.run();
                }
                return;
            }
            b.home = into != null ? into : b.home;
            b.state = STATE_IN;
            b.onIn = done;
            b.delay = Math.max(0, delaySeconds);
            b.holdD0 = -1;
            pathValid = false;
        }

        /**
         * A chain flows out: the first drop out of {@code origin}, every next one out of the one before,
         * {@code stagger} seconds apart.
         */
        public void chainOut(@Nullable Blob origin, float stagger, Blob... chain) {
            cancelChains(chain);
            Blob from = origin;
            for (int i = 0; i < chain.length; i++) {
                out(chain[i], from, i * stagger);
                from = chain[i];
            }
        }

        /**
         * The chain flows back in the reverse order: the last drop into the one before it, that one into its own
         * before, and so on; only then the first drop flows into {@code origin}. Each starts as the one after it
         * is mostly merged, so it reads as one motion.
         */
        public void chainIn(@Nullable Blob origin, @Nullable Runnable done, Blob... chain) {
            cancelChains(chain);
            final Chain c = new Chain(origin, chain, done);
            chains.add(c);
            c.next(this);
            pathValid = false;
        }

        private void cancelChains(Blob[] blobsOfChain) {
            for (int i = chains.size() - 1; i >= 0; i--) {
                final Chain c = chains.get(i);
                for (Blob b : blobsOfChain) {
                    if (c.contains(b)) {
                        chains.remove(i);
                        break;
                    }
                }
            }
        }

        private static final class Chain {
            final Blob origin;
            final Blob[] blobs;
            final Runnable done;
            int index;

            Chain(Blob origin, Blob[] blobs, Runnable done) {
                this.origin = origin;
                this.blobs = blobs;
                this.done = done;
                index = blobs.length - 1;
                // skip the tail that is not out
                while (index >= 0 && blobs[index].state == STATE_HIDDEN) {
                    index--;
                }
            }

            boolean contains(Blob b) {
                for (Blob x : blobs) {
                    if (x == b) {
                        return true;
                    }
                }
                return false;
            }

            /** Starts the current drop flowing into the one before it. */
            void next(Group g) {
                if (index < 0) {
                    return;
                }
                final Blob b = blobs[index];
                final Blob into = index > 0 ? blobs[index - 1] : origin;
                g.in(b, into, 0, null);
            }

            /** The next drop starts when the current one is mostly merged; true when the chain is done. */
            boolean update(Group g) {
                if (index < 0) {
                    return true;
                }
                final Blob b = blobs[index];
                final boolean merged = b.state == STATE_HIDDEN || b.holdD0 > 0 && distanceToHome(b) < b.holdD0 * 0.38f;
                if (merged) {
                    index--;
                    next(g);
                }
                if (index < 0) {
                    // the last one (the first drop) still flowing into the origin: done when it is gone
                    return false;
                }
                return false;
            }

            boolean finished() {
                for (Blob b : blobs) {
                    if (b.state == STATE_IN) {
                        return false;
                    }
                }
                return index < 0;
            }
        }

        static float distanceToHome(Blob b) {
            if (b.home == null) {
                return 0;
            }
            return (float) Math.hypot(b.x.value - homeX(b), b.y.value - homeY(b));
        }

        /** Where a drop flows back to: the nearest point of its parent's core (a wide capsule: the near end, not the middle). */
        static float homeX(Blob b) {
            final Blob h = b.home;
            final float hw = Math.max(0, h.w.value / 2 - h.r.value);
            return clamp(b.x.value, h.x.value - hw, h.x.value + hw);
        }

        static float homeY(Blob b) {
            final Blob h = b.home;
            final float hh = Math.max(0, h.h.value / 2 - h.r.value);
            return clamp(b.y.value, h.y.value - hh, h.y.value + hh);
        }

        /** Jumps every drop to where it is going (and finishes chains): for layout changes, leaving the screen. */
        public void finish() {
            for (int i = 0; i < blobs.size(); i++) {
                final Blob b = blobs.get(i);
                if (b.state == STATE_OUT && b.pendingBorn) {
                    b.pendingBorn = false;
                }
                b.delay = 0;
                if (b.state == STATE_IN) {
                    b.state = STATE_HIDDEN;
                    for (Spring sp : b.springs()) {
                        sp.set(0);
                    }
                    final Runnable r = b.onIn;
                    b.onIn = null;
                    if (r != null) {
                        r.run();
                    }
                } else if (b.state == STATE_OUT) {
                    b.x.set(b.finX);
                    b.y.set(b.finY);
                    b.w.set(b.finW);
                    b.h.set(b.finH);
                    b.r.set(b.finR);
                } else {
                    for (Spring sp : b.springs()) {
                        sp.set(sp.target);
                    }
                }
                b.liquid = 0;
                updateReveal(b);
            }
            final ArrayList<Chain> ended = new ArrayList<>(chains);
            chains.clear();
            for (Chain c : ended) {
                if (c.done != null) {
                    c.done.run();
                }
            }
            lastFrame = 0;
            buildPath();
            pathValid = true;
        }
        // endregion

        /** Something still moves (or waits to). */
        public boolean isAnimating() {
            if (!chains.isEmpty() || !pathValid) {
                return true;
            }
            for (int i = 0; i < blobs.size(); i++) {
                if (blobs.get(i).active()) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Advances the springs to the frame time (System.nanoTime / Choreographer) and rebuilds the outline.
         * True while anything still moves: the host keeps invalidating.
         */
        public boolean step(long frameTimeNanos) {
            float dt = lastFrame == 0 ? 1f / 60f : (frameTimeNanos - lastFrame) / 1e9f;
            lastFrame = frameTimeNanos;
            if (dt <= 0) {
                dt = 1f / 120f;
            }
            dt = Math.min(dt, 1f / 30f);
            boolean moving = false;
            for (int i = 0; i < blobs.size(); i++) {
                final Blob b = blobs.get(i);
                if (b.delay > 0) {
                    b.delay -= dt;
                    if (b.delay <= 0) {
                        b.delay = 0;
                        if (b.state == STATE_OUT) {
                            if (b.pendingBorn) {
                                bornIn(b, b.home);
                                b.pendingBorn = false;
                            }
                            launch(b);
                        }
                    }
                    moving = true;
                    updateReveal(b);
                    continue;
                }
                if (b.state == STATE_IN) {
                    stepIn(b);
                }
                for (Spring s : b.springs()) {
                    if (s.step(dt)) {
                        moving = true;
                    }
                }
                if (b.moving()) {
                    b.liquid = Math.min(1, b.liquid + dt / 0.05f);
                } else if (b.liquid > 0) {
                    // settled: the melt fades out, the drop takes its exact shape
                    b.liquid = Math.max(0, b.liquid - dt / 0.22f);
                    moving = true;
                }
                if (b.state == STATE_IN) {
                    moving = true;
                    if (b.w.value < AndroidUtilities.dp(2) && b.h.value < AndroidUtilities.dp(2)
                            || b.home == null && !b.w.isActive()) {
                        b.state = STATE_HIDDEN;
                        b.w.set(0);
                        b.h.set(0);
                        final Runnable r = b.onIn;
                        b.onIn = null;
                        if (r != null) {
                            r.run();
                        }
                    }
                }
                updateReveal(b);
            }
            Runnable chainDone = null;
            for (int i = chains.size() - 1; i >= 0; i--) {
                final Chain c = chains.get(i);
                c.update(this);
                if (c.finished()) {
                    chains.remove(i);
                    if (c.done != null) {
                        // run after the loop: a callback may start or clear chains
                        final Runnable prev = chainDone, d = c.done;
                        chainDone = prev == null ? d : () -> { prev.run(); d.run(); };
                    }
                } else {
                    moving = true;
                }
            }
            if (moving || !pathValid) {
                buildPath();
                pathValid = true;
            }
            if (!moving) {
                lastFrame = 0;
            }
            if (chainDone != null) {
                chainDone.run();
            }
            return moving;
        }

        /** Flowing back: aims at the parent's centre, keeps its size until close, then shrinks into it. */
        private void stepIn(Blob b) {
            final Blob home = b.home;
            if (home == null || !home.isVisible()) {
                b.x.to(b.x.value);
                b.y.to(b.y.value);
                b.w.to(0);
                b.h.to(0);
                b.r.to(0);
                return;
            }
            final float d = distanceToHome(b);
            if (b.holdD0 < 0) {
                b.holdD0 = Math.max(1, d);
                b.holdW = b.w.value;
                b.holdH = b.h.value;
                b.holdR = b.r.value;
            }
            b.x.to(homeX(b));
            b.y.to(homeY(b));
            // full size far away, melting into the parent's size as it arrives, gone once inside
            final float f = clamp(d / b.holdD0, 0, 1);
            final float inner = Math.min(home.w.value, home.h.value) * 0.35f;
            final float tw = lerp(Math.min(inner, b.holdW), b.holdW, smooth(f));
            final float th = lerp(Math.min(inner, b.holdH), b.holdH, smooth(f));
            if (d < Math.max(AndroidUtilities.dp(3), Math.min(home.w.value, home.h.value) * 0.18f)) {
                b.w.to(0);
                b.h.to(0);
                b.r.to(0);
            } else {
                b.w.to(tw);
                b.h.to(th);
                b.r.to(Math.min(b.holdR, Math.min(tw, th) / 2));
            }
        }

        private void updateReveal(Blob b) {
            float rv;
            if (b.state == STATE_HIDDEN || b.pendingBorn) {
                rv = 0;
            } else if (b.state == STATE_SHOWN) {
                rv = 1;
            } else {
                final float g = b.finW > 0 && b.finH > 0 ? Math.min(b.w.value / b.finW, b.h.value / b.finH) : 1;
                rv = smoothstep(0.62f, 0.96f, g);
                if (b.state == STATE_IN) {
                    rv = Math.min(rv, smoothstep(0.35f, 0.8f, b.holdD0 > 0 ? distanceToHome(b) / b.holdD0 : 1));
                }
            }
            b.reveal = rv;
            final View v = b.view;
            if (v != null) {
                v.setAlpha(rv);
                final float s = 0.86f + 0.14f * rv;
                v.setScaleX(s);
                v.setScaleY(s);
                v.setVisibility(rv > 0.01f ? View.VISIBLE : View.INVISIBLE);
            }
        }

        // region outline

        /** Per drop: centre x, y, inner half width, half height, radius, cut, liquid. */
        private static final int P = 7;
        private float[] params = new float[P * 8];
        private float[] coarse = new float[0], fine = new float[0];
        private byte[] near = new byte[0];
        private int[] segs = new int[0];
        private int segCount;

        /** Signed distance of the union at (px, py): negative inside. */
        private float field(float px, float py, int n) {
            final float[] p = comp;
            float d = Float.MAX_VALUE;
            float cut = Float.MAX_VALUE;
            boolean hasCut = false;
            // settled drops are a plain union; moving ones melt into it, as liquid as they are now
            for (int pass = 0; pass < 2; pass++) {
                for (int i = 0; i < n; i++) {
                    final int o = i * P;
                    final float liq = p[o + 6];
                    if (pass == 0 ? liq > 0 : liq <= 0) {
                        continue;
                    }
                    final float qx = Math.abs(px - p[o]) - p[o + 2];
                    final float qy = Math.abs(py - p[o + 1]) - p[o + 3];
                    final float ox = Math.max(qx, 0), oy = Math.max(qy, 0);
                    final float s = (float) Math.sqrt(ox * ox + oy * oy) + Math.min(Math.max(qx, qy), 0) - p[o + 4];
                    d = d == Float.MAX_VALUE ? s : pass == 0 ? Math.min(d, s) : smin(d, s, k * liq);
                    if (p[o + 5] > 0) {
                        hasCut = true;
                        cut = Math.min(cut, s);
                    }
                }
            }
            // drops melt into a bubble (for the necks) but the bubble itself is not glass
            return hasCut ? Math.max(d, -cut + AndroidUtilities.dpf2(0.5f)) : d;
        }

        private void buildPath() {
            path.rewind();
            int n = 0;
            float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
            boolean anyGlass = false;
            for (int i = 0; i < blobs.size(); i++) {
                final Blob b = blobs.get(i);
                final float w = b.w.value, h = b.h.value;
                if (b.state == STATE_HIDDEN || b.pendingBorn || w < 0.6f || h < 0.6f) {
                    continue;
                }
                if (params.length < (n + 1) * P) {
                    params = Arrays.copyOf(params, (n + 4) * P);
                }
                final float r = b.drawnRadius(w, h);
                final int o = n * P;
                params[o] = b.x.value;
                params[o + 1] = b.y.value;
                params[o + 2] = w / 2 - r;
                params[o + 3] = h / 2 - r;
                params[o + 4] = r;
                params[o + 5] = b.cut ? 1 : 0;
                params[o + 6] = b.cut ? 0 : b.liquid;
                n++;
                if (!b.cut) {
                    anyGlass = true;
                }
                minX = Math.min(minX, b.x.value - w / 2);
                minY = Math.min(minY, b.y.value - h / 2);
                maxX = Math.max(maxX, b.x.value + w / 2);
                maxY = Math.max(maxY, b.y.value + h / 2);
            }
            if (n == 0 || !anyGlass) {
                bounds.setEmpty();
                return;
            }
            final float pad = k * 0.5f + AndroidUtilities.dp(4);
            minX -= pad;
            minY -= pad;
            maxX += pad;
            maxY += pad;
            bounds.set(minX, minY, maxX, maxY);
            // drops too far apart to melt (box gap over 1.5 k: the blend cannot reach the outline) are drawn
            // apart: a lone drop exactly, and only the ones melting together are traced, over their own area
            if (parent.length < n) {
                parent = new int[n];
            }
            for (int i = 0; i < n; i++) {
                parent[i] = i;
            }
            final float reach = k * 1.5f;
            for (int i = 0; i < n; i++) {
                for (int j = i + 1; j < n; j++) {
                    // overlapping drops are one outline; others melt only while one of them moves
                    final float gap = boxGap(i, j);
                    if (gap < 0 || gap < reach * Math.max(params[i * P + 6], params[j * P + 6])) {
                        union(i, j);
                    }
                }
            }
            for (int root = 0; root < n; root++) {
                if (find(root) != root) {
                    continue;
                }
                int m = 0;
                boolean glass = false, cutIn = false;
                float x0 = Float.MAX_VALUE, y0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE, y1 = -Float.MAX_VALUE;
                for (int i = 0; i < n; i++) {
                    if (find(i) != root) {
                        continue;
                    }
                    if (comp.length < (m + 1) * P) {
                        comp = Arrays.copyOf(comp, (m + 4) * P);
                    }
                    System.arraycopy(params, i * P, comp, m * P, P);
                    m++;
                    final int o = i * P;
                    if (params[o + 5] > 0) {
                        cutIn = true;
                    } else {
                        glass = true;
                    }
                    final float hw = params[o + 2] + params[o + 4], hh = params[o + 3] + params[o + 4];
                    x0 = Math.min(x0, params[o] - hw);
                    y0 = Math.min(y0, params[o + 1] - hh);
                    x1 = Math.max(x1, params[o] + hw);
                    y1 = Math.max(y1, params[o + 1] + hh);
                }
                if (!glass) {
                    continue;
                }
                if (m == 1 && !cutIn) {
                    // a single drop: exact rounded rect, no tracing
                    final float r = comp[4];
                    path.addRoundRect(comp[0] - comp[2] - r, comp[1] - comp[3] - r,
                            comp[0] + comp[2] + r, comp[1] + comp[3] + r, r, r, Path.Direction.CW);
                    continue;
                }
                final float p2 = k * 0.5f + AndroidUtilities.dp(4);
                trace(x0 - p2, y0 - p2, x1 + p2, y1 + p2, m);
            }
        }

        private float[] comp = new float[P * 8];
        private int[] parent = new int[8];

        private int find(int i) {
            while (parent[i] != i) {
                parent[i] = parent[parent[i]];
                i = parent[i];
            }
            return i;
        }

        private void union(int a, int b) {
            final int ra = find(a), rb = find(b);
            if (ra != rb) {
                parent[Math.max(ra, rb)] = Math.min(ra, rb);
            }
        }

        /** Gap between the boxes of two drops (a lower bound of the real gap). */
        private float boxGap(int i, int j) {
            final int a = i * P, b = j * P;
            final float dx = Math.abs(params[a] - params[b]) - (params[a + 2] + params[a + 4] + params[b + 2] + params[b + 4]);
            final float dy = Math.abs(params[a + 1] - params[b + 1]) - (params[a + 3] + params[a + 4] + params[b + 3] + params[b + 4]);
            return Math.max(dx, dy);
        }

        /**
         * Marching squares on a fine grid, evaluated only in coarse blocks the edge can pass through: the field
         * changes by at most 1 px per px, so a block whose corners are all far from zero has no edge inside.
         */
        private void trace(float minX, float minY, float maxX, float maxY, int n) {
            final float area = (maxX - minX) * (maxY - minY);
            final float cell = Math.max(AndroidUtilities.dpf2(1.6f), (float) Math.sqrt(area / 26000f));
            final int B = 4; // fine cells per coarse block
            traceX = minX;
            traceY = minY;
            traceCell = cell;
            final float block = cell * B;
            final int bw = (int) Math.ceil((maxX - minX) / block) + 1, bh = (int) Math.ceil((maxY - minY) / block) + 1;
            final int cw = bw + 1, ch = bh + 1; // coarse corners
            if (coarse.length < cw * ch) {
                coarse = new float[cw * ch];
            }
            for (int j = 0; j < ch; j++) {
                for (int i = 0; i < cw; i++) {
                    coarse[j * cw + i] = field(minX + i * block, minY + j * block, n);
                }
            }
            if (near.length < bw * bh) {
                near = new byte[bw * bh];
            }
            final float margin = block * 0.75f;
            for (int j = 0; j < bh; j++) {
                for (int i = 0; i < bw; i++) {
                    final float a = coarse[j * cw + i], b = coarse[j * cw + i + 1], c = coarse[(j + 1) * cw + i], d = coarse[(j + 1) * cw + i + 1];
                    near[j * bw + i] = (byte) (Math.min(Math.min(Math.abs(a), Math.abs(b)), Math.min(Math.abs(c), Math.abs(d))) < margin ? 1 : 0);
                }
            }
            // fine values of the near blocks only (5 x 5 points each)
            final int fw = bw * B + 1;
            final int fh = bh * B + 1;
            if (fine.length < fw * fh) {
                fine = new float[fw * fh];
            }
            for (int j = 0; j < bh; j++) {
                for (int i = 0; i < bw; i++) {
                    if (near[j * bw + i] == 0) {
                        continue;
                    }
                    for (int y = 0; y <= B; y++) {
                        final int fy = j * B + y;
                        final float py = minY + fy * cell;
                        for (int x = 0; x <= B; x++) {
                            final int fx = i * B + x;
                            fine[fy * fw + fx] = field(minX + fx * cell, py, n);
                        }
                    }
                }
            }
            // segments
            segCount = 0;
            for (int j = 0; j < bh; j++) {
                for (int i = 0; i < bw; i++) {
                    if (near[j * bw + i] == 0) {
                        continue;
                    }
                    for (int y = 0; y < B; y++) {
                        final int fy = j * B + y;
                        for (int x = 0; x < B; x++) {
                            final int fx = i * B + x;
                            march(fx, fy, fw, minX, minY, cell);
                        }
                    }
                }
            }
            chain(fw);
        }

        // edge ids: horizontal edge (fx, fy) -> 2 * (fy * fw + fx), vertical edge -> 2 * (fy * fw + fx) + 1
        private void march(int fx, int fy, int fw, float ox, float oy, float cell) {
            final float a = fine[fy * fw + fx], b = fine[fy * fw + fx + 1];
            final float c = fine[(fy + 1) * fw + fx + 1], d = fine[(fy + 1) * fw + fx];
            int idx = 0;
            if (a < 0) idx |= 1;
            if (b < 0) idx |= 2;
            if (c < 0) idx |= 4;
            if (d < 0) idx |= 8;
            if (idx == 0 || idx == 15) {
                return;
            }
            final int top = 2 * (fy * fw + fx), bottom = 2 * ((fy + 1) * fw + fx), left = 2 * (fy * fw + fx) + 1, right = 2 * (fy * fw + fx + 1) + 1;
            // oriented so the inside is on the left of each segment: loops come out consistently wound
            switch (idx) {
                case 1: seg(left, top); break;
                case 2: seg(top, right); break;
                case 3: seg(left, right); break;
                case 4: seg(right, bottom); break;
                case 5: {
                    final float centre = (a + b + c + d) / 4;
                    if (centre < 0) {
                        seg(left, bottom);
                        seg(right, top);
                    } else {
                        seg(left, top);
                        seg(right, bottom);
                    }
                    break;
                }
                case 6: seg(top, bottom); break;
                case 7: seg(left, bottom); break;
                case 8: seg(bottom, left); break;
                case 9: seg(bottom, top); break;
                case 10: {
                    final float centre = (a + b + c + d) / 4;
                    if (centre < 0) {
                        seg(top, left);
                        seg(bottom, right);
                    } else {
                        seg(top, right);
                        seg(bottom, left);
                    }
                    break;
                }
                case 11: seg(bottom, right); break;
                case 12: seg(right, left); break;
                case 13: seg(right, top); break;
                case 14: seg(top, left); break;
            }
        }

        private void seg(int fromEdge, int toEdge) {
            if (segs.length < (segCount + 1) * 2) {
                segs = Arrays.copyOf(segs, Math.max(64, segs.length * 2));
            }
            segs[segCount * 2] = fromEdge;
            segs[segCount * 2 + 1] = toEdge;
            segCount++;
        }

        private int[] nextOf = new int[0];
        private int[] keys = new int[0];
        private int[] vals = new int[0];
        private boolean[] used = new boolean[0];
        private float[] loop = new float[0];

        /** Links the segments into closed loops (by shared edges) and adds them to the path, smoothed. */
        private void chain(int fw) {
            if (segCount == 0) {
                return;
            }
            // open-addressing map: edge id -> segment starting there
            int cap = Integer.highestOneBit(segCount * 2 - 1) << 1;
            cap = Math.max(16, cap * 2);
            if (keys.length < cap) {
                keys = new int[cap];
                vals = new int[cap];
            }
            Arrays.fill(keys, 0, cap, -1);
            final int mask = cap - 1;
            for (int s = 0; s < segCount; s++) {
                final int key = segs[s * 2];
                int h = (key * 0x9E3779B1) >>> 7 & mask;
                while (keys[h] != -1 && keys[h] != key) {
                    h = h + 1 & mask;
                }
                keys[h] = key;
                vals[h] = s;
            }
            if (nextOf.length < segCount) {
                nextOf = new int[segCount];
                used = new boolean[segCount];
            }
            Arrays.fill(used, 0, segCount, false);
            for (int s = 0; s < segCount; s++) {
                final int key = segs[s * 2 + 1];
                int h = (key * 0x9E3779B1) >>> 7 & mask;
                int found = -1;
                while (keys[h] != -1) {
                    if (keys[h] == key) {
                        found = vals[h];
                        break;
                    }
                    h = h + 1 & mask;
                }
                nextOf[s] = found;
            }
            for (int s = 0; s < segCount; s++) {
                if (used[s]) {
                    continue;
                }
                int count = 0;
                int cur = s;
                while (cur >= 0 && !used[cur]) {
                    used[cur] = true;
                    if (loop.length < (count + 1) * 2) {
                        loop = Arrays.copyOf(loop, Math.max(128, loop.length * 2));
                    }
                    edgePoint(segs[cur * 2], fw, loop, count * 2);
                    count++;
                    cur = nextOf[cur];
                }
                if (count >= 3) {
                    addSmooth(loop, count);
                }
            }
        }

        /** The zero crossing on a grid edge, linearly interpolated. */
        private void edgePoint(int edge, int fw, float[] out, int o) {
            final int base = edge >> 1;
            final int fx = base % fw, fy = base / fw;
            final float cell = traceCell, ox = traceX, oy = traceY;
            final float a = fine[base];
            if ((edge & 1) == 0) {
                final float b = fine[base + 1];
                final float t = a == b ? 0.5f : clamp(a / (a - b), 0, 1);
                out[o] = ox + (fx + t) * cell;
                out[o + 1] = oy + fy * cell;
            } else {
                final float b = fine[base + fw];
                final float t = a == b ? 0.5f : clamp(a / (a - b), 0, 1);
                out[o] = ox + fx * cell;
                out[o + 1] = oy + (fy + t) * cell;
            }
        }

        private float traceX, traceY, traceCell;

        /** Quadratic curves through the midpoints: a smooth outline without the grid's corners. */
        private void addSmooth(float[] p, int count) {
            final float sx = (p[(count - 1) * 2] + p[0]) / 2, sy = (p[(count - 1) * 2 + 1] + p[1]) / 2;
            path.moveTo(sx, sy);
            for (int i = 0; i < count; i++) {
                final int j = (i + 1) % count;
                final float mx = (p[i * 2] + p[j * 2]) / 2, my = (p[i * 2 + 1] + p[j * 2 + 1]) / 2;
                path.quadTo(p[i * 2], p[i * 2 + 1], mx, my);
            }
            path.close();
        }
        // endregion

        // region drawing

        /** Shadow, glass, tint and rim of the outline. Call after {@link #step}. */
        public void draw(Canvas canvas, @Nullable GlassFill fill, float alpha) {
            if (path.isEmpty() || alpha <= 0) {
                return;
            }
            final int a = (int) (255 * Math.min(1, alpha));
            if (Build.VERSION.SDK_INT >= 28 && Color.alpha(shadow) > 0) {
                canvas.save();
                canvas.clipOutPath(path);
                shadowPaint.setColor(0xFF000000);
                shadowPaint.setAlpha(a);
                shadowPaint.setShadowLayer(shadowRadius, 0, shadowDy, multAlpha(shadow, alpha));
                canvas.drawPath(path, shadowPaint);
                canvas.restore();
            }
            if (fill != null) {
                if (a < 255) {
                    canvas.saveLayerAlpha(bounds, a);
                    fill.fill(canvas, path, bounds);
                    canvas.restore();
                } else {
                    fill.fill(canvas, path, bounds);
                }
            }
            tintPaint.setColor(multAlpha(tint, alpha));
            canvas.drawPath(path, tintPaint);
            // rim: bright along the top, fading down; a soft darker edge at the bottom
            // unit gradients made once per colour set, stretched over the outline each frame (no allocation)
            if (rimShader == null || Float.isNaN(rimShaderTop)) {
                rimShaderTop = 0;
                rimShader = new LinearGradient(0, 0, 0.35f, 1, new int[]{rimTop, rimBottom, rimBottom}, new float[]{0, 0.55f, 1}, Shader.TileMode.CLAMP);
                lowShader = new LinearGradient(0, 0, 0, 1, new int[]{0, 0, low}, new float[]{0, 0.6f, 1}, Shader.TileMode.CLAMP);
                rimPaint.setShader(rimShader);
                lowPaint.setShader(lowShader);
            }
            shaderMatrix.setScale(Math.max(1, bounds.width()), Math.max(1, bounds.height()));
            shaderMatrix.postTranslate(bounds.left, bounds.top);
            rimShader.setLocalMatrix(shaderMatrix);
            lowShader.setLocalMatrix(shaderMatrix);
            rimPaint.setAlpha(a);
            lowPaint.setAlpha(a);
            canvas.save();
            canvas.clipPath(path);
            canvas.drawPath(path, lowPaint);
            canvas.restore();
            canvas.drawPath(path, rimPaint);
        }
        // endregion
    }
    // endregion

    // region math

    static float smin(float a, float b, float k) {
        if (k <= 0) {
            return Math.min(a, b);
        }
        final float h = Math.max(k - Math.abs(a - b), 0) / k;
        return Math.min(a, b) - h * h * k * 0.25f;
    }

    static float clamp(float v, float a, float b) {
        return v < a ? a : v > b ? b : v;
    }

    static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    static float smooth(float t) {
        return t * t * (3 - 2 * t);
    }

    static float smoothstep(float e0, float e1, float x) {
        return smooth(clamp((x - e0) / (e1 - e0), 0, 1));
    }

    static int multAlpha(int color, float alpha) {
        return Color.argb((int) (Color.alpha(color) * Math.max(0, Math.min(1, alpha))), Color.red(color), Color.green(color), Color.blue(color));
    }
    // endregion
}
