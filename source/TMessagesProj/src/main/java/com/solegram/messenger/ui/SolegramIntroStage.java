/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.Premium.GLIcon.GLIconRenderer;
import org.telegram.ui.Components.Premium.GLIcon.GLIconTextureView;
import org.telegram.ui.Components.Premium.GLIcon.Icon3D;
import org.telegram.ui.Components.Premium.StarParticlesView;
import org.telegram.ui.Components.RLottieImageView;
import org.telegram.ui.Stars.StarsIntroActivity;

/**
 * The pictures of the welcome screen, one for each page: Solegram's avatar first, then an animated sticker in
 * Telegram's style that tells what the page is about. They follow the finger: the one going away shrinks and fades
 * to its side, the next comes in from the other; the sticker of the page that settled plays.
 */
public class SolegramIntroStage extends FrameLayout {

    /** For each page after the first: what it says - glass, chats, music, plugins, protection. */
    private static final int[] STICKERS = {
        R.raw.wallet_diamond_blue,  // liquid glass: a crystal
        R.raw.utyan_empty,          // better in chats: the detective duck reading (summaries, search by meaning)
        R.raw.utyan_schedule,       // music and profile: ducks in headphones over a sound wave
        R.raw.utyan_draw,           // plugins: the duck making something of its own
        R.raw.utyan_passcode,       // protection: the duck at a safe with a passcode
    };
    private static final int SIZE_DP = 150;

    private final SolegramIntroView logo;
    /** The first page: the logo in 3D (turns under the finger, sparks behind it); the flat logo until it draws, or if GL fails. */
    private final FrameLayout logo3d;
    private StarParticlesView sparks;
    private GLIconTextureView glLogo;
    private static final int GL_SIZE_DP = 180;
    private final RLottieImageView[] stickers = new RLottieImageView[STICKERS.length];
    private int playing = -1;
    private float page;
    private boolean hidden;

