/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.ui;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Build;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;

import com.solegram.messenger.SolegramGlass;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundColorProvider;

import java.lang.ref.WeakReference;

/**
 * A popup menu that flows out of what opened it, as liquid: the panel is born round inside its origin (the button
 * pressed, or a small drop in the corner it opens from), flows out into its full shape and the items show as the
 * liquid reaches them; on dismiss the items go first and the panel flows back into the origin.
 * <p>
 * Two kinds of origin. A button the menu covers (the three dots of a header) is a drop of glass that drifts into
 * the menu's corner while the menu flows out of it, and is dropped once everything rests (the menu is then its
 * exact rounded rect). A button next to the menu (the menu below or above it) is a {@link SolegramLiquid.Blob#cut}:
 * the button itself stays visible, the menu comes out of its outline through a neck that pinches off.
 * <p>
 * The host (the menu's layout) calls {@link #frame} at the start of every draw, draws {@link #group} with
 * {@link #fill}, clips its content to the outline while it moves and fades the items with {@link #itemReveal}.
 * Coordinates are the host's pixels.
 */
public final class SolegramLiquidPopup {

    public static final int STATE_IDLE = 0, STATE_PENDING = 1, STATE_OPENING = 2, STATE_OPEN = 3, STATE_CLOSING = 4, STATE_CLOSED = 5;

    private static final int MODE_COVER = 0, MODE_CUT = 1;
    private static final float FADE = 0.09f; // seconds: a glass origin appears and goes this fast
    private static final float ITEMS_OUT = 0.12f; // seconds for the items to go before the panel flows back

    /** The window that holds the menu: it can make room round the menu for the neck to an origin outside it. */
    public interface Room {
        /**
         * Grows the window toward {@code needOnScreen} (no more than {@code reach} past its near edge) keeping the
         * menu where it is. True when the window changed (the menu waits a frame for it).
         */
        boolean solegramEnsureRoom(RectF needOnScreen, float reach);
    }

    // a blob that is never in the group: removing it only makes the group trace its outline again
    private static final SolegramLiquid.Blob NONE = new SolegramLiquid.Blob();

    private final View host;
    public final SolegramLiquid.Group group = new SolegramLiquid.Group();
    @Nullable
    public SolegramLiquid.GlassFill fill;

    private SolegramLiquid.Blob panel, origin;
    private int state = STATE_IDLE;
    private int mode;
    // the origin is a place on screen (a button): it stays there when the window moves
    private boolean originOnScreen;
    // else a small drop of the menu's own, in its corner or at its edge nearest to the origin (host pixels)
    private float dropX, dropY;
    private boolean dropCorner, dropRight, dropBottom;

    @Nullable
    private Room room;
    private int roomTries;
    @Nullable
    private WeakReference<View> originView;
    private final RectF originGiven = new RectF();
    private boolean hasOriginGiven;
    private float originGivenRadius = -1;
    private final int[] originViewLoc = new int[2];
    private boolean bottomHint;

    private final RectF panelRect = new RectF();
    private float panelRadius;
    // the origin on screen (where it was found, and the drop made of it), and in the host: where it is and where it
    // rests while the menu is open (inside the menu for a covered button)
    private final RectF originFound = new RectF(), originScreen = new RectF();
    private final RectF originLocal = new RectF(), originRestLocal = new RectF();
    private float originR;
    private final RectF tmp = new RectF();
    private final int[] loc = new int[2];
    private float lastHostX = Float.NaN, lastHostY = Float.NaN;

    private long beginTime, closeTime, fadeOutTime;
    private int pendingFrames, closeFrames;
    private boolean fadeIn;
    private boolean settled;
    @Nullable
    private Runnable onClosed;

    public SolegramLiquidPopup(View host) {
        this.host = host;
    }

    public int getState() {
        return state;
    }

    /** Drawn as liquid (from the window's show until the menu is gone). */
    public boolean isActive() {
        return state != STATE_IDLE;
    }

    /**
     * The window has just shown the menu; it flows out on its first frame.
     *
     * @param originView   what opened the menu, or null
     * @param originRect   where it is on screen (px), or null for the view's own bounds
     * @param originRadius the origin's corner radius, -1 to guess
     * @param bottomHint   the menu opens upward (for a menu without an origin: the corner it grows from)
     */
    public void prepare(@Nullable Room room, @Nullable View originView, @Nullable RectF originRect, float originRadius, boolean bottomHint) {
        finishClose();
        this.room = room;
        roomTries = 0;
        this.originView = originView != null ? new WeakReference<>(originView) : null;
        hasOriginGiven = originRect != null;
        if (originRect != null) {
            originGiven.set(originRect);
        }
        if (originView != null) {
            originView.getLocationOnScreen(originViewLoc);
        }
        originGivenRadius = originRadius;
        this.bottomHint = bottomHint;
        group.clear();
        group.k = AndroidUtilities.dp(18);
        group.springs(0.42f, 0.82f);
        panel = group.add();
        origin = group.add();
        settled = false;
        onClosed = null;
        fadeOutTime = 0;
        lastHostX = lastHostY = Float.NaN;
        pendingFrames = 0;
        closeFrames = 0;
        state = STATE_PENDING;
    }

    /** Glass colours of the app (tint, rim) over the picture of the screen or Telegram's blur. */
    public void glassColors(BlurredBackgroundColorProvider provider, boolean tinted) {
        final boolean dark = ColorUtils.calculateLuminance(provider.getBackgroundColor() | 0xFF000000) < 0.5;
        // the one menu glass (SolegramGlass, region Menus), as Telegram's blurred menus have it;
        // the group's own shadow is wider than the few dp a menu's window has round it: the menu draws a tighter one
        group.colors(
            tinted ? SolegramGlass.tint(provider.getBackgroundColor()) : 0,
            SolegramGlass.menuRimTop(dark),
            SolegramGlass.menuRimBottom(dark),
            SolegramGlass.menuLowEdge(dark),
            0
        );
        shadowColor = SolegramGlass.menuShadow(dark);
    }

    /** A plain menu (its own colour, no glass): a shadow, and a faint edge on dark menus. */
    public void plainColors(int menuColor) {
        final boolean dark = ColorUtils.calculateLuminance(menuColor | 0xFF000000) < 0.5;
        group.colors(0, dark ? 0x1AFFFFFF : 0, 0, 0, 0);
        shadowColor = dark ? 0x59000000 : 0x2E000000;
    }

    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int shadowColor;

    /** The menu's shadow (as tight as the shadow of Telegram's menus), under the outline; call before drawing the group. */
    public void drawShadow(Canvas canvas, float alpha) {
        if (Build.VERSION.SDK_INT < 28 || Color.alpha(shadowColor) == 0 || alpha <= 0 || group.path.isEmpty()) {
            return;
        }
        canvas.save();
        canvas.clipOutPath(group.path);
        shadowPaint.setColor(0xFF000000);
        shadowPaint.setAlpha((int) (255 * Math.min(1, alpha)));
        shadowPaint.setShadowLayer(AndroidUtilities.dp(SolegramGlass.MENU_SHADOW_RADIUS_DP), 0, AndroidUtilities.dp(SolegramGlass.MENU_SHADOW_DY_DP), ColorUtils.setAlphaComponent(shadowColor, (int) (Color.alpha(shadowColor) * Math.min(1, alpha))));
        canvas.drawPath(group.path, shadowPaint);
        canvas.restore();
    }

    // region origin

    /** Where the origin is on screen now: the view's place (it may have moved since), else the rect given. */
    private boolean findOrigin(RectF out) {
        final View v = originView != null ? originView.get() : null;
        if (v != null && v.isAttachedToWindow() && v.getWidth() > 0 && v.getHeight() > 0) {
            v.getLocationOnScreen(loc);
            if (hasOriginGiven) {
                out.set(originGiven);
                out.offset(loc[0] - originViewLoc[0], loc[1] - originViewLoc[1]);
            } else {
                out.set(loc[0], loc[1], loc[0] + v.getWidth() * v.getScaleX(), loc[1] + v.getHeight() * v.getScaleY());
            }
            return true;
        }
        if (hasOriginGiven) {
            out.set(originGiven);
            return true;
        }
        return false;
    }