    public SolegramIntroStage(Context context) {
        super(context);
        setClipChildren(false);
        logo3d = new FrameLayout(context);
        logo3d.setClipChildren(false);
        addView(logo3d, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        logo = new SolegramIntroView(context);
        logo3d.addView(logo, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        try {
            sparks = StarsIntroActivity.makeParticlesView(context, 60, 3);
            sparks.setAlpha(0f);
            logo3d.addView(sparks, LayoutHelper.createFrame(300, 240, Gravity.CENTER));
            glLogo = new GLIconTextureView(context, GLIconRenderer.FRAGMENT_STYLE, Icon3D.TYPE_SOLEGRAM) {
                @Override
                protected void onRenderError() {
                    gl3dFailed();
                }
            };
            glLogo.setStarParticlesView(sparks);
            glLogo.setAlpha(0f);
            logo3d.addView(glLogo, LayoutHelper.createFrame(GL_SIZE_DP, GL_SIZE_DP, Gravity.CENTER));
            glLogo.whenReady(() -> {
                // the 3D logo drew its first frame: it takes the flat one's place
                if (glLogo == null) return;
                glLogo.animate().alpha(1f).setDuration(260).start();
                sparks.animate().alpha(1f).setDuration(420).start();
                logo.animate().alpha(0f).setDuration(200).withEndAction(() -> {
                    if (glLogo != null) logo.setVisibility(GONE);
                }).start();
            });
        } catch (Throwable e) {
            FileLog.e(e);
            gl3dFailed();
        }
        for (int i = 0; i < STICKERS.length; i++) {
            final RLottieImageView sticker = new RLottieImageView(context);
            sticker.setAnimation(STICKERS[i], SIZE_DP, SIZE_DP);
            sticker.setAutoRepeat(true);
            sticker.setVisibility(INVISIBLE);
            final int index = i + 1;
            // a tap plays it again, as a sticker does
            sticker.setOnClickListener(v -> replay(index));
            stickers[i] = sticker;
            addView(sticker, LayoutHelper.createFrame(SIZE_DP, SIZE_DP, Gravity.CENTER));
        }
        setPage(0);
    }

    /** The pager's position: the page and how far it is swiped towards the next one. */
    public void setPage(float page) {
        this.page = page;
        logo.setPage(page);
        place(logo3d, 0);
        if (glLogo != null) {
            // nothing to draw while another page is on screen
            glLogo.setPaused(hidden || Math.abs(page) > 0.98f);
        }
        for (int i = 0; i < stickers.length; i++) {
            place(stickers[i], i + 1);
        }
        final int settled = Math.round(page);
        if (Math.abs(page - settled) < 0.02f && settled != playing) {
            replay(settled);
        }
    }

    /** Solegram's flat logo (half-sun over two bars) in white on a transparent square, for the sparks. */
    public static android.graphics.Bitmap logoBitmap(int size) {
        final android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(Math.max(1, size), Math.max(1, size), android.graphics.Bitmap.Config.ARGB_8888);
        final android.graphics.Canvas c = new android.graphics.Canvas(b);
        final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFFFFFFFF);
        // the logo's own box: 34.9 36.295 38.2 x 35.41, centred in the square
        final float k = size / 38.2f;
        c.translate(-34.9f * k, -36.295f * k + (size - 35.41f * k) / 2f);
        c.scale(k, k);
        final android.graphics.Path sun = new android.graphics.Path();
        sun.addArc(new android.graphics.RectF(35.4f, 36.795f, 72.6f, 73.995f), 180, 180);
        sun.lineTo(72.6f, 56.635f);
        sun.lineTo(35.4f, 56.635f);
        sun.close();
        c.drawPath(sun, p);
        c.drawRoundRect(new android.graphics.RectF(38.345f, 59.115f, 69.655f, 63.765f), 2.325f, 2.325f, p);
        c.drawRoundRect(new android.graphics.RectF(45.165f, 66.555f, 62.835f, 71.205f), 2.325f, 2.325f, p);
        return b;
    }

    /** The screen went away (or came back): the 3D logo stops drawing meanwhile. */
    public void setHidden(boolean hidden) {
        this.hidden = hidden;
        if (glLogo != null) {
            glLogo.setPaused(hidden || Math.abs(page) > 0.98f);
            glLogo.setDialogVisible(hidden);
        }
    }

    private void gl3dFailed() {
        final View gl = glLogo, sp = sparks;
        glLogo = null;
        sparks = null;
        logo.animate().cancel();
        logo.setVisibility(VISIBLE);
        logo.setAlpha(1f);
        for (View v : new View[] { gl, sp }) {
            if (v != null && v.getParent() instanceof ViewGroup) {
                ((ViewGroup) v.getParent()).removeView(v);
            }
        }
    }

    /** True when a finger put down here (screen coordinates) should turn the 3D logo rather than swipe the pages. */
    public boolean takesTouch(float rawX, float rawY) {
        if (glLogo == null || glLogo.getAlpha() < 0.5f || logo3d.getAlpha() < 0.9f || !glLogo.isAttachedToWindow()) {
            return false;
        }
        final int[] at = new int[2];
        glLogo.getLocationOnScreen(at);
        final float pad = dp(8);
        return rawX >= at[0] - pad && rawX <= at[0] + glLogo.getWidth() + pad && rawY >= at[1] - pad && rawY <= at[1] + glLogo.getHeight() + pad;
    }

    /** One event of a gesture that {@link #takesTouch} kept for the logo. */
    public void touchLogo(MotionEvent screenEvent) {
        if (glLogo == null) return;
        final int[] at = new int[2];
        glLogo.getLocationOnScreen(at);
        final MotionEvent e = MotionEvent.obtain(screenEvent);
        e.setLocation(screenEvent.getRawX() - at[0], screenEvent.getRawY() - at[1]);
        glLogo.dispatchTouchEvent(e);
        e.recycle();
    }

    private void place(View view, int index) {
        final float d = index - page;
        final float away = Math.min(1f, Math.abs(d));
        final float alpha = Math.max(0f, 1f - away * 1.6f);
        view.setAlpha(alpha);
        view.setVisibility(alpha > 0.01f ? VISIBLE : INVISIBLE);
        view.setTranslationX(d * dp(110));
        final float scale = 0.78f + 0.22f * CubicBezierInterpolator.EASE_OUT.getInterpolation(1f - away);
        view.setScaleX(scale);
        view.setScaleY(scale);
    }

    /** The sticker of this page from its start; the others stop (only one plays at a time). */
    private void replay(int index) {
        playing = index;
        for (int i = 0; i < stickers.length; i++) {
            final RLottieImageView sticker = stickers[i];
            if (i + 1 == index) {
                if (sticker.getAnimatedDrawable() != null) {
                    sticker.getAnimatedDrawable().setCurrentFrame(0, false);
                }
                sticker.playAnimation();
            } else {
                sticker.stopAnimation();
            }
        }
    }
}