    /**
     * Decides the drops on the first frame. False while the window makes room for the neck (or the menu has no
     * size yet): nothing is drawn, the next frame tries again.
     */
    private boolean begin(long now) {
        host.getLocationOnScreen(loc);
        final float hx = loc[0], hy = loc[1];
        final RectF p = panelRect;
        boolean has = findOrigin(tmp);
        if (has) {
            originFound.set(tmp);
            originLocal.set(tmp);
            originLocal.offset(-hx, -hy);
            originR = originGivenRadius >= 0 ? originGivenRadius : guessRadius(originLocal);
            if (Math.max(originLocal.width(), originLocal.height()) > AndroidUtilities.dp(96)) {
                // a whole cell or bar: the menu comes out of the point of its edge nearest to the menu
                if (originLocal.contains(p.centerX(), p.centerY())) {
                    has = false;
                } else {
                    final float d = Math.min(AndroidUtilities.dp(40), Math.min(originLocal.width(), originLocal.height()));
                    final float cx = clamp(p.centerX(), originLocal.left + d / 2, originLocal.right - d / 2);
                    final float cy = clamp(p.centerY(), originLocal.top + d / 2, originLocal.bottom - d / 2);
                    originLocal.set(cx - d / 2, cy - d / 2, cx + d / 2, cy + d / 2);
                    originR = d / 2;
                }
            }
        }
        originOnScreen = has;
        if (!has) {
            // opened from nowhere in particular: a small drop in the corner it opens from
            dropCorner = true;
            dropRight = hx + p.centerX() > AndroidUtilities.displaySize.x / 2f;
            dropBottom = bottomHint || hy + p.centerY() > AndroidUtilities.displaySize.y * 0.62f;
            placeDrop();
            mode = MODE_COVER;
        } else if (RectF.intersects(originLocal, p)) {
            // the menu covers its button: the button's drop drifts into the menu's corner as the menu flows out
            mode = MODE_COVER;
            fitInside(originLocal, originR, p, originRestLocal);
        } else if (gap(originLocal, p) > AndroidUtilities.dp(120)) {
            // too far for a neck: a small drop at the menu's edge nearest to it
            dropCorner = false;
            dropX = originLocal.centerX();
            dropY = originLocal.centerY();
            placeDrop();
            mode = MODE_COVER;
            originOnScreen = false;
        } else {
            mode = MODE_CUT;
            if (room != null && roomTries < 2) {
                tmp.set(originLocal);
                tmp.offset(hx, hy);
                if (room.solegramEnsureRoom(tmp, group.k + AndroidUtilities.dp(6))) {
                    roomTries++;
                    return false;
                }
            }
            originRestLocal.set(originLocal);
        }
        originScreen.set(originLocal);
        originScreen.offset(hx, hy);
        lastHostX = hx;
        lastHostY = hy;

        panel.rest(p.centerX(), p.centerY(), p.width(), p.height(), panelRadius);
        origin.cut = mode == MODE_CUT;
        origin.rest(originLocal.centerX(), originLocal.centerY(), originLocal.width(), originLocal.height(), originR).show();
        if (!originRestLocal.equals(originLocal)) {
            origin.rest(originRestLocal.centerX(), originRestLocal.centerY(), originRestLocal.width(), originRestLocal.height(), originR);
        }
        group.out(panel, origin, 0);
        fadeIn = mode == MODE_COVER;
        beginTime = now;
        state = STATE_OPENING;
        return true;
    }

    /** The menu's own small drop, where the panel is now (it may have changed its size since it opened). */
    private void placeDrop() {
        final RectF p = panelRect;
        final float d = Math.min(AndroidUtilities.dp(28), Math.min(p.width(), p.height()) * 0.6f);
        if (dropCorner) {
            dropInside(dropRight ? p.right : p.left, dropBottom ? p.bottom : p.top, d);
        } else {
            dropInside(dropX, dropY, d);
        }
    }

    /** A small round drop inside the panel, as near to (x, y) as it fits. */
    private void dropInside(float x, float y, float d) {
        final RectF p = panelRect;
        final float ins = AndroidUtilities.dp(2) + Math.max(0, panelRadius - d / 2) * 0.3f;
        final float cx = clamp(x, p.left + ins + d / 2, p.right - ins - d / 2);
        final float cy = clamp(y, p.top + ins + d / 2, p.bottom - ins - d / 2);
        originLocal.set(cx - d / 2, cy - d / 2, cx + d / 2, cy + d / 2);
        originRestLocal.set(originLocal);
        originR = d / 2;
    }

    /** The origin moved into the panel (no bigger than it, kept round if it is round), touching its nearest edges. */
    private void fitInside(RectF o, float r, RectF p, RectF out) {
        final boolean round = r >= Math.min(o.width(), o.height()) / 2 - 0.5f;
        final float ins = AndroidUtilities.dp(1) + Math.max(0, panelRadius - r) * 0.3f;
        float w = Math.max(1, Math.min(o.width(), p.width() - ins * 2));
        float h = Math.max(1, Math.min(o.height(), p.height() - ins * 2));
        if (round) {
            w = h = Math.min(w, h);
        }
        final float cx = clamp(o.centerX(), p.left + ins + w / 2, p.right - ins - w / 2);
        final float cy = clamp(o.centerY(), p.top + ins + h / 2, p.bottom - ins - h / 2);
        out.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2);
    }

    private static float guessRadius(RectF o) {
        final float m = Math.min(o.width(), o.height());
        // buttons and avatars are round (or capsules); bigger things have rounded corners
        return m <= AndroidUtilities.dp(64) ? m / 2 : AndroidUtilities.dp(12);
    }

    private static float gap(RectF a, RectF b) {
        final float dx = Math.max(0, Math.max(a.left - b.right, b.left - a.right));
        final float dy = Math.max(0, Math.max(a.top - b.bottom, b.top - a.bottom));
        return (float) Math.hypot(dx, dy);
    }

    /** A button stays where it is on screen when the window moves: its drop, in the host's pixels, from its place on screen. */
    private void originFromScreen(int hostX, int hostY) {
        originLocal.set(originScreen);
        originLocal.offset(-hostX, -hostY);
        if (mode == MODE_COVER) {
            fitInside(originLocal, originR, panelRect, originRestLocal);
        } else {
            originRestLocal.set(originLocal);
        }
    }

    private void followOrigin() {
        if (!originOnScreen) {
            return;
        }
        host.getLocationOnScreen(loc);
        if (loc[0] == lastHostX && loc[1] == lastHostY) {
            return;
        }
        lastHostX = loc[0];
        lastHostY = loc[1];
        originFromScreen(loc[0], loc[1]);
        if (!origin.isVisible() || !group.blobs.contains(origin)) {
            return;
        }
        if (mode == MODE_CUT) {
            origin.place(originLocal.centerX(), originLocal.centerY(), originLocal.width(), originLocal.height(), originR);
            group.remove(NONE);
        } else if (state == STATE_OPENING) {
            origin.rest(originRestLocal.centerX(), originRestLocal.centerY(), originRestLocal.width(), originRestLocal.height(), originR);
        } else {
            origin.rest(originLocal.centerX(), originLocal.centerY(), originLocal.width(), originLocal.height(), originR);
        }
    }
    // endregion

    /**
     * Advances to this frame: {@code panelNow} is where the panel is when open (host pixels), with its corner radius.
     * False while nothing should be drawn yet (the first frame, or the window making room).
     */
    public boolean frame(RectF panelNow, float radius, long now) {
        if (state == STATE_IDLE) {
            return false;
        }
        if (state == STATE_CLOSED) {
            return true;
        }
        final boolean changed = !panelRect.equals(panelNow) || panelRadius != radius;
        panelRect.set(panelNow);
        panelRadius = radius;
        if (state == STATE_PENDING) {
            pendingFrames++;
            if (panelRect.width() <= 1 || panelRect.height() <= 1 || !begin(now)) {
                return false;
            }
        } else if (changed) {
            if (state == STATE_OPENING) {
                panel.rest(panelRect.centerX(), panelRect.centerY(), panelRect.width(), panelRect.height(), panelRadius);
            } else if (state == STATE_OPEN) {
                // resized while open (a page of the menu sliding in): the panel follows exactly
                panel.place(panelRect.centerX(), panelRect.centerY(), panelRect.width(), panelRect.height(), panelRadius);
                group.remove(NONE);
            }
        }
        if (state == STATE_CLOSING) {
            closeFrames++;
        }
        if (state == STATE_OPENING || state == STATE_CLOSING) {
            followOrigin();
        }
        group.step(now);
        if (state == STATE_OPENING) {
            if (!group.isAnimating() && !fadingIn(now)) {
                // everything rests: the origin is inside the menu or apart from it, the menu is its exact shape
                group.remove(origin);
                state = STATE_OPEN;
                settled = true;
            }
        } else if (state == STATE_CLOSING) {
            if (mode == MODE_CUT) {
                // gone under its button: nothing of it shows any more
                if (!panel.isVisible() || inside(panel, origin, -Math.min(origin.r.value, Math.min(origin.w.value, origin.h.value) / 2) * 0.3f)) {
                    closed();
                }
            } else if (fadeOutTime == 0) {
                // nearly merged into the glass drop of its button: the drop goes as the rest of the menu flows in
                if (!panel.isVisible() || inside(panel, origin, Math.min(origin.w.value, origin.h.value) * 0.35f)) {
                    fadeOutTime = now;
                }
            } else if ((now - fadeOutTime) / 1e9f >= FADE) {
                closed();
            }
        }
        return true;
    }

    private boolean fadingIn(long now) {
        return fadeIn && (now - beginTime) / 1e9f < FADE;
    }

    /** The drop is all inside the other's bounds grown by {@code margin} (px, negative: shrunk). */
    private static boolean inside(SolegramLiquid.Blob b, SolegramLiquid.Blob o, float margin) {
        if (!o.isVisible()) {
            return false;
        }
        return b.x.value - b.w.value / 2 >= o.x.value - o.w.value / 2 - margin
            && b.x.value + b.w.value / 2 <= o.x.value + o.w.value / 2 + margin
            && b.y.value - b.h.value / 2 >= o.y.value - o.h.value / 2 - margin
            && b.y.value + b.h.value / 2 <= o.y.value + o.h.value / 2 + margin;
    }

    /** Waited too many frames for a size or for room: the host gives up and shows the usual menu. */
    public boolean pendingTooLong() {
        return state == STATE_PENDING && pendingFrames > 30;
    }

    /** Frames drawn while flowing back (the window's safety timeout waits while they still come). */
    public int closeFrames() {
        return closeFrames;
    }

    /** Flowing back (or flowed back, the window about to go): the menu takes no touches. */
    public boolean isClosing() {
        return state == STATE_CLOSING || state == STATE_CLOSED;
    }

    /** Still moving: the host keeps drawing frames. */
    public boolean isAnimating(long now) {
        switch (state) {
            case STATE_PENDING:
            case STATE_OPENING:
            case STATE_CLOSING:
                return true;
            case STATE_OPEN:
                return group.isAnimating();
            default:
                return false;
        }
    }

    /** True once, on the frame the menu came to rest open: the host puts its items back as they were. */
    public boolean consumeSettled() {
        final boolean s = settled;
        settled = false;
        return s;
    }

    /** Opacity of the whole liquid (a glass origin appears and goes with a short fade). */
    public float alpha(long now) {
        if (state == STATE_CLOSED) {
            return 0;
        }
        float a = 1;
        if (fadeIn) {
            a = Math.min(a, (now - beginTime) / 1e9f / FADE);
        }
        if (fadeOutTime != 0) {
            a = Math.min(a, 1 - (now - fadeOutTime) / 1e9f / FADE);
        }
        return clamp(a, 0, 1);
    }

    /** The content follows the liquid (clipped to it) while it moves. */
    public boolean isMoving() {
        return state == STATE_OPENING || state == STATE_CLOSING;
    }

    // region items

    /**
     * How much of an item (centre cx, cy and height h, host pixels) shows now: items appear as the liquid reaches
     * them, and go before the panel flows back, the ones far from the origin a beat first.
     */
    public float itemReveal(float cx, float cy, float h, long now) {
        if (state == STATE_OPEN || state == STATE_IDLE) {
            return 1;
        }
        if (state == STATE_PENDING || state == STATE_CLOSED || panel == null || !panel.isVisible()) {
            return 0;
        }
        final float pw = panel.w.value, ph = panel.h.value, px = panel.x.value, py = panel.y.value;
        final float depthY = Math.min(cy - (py - ph / 2), py + ph / 2 - cy);
        final float depthX = Math.min(cx - (px - pw / 2), px + pw / 2 - cx);
        float g = smoothstep(-0.25f * h, 0.5f * h, depthY) * smoothstep(-AndroidUtilities.dp(6), AndroidUtilities.dp(30), depthX);
        if (state == STATE_CLOSING) {
            final float far = clamp(Math.abs(cy - originLocal.centerY()) / Math.max(1, panelRect.height()), 0, 1);
            final float t = (now - closeTime) / 1e9f / ITEMS_OUT;
            g = Math.min(g, 1 - smoothstep(0, 1, (t - (1 - far) * 0.4f) / 0.6f));
        }
        return g;
    }

    /** How far an item that is not shown yet sits toward the origin (it slides into place as it shows). */
    public float itemShift(float cy) {
        return cy > originLocal.centerY() ? -AndroidUtilities.dp(6) : AndroidUtilities.dp(6);
    }
    // endregion

    // region closing

    /**
     * The menu flows back: the items go, the panel flows into the origin, then {@code onClosed} runs (posted, once).
     * Dismissing while it still opens turns it around from where it is.
     */
    public void close(Runnable onClosed) {
        if (state == STATE_CLOSING) {
            this.onClosed = onClosed;
            return;
        }
        if (state != STATE_OPENING && state != STATE_OPEN) {
            state = STATE_CLOSED;
            AndroidUtilities.runOnUIThread(onClosed);
            return;
        }
        this.onClosed = onClosed;
        final boolean wasOpen = state == STATE_OPEN;
        if (originOnScreen) {
            // the button may have moved since it opened the menu (the list under the menu scrolled)
            if (findOrigin(tmp)) {
                originScreen.offset(tmp.left - originFound.left, tmp.top - originFound.top);
                originFound.set(tmp);
            }
            host.getLocationOnScreen(loc);
            lastHostX = loc[0];
            lastHostY = loc[1];
            originFromScreen(loc[0], loc[1]);
        } else if (wasOpen) {
            placeDrop();
        }
        if (wasOpen || !group.blobs.contains(origin)) {
            // the origin was dropped at rest: back where it rested, inside the menu or at its button
            origin = group.add();
            origin.cut = mode == MODE_CUT;
            final RectF from = mode == MODE_COVER ? originRestLocal : originLocal;
            origin.rest(from.centerX(), from.centerY(), from.width(), from.height(), originR).show();
        }
        // a covered button's drop drifts back out to the button as the menu flows into it
        origin.rest(originLocal.centerX(), originLocal.centerY(), originLocal.width(), originLocal.height(), originR);
        // a little quicker than the opening: menus are closed as often as they are opened
        group.springs(0.32f, 0.88f);
        group.in(panel, origin, wasOpen ? 0.04f : 0f, null);
        closeTime = System.nanoTime();
        fadeOutTime = 0;
        state = STATE_CLOSING;
        host.invalidate();
    }

    private void closed() {
        state = STATE_CLOSED;
        final Runnable r = onClosed;
        onClosed = null;
        if (r != null) {
            // not inside the draw: the window goes away on the next turn of the loop
            AndroidUtilities.runOnUIThread(r);
        }
    }

    /** A window waiting for the menu to flow back closes now (the menu is taken away or shown again). */
    public void finishClose() {
        if (state == STATE_CLOSING || onClosed != null) {
            closed();
        }
    }

    /** Back to the usual menu: nothing drawn as liquid any more. */
    public void reset() {
        state = STATE_IDLE;
        onClosed = null;
        fadeOutTime = 0;
        settled = false;
        group.clear();
        fill = null;
    }
    // endregion

    // region math

    private static float clamp(float v, float a, float b) {
        if (a > b) {
            return (a + b) / 2;
        }
        return v < a ? a : v > b ? b : v;
    }

    private static float smoothstep(float e0, float e1, float x) {
        final float t = clamp((x - e0) / (e1 - e0), 0, 1);
        return t * t * (3 - 2 * t);
    }
    // endregion
}
